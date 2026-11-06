package br.com.neurelix.vcam.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.neurelix.vcam.App
import br.com.neurelix.vcam.core.model.FitMode
import br.com.neurelix.vcam.core.model.PatternType
import br.com.neurelix.vcam.core.model.SourceKind
import br.com.neurelix.vcam.core.model.SourceSpec
import kotlinx.coroutines.launch

@Composable
fun SourcesScreen() {
    val c = App.controller
    val scope = rememberCoroutineScope()
    val active by c.activeSpec.collectAsStateWithLifecycle()
    val recents by c.recents.collectAsStateWithLifecycle()
    val out by c.output.collectAsStateWithLifecycle()
    val (camGranted, requestCam) = rememberCameraPermission()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { c.importMedia(uri, SourceKind.IMAGE)?.let { c.selectSource(it) } }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { c.importMedia(uri, SourceKind.VIDEO)?.let { c.selectSource(it) } }
    }

    ScreenColumn {
        SectionCard("Escolher fonte", "A mídia escolhida é copiada para o armazenamento privado do app. Nada é enviado para fora do aparelho.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Text("Fotografia") }
                Button(onClick = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, modifier = Modifier.weight(1f)) { Text("Vídeo") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    if (!camGranted) requestCam()
                    c.selectSource(SourceSpec(SourceKind.CAMERA_BACK, label = "Câmera traseira"))
                }) { Text("Câmera traseira") }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    if (!camGranted) requestCam()
                    c.selectSource(SourceSpec(SourceKind.CAMERA_FRONT, label = "Câmera frontal"))
                }) { Text("Câmera frontal") }
            }
            if (!camGranted) Text("A permissão de câmera só é pedida ao escolher uma câmera.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Fonte de diagnóstico", "Padrões 100% GPU para medir perda de quadros, latência e integridade. Os quadros numerados trazem código binário do contador e do tempo.") {
            ChoiceChips("Padrão", PatternType.values().toList(),
                active?.takeIf { it.kind == SourceKind.PATTERN }?.pattern ?: PatternType.NUMBERED, { it.label }) { c.selectSource(c.patternSpec(it)) }
        }

        SectionCard("Fonte atual") {
            Text(active?.let { "${it.kind.label}: ${it.label}" } ?: "Nenhuma", style = MaterialTheme.typography.bodyMedium)
            Text("A troca de fonte com o motor ligado não reinicia o app nem o serviço.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Orientação, enquadramento e proporção") {
            ChoiceChips("Enquadramento", FitMode.values().toList(), out.fit, { it.label }) { v -> c.updateOutput { it.copy(fit = v) } }
            ChoiceChips("Rotação", listOf(0, 90, 180, 270), out.rotationDegrees, { "$it°" }) { v -> c.updateOutput { it.copy(rotationDegrees = v) } }
            SwitchRow("Espelhar horizontalmente", out.mirrorHorizontal, { v -> c.updateOutput { it.copy(mirrorHorizontal = v) } })
            SwitchRow("Repetir vídeo em loop", out.loopVideo, { v -> c.updateOutput { it.copy(loopVideo = v) } })
            SliderRow("Zoom", out.zoom, 1f..4f, { v -> c.updateOutput { it.copy(zoom = v) } }, { "%.2f×".format(it) })
            SliderRow("Deslocamento horizontal", out.panX, -1f..1f, { v -> c.updateOutput { it.copy(panX = v) } })
            SliderRow("Deslocamento vertical", out.panY, -1f..1f, { v -> c.updateOutput { it.copy(panY = v) } })
            OutlinedButton(onClick = { c.updateOutput { it.copy(zoom = 1f, panX = 0f, panY = 0f, rotationDegrees = 0, mirrorHorizontal = false) } }) { Text("Restaurar enquadramento") }
        }

        SectionCard("Fontes recentes") {
            if (recents.isEmpty()) Text("Nenhuma ainda.", style = MaterialTheme.typography.bodySmall)
            recents.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { c.selectSource(r) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(r.kind.label + if (r.id == active?.id) " · ativa" else "", style = MaterialTheme.typography.bodySmall,
                            color = if (r.id == active?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { c.removeRecent(r) }) { Icon(Icons.Filled.Delete, contentDescription = "Remover") }
                }
            }
        }
    }
}
