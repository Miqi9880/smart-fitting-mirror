package com.smartfitting.mirror

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.filters.SdkSuppress
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.network.*
import com.smartfitting.mirror.tryon.TryOnView
import com.smartfitting.mirror.tryon.TorsoWarp
import com.smartfitting.mirror.vision.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real HTTP and native dialog tests; all people/pixels in these tests are synthetic. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ReferenceCatalogTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun host(): String {
        val host = InstrumentationRegistry.getArguments().getString("fitting_test_host")
        assumeTrue("Start the documented success service",host != null)
        require(host in listOf("http://10.0.2.2","http://127.0.0.1")); return host!!
    }
    private fun descendants(view: View): List<View> = listOf(view)+if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun windows() = WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }
    private fun await(scenario: ActivityScenario<TryOnActivity>, check: (TryOnActivity)->Boolean) {
        val deadline = SystemClock.elapsedRealtime()+30_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime()<deadline) {
            scenario.onActivity { ready = check(it) }; if (!ready) SystemClock.sleep(100)
        }
        assertTrue("Expected catalog state",ready)
    }
    private fun model(activity: TryOnActivity) = ViewModelProvider(activity)[TryOnModel::class.java]

    @Test fun nativeBrowserSearchDetailsAndCreateEditDeleteUseTheSameBackend() {
        val host = host()
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.window.decorView.findViewWithTag<Button>("fitting.mock").performClick()
                it.window.decorView.findViewWithTag<EditText>("fitting.address").setText("$host:8000")
                it.window.decorView.findViewWithTag<Button>("fitting.connect").performClick()
            }
            await(scenario) { model(it).state.value.canSelect }
            scenario.onActivity {
                it.window.decorView.findViewWithTag<Button>("fitting.catalog").performClick()
                windows().filterIsInstance<EditText>().single { it.tag == "catalog.search" }.setText("白恤")
            }
            await(scenario) { windows().any { it.contentDescription == "查看实拍白色 T 恤（辅助去背景）详情" } }
            scenario.onActivity {
                assertFalse(windows().any { it.contentDescription == "查看蓝色休闲上衣详情" })
                windows().single { it.contentDescription == "查看实拍白色 T 恤（辅助去背景）详情" }.performClick()
                assertTrue(windows().filterIsInstance<TextView>().any { it.text.contains("非逐像素无损抠图") })
                windows().filterIsInstance<Button>().single { it.text == "返回" }.performClick()
                windows().filterIsInstance<EditText>().single { it.tag == "catalog.search" }.setText("")
                windows().single { it.tag == "catalog.add" }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                windows().filterIsInstance<EditText>().single { it.contentDescription == "服装名称（必填）" }.setText("自动联调上衣")
                windows().filterIsInstance<EditText>().single { it.contentDescription == "材质" }.setText("cotton")
                windows().filterIsInstance<Button>().single { it.text == "保存" }.performClick()
            }
            await(scenario) { model(it).state.value.garments.any { garment -> garment.name == "自动联调上衣" } && windows().any { it.contentDescription == "查看自动联调上衣详情" } }
            scenario.onActivity {
                windows().single { it.contentDescription == "查看自动联调上衣详情" }.performClick()
                windows().filterIsInstance<Button>().single { it.text == "编辑资料" }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                windows().filterIsInstance<EditText>().single { it.contentDescription == "服装名称（必填）" }.setText("编辑后的上衣")
                windows().filterIsInstance<Button>().single { it.text == "保存" }.performClick()
            }
            await(scenario) { model(it).state.value.garments.any { garment -> garment.name == "编辑后的上衣" } && windows().any { it.contentDescription == "查看编辑后的上衣详情" } }
            scenario.onActivity {
                windows().single { it.contentDescription == "查看编辑后的上衣详情" }.performClick()
                windows().filterIsInstance<Button>().single { it.text == "从本次衣柜移除" }.performClick()
                windows().filterIsInstance<Button>().single { it.text == "移除" }.performClick()
            }
            await(scenario) { model(it).state.value.canSelect && model(it).state.value.garments.none { garment -> garment.name == "编辑后的上衣" } }
            scenario.onActivity { model(it).controller!!.end() }
            await(scenario) { model(it).state.value.phase == FittingPhase.CLOSED }
        }
    }

    @Test fun realGarmentKeepsShapeAcrossValidV07ViewportsAndOriginalPixelsStayClean() = runBlocking {
        HttpFittingApi("${host()}:8000").use { api ->
            val session = api.createSession()
            try {
                val garment = api.garments(session.sessionToken).items.single { it.garmentId == "real_white_tee" }
                val bytes = api.download(session.sessionToken,garment.previewImage.url)
                val ratios = mutableListOf<Float>()
                try { instrumentation.runOnMainSync {
                    for (viewport in SyntheticViewport.entries) {
                        val w = viewport.width; val h = viewport.height
                        val view = TryOnView(instrumentation.targetContext).apply { mirrored=false; layout(0,0,w,h) }
                        val raw = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
                        val points = SyntheticScene.sample(SyntheticScenario.STILL,0,viewport)
                        val frame = FrameLease(VisionFrame(sessionId="v07",frameId=1,timestampMs=1,
                            image=ImageMetadata("v07-$viewport",w,h),trackingState=points.state(),keypoints=points),BitmapPixels(raw)) { it.bitmap.recycle() }
                        view.setAsset(garment,BitmapFactory.decodeByteArray(bytes,0,bytes.size)); view.submit(frame,garment)
                        val correspondences = TorsoWarp.points(frame.metadata,garment)!!
                        val transform = Matrix()
                        assertTrue(transform.setPolyToPoly(correspondences.source,0,correspondences.destination,0,4))
                        val mapped = correspondences.source.copyOf(); transform.mapPoints(mapped)
                        for (i in mapped.indices) assertEquals(correspondences.destination[i],mapped[i],.001f)
                        val output = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); view.draw(Canvas(output))
                        var left=w; var right=0; var top=h; var bottom=0
                        val pixels=IntArray(w*h); output.getPixels(pixels,0,w,0,0,w,h)
                        for (y in 0 until h) for (x in 0 until w) if (Color.red(pixels[y*w+x])>40) {
                            left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)
                        }
                        assertTrue(right>left && bottom>top)
                        ratios += (right-left).toFloat()/(bottom-top)
                        assertEquals(Color.BLACK,raw.getPixel(w/2,h/2))
                        view.mirrored=true; view.draw(Canvas(output)); output.getPixels(pixels,0,w,0,0,w,h)
                        var mirrorLeft=w; var mirrorRight=0
                        for (y in 0 until h) for (x in 0 until w) if (Color.red(pixels[y*w+x])>40) {
                            mirrorLeft=minOf(mirrorLeft,x);mirrorRight=maxOf(mirrorRight,x)
                        }
                        assertEquals((w-1-right).toFloat(),mirrorLeft.toFloat(),2f)
                        assertEquals((w-1-left).toFloat(),mirrorRight.toFloat(),2f)
                        view.mirrored=false
                        view.showLandmarks=true; view.draw(Canvas(output))
                        val path=File(instrumentation.targetContext.getExternalFilesDir(null),"evidence/reference-v07-${viewport.name.lowercase()}.png")
                        path.parentFile!!.mkdirs(); path.outputStream().use { output.compress(Bitmap.CompressFormat.PNG,100,it) }
                        val record=File(path.parentFile,"reference-v07-${viewport.name.lowercase()}.json")
                        record.writeText("""{"width":$w,"height":$h,"garment_id":"real_white_tee","bbox":[$left,$top,$right,$bottom],"ratio":${ratios.last()},"anchor_error_max_px":${mapped.indices.maxOf { kotlin.math.abs(mapped[it]-correspondences.destination[it]) }},"mirror_bbox_checked":true}""")
                        view.clear();frame.release();output.recycle()
                    }
                } } finally { bytes.fill(0) }
                assertEquals(ratios[0],ratios[1],.02f)
            } finally { api.endSession(session.sessionToken) }
        }
    }
}
