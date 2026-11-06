package br.com.neurelix.vcam.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.neurelix.vcam.App
import br.com.neurelix.vcam.core.model.FitMode
import br.com.neurelix.vcam.core.model.PatternType
import br.com.neurelix.vcam.core.model.SourceKind
import br.com.neurelix.vcam.core.model.SourceSpec
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun SourcesScreen() {
    val c = App.controller
    val scope = rememberCoroutineScope()
    val active by c.activeSpec.collectAsStateWithLifecycle()
    val recents by c.recents.collectAsStateWithLifecycle()
    val running by c.running.collectAsStateWithLifecycle()
    val out by c.output.collectAsStateWithLifecycle()
    val (camGranted, requestCam) = rememberCameraPermission()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { c.importMedia(uri, SourceKind.IMAGE)?.let { c.selectSource(it) } }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { c.importMedia(uri, SourceKind.VIDEO)?.let { c.selectSource(it) } }
    }
    val gallery = recents.filter { it.kind == SourceKind.IMAGE || it.kind == SourceKind.VIDEO }

    ScreenColumn {
        // ---- fonte ativa ----
        SectionLabel("Fonte ativa")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (running) Green.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(96.dp).aspectRatio(16f / 10f), contentAlignment = Alignment.Center) {
                    val a = active
                    when {
                        a == null -> Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)))
                        a.kind == SourceKind.IMAGE || a.kind == SourceKind.VIDEO -> SourceThumb(a, Modifier.fillMaxWidth().aspectRatio(16f / 10f), 240)
                        a.kind == SourceKind.PATTERN -> PatternPreview(a.pattern, Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Color.Black, RoundedCornerShape(12.dp)))
                        else -> Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(if (a.kind == SourceKind.CAMERA_FRONT) Icons.Filled.CameraFront else Icons.Filled.CameraRear, null, tint = Teal)
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(active?.label ?: "Nenhuma fonte", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(active?.kind?.label ?: "Escolha uma abaixo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (running) Text("Trocar aqui não reinicia o app nem o serviço", style = MaterialTheme.typography.bodySmall, color = Green)
                }
            }
        }

        // ---- câmeras (fixas) ----
        SectionLabel("Câmeras do aparelho", "A imagem da câmera passa pelos seus filtros antes de sair.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SourceCard("Traseira", if (camGranted) "Pronta" else "Pede permissão", active?.kind == SourceKind.CAMERA_BACK,
                onClick = { if (!camGranted) requestCam(); c.selectSource(SourceSpec(SourceKind.CAMERA_BACK, label = "Câmera traseira")) },
                modifier = Modifier.weight(1f)) { Icon(Icons.Filled.CameraRear, null, tint = Teal, modifier = Modifier.size(36.dp)) }
            SourceCard("Frontal", if (camGranted) "Pronta" else "Pede permissão", active?.kind == SourceKind.CAMERA_FRONT,
                onClick = { if (!camGranted) requestCam(); c.selectSource(SourceSpec(SourceKind.CAMERA_FRONT, label = "Câmera frontal")) },
                modifier = Modifier.weight(1f)) { Icon(Icons.Filled.CameraFront, null, tint = Teal, modifier = Modifier.size(36.dp)) }
        }

        // ---- galeria + recentes (com miniatura) ----
        SectionLabel("Galeria", "Escolha uma foto ou vídeo. A mídia é copiada para o armazenamento privado do app. Nada sai do aparelho.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(modifier = Modifier.weight(1f), onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Foto")
            }
            Button(modifier = Modifier.weight(1f), onClick = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                Icon(Icons.Filled.Videocam, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Vídeo")
            }
        }
        SectionLabel("Recentes da galeria")
        if (gallery.isEmpty()) {
            Text("Nada por aqui ainda. Fotos e vídeos que você escolher ficam salvos aqui, com miniatura.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        gallery.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { r ->
                    RecentCard(r, selected = r.id == active?.id, onClick = { c.selectSource(r) }, onRemove = { c.removeRecent(r) }, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // ---- diagnóstico ----
        SectionLabel("Fontes de diagnóstico", "Padrões 100% GPU para medir perda de quadros, latência e orientação.")
        PatternType.values().toList().chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { p ->
                    SourceCard(p.label.substringBefore(" ("), if (p == PatternType.NOISY_BARS) "teste de denoise" else "padrão", active?.kind == SourceKind.PATTERN && active?.pattern == p,
                        onClick = { c.selectSource(c.patternSpec(p)) }, modifier = Modifier.weight(1f)) {
                        PatternPreview(p, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
                    }
                }
            }
        }

        // ---- orientação ----
        SectionLabel("Orientação e enquadramento")
        SectionCard("Ajustes de imagem") {
            ChoiceChips("Enquadramento", FitMode.values().toList(), out.fit, { it.label }) { v -> c.updateOutput { it.copy(fit = v) } }
            ChoiceChips("Rotação", listOf(0, 90, 180, 270), out.rotationDegrees, { "$it°" }) { v -> c.updateOutput { it.copy(rotationDegrees = v) } }
            SwitchRow("Espelhar horizontalmente", out.mirrorHorizontal, { v -> c.updateOutput { it.copy(mirrorHorizontal = v) } })
            SwitchRow("Repetir vídeo em loop", out.loopVideo, { v -> c.updateOutput { it.copy(loopVideo = v) } })
            SliderRow("Zoom", out.zoom, 1f..4f, { v -> c.updateOutput { it.copy(zoom = v) } }, { "%.2f×".format(it) })
            SliderRow("Deslocamento horizontal", out.panX, -1f..1f, { v -> c.updateOutput { it.copy(panX = v) } })
            SliderRow("Deslocamento vertical", out.panY, -1f..1f, { v -> c.updateOutput { it.copy(panY = v) } })
            OutlinedButton(onClick = { c.updateOutput { it.copy(zoom = 1f, panX = 0f, panY = 0f, rotationDegrees = 0, mirrorHorizontal = false) } }) { Text("Restaurar enquadramento") }
        }
    }
}

