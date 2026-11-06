package br.com.neurelix.vcam.shim;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pontos Camera2 reescritos no app alvo (CameraX e Camera2 direto). Cada Surface de saída do app
 * recebe um tratamento conforme o formato do ImageReader que a criou:
 *  - SurfaceTexture/RGBA (preview): o NEURELIX desenha direto na Surface (EGL).
 *  - YUV_420_888 (análise/YOLO): alimentador converte o quadro virtual para YUV e escreve (ImageWriter).
 *  - JPEG (captura): quando a câmera real dispara uma foto, injeta o JPEG virtual com o mesmo timestamp.
 * A sessão real continua aberta (3A) e escreve em Surfaces descartáveis do mesmo tamanho.
 */
public final class CamHook {
    private static final String TAG = "NVC-CAMHOOK";
    private static final long RETRY_MS = 3000;
    private static final long FEED_INTERVAL_MS = 100;   // ~10 fps para análise (captureJpeg é o caminho atual)

    private static final Object LOCK = new Object();
    private static NeurelixSession neurelix;
    private static long lastFailMs;
    // listener dos readers de drain/trigger roda aqui, não na main: a main fica ocupada durante a
    // captura (UI, som, flash) e deixa de esvaziar o drain a tempo -> buffer perdido -> CameraX derruba o app
    private static final Handler IO = ioHandler();
    private static Handler ioHandler() {
        HandlerThread t = new HandlerThread("nvc-cam-io");
        t.start();
        return new Handler(t.getLooper());
    }

    private static final class Info {
        final int format, w, h, maxImages;
        Info(int f, int w, int h, int maxImages) { format = f; this.w = w; this.h = h; this.maxImages = maxImages; }
    }
    private static final Map<Surface, Info> INFO = new IdentityHashMap<>();        // do ImageReader do app
    private static final Map<Surface, Surface> REAL = new IdentityHashMap<>();     // app -> superfície da sessão real
    private static final Map<Surface, Object> RESOURCES = new IdentityHashMap<>(); // app -> ImageReader/feeder a liberar
    private static final Map<Surface, Integer> STREAMS = new IdentityHashMap<>(); // app -> stream NEURELIX (EGL)

    private CamHook() { }

