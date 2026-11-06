package br.com.neurelix.vcam.consumer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import br.com.neurelix.vcam.api.ClientResult
import br.com.neurelix.vcam.api.VcamApi
import br.com.neurelix.vcam.api.VirtualCameraClient
import br.com.neurelix.vcam.api.VirtualCameraSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "NVC-CONSUMER"

/** Resultado da leitura do código de barras do padrão "quadros numerados". */
data class FrameInfo(val markerOk: Boolean, val seq: Long, val timeBits: Int)

/**
 * Consumidor independente (outro pacote/UID). Abre sessão no NEURELIX via SDK, entrega uma SurfaceView e um ImageReader
 * RGBA_8888 e verifica o conteúdo recebido: decodifica o contador de quadros do padrão de diagnóstico, mede perda e latência.
 */
class ConsumerActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: VirtualCameraClient
    private var session: VirtualCameraSession? = null
    private var previewStream = -1
    private var readerStream = -1
    private lateinit var surfaceView: SurfaceView
    private lateinit var log: TextView
    private lateinit var status: TextView
    private var holderSurface: SurfaceHolder? = null
    private var reader: ImageReader? = null
    private val thread = HandlerThread("consumer-reader").also { it.start() }
    private val handler = Handler(thread.looper)

    // estatísticas
    private val frames = AtomicLong()
    private val gaps = AtomicLong()
    private val markerOk = AtomicLong()
    @Volatile private var lastSeq = -1L
    @Volatile private var latSumMs = 0.0
    @Volatile private var latN = 0
    @Volatile private var lastBitmapPng: Bitmap? = null
    private var reqW = 1280
    private var reqH = 720
    private var reqFps = 30
    private var statsJob: kotlinx.coroutines.Job? = null
    private val bg = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        client = VirtualCameraClient(this)
        reqW = intent.getIntExtra("w", 1280); reqH = intent.getIntExtra("h", 720); reqFps = intent.getIntExtra("fps", 30)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(0xFF0E1118.toInt()) }
        status = TextView(this).apply { setTextColor(0xFFE6EAF2.toInt()); textSize = 14f; text = "Desconectado" }
        surfaceView = SurfaceView(this)
        root.addView(status)
        root.addView(surfaceView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        val row1 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        row1.addView(btn("Conectar") { scope.launch { connectAndAttach() } })
        row1.addView(btn("Fechar") { closeSession("botão") })
        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        row2.addView(btn("Foto SDK") { scope.launch { photoSdk() } })
        row2.addView(btn("Foto Reader") { scope.launch { photoReader() } })
        row2.addView(btn("Foto Intent") { photoIntent() })
        root.addView(row1); root.addView(row2)
        log = TextView(this).apply { setTextColor(0xFFA9B3C5.toInt()); textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360))
        setContentView(root)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { holderSurface = h }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                if (previewStream >= 0) { session?.detach(previewStream); previewStream = -1 }
                holderSurface = null
            }
        })
        if (intent.getBooleanExtra("auto", false)) scope.launch { autoRun(intent.getIntExtra("seconds", 15)) }
    }

    private fun say(m: String) {
        Log.i(TAG, m)
        log.append(m + "\n")
    }

    private suspend fun connectAndAttach(): Boolean {
        closeSession("reconectando")
        say("Consultando serviço NEURELIX…")
        when (val st = client.status()) {
            is ClientResult.Err -> { say("STATUS ERRO [${st.kind}] ${st.message}"); status.text = "Erro: ${st.kind}"; return false }
            is ClientResult.Ok -> say("STATUS ok: estado=${st.value.engineState} fonte='${st.value.sourceLabel}' externo=${st.value.externalEnabled} autorizado=${st.value.callerAuthorized} saída=${st.value.outputWidth}x${st.value.outputHeight}@${st.value.fps}")
        }
        val r = client.openSession("Consumidor de teste",
            onState = { s, label -> runOnUiThread { say("CALLBACK estado=$s fonte='$label'") } },
            onClosed = { reason -> runOnUiThread { say("CALLBACK sessão encerrada pelo NEURELIX: $reason"); status.text = "Encerrada: $reason" } })
        val s = when (r) {
            is ClientResult.Err -> { say("OPEN_SESSION ERRO [${r.kind}] ${r.message}"); status.text = "Erro: ${r.kind}"; return false }
            is ClientResult.Ok -> r.value
        }
        session = s
        say("OPEN_SESSION ok")
        // 1) ImageReader RGBA_8888 (captura/análise) 2) SurfaceView (preview)
        frames.set(0); gaps.set(0); markerOk.set(0); lastSeq = -1; latSumMs = 0.0; latN = 0
        val rd = ImageReader.newInstance(reqW, reqH, PixelFormat.RGBA_8888, 4)
        rd.setOnImageAvailableListener({ r2 -> onImage(r2) }, handler)
        reader = rd
        when (val a = s.attach(rd.surface, reqW, reqH, reqFps)) {
            is ClientResult.Ok -> { readerStream = a.value.streamId; say("ATTACH reader ok: stream=${a.value.streamId} surface=${a.value.width}x${a.value.height}@${a.value.fps} fonte='${a.value.sourceLabel}' fmt=${a.value.format}") }
            is ClientResult.Err -> { say("ATTACH reader ERRO ${a.message}"); return false }
        }
        holderSurface?.let { h ->
            when (val a = s.attach(h.surface, surfaceView.width.coerceAtLeast(16), surfaceView.height.coerceAtLeast(16), reqFps, VcamApi.FIT_FIT)) {
                is ClientResult.Ok -> { previewStream = a.value.streamId; say("ATTACH preview ok: stream=${a.value.streamId} surface=${a.value.width}x${a.value.height}") }
                is ClientResult.Err -> say("ATTACH preview ERRO ${a.message}")
            }
        }
        status.text = "Conectado"
        startStats()
        return true
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            frames.incrementAndGet()
            val info = decode(img)
            if (info.markerOk) {
                markerOk.incrementAndGet()
                if (lastSeq >= 0 && info.seq > lastSeq + 1) gaps.addAndGet(info.seq - lastSeq - 1)
                if (info.seq > lastSeq) lastSeq = info.seq
                val now = (SystemClock.elapsedRealtime() and 0xFFFF).toInt()
                val lat = (now - info.timeBits + 65536) % 65536
                if (lat < 2000) { latSumMs += lat; latN++ }
            }
            if (frames.get() % 5 == 0L || lastBitmapPng == null) lastBitmapPng = toBitmap(img)
        } finally { img.close() }
    }

    private fun px(img: Image, nx: Float, nyTop: Float): IntArray {
        val p = img.planes[0]
        val x = (nx * img.width).toInt().coerceIn(0, img.width - 1)
        val y = (nyTop * img.height).toInt().coerceIn(0, img.height - 1)
        val i = y * p.rowStride + x * p.pixelStride
        val b = p.buffer
        return intArrayOf(b.get(i).toInt() and 0xFF, b.get(i + 1).toInt() and 0xFF, b.get(i + 2).toInt() and 0xFF)
    }

    private fun decode(img: Image): FrameInfo {
        val m = px(img, 0.047f, 1f - 0.13f)
        val marker = m[0] > 180 && m[1] < 90 && m[2] > 180
        if (!marker) return FrameInfo(false, -1, 0)
        var seq = 0L
        for (i in 0 until 32) { val c = px(img, (4 + i + 0.5f) / 40f, 1f - 0.08f); seq = (seq shl 1) or if (c[0] > 128) 1 else 0 }
        var t = 0
        for (i in 0 until 16) { val c = px(img, (4 + i + 0.5f) / 40f, 1f - 0.18f); t = (t shl 1) or if (c[0] > 128) 1 else 0 }
        return FrameInfo(true, seq, t)
    }

    private fun decodeBitmap(b: Bitmap): FrameInfo {
        fun p(nx: Float, ny: Float) = b.getPixel((nx * b.width).toInt().coerceIn(0, b.width - 1), (ny * b.height).toInt().coerceIn(0, b.height - 1))
        val m = p(0.047f, 1f - 0.13f)
        val marker = android.graphics.Color.red(m) > 170 && android.graphics.Color.green(m) < 100 && android.graphics.Color.blue(m) > 170
        if (!marker) return FrameInfo(false, -1, 0)
        var seq = 0L
        for (i in 0 until 32) { val c = p((4 + i + 0.5f) / 40f, 1f - 0.08f); seq = (seq shl 1) or if (android.graphics.Color.red(c) > 128) 1 else 0 }
        return FrameInfo(true, seq, 0)
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val bmp = Bitmap.createBitmap(p.rowStride / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        p.buffer.rewind(); bmp.copyPixelsFromBuffer(p.buffer)
        return Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
    }

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var last = 0L; var t0 = SystemClock.elapsedRealtime()
            while (true) {
                delay(1000)
                val f = frames.get(); val now = SystemClock.elapsedRealtime()
                val fps = (f - last) * 1000f / (now - t0); last = f; t0 = now
                val lat = if (latN > 0) latSumMs / latN else -1.0
                val line = "STATS fps=%.1f frames=%d marker=%d lastSeq=%d gaps=%d latRender→leitura=%.1fms".format(fps, f, markerOk.get(), lastSeq, gaps.get(), lat)
                Log.i(TAG, line)
                status.text = line
                latSumMs = 0.0; latN = 0
            }
        }
    }

    private fun outFile(name: String) = File(filesDir, "out").apply { mkdirs() }.let { File(it, name) }

    private suspend fun photoSdk() {
        val s = session ?: run { say("Sem sessão"); return }
        val r = withContext(Dispatchers.IO) { s.captureJpeg(95) }
        when (r) {
            is ClientResult.Ok -> {
                val f = outFile("sdk_capture.jpg"); f.writeBytes(r.value)
                val bmp = BitmapFactory.decodeByteArray(r.value, 0, r.value.size)
                val info = decodeBitmap(bmp)
                say("PHOTO_SDK ok: ${r.value.size} bytes ${bmp.width}x${bmp.height} marker=${info.markerOk} seq=${info.seq} → ${f.name}")
            }
            is ClientResult.Err -> say("PHOTO_SDK ERRO ${r.message}")
        }
    }

    private suspend fun photoReader() {
        val b = lastBitmapPng ?: run { say("PHOTO_READER sem quadro"); return }
        val f = outFile("reader_capture.png")
        withContext(Dispatchers.IO) { f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val info = decodeBitmap(b)
        say("PHOTO_READER ok: ${b.width}x${b.height} marker=${info.markerOk} seq=${info.seq} → ${f.name}")
    }

    private var pendingIntentOut: File? = null
    private fun photoIntent() {
        val f = outFile("intent_capture.jpg").also { it.delete() }
        pendingIntentOut = f
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // restringe ao NEURELIX para o teste ser determinístico
        i.setPackage(VcamApi.SERVICE_PACKAGE)
        say("PHOTO_INTENT lançando ACTION_IMAGE_CAPTURE → ${VcamApi.SERVICE_PACKAGE}")
        try { startActivityForResult(i, 77) } catch (e: Exception) { say("PHOTO_INTENT ERRO ${e.message}") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77) {
            val f = pendingIntentOut
            if (resultCode == RESULT_OK && f != null && f.length() > 0) {
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                val info = decodeBitmap(bmp)
                say("PHOTO_INTENT ok: ${f.length()} bytes ${bmp.width}x${bmp.height} marker=${info.markerOk} seq=${info.seq}")
            } else say("PHOTO_INTENT resultCode=$resultCode (sem arquivo)")
        }
    }

    private fun closeSession(why: String) {
        statsJob?.cancel(); statsJob = null
        session?.let { s ->
            if (previewStream >= 0) s.detach(previewStream)
            if (readerStream >= 0) s.detach(readerStream)
            s.close(); say("Sessão fechada ($why)")
        }
        session = null; previewStream = -1; readerStream = -1
        reader?.close(); reader = null
        status.text = "Desconectado"
    }

    /** Execução automatizada para o script de testes. */
    private suspend fun autoRun(seconds: Int) {
        say("AUTO_START seconds=$seconds ${reqW}x$reqH@$reqFps")
        delay(1500)
        if (!connectAndAttach()) { say("AUTO_FAIL conexão"); return }
        delay(3000)
        photoSdk(); photoReader()
        var remaining = seconds - 3
        while (remaining > 0) { delay(1000); remaining-- }
        val f = frames.get()
        say("AUTO_RESULT frames=$f marker=${markerOk.get()} gaps=${gaps.get()} lastSeq=$lastSeq")
        closeSession("fim do teste automático")
        say("AUTO_DONE")
    }

    override fun onDestroy() {
        closeSession("onDestroy"); client.disconnect(); thread.quitSafely(); scope.cancel()
        super.onDestroy()
    }
}
