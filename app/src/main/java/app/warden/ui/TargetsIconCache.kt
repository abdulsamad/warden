package app.warden.ui

import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Byte-bounded cache of app icons, decoded lazily per visible row. Only bitmaps are retained
 * (never Drawables). Failures resolve to null so callers keep showing the placeholder.
 */
@Stable
class TargetsIconCache(private val pm: PackageManager) {

    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** Synchronous cache hit, or null. Safe on the main thread. */
    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(packageName: String, sizePx: Int): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = pm.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap()
                cache.put(packageName, bitmap)
                bitmap
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // NameNotFoundException or any decode failure: fall back to the placeholder.
                null
            }
        }
    }

    fun clear() = cache.evictAll()

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