    static {
        // O CameraX monta seu próprio mapa Surface->streamId a partir da configuração ORIGINAL da
        // sessão. Como trocamos a Surface real por uma de drenagem (drain) depois que esse mapa já foi
        // montado, um evento de perda de buffer no stream trocado faz o CameraX derrubar o app com
        // IllegalStateException("Unable to find the streamId..."). Não dá pra evitar o evento (vem da
        // câmera real); só dá pra impedir que ele mate o processo.
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            String msg = e.getMessage();
            if (e instanceof IllegalStateException && msg != null && msg.contains("streamId")) {
                Log.w(TAG, "falha interna do CameraX pós-redirecionamento ignorada (" + t.getName() + "): " + e);
                return;
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    // ---- ponto 0: ImageReader.newInstance(w, h, format, maxImages) ----
    public static ImageReader newImageReader(int w, int h, int format, int maxImages) {
        // Tentativa de usage com CPU_WRITE: travou o CameraX neste aparelho (câmera nunca abre). Revertido.
        ImageReader r = ImageReader.newInstance(w, h, format, maxImages);
        synchronized (LOCK) { INFO.put(r.getSurface(), new Info(format, w, h, maxImages)); }
        return r;
    }

    // ---- ponto 1: CaptureRequest.Builder.addTarget(Surface) ----
    public static void addTarget(CaptureRequest.Builder b, Surface s) {
        Surface m;
        synchronized (LOCK) { m = REAL.get(s); }
        b.addTarget(m != null ? m : s);
    }

    // ---- ponto 2: createCaptureSession(List<Surface>, StateCallback, Handler) ----
    public static void createCaptureSession(CameraDevice d, List<Surface> outs, CameraCaptureSession.StateCallback cb, Handler h)
            throws CameraAccessException {
        List<Surface> real = new ArrayList<>(outs.size());
        List<Surface> apps = new ArrayList<>();
        for (Surface app : outs) {
            Surface r = redirect(app);
            real.add(r != null ? r : app);
            if (r != null) apps.add(app);
        }
        d.createCaptureSession(real, wrap(cb, apps), h);
    }

    // ---- ponto 3: createCaptureSession(SessionConfiguration) ----
    public static void createCaptureSession(CameraDevice d, SessionConfiguration sc) throws CameraAccessException {
        List<OutputConfiguration> out = new ArrayList<>();
        List<Surface> apps = new ArrayList<>();
        for (OutputConfiguration oc : sc.getOutputConfigurations()) out.add(redirect(oc, apps));
        SessionConfiguration nsc = new SessionConfiguration(sc.getSessionType(), out, sc.getExecutor(), wrap(sc.getStateCallback(), apps));
        if (sc.getSessionParameters() != null) nsc.setSessionParameters(sc.getSessionParameters());
        if (sc.getInputConfiguration() != null) nsc.setInputConfiguration(sc.getInputConfiguration());
        d.createCaptureSession(nsc);
    }

    // ---- ponto 4: createCaptureSessionByOutputConfigurations(List<OutputConfiguration>, StateCallback, Handler) ----
    public static void createCaptureSessionByOutputConfigurations(CameraDevice d, List<OutputConfiguration> ocs,
                                                                  CameraCaptureSession.StateCallback cb, Handler h) throws CameraAccessException {
        List<OutputConfiguration> out = new ArrayList<>(ocs.size());
        List<Surface> apps = new ArrayList<>();
        for (OutputConfiguration oc : ocs) out.add(redirect(oc, apps));
        d.createCaptureSessionByOutputConfigurations(out, wrap(cb, apps), h);
    }

    // ------------------------------------------------------------------------------------------

    private static OutputConfiguration redirect(OutputConfiguration oc, List<Surface> apps) {
        Surface app = oc.getSurface();          // null em configurações com superfícies compartilhadas
        if (app == null) return oc;
        Surface r = redirect(app);
        if (r == null) return oc;
        apps.add(app);
        return new OutputConfiguration(r);
    }

    /** Devolve a superfície que a sessão real usa no lugar de [app], ou null para manter [app]. */
    private static Surface redirect(Surface app) {
        Info info;
        synchronized (LOCK) {
            Surface existing = REAL.get(app);
            if (existing != null) return existing;
            info = INFO.get(app);
        }
        if (info == null) return redirectEgl(app);                      // preview (SurfaceTexture)
        if (info.format == ImageFormat.YUV_420_888) return redirectYuv(app, info);
        // JPEG: fica com a câmera real (um ImageWriter não sabe alocar o buffer BLOB). Os bytes da foto
        // são trocados depois, em acquireLatestImage/acquireNextImage, dentro do buffer já alocado.
        if (info.format == ImageFormat.JPEG) return null;
        if (info.format == PixelFormat.RGBA_8888) return redirectEgl(app);
        Log.i(TAG, "saída de formato " + Integer.toHexString(info.format) + " mantida na câmera real");
        return null;
    }

    private static Surface redirectEgl(Surface app) {
        NeurelixSession s = session();
        if (s == null) return null;
        try {
            // a sessão real NÃO pode usar a Surface do app: câmera e NEURELIX disputariam o mesmo produtor
            // ("connect: already connected" / "Unsupported set of inputs/outputs"). Ela recebe um drain descartável.
            ImageReader drain = drainReader(640, 480, ImageFormat.YUV_420_888);
            int id = s.attach(app, 1280, 720, 30, 0);               // FIT_CROP: NEURELIX desenha direto na Surface do app
            synchronized (LOCK) {
                STREAMS.put(app, id);
                REAL.put(app, drain.getSurface());
                RESOURCES.put(app, new Object[] { drain });
            }
            Log.i(TAG, "preview redirecionado para o NEURELIX (stream " + id + "), sessão real usa drain");
            return drain.getSurface();
        } catch (Throwable t) {
            Log.i(TAG, "saída mantida na câmera real: " + t.getMessage());
            return null;
        }
    }

    /** Análise: sessão real recebe um drain YUV; o alimentador escreve quadros virtuais na Surface do app. */
    private static Surface redirectYuv(Surface app, Info info) {
        NeurelixSession s = session();
        if (s == null) return null;
        // diagnóstico: com /data/local/tmp/nvc_nofeed presente, o alimentador de análise não roda
        boolean noFeed = new java.io.File("/data/local/tmp/nvc_nofeed").exists();
        ImageReader drain = null;
        try {
            drain = drainReader(info.w, info.h, ImageFormat.YUV_420_888);
            if (noFeed) {
                synchronized (LOCK) { REAL.put(app, drain.getSurface()); RESOURCES.put(app, new Object[] { drain }); }
                Log.i(TAG, "análise: alimentador DESLIGADO (diagnóstico)");
                return drain.getSurface();
            }
            ImageWriter writer = ImageWriter.newInstance(app, info.maxImages, ImageFormat.YUV_420_888);   // mesmo maxImages do ImageReader real
            Feeder feeder = new Feeder(app, writer, info.w, info.h);
            feeder.start();
            synchronized (LOCK) {
                REAL.put(app, drain.getSurface());
                RESOURCES.put(app, new Object[] { drain, feeder, writer });
            }
            Log.i(TAG, "análise YUV " + info.w + "x" + info.h + " alimentada com quadros virtuais");
            return drain.getSurface();
        } catch (Throwable t) {
            Log.e(TAG, "alimentador de análise falhou, usando drain (não a câmera real)", t);
            // mesmo motivo do JPEG: a Surface do app nunca pode ficar presa direto na câmera real
            if (drain != null) {
                synchronized (LOCK) { REAL.put(app, drain.getSurface()); RESOURCES.put(app, new Object[] { drain }); }
                return drain.getSurface();
            }
            return null;
        }
    }

    // ---- ponto 5/6: ImageReader.acquireLatestImage()/acquireNextImage() ----
    // A foto JPEG real chega aqui, já no buffer alocado (e corretamente dimensionado) pela câmera real.
    // Troca o conteúdo desse buffer pelo JPEG virtual em vez de criar um buffer novo (ImageWriter não
    // sabe alocar BLOB: "gralloc failed to allocate ... format 33 size 0" é sempre o resultado).
    public static Image acquireLatestImage(ImageReader r) { return maybeSwapJpeg(r, r.acquireLatestImage()); }
    public static Image acquireNextImage(ImageReader r) { return maybeSwapJpeg(r, r.acquireNextImage()); }

    private static Image maybeSwapJpeg(ImageReader r, Image img) {
        if (img == null) return img;
        Info info;
        synchronized (LOCK) { info = INFO.get(r.getSurface()); }
        if (info == null || info.format != ImageFormat.JPEG) return img;
        NeurelixSession s = session();
        if (s == null) return img;
        try {
            byte[] jpg = s.captureJpeg(95);
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            int capacity = buf.capacity();
            final int FOOTER = 8;   // camera3_jpeg_blob_t: 2 bytes id + 2 padding + 4 bytes jpeg_size
            if (jpg.length + FOOTER > capacity) {
                Log.w(TAG, "JPEG virtual (" + jpg.length + ") não cabe no buffer real (" + capacity + "), foto mantida real");
                return img;
            }
            // Buffers BLOB/JPEG guardam o tamanho real num rodapé de 8 bytes no FINAL do buffer
            // (convenção camera3_jpeg_blob_t), não no limit() do ByteBuffer. Quem lê a foto usa esse
            // rodapé; se ele continuar apontando pro tamanho da foto real, a leitura estoura o que
            // escrevemos (BufferUnderflowException). Por isso preenche o resto e escreve o rodapé certo.
            buf.order(java.nio.ByteOrder.nativeOrder());
            buf.position(0);
            buf.put(jpg);
            int pad = capacity - FOOTER - jpg.length;
            if (pad > 0) buf.put(new byte[pad]);
            buf.position(capacity - FOOTER);
            buf.putInt(0x00FF);      // jpeg_blob_id
            buf.putInt(jpg.length);  // jpeg_size real do JPEG virtual
            buf.position(0);
            buf.limit(capacity);     // buffer continua no tamanho total: quem lê usa o rodapé, não limit()
            Log.i(TAG, "foto: bytes reais trocados pelo JPEG virtual (" + jpg.length + " bytes)");
        } catch (Throwable t) {
            Log.e(TAG, "falha ao trocar bytes da foto, mantida real", t);
        }
        return img;
    }

    private static ImageReader drainReader(int w, int h, int format) {
        ImageReader r = ImageReader.newInstance(w, h, format, 6);   // folga extra p/ não perder buffer durante captura
        r.setOnImageAvailableListener(rd -> {
            try (Image img = rd.acquireLatestImage()) { /* descarta */ } catch (Throwable ignored) { }
        }, IO);
        return r;
    }

    /** Thread que puxa o quadro virtual do NEURELIX, converte para YUV_420_888 e escreve no ImageWriter. */
    private static final class Feeder extends Thread {
        private final Surface app; private final ImageWriter writer; private final int w, h;
        private volatile boolean running = true;
        private long frames;
        private int falhasSeguidas;
        Feeder(Surface app, ImageWriter writer, int w, int h) {
            super("nvc-yuv-feed"); this.app = app; this.writer = writer; this.w = w; this.h = h;
        }
        void stopFeed() { running = false; }
        @Override public void run() {
            while (running) {
                try {
                    NeurelixSession s = session();
                    if (s != null) {
                        byte[] jpg = s.captureJpeg(80);
                        Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                        Bitmap sc = Bitmap.createScaledBitmap(b, w, h, true);
                        if (sc != b) b.recycle();
                        queueYuv(sc);
                        sc.recycle();
                        if (++frames % 50 == 0) Log.i(TAG, "análise: " + frames + " quadros virtuais entregues");
                        falhasSeguidas = 0;
                    }
                    Thread.sleep(FEED_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "quadro de análise falhou: " + t);
                    if (++falhasSeguidas >= 5) {
                        Log.e(TAG, "análise desistiu após 5 falhas seguidas: câmera real mantida na análise");
                        return;
                    }
                    try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                }
            }
        }
        private void queueYuv(Bitmap bmp) {
            Image img = writer.dequeueInputImage();
            try {
                Image.Plane[] pl = img.getPlanes();
                ByteBuffer y = pl[0].getBuffer(); int yRow = pl[0].getRowStride(), yPix = pl[0].getPixelStride();
                ByteBuffer u = pl[1].getBuffer(); int uRow = pl[1].getRowStride(), uPix = pl[1].getPixelStride();
                ByteBuffer v = pl[2].getBuffer(); int vRow = pl[2].getRowStride(), vPix = pl[2].getPixelStride();
                int[] px = new int[w * h];
                bmp.getPixels(px, 0, w, 0, 0, w, h);
                for (int j = 0; j < h; j++) {
                    for (int i = 0; i < w; i++) {
                        int c = px[j * w + i];
                        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
                        int yy = ((66 * r + 129 * g + 25 * bl + 128) >> 8) + 16;
                        y.put(j * yRow + i * yPix, (byte) clamp(yy));
                        if ((j & 1) == 0 && (i & 1) == 0) {
                            int uu = ((-38 * r - 74 * g + 112 * bl + 128) >> 8) + 128;
                            int vv = ((112 * r - 94 * g - 18 * bl + 128) >> 8) + 128;
                            u.put((j / 2) * uRow + (i / 2) * uPix, (byte) clamp(uu));
                            v.put((j / 2) * vRow + (i / 2) * vPix, (byte) clamp(vv));
                        }
                    }
                }
                img.setTimestamp(System.nanoTime());
                writer.queueInputImage(img);
            } catch (Throwable t) {
                img.close();
                throw t;
            }
        }
        private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
    }

    private static CameraCaptureSession.StateCallback wrap(final CameraCaptureSession.StateCallback cb, final List<Surface> apps) {
        return new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession s) { if (cb != null) cb.onConfigured(s); }
            @Override public void onConfigureFailed(CameraCaptureSession s) { release(apps); if (cb != null) cb.onConfigureFailed(s); }
            @Override public void onReady(CameraCaptureSession s) { if (cb != null) cb.onReady(s); }
            @Override public void onActive(CameraCaptureSession s) { if (cb != null) cb.onActive(s); }
            @Override public void onClosed(CameraCaptureSession s) { release(apps); if (cb != null) cb.onClosed(s); }
            @Override public void onSurfacePrepared(CameraCaptureSession s, Surface surface) { if (cb != null) cb.onSurfacePrepared(s, surface); }
            @Override public void onCaptureQueueEmpty(CameraCaptureSession s) { if (cb != null) cb.onCaptureQueueEmpty(s); }
        };
    }

