package br.com.neurelix.vcam.engine.sources

import android.graphics.ImageDecoder
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.engine.FrameSource
import br.com.neurelix.vcam.engine.FrameTarget
import br.com.neurelix.vcam.engine.SourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Fotografia estática. Decodifica uma vez (ImageDecoder aplica EXIF), limita a lado maior [MAX_DIM],
 * envia à GPU e libera o bitmap. Depois disso não há CPU por quadro: o motor só re-renderiza a textura.
 */
class StaticImageSource(private val file: File, override val label: String = file.name) : FrameSource {
    private val _state = MutableStateFlow<SourceState>(SourceState.Idle)
    override val state: StateFlow<SourceState> = _state

    override suspend fun start(target: FrameTarget) {
        _state.value = SourceState.Starting
        try {
            val bmp = withContext(Dispatchers.IO) {
                require(file.exists() && file.length() > 0) { "Arquivo de imagem ausente ou vazio" }
                val src = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val m = max(info.size.width, info.size.height)
                    if (m > MAX_DIM) {
                        val k = MAX_DIM.toFloat() / m
                        decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                    }
                }
            }
            AppLog.i("engine", "image", "imagem decodificada ${bmp.width}x${bmp.height}")
            target.submitBitmap(bmp)
            _state.value = SourceState.Running
        } catch (t: Throwable) {
            val msg = "Imagem inválida ou ilegível: ${t.message}"
            _state.value = SourceState.Error(msg)
            AppLog.e("engine", "image", msg, t)
            throw IllegalStateException(msg, t)
        }
    }

    override suspend fun stop() { _state.value = SourceState.Idle }

    companion object { const val MAX_DIM = 3072 }
}
