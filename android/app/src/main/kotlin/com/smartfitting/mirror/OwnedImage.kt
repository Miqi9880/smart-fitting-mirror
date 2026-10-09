package com.smartfitting.mirror

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import kotlinx.coroutines.*

internal fun decodeBounded(bytes: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) {
        "图片尺寸无效或超过 1600 万像素"
    }
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "图片无法解码" }
}

/** Owns only its decoded bitmap and a private byte copy; controller storage remains untouched. */
internal class OwnedImage(private val view: ImageView, private val scope: CoroutineScope,
                          private val onError: (String) -> Unit) {
    private var source: ByteArray? = null
    private var bitmap: Bitmap? = null
    private var loading: Job? = null
    fun update(bytes: ByteArray?) {
        if (source === bytes) return
        clear()
        if (bytes == null) return
        source = bytes
        val copy = bytes.copyOf()
        loading = scope.launch {
            var decoded: Bitmap? = null
            try {
                withContext(Dispatchers.Default) { decoded = decodeBounded(copy) }
                ensureActive()
                if (source === bytes) { bitmap = decoded; view.setImageBitmap(bitmap); decoded = null }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onError(error.message ?: "图片加载失败")
            } finally { copy.fill(0); decoded?.recycle() }
        }
        // Also clear the private copy if cancelled before launch is dispatched.
        loading?.invokeOnCompletion { copy.fill(0) }
    }
    fun clear() {
        source = null; loading?.cancel(); loading = null
        view.setImageDrawable(null); bitmap?.recycle(); bitmap = null
    }
}
