package br.com.neurelix.vcam

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Surface
import androidx.core.content.ContextCompat
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.core.model.EngineMetrics
import br.com.neurelix.vcam.core.model.EngineState
import br.com.neurelix.vcam.core.model.FilterParams
import br.com.neurelix.vcam.core.model.FilterProfile
import br.com.neurelix.vcam.core.model.FitMode
import br.com.neurelix.vcam.core.model.OutputSettings
import br.com.neurelix.vcam.core.model.PatternType
import br.com.neurelix.vcam.core.model.SourceKind
import br.com.neurelix.vcam.core.model.SourceSpec
import br.com.neurelix.vcam.core.storage.SettingsRepository
import br.com.neurelix.vcam.engine.FrameSource
import br.com.neurelix.vcam.engine.NoiseBenchmark
import br.com.neurelix.vcam.engine.PausableSource
import br.com.neurelix.vcam.engine.RenderEngine
import br.com.neurelix.vcam.engine.SourceState
import br.com.neurelix.vcam.engine.sources.PhysicalCameraSource
import br.com.neurelix.vcam.engine.sources.StaticImageSource
import br.com.neurelix.vcam.engine.sources.TestPatternSource
import br.com.neurelix.vcam.engine.sources.VideoFileSource
import br.com.neurelix.vcam.integration.consumers.ConsumerSessionManager
import br.com.neurelix.vcam.service.VirtualCameraService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Central de controle (processo único). Dona do [RenderEngine], da fonte ativa, do preview local e da
 * integração externa. A UI e o serviço Binder observam/comandam por aqui.
 */
class EngineController(private val app: Application) {
    val settings = SettingsRepository(app)
    val engine = RenderEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val opMutex = Mutex()

    // Estado otimista síncrono (evita perder atualizações consecutivas); persistido de forma assíncrona e na mesma ordem.
    private val _output = MutableStateFlow(OutputSettings())
    private val _filters = MutableStateFlow(FilterParams())
    val output: StateFlow<OutputSettings> = _output
    val filters: StateFlow<FilterParams> = _filters
    val activeSpec: StateFlow<SourceSpec?> = settings.activeSource.stateIn(scope, SharingStarted.Eagerly, null)
    val recents: StateFlow<List<SourceSpec>> = settings.recents.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val profiles: StateFlow<List<FilterProfile>> = settings.profiles.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val externalEnabled: StateFlow<Boolean> = settings.externalEnabled.stateIn(scope, SharingStarted.Eagerly, false)

    val engineState: StateFlow<EngineState> = engine.state
    val metrics: StateFlow<EngineMetrics> = engine.metrics
    val engineError: StateFlow<String?> = engine.lastError

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _sourceState = MutableStateFlow<SourceState>(SourceState.Idle)
    val sourceState: StateFlow<SourceState> = _sourceState
    private val _sourceLabel = MutableStateFlow("Nenhuma")
    val sourceLabel: StateFlow<String> = _sourceLabel
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    private var current: FrameSource? = null
    private var stateJob: Job? = null
    private var lastOutputKey = ""

    val sessions = ConsumerSessionManager(app, this)

    init {
        // leitura única e rápida no boot, antes de qualquer atualização
        kotlinx.coroutines.runBlocking { _output.value = settings.output.first(); _filters.value = settings.filters.first() }
        scope.launch {
            output.collect { o ->
                engine.setOutput(o)
                val key = "${o.width}x${o.height}@${o.effectiveFps}|${o.loopVideo}"
                if (lastOutputKey.isNotEmpty() && key != lastOutputKey) restartSourceIfNeeded()
                lastOutputKey = key
            }
        }
        scope.launch { filters.collect { engine.setFilters(it) } }
        scope.launch {
            externalEnabled.collect { on -> if (!on) sessions.closeAll("Integração externa desativada pelo usuário") }
        }
    }

    fun buildSource(spec: SourceSpec): FrameSource = when (spec.kind) {
        SourceKind.IMAGE -> StaticImageSource(File(requireNotNull(spec.path)), spec.label)
        SourceKind.VIDEO -> VideoFileSource(app, File(requireNotNull(spec.path)), output.value.loopVideo, spec.label)
        SourceKind.CAMERA_BACK -> PhysicalCameraSource(app, true) { output.value }
        SourceKind.CAMERA_FRONT -> PhysicalCameraSource(app, false) { output.value }
        SourceKind.PATTERN -> TestPatternSource(spec.pattern)
    }

