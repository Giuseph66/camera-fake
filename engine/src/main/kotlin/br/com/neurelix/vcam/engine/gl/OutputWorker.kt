package br.com.neurelix.vcam.engine.gl

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.core.model.FitMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Uma saída = uma Surface de destino (preview, ImageReader, codec de consumidor) com thread e contexto EGL próprios
 * (compartilhado com o motor). Se o consumidor travar a Surface, só este worker bloqueia; o motor continua e
 * descarta quadros desta saída ([dropped]).
 */
internal class OutputWorker(
    val id: String,
    private val surface: Surface,
    val external: Boolean,
    @Volatile var fit: FitMode,
    private val sharedContext: EGLContext,
    private val onFailed: (String, String) -> Unit,
) {
    private val thread = HandlerThread("nvc-out-$id", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)
    private var egl: EglCore? = null
    private var eglSurface: EGLSurface? = null
    private var program: GlProgram? = null
    private val queued = AtomicBoolean(false)
    @Volatile private var pendingTex = 0
    @Volatile private var pendingW = 0
    @Volatile private var pendingH = 0
    @Volatile private var released = false
    val delivered = AtomicLong()
    val dropped = AtomicLong()
    @Volatile var surfaceW = 0; private set
    @Volatile var surfaceH = 0; private set

    /** Cria contexto/surface na thread do worker. Retorna null se OK ou mensagem de erro. */
    fun init(): String? {
        val latch = CountDownLatch(1)
        var err: String? = null
        handler.post {
            try {
                val e = EglCore(sharedContext)
                val s = e.createWindowSurface(surface) ?: throw IllegalStateException("Surface inválida/abandonada")
                egl = e; eglSurface = s
                e.makeCurrent(s)
                e.setSwapInterval(0)
                program = GlProgram(Shaders.VERTEX, Shaders.source(false), "blit-$id")
                val sz = e.size(s); surfaceW = sz[0]; surfaceH = sz[1]
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
                AppLog.e("engine", "output:$id", "falha ao iniciar saída", t)
            }
            latch.countDown()
        }
        if (!latch.await(3, TimeUnit.SECONDS)) return "timeout ao criar saída"
        return err
    }

    fun submit(tex: Int, w: Int, h: Int) {
        if (released) return
        pendingTex = tex; pendingW = w; pendingH = h
        if (queued.compareAndSet(false, true)) handler.post(::draw) else dropped.incrementAndGet()
    }

    private fun draw() {
        queued.set(false)
        val e = egl ?: return
        val s = eglSurface ?: return
        val p = program ?: return
        if (released) return
        try {
            if (!e.makeCurrent(s)) { fail("eglMakeCurrent falhou 0x${Integer.toHexString(EGL14.eglGetError())}"); return }
            val sz = e.size(s); surfaceW = sz[0]; surfaceH = sz[1]
            if (sz[0] <= 0 || sz[1] <= 0) return
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, sz[0], sz[1])
            p.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pendingTex)
            p.i("uTex", 0); p.f("uFlipV", 0f); p.f("uMirror", 0f)
            p.f("uOutAspect", sz[0].toFloat() / sz[1]); p.f("uMode", fit.ordinal.toFloat())
            p.f2("uBuf", pendingW.toFloat(), pendingH.toFloat()); p.f2("uRot", 1f, 0f)
            p.f("uZoom", 1f); p.f2("uPan", 0f, 0f)
            p.drawQuad()
            if (!e.swap(s)) {
                fail("eglSwapBuffers falhou 0x${Integer.toHexString(EGL14.eglGetError())}")
                return
            }
            delivered.incrementAndGet()
        } catch (t: Throwable) {
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun fail(msg: String) {
        if (released) return
        released = true
        AppLog.e("engine", "output:$id", "saída falhou: $msg")
        onFailed(id, msg)
    }

    /** Libera de forma síncrona (importante em surfaceDestroyed). */
    fun release() {
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                egl?.let { e ->
                    eglSurface?.let { s -> e.makeCurrent(s); program?.release(); e.makeNothingCurrent(); e.destroySurface(s) }
                    e.release()
                }
            } catch (t: Throwable) { AppLog.w("engine", "output:$id", "erro ao liberar", t) }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }
}
