package br.com.neurelix.vcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.neurelix.vcam.App
import br.com.neurelix.vcam.core.model.AuthorizedConsumer
import br.com.neurelix.vcam.core.model.PendingConsumer
import br.com.neurelix.vcam.integration.IntegrationStatus
import br.com.neurelix.vcam.integration.local.LocalBackend
import br.com.neurelix.vcam.integration.privileged.PrivilegedBackend
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun IntegrationScreen() {
    val c = App.controller
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val extOn by c.externalEnabled.collectAsStateWithLifecycle()
    val running by c.running.collectAsStateWithLifecycle()
    val pending by c.settings.pending.collectAsStateWithLifecycle(emptyList())
    val authorized by c.settings.authorized.collectAsStateWithLifecycle(emptyList())
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val history by c.settings.sessions.collectAsStateWithLifecycle(emptyList())

    val local = remember { LocalBackend(c) }
    val priv = remember { PrivilegedBackend(ctx) }
    var localSt by remember { mutableStateOf<IntegrationStatus?>(null) }
    var consSt by remember { mutableStateOf<IntegrationStatus?>(null) }
    var privSt by remember { mutableStateOf<IntegrationStatus?>(null) }
    LaunchedEffect(extOn, running, active.size) {
        localSt = local.checkAvailability(); consSt = c.sessions.checkAvailability(); privSt = priv.checkAvailability()
    }
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }

    ScreenColumn {
        IntegrationGuide()
        InstalledAppsPanel()

        SectionCard("Ativar / desativar a fonte para apps integrados") {
            Text("Quando ligada, apps que usam o SDK NEURELIX e que você autorizar abaixo podem receber a fonte numa Surface própria. Um aviso permanente fica visível enquanto a fonte estiver ativa.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow("Permitir consumidores integrados", extOn, { c.setExternalEnabled(it) })
            if (extOn && !running) Text("Pressione Iniciar no Painel para disponibilizar a fonte.", style = MaterialTheme.typography.bodySmall, color = Amber)
            OutlinedButton(onClick = { c.emergencyStop() }, modifier = Modifier.fillMaxWidth()) { Text("Desligar tudo agora") }
        }

        SectionCard("Pedidos pendentes") {
            if (pending.isEmpty()) Text("Nenhum app pediu acesso.", style = MaterialTheme.typography.bodySmall)
            pending.forEach { p -> PendingRow(p) }
        }

        SectionCard("Apps autorizados") {
            if (authorized.isEmpty()) Text("Nenhum app autorizado.", style = MaterialTheme.typography.bodySmall)
            authorized.forEach { a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = MaterialTheme.typography.bodyMedium)
                        Text("${a.packageName}\ncert ${a.certSha256.take(16)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { scope.launch { c.settings.revoke(a.packageName); c.sessions.closeAll("autorização revogada") } }) { Text("Revogar") }
                }
            }
        }

        SectionCard("Sessões ativas agora") {
            if (active.isEmpty()) Text("Nenhuma.", style = MaterialTheme.typography.bodySmall)
            active.forEach { s ->
                Text("${s.label} (${s.pkg}) · ${s.streams} stream(s) · ${s.delivered} quadros", style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionCard("Tipos de integração", "Cada tipo mostra o que realmente alcança. Nenhum deles registra uma câmera global sem root.") {
            BackendRow(local.title, local.scope, localSt)
            BackendRow(c.sessions.title, c.sessions.scope, consSt)
            BackendRow(priv.title, priv.scope, privSt)
        }

        SectionCard("Compatibilidade comprovada por aplicativo", "Baseada em sessões reais registradas. Sem registro = não comprovado.") {
            val proven = history.filter { it.framesDelivered > 0 }.groupBy { it.consumerPackage }
            if (proven.isEmpty()) Text("Nenhum consumidor testado ainda neste aparelho.", style = MaterialTheme.typography.bodySmall)
            proven.forEach { (pkg, list) ->
                StatusRow(pkg, "Comprovado: ${list.size} sessão(ões), ${list.sumOf { it.framesDelivered }} quadros entregues", Green)
            }
            Text("Apps que NÃO usam o SDK (WhatsApp, navegadores, câmeras de terceiros etc.) continuam usando a câmera física: a integração é por SDK, não global.",
                style = MaterialTheme.typography.bodySmall, color = Amber)
        }

        SectionCard("Histórico de sessões") {
            if (history.isEmpty()) Text("Vazio.", style = MaterialTheme.typography.bodySmall)
            history.take(15).forEach { r ->
                Text("${fmt.format(Date(r.startedAtMs))} · ${r.consumerPackage.substringAfterLast('.')} · ${r.framesDelivered} quadros · ${r.note}",
                    style = MaterialTheme.typography.labelSmall)
            }
            if (history.isNotEmpty()) TextButton(onClick = { scope.launch { c.settings.clearSessions() } }) { Text("Limpar histórico") }
        }
    }
}

@Composable
private fun BackendRow(title: String, scope: String, st: IntegrationStatus?) {
    val color = when (st) { is IntegrationStatus.Active -> Green; is IntegrationStatus.Available -> Blue; is IntegrationStatus.Unavailable -> Red; null -> MaterialTheme.colorScheme.outline }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusRow(title, st?.summary ?: "verificando…", color)
        Text(scope, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        st?.evidence?.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PendingRow(p: PendingConsumer) {
    val c = App.controller
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${p.label} — ${p.packageName}", style = MaterialTheme.typography.bodyMedium)
        Text("Certificado SHA-256: ${p.certSha256.take(32)}…", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { c.settings.approve(AuthorizedConsumer(p.packageName, p.certSha256, p.label, System.currentTimeMillis())) } }) { Text("Permitir") }
            OutlinedButton(onClick = { scope.launch { c.settings.revoke(p.packageName) } }) { Text("Recusar") }
        }
    }
}