    private static void release(List<Surface> apps) {
        NeurelixSession s = neurelix;
        for (Surface a : apps) {
            synchronized (LOCK) {
                Integer id = STREAMS.remove(a);
                if (id != null && s != null) s.detach(id);
                Object[] res = (Object[]) RESOURCES.remove(a);
                if (res != null) {
                    for (Object o : res) {
                        if (o instanceof Feeder) ((Feeder) o).stopFeed();
                        else if (o instanceof ImageReader) ((ImageReader) o).close();
                        else if (o instanceof ImageWriter) ((ImageWriter) o).close();
                    }
                }
                REAL.remove(a);
            }
        }
    }

    /** Sessão NEURELIX compartilhada pelo processo. Tenta de novo após RETRY_MS se o serviço não respondeu. */
    private static NeurelixSession session() {
        synchronized (LOCK) {
            if (neurelix != null) return neurelix;
            if (System.currentTimeMillis() - lastFailMs < RETRY_MS) return null;
            try {
                Context ctx = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                NeurelixSession s = new NeurelixSession(ctx);
                s.open("App integrado (NEURELIX)", 4000);
                neurelix = s;
                Log.i(TAG, "sessão NEURELIX aberta para o app");
                return s;
            } catch (Throwable t) {
                lastFailMs = System.currentTimeMillis();
                Log.e(TAG, "NEURELIX indisponível, câmera real mantida: " + t);
                return null;
            }
        }
    }
}
