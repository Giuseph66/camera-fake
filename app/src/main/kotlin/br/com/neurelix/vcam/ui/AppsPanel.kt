package br.com.neurelix.vcam.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import br.com.neurelix.vcam.App
import br.com.neurelix.vcam.core.diagnostics.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** Apps que o patcher já sabe modificar (hook validado no aparelho). Chave = pacote original. */
private data class Supported(val patchedPackage: String, val name: String, val note: String)

private val SUPPORTED = mapOf(
    "net.sourceforge.opencamera" to Supported("br.com.neurelix.opencamera", "Open Camera 1.56.2",
        "Hook validado: preview e foto vêm da fonte virtual. Sem vídeo."),
)

/** Mesma lista de exclusão do patcher: apps de identidade/pagamento nunca são oferecidos. */
private val DENYLIST = listOf(
    "com.google.android.apps.walletnfcrel", "br.gov.", "com.nu.", "br.com.bradesco", "br.com.sicoob",
    "com.bradesco", "br.com.vivo", "com.paypal", "com.samsung.android.spay", "br.com.honda", "com.salesforce", "br.com.sim.",
)

private fun isBlockedPackage(pkg: String): Boolean = DENYLIST.any { prefix ->
    val root = prefix.trimEnd('.')
    pkg == root || pkg.startsWith("$root.")
}

private data class AppEntry(
    val pkg: String, val label: String, val icon: ImageBitmap?, val apkPath: String,
    val blocked: Boolean, val supported: Supported?, val isNeurelixVariant: Boolean,
)

