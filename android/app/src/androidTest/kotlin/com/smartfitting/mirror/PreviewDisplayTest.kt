package com.smartfitting.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.tryon.*
import com.smartfitting.mirror.vision.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Camera-free native regression with generated pixels, not stored human photos. */
@RunWith(AndroidJUnit4::class)
class PreviewDisplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun item(category: GarmentCategory = GarmentCategory.UPPER_BODY) = Garment("generated-$category","程序测试图",category,
        emptyList(),"http://localhost/test.png",PreviewImage("http://localhost/test.png",100,100,
            if (category == GarmentCategory.UPPER_BODY) GarmentAnchors(Anchor(.75f,.45f),Anchor(.25f,.45f),Anchor(.7f,.85f),Anchor(.3f,.85f)) else null,
            if (category == GarmentCategory.HEADWEAR) mapOf("right_ear" to Anchor(0f,1f),"left_ear" to Anchor(1f,1f)) else emptyMap()),null)
    private val pose = Keypoints(leftShoulder=Landmark(.75f,.45f,.95f),rightShoulder=Landmark(.25f,.45f,.95f),
        leftHip=Landmark(.7f,.85f,.95f),rightHip=Landmark(.3f,.85f,.95f),
        leftEar=Landmark(.6f,.2f,.95f),rightEar=Landmark(.4f,.2f,.95f))
    private fun frame(bitmap: Bitmap, points: Keypoints = pose, disposed: () -> Unit = {}) = FrameLease(
        VisionFrame(sessionId="display",frameId=1,timestampMs=1,image=ImageMetadata("rgb",bitmap.width,bitmap.height),
            trackingState=points.state(),keypoints=points),BitmapPixels(bitmap)) { it.bitmap.recycle(); disposed() }
    private fun bitmap(color: Int) = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun upperLayerPreservesHeadButDoesNotEraseHatOrModifyRawPixels() {
        instrumentation.runOnMainSync {
            val view = TryOnView(instrumentation.targetContext).apply { layout(0,0,100,100); mirrored=false }
            val source = bitmap(Color.GREEN)
            val lease = frame(source); val upper = item(); val output = bitmap(Color.BLACK)
            view.setAsset(upper,bitmap(Color.RED)); view.submit(lease,upper)
            view.draw(Canvas(output))
            assertEquals("Head RGB must remain above the hoodie",Color.GREEN,output.getPixel(50,20))
            assertEquals("Chest still shows clothing",Color.RED,output.getPixel(50,60))
            view.protectHead=false; view.draw(Canvas(output)); assertEquals(Color.RED,output.getPixel(50,20))
            view.protectHead=true
            val missing = frame(bitmap(Color.GREEN),pose.copy(leftEar=null))
            view.submit(missing,upper); missing.release(); view.draw(Canvas(output))
            assertEquals("Missing ears must not reuse the last head",Color.RED,output.getPixel(50,20))
            view.submit(lease,upper)
            val hat = item(GarmentCategory.HEADWEAR)
            view.outfitFits = mapOf(GarmentCategory.HEADWEAR to OutfitFit())
            view.setOutfit(listOf(upper,hat)); view.setLayerAsset(upper,bitmap(Color.RED)); view.setLayerAsset(hat,bitmap(Color.BLUE))
            view.draw(Canvas(output)); assertEquals("Independent hat remains above the head",Color.BLUE,output.getPixel(50,15))
            assertEquals(Color.GREEN,source.getPixel(50,20))
            view.clear(); lease.release(); assertTrue(source.isRecycled); output.recycle()
        }
    }

    @Test fun zoomTransformsRgbGarmentAndLandmarksTogetherWithOneMirror() {
        instrumentation.runOnMainSync {
            val view = TryOnView(instrumentation.targetContext).apply { layout(0,0,100,100); mirrored=false; protectHead=false }
            val source = bitmap(Color.GREEN)
            Canvas(source).drawRect(15f,40f,25f,50f,Paint().apply { color=Color.MAGENTA })
            val garment = bitmap(Color.TRANSPARENT)
            Canvas(garment).drawRect(30f,40f,70f,85f,Paint().apply { color=Color.RED })
            val lease = frame(source); val upper = item()
            view.outfitFits = mapOf(GarmentCategory.UPPER_BODY to OutfitFit())
            view.setAsset(upper,garment); view.submit(lease,upper)
            view.viewport = PreviewViewport(1.5f)
            val output=bitmap(Color.BLACK); view.draw(Canvas(output))
            assertEquals(Color.MAGENTA,output.getPixel(5,42))
            assertEquals(Color.RED,output.getPixel(50,65))
            view.showLandmarks=true; view.draw(Canvas(output))
            assertEquals(Color.YELLOW,output.getPixel(87,42))
            view.showLandmarks=false; view.mirrored=true; view.draw(Canvas(output))
            assertEquals(Color.MAGENTA,output.getPixel(95,42))
            view.showLandmarks=true; view.draw(Canvas(output))
            assertEquals(Color.YELLOW,output.getPixel(13,42))
            assertEquals(Color.MAGENTA,source.getPixel(20,45))
            view.clear(); lease.release(); output.recycle()
        }
    }

    @Test fun pinchZoomAndSingleFingerPanAreDisplayOnlyAndBounded() {
        instrumentation.runOnMainSync {
            val view=TryOnView(instrumentation.targetContext).apply { layout(0,0,1200,1200); gesturesEnabled=true }
            val source=bitmap(Color.GREEN); val lease=frame(source)
            view.submit(lease,null)
            val start=SystemClock.uptimeMillis()
            var time=start
            fun send(action: Int, vararg positions: Pair<Float,Float>) {
                time+=30
                val properties=positions.indices.map { index -> MotionEvent.PointerProperties().apply { id=index; toolType=MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
                val coordinates=positions.map { (px,py) -> MotionEvent.PointerCoords().apply { x=px; y=py; pressure=1f; size=1f } }.toTypedArray()
                val event=MotionEvent.obtain(start,time,action,positions.size,properties,coordinates,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                assertTrue(view.dispatchTouchEvent(event)); event.recycle()
            }
            val minimum = if (android.os.Build.VERSION.SDK_INT >= 29)
                android.view.ViewConfiguration.get(instrumentation.targetContext).scaledMinimumScalingSpan else 200
            val half=minimum/2f+60f
            send(MotionEvent.ACTION_DOWN,(600f-half) to 600f)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),(600f-half) to 600f,(600f+half) to 600f)
            for (extra in 20..300 step 20) send(MotionEvent.ACTION_MOVE,(600f-half-extra) to 600f,(600f+half+extra) to 600f)
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),(300f-half) to 600f,(900f+half) to 600f)
            send(MotionEvent.ACTION_UP,(300f-half) to 600f)
            assertTrue("Native pinch must change the viewport; minimumSpan=$minimum, actual=${view.viewport}",view.viewport.zoom>1.2f)
            send(MotionEvent.ACTION_DOWN,200f to 200f)
            send(MotionEvent.ACTION_MOVE,-2000f to 2000f)
            send(MotionEvent.ACTION_UP,-2000f to 2000f)
            assertTrue(view.viewport.offsetX<0)
            val max=(view.viewport.zoom-1)/2
            assertEquals(-max,view.viewport.offsetX,.001f); assertEquals(max,view.viewport.offsetY,.001f)
            assertEquals(Color.GREEN,source.getPixel(50,50))
            view.clear(); lease.release(); assertTrue(source.isRecycled)
        }
    }

    @Test fun fullscreenKeepsRendererAndResourcesAttachedSupportsBackRotationAndClear() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            lateinit var old: TryOnView
            lateinit var source: Bitmap
            lateinit var asset: Bitmap
            var disposed=0
            scenario.onActivity { activity ->
                val root=activity.window.decorView
                old=root.findViewWithTag("fitting.preview")
                source=bitmap(Color.GREEN); asset=bitmap(Color.RED)
                old.setAsset(item(),asset)
                val lease=frame(source) { disposed++ }; old.submit(lease,item()); lease.release()
                assertFalse(ViewModelProvider(activity)[TryOnModel::class.java].running)
                root.findViewWithTag<Button>("fitting.preview.fullscreen").performClick()
            }
            instrumentation.waitForIdleSync(); SystemClock.sleep(400)
            scenario.onActivity { activity ->
                val root=activity.window.decorView
                assertSame(old,root.findViewWithTag("fitting.preview"))
                assertTrue(old.isAttachedToWindow); assertFalse(asset.isRecycled); assertFalse(source.isRecycled)
                assertEquals(0,disposed)
                assertFalse(root.findViewWithTag<View>("fitting.address").isShown)
                assertFalse(root.findViewWithTag<View>("fitting.generate").isShown)
                assertTrue("Fullscreen must use most of the display",old.height > root.height*.65f)
                val scroll=root.findViewWithTag<ScrollView>("fitting.scroll")
                assertEquals("Fullscreen must not scroll the exit button away",0,scroll.scrollY)
                val exit=root.findViewWithTag<Button>("fitting.preview.fullscreen")
                val rect=Rect(); assertTrue(exit.getGlobalVisibleRect(rect)); assertTrue(rect.height()>=exit.height-1)
                root.findViewWithTag<Button>("fitting.preview.plus").performClick()
                assertEquals(1.25f,old.viewport.zoom,0f)
                val now=SystemClock.uptimeMillis()
                val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,50f,50f,0)
                val move=MotionEvent.obtain(now,now+20,MotionEvent.ACTION_MOVE,80f,90f,0)
                val up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,80f,90f,0)
                assertTrue(old.dispatchTouchEvent(down)); old.dispatchTouchEvent(move); old.dispatchTouchEvent(up)
                down.recycle(); move.recycle(); up.recycle()
                root.findViewWithTag<Switch>("fitting.head.protection").isChecked=false
            }
            scenario.recreate(); instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root=activity.window.decorView; val model=ViewModelProvider(activity)[TryOnModel::class.java]
                assertTrue(model.previewFullscreen); assertEquals(1.25f,model.previewViewport.zoom,0f)
                assertFalse(model.protectHead); assertFalse(model.running)
                assertTrue(source.isRecycled); assertTrue(asset.isRecycled); assertEquals(1,disposed)
                activity.onBackPressedDispatcher.onBackPressed()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root=activity.window.decorView
                assertTrue(root.findViewWithTag<View>("fitting.address").isShown)
                root.findViewWithTag<Button>("fitting.preview.reset").performClick()
                assertEquals(PreviewViewport(),ViewModelProvider(activity)[TryOnModel::class.java].previewViewport)
                assertFalse(ViewModelProvider(activity)[TryOnModel::class.java].running)
            }
        }
    }
}
