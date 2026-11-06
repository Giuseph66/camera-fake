package br.com.neurelix.vcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.launch
import br.com.neurelix.vcam.core.model.FilterParams
import br.com.neurelix.vcam.ui.AppRoot
import br.com.neurelix.vcam.ui.NeurelixTheme

class MainActivity : ComponentActivity() {
    private var tab = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tab.intValue = intent.getIntExtra("tab", 0)
        handleAutomation(intent)
        setContent { NeurelixTheme { AppRoot(tab) } }
    }

    /**
     * Ganho de automação para testes por adb. SÓ funciona em build debuggable. Não toca em autorização de consumidores.
     * extras: src=pattern:NUMBERED|image:/path|video:/path|camera_back|camera_front, start=true, ext=true|false,
     * out=WxH@FPS, denoise=0..1, bright=-1..1, contrast, sat, rot, mirror, stop=true
     */
    private fun handleAutomation(i: android.content.Intent) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val c = App.controller
        if (i.getBooleanExtra("reset", false)) { c.updateFilters { FilterParams() }; c.updateOutput { it.copy(rotationDegrees = 0, mirrorHorizontal = false, zoom = 1f, panX = 0f, panY = 0f, fit = br.com.neurelix.vcam.core.model.FitMode.CROP) } }
        i.getStringExtra("out")?.let { o ->
            val m = Regex("(\\d+)x(\\d+)@(\\d+)").matchEntire(o)
            if (m != null) c.updateOutput { it.copy(width = m.groupValues[1].toInt(), height = m.groupValues[2].toInt(), fps = m.groupValues[3].toInt()) }
        }
        if (i.hasExtra("rot")) c.updateOutput { it.copy(rotationDegrees = i.getIntExtra("rot", 0)) }
        if (i.hasExtra("mirror")) c.updateOutput { it.copy(mirrorHorizontal = i.getBooleanExtra("mirror", false)) }
        if (i.hasExtra("fit")) c.updateOutput { it.copy(fit = br.com.neurelix.vcam.core.model.FitMode.values()[i.getIntExtra("fit", 0)]) }
        c.updateFilters { f ->
            f.copy(
                denoise = i.getFloatExtra("denoise", f.denoise), brightness = i.getFloatExtra("bright", f.brightness),
                contrast = i.getFloatExtra("contrast", f.contrast), saturation = i.getFloatExtra("sat", f.saturation),
                sharpness = i.getFloatExtra("sharp", f.sharpness), exposureEv = i.getFloatExtra("ev", f.exposureEv),
                temperature = i.getFloatExtra("temp", f.temperature), gamma = i.getFloatExtra("gamma", f.gamma),
                autoLight = i.getBooleanExtra("auto", f.autoLight),
            )
        }
        if (i.hasExtra("lostpolicy")) c.updateOutput { it.copy(lostSourcePolicy = br.com.neurelix.vcam.core.model.LostSourcePolicy.values()[i.getIntExtra("lostpolicy", 1)]) }
        if (i.hasExtra("lost")) { if (i.getBooleanExtra("lost", false)) c.engine.onSourceLost("SIMULADO (gancho de teste)") else c.engine.onSourceRecovered() }
        if (i.hasExtra("ext")) c.setExternalEnabled(i.getBooleanExtra("ext", false))
        val src = i.getStringExtra("src")
        val spec = when {
            src == null -> null
            src.startsWith("pattern:") -> c.patternSpec(br.com.neurelix.vcam.core.model.PatternType.valueOf(src.removePrefix("pattern:")))
            src.startsWith("image:") -> br.com.neurelix.vcam.core.model.SourceSpec(br.com.neurelix.vcam.core.model.SourceKind.IMAGE, src.removePrefix("image:"), src.substringAfterLast('/'))
            src.startsWith("video:") -> br.com.neurelix.vcam.core.model.SourceSpec(br.com.neurelix.vcam.core.model.SourceKind.VIDEO, src.removePrefix("video:"), src.substringAfterLast('/'))
            src == "camera_back" -> br.com.neurelix.vcam.core.model.SourceSpec(br.com.neurelix.vcam.core.model.SourceKind.CAMERA_BACK, label = "Câmera traseira")
            src == "camera_front" -> br.com.neurelix.vcam.core.model.SourceSpec(br.com.neurelix.vcam.core.model.SourceKind.CAMERA_FRONT, label = "Câmera frontal")
            else -> null
        }
        kotlinx.coroutines.MainScope().launch {
            if (i.getBooleanExtra("stop", false)) c.stop()
            if (spec != null) { c.selectSource(spec); kotlinx.coroutines.delay(300) }
            if (i.getBooleanExtra("start", false)) c.start()
            if (i.getBooleanExtra("bench", false)) { c.runNoiseBenchmark() }
            if (i.getBooleanExtra("capture", false)) { kotlinx.coroutines.delay(1200); c.captureToGallery() }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleAutomation(intent)
        tab.intValue = intent.getIntExtra("tab", tab.intValue)
    }
}
