package br.com.neurelix.vcam.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.util.LruCache
import br.com.neurelix.vcam.core.model.SourceKind
import br.com.neurelix.vcam.core.model.SourceSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Miniaturas das fontes da galeria (foto: decodificada reduzida; vídeo: frame em ~0,5 s). Cache em memória. */
object Thumbs {
    private val cache = LruCache<String, Bitmap>(48)

    suspend fun load(spec: SourceSpec, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${spec.id}@$px"
        cache.get(key)?.let { return@withContext it }
        val path = spec.path ?: return@withContext null
        val f = File(path)
        if (!f.exists() || f.length() == 0L) return@withContext null
        val bmp = runCatching {
            when (spec.kind) {
                SourceKind.IMAGE -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val m = max(info.size.width, info.size.height)
                    if (m > px) {
                        val k = px.toFloat() / m
                        d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                    }
                }
                SourceKind.VIDEO -> MediaMetadataRetriever().use { r ->
                    r.setDataSource(path)
                    val full = r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@use null
                    val k = px.toFloat() / max(full.width, full.height)
                    if (k >= 1f) full else Bitmap.createScaledBitmap(full, (full.width * k).toInt().coerceAtLeast(1), (full.height * k).toInt().coerceAtLeast(1), true).also { full.recycle() }
                }
                else -> null
            }
        }.getOrNull()
        bmp?.also { cache.put(key, it) }
    }
}
