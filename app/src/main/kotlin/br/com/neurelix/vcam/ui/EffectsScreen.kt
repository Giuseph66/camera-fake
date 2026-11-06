package br.com.neurelix.vcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.neurelix.vcam.App
import br.com.neurelix.vcam.core.model.FilterParams
import kotlinx.coroutines.launch

@Composable
fun EffectsScreen() {
    val c = App.controller
    val scope = rememberCoroutineScope()
    val f by c.filters.collectAsStateWithLifecycle()
    val profiles by c.profiles.collectAsStateWithLifecycle()
    val m by c.metrics.collectAsStateWithLifecycle()
    var profileName by remember { mutableStateOf("") }
    val upd = { g: (FilterParams) -> FilterParams -> c.updateFilters(g) }

    ScreenColumn {
        SectionCard("Pré-visualização", "O efeito é aplicado ao quadro processado que alimenta o preview, a captura e as saídas externas.") {
            PreviewSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Text(if (m.avgLuma >= 0) "Luminância média medida: %.0f%%".format(m.avgLuma * 100) else "Inicie uma fonte para ver o resultado.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Filtros", "0 = neutro. Cada controle é a intensidade do filtro.") {
            SwitchRow("Processamento habilitado", f.enabled, { v -> upd { it.copy(enabled = v) } })
            SliderRow("Brilho", f.brightness, -1f..1f, { v -> upd { it.copy(brightness = v) } })
            SliderRow("Contraste", f.contrast, 0f..2f, { v -> upd { it.copy(contrast = v) } })
            SliderRow("Saturação", f.saturation, 0f..2f, { v -> upd { it.copy(saturation = v) } })
            SliderRow("Temperatura de cor (frio ↔ quente)", f.temperature, -1f..1f, { v -> upd { it.copy(temperature = v) } })
            SliderRow("Correção gama", f.gamma, 0.4f..2.5f, { v -> upd { it.copy(gamma = v) } })
            SliderRow("Nitidez", f.sharpness, 0f..1f, { v -> upd { it.copy(sharpness = v) } })
            SliderRow("Redução de ruído (bilateral + temporal)", f.denoise, 0f..1f, { v -> upd { it.copy(denoise = v) } })
            SliderRow("Correção de exposição (ganho digital, EV)", f.exposureEv, -2f..2f, { v -> upd { it.copy(exposureEv = v) } }, { "%+.1f EV".format(it) })
            SwitchRow("Ajuste automático de iluminação", f.autoLight, { v -> upd { it.copy(autoLight = v) } },
                "Ganho digital pela luminância média. Não recupera detalhe perdido em imagem subexposta; apenas reescala o sinal.")
            if (f.autoLight) SliderRow("Intensidade do ajuste automático", f.autoLightStrength, 0f..1f, { v -> upd { it.copy(autoLightStrength = v) } })
            OutlinedButton(onClick = { upd { FilterParams() } }) { Text("Restaurar neutro") }
        }

        SectionCard("Perfis") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(profileName, { profileName = it }, label = { Text("Nome do perfil") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(enabled = profileName.isNotBlank(), onClick = { c.saveProfile(profileName.trim()); profileName = "" }) { Text("Salvar") }
            }
            if (profiles.isEmpty()) Text("Nenhum perfil salvo.", style = MaterialTheme.typography.bodySmall)
            profiles.forEach { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, Modifier.weight(1f))
                    TextButton(onClick = { c.applyProfile(p) }) { Text("Aplicar") }
                    TextButton(onClick = { c.deleteProfile(p.name) }) { Text("Excluir") }
                }
            }
        }

        SectionCard("Captura local") {
            Button(modifier = Modifier.fillMaxWidth(), onClick = { scope.launch { c.captureToGallery() } }) { Text("Salvar quadro processado em Pictures/NEURELIX") }
        }
    }
}

