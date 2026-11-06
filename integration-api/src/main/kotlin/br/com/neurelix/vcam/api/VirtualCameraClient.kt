package br.com.neurelix.vcam.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Erro tipado para chamadores Java/bloqueantes. [kind] igual a [ClientResult.Err.kind]. */
class VcamException(val kind: String, message: String) : Exception("[$kind] $message")

sealed interface ClientResult<out T> {
    data class Ok<T>(val value: T) : ClientResult<T>
    /** [kind]: NOT_INSTALLED, BIND_DENIED, NOT_AUTHORIZED, INACTIVE, API_MISMATCH, REMOTE_ERROR, TIMEOUT */
    data class Err(val kind: String, val message: String) : ClientResult<Nothing>
}

/**
 * SDK do consumidor: conecta ao serviço NEURELIX e entrega uma Surface para receber a fonte virtual.
 * O consumidor continua dono da Surface; o NEURELIX apenas desenha nela, enquanto o usuário mantiver a integração ativa.
 */
class VirtualCameraClient(private val context: Context) {
    private var conn: ServiceConnection? = null
    private var service: IVirtualCameraService? = null

    suspend fun connect(timeoutMs: Long = 4000): ClientResult<IVirtualCameraService> {
        service?.let { if (it.asBinder().isBinderAlive) return ClientResult.Ok(it) }
        val intent = Intent(VcamApi.ACTION_BIND).setComponent(ComponentName(VcamApi.SERVICE_PACKAGE, VcamApi.SERVICE_CLASS))
        try {
            context.packageManager.getPackageInfo(VcamApi.SERVICE_PACKAGE, 0)
        } catch (e: Exception) {
            return ClientResult.Err("NOT_INSTALLED", "NEURELIX Virtual Camera não está instalado (ou invisível: faltam <queries>): ${e.message}")
        }
        val d = CompletableDeferred<IVirtualCameraService?>()
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { d.complete(IVirtualCameraService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
            override fun onNullBinding(name: ComponentName) { d.complete(null) }
            override fun onBindingDied(name: ComponentName) { service = null; d.complete(null) }
        }
        val ok = try { context.bindService(intent, c, Context.BIND_AUTO_CREATE) } catch (e: SecurityException) { false }
        if (!ok) return ClientResult.Err("BIND_DENIED", "bindService recusado pelo sistema")
        val s = withTimeoutOrNull(timeoutMs) { d.await() }
        if (s == null) { runCatching { context.unbindService(c) }; return ClientResult.Err("TIMEOUT", "Serviço não respondeu") }
        conn = c; service = s
        return ClientResult.Ok(s)
    }

    suspend fun status(): ClientResult<ServiceStatus> = withContext(Dispatchers.IO) {
        when (val r = connect()) {
            is ClientResult.Err -> r
            is ClientResult.Ok -> try { ClientResult.Ok(r.value.status) } catch (e: RemoteException) { ClientResult.Err("REMOTE_ERROR", e.message ?: "") }
        }
    }

    suspend fun openSession(label: String, onState: (Int, String) -> Unit = { _, _ -> }, onClosed: (String) -> Unit = {}): ClientResult<VirtualCameraSession> =
        withContext(Dispatchers.IO) {
            val s = when (val r = connect()) { is ClientResult.Err -> return@withContext r; is ClientResult.Ok -> r.value }
            val cb = object : ISessionCallback.Stub() {
                override fun onSourceStateChanged(engineState: Int, sourceLabel: String) = onState(engineState, sourceLabel)
                override fun onSessionClosed(reason: String) = onClosed(reason)
            }
            try {
                ClientResult.Ok(VirtualCameraSession(s.openSession(SessionRequest(label), cb)))
            } catch (e: SecurityException) {
                ClientResult.Err("NOT_AUTHORIZED", e.message ?: "App não autorizado no NEURELIX")
            } catch (e: IllegalStateException) {
                ClientResult.Err("INACTIVE", e.message ?: "Integração/fonte inativa")
            } catch (e: IllegalArgumentException) {
                ClientResult.Err("API_MISMATCH", e.message ?: "")
            } catch (e: RemoteException) {
                ClientResult.Err("REMOTE_ERROR", e.message ?: "")
            }
        }

    /** Versão bloqueante para chamadores Java (NÃO chamar na thread principal). */
    @Throws(VcamException::class)
    fun openSessionBlockingOrThrow(label: String): VirtualCameraSession =
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            when (val r = openSession(label)) {
                is ClientResult.Ok -> r.value
                is ClientResult.Err -> throw VcamException(r.kind, r.message)
            }
        }

    fun disconnect() {
        conn?.let { runCatching { context.unbindService(it) } }
        conn = null; service = null
    }
}

class VirtualCameraSession internal constructor(private val s: IVirtualCameraSession) {
    fun attach(surface: Surface, width: Int, height: Int, fps: Int = 30, fit: Int = VcamApi.FIT_CROP): ClientResult<StreamConfig> = try {
        ClientResult.Ok(s.attachSurface(surface, StreamRequest(width, height, fps, fit)))
    } catch (e: Exception) { ClientResult.Err("REMOTE_ERROR", e.message ?: e.javaClass.simpleName) }

    @Throws(VcamException::class)
    fun attachOrThrow(surface: Surface, width: Int, height: Int, fps: Int, fit: Int): StreamConfig =
        when (val r = attach(surface, width, height, fps, fit)) { is ClientResult.Ok -> r.value; is ClientResult.Err -> throw VcamException(r.kind, r.message) }

    @Throws(VcamException::class)
    fun captureJpegOrThrow(quality: Int): ByteArray =
        when (val r = captureJpeg(quality)) { is ClientResult.Ok -> r.value; is ClientResult.Err -> throw VcamException(r.kind, r.message) }

    fun detach(streamId: Int) { runCatching { s.detachSurface(streamId) } }

    /** Captura JPEG do quadro virtual atual. */
    fun captureJpeg(quality: Int = 92): ClientResult<ByteArray> = try {
        val pfd: ParcelFileDescriptor = s.captureJpeg(quality) ?: return ClientResult.Err("REMOTE_ERROR", "captura indisponível")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ClientResult.Ok(it.readBytes()) }
    } catch (e: Exception) { ClientResult.Err("REMOTE_ERROR", e.message ?: e.javaClass.simpleName) }

    fun stats(): SessionStats? = runCatching { s.stats }.getOrNull()
    fun close() { runCatching { s.close() } }
}
