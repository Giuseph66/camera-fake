package br.com.neurelix.vcam.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.neurelix.vcam.App
import br.com.neurelix.vcam.core.model.DropPolicy
import br.com.neurelix.vcam.core.model.LostSourcePolicy
import br.com.neurelix.vcam.core.model.QualityPriority

private data class Res(val label: String, val w: Int, val h: Int)

private val RESOLUTIONS = listOf(
    Res("360p 640×360", 640, 360), Res("480p 854×480", 854, 480), Res("540p 960×540", 960, 540),
    Res("720p 1280×720", 1280, 720), Res("720p retrato 720×1280", 720, 1280),
    Res("1080p 1920×1080", 1920, 1080), Res("1080p retrato 1080×1920", 1080, 1920),
    Res("1440p 2560×1440", 2560, 1440),
)

@Composable
fun SettingsScreen() {
    val c = App.controller
    val o by c.output.collectAsStateWithLifecycle()
    val cur = RESOLUTIONS.firstOrNull { it.w == o.width && it.h == o.height }

    ScreenColumn {
        SectionCard("Saída", "Referência de desempenho: 720p a 30 FPS. Resoluções maiores dependem do hardware; veja docs/TEST_REPORT.md.") {
            ChoiceChips("Resolução de saída", RESOLUTIONS, cur ?: RESOLUTIONS[3], { it.label }) { r -> c.updateOutput { it.copy(width = r.w, height = r.h) } }
            ChoiceChips("FPS de destino", listOf(15, 24, 30, 60), o.fps, { "$it" }) { v -> c.updateOutput { it.copy(fps = v) } }
            ChoiceChips("Proporção / enquadramento", br.com.neurelix.vcam.core.model.FitMode.values().toList(), o.fit, { it.label }) { v -> c.updateOutput { it.copy(fit = v) } }
            androidx.compose.foundation.layout.Column { }
            SwitchRow("Espelhamento horizontal", o.mirrorHorizontal, { v -> c.updateOutput { it.copy(mirrorHorizontal = v) } })
        }
        SectionCard("Comportamento") {
            ChoiceChips("Política de descarte de quadros", DropPolicy.values().toList(), o.dropPolicy, { it.label }) { v -> c.updateOutput { it.copy(dropPolicy = v) } }
            ChoiceChips("Se a fonte for perdida", LostSourcePolicy.values().toList(), o.lostSourcePolicy, { it.label }) { v -> c.updateOutput { it.copy(lostSourcePolicy = v) } }
            ChoiceChips("Prioridade", QualityPriority.values().toList(), o.priority, { it.label }) { v -> c.updateOutput { it.copy(priority = v) } }
            SwitchRow("Modo de economia de energia", o.powerSaving, { v -> c.updateOutput { it.copy(powerSaving = v) } },
                "Limita a 15 FPS. Qualidade: bilateral 5×5 + temporal. Equilíbrio: 3×3 + temporal. Baixa latência: 3×3 sem temporal.")
        }
    }
}
