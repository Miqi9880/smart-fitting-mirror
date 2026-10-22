package com.smartfitting.mirror

import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Spinner
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.network.FittingPhase
import com.smartfitting.mirror.tryon.PreviewViewport
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in local MOCK catalog only. Never opens a camera or uploads a photo. */
@RunWith(AndroidJUnit4::class)
class PreviewIntegrationTest {
    @Test fun syntheticThreePiecePreviewCanExpandZoomResetAndEnd() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val host=InstrumentationRegistry.getArguments().getString("studio_test_host")
        assumeTrue(host!=null)
        require(host in listOf("http://10.0.2.2:8010","http://127.0.0.1:8010"))
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            fun model(activity: TryOnActivity)=ViewModelProvider(activity)[TryOnModel::class.java]
            fun await(check: (TryOnActivity)->Boolean) {
                val end=SystemClock.elapsedRealtime()+30_000; var ready=false
                while (!ready && SystemClock.elapsedRealtime()<end) {
                    scenario.onActivity { ready=check(it) }; if (!ready) SystemClock.sleep(100)
                }
                var detail=""
                if (!ready) scenario.onActivity {
                    detail="phase=${model(it).state.value.phase}; controller=${model(it).controller?.state?.value?.phase}; " +
                        "message=${model(it).state.value.message}; retry=${model(it).state.value.retry}; running=${model(it).running}; fullscreen=${model(it).previewFullscreen}"
                }
                assertTrue("Expected synthetic preview state: $detail",ready)
            }
            fun evidence(name: String) {
                if (InstrumentationRegistry.getArguments().getString("preview_evidence")!="true") return
                instrumentation.waitForIdleSync(); SystemClock.sleep(1200)
                // Acknowledge the emulator's observed first-use Android education overlay.
                val guide=instrumentation.uiAutomation.rootInActiveWindow
                if (guide?.findAccessibilityNodeInfosByText("Viewing full screen")?.isNotEmpty()==true) {
                    guide.findAccessibilityNodeInfosByText("Got it").filter { it.isClickable }.forEach {
                        it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    instrumentation.waitForIdleSync(); SystemClock.sleep(300)
                }
                val image=instrumentation.uiAutomation.takeScreenshot()
                val path=File(instrumentation.targetContext.getExternalFilesDir(null),"evidence/$name.png")
                path.parentFile!!.mkdirs()
                path.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
            }
            scenario.onActivity {
                val root=it.window.decorView
                root.findViewWithTag<EditText>("fitting.address").setText(host)
                root.findViewWithTag<Button>("fitting.connect").performClick()
                root.findViewWithTag<Button>("fitting.mock").performClick()
                root.findViewWithTag<Spinner>("synthetic.scenario").setSelection(1)
                root.findViewWithTag<Spinner>("synthetic.viewport").setSelection(1)
            }
            await { model(it).state.value.canSelect }
            for (id in listOf("studio_zip_hoodie_09","studio_pleated_wide_06","studio_black_beanie")) {
                scenario.onActivity { model(it).controller!!.select(id) }
                await { model(it).state.value.selectedId==id }
            }
            await { it.window.decorView.findViewWithTag<TextView>("fitting.asset").text.contains("已加载 3/3") }
            scenario.onActivity {
                val root=it.window.decorView
                val play=root.findViewWithTag<Button>("synthetic.play")
                if (play.text=="暂停") play.performClick()
                assertTrue(root.findViewWithTag<TextView>("fitting.preview.source").text.contains("已加载 3/3"))
                root.findViewWithTag<Button>("fitting.preview.fullscreen").performClick()
            }
            evidence("fullscreen-synthetic-whole")
            scenario.onActivity {
                val root=it.window.decorView
                repeat(3) { root.findViewWithTag<Button>("fitting.preview.plus").performClick() }
                assertTrue(model(it).previewViewport.zoom>1.9f)
            }
            evidence("fullscreen-synthetic-detail")
            // Local Uvicorn closes idle keep-alive sockets after 5s. Exercise a
            // real pause to inspect clothing, then require acknowledged cleanup.
            SystemClock.sleep(6_000)
            var selectedIds = emptyList<String>()
            scenario.onActivity {
                val root=it.window.decorView
                selectedIds=model(it).outfit.values.map { item -> item.garmentId }
                val viewport=model(it).previewViewport
                val input=root.findViewWithTag<Button>("fitting.preview.input")
                assertTrue(input.isShown)
                input.performClick()
                assertFalse(model(it).running)
                assertFalse(root.findViewWithTag<Button>("fitting.generate").isEnabled)
                assertEquals(selectedIds,model(it).outfit.values.map { item -> item.garmentId })
                assertEquals(viewport,model(it).previewViewport)
                input.performClick()
            }
            await { it.window.decorView.findViewWithTag<TextView>("fitting.asset").text.contains("已加载 3/3") }
            scenario.onActivity {
                assertTrue(model(it).running)
                assertFalse(model(it).rgb)
                assertEquals(selectedIds,model(it).outfit.values.map { item -> item.garmentId })
            }
            scenario.onActivity {
                val root=it.window.decorView
                root.findViewWithTag<Button>("fitting.preview.reset").performClick()
                assertEquals(PreviewViewport(),model(it).previewViewport)
                root.findViewWithTag<Button>("fitting.preview.fullscreen").performClick()
                val end=root.findViewWithTag<Button>("fitting.end")
                assertTrue("End must remain enabled",end.isEnabled)
                assertTrue("End click must be handled",end.performClick())
            }
            await { model(it).state.value.phase==FittingPhase.CLOSED }
            scenario.onActivity { assertFalse(model(it).previewFullscreen); assertFalse(model(it).running) }
        }
    }
}