@Composable
private fun RecentCard(spec: SourceSpec, selected: Boolean, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier) {
    val border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, Teal) else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Card(onClick = onClick, modifier = modifier, border = border,
        colors = CardDefaults.cardColors(containerColor = if (selected) Teal.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                SourceThumb(spec, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
                IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(30.dp).padding(2.dp)) {
                    Box(Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(4.dp)) {
                        Icon(Icons.Filled.Close, "Remover dos recentes", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Text(spec.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(spec.kind.label + if (selected) " · ativa" else "", style = MaterialTheme.typography.bodySmall,
                color = if (selected) Teal else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Prévia desenhada (não usa a GPU do motor) dos padrões de diagnóstico. */
@Composable
fun PatternPreview(p: PatternType, modifier: Modifier = Modifier) {
    val bars = listOf(Color.White, Color(0xFFFFFF00), Color(0xFF00FFFF), Color(0xFF00FF00), Color(0xFFFF00FF), Color.Red, Color.Blue, Color.Black)
    Canvas(modifier) {
        val w = size.width; val h = size.height
        when (p) {
            PatternType.COLOR_BARS, PatternType.NOISY_BARS -> {
                bars.forEachIndexed { i, col ->
                    val c = if (p == PatternType.NOISY_BARS) Color(0.2f + 0.6f * col.red, 0.2f + 0.6f * col.green, 0.2f + 0.6f * col.blue) else col
                    drawRect(c, Offset(i * w / 8f, 0f), Size(w / 8f + 1f, h * 0.85f))
                }
                drawRect(Color.Gray, Offset(0f, h * 0.85f), Size(w, h * 0.15f))
                if (p == PatternType.NOISY_BARS) { val r = Random(7); repeat(260) { drawCircle(Color.White.copy(alpha = 0.35f), 1.4f, Offset(r.nextFloat() * w, r.nextFloat() * h)) } }
            }
            PatternType.GRADIENT -> {
                drawRect(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            }
            PatternType.NUMBERED -> {
                drawRect(Color(0xFF1A1E28))
                drawRect(Color(0xFFFFC107), Offset(w * 0.06f, h * 0.08f), Size(w * 0.05f, h * 0.34f))
                drawRect(Color(0xFFFFC107), Offset(w * 0.06f, h * 0.08f), Size(w * 0.16f, h * 0.07f))
                drawRect(Color(0xFFFFC107), Offset(w * 0.06f, h * 0.22f), Size(w * 0.12f, h * 0.06f))
                repeat(6) { i -> drawRect(Color(0xFF33FF66), Offset(w * (0.16f + i * 0.11f), h * 0.4f), Size(w * 0.08f, h * 0.2f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f)) }
                val r = Random(3)
                repeat(32) { i -> drawRect(if (r.nextBoolean()) Color.White else Color.Black, Offset(w * (0.1f + i * 0.0275f), h * 0.78f), Size(w * 0.025f, h * 0.1f)) }
                drawRect(Color.Magenta, Offset(w * 0.03f, h * 0.76f), Size(w * 0.05f, h * 0.14f))
            }
        }
    }
}
