package br.com.neurelix.vcam.engine

import android.graphics.Bitmap
import android.view.Surface
import br.com.neurelix.vcam.core.model.PatternType
import kotlinx.coroutines.flow.StateFlow

sealed interface SourceState {
    data object Idle : SourceState
    data object Starting : SourceState
    data object Running : SourceState
    data object Paused : SourceState
    data class Lost(val reason: String) : SourceState
    data class Error(val message: String) : SourceState
}

/**
 * Formato do conteúdo entregue pela fonte.
 * @param width,height dimensões do buffer no espaço de amostragem (já com a matriz do SurfaceTexture aplicada)
 * @param rotationDegrees rotação horária ainda necessária para deixar o conteúdo "em pé"
 *        (câmeras: SENSOR_ORIENTATION; vídeo/imagem: 0, pois a rotação já está resolvida).
 */
data class SourceFormat(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val description: String = "",
    /** true se o timestamp do SurfaceTexture usa a mesma base de SystemClock.elapsedRealtimeNanos. */
    val timestampIsRealtime: Boolean = false,
    /** true se a fonte produz quadros continuamente por conta própria (câmera, vídeo). */
    val live: Boolean = true,
)

/** Destino dos quadros. Implementado pelo [RenderEngine]. Caminho crítico = GPU, sem Flow. */
interface FrameTarget {
    /** Caminho GPU: Surface (SurfaceTexture/OES) em que o produtor (Camera2, MediaCodec) escreve. */
    fun acquireInputSurface(width: Int, height: Int): Surface
    fun submitBitmap(bitmap: Bitmap)
    fun submitProcedural(pattern: PatternType)
    fun onFormat(format: SourceFormat)
    fun onSourceLost(reason: String)
    fun onSourceRecovered()
}

/**
 * Fonte de quadros. Não usa Flow de pixels: backpressure é resolvida pelo SurfaceTexture
 * (o consumidor GL sempre pega o quadro mais novo; os intermediários são descartados e contados).
 * [state] é Flow apenas para a UI/diagnóstico.
 */
interface FrameSource {
    val label: String
    val state: StateFlow<SourceState>
    suspend fun start(target: FrameTarget)
    suspend fun stop()
}

/** Fontes que suportam pausa (vídeo). */
interface PausableSource {
    suspend fun setPaused(paused: Boolean)
}
