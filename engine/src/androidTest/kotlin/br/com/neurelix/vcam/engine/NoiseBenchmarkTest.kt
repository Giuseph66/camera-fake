package br.com.neurelix.vcam.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Instrumentado (precisa de GPU real). Prova que a redução de ruído é efetiva e mensurável. */
class NoiseBenchmarkTest {
    @Test fun denoiseReducesErrorSubstantially() = runBlocking {
        val engine = RenderEngine()
        try {
            val r = NoiseBenchmark.run(engine, 1f)
            println("NOISE_BENCH $r")
            assertTrue("σ antes deve ser ~0.08, foi ${r.sigmaBefore}", r.sigmaBefore in 0.05f..0.11f)
            assertTrue("redução < 40%: $r", r.reductionPercent >= 40f)
            assertTrue("PSNR deve subir >= 4 dB: $r", r.psnrAfter - r.psnrBefore >= 4f)
        } finally { engine.shutdown() }
    }
}
