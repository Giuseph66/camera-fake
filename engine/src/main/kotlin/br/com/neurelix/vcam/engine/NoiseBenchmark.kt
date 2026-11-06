package br.com.neurelix.vcam.engine

import android.graphics.Bitmap
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.core.model.FilterParams
import br.com.neurelix.vcam.core.model.FitMode
import br.com.neurelix.vcam.core.model.OutputSettings
import br.com.neurelix.vcam.core.model.PatternType
import br.com.neurelix.vcam.engine.sources.TestPatternSource
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Medição objetiva da redução de ruído. O padrão NOISY_BARS (barras 0.2/0.8 + ruído aprox. gaussiano σ≈0,08, novo a cada quadro)
 * tem referência analítica sem ruído; mede-se o erro RMS (σ) nas regiões planas antes/depois do filtro.
 */
object NoiseBenchmark {
    data class Result(val sigmaBefore: Float, val sigmaAfter: Float, val psnrBefore: Float, val psnrAfter: Float, val denoiseStrength: Float) {
        val reductionPercent: Float get() = if (sigmaBefore <= 0f) 0f else (1f - sigmaAfter / sigmaBefore) * 100f
        override fun toString() = "σ %.4f → %.4f (−%.1f%%), PSNR %.2f → %.2f dB (denoise=%.2f)".format(
            sigmaBefore, sigmaAfter, reductionPercent, psnrBefore, psnrAfter, denoiseStrength)
    }

    private val BARS = arrayOf(
        floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 1f, 0f), floatArrayOf(0f, 1f, 1f), floatArrayOf(0f, 1f, 0f),
        floatArrayOf(1f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, 0f))

    /** Referência limpa em (x,y) com origem no topo, ou null perto de bordas (excluídas da medição). */
    fun reference(x: Int, y: Int, w: Int, h: Int): FloatArray? {
        val u = (x + 0.5f) / w
        val v = 1f - (y + 0.5f) / h
        val edge = 6f
        val fx = u * 8f
        val dx = abs(fx - Math.round(fx)) * (w / 8f)
        if (dx < edge) return null
        if (abs(v - 0.15f) * h < edge) return null
        if (x < 6 || y < 6 || x > w - 7 || y > h - 7) return null
        if (v < 0.15f) return floatArrayOf(0.5f, 0.5f, 0.5f)
        val b = BARS[(fx.toInt()).coerceIn(0, 7)]
        return floatArrayOf(0.2f + 0.6f * b[0], 0.2f + 0.6f * b[1], 0.2f + 0.6f * b[2])
    }

    fun rmsError(bmp: Bitmap): Float {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        var sum = 0.0; var n = 0L
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            val ref = reference(x, y, w, h) ?: continue
            val p = px[y * w + x]
            val r = ((p shr 16) and 0xFF) / 255f; val g = ((p shr 8) and 0xFF) / 255f; val b = (p and 0xFF) / 255f
            sum += ((r - ref[0]) * (r - ref[0]) + (g - ref[1]) * (g - ref[1]) + (b - ref[2]) * (b - ref[2])) / 3.0; n++
        }
        return if (n == 0L) 0f else sqrt(sum / n).toFloat()
    }

    /** O chamador deve restaurar fonte/saída/filtros depois. */
    suspend fun run(engine: RenderEngine, strength: Float = 1f): Result {
        engine.setOutput(OutputSettings(width = 1280, height = 720, fps = 30, fit = FitMode.CROP))
        engine.setFilters(FilterParams(denoise = 0f))
        engine.setSource(TestPatternSource(PatternType.NOISY_BARS))
        delay(900)
        val before = engine.capture() ?: error("Sem quadro para medir (antes)")
        val s0 = rmsError(before); before.recycle()
        engine.setFilters(FilterParams(denoise = strength))
        delay(1200)   // convergência do filtro temporal recursivo
        val after = engine.capture() ?: error("Sem quadro para medir (depois)")
        val s1 = rmsError(after); after.recycle()
        val r = Result(s0, s1, (-20 * log10(s0.coerceAtLeast(1e-6f))), (-20 * log10(s1.coerceAtLeast(1e-6f))), strength)
        AppLog.i("engine", "benchmark", "redução de ruído: $r")
        return r
    }
}
