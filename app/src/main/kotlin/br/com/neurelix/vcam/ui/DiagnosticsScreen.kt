package br.com.neurelix.vcam.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.neurelix.vcam.App
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.core.diagnostics.DiagnosticsExporter
import br.com.neurelix.vcam.core.diagnostics.LogLevel
import br.com.neurelix.vcam.integration.privileged.PrivilegedBackend
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DiagnosticsScreen() {
    val c = App.controller
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m by c.metrics.collectAsStateWithLifecycle()
    val state by c.engineState.collectAsStateWithLifecycle()
    val logs by AppLog.entries.collectAsStateWithLifecycle()
    var channel by remember { mutableStateOf("todos") }
    var bench by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val channels = listOf("todos", "app", "engine", "camera2", "codec", "binder", "privileged")
    val shown = remember(logs, channel) { (if (channel == "todos") logs else logs.filter { it.channel == channel }).takeLast(200).asReversed() }

    fun share(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Exportar diagnóstico"))
    }

    ScreenColumn {
        SectionCard("Métricas reais do motor", "Estado: $state") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("FPS fonte", "%.1f".format(m.sourceFps), Modifier.weight(1f))
                MetricTile("FPS entregue", "%.1f".format(m.deliveredFps), Modifier.weight(1f))
                MetricTile("Descartados", "${m.dropped}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("FPS mín/máx", "%.0f/%.0f".format(m.minFps, m.maxFps), Modifier.weight(1f))
                MetricTile("σ FPS", "%.2f".format(m.fpsStdDev), Modifier.weight(1f))
                MetricTile("Latência", "%.1f (p95 %.1f) ms".format(m.latencyAvgMs, m.latencyP95Ms), Modifier.weight(1.4f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("CPU app", "%.0f%%".format(m.cpuPercent), Modifier.weight(1f))
                MetricTile("Memória", "%.0f MB".format(m.memoryMb), Modifier.weight(1f))
                MetricTile("Saídas", "${m.outputs}", Modifier.weight(1f))
            }
            Text("Fonte: ${m.sourceWidth}×${m.sourceHeight} · ${m.sourceFormat}", style = MaterialTheme.typography.labelSmall)
            Text("Saída: ${m.outputFormat}", style = MaterialTheme.typography.labelSmall)
            Text("Entregues no total: ${m.delivered}", style = MaterialTheme.typography.labelSmall)
        }

        SectionCard("Medição de redução de ruído", "Gera barras com ruído conhecido, mede o erro RMS antes/depois do filtro e restaura seu estado.") {
            Button(enabled = !busy, onClick = {
                busy = true; bench = "medindo…"
                scope.launch { bench = c.runNoiseBenchmark()?.toString() ?: "falhou (veja log)"; busy = false }
            }) { Text("Medir redução de ruído") }
            bench?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }

        SectionCard("Exportar") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    scope.launch { share(DiagnosticsExporter.exportText(File(ctx.cacheDir, "exports"), extras(ctx), m), "text/plain") }
                }) { Text("Texto") }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    scope.launch { share(DiagnosticsExporter.exportZip(File(ctx.cacheDir, "exports"), extras(ctx), m), "application/zip") }
                }) { Text("ZIP") }
            }
            Text("Caminhos privados, e-mails e URIs são removidos do arquivo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Log (aplicativo, serviço, Camera2, codecs, Binder)") {
            ChoiceChips("Canal", channels, channel, { it }) { channel = it }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(shown) { e ->
                    Text(e.format(), style = MaterialTheme.typography.labelSmall,
                        color = when (e.level) { LogLevel.ERROR -> Red; LogLevel.WARN -> Amber; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                }
            }
            OutlinedButton(onClick = { AppLog.clear() }) { Text("Limpar log") }
        }
    }
}

private fun extras(ctx: android.content.Context): Map<String, String> {
    val c = App.controller
    val probe = kotlinx.coroutines.runBlocking { PrivilegedBackend(ctx).checkAvailability() }
    return mapOf(
        "Estado" to "${c.engineState.value} · fonte=${c.sourceLabel.value} · running=${c.running.value}",
        "Saída" to c.output.value.toString(),
        "Filtros" to c.filters.value.toString(),
        "Integração privilegiada" to (probe.summary + "\n" + probe.evidence.joinToString("\n")),
        "Sessões ativas" to c.sessions.active.value.joinToString("\n") { "${it.pkg} streams=${it.streams} quadros=${it.delivered}" },
    )
}
