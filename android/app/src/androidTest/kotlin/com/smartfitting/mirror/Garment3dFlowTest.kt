package com.smartfitting.mirror

import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.garment3d.Garment3dActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in packaged CG only; no backend, camera, uploaded photo or paid provider. */
@RunWith(AndroidJUnit4::class)
class Garment3dFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun js(scenario: ActivityScenario<Garment3dActivity>,script: String): String {
        val result = AtomicReference<String>(); val latch = CountDownLatch(1)
        scenario.onActivity { activity -> requireNotNull(activity.webView).evaluateJavascript(script) { result.set(it); latch.countDown() } }
        assertTrue("WebView evaluation timed out",latch.await(5,TimeUnit.SECONDS))
        return result.get()
    }
    private fun await(scenario: ActivityScenario<Garment3dActivity>,condition: String) {
        val end = SystemClock.elapsedRealtime()+40_000
        while (SystemClock.elapsedRealtime()<end) { if (js(scenario,condition)=="true") return; SystemClock.sleep(100) }
        fail("3D state: "+js(scenario,"document.querySelector('#load-status')?.textContent"))
    }
    private fun evidence(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot()
        try {
            var blue = 0
            for (y in 0 until image.height step 4) for (x in 0 until image.width step 4) {
                val c = image.getPixel(x,y);val r = android.graphics.Color.red(c);val g = android.graphics.Color.green(c);val b = android.graphics.Color.blue(c)
                if (b>r+8 && g>r+3 && r<220) blue++
            }
            assertTrue("Expected a rendered blue garment, not just a ready label: $blue sampled pixels",blue>1000)
            if (InstrumentationRegistry.getArguments().getString("garment3d_evidence")=="true") {
                val file = File(instrumentation.targetContext.getExternalFilesDir(null),"evidence/garment3d-$name.png")
                file.parentFile!!.mkdirs();file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            }
        } finally { image.recycle() }
    }

    @Test fun offlinePackRendersActionsAndPausesWithoutLosingTheScene() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("garment3d_sample")=="true")
        ActivityScenario.launch(Garment3dActivity::class.java).use { scenario ->
            await(scenario,"document.querySelector('#viewport')?.dataset.sceneState==='ready'")
            assertEquals("true",js(scenario,"location.origin==='https://appassets.androidplatform.net'"))
            js(scenario,"document.querySelector('button[data-view=top]').click();document.querySelector('button[data-pose=raise]').click();true")
            await(scenario,"document.querySelector('#viewport').dataset.activePose==='raise' && +document.querySelector('#viewport').dataset.frame>=60")
            evidence("raise-top")
            js(scenario,"document.querySelector('button[data-pose=reach]').click();true")
            await(scenario,"document.querySelector('#viewport').dataset.activePose==='reach' && +document.querySelector('#viewport').dataset.frame>=60")
            js(scenario,"document.querySelector('button[data-view=detail]').click();document.querySelector('#animate').click();true")
            await(scenario,"document.querySelector('#viewport').dataset.playback==='running'")
            scenario.moveToState(Lifecycle.State.CREATED)
            await(scenario,"document.querySelector('#viewport').dataset.playback==='paused'")
            val frame = js(scenario,"document.querySelector('#viewport').dataset.frame")
            SystemClock.sleep(650)
            assertEquals("Background must not keep advancing the cached animation",frame,js(scenario,"document.querySelector('#viewport').dataset.frame"))
            scenario.moveToState(Lifecycle.State.RESUMED)
            await(scenario,"document.querySelector('#viewport').dataset.playback==='running'")
            assertEquals("true",js(scenario,"document.querySelector('button[data-pose=reach]').getAttribute('aria-pressed')==='true'"))
            js(scenario,"document.querySelector('#animate').checked=false;document.querySelector('#animate').dispatchEvent(new Event('change'));true")
            await(scenario,"document.querySelector('#viewport').dataset.activePose==='reach' && +document.querySelector('#viewport').dataset.frame>=60")
            evidence("reach-detail")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await(scenario,"innerHeight>innerWidth && document.querySelector('#viewport').dataset.sceneState==='ready'")
            assertEquals("true",js(scenario,"document.documentElement.scrollWidth<=document.documentElement.clientWidth"))
            evidence("portrait")
            if (android.os.Build.VERSION.SDK_INT>=29) {
                scenario.onActivity { activity -> assertTrue(requireNotNull(activity.webView?.webViewRenderProcess).terminate()) }
                val end = SystemClock.elapsedRealtime()+10_000
                var offered = false
                while (!offered && SystemClock.elapsedRealtime()<end) {
                    scenario.onActivity { offered = it.window.decorView.findViewWithTag<Button>("garment3d.retry")!=null }
                    if (!offered) SystemClock.sleep(100)
                }
                assertTrue("Renderer exit must offer a local retry rather than crash the app",offered)
                scenario.onActivity { it.window.decorView.findViewWithTag<Button>("garment3d.retry").performClick() }
                await(scenario,"document.querySelector('#viewport')?.dataset.sceneState==='ready'")
            }
        }
    }

    @Test fun sourceOnlyApkShowsMissingPackAndStillAllowsReturn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("garment3d_missing")=="true")
        ActivityScenario.launch(Garment3dActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.window.decorView.findViewWithTag<View>("garment3d.missing"))
                assertNull(activity.webView)
                activity.window.decorView.findViewWithTag<Button>("garment3d.back").performClick()
            }
        }
    }

    @Test fun homeEntryReturnsWithSensingStillOff() {
        val monitor = instrumentation.addMonitor(Garment3dActivity::class.java.name,null,false)
        try {
            ActivityScenario.launch(StandbyActivity::class.java).use { scenario ->
                scenario.onActivity { home ->
                    assertFalse(home.sensingActive)
                    home.window.decorView.findViewWithTag<Button>("home.garment3d").performClick()
                }
                val child = monitor.waitForActivityWithTimeout(10_000)
                assertNotNull("Home must open the standalone, offline sample",child)
                instrumentation.runOnMainSync { child!!.window.decorView.findViewWithTag<Button>("garment3d.back").performClick() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { home -> assertFalse(home.sensingActive);assertNotNull(home.window.decorView.findViewWithTag<View>("home.start")) }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
