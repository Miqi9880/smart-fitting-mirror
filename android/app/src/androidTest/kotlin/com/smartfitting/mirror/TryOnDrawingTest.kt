package com.smartfitting.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.tryon.TryOnView
import com.smartfitting.mirror.vision.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TryOnDrawingTest {
    @Test fun drawsGarmentMirrorsWholeFrameHidesLostAndClearsLeases() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = TryOnView(instrumentation.targetContext)
            view.layout(0, 0, 480, 640)
            var disposed = 0
            fun frame(scene: SyntheticScenario): FrameLease<BitmapPixels> {
                val image = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
                Canvas(image).apply { drawColor(Color.BLACK); drawCircle(40f, 60f, 8f, Paint().apply { color = Color.GREEN }) }
                val points = SyntheticScene.sample(scene, 0)
                return FrameLease(VisionFrame(sessionId = "render", frameId = 1, timestampMs = 1,
                    image = ImageMetadata("pixel", 480, 640), trackingState = points.state(), keypoints = points),
                    BitmapPixels(image)) { disposed++; it.bitmap.recycle() }
            }
            val anchors = GarmentAnchors(Anchor(.75f,.15f), Anchor(.25f,.15f), Anchor(.7f,.85f), Anchor(.3f,.85f))
            val garment = Garment("red", "Red", GarmentCategory.UPPER_BODY, emptyList(), "http://localhost/red.png",
                PreviewImage("http://localhost/red.png", 100, 100, anchors), null)
            fun asset(color: Int) = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            val red = asset(Color.RED)
            view.setAsset(garment, red)
            val frame = frame(SyntheticScenario.STILL)
            view.submit(frame, garment); frame.release()
            val output = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
            view.mirrored = false; view.draw(Canvas(output))
            assertEquals(Color.RED, output.getPixel(240, 290))
            assertEquals(Color.GREEN, output.getPixel(40, 60))
            view.mirrored = true; view.draw(Canvas(output))
            assertEquals(Color.GREEN, output.getPixel(440, 60))
            // Change clothing while input is paused: no new submit is needed.
            val yellow = asset(Color.YELLOW)
            view.setAsset(garment.copy(garmentId = "yellow"), yellow)
            view.draw(Canvas(output))
            assertEquals(Color.YELLOW, output.getPixel(240, 290))
            assertTrue(red.isRecycled)
            val lost = frame(SyntheticScenario.LOST)
            view.submit(lost, garment.copy(garmentId = "yellow")); lost.release()
            view.draw(Canvas(output))
            assertEquals(Color.BLACK, output.getPixel(240, 290))
            assertEquals(1, disposed)
            view.clear(); assertEquals(2, disposed); assertTrue(yellow.isRecycled)
            output.recycle()
        }
    }
}
