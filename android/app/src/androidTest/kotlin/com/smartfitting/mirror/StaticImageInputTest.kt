package com.smartfitting.mirror

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException

/** Generated pixels only; never reads a user photo or contacts a service. */
@RunWith(AndroidJUnit4::class)
class StaticImageInputTest {
    @Test fun allEightExifOrientationsAreNormalizedWithoutEditingSourceOrCopyingGps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        for (y in 0 until 60) for (x in 0 until 80) bitmap.setPixel(x, y,
            if (y < 30) { if (x < 40) Color.RED else Color.GREEN }
            else { if (x < 40) Color.BLUE else Color.YELLOW })
        val expected = listOf(Color.RED, Color.GREEN, Color.YELLOW, Color.BLUE,
            Color.RED, Color.BLUE, Color.YELLOW, Color.GREEN)
        val file = File.createTempFile("generated-exif-", ".jpg", context.cacheDir)
        try {
            for (orientation in 1..8) {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    setLatLong(30.0, 120.0); saveAttributes()
                }
                val original = file.readBytes()
                val photo = file.inputStream().use { StaticImageInput.read(it) }
                val stored = photo.bytes
                try {
                    assertEquals(if (orientation >= 5) 60 else 80, photo.width)
                    assertEquals(if (orientation >= 5) 80 else 60, photo.height)
                    val decoded = BitmapFactory.decodeByteArray(stored, 0, stored.size)
                    try {
                        val pixel = decoded.getPixel(photo.width / 4, photo.height / 4)
                        val target = expected[orientation - 1]
                        assertTrue("orientation=$orientation color=$pixel", listOf(
                            Color.red(pixel) - Color.red(target), Color.green(pixel) - Color.green(target),
                            Color.blue(pixel) - Color.blue(target)).all { kotlin.math.abs(it) < 35 })
                    } finally { decoded.recycle() }
                    val exif = ExifInterface(ByteArrayInputStream(stored))
                    assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                    assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 0..1)
                    assertArrayEquals(original, file.readBytes())
                } finally { photo.close() }
                assertTrue(stored.all { it == 0.toByte() })
            }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun transparentPngUsesWhiteBackgroundAndLargeEdgeIsBounded() {
        val bitmap = Bitmap.createBitmap(3000, 1000, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        bitmap.recycle()
        StaticImageInput.read(ByteArrayInputStream(bytes)).use { photo ->
            assertEquals(2048, photo.width); assertEquals(683, photo.height)
            val output = BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)
            try { assertEquals(Color.WHITE, output.getPixel(100, 100)) } finally { output.recycle() }
            assertEquals("image/jpeg", photo.mimeType)
        }
    }

    @Test fun sourceReadRejectsAtLimitPlusOneAndInvalidFormatsFail() {
        var read = 0
        val unbounded = object : InputStream() {
            override fun read(): Int { read++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(1, off, off + len); read += len; return len
            }
        }
        try { StaticImageInput.read(unbounded); fail("Oversized stream accepted") }
        catch (_: IOException) { assertEquals(StaticImageInput.MAX_BYTES + 1, read) }
        try { StaticImageInput.read(ByteArrayInputStream("bad image".toByteArray())); fail("Invalid image accepted") }
        catch (_: IllegalArgumentException) { }
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        @Suppress("DEPRECATION") val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.WEBP, 100, this) }.toByteArray()
        bitmap.recycle()
        try { StaticImageInput.read(ByteArrayInputStream(bytes)); fail("WEBP accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun declaredPixelBombIsRejectedBeforeBitmapAllocation() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        bitmap.recycle()
        java.nio.ByteBuffer.wrap(bytes, 16, 8).putInt(5000).putInt(4000)
        val crc = java.util.zip.CRC32().apply { update(bytes, 12, 17) }
        java.nio.ByteBuffer.wrap(bytes, 29, 4).putInt(crc.value.toInt())
        try { StaticImageInput.read(ByteArrayInputStream(bytes)); fail("Pixel bomb accepted") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("1600")) }
    }
}
