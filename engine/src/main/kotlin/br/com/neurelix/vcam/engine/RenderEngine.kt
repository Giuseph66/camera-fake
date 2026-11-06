package br.com.neurelix.vcam.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.core.diagnostics.ResourceSampler
import br.com.neurelix.vcam.core.model.DropPolicy
import br.com.neurelix.vcam.core.model.EngineMetrics
import br.com.neurelix.vcam.core.model.EngineState
import br.com.neurelix.vcam.core.model.FilterParams
import br.com.neurelix.vcam.core.model.FitMode
import br.com.neurelix.vcam.core.model.LostSourcePolicy
import br.com.neurelix.vcam.core.model.OutputSettings
import br.com.neurelix.vcam.core.model.PatternType
import br.com.neurelix.vcam.core.model.QualityPriority
import br.com.neurelix.vcam.engine.gl.EglCore
import br.com.neurelix.vcam.engine.gl.GlProgram
import br.com.neurelix.vcam.engine.gl.GlUtil
import br.com.neurelix.vcam.engine.gl.OutputWorker
import br.com.neurelix.vcam.engine.gl.Shaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor de renderização GPU.
 *
 * Pipeline por quadro (thread GL própria):
 *   Fonte(OES | bitmap | procedural) → [entrada: geometria/enquadramento] → [denoise bilateral+temporal]
 *   → [grade: nitidez, exposição, brilho, contraste, gama, temperatura, saturação] → master (ring de 3 texturas)
 *   → glFinish → cada [OutputWorker] (thread própria) desenha o master na sua Surface.
 *
 * Descarte controlado: o SurfaceTexture entrega sempre o quadro mais novo; além disso, [DropPolicy.NEWEST]
 * limita ao FPS-alvo e cada saída pendente é sobrescrita pelo quadro mais recente.
 */
class RenderEngine : FrameTarget {
    private enum class Input { NONE, OES, BITMAP, PATTERN }

    private val thread = HandlerThread("nvc-render", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)

    private val _state = MutableStateFlow(EngineState.IDLE)
    val state: StateFlow<EngineState> = _state
    private val _metrics = MutableStateFlow(EngineMetrics())
    val metrics: StateFlow<EngineMetrics> = _metrics
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    @Volatile private var output = OutputSettings()
    @Volatile private var filters = FilterParams()
    @Volatile private var format: SourceFormat = SourceFormat(1280, 720)

    // ---- recursos GL (somente thread de render) ----
    private var egl: EglCore? = null
    private var pbuf: EGLSurface? = null
    private var progOes: GlProgram? = null
    private var prog2d: GlProgram? = null
    private var progDenoise: GlProgram? = null
    private var progGrade: GlProgram? = null
    private var progMeter: GlProgram? = null
    private var progPattern: GlProgram? = null

    private var oesTex = 0
    private var st: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val stMatrix = FloatArray(16)

    private var bitmapTex = 0
    private var bitmapW = 0
    private var bitmapH = 0

    private var patternTex = 0; private var patternFbo = 0
    private val PW = 1280; private val PH = 720

    private var allocW = 0; private var allocH = 0
    private var texA = 0; private var fboA = 0
    private val texD = IntArray(2); private val fboD = IntArray(2)
    private val texM = IntArray(3); private val fboM = IntArray(3)
    private var texMeter = 0; private var fboMeter = 0
    private var dIdx = 0
    private var mIdx = 0
    private var histValid = false

    private var input = Input.NONE
    private var patternType = PatternType.NUMBERED
    private var lostPlaceholder = false
    private var sourceLost = false
    private var lastMasterTex = 0
    private var patternFrame = 0L

    // ---- agendamento ----
    private var lastRenderNs = 0L
    private var timerScheduled = false
    private var renderQueued = false
    private var oesPending = false

    // ---- luz automática ----
    private var autoGain = 1f
    private var frameCounter = 0L
    @Volatile private var lumaMeasured = -1f

    // ---- saídas ----
    private val workers = ConcurrentHashMap<String, OutputWorker>()

    // ---- fonte ----
    private val sourceMutex = Mutex()
    private var currentSource: FrameSource? = null
    @Volatile private var sourceStarted = false

    // ---- captura ----
    @Volatile private var captureReq: CompletableDeferred<Bitmap?>? = null

