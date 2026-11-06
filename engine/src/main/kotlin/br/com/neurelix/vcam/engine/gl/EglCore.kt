package br.com.neurelix.vcam.engine.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import br.com.neurelix.vcam.core.diagnostics.AppLog

/** Contexto EGL/ES3 com suporte a contexto compartilhado e surfaces de janela (SurfaceView, ImageReader, codecs). */
class EglCore(sharedContext: EGLContext = EGL14.EGL_NO_CONTEXT) {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val context: EGLContext
    private val config: EGLConfig
    val recordable: Boolean

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay falhou" }
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize falhou" }
        var chosen = chooseConfig(true)
        recordable = chosen != null
        if (chosen == null) chosen = chooseConfig(false)
        config = checkNotNull(chosen) { "Nenhuma EGLConfig ES3 RGBA8888 disponível" }
        val ctx = EGL14.eglCreateContext(display, config, sharedContext, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext falhou: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        context = ctx
    }

    private fun chooseConfig(recordable: Boolean): EGLConfig? {
        val attrs = ArrayList<Int>().apply {
            addAll(listOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT))
            if (recordable) addAll(listOf(0x3142 /* EGL_RECORDABLE_ANDROID */, 1))
            add(EGL14.EGL_NONE)
        }.toIntArray()
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        return if (EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) configs[0] else null
    }

    fun createWindowSurface(surface: Any): EGLSurface? {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (s == null || s == EGL14.EGL_NO_SURFACE) {
            AppLog.w("engine", "egl", "eglCreateWindowSurface falhou: 0x${Integer.toHexString(EGL14.eglGetError())}")
            return null
        }
        return s
    }

    fun createPbuffer(w: Int = 1, h: Int = 1): EGLSurface {
        val s = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface falhou" }
        return s
    }

    fun makeCurrent(s: EGLSurface): Boolean = EGL14.eglMakeCurrent(display, s, s, context)
    fun makeNothingCurrent() { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
    fun swap(s: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, s)
    fun destroySurface(s: EGLSurface) { EGL14.eglDestroySurface(display, s) }
    fun setSwapInterval(i: Int) { EGL14.eglSwapInterval(display, i) }

    fun size(s: EGLSurface): IntArray {
        val w = IntArray(1); val h = IntArray(1)
        EGL14.eglQuerySurface(display, s, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, s, EGL14.EGL_HEIGHT, h, 0)
        return intArrayOf(w[0], h[0])
    }

    fun release() {
        makeNothingCurrent()
        EGL14.eglDestroyContext(display, context)
        // display compartilhado do processo: não chamar eglTerminate
    }
}
