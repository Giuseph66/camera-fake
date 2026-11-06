package br.com.neurelix.vcam.engine.sources

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.core.model.OutputSettings
import br.com.neurelix.vcam.engine.FrameSource
import br.com.neurelix.vcam.engine.FrameTarget
import br.com.neurelix.vcam.engine.SourceFormat
import br.com.neurelix.vcam.engine.SourceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

/**
 * Câmera física (Camera2) → Surface OES do motor. Os quadros originais NUNCA saem daqui diretamente:
 * só o resultado processado do motor é entregue às saídas.
 */
class PhysicalCameraSource(
    private val context: Context,
    private val back: Boolean,
    private val settings: () -> OutputSettings,
) : FrameSource {
    override val label: String get() = if (back) "Câmera traseira" else "Câmera frontal"
    private val _state = MutableStateFlow<SourceState>(SourceState.Idle)
    override val state: StateFlow<SourceState> = _state

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var target: FrameTarget? = null
    private var surface: Surface? = null
    @Volatile private var stopping = false
    private var retries = 0
    private var cameraId = ""

    @SuppressLint("MissingPermission")
    override suspend fun start(target: FrameTarget) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val m = "Permissão de câmera não concedida"
            _state.value = SourceState.Error(m); throw SecurityException(m)
        }
        stopping = false
        _state.value = SourceState.Starting
        this.target = target
        val ht = HandlerThread("nvc-cam2").also { it.start() }
        thread = ht; handler = Handler(ht.looper)

        val id = findCamera() ?: run {
            val m = "Nenhuma câmera ${if (back) "traseira" else "frontal"} encontrada"
            _state.value = SourceState.Error(m); throw IllegalStateException(m)
        }
        cameraId = id
        val ch = manager.getCameraCharacteristics(id)
        val size = chooseSize(ch)
        val sensorRot = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val realtime = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val s = target.acquireInputSurface(size.width, size.height)
        surface = s
        target.onFormat(SourceFormat(size.width, size.height, sensorRot,
            "Camera2 id=$id ${size.width}x${size.height} sensor=${sensorRot}° (PRIVATE→OES)", timestampIsRealtime = realtime, live = true))
        AppLog.i("camera2", "open", "abrindo câmera $id (${label}) ${size.width}x${size.height} rot=$sensorRot realtime=$realtime")
        openAndStream(throwOnFail = true)
        _state.value = SourceState.Running
    }

    @SuppressLint("MissingPermission")
    private suspend fun openAndStream(throwOnFail: Boolean) {
        val opened = CompletableDeferred<CameraDevice>()
        val h = handler ?: return
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { opened.complete(camera) }
            override fun onDisconnected(camera: CameraDevice) {
                AppLog.w("camera2", "state", "câmera desconectada")
                camera.close()
                if (!opened.isCompleted) opened.completeExceptionally(IllegalStateException("câmera desconectada")) else lost("câmera desconectada (usada por outro app?)")
            }
            override fun onError(camera: CameraDevice, error: Int) {
                val msg = "Camera2 erro $error (${errorName(error)})"
                AppLog.e("camera2", "state", msg)
                camera.close()
                if (!opened.isCompleted) opened.completeExceptionally(IllegalStateException(msg)) else lost(msg)
            }
        }, h)
        val dev = try { withTimeout(8000) { opened.await() } } catch (t: Throwable) {
            _state.value = SourceState.Error(t.message ?: "falha ao abrir câmera")
            if (throwOnFail) { stop(); throw IllegalStateException("Falha ao abrir câmera: ${t.message}", t) } else return
        }
        device = dev
        val ready = CompletableDeferred<CameraCaptureSession>()
        val out = OutputConfiguration(surface!!)
        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(out), { r -> h.post(r) }, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { ready.complete(s) }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                AppLog.e("camera2", "session", "configuração da sessão falhou")
                ready.completeExceptionally(IllegalStateException("configuração da sessão falhou"))
            }
        })
        dev.createCaptureSession(cfg)
        val sess = try { withTimeout(8000) { ready.await() } } catch (t: Throwable) {
            _state.value = SourceState.Error(t.message ?: "sessão falhou")
            if (throwOnFail) { stop(); throw IllegalStateException("Sessão Camera2 falhou: ${t.message}", t) } else return
        }
        session = sess
        val ch = manager.getCameraCharacteristics(cameraId)
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface!!)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            chooseFps(ch, settings().effectiveFps)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }.build()
        sess.setRepeatingRequest(req, null, h)
        retries = 0
        AppLog.i("camera2", "session", "pré-visualização iniciada na câmera $cameraId")
        if (_state.value is SourceState.Lost) { _state.value = SourceState.Running; target?.onSourceRecovered() }
    }

    private fun lost(reason: String) {
        if (stopping) return
        _state.value = SourceState.Lost(reason)
        target?.onSourceLost(reason)
        val h = handler ?: return
        if (retries < 5) {
            retries++
            h.postDelayed({
                if (stopping) return@postDelayed
                AppLog.i("camera2", "retry", "tentativa $retries de reabrir a câmera")
                runCatching { session?.close() }; runCatching { device?.close() }
                session = null; device = null
                kotlinx.coroutines.runBlocking { runCatching { openAndStream(throwOnFail = false) } }
                if (_state.value !is SourceState.Running && !stopping) lost(reason)
            }, 1000L * retries)
        }
    }

    private fun findCamera(): String? {
        val want = if (back) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        return manager.cameraIdList.filter { id ->
            val c = manager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == want &&
                c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
        }.minByOrNull { it.toIntOrNull() ?: 99 }
    }

    private fun chooseSize(ch: CameraCharacteristics): Size {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).filter { it.width <= 3840 }
        val o = settings()
        val dw = maxOf(o.width, o.height); val dh = minOf(o.width, o.height)
        val dAr = dw.toFloat() / dh
        val matching = sizes.filter { abs(it.width.toFloat() / it.height - dAr) < 0.02f }
        val pool = matching.ifEmpty { sizes }
        val enough = pool.filter { it.width >= dw && it.height >= dh }
        return enough.minByOrNull { it.width.toLong() * it.height } ?: pool.maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)
    }

    private fun chooseFps(ch: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper <= fps }.maxByOrNull { it.upper }
    }

    private fun errorName(e: Int) = when (e) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "em uso"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "máx. câmeras em uso"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "desabilitada por política"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "erro do dispositivo"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "erro do serviço"
        else -> "desconhecido"
    }

    override suspend fun stop() {
        stopping = true
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null; device = null; surface = null
        thread?.quitSafely(); thread = null; handler = null
        _state.value = SourceState.Idle
        AppLog.i("camera2", "close", "câmera liberada")
    }
}
