package com.smartfitting.mirror

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.smartfitting.mirror.contracts.CapturedPhoto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Local-only decoding. Caller owns the input stream and the returned photo. */
internal object StaticImageInput {
    const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_PIXELS = 16_000_000L
    private const val DECODE_PIXELS = 4_000_000L
    private const val MAX_EDGE = 2048

    fun read(input: InputStream, checkActive: () -> Unit = {}): CapturedPhoto {
        val raw = readBounded(input, checkActive)
        val bitmaps = mutableListOf<Bitmap>()
        fun own(bitmap: Bitmap): Bitmap {
            if (bitmaps.none { it === bitmap }) bitmaps.add(bitmap)
            return bitmap
        }
        try {
            checkActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            require(bounds.outMimeType in setOf("image/jpeg", "image/png")) { "请选择 JPEG 或 PNG 照片" }
            val width = bounds.outWidth
            val height = bounds.outHeight
            require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS) {
                "图片尺寸无效或超过 1600 万像素"
            }
            val orientation = ExifInterface(ByteArrayInputStream(raw))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            var sample = 1
            while ((width.toLong() * height / sample / sample > DECODE_PIXELS) ||
                max(width, height) / sample > MAX_EDGE * 2) sample *= 2
            val decoded = own(requireNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size,
                BitmapFactory.Options().apply { inSampleSize = sample })) { "图片无法解码" })
            checkActive()
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            var normalized = own(Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true))
            val longest = max(normalized.width, normalized.height)
            if (longest > MAX_EDGE) {
                val scale = MAX_EDGE.toFloat() / longest
                normalized = own(Bitmap.createScaledBitmap(normalized,
                    (normalized.width * scale).roundToInt().coerceAtLeast(1),
                    (normalized.height * scale).roundToInt().coerceAtLeast(1), true))
            }
            val opaque = own(Bitmap.createBitmap(normalized.width, normalized.height, Bitmap.Config.ARGB_8888))
            Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(normalized, 0f, 0f, null) }
            checkActive()
            val encoded = WipingOutput(MAX_BYTES).use { output ->
                check(opaque.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "图片编码失败" }
                output.toByteArray()
            }
            try {
                checkActive()
                // Transfer a private copy; no EXIF/location metadata is copied.
                return CapturedPhoto("static-import", 0L, opaque.width, opaque.height, "image/jpeg", encoded.copyOf())
            } finally { encoded.fill(0) }
        } finally {
            raw.fill(0)
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun readBounded(input: InputStream, checkActive: () -> Unit): ByteArray {
        val buffer = ByteArray(8192)
        try {
            return WipingOutput(MAX_BYTES).use { output ->
                while (true) {
                    checkActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - output.size() + 1))
                    if (count < 0) break
                    if (count == 0) {
                        val next = input.read()
                        if (next < 0) break
                        output.write(next)
                    } else output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { buffer.fill(0) }
    }

    private class WipingOutput(private val limit: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            if (count >= limit) throw IOException("图片超过 10 MiB")
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > limit - count) throw IOException("图片超过 10 MiB")
            super.write(bytes, offset, length)
        }
        override fun close() { buf.fill(0); reset(); super.close() }
    }
}
