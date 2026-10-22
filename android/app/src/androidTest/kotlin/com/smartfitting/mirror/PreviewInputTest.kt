package com.smartfitting.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.tryon.OutfitFit
import com.smartfitting.mirror.tryon.TryOnView
import com.smartfitting.mirror.vision.BitmapPixels
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No camera: intentional synthetic pause, explicit stop/restart and lifecycle cleanup. */
@RunWith(AndroidJUnit4::class)
class PreviewInputTest {
    @Test fun pausedSyntheticStaysLiveButManualStopClearsPixelsAndSurvivesRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            fun model(activity: TryOnActivity) = ViewModelProvider(activity)[TryOnModel::class.java]
            fun awaitFrame() {
                var ready = false
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity {
                        ready = it.window.decorView.findViewWithTag<TextView>("fitting.vision").text.contains("合成输入")
                    }
                    if (!ready) SystemClock.sleep(50)
                }
                assertTrue("Expected a synthetic frame without opening a camera", ready)
            }
            scenario.onActivity {
                model(it).playing = false
                // The source is configured onCreate, so pause through its actual control.
                it.window.decorView.findViewWithTag<Button>("fitting.mock").performClick()
                it.window.decorView.findViewWithTag<Button>("synthetic.play").performClick()
            }
            awaitFrame()
            // A deliberate pause longer than the RGB deadline must not be treated as a fault.
            SystemClock.sleep(2_300)
            lateinit var source: Bitmap
            lateinit var fit: OutfitFit
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertTrue(model(activity).running)
                assertNull(model(activity).visionFailure)
                assertFalse(model(activity).rgb)
                root.findViewWithTag<Button>("fitting.preview.fullscreen").performClick()
                root.findViewWithTag<Button>("fitting.preview.plus").performClick()
                fit = OutfitFit(width = 1.17f)
                model(activity).outfitFits = mapOf(GarmentCategory.UPPER_BODY to fit)
                val preview = root.findViewWithTag<TryOnView>("fitting.preview")
                source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
                val frame = FrameLease(VisionFrame(sessionId = "generated-test", frameId = 1, timestampMs = 1,
                    image = ImageMetadata("test", 100, 100), trackingState = TrackingState.LOST, keypoints = Keypoints()),
                    BitmapPixels(source)) { it.bitmap.recycle() }
                preview.submit(frame, null); frame.release()
                val input = root.findViewWithTag<Button>("fitting.preview.input")
                assertTrue(input.isShown); assertEquals("停止预览", input.text.toString())
                input.performClick()
                assertTrue("Stopping must release the view's retained RGB pixels", source.isRecycled)
                assertFalse(model(activity).running)
                assertEquals("开始预览", input.text.toString())
                assertFalse(root.findViewWithTag<Button>("fitting.generate").isEnabled)
                val rendered = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
                preview.draw(Canvas(rendered))
                assertEquals("No stale frame or garment", Color.rgb(25, 39, 49), rendered.getPixel(preview.width / 2, preview.height / 2))
                rendered.recycle()
            }
            scenario.recreate(); instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertFalse("A manual stop must survive recreation", model(activity).running)
                assertFalse(model(activity).rgb)
                assertTrue(model(activity).previewFullscreen)
                assertEquals(1.25f, model(activity).previewViewport.zoom, 0f)
                assertEquals(fit, model(activity).outfitFits[GarmentCategory.UPPER_BODY])
                val input = root.findViewWithTag<Button>("fitting.preview.input")
                assertTrue(input.contentDescription.contains("合成样例"))
                input.performClick()
                assertFalse("Restart keeps the chosen source; never opens RGB", model(activity).rgb)
            }
            awaitFrame()
            scenario.onActivity {
                assertTrue(model(it).running)
                it.window.decorView.findViewWithTag<Button>("fitting.preview.input").performClick()
            }
        }
    }
}