    // ---------------------------------------------------------------- ciclo de vida

    /** Inicia a fonte ativa. Retorna mensagem de erro ou null. */
    suspend fun start(): String? = opMutex.withLock {
        val spec = activeSpec.value ?: return@withLock "Selecione uma fonte antes de iniciar".also { _userMessage.value = it }
        if (_running.value) return@withLock null   // idempotente
        startLocked(spec)
    }

    private suspend fun startLocked(spec: SourceSpec): String? {
        return try {
            _paused.value = false
            val src = buildSource(spec)
            ContextCompat.startForegroundService(app, Intent(app, VirtualCameraService::class.java).setAction(VirtualCameraService.ACTION_START))
            engine.setSource(src)
            current = src
            observe(src)
            _running.value = true
            _userMessage.value = null
            AppLog.i("app", "controller", "iniciado: ${spec.label} (${spec.kind})")
            null
        } catch (t: Throwable) {
            current = null
            _running.value = false
            val msg = t.message ?: t.javaClass.simpleName
            _userMessage.value = msg
            AppLog.e("app", "controller", "falha ao iniciar", t)
            stopServiceIfIdle()
            msg
        }
    }

    suspend fun stop() = opMutex.withLock { stopLocked("parado pelo usuário") }

    private suspend fun stopLocked(reason: String) {
        sessions.closeAll("Fonte parada: $reason")
        runCatching { engine.setSource(null) }
        stateJob?.cancel(); current = null
        _running.value = false; _paused.value = false
        _sourceState.value = SourceState.Idle
        _sourceLabel.value = "Nenhuma"
        stopServiceIfIdle()
        AppLog.i("app", "controller", "motor parado ($reason). Câmera física liberada.")
    }

    /** Desligamento imediato: fecha sessões externas, desativa integração e para tudo. */
    fun emergencyStop() {
        scope.launch {
            settings.setExternalEnabled(false)
            stop()
        }
    }

    private fun stopServiceIfIdle() {
        ContextCompat.startForegroundService(app, Intent(app, VirtualCameraService::class.java).setAction(VirtualCameraService.ACTION_STOP_SERVICE))
    }

    fun selectSource(spec: SourceSpec) {
        scope.launch {
            opMutex.withLock {
                settings.setActiveSource(spec)
                settings.addRecent(spec)
                if (_running.value) {
                    val err = switchLocked(spec)
                    if (err != null) AppLog.w("app", "controller", "troca de fonte falhou: $err")
                }
            }
        }
    }

    private suspend fun switchLocked(spec: SourceSpec): String? = try {
        _paused.value = false
        val src = buildSource(spec)
        ContextCompat.startForegroundService(app, Intent(app, VirtualCameraService::class.java).setAction(VirtualCameraService.ACTION_START))
        engine.setSource(src)    // para a antiga antes de iniciar a nova; sem reiniciar o app/serviço
        current = src; observe(src)
        _userMessage.value = null
        AppLog.i("app", "controller", "fonte trocada para ${spec.label}")
        null
    } catch (t: Throwable) {
        current = null; _running.value = false
        _userMessage.value = t.message
        t.message ?: "erro"
    }

    private fun restartSourceIfNeeded() {
        val spec = activeSpec.value ?: return
        if (!_running.value) return
        if (spec.kind != SourceKind.CAMERA_BACK && spec.kind != SourceKind.CAMERA_FRONT && spec.kind != SourceKind.VIDEO) return
        scope.launch { opMutex.withLock { switchLocked(spec) } }
    }

    private fun observe(src: FrameSource) {
        stateJob?.cancel()
        _sourceLabel.value = src.label
        stateJob = scope.launch { src.state.collect { _sourceState.value = it } }
    }

    suspend fun togglePause() {
        val src = current as? PausableSource ?: return
        val np = !_paused.value
        src.setPaused(np); _paused.value = np
    }

    fun supportsPause(): Boolean = current is PausableSource

    // ---------------------------------------------------------------- preview local

    fun attachPreview(surface: Surface): String? =
        engine.addOutput(PREVIEW_ID, surface, external = false, fit = FitMode.FIT)

    fun detachPreview() = engine.removeOutput(PREVIEW_ID)

    // ---------------------------------------------------------------- configurações

