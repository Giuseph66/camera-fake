package br.com.neurelix.vcam.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.neurelix.vcam.App
import br.com.neurelix.vcam.core.model.EngineState
import br.com.neurelix.vcam.core.model.SourceKind
import br.com.neurelix.vcam.engine.SourceState
import br.com.neurelix.vcam.integration.IntegrationStatus
import br.com.neurelix.vcam.integration.privileged.PrivilegedBackend
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(onConfigure: () -> Unit, onSources: () -> Unit) {
    val c = App.controller
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val running by c.running.collectAsStateWithLifecycle()
    val state by c.engineState.collectAsStateWithLifecycle()
    val m by c.metrics.collectAsStateWithLifecycle()
    val spec by c.activeSpec.collectAsStateWithLifecycle()
    val srcState by c.sourceState.collectAsStateWithLifecycle()
    val err by c.engineError.collectAsStateWithLifecycle()
    val extOn by c.externalEnabled.collectAsStateWithLifecycle()
    val sessions by c.sessions.active.collectAsStateWithLifecycle()
    val paused by c.paused.collectAsStateWithLifecycle()
    var priv by remember { mutableStateOf<IntegrationStatus?>(null) }
    LaunchedEffect(Unit) { priv = PrivilegedBackend(ctx).checkAvailability() }

    val needsCamera = spec?.kind == SourceKind.CAMERA_BACK || spec?.kind == SourceKind.CAMERA_FRONT
    val (camGranted, requestCam) = rememberCameraPermission { ok ->
        if (ok) scope.launch { c.start() } else c.postMessage("Sem permissão de câmera a câmera física não pode ser usada")
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val preview: @Composable (Modifier) -> Unit = { mod ->
        Column(mod, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("FPS saída", "%.1f".format(m.deliveredFps), Modifier.weight(1f))
                MetricTile("Resolução", if (m.outputWidth > 0) "${m.outputWidth}×${m.outputHeight}" else "—", Modifier.weight(1f))
                MetricTile("Latência", if (m.latencyAvgMs > 0) "%.0f ms".format(m.latencyAvgMs) else "—", Modifier.weight(1f))
            }
        }
    }

    val controls: @Composable (Modifier) -> Unit = { mod ->
        Column(mod, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Banner de estado: diferencia local / externo / indisponível / erro
            when {
                state == EngineState.ERROR || (err != null && state != EngineState.SOURCE_LOST) ->
                    Banner("Erro de inicialização: ${err ?: srcState.let { (it as? SourceState.Error)?.message ?: "desconhecido" }}", Red)
                state == EngineState.SOURCE_LOST -> Banner("Fonte perdida — ${err ?: ""}", Amber)
                state == EngineState.RUNNING_EXTERNAL -> Banner("FONTE VIRTUAL ATIVA · integração externa: ${sessions.size} app(s) recebendo", Green)
                state == EngineState.RUNNING_LOCAL && running -> Banner("Processamento local ativo (nenhum app externo conectado)", Blue)
                else -> Banner("Parado", MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard("Fonte selecionada") {
                Text(spec?.let { "${it.kind.label}: ${it.label}" } ?: "Nenhuma fonte selecionada", style = MaterialTheme.typography.bodyMedium)
                if (running) Text("Estado da fonte: " + when (val s = srcState) {
                    SourceState.Idle -> "ociosa"; SourceState.Starting -> "iniciando"; SourceState.Running -> "ativa"
                    SourceState.Paused -> "em pausa"; is SourceState.Lost -> "perdida (${s.reason})"; is SourceState.Error -> "erro (${s.message})"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onSources) { Text("Trocar fonte") }
            }

            SectionCard("Estado da integração") {
                StatusRow("Câmera virtual (processamento local)",
                    if (running) "Ativa · ${m.sourceFormat}" else "Parada", if (running) Green else MaterialTheme.colorScheme.outline)
                StatusRow("Integração externa (SDK/Binder)",
                    when {
                        !extOn -> "Desligada"
                        !running -> "Ligada, sem fonte ativa"
                        sessions.isEmpty() -> "Ligada · aguardando consumidor autorizado"
                        else -> "Ativa · ${sessions.joinToString { it.label }}"
                    }, if (extOn && running && sessions.isNotEmpty()) Green else if (extOn) Amber else MaterialTheme.colorScheme.outline)
                StatusRow("Integração com o Android (câmera de sistema)",
                    priv?.summary ?: "verificando…", if (priv is IntegrationStatus.Active) Green else Red)
                StatusRow("Permissão de câmera",
                    if (camGranted) "Concedida" else if (needsCamera) "NECESSÁRIA para a fonte selecionada" else "Não necessária para esta fonte",
                    if (camGranted || !needsCamera) Green else Amber)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (!running) {
                    Button(modifier = Modifier.weight(1f).height(52.dp), enabled = spec != null, onClick = {
                        if (needsCamera && !camGranted) requestCam() else scope.launch { c.start() }
                    }) { Text("Iniciar") }
                } else {
                    Button(modifier = Modifier.weight(1f).height(52.dp), onClick = { scope.launch { c.stop() } },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Parar") }
                }
                OutlinedButton(modifier = Modifier.weight(1f).height(52.dp), onClick = onConfigure) { Text("Configurar") }
            }
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { scope.launch { c.captureToGallery() } }) { Text("Capturar quadro") }
                    if (c.supportsPause()) OutlinedButton(modifier = Modifier.weight(1f), onClick = { scope.launch { c.togglePause() } }) {
                        Text(if (paused) "Retomar vídeo" else "Pausar vídeo")
                    }
                }
            }
            if (running || extOn) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(), onClick = { c.emergencyStop() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                ) { Text("DESLIGAR TUDO (fecha sessões externas)") }
            }
        }
    }

    if (landscape) {
        ScreenColumn {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                preview(Modifier.weight(1f)); controls(Modifier.weight(1f))
            }
        }
    } else {
        ScreenColumn { preview(Modifier.fillMaxWidth()); controls(Modifier.fillMaxWidth()) }
    }
}
