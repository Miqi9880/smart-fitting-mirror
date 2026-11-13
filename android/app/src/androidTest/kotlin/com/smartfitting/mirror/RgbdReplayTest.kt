package com.smartfitting.mirror

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.SourceState
import com.smartfitting.mirror.vision.DepthGeometry
import com.smartfitting.mirror.vision.RgbdReader
import com.smartfitting.mirror.vision.RgbdSample
import com.smartfitting.mirror.vision.RgbdVisionSource
import com.smartfitting.mirror.vision.StandingCue
import com.smartfitting.mirror.vision.StandingGuide
import com.smartfitting.mirror.vision.StandingRange
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RgbdReplayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun approvedCgReplayProducesForegroundAndMeasuredStandingCues() {
        val context = instrumentation.targetContext
        val filename = InstrumentationRegistry.getArguments().getString("rgbd_cg_fixture")
        assumeNotNull(filename)
        require(filename!!.matches(Regex("[a-z0-9_.-]+")))
        val bitmap = requireNotNull(BitmapFactory.decodeFile(File(context.filesDir, filename).path,
            BitmapFactory.Options().apply { inSampleSize = 2 }))
        val width = bitmap.width
        val height = bitmap.height
        val depth = DepthGeometry.millimeterPlane("test_depth", width, height,
            ByteArray(width * height * 2) { if (it % 2 == 0) 0x6c else 0x07 }, 1f, 0)
        val outcomes = LinkedBlockingQueue<Result<Unit>>()
        val source = RgbdVisionSource(context, RgbdReader { active, emit ->
            emit(RgbdSample(bitmap, depth, SystemClock.elapsedRealtime()))
            while (active()) Thread.sleep(20)
        })
        try {
            instrumentation.runOnMainSync {
                source.start("approved-cg", { frame ->
                    outcomes.offer(runCatching {
                        val metadata = frame.metadata
                        val mask = requireNotNull(frame.pixels.personMask)
                        assertEquals(mask.metadata, metadata.mask)
                        assertTrue(mask.confidenceAt(.5f, .43f) >= 166)
                        assertTrue(mask.confidenceAt(.03f, .03f) < 166)
                        assertEquals(1.9f, requireNotNull(metadata.distanceM), .001f)
                        assertEquals(StandingCue.TOO_NEAR, StandingGuide.assess(metadata, true, StandingRange(2.1f, 3f)).cue)
                        assertEquals(StandingCue.TOO_FAR, StandingGuide.assess(metadata, true, StandingRange(.8f, 1.5f)).cue)
                        assertNull(StandingGuide.assess(metadata, false, StandingRange(.8f, 1.5f)).distanceM)
                    })
                }, { state ->
                    if (state.state == SourceState.ERROR) outcomes.offer(Result.failure(AssertionError(state.message)))
                })
            }
            requireNotNull(outcomes.poll(20, TimeUnit.SECONDS)) { "Replay produced no result" }.getOrThrow()
        } finally {
            instrumentation.runOnMainSync { source.close() }
        }
    }

    @Test fun absentGeminiReportsUnavailableAndDoesNotPublishFrames() {
        val context = instrumentation.targetContext
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        assumeTrue(usb.deviceList.values.none { it.vendorId == 0x2bc5 && it.productId == 0x0670 })
        val source = geminiDepthSource(context)
        assumeNotNull(source)
        val outcomes = LinkedBlockingQueue<SourceState>()
        try {
            instrumentation.runOnMainSync {
                source!!.start("no-usb-device", { outcomes.offer(SourceState.READY) },
                    { update -> outcomes.offer(update.state) })
            }
            assertEquals(SourceState.STARTING, outcomes.poll(3, TimeUnit.SECONDS))
            assertEquals(SourceState.ERROR, outcomes.poll(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { source!!.close() }
        }
    }
}
