package com.smartfitting.mirror.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BitmapAndSourceTest {
    @Test fun rotateToUprightWithoutMirroring() {
        val input = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        input.setPixel(0, 0, Color.RED)
        input.setPixel(1, 0, Color.GREEN)
        input.setPixel(0, 2, Color.BLUE)
        val result = uprightBitmap(input, 90)
        assertEquals(3, result.width); assertEquals(2, result.height)
        assertEquals(Color.RED, result.getPixel(2, 0))
        assertEquals(Color.GREEN, result.getPixel(2, 1))
        assertEquals(Color.BLUE, result.getPixel(0, 0))
        assertTrue(input.isRecycled)
        result.recycle()
    }
    @Test fun syntheticCaptureContainsRawPixelsAndStopsCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        lateinit var source: SyntheticVisionSource
        var callbacks = 0
        instrumentation.runOnMainSync {
            source = SyntheticVisionSource()
            source.start("instrumented", { lease ->
                try {
                    callbacks++
                    assertEquals(VisionSourceKind.MOCK, source.capabilities.source)
                    assertFalse(lease.metadata.image.mirrored)
                    source.captureOriginal { result ->
                        result.getOrThrow().use { photo ->
                            val decoded = BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)
                            assertEquals(480, decoded.width)
                            assertEquals(Color.rgb(110, 160, 180), decoded.getPixel(70, 70))
                            // A shoulder is drawn by the View, never burned into captured pixels.
                            val shoulder = lease.metadata.keypoints.leftShoulder!!
                            val color = decoded.getPixel((shoulder.x * 479).toInt(), (shoulder.y * 639).toInt())
                            assertNotEquals(Color.WHITE, color)
                            decoded.recycle()
                        }
                    }
                    source.stop(); source.stop()
                } catch (error: Throwable) { failure.set(error) }
                finally { completed.countDown() }
            }, {})
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { source.close() }
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(1, callbacks)
    }
}
