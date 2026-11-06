package br.com.neurelix.vcam.capture

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.neurelix.vcam.App
import br.com.neurelix.vcam.MainActivity
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.ui.Amber
import br.com.neurelix.vcam.ui.Banner
import br.com.neurelix.vcam.ui.NeurelixTheme
import br.com.neurelix.vcam.ui.PreviewSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Atende android.media.action.IMAGE_CAPTURE: o app chamador pede "uma foto"; o usuário vê explicitamente que é a
 * Câmera Virtual NEURELIX e confirma. Entrega o quadro processado da fonte ativa em EXTRA_OUTPUT (ou miniatura em "data").
 */
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val output: Uri? = androidx.core.content.IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java)
        AppLog.i("app", "capture-intent", "IMAGE_CAPTURE recebido de ${callingPackage ?: "?"} (EXTRA_OUTPUT=${output != null})")
        setContent {
            NeurelixTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    CaptureScreen(
                        caller = callingPackage ?: "app desconhecido",
                        onCancel = { setResult(Activity.RESULT_CANCELED); finish() },
                        onOpenApp = { startActivity(Intent(this, MainActivity::class.java)) },
                        onAccept = { bmp -> deliver(bmp, output) },
                    )
                }
            }
        }
    }

    private suspend fun deliver(bmp: Bitmap, output: Uri?) {
        withContext(Dispatchers.IO) {
            try {
                if (output != null) {
                    contentResolver.openOutputStream(output, "wt")?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: error("URI de saída inválido")
                    setResult(Activity.RESULT_OK)
                } else {
                    val k = 320f / maxOf(bmp.width, bmp.height)
                    val thumb = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
                    setResult(Activity.RESULT_OK, Intent().putExtra("data", thumb))
                }
                AppLog.i("app", "capture-intent", "imagem entregue ao app chamador (${bmp.width}x${bmp.height})")
            } catch (t: Throwable) {
                AppLog.e("app", "capture-intent", "falha ao entregar imagem", t)
                setResult(Activity.RESULT_CANCELED)
            }
        }
        finish()
    }
}

@Composable
private fun CaptureScreen(caller: String, onCancel: () -> Unit, onOpenApp: () -> Unit, onAccept: suspend (Bitmap) -> Unit) {
    val c = App.controller
    val running by c.running.collectAsStateWithLifecycle()
    val label by c.sourceLabel.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("NEURELIX Câmera Virtual", style = MaterialTheme.typography.headlineSmall)
        Text("O app “$caller” pediu uma foto. Esta NÃO é a câmera física: é a fonte virtual escolhida por você.", style = MaterialTheme.typography.bodyMedium)
        if (running) {
            Banner("Fonte virtual: $label", Amber)
            PreviewSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(modifier = Modifier.weight(1f), onClick = {
                    scope.launch { c.engine.capture()?.let { onAccept(it) } ?: c.postMessage("Sem quadro disponível") }
                }) { Text("Usar esta imagem") }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onCancel) { Text("Cancelar") }
            }
        } else {
            Banner("Nenhuma fonte ativa. Abra o NEURELIX e pressione Iniciar.", Amber)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpenApp) { Text("Abrir NEURELIX") }
                OutlinedButton(onClick = onCancel) { Text("Cancelar") }
            }
        }
    }
}
