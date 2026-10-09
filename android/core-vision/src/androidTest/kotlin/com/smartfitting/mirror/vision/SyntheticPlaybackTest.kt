package com.smartfitting.mirror.vision

import android.graphics.BitmapFactory
import android.os.SystemClock
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
class SyntheticPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun pauseStepSeekAndViewportKeepFrameContract() {
        lateinit var source: SyntheticVisionSource
        val first = CountDownLatch(1)
        val frames = mutableListOf<VisionFrame>()
        instrumentation.runOnMainSync {
            source = SyntheticVisionSource()
            source.configure(SyntheticSettings(SyntheticScenario.RECOVERY))
            source.setPlaying(false)
            source.start("playback", { frames.add(it.metadata); first.countDown() }, {})
        }
        try {
            assertTrue(first.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(250)
            instrumentation.runOnMainSync {
                assertEquals(1, frames.size)
                source.step()
                assertEquals(1L, source.lastSampleStep)
                source.seekTo(20); assertEquals(TrackingState.PARTIAL, frames.last().trackingState)
                source.seekTo(40); assertEquals(TrackingState.LOST, frames.last().trackingState)
                source.seekTo(60); assertEquals(TrackingState.TRACKING, frames.last().trackingState)
                source.seekTo(0); assertEquals(frames.first().keypoints, frames.last().keypoints)
                source.configure(source.settings.copy(viewport = SyntheticViewport.LANDSCAPE))
                assertEquals(640, frames.last().image.width); assertEquals(480, frames.last().image.height)
                assertEquals(frames.first().keypoints, frames.last().keypoints)
                assertTrue(frames.zipWithNext().all { (a, b) -> b.frameId > a.frameId && b.timestampMs >= a.timestampMs })
                assertEquals(frames.size, frames.map { it.image.bufferId }.distinct().size)
                frames.forEach {
                    assertFalse(it.image.mirrored); assertNull(it.depth); assertNull(it.mask); assertNull(it.distanceM)
                    assertEquals(StandingHint.UNKNOWN, it.standingHint)
                }
            }
            val captured = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync {
                source.captureOriginal { result ->
                    try {
                        result.getOrThrow().use { photo ->
                            val bitmap = BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)
                            assertEquals(640, bitmap.width); assertEquals(480, bitmap.height)
                            assertEquals(frames.last().frameId, photo.frameId)
                            bitmap.recycle()
                        }
                    } catch (e: Throwable) { error.set(e) }
                    finally { captured.countDown() }
                }
            }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError(it) }
        } finally { instrumentation.runOnMainSync { source.close() } }
    }

    @Test fun restartingDuringCaptureRejectsOldSessionAndReleasesOldPixels() {
        val completed = CountDownLatch(1)
        val newFrame = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        lateinit var source: SyntheticVisionSource
        instrumentation.runOnMainSync {
            source = SyntheticVisionSource()
            source.setPlaying(false)
            source.start("old", { old ->
                source.captureOriginal { result ->
                    try {
                        assertTrue(result.isFailure)
                        assertThrows(IllegalStateException::class.java) { old.pixels }
                    } catch (e: Throwable) { error.set(e) }
                    finally { result.getOrNull()?.close(); completed.countDown() }
                }
                source.start("new", { fresh ->
                    try {
                        assertEquals("new", fresh.metadata.sessionId)
                        assertEquals(1L, fresh.metadata.frameId)
                        assertNotEquals(old.metadata.image.bufferId, fresh.metadata.image.bufferId)
                    } catch (e: Throwable) { error.set(e) }
                    finally { newFrame.countDown() }
                }, {})
            }, {})
        }
        try {
            assertTrue(completed.await(5, TimeUnit.SECONDS)); assertTrue(newFrame.await(5, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError(it) }
        } finally { instrumentation.runOnMainSync { source.close() } }
    }

    @Test fun stopFromReadyCallbackDoesNotRestartTheTimer() {
        lateinit var source: SyntheticVisionSource
        var count = 0
        instrumentation.runOnMainSync {
            source = SyntheticVisionSource()
            source.start("immediately-stopped", { count++ }, { if (it.state == SourceState.READY) source.stop() })
        }
        SystemClock.sleep(250)
        instrumentation.runOnMainSync {
            assertEquals(0, count)
            source.close()
        }
    }
}
