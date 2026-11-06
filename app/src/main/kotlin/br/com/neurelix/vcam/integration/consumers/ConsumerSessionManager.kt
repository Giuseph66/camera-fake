package br.com.neurelix.vcam.integration.consumers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.core.app.NotificationCompat
import br.com.neurelix.vcam.EngineController
import br.com.neurelix.vcam.MainActivity
import br.com.neurelix.vcam.api.ISessionCallback
import br.com.neurelix.vcam.api.IVirtualCameraService
import br.com.neurelix.vcam.api.IVirtualCameraSession
import br.com.neurelix.vcam.api.ServiceStatus
import br.com.neurelix.vcam.api.SessionRequest
import br.com.neurelix.vcam.api.SessionStats
import br.com.neurelix.vcam.api.StreamConfig
import br.com.neurelix.vcam.api.StreamRequest
import br.com.neurelix.vcam.api.VcamApi
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.core.model.FitMode
import br.com.neurelix.vcam.core.model.PendingConsumer
import br.com.neurelix.vcam.core.model.SessionRecord
import br.com.neurelix.vcam.integration.CameraIntegrationBackend
import br.com.neurelix.vcam.integration.IntegrationResult
import br.com.neurelix.vcam.integration.IntegrationStatus
import br.com.neurelix.vcam.engine.FrameSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ActiveSession(val id: Long, val pkg: String, val label: String, val streams: Int, val startedAtMs: Long, val delivered: Long)

/**
 * Backend "consumidores explicitamente integrados": serviço Binder + SDK. O consumidor autorizado entrega uma Surface
 * e o motor GL desenha nela. NÃO é uma câmera registrada no sistema: só apps que usam o SDK recebem os quadros.
 */
class ConsumerSessionManager(private val ctx: Context, private val controller: EngineController) : CameraIntegrationBackend {
    override val id = "consumers"
    override val title = "Consumidores integrados (SDK + Binder)"
    override val scope = "Apps que usam o SDK NEURELIX e foram autorizados por você recebem a fonte numa Surface própria. Não aparece em CameraManager."

    private val scopeCo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<Long, SessionImpl>()
    private val _active = MutableStateFlow<List<ActiveSession>>(emptyList())
    val active: StateFlow<List<ActiveSession>> = _active
    private val lastError = MutableStateFlow<String?>(null)

    private fun publish() {
        _active.value = sessions.values.map { it.snapshot() }.sortedBy { it.startedAtMs }
    }

    // ---- CameraIntegrationBackend ----
    override suspend fun checkAvailability(): IntegrationStatus {
        val enabled = controller.externalEnabled.value
        val running = controller.running.value
        val ev = listOf("API v${VcamApi.VERSION}", "integração externa: ${if (enabled) "ligada" else "desligada"}",
            "fonte ativa: ${if (running) controller.sourceLabel.value else "nenhuma"}", "sessões abertas: ${sessions.size}")
        return when {
            enabled && running && sessions.isNotEmpty() -> IntegrationStatus.Active("${sessions.size} sessão(ões) externa(s) ativa(s)", ev)
            enabled && running -> IntegrationStatus.Available("Pronto: aguardando consumidor autorizado", ev)
            enabled -> IntegrationStatus.Unavailable("Ligada, mas nenhuma fonte está ativa. Pressione Iniciar.", ev)
            else -> IntegrationStatus.Unavailable("Desligada. Ative 'Permitir consumidores' na tela Integração.", ev)
        }
    }

    override suspend fun start(source: FrameSource): IntegrationResult {
        controller.setExternalEnabled(true)
        return IntegrationResult.Success("Consumidores autorizados podem abrir sessões enquanto '${source.label}' estiver ativa")
    }

    override suspend fun stop() {
        controller.setExternalEnabled(false)
        closeAll("Integração desativada")
    }

    fun closeAll(reason: String) {
        sessions.values.toList().forEach { it.closeInternal(reason, notify = true) }
    }

    // ---- Binder ----
    fun createBinder(): IBinder = object : IVirtualCameraService.Stub() {
        override fun getApiVersion() = VcamApi.VERSION

        override fun getStatus(): ServiceStatus {
            val caller = ConsumerAuth.resolve(ctx, Binder.getCallingUid())
            val auth = caller != null && ConsumerAuth.isAuthorized(runBlocking { controller.settings.authorizedNow() }, caller)
            val o = controller.output.value
            return ServiceStatus(VcamApi.VERSION, controller.engineState.value.ordinal, controller.sourceLabel.value,
                controller.externalEnabled.value, auth, o.width, o.height, o.effectiveFps)
        }

        override fun openSession(request: SessionRequest, callback: ISessionCallback): IVirtualCameraSession {
            val uid = Binder.getCallingUid()
            val caller = ConsumerAuth.resolve(ctx, uid) ?: throw SecurityException("Não foi possível identificar o app chamador (uid=$uid)")
            if (request.apiVersion > VcamApi.VERSION) throw IllegalArgumentException("API ${request.apiVersion} não suportada (serviço v${VcamApi.VERSION})")
            val authorized = runBlocking { controller.settings.authorizedNow() }
            if (!ConsumerAuth.isAuthorized(authorized, caller)) {
                runBlocking { controller.settings.addPending(PendingConsumer(caller.packageName, caller.certSha256, caller.label, System.currentTimeMillis())) }
                notifyPending(caller)
                AppLog.w("binder", "auth", "recusado: ${caller.packageName} não autorizado (cert ${caller.certSha256.take(12)}…)")
                throw SecurityException("App '${caller.packageName}' não autorizado. Abra NEURELIX → Integração e permita este app.")
            }
            if (!controller.externalEnabled.value) throw IllegalStateException("Integração externa desligada no NEURELIX")
            if (!controller.running.value) throw IllegalStateException("Nenhuma fonte ativa: pressione Iniciar no NEURELIX")
            val s = SessionImpl(nextId(), caller, callback)
            sessions[s.id] = s
            publish()
            AppLog.i("binder", "session", "sessão ${s.id} aberta por ${caller.packageName}")
            scopeCo.launch {
                controller.settings.upsertSession(SessionRecord(s.id, caller.packageName, s.startedAt, null, 0, 0, 0, "aberta"))
            }
            return s
        }
    }

