package br.com.neurelix.vcam.shim;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import br.com.neurelix.vcam.api.ISessionCallback;
import br.com.neurelix.vcam.api.IVirtualCameraService;
import br.com.neurelix.vcam.api.IVirtualCameraSession;
import br.com.neurelix.vcam.api.SessionRequest;
import br.com.neurelix.vcam.api.StreamConfig;
import br.com.neurelix.vcam.api.StreamRequest;
import br.com.neurelix.vcam.api.VcamApi;

/**
 * Cliente mínimo (Java, sem coroutines) do serviço NEURELIX. Injetado no APK do app alvo pelo patcher.
 * Toda chamada bloqueante deve rodar fora da thread principal (o Open Camera abre a câmera em background).
 */
public final class NeurelixSession {
    private static final String TAG = "NVC-SHIM";
    private final Context ctx;
    private ServiceConnection conn;
    private IVirtualCameraSession session;

    public NeurelixSession(Context ctx) {
        this.ctx = ctx;
    }

    public synchronized void open(String label, long timeoutMs) throws Exception {
        Intent i = new Intent(VcamApi.ACTION_BIND);
        i.setComponent(new ComponentName(VcamApi.SERVICE_PACKAGE, VcamApi.SERVICE_CLASS));
        final CountDownLatch latch = new CountDownLatch(1);
        final IVirtualCameraService[] box = new IVirtualCameraService[1];
        conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) {
                box[0] = IVirtualCameraService.Stub.asInterface(b);
                latch.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName n) { }
        };
        if (!ctx.bindService(i, conn, Context.BIND_AUTO_CREATE))
            throw new IllegalStateException("NEURELIX: bindService recusado (app NEURELIX instalado? <queries>?)");
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS))
            throw new IllegalStateException("NEURELIX: serviço não respondeu");
        session = box[0].openSession(new SessionRequest(label, VcamApi.VERSION), new ISessionCallback.Stub() {
            @Override public void onSourceStateChanged(int engineState, String sourceLabel) {
                android.util.Log.i(TAG, "estado=" + engineState + " fonte=" + sourceLabel);
            }
            @Override public void onSessionClosed(String reason) {
                android.util.Log.w(TAG, "sessão encerrada pelo NEURELIX: " + reason);
            }
        });
    }

    public synchronized int attach(Surface s, int w, int h, int fps, int fit) throws Exception {
        StreamConfig cfg = session.attachSurface(s, new StreamRequest(w, h, fps, fit));
        return cfg.getStreamId();
    }

    public synchronized void detach(int streamId) {
        try { if (session != null) session.detachSurface(streamId); } catch (Throwable ignored) { }
    }

    public synchronized byte[] captureJpeg(int quality) throws Exception {
        ParcelFileDescriptor pfd = session.captureJpeg(quality);
        if (pfd == null) throw new IllegalStateException("NEURELIX: captura indisponível");
        InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    public synchronized void close() {
        try { if (session != null) session.close(); } catch (Throwable ignored) { }
        try { if (conn != null) ctx.unbindService(conn); } catch (Throwable ignored) { }
        session = null; conn = null;
    }
}
