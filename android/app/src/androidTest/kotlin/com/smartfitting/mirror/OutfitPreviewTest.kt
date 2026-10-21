package com.smartfitting.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.tryon.TryOnView
import com.smartfitting.mirror.tryon.OutfitFit
import com.smartfitting.mirror.vision.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutfitPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun trousersCoverMoreOnBothSidesOfEachLegWithoutMovingTheirCentresOrLength() {
        instrumentation.runOnMainSync {
            val view=TryOnView(instrumentation.targetContext).apply { layout(0,0,480,640); mirrored=false }
            val controls=mapOf("right_hip" to Anchor(.3f,.12f),"left_hip" to Anchor(.7f,.12f),
                "right_knee" to Anchor(.3f,.55f),"left_knee" to Anchor(.7f,.55f),
                "right_ankle" to Anchor(.3f,.94f),"left_ankle" to Anchor(.7f,.94f))
            val pants=Garment("coverage","coverage",GarmentCategory.LOWER_BODY,emptyList(),"http://localhost/pants.png",
                PreviewImage("http://localhost/pants.png",100,140,null,controls),null)
            val pixels=Bitmap.createBitmap(100,140,Bitmap.Config.ARGB_8888)
            Canvas(pixels).apply {
                val paint=android.graphics.Paint().apply { color=Color.RED }
                drawRect(20f,0f,40f,140f,paint); drawRect(60f,0f,80f,140f,paint)
            }
            val original=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            val output=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888)
            val points=Keypoints(rightHip=Landmark(.35f,.4f,.95f),leftHip=Landmark(.65f,.4f,.95f),
                rightKnee=Landmark(.35f,.65f,.95f),leftKnee=Landmark(.65f,.65f,.95f),
                rightAnkle=Landmark(.35f,.92f,.95f),leftAnkle=Landmark(.65f,.92f,.95f))
            val lease=FrameLease(VisionFrame(sessionId="coverage",frameId=1,timestampMs=1,
                image=ImageMetadata("coverage",480,640),trackingState=points.state(),keypoints=points),BitmapPixels(original)) {}
            view.setOutfit(listOf(pants));view.setLayerAsset(pants,pixels);view.submit(lease,pants);lease.release()
            fun runs(y: Int): List<IntRange> {
                val result=mutableListOf<IntRange>(); var start: Int?=null
                for (x in 0..479) {
                    // Adjacent clipped triangles have antialiased boundaries;
                    // distinguish actual fabric coverage from exact solid red.
                    val color=output.getPixel(x,y)
                    val red=Color.red(color)>=180 && Color.green(color)==0 && Color.blue(color)==0
                    if (red && start==null) start=x
                    if (!red && start!=null) { result+=start until x;start=null }
                }
                if (start!=null) result+=start..479
                return result
            }
            view.outfitFits=mapOf(GarmentCategory.LOWER_BODY to OutfitFit())
            view.draw(Canvas(output)); val before=listOf(350,500).map { runs(it) }
            val oldY=(0..639).filter { runs(it).isNotEmpty() }
            view.outfitFits=mapOf(GarmentCategory.LOWER_BODY to OutfitFit(width=1.12f));view.draw(Canvas(output))
            for ((i,y) in listOf(350,500).withIndex()) {
                val after=runs(y); assertEquals("raw y=$y runs=${before[i]}",2,before[i].size);assertEquals("eased y=$y runs=$after",2,after.size)
                for ((a,b) in before[i].zip(after)) {
                    assertTrue("Both edges must expand",b.first<a.first && b.last>a.last)
                    assertEquals("Do not spread the leg axes",(a.first+a.last)/2f,(b.first+b.last)/2f,1f)
                }
            }
            assertEquals(oldY,(0..639).filter { runs(it).isNotEmpty() })
            view.outfitFits=emptyMap();view.draw(Canvas(output))
            val extendedY=(0..639).filter { runs(it).isNotEmpty() }
            assertTrue("The final default also extends the trouser legs",extendedY.last()>oldY.last()+20)
            assertEquals(Color.BLACK,original.getPixel(168,500))
            view.clear();assertTrue(pixels.isRecycled);original.recycle();output.recycle()
        }
    }
    @Test fun independentLayersHideMissingRegionsMirrorTogetherAndRecycleOnRemoval() {
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            val view=TryOnView(context).apply { layout(0,0,480,640); mirrored=false }
            fun item(id: String, category: GarmentCategory, controls: Map<String,Anchor> = emptyMap()) = Garment(id,id,category,emptyList(),"http://localhost/$id.png",
                PreviewImage("http://localhost/$id.png",100,140,if (category==GarmentCategory.UPPER_BODY) GarmentAnchors(Anchor(.75f,.15f),Anchor(.25f,.15f),Anchor(.7f,.85f),Anchor(.3f,.85f)) else null,controls),null)
            val shirt=item("shirt",GarmentCategory.UPPER_BODY)
            val pants=item("pants",GarmentCategory.LOWER_BODY,mapOf("right_hip" to Anchor(.33f,.12f),"left_hip" to Anchor(.68f,.12f),
                "right_knee" to Anchor(.37f,.55f),"left_knee" to Anchor(.64f,.55f),"right_ankle" to Anchor(.36f,.94f),"left_ankle" to Anchor(.65f,.94f)))
            val hat=item("hat",GarmentCategory.HEADWEAR,mapOf("right_ear" to Anchor(.12f,.92f),"left_ear" to Anchor(.88f,.92f)))
            fun asset(color: Int)=Bitmap.createBitmap(100,140,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            val shirtPixels=asset(Color.BLUE); val pantsPixels=asset(Color.RED); val hatPixels=asset(Color.GREEN)
            view.setOutfit(listOf(shirt,pants,hat))
            view.setLayerAsset(shirt,shirtPixels);view.setLayerAsset(pants,pantsPixels);view.setLayerAsset(hat,hatPixels)
            val original=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            var sequence=0L
            fun submit(p: Keypoints) {
                val f=FrameLease(VisionFrame(sessionId="outfit",frameId=++sequence,timestampMs=sequence,
                    image=ImageMetadata("outfit",480,640),trackingState=p.state(),keypoints=p),BitmapPixels(original)) {}
                view.submit(f,pants);f.release()
            }
            val output=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888)
            fun colors(): Set<Int> { view.draw(Canvas(output));val pixels=IntArray(480*640);output.getPixels(pixels,0,480,0,0,480,640);return pixels.toSet() }
            val pose=SyntheticScene.sample(SyntheticScenario.STILL,0)
            submit(pose);assertTrue(colors().containsAll(listOf(Color.BLUE,Color.RED,Color.GREEN)))
            assertEquals(Color.BLACK,original.getPixel(240,250))
            submit(pose.copy(leftKnee=null));assertFalse(Color.RED in colors());assertTrue(Color.BLUE in colors());assertTrue(Color.GREEN in colors())
            submit(Keypoints(leftEar=pose.leftEar,rightEar=pose.rightEar));assertTrue(Color.GREEN in colors());assertFalse(Color.RED in colors());assertFalse(Color.BLUE in colors())
            submit(Keypoints());assertEquals(setOf(Color.BLACK),colors())
            submit(SyntheticScene.sample(SyntheticScenario.TRANSLATE,20));view.mirrored=false;view.draw(Canvas(output))
            val before=IntArray(480*640);output.getPixels(before,0,480,0,0,480,640)
            view.mirrored=true;view.draw(Canvas(output));val after=IntArray(before.size);output.getPixels(after,0,480,0,0,480,640)
            // Compare representative interior pixels; triangle clipping may rasterize boundary pixels differently.
            var checked=0
            for (y in 2..637) for (x in 2..477) {
                val color=before[y*480+x]
                if (color!=Color.BLACK && before[y*480+x-1]==color && before[y*480+x+1]==color &&
                    before[(y-1)*480+x]==color && before[(y+1)*480+x]==color) {
                    assertEquals(color,after[y*480+479-x]);checked++
                }
            }
            assertTrue(checked>1000)
            view.setOutfit(listOf(shirt,hat));assertTrue(pantsPixels.isRecycled);assertFalse(shirtPixels.isRecycled)
            view.clear();assertTrue(shirtPixels.isRecycled);assertTrue(hatPixels.isRecycled)
            assertFalse(original.isRecycled);original.recycle();output.recycle()
        }
    }

    @Test fun nativeOutfitKeepsOnePerRegionAcrossRotationAndCanClear() {
        val host=InstrumentationRegistry.getArguments().getString("studio_test_host")
        assumeTrue("Requires studio service and local pack",host!=null)
        require(host in listOf("http://10.0.2.2:8010","http://127.0.0.1:8010"))
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            fun model(a: TryOnActivity)=ViewModelProvider(a)[TryOnModel::class.java]
            fun await(check: (TryOnActivity)->Boolean) {
                val deadline=SystemClock.elapsedRealtime()+30_000;var ready=false
                while (!ready && SystemClock.elapsedRealtime()<deadline) { scenario.onActivity { ready=check(it) };if (!ready) SystemClock.sleep(100) }
                var detail=""
                if (!ready) scenario.onActivity { detail="phase=${model(it).state.value.phase}; outfit=${model(it).outfit.keys}; asset=${it.window.decorView.findViewWithTag<TextView>("fitting.asset").text}" }
                assertTrue("Expected studio outfit state: $detail",ready)
            }
            scenario.onActivity {
                it.window.decorView.findViewWithTag<EditText>("fitting.address").setText(host)
                it.window.decorView.findViewWithTag<Button>("fitting.connect").performClick()
            }
            await { model(it).state.value.canSelect }
            scenario.onActivity {
                assertFalse("Connecting alone must not start the camera",model(it).running)
                it.window.decorView.findViewWithTag<Button>("fitting.mock").performClick()
            }
            for (id in listOf("studio_black_pants","studio_black_beanie","studio_blue_tee")) {
                scenario.onActivity { model(it).controller!!.select(id) }
                await { model(it).outfit.values.any { item -> item.garmentId==id } }
            }
            await { it.window.decorView.findViewWithTag<TextView>("fitting.asset").text.contains("3/3") }
            scenario.onActivity { assertEquals(3,model(it).outfit.size);assertEquals("studio_blue_tee",model(it).outfit[GarmentCategory.UPPER_BODY]!!.garmentId) }
            for (id in listOf("studio_cotton_t_dress_09","studio_cotton_t_dress_30","studio_cotton_t_dress_69")) {
                scenario.onActivity { model(it).controller!!.select(id) }
                await { model(it).outfit[GarmentCategory.DRESS]?.garmentId==id &&
                    it.window.decorView.findViewWithTag<TextView>("fitting.asset").text.contains("2/2") }
                scenario.onActivity {
                    assertEquals(setOf(GarmentCategory.DRESS,GarmentCategory.HEADWEAR),model(it).outfit.keys)
                    assertFalse(model(it).state.value.canCapture)
                }
            }
            scenario.recreate()
            await { model(it).outfit[GarmentCategory.DRESS]?.garmentId=="studio_cotton_t_dress_69" &&
                it.window.decorView.findViewWithTag<TextView>("fitting.asset").text.contains("2/2") }
            for (id in listOf("studio_mini_skirt_11","studio_blue_tee","studio_chino_shorts_62")) {
                scenario.onActivity { model(it).controller!!.select(id) }
                await { model(it).outfit.values.any { item -> item.garmentId==id } }
            }
            await { it.window.decorView.findViewWithTag<TextView>("fitting.asset").text.contains("3/3") }
            scenario.onActivity { assertEquals(setOf(GarmentCategory.UPPER_BODY,GarmentCategory.SHORTS,GarmentCategory.HEADWEAR),model(it).outfit.keys) }
            scenario.recreate()
            await { it.window.decorView.findViewWithTag<TextView>("fitting.asset").text.contains("3/3") }
            scenario.onActivity { it.window.decorView.findViewWithTag<Button>("fitting.outfit.clear").performClick();assertTrue(model(it).outfit.isEmpty()) }
            scenario.recreate()
            await { model(it).outfit.isEmpty() }
            scenario.onActivity { it.window.decorView.findViewWithTag<Button>("fitting.end").performClick() }
        }
    }
}
