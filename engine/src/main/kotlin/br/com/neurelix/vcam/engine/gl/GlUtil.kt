package br.com.neurelix.vcam.engine.gl

import android.opengl.GLES20
import android.opengl.GLES30
import br.com.neurelix.vcam.core.diagnostics.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {
    val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    fun checkError(where: String) {
        var e = GLES20.glGetError()
        while (e != GLES20.GL_NO_ERROR) {
            AppLog.w("engine", "gl", "glError 0x${Integer.toHexString(e)} em $where")
            e = GLES20.glGetError()
        }
    }

    fun newTexture2D(w: Int, h: Int, linear: Boolean = true, mipmaps: Boolean = false): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val f = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (mipmaps) GLES20.GL_LINEAR_MIPMAP_LINEAR else f)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, f)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // Com filtro mipmap a textura só é "completa" se todos os níveis existirem; sem isto amostra preto até o 1º medidor.
        if (mipmaps) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        return ids[0]
    }

    fun newFbo(tex: Int): Int {
        val ids = IntArray(1); GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(st == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incompleto: 0x${Integer.toHexString(st)}" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return ids[0]
    }

    fun deleteTexture(t: Int) { if (t != 0) GLES20.glDeleteTextures(1, intArrayOf(t), 0) }
    fun deleteFbo(f: Int) { if (f != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(f), 0) }
}

/** Programa GLSL com cache de uniforms. */
class GlProgram(vs: String, fs: String, val name: String) {
    val id: Int
    private val cache = HashMap<String, Int>()

    init {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, v); GLES20.glAttachShader(id, f)
        GLES20.glBindAttribLocation(id, 0, "aPos")
        GLES20.glLinkProgram(id)
        val ok = IntArray(1); GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            throw IllegalStateException("Link do programa '$name' falhou: $log")
        }
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Shader '$name' não compilou: ${GLES20.glGetShaderInfoLog(s)}")
        return s
    }

    fun use() = GLES20.glUseProgram(id)
    fun loc(n: String): Int = cache.getOrPut(n) { GLES20.glGetUniformLocation(id, n) }
    fun f(n: String, v: Float) = GLES20.glUniform1f(loc(n), v)
    fun f2(n: String, a: Float, b: Float) = GLES20.glUniform2f(loc(n), a, b)
    fun i(n: String, v: Int) = GLES20.glUniform1i(loc(n), v)
    fun m4(n: String, m: FloatArray) = GLES20.glUniformMatrix4fv(loc(n), 1, false, m, 0)
    fun release() = GLES20.glDeleteProgram(id)

    fun drawQuad() {
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, GlUtil.QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
