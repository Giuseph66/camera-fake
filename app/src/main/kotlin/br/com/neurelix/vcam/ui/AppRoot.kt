package br.com.neurelix.vcam.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.neurelix.vcam.App

private data class Tab(val title: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Painel", Icons.Filled.Dashboard),
    Tab("Fontes", Icons.Filled.Image),
    Tab("Efeitos", Icons.Filled.AutoFixHigh),
    Tab("Integração", Icons.Filled.Hub),
    Tab("Diagnóstico", Icons.Filled.BugReport),
)
const val TAB_SETTINGS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(tabState: MutableIntState) {
    val controller = App.controller
    val snackbar = remember { SnackbarHostState() }
    val msg by controller.userMessage.collectAsStateWithLifecycle()
    LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); controller.consumeMessage() } }
    val tab = tabState.intValue

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (tab == TAB_SETTINGS) "Ajustes avançados" else "NEURELIX · Câmera Virtual") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { tabState.intValue = if (tab == TAB_SETTINGS) 0 else TAB_SETTINGS }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ajustes avançados")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tabState.intValue = i },
                        icon = { Icon(t.icon, contentDescription = t.title) },
                        label = { Text(t.title, maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> DashboardScreen(onConfigure = { tabState.intValue = TAB_SETTINGS }, onSources = { tabState.intValue = 1 })
                1 -> SourcesScreen()
                2 -> EffectsScreen()
                3 -> IntegrationScreen()
                4 -> DiagnosticsScreen()
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
fun ScreenColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** Retorna (granted, request) para a permissão de câmera, pedindo somente quando necessário. */
@Composable
fun rememberCameraPermission(onResult: (Boolean) -> Unit = {}): Pair<Boolean, () -> Unit> {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; onResult(it) }
    return granted to { launcher.launch(Manifest.permission.CAMERA) }
}
