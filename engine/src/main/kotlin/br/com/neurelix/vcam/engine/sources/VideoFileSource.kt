package br.com.neurelix.vcam.engine.sources

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.engine.FrameSource
import br.com.neurelix.vcam.engine.FrameTarget
import br.com.neurelix.vcam.engine.PausableSource
import br.com.neurelix.vcam.engine.SourceFormat
import br.com.neurelix.vcam.engine.SourceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Vídeo da galeria via Media3/ExoPlayer (MediaCodec) renderizando direto em Surface OES: zero cópia para CPU.
 * Áudio desativado (sem foco de áudio). Rotação de metadados e mudança de resolução chegam via
 * VideoSize + matriz do SurfaceTexture. Loop opcional.
 */
class VideoFileSource(
    private val context: Context,
    private val file: File,
    private val loop: Boolean,
    override val label: String = file.name,
) : FrameSource, PausableSource {
    private val _state = MutableStateFlow<SourceState>(SourceState.Idle)
    override val state: StateFlow<SourceState> = _state
    private var player: ExoPlayer? = null
    private var target: FrameTarget? = null

    override suspend fun start(target: FrameTarget) {
        this.target = target
        _state.value = SourceState.Starting
        if (!file.exists() || file.length() == 0L) {
            val msg = "Arquivo de vídeo ausente ou vazio"
            _state.value = SourceState.Error(msg); throw IllegalStateException(msg)
        }
        val ready = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            val surface = target.acquireInputSurface(1280, 720)
            val p = ExoPlayer.Builder(context).build()
            player = p
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            p.volume = 0f
            p.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            p.setVideoSurface(surface)
            p.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width <= 0 || videoSize.height <= 0) return
                    val w = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
                    AppLog.i("codec", "video", "tamanho do vídeo ${videoSize.width}x${videoSize.height} par=${videoSize.pixelWidthHeightRatio} rotNaoAplicada=${videoSize.unappliedRotationDegrees}")
                    target.onFormat(SourceFormat(w, videoSize.height, videoSize.unappliedRotationDegrees,
                        "Vídeo ${videoSize.width}x${videoSize.height} (Media3/MediaCodec → OES)", timestampIsRealtime = false, live = true))
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        if (_state.value !is SourceState.Paused) _state.value = SourceState.Running
                        if (!ready.isCompleted) ready.complete(Unit)
                        if (_state.value is SourceState.Lost) target.onSourceRecovered()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val msg = "Falha do codec/decodificador (${error.errorCodeName}): ${error.message}"
                    AppLog.e("codec", "video", msg, error)
                    _state.value = SourceState.Lost(msg)
                    if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(msg)) else target.onSourceLost(msg)
                }
            })
            p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            p.playWhenReady = true
            p.prepare()
        }
        try {
            withTimeout(10_000) { ready.await() }
        } catch (t: Throwable) {
            _state.value = SourceState.Error(t.message ?: "Vídeo não iniciou")
            stop()
            throw IllegalStateException("Não foi possível iniciar o vídeo: ${t.message}", t)
        }
    }

    override suspend fun setPaused(paused: Boolean) {
        withContext(Dispatchers.Main) { player?.playWhenReady = !paused }
        _state.value = if (paused) SourceState.Paused else SourceState.Running
    }

    override suspend fun stop() {
        val p = player ?: run { _state.value = SourceState.Idle; return }
        player = null
        withContext(Dispatchers.Main) { runCatching { p.release() } }
        _state.value = SourceState.Idle
    }
}