    fun updateOutput(f: (OutputSettings) -> OutputSettings) { val n = _output.updateAndGet(f); scope.launch { settings.setOutput(n) } }
    fun updateFilters(f: (FilterParams) -> FilterParams) { val n = _filters.updateAndGet { f(it).sanitized() }; scope.launch { settings.setFilters(n) } }
    fun saveProfile(name: String) { scope.launch { settings.saveProfile(FilterProfile(name, filters.value)) } }
    fun applyProfile(p: FilterProfile) { updateFilters { p.params } }
    fun deleteProfile(name: String) { scope.launch { settings.deleteProfile(name) } }
    fun setExternalEnabled(v: Boolean) { scope.launch { settings.setExternalEnabled(v) } }
    fun consumeMessage() { _userMessage.value = null }
    fun postMessage(m: String) { _userMessage.value = m }

    // ---------------------------------------------------------------- importação

    /** Copia mídia escolhida (Photo Picker) para o armazenamento privado do app. */
    suspend fun importMedia(uri: Uri, kind: SourceKind): SourceSpec? = withContext(Dispatchers.IO) {
        try {
            val dir = File(app.filesDir, "sources").apply { mkdirs() }
            val mime = app.contentResolver.getType(uri) ?: ""
            val ext = when {
                mime.contains("png") -> "png"; mime.contains("webp") -> "webp"; mime.contains("heic") -> "heic"
                mime.contains("mp4") -> "mp4"; mime.contains("webm") -> "webm"; mime.contains("3gp") -> "3gp"
                kind == SourceKind.VIDEO -> "mp4"; else -> "jpg"
            }
            val dst = File(dir, "${UUID.randomUUID()}.$ext")
            val name = queryName(uri) ?: dst.name
            app.contentResolver.openInputStream(uri)?.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                ?: error("Não foi possível abrir o arquivo selecionado")
            if (dst.length() == 0L) { dst.delete(); error("Arquivo vazio") }
            SourceSpec(kind, dst.absolutePath, name, addedAtMs = System.currentTimeMillis())
        } catch (t: Throwable) {
            AppLog.e("app", "import", "falha ao importar mídia", t)
            _userMessage.value = "Não foi possível importar: ${t.message}"
            null
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun removeRecent(spec: SourceSpec) {
        scope.launch {
            settings.removeRecent(spec.id)
            if (spec.path != null && activeSpec.value?.id != spec.id) withContext(Dispatchers.IO) { File(spec.path!!).delete() }
        }
    }

    /** Mede a redução de ruído do pipeline GPU e restaura o estado anterior. */
    suspend fun runNoiseBenchmark(): NoiseBenchmark.Result? = opMutex.withLock {
        val wasRunning = _running.value
        val prev = activeSpec.value
        try {
            if (wasRunning) { sessions.closeAll("benchmark de ruído"); }
            NoiseBenchmark.run(engine, 1f)
        } catch (t: Throwable) {
            _userMessage.value = "Benchmark falhou: ${t.message}"; AppLog.e("app", "benchmark", "falhou", t); null
        } finally {
            engine.setOutput(output.value); engine.setFilters(filters.value)
            if (wasRunning && prev != null) switchLocked(prev) else runCatching { engine.setSource(null) }
        }
    }

    fun patternSpec(p: PatternType) = SourceSpec(SourceKind.PATTERN, null, p.label, p)

    // ---------------------------------------------------------------- captura local

    /** Salva o quadro processado atual em Pictures/NEURELIX (MediaStore, sem permissão extra). */
    suspend fun captureToGallery(): Uri? {
        val bmp = engine.capture() ?: run { _userMessage.value = "Nenhum quadro disponível para capturar"; return null }
        return withContext(Dispatchers.IO) {
            try {
                val v = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "NEURELIX_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NEURELIX")
                }
                val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("MediaStore recusou")
                app.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                AppLog.i("app", "capture", "captura salva em $uri (${bmp.width}x${bmp.height})")
                _userMessage.value = "Captura salva em Pictures/NEURELIX"
                uri
            } catch (t: Throwable) {
                AppLog.e("app", "capture", "falha ao salvar captura", t)
                _userMessage.value = "Falha ao salvar captura: ${t.message}"; null
            } finally { bmp.recycle() }
        }
    }

    companion object { const val PREVIEW_ID = "preview" }
}
