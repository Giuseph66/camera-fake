package br.com.neurelix.vcam.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import br.com.neurelix.vcam.App

@Composable
fun SectionCard(title: String, subtitle: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun StatusRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(color)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun SliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit,
    format: (Float) -> String = { "%.2f".format(it) }, onReset: (() -> Unit)? = null,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(format(value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(text(o)) }) }
        }
    }
}

@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.16f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(color)
        Text(text, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

/** Preview local: Surface do SurfaceView vira uma saída (não-externa) do motor. */
@Composable
fun PreviewSurface(modifier: Modifier = Modifier) {
    val controller = App.controller
    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) { controller.attachPreview(h.surface) }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) { controller.detachPreview() }
                })
            }
        },
    )
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

// ------------------------------------------------------------------------------------------------
// Identidade visual NEURELIX
// ------------------------------------------------------------------------------------------------

/** Marca: lente (anel turquesa + núcleo violeta + brilho). Mesma forma do ícone do app. */
@Composable
fun NvLogo(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(40.dp)) {
        val c = center; val r = size.minDimension / 2f
        drawCircle(androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Teal, Violet, Teal), c), radius = r)
        drawCircle(Color(0xFF101826), radius = r * 0.72f)
        drawCircle(Violet, radius = r * 0.42f)
        drawCircle(Color.White, radius = r * 0.11f, center = c.copy(x = c.x - r * 0.14f, y = c.y - r * 0.14f))
    }
}

/** Cabeçalho de marca: logo + wordmark + título/subtítulo da tela + indicador "ao vivo" + ajustes. */
@Composable
fun NvHeader(title: String, subtitle: String, live: Boolean, onSettings: () -> Unit, settingsActive: Boolean) {
    val bg = androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(Violet.copy(alpha = 0.22f), Teal.copy(alpha = 0.06f), Color.Transparent))
    Row(
        Modifier.fillMaxWidth().background(bg).androidx_statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NvLogo()
        Column(Modifier.weight(1f)) {
            Text("NEURELIX", style = MaterialTheme.typography.labelSmall, color = Teal, letterSpacing = 2.sp)
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (live) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Green.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) { StatusDot(Green); Text("VIVO", style = MaterialTheme.typography.labelSmall, color = Green) }
        }
        androidx.compose.material3.IconButton(onClick = onSettings) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Settings, contentDescription = "Ajustes avançados",
                tint = if (settingsActive) Teal else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Modifier.androidx_statusBarsPadding(): Modifier = this.then(Modifier.statusBarsPadding())

/** Título de seção com barra de acento. */
@Composable
fun SectionLabel(text: String, hint: String? = null) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(width = 4.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(Teal))
            Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp)
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
    }
}

/** Miniatura da fonte (foto/vídeo da galeria). Mostra ícone enquanto carrega ou se o arquivo sumiu. */
@Composable
fun SourceThumb(spec: br.com.neurelix.vcam.core.model.SourceSpec, modifier: Modifier = Modifier, px: Int = 360) {
    var failed by androidx.compose.runtime.remember(spec.id) { androidx.compose.runtime.mutableStateOf(false) }
    val bmp by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, spec.id) {
        value = Thumbs.load(spec, px)
        failed = value == null
    }
    val isVideo = spec.kind == br.com.neurelix.vcam.core.model.SourceKind.VIDEO
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) androidx.compose.foundation.Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().height(IntrinsicSize.Max), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Warning, null, tint = Red)
            Text("Arquivo inválido", style = MaterialTheme.typography.bodySmall, color = Red)
        } else androidx.compose.material3.Icon(
            if (isVideo) androidx.compose.material.icons.Icons.Filled.Videocam else androidx.compose.material.icons.Icons.Filled.Image,
            null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isVideo && b != null) Box(
            Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp)
        ) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}

/** Cartão fixo de fonte (câmeras, padrões). */
@Composable
fun SourceCard(
    title: String, subtitle: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, preview: @Composable BoxScope.() -> Unit,
) {
    val border = if (selected) BorderStroke(2.dp, Teal) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Card(
        onClick = onClick, modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = if (selected) Teal.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainer),
        border = border,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center, content = preview)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (selected) Teal else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.CheckCircle, null, tint = Teal, modifier = Modifier.size(18.dp))
            }
        }
    }
}