    private fun nextId() = System.currentTimeMillis() * 10 + sessionCounter.incrementAndGet() % 10
    private val sessionCounter = AtomicInteger()

    private fun notifyPending(c: CallerInfo) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("auth", "Pedidos de acesso", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = android.app.PendingIntent.getActivity(ctx, 1, android.content.Intent(ctx, MainActivity::class.java).putExtra("tab", 3),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(c.packageName.hashCode(), NotificationCompat.Builder(ctx, "auth")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("${c.label} pediu a câmera virtual")
            .setContentText("Toque para autorizar ou recusar em Integração.")
            .setContentIntent(pi).setAutoCancel(true).build())
    }

    private inner class SessionImpl(val id: Long, val caller: CallerInfo, val cb: ISessionCallback) : IVirtualCameraSession.Stub() {
        val startedAt = System.currentTimeMillis()
        private val streams = ConcurrentHashMap<Int, String>()
        private val streamIds = AtomicInteger()
        @Volatile private var closed = false
        private var deliveredFinal = 0L
        private var droppedFinal = 0L
        private val death = IBinder.DeathRecipient {
            AppLog.w("binder", "session", "consumidor ${caller.packageName} morreu; limpando sessão $id")
            closeInternal("consumidor encerrou", notify = false)
        }

        init { runCatching { cb.asBinder().linkToDeath(death, 0) } }

        fun snapshot() = ActiveSession(id, caller.packageName, caller.label, streams.size, startedAt, delivered())

        private fun delivered() = streams.values.sumOf { controller.engine.outputStats(it)?.first ?: 0L } + deliveredFinal

        override fun attachSurface(surface: Surface, request: StreamRequest): StreamConfig {
            if (closed) throw IllegalStateException("sessão fechada")
            if (!surface.isValid) throw IllegalArgumentException("Surface inválida")
            val sid = streamIds.incrementAndGet()
            val oid = "ext-$id-$sid"
            val fit = FitMode.values().getOrElse(request.fit) { FitMode.CROP }
            val err = controller.engine.addOutput(oid, surface, external = true, fit = fit) { failedId, msg ->
                AppLog.w("binder", "session", "saída $failedId falhou: $msg")
                streams.entries.removeIf { it.value == failedId }; publish()
            }
            if (err != null) throw IllegalStateException("Falha ao anexar Surface: $err")
            streams[sid] = oid
            publish()
            val size = controller.engine.outputSize(oid) ?: (0 to 0)
            val o = controller.output.value
            AppLog.i("binder", "session", "stream $sid anexado por ${caller.packageName}: pedido ${request.width}x${request.height}@${request.fps} → Surface real ${size.first}x${size.second}")
            return StreamConfig(sid, size.first, size.second, minOf(request.fps.coerceAtLeast(1), o.effectiveFps),
                controller.sourceLabel.value, "RGBA8888 (GL) ${o.width}x${o.height} master")
        }

        override fun detachSurface(streamId: Int) {
            val oid = streams.remove(streamId) ?: return
            controller.engine.outputStats(oid)?.let { deliveredFinal += it.first; droppedFinal += it.second }
            controller.engine.removeOutput(oid)
            publish()
        }

        override fun captureJpeg(quality: Int): ParcelFileDescriptor? {
            if (closed) return null
            val bmp: Bitmap = runBlocking { controller.engine.capture() } ?: return null
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 100), bos); bmp.recycle()
            val bytes = bos.toByteArray()
            val pipe = ParcelFileDescriptor.createPipe()
            Thread({
                runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } }
            }, "nvc-jpeg-pipe").start()
            AppLog.i("binder", "session", "captura JPEG ${bytes.size} bytes entregue a ${caller.packageName}")
            return pipe[0]
        }

        override fun getStats(): SessionStats {
            val dropped = streams.values.sumOf { controller.engine.outputStats(it)?.second ?: 0L } + droppedFinal
            return SessionStats(id, delivered(), dropped, streams.size)
        }

        override fun close() = closeInternal("fechada pelo consumidor", notify = false)

        fun closeInternal(reason: String, notify: Boolean) {
            if (closed) return
            closed = true
            val d = delivered()
            streams.keys.toList().forEach { sid ->
                val oid = streams.remove(sid) ?: return@forEach
                controller.engine.removeOutput(oid)
            }
            sessions.remove(id)
            runCatching { cb.asBinder().unlinkToDeath(death, 0) }
            if (notify) runCatching { cb.onSessionClosed(reason) }
            publish()
            AppLog.i("binder", "session", "sessão $id (${caller.packageName}) encerrada: $reason; quadros entregues=$d")
            scopeCo.launch {
                controller.settings.upsertSession(SessionRecord(id, caller.packageName, startedAt, System.currentTimeMillis(),
                    controller.output.value.width, controller.output.value.height, d, reason))
            }
        }
    }
}