private suspend fun loadApps(ctx: Context): List<AppEntry> = withContext(Dispatchers.IO) {
    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.distinct()
        .filter { it != ctx.packageName && it != "br.com.neurelix.vcam.consumer" }
        .mapNotNull { pkg ->
            runCatching {
                val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                val ai = pi.applicationInfo ?: return@runCatching null
                val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                val usesCamera = pi.requestedPermissions?.contains("android.permission.CAMERA") == true
                if (system || !usesCamera) return@runCatching null
                AppEntry(
                    pkg, pm.getApplicationLabel(ai).toString(),
                    runCatching { pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
                    ai.sourceDir, isBlockedPackage(pkg), SUPPORTED[pkg], pkg.startsWith("br.com.neurelix.opencamera"),
                )
            }.getOrNull()
        }
        .sortedWith(compareBy({ it.isNeurelixVariant }, { it.supported == null }, { it.label.lowercase() }))
}

@Composable
fun IntegrationGuide() {
    var way by remember { mutableStateOf(0) }
    val ways = listOf("Modificar um app", "App com SDK", "App que pede foto")
    SectionCard("Como integrar outro aplicativo", "A câmera virtual só chega a apps que foram preparados para recebê-la. Escolha o caminho:") {
        ChoiceChips("Caminho", ways, ways[way], { it }) { way = ways.indexOf(it) }
        when (way) {
            0 -> Steps(
                "Cria uma cópia do app (pacote e assinatura novos) que sempre usa a câmera virtual. O original continua intacto.",
                listOf(
                    "Na lista abaixo, toque no app (hoje só o Open Camera tem hook validado).",
                    "Exporte o APK e rode o comando mostrado no seu PC (o patcher usa apktool, que não roda no celular).",
                    "Abra a cópia: o pedido aparece em “Pedidos pendentes”. Toque em Permitir.",
                    "Escolha a fonte na aba Fontes e pressione Iniciar. A cópia passa a usar essa fonte.",
                ),
            )
            1 -> Steps(
                "Para quem desenvolve o app: ele pede uma sessão e entrega uma Surface onde a fonte é desenhada.",
                listOf(
                    "Dependa do módulo integration-api (já traz as <queries> para enxergar o NEURELIX).",
                    "VirtualCameraClient(context).openSession(\"Meu app\") → attach(surface, 1280, 720).",
                    "O usuário autoriza o app em “Pedidos pendentes”. Captura: session.captureJpeg().",
                ),
            )
            else -> Steps(
                "Apps que usam o botão “Tirar foto” do sistema (ACTION_IMAGE_CAPTURE) podem listar o NEURELIX como câmera.",
                listOf(
                    "Inicie uma fonte no Painel.",
                    "No outro app, ao pedir a câmera do sistema, escolha “NEURELIX Câmera Virtual”.",
                    "Você vê a fonte, confirma, e só então a imagem é entregue. Não serve para câmera embutida do app (preview ao vivo).",
                ),
            )
        }
        Text("O que NÃO funciona: apps com câmera própria (WhatsApp, navegadores, câmeras de terceiros) não recebem a fonte sem uma dessas três preparações. Não existe câmera global sem root.",
            style = MaterialTheme.typography.bodySmall, color = Amber)
    }
}

@Composable
private fun Steps(intro: String, steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(intro, style = MaterialTheme.typography.bodyMedium)
        steps.forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(22.dp).background(Teal.copy(alpha = 0.18f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = Teal)
                }
                Text(s, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun InstalledAppsPanel() {
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) { value = loadApps(ctx) }
    var selected by remember { mutableStateOf<AppEntry?>(null) }
    var showOthers by remember { mutableStateOf(false) }
    SectionCard("Aplicativos no aparelho", "Apps com acesso à câmera (sem apps de sistema). Toque para ver como integrar.") {
        val list = apps
        when {
            list == null -> Text("Carregando…", style = MaterialTheme.typography.bodySmall)
            list.isEmpty() -> Text("Nenhum app com câmera encontrado.", style = MaterialTheme.typography.bodySmall)
            else -> {
                val ready = list.filter { it.supported != null || it.isNeurelixVariant }
                val others = list - ready.toSet()
                SectionLabel("Compatíveis (${ready.size})")
                if (ready.isEmpty()) Text("Nenhum app compatível instalado. O Open Camera é o único com hook validado.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ready.forEach { a -> AppRow(a) { selected = a } }
                TextButton(onClick = { showOthers = !showOthers }) { Text(if (showOthers) "Ocultar outros apps" else "Outros apps com câmera (${others.size}) — sem hook validado") }
                if (showOthers) others.forEach { a -> AppRow(a) { selected = a } }
            }
        }
    }
    selected?.let { AppDialog(it) { selected = null } }
}

@Composable
private fun AppRow(a: AppEntry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val patchedInstalled = remember(a.pkg) { a.supported?.let { runCatching { ctx.packageManager.getPackageInfo(it.patchedPackage, 0) }.isSuccess } == true }
    val (chip, color) = when {
        a.isNeurelixVariant -> "Variante NEURELIX" to Teal
        a.blocked -> "Bloqueado" to Red
        a.supported != null && patchedInstalled -> "Integrado" to Green
        a.supported != null -> "Compatível" to Blue
        else -> "Sem hook" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (a.icon != null) Image(a.icon, null, Modifier.size(40.dp)) else Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f)) {
            Text(if (a.isNeurelixVariant) "${a.label} (cópia NEURELIX)" else a.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(a.pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(chip, style = MaterialTheme.typography.labelSmall, color = color,
            modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp))
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppDialog(a: AppEntry, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exported by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val sup = a.supported
    val serial = "SERIAL_DO_APARELHO"
    val outName = "${a.pkg.substringAfterLast('.')}-neurelix.apk"
    val patchedInstalled = sup?.let { runCatching { ctx.packageManager.getPackageInfo(it.patchedPackage, 0) }.isSuccess } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        title = { Text(a.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(a.pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    a.blocked -> Text("App de identidade/pagamento: o NEURELIX não o modifica e não deve ser usado para burlar biometria, prova de vida ou antifraude.", color = Red, style = MaterialTheme.typography.bodyMedium)
                    a.isNeurelixVariant -> {
                        Text("Esta já é uma cópia preparada pelo NEURELIX. Ela sempre pede a câmera virtual; se não houver fonte ativa ou autorização, volta para a câmera física.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let { ctx.startActivity(it) } }) { Text("Abrir") }
                    }
                    sup == null -> {
                        Text("Hook ainda não validado. O fluxo experimental detecta padrões Camera2, tenta gerar uma cópia e valida no aparelho; pode recusar APIs não suportadas.", style = MaterialTheme.typography.bodyMedium)
                        Text("1. Exportar o APK original", style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { exportApk(ctx, a) }.onSuccess { exported = it; err = null }.onFailure { err = it.message }
                            }
                        }) { Text(if (exported == null) "Exportar APK" else "Exportado ✓") }
                        err?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
                        if (exported != null) {
                            val auto = "python3 patcher/auto_pipeline.py --serial $serial --only ${a.pkg}.apk"
                            Text("2. No PC, dentro do projeto", style = MaterialTheme.typography.titleSmall)
                            CodeBlock(auto, ctx)
                            Text("Troque SERIAL_DO_APARELHO pelo resultado de `adb devices`. O pipeline inspeciona, tenta o hook, instala a cópia e valida. O APK original não é alterado.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        Text("${sup.name} — ${sup.note}", style = MaterialTheme.typography.bodyMedium)
                        Text("1. Exportar o APK original", style = MaterialTheme.typography.titleSmall)
                        Text("Copia o APK para a pasta do NEURELIX no armazenamento. Se o app já veio modificado por LSPatch, extrai o APK original de dentro dele.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { exportApk(ctx, a) }.onSuccess { exported = it; err = null }.onFailure { err = it.message }
                            }
                        }) { Text(if (exported == null) "Exportar APK" else "Exportado ✓") }
                        err?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
                        if (exported != null) {
                            val pull = "adb pull $exported ."
                            val patch = "python3 patcher/patch_app.py ${a.pkg}.apk ${sup.patchedPackage} out/$outName --install $serial"
                            Text("2. No PC, dentro do projeto", style = MaterialTheme.typography.titleSmall)
                            CodeBlock(pull, ctx); CodeBlock(patch, ctx)
                            Text("(troque SERIAL_DO_APARELHO pelo resultado de `adb devices`)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("3. Depois de instalar", style = MaterialTheme.typography.titleSmall)
                            Text("Abra a cópia “${sup.name}”. O NEURELIX coloca o pedido em Pedidos pendentes: toque em Permitir. Escolha a fonte em Fontes e pressione Iniciar.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (patchedInstalled) {
                            Text("A cópia já está instalada (${sup.patchedPackage}).", color = Green, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { ctx.packageManager.getLaunchIntentForPackage(sup.patchedPackage)?.let { ctx.startActivity(it) } }) { Text("Abrir cópia integrada") }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun CodeBlock(text: String, ctx: Context) {
    Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        androidx.compose.material3.IconButton(onClick = {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("comando", text))
            App.controller.postMessage("Comando copiado")
        }) { Icon(Icons.Filled.ContentCopy, "Copiar", Modifier.size(18.dp)) }
    }
}

/** Copia o APK do app para Android/data/<nosso pacote>/files/apks (acessível por adb pull). Retorna o caminho. */
private suspend fun exportApk(ctx: Context, a: AppEntry): String = withContext(Dispatchers.IO) {
    val dir = ctx.getExternalFilesDir("apks") ?: error("armazenamento externo indisponível")
    dir.mkdirs()
    val dst = File(dir, "${a.pkg}.apk")
    val src = File(a.apkPath)
    ZipFile(src).use { z ->
        val inner = z.getEntry("assets/lspatch/origin.apk")
        if (inner != null) {
            z.getInputStream(inner).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            AppLog.i("app", "export", "APK original extraído do contêiner LSPatch (${dst.length()} bytes)")
        } else src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
    }
    "/sdcard/Android/data/${ctx.packageName}/files/apks/${a.pkg}.apk"
}
