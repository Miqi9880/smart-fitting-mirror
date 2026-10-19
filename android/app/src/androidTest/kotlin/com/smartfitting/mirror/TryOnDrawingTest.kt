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
    @Test fun currentLandmarksMirrorWithoutMutatingOriginalAndDisappearOnLost() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = TryOnView(InstrumentationRegistry.getInstrumentation().targetContext)
            view.layout(0,0,480,640); view.mirrored = false
            val bitmap = Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            val points = Keypoints(leftShoulder=Landmark(.8f,.25f,.9f))
            val frame = FrameLease(VisionFrame(sessionId="dots",frameId=1,timestampMs=1,
                image=ImageMetadata("dots",480,640),trackingState=points.state(),keypoints=points),BitmapPixels(bitmap)) { it.bitmap.recycle() }
            view.submit(frame,null)
            val output = Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(output)); assertEquals(Color.BLACK,output.getPixel(384,160))
            view.showLandmarks = true; view.draw(Canvas(output))
            assertEquals(Color.YELLOW,output.getPixel(384,160))
            assertEquals(Color.BLACK,bitmap.getPixel(384,160))
            view.mirrored = true; view.draw(Canvas(output))
            assertEquals(Color.YELLOW,output.getPixel(96,160)); assertEquals(Color.BLACK,output.getPixel(384,160))
            val lost = FrameLease(frame.metadata.copy(frameId=2,trackingState=TrackingState.LOST,keypoints=Keypoints()),BitmapPixels(bitmap)) { }
            view.submit(lost,null); lost.release(); view.draw(Canvas(output))
            assertEquals(Color.BLACK,output.getPixel(96,160))
            view.clear(); frame.release(); output.recycle()
        }
    }
    @Test fun objProjectionDrawsCurrentBodyAndHidesLost() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = TryOnView(InstrumentationRegistry.getInstrumentation().targetContext)
            view.layout(0,0,480,640); view.mirrored = false; view.mode = TryOnView.Mode.OBJ_MESH
            val bitmap = Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            val points = SyntheticScene.sample(SyntheticScenario.STILL,0)
            val frame = FrameLease(VisionFrame(sessionId="obj",frameId=1,timestampMs=1,image=ImageMetadata("obj",480,640),
                trackingState=points.state(),keypoints=points),BitmapPixels(bitmap)) { it.bitmap.recycle() }
            view.submit(frame,null); val output = Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(output)); assertNotEquals(Color.BLACK,output.getPixel(240,260))
            val lost = FrameLease(frame.metadata.copy(frameId=2,trackingState=TrackingState.LOST,keypoints=Keypoints()),BitmapPixels(bitmap)) { }
            view.submit(lost,null); lost.release(); view.draw(Canvas(output))
            assertEquals(Color.BLACK,output.getPixel(240,260))
            view.clear(); frame.release(); output.recycle()
        }
    }
    @Test fun metricDepthClipsOnlyGarmentAndUnknownDistanceDoesNotInventOcclusion() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = TryOnView(InstrumentationRegistry.getInstrumentation().targetContext)
            view.layout(0,0,100,100); view.mirrored = false
            val points = Keypoints(Landmark(.8f,.2f,.9f),Landmark(.2f,.2f,.9f),
                leftHip=Landmark(.7f,.8f,.9f),rightHip=Landmark(.3f,.8f,.9f))
            val bitmap = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val depth = DepthMetadata("depth",100,100,200)
            val bytes = ByteArray(20000)
            for (y in 0..99) for (x in 0..99) {
                val mm = if (x < 50) 800 else 1200
                bytes[y*200+x*2] = mm.toByte(); bytes[y*200+x*2+1] = (mm shr 8).toByte()
            }
            val metadata = VisionFrame(sessionId="depth",frameId=1,timestampMs=1,image=ImageMetadata("depth-rgb",100,100),
                trackingState=points.state(),keypoints=points,distanceM=1f,depth=depth)
            val frame = FrameLease(metadata,BitmapPixels(bitmap,AlignedDepthPixels(depth,bytes))) { it.bitmap.recycle(); bytes.fill(0) }
            val anchors = GarmentAnchors(Anchor(.75f,.15f),Anchor(.25f,.15f),Anchor(.7f,.85f),Anchor(.3f,.85f))
            val garment = Garment("red","red",GarmentCategory.UPPER_BODY,emptyList(),"http://localhost/red.png",PreviewImage("http://localhost/red.png",100,100,anchors),null)
            view.setAsset(garment,Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
            view.submit(frame,garment); val output = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(output)); assertEquals(Color.GREEN,output.getPixel(40,50)); assertEquals(Color.RED,output.getPixel(60,50))
            assertEquals(Color.GREEN,bitmap.getPixel(60,50))
            val unknown = FrameLease(metadata.copy(frameId=2,distanceM=null),frame.pixels) { }
            view.submit(unknown,garment); unknown.release(); view.draw(Canvas(output))
            assertEquals(Color.RED,output.getPixel(40,50))
            view.clear(); frame.release(); output.recycle()
        }
    }
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