    // ---- métricas ----
    private val srcArrived = AtomicInteger()
    private val rendered = AtomicInteger()
    private val dropSource = AtomicLong()
    private val deliveredTotal = AtomicLong()
    private val latencyWindow = ArrayDeque<Float>()
    private val fpsHistory = ArrayDeque<Float>()
    private val resources = ResourceSampler()
    private var lastMetricNs = SystemClock.elapsedRealtimeNanos()
    private var lastWorkerDrops = 0L
    private var lastLogMs = 0L
    @Volatile private var released = false

    init {
        handler.post {
            try {
                initGl()
                handler.postDelayed(metricsTick, 1000)
            } catch (t: Throwable) {
                _lastError.value = "Falha ao iniciar OpenGL: ${t.message}"
                _state.value = EngineState.ERROR
                AppLog.e("engine", "init", "falha ao iniciar GL", t)
            }
        }
    }

    private fun initGl() {
        val e = EglCore()
        egl = e
        val pb = e.createPbuffer(); pbuf = pb
        e.makeCurrent(pb)
        progOes = GlProgram(Shaders.VERTEX, Shaders.source(true), "src-oes")
        prog2d = GlProgram(Shaders.VERTEX, Shaders.source(false), "src-2d")
        progDenoise = GlProgram(Shaders.VERTEX, Shaders.DENOISE, "denoise")
        progGrade = GlProgram(Shaders.VERTEX, Shaders.GRADE, "grade")
        progMeter = GlProgram(Shaders.VERTEX, Shaders.METER, "meter")
        progPattern = GlProgram(Shaders.VERTEX, Shaders.PATTERN, "pattern")
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); oesTex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        patternTex = GlUtil.newTexture2D(PW, PH); patternFbo = GlUtil.newFbo(patternTex)
        texMeter = GlUtil.newTexture2D(4, 4, linear = false); fboMeter = GlUtil.newFbo(texMeter)
        AppLog.i("engine", "init", "GL pronto: ${GLES20.glGetString(GLES20.GL_RENDERER)} / ${GLES20.glGetString(GLES20.GL_VERSION)} (recordable=${e.recordable})")
    }

    // ======================================================================================
    // API pública
    // ======================================================================================

    fun setOutput(o: OutputSettings) {
        val prev = output
        output = o
        handler.post {
            if (prev.width != o.width || prev.height != o.height) { allocW = 0 }
            if (prev.priority != o.priority) histValid = false
            if (o.powerSaving != prev.powerSaving) lastRenderNs = 0
            applyTimer(); requestRender()
        }
    }

    fun setFilters(f: FilterParams) {
        val prev = filters
        filters = f.sanitized()
        if (prev.denoise != f.denoise) handler.post { histValid = false }
        requestRender()
    }

    /** Troca a fonte sem reiniciar o motor. A fonte antiga é parada antes da nova começar. */
    suspend fun setSource(source: FrameSource?) = sourceMutex.withLock {
        val old = currentSource
        currentSource = null
        sourceStarted = false
        if (old != null) runCatching { old.stop() }.onFailure { AppLog.w("engine", "source", "erro ao parar fonte", it) }
        runOnRender { resetInput(); sourceLost = false; lostPlaceholder = false; refreshState() }
        if (source != null) {
            _state.value = EngineState.STARTING
            try {
                currentSource = source
                source.start(this)
                sourceStarted = true
                runOnRender { refreshState() }
                AppLog.i("engine", "source", "fonte iniciada: ${source.label}")
            } catch (t: Throwable) {
                currentSource = null
                _lastError.value = "Falha ao iniciar '${source.label}': ${t.message}"
                _state.value = EngineState.ERROR
                AppLog.e("engine", "source", "falha ao iniciar fonte ${source.label}", t)
                runCatching { source.stop() }
                throw t
            }
        } else {
            runOnRender { refreshState() }
        }
    }

    /** Adiciona uma saída. Retorna null se OK, ou mensagem de erro. */
    fun addOutput(id: String, surface: Surface, external: Boolean, fit: FitMode, onFailed: (String, String) -> Unit = { _, _ -> }): String? {
        if (released) return "motor encerrado"
        val ctx = runOnRender { egl?.context } ?: return "OpenGL indisponível"
        workers.remove(id)?.release()
        val w = OutputWorker(id, surface, external, fit, ctx) { oid, msg ->
            handler.post { removeOutputInternal(oid); onFailed(oid, msg) }
        }
        val err = w.init()
        if (err != null) { w.release(); return err }
        workers[id] = w
        handler.post { refreshState(); applyTimer(); requestRender(force = true) }
        AppLog.i("engine", "output", "saída '$id' adicionada (external=$external, ${w.surfaceW}x${w.surfaceH})")
        return null
    }

    fun removeOutput(id: String) {
        removeOutputInternal(id)
    }

    private fun removeOutputInternal(id: String) {
        val w = workers.remove(id) ?: return
        w.release()
        handler.post { refreshState(); applyTimer() }
        AppLog.i("engine", "output", "saída '$id' removida (entregues=${w.delivered.get()}, descartes=${w.dropped.get()})")
    }

    fun outputStats(id: String): Pair<Long, Long>? = workers[id]?.let { it.delivered.get() to it.dropped.get() }
    fun outputSize(id: String): Pair<Int, Int>? = workers[id]?.let { it.surfaceW to it.surfaceH }
    fun hasExternalOutputs() = workers.values.any { it.external }

    /** Captura o quadro processado mais recente (master). */
    suspend fun capture(): Bitmap? {
        val d = CompletableDeferred<Bitmap?>()
        captureReq = d
        requestRender(force = true)
        val b = withTimeoutOrNull(4000) { d.await() }
        if (b == null) captureReq = null
        return b
    }

    fun shutdown() {
        released = true
        workers.keys.toList().forEach { removeOutputInternal(it) }
        val latch = CountDownLatch(1)
        handler.post {
            handler.removeCallbacksAndMessages(null)
            runCatching { releaseGl() }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    // ======================================================================================
    // FrameTarget
    // ======================================================================================

    override fun acquireInputSurface(width: Int, height: Int): Surface = runOnRender {
        resetInput()
        val t = SurfaceTexture(oesTex)
        t.setDefaultBufferSize(width, height)
        t.setOnFrameAvailableListener({ onSourceFrame() }, handler)
        st = t
        val s = Surface(t)
        inputSurface = s
        input = Input.OES
        histValid = false
        s
    }

    override fun submitBitmap(bitmap: Bitmap) {
        handler.post {
            try {
                resetInput(keepSurfaces = false)
                if (bitmapTex == 0 || bitmapW != bitmap.width || bitmapH != bitmap.height) {
                    GlUtil.deleteTexture(bitmapTex)
                    val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); bitmapTex = ids[0]
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                bitmapW = bitmap.width; bitmapH = bitmap.height
                format = SourceFormat(bitmapW, bitmapH, 0, "Bitmap ARGB_8888 ${bitmapW}x$bitmapH", live = false)
                input = Input.BITMAP
                histValid = false
                GlUtil.checkError("submitBitmap")
            } catch (t: Throwable) {
                AppLog.e("engine", "bitmap", "falha ao enviar bitmap para GPU", t)
                _lastError.value = "Falha ao carregar imagem na GPU: ${t.message}"
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            refreshState(); applyTimer(); requestRender(force = true)
        }
    }

    override fun submitProcedural(pattern: PatternType) {
        handler.post {
            resetInput(keepSurfaces = false)
            patternType = pattern
            input = Input.PATTERN
            format = SourceFormat(PW, PH, 0, "Procedural GPU ${pattern.name} ${PW}x$PH", live = true)
            histValid = false
            patternFrame = 0
            refreshState(); applyTimer(); requestRender(force = true)
        }
    }

    override fun onFormat(format: SourceFormat) {
        this.format = format
        handler.post { histValid = false; requestRender(force = true) }
    }

    override fun onSourceLost(reason: String) {
        AppLog.w("engine", "source", "fonte perdida: $reason")
        handler.post {
            sourceLost = true
            _lastError.value = "Fonte perdida: $reason"
            when (output.lostSourcePolicy) {
                LostSourcePolicy.PLACEHOLDER -> lostPlaceholder = true
                else -> {}
            }
            refreshState(); applyTimer(); requestRender(force = true)
        }
    }

    override fun onSourceRecovered() {
        handler.post {
            sourceLost = false; lostPlaceholder = false; _lastError.value = null
            AppLog.i("engine", "source", "fonte recuperada")
            refreshState(); applyTimer()
        }
    }

    // ======================================================================================
    // Agendamento
    // ======================================================================================

    private fun <T> runOnRender(block: () -> T): T {
        if (Looper.myLooper() == handler.looper) return block()
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        handler.post { result = runCatching(block); latch.countDown() }
        check(latch.await(5, TimeUnit.SECONDS)) { "timeout aguardando thread de render" }
        return result!!.getOrThrow()
    }

    private fun resetInput(keepSurfaces: Boolean = false) {
        st?.setOnFrameAvailableListener(null)
        runCatching { inputSurface?.release() }
        runCatching { st?.release() }
        st = null; inputSurface = null
        oesPending = false
        input = Input.NONE
        sourceLost = false; lostPlaceholder = false
        histValid = false
    }

    private fun onSourceFrame() {
        srcArrived.incrementAndGet()
        oesPending = true
        if (released || input != Input.OES) return
        val o = output
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val interval = 1_000_000_000L / o.effectiveFps
        if (o.dropPolicy == DropPolicy.NEWEST && lastRenderNs != 0L && nowNs - lastRenderNs < interval * 60 / 100) {
            // descarte controlado: consome o buffer (libera o produtor) sem renderizar
            runCatching { st?.updateTexImage() }
            oesPending = false
            dropSource.incrementAndGet()
            return
        }
        renderFrame(nowNs)
    }

    private fun requestRender(force: Boolean = false) {
        if (released) return
        if (renderQueued) return
        renderQueued = true
        handler.post {
            renderQueued = false
            if (input != Input.NONE || lostPlaceholder) renderFrame(SystemClock.elapsedRealtimeNanos())
        }
    }

    private val tick = Runnable {
        timerScheduled = false
        if (released) return@Runnable
        if (timerWanted()) {
            if (!(sourceLost && output.lostSourcePolicy == LostSourcePolicy.STOP)) {
                if (sourceLost && output.lostSourcePolicy == LostSourcePolicy.HOLD_LAST) resubmitLast()
                else renderFrame(SystemClock.elapsedRealtimeNanos())
            }
            applyTimer()
        }
    }

    private fun timerWanted(): Boolean = when {
        sourceLost -> output.lostSourcePolicy != LostSourcePolicy.STOP
        input == Input.PATTERN -> true
        input == Input.BITMAP -> true
        else -> false
    }

    private fun timerIntervalMs(): Long {
        val fps = output.effectiveFps
        return when {
            input == Input.BITMAP && !sourceLost && !hasExternalOutputs() -> 500L   // imagem estática só com preview local: 2 Hz
            else -> (1000L / fps).coerceAtLeast(5)
        }
    }

    private var nextTickAt = 0L

    private fun applyTimer() {
        if (released) return
        if (timerWanted() && !timerScheduled) {
            timerScheduled = true
            // agendamento sem deriva: próximo = anterior + intervalo (nunca no passado)
            val now = SystemClock.uptimeMillis()
            val iv = timerIntervalMs()
            nextTickAt = if (nextTickAt == 0L || now - nextTickAt > iv * 3) now + iv else nextTickAt + iv
            handler.postAtTime(tick, nextTickAt.coerceAtLeast(now))
        } else if (!timerWanted() && timerScheduled) {
            handler.removeCallbacks(tick); timerScheduled = false
        }
    }

    private fun resubmitLast() {
        if (lastMasterTex != 0) workers.values.forEach { it.submit(lastMasterTex, allocW, allocH) }
    }

    private fun refreshState() {
        val s = when {
            _state.value == EngineState.ERROR && input == Input.NONE && currentSource == null && _lastError.value != null -> EngineState.ERROR
            sourceLost -> EngineState.SOURCE_LOST
            input == Input.NONE -> if (currentSource != null && !sourceStarted) EngineState.STARTING else EngineState.IDLE
            hasExternalOutputs() -> EngineState.RUNNING_EXTERNAL
            else -> EngineState.RUNNING_LOCAL
        }
        _state.value = s
    }

    // ======================================================================================
    // Renderização
    // ======================================================================================

    private fun ensureTargets(): Boolean {
        val o = output
        val w = o.width.coerceIn(160, 3840); val h = o.height.coerceIn(90, 2160)
        if (allocW == w && allocH == h && texA != 0) return true
        GlUtil.deleteFbo(fboA); GlUtil.deleteTexture(texA)
        for (i in 0..1) { GlUtil.deleteFbo(fboD[i]); GlUtil.deleteTexture(texD[i]) }
        for (i in 0..2) { GlUtil.deleteFbo(fboM[i]); GlUtil.deleteTexture(texM[i]) }
        texA = GlUtil.newTexture2D(w, h, mipmaps = true); fboA = GlUtil.newFbo(texA)
        for (i in 0..1) { texD[i] = GlUtil.newTexture2D(w, h, mipmaps = true); fboD[i] = GlUtil.newFbo(texD[i]) }
        for (i in 0..2) { texM[i] = GlUtil.newTexture2D(w, h); fboM[i] = GlUtil.newFbo(texM[i]) }
        allocW = w; allocH = h; histValid = false
        AppLog.i("engine", "gl", "alvos de render ${w}x$h alocados")
        return true
    }

    private fun renderFrame(arrivalNs: Long) {
        val e = egl ?: return
        if (released) return
        try {
            e.makeCurrent(pbuf ?: return)
            if (!ensureTargets()) return
            val o = output
            val f = if (filters.enabled) filters else FilterParams(enabled = false)
            val W = allocW; val H = allocH
            val fmt = format
            var tsNs = 0L

            if (input == Input.OES) {
                val t = st ?: return
                if (oesPending) {
                    t.updateTexImage(); oesPending = false
                    t.getTransformMatrix(stMatrix); tsNs = t.timestamp
                } else if (lastRenderNs == 0L) {
                    return   // ainda sem nenhum quadro da fonte
                } else t.getTransformMatrix(stMatrix)
            }

            // ---- 1. entrada → A ----
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA)
            GLES20.glViewport(0, 0, W, H)
            val ow = W.toFloat() / H
            val theta = (fmt.rotationDegrees + o.rotationDegrees).mod(360)
            val rad = theta * PI / 180.0
            when {
                lostPlaceholder -> drawPattern(9)
                input == Input.OES -> {
                    val p = progOes!!; p.use()
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
                    p.i("uTex", 0); p.m4("uSt", stMatrix)
                    geometry(p, ow, fmt.width, fmt.height, rad, o)
                    p.drawQuad()
                }
                input == Input.BITMAP -> {
                    val p = prog2d!!; p.use()
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTex)
                    p.i("uTex", 0); p.f("uFlipV", 1f)
                    geometry(p, ow, bitmapW, bitmapH, rad, o)
                    p.drawQuad()
                }
                input == Input.PATTERN -> {
                    drawPattern(null)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA)
                    GLES20.glViewport(0, 0, W, H)
                    val p = prog2d!!; p.use()
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, patternTex)
                    p.i("uTex", 0); p.f("uFlipV", 0f)
                    geometry(p, ow, PW, PH, rad, o)
                    p.drawQuad()
                }
                else -> return
            }

            // ---- 2. denoise ----
            var pre = texA
            val denoiseOn = f.enabled && f.denoise > 0.001f
            if (denoiseOn) {
                val cur = dIdx; val prev = 1 - dIdx
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboD[cur])
                GLES20.glViewport(0, 0, W, H)
                val p = progDenoise!!; p.use()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texD[prev])
                p.i("uCur", 0); p.i("uHist", 1)
                p.f2("uTexel", 1f / W, 1f / H)
                p.f("uStrength", f.denoise)
                p.f("uHistValid", if (histValid && o.priority != QualityPriority.LATENCY) 1f else 0f)
                p.i("uRadius", when (o.priority) { QualityPriority.QUALITY -> 2; QualityPriority.BALANCED -> 1; QualityPriority.LATENCY -> 1 })
                p.drawQuad()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                pre = texD[cur]
                dIdx = prev
                histValid = true
            } else histValid = false

            // ---- 3. medição de luminância (antes do ganho) ----
            frameCounter++
            val meterEvery = if (f.autoLight) 3 else 15
            if (frameCounter % meterEvery == 0L) measureLuma(pre, W, H, f)

            // ---- 4. grade → master ----
            val m = mIdx; mIdx = (mIdx + 1) % 3
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboM[m])
            GLES20.glViewport(0, 0, W, H)
            val g = progGrade!!; g.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pre)
            g.i("uTex", 0); g.f2("uTexel", 1f / W, 1f / H)
            val on = f.enabled
            g.f("uSharp", if (on) f.sharpness else 0f)
            val ev = if (on) f.exposureEv else 0f
            val auto = if (on && f.autoLight) autoGain.pow(f.autoLightStrength) else 1f
            g.f("uGain", 2f.pow(ev) * auto)
            g.f("uBright", if (on) f.brightness else 0f)
            g.f("uContrast", if (on) f.contrast else 1f)
            g.f("uSat", if (on) f.saturation else 1f)
            g.f("uTemp", if (on) f.temperature else 0f)
            g.f("uGamma", if (on) f.gamma else 1f)
            g.drawQuad()

            // ---- 5. captura ----
            captureReq?.let { req ->
                captureReq = null
                req.complete(readMaster(fboM[m], W, H))
            }

            // ---- 6. publicar nas saídas ----
            GLES20.glFinish()
            val doneNs = SystemClock.elapsedRealtimeNanos()
            lastMasterTex = texM[m]
            workers.values.forEach { it.submit(texM[m], W, H) }
            lastRenderNs = arrivalNs
            rendered.incrementAndGet()
            deliveredTotal.incrementAndGet()
            val base = if (fmt.timestampIsRealtime && tsNs > 0) tsNs else arrivalNs
            val lat = (doneNs - base) / 1e6f
            if (lat in 0f..2000f) synchronized(latencyWindow) { latencyWindow.addLast(lat); if (latencyWindow.size > 300) latencyWindow.removeFirst() }
            GlUtil.checkError("renderFrame")
        } catch (t: Throwable) {
            AppLog.e("engine", "render", "erro no quadro", t)
            _lastError.value = "Erro de render: ${t.message}"
        }
    }

    private fun geometry(p: GlProgram, outAspect: Float, bw: Int, bh: Int, rad: Double, o: OutputSettings) {
        p.f("uMirror", if (o.mirrorHorizontal) 1f else 0f)
        p.f("uOutAspect", outAspect)
        p.f("uMode", o.fit.ordinal.toFloat())
        p.f2("uBuf", bw.toFloat(), bh.toFloat())
        p.f2("uRot", cos(rad).toFloat(), sin(rad).toFloat())
        p.f("uZoom", o.zoom.coerceIn(1f, 8f))
        p.f2("uPan", o.panX.coerceIn(-1f, 1f), o.panY.coerceIn(-1f, 1f))
    }

    private fun drawPattern(forced: Int?) {
        val p = progPattern!!
        val toFbo = forced == null
        if (toFbo) { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, patternFbo); GLES20.glViewport(0, 0, PW, PH) }
        else { GLES20.glViewport(0, 0, allocW, allocH) }
        p.use()
        val frame = patternFrame++
        p.i("uPattern", forced ?: when (patternType) {
            PatternType.COLOR_BARS -> 0; PatternType.GRADIENT -> 1; PatternType.NUMBERED -> 2; PatternType.NOISY_BARS -> 3
        })
        p.f("uFrameHi", ((frame shr 16) and 0xFFFF).toFloat())
        p.f("uFrameLo", (frame and 0xFFFF).toFloat())
        p.f("uTimeBits", (SystemClock.elapsedRealtime() and 0xFFFF).toFloat())
        p.f("uNoise", 0.08f)
        p.f("uAspect", if (toFbo) PW.toFloat() / PH else allocW.toFloat() / allocH)
        p.drawQuad()
    }

    private fun measureLuma(tex: Int, W: Int, H: Int, f: FilterParams) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboMeter)
        GLES20.glViewport(0, 0, 4, 4)
        val p = progMeter!!; p.use()
        p.i("uTex", 0)
        p.f("uLod", (ln(max(W, H).toDouble()) / ln(2.0)).toFloat() - 2f)
        p.drawQuad()
        val buf = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, 4, 4, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        var sum = 0f
        for (i in 0 until 16) sum += (buf.get(i * 4).toInt() and 0xFF) / 255f
        val mean = sum / 16f
        lumaMeasured = mean
        if (f.autoLight) {
            val target = 0.45f
            val g = (target / max(mean, 0.03f)).coerceIn(0.5f, 4f)
            autoGain += (g - autoGain) * 0.2f
        } else autoGain = 1f
    }

    private fun readMaster(fbo: Int, W: Int, H: Int): Bitmap? = try {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        val buf = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        val raw = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buf)
        val flip = Matrix().apply { preScale(1f, -1f) }
        Bitmap.createBitmap(raw, 0, 0, W, H, flip, false).also { raw.recycle() }
    } catch (t: Throwable) {
        AppLog.e("engine", "capture", "falha na leitura do master", t); null
    }

    // ======================================================================================
    // Métricas
    // ======================================================================================

    private val metricsTick = object : Runnable {
        override fun run() {
            if (released) return
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val dt = (nowNs - lastMetricNs) / 1e9f
            lastMetricNs = nowNs
            val src = srcArrived.getAndSet(0) / dt
            val del = rendered.getAndSet(0) / dt
            val active = input != Input.NONE
            if (active) { fpsHistory.addLast(del); if (fpsHistory.size > 60) fpsHistory.removeFirst() } else fpsHistory.clear()
            val mean = if (fpsHistory.isEmpty()) 0f else fpsHistory.average().toFloat()
            val sd = if (fpsHistory.size < 2) 0f else sqrt(fpsHistory.map { (it - mean) * (it - mean) }.average()).toFloat()
            val lats = synchronized(latencyWindow) { latencyWindow.toList() }
            val sorted = lats.sorted()
            val avgLat = if (lats.isEmpty()) 0f else lats.average().toFloat()
            val p95 = if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * 0.95).toInt()]
            val workerDrops = workers.values.sumOf { it.dropped.get() }
            val r = resources.sample()
            val fmt = format
            val m = EngineMetrics(
                sourceFps = if (input == Input.OES) src else if (active) del else 0f,
                deliveredFps = del,
                minFps = fpsHistory.minOrNull() ?: 0f,
                maxFps = fpsHistory.maxOrNull() ?: 0f,
                fpsStdDev = sd,
                dropped = dropSource.get() + workerDrops,
                delivered = deliveredTotal.get(),
                latencyAvgMs = avgLat,
                latencyP95Ms = p95,
                sourceWidth = if (input == Input.NONE) 0 else fmt.width,
                sourceHeight = if (input == Input.NONE) 0 else fmt.height,
                outputWidth = allocW, outputHeight = allocH,
                sourceFormat = if (input == Input.NONE) "-" else fmt.description.ifEmpty { "${fmt.width}x${fmt.height} rot=${fmt.rotationDegrees}" },
                outputFormat = if (allocW == 0) "-" else "RGBA8888 ${allocW}x$allocH (GL, ${if (filters.isNeutral) "sem filtros" else "com filtros"})",
                outputs = workers.size,
                avgLuma = lumaMeasured,
                cpuPercent = r.cpuPercent, memoryMb = r.memoryMb,
            )
            _metrics.value = m
            val nowMs = SystemClock.elapsedRealtime()
            if (active && nowMs - lastLogMs >= 5000) {
                lastLogMs = nowMs
                AppLog.i("engine", "metrics", "src=%.1f out=%.1f fps min=%.1f max=%.1f sd=%.2f drop=%d lat=%.1f/p95 %.1f ms cpu=%.0f%% mem=%.0fMB outs=%d luma=%.2f".format(
                    m.sourceFps, m.deliveredFps, m.minFps, m.maxFps, m.fpsStdDev, m.dropped, m.latencyAvgMs, m.latencyP95Ms, m.cpuPercent, m.memoryMb, m.outputs, m.avgLuma))
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun releaseGl() {
        egl?.let { e ->
            pbuf?.let { e.makeCurrent(it) }
            resetInput()
            listOf(progOes, prog2d, progDenoise, progGrade, progMeter, progPattern).forEach { it?.release() }
            GlUtil.deleteFbo(fboA); GlUtil.deleteTexture(texA)
            for (i in 0..1) { GlUtil.deleteFbo(fboD[i]); GlUtil.deleteTexture(texD[i]) }
            for (i in 0..2) { GlUtil.deleteFbo(fboM[i]); GlUtil.deleteTexture(texM[i]) }
            GlUtil.deleteFbo(patternFbo); GlUtil.deleteTexture(patternTex)
            GlUtil.deleteFbo(fboMeter); GlUtil.deleteTexture(texMeter)
            GlUtil.deleteTexture(bitmapTex)
            e.makeNothingCurrent()
            pbuf?.let { e.destroySurface(it) }
            e.release()
        }
        egl = null
    }
}
