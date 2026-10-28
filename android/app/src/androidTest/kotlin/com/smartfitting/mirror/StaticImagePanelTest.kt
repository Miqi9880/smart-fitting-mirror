package com.smartfitting.mirror

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import android.view.View
import android.content.res.Configuration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.network.FittingPhase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StaticImagePanelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun generated(): ByteArray {
        val bitmap = Bitmap.createBitmap(100, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        return try { ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    private fun await(scenario: ActivityScenario<TryOnActivity>, check: (TryOnActivity) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < end) {
            scenario.onActivity { ready = check(it) }
            if (!ready) SystemClock.sleep(50)
        }
        var detail = ""
        if (!ready) scenario.onActivity {
            detail = "phase=${ViewModelProvider(it)[TryOnModel::class.java].state.value.phase}; " +
                "input=${it.window.decorView.findViewWithTag<TextView>("fitting.import.status").text}"
        }
        assertTrue("Expected import/session state within timeout: $detail", ready)
    }

    @Test fun selectionIsLocalExplicitSubmitIsOnceAndClearWipesPendingInput() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            lateinit var panel: StaticImagePanel
            var submitted = 0
            scenario.onActivity { activity ->
                panel = StaticImagePanel(activity, activity.lifecycleScope, {}, { photo ->
                    submitted++; assertEquals(100, photo.width); photo.close(); true
                }, {})
                panel.render(true); panel.load { ByteArrayInputStream(generated()) }
            }
            await(scenario) { !panel.busy }
            scenario.onActivity {
                assertEquals(0, submitted)
                assertTrue(panel.findViewWithTag<TextView>("fitting.import.status").text.contains("尚未上传"))
                panel.findViewWithTag<Button>("fitting.import.generate").performClick()
                panel.findViewWithTag<Button>("fitting.import.generate").performClick()
                assertEquals(1, submitted)
                assertFalse(panel.findViewWithTag<Button>("fitting.import.generate").isEnabled)
                panel.load { ByteArrayInputStream("bad".toByteArray()) }
            }
            await(scenario) { !panel.busy }
            scenario.onActivity {
                assertTrue(panel.findViewWithTag<TextView>("fitting.import.status").text.contains("导入失败"))
                assertTrue(panel.findViewWithTag<Button>("fitting.import").isEnabled)
                panel.clear()
            }
        }
    }

    @Test fun cancelledSlowReadClosesStreamAndCannotResurrectPhoto() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            lateinit var panel: StaticImagePanel
            val entered = CountDownLatch(1); val closed = CountDownLatch(1)
            val slow = object : InputStream() {
                override fun read(): Int { entered.countDown(); closed.await(5, TimeUnit.SECONDS); return -1 }
                override fun close() { closed.countDown() }
            }
            scenario.onActivity { activity ->
                panel = StaticImagePanel(activity, activity.lifecycleScope, {}, { fail("Unexpected upload"); false }, {})
                panel.render(true); panel.load { slow }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            scenario.onActivity { panel.clear(); assertFalse(panel.busy) }
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            SystemClock.sleep(250)
            scenario.onActivity {
                assertFalse(panel.findViewWithTag<Button>("fitting.import.generate").isEnabled)
                assertTrue(panel.findViewWithTag<TextView>("fitting.import.status").text.contains("尚未选择"))
                panel.load { ByteArrayInputStream(generated()) }
            }
            await(scenario) { !panel.busy }
            scenario.onActivity { assertTrue(panel.findViewWithTag<Button>("fitting.import.generate").isEnabled); panel.clear() }
        }
    }

    @Test fun largeFontPortraitAndLandscapeControlsRemainReadableAndAccessible() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val config = Configuration(activity.resources.configuration).apply { fontScale = 1.5f }
                val context = activity.createConfigurationContext(config)
                val panel = StaticImagePanel(context, activity.lifecycleScope, {}, { false }, {})
                panel.render(true)
                for (widthDp in listOf(320, 700)) {
                    val width = (widthDp * context.resources.displayMetrics.density).toInt()
                    panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    panel.layout(0, 0, width, panel.measuredHeight)
                    for (tag in listOf("fitting.import", "fitting.import.generate", "fitting.import.clear")) {
                        val button = panel.findViewWithTag<Button>(tag)
                        assertTrue(button.text.isNotBlank()); assertTrue(button.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO)
                        assertTrue("Touch target $tag", button.height >= 48 * context.resources.displayMetrics.density)
                        assertTrue("Text must not clip $tag", button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                    }
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                        panel.findViewWithTag<TextView>("fitting.import.status").accessibilityLiveRegion)
                    assertTrue(panel.findViewWithTag<View>("fitting.import.preview").contentDescription.isNotBlank())
                }
                panel.clear()
            }
        }
    }

    /** Opt-in local MOCK only, never a cloud provider or camera. */
    @Test fun pickerCancelRotationBackgroundEndAndExplicitMockGenerationWithoutCamera() {
        val host = InstrumentationRegistry.getArguments().getString("studio_test_host")
        assumeTrue(host != null)
        require(host in listOf("http://10.0.2.2:8010", "http://127.0.0.1:8010"))
        val source = File.createTempFile("generated-import-", ".png", instrumentation.targetContext.cacheDir)
        source.writeBytes(generated())
        try {
            ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
                fun panel(activity: TryOnActivity) = activity.window.decorView.findViewWithTag<StaticImagePanel>("fitting.import.panel")
                fun model(activity: TryOnActivity) = ViewModelProvider(activity)[TryOnModel::class.java]
                scenario.onActivity { model(it).connect(host!!) }
                await(scenario) { model(it).state.value.canCapture }
                // Intercept ACTION_OPEN_DOCUMENT with a generated fixture, including cancellation.
                fun choose(code: Int) {
                    val result = Intent().apply { if (code == Activity.RESULT_OK) data = Uri.fromFile(source) }
                    val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
                    }
                    val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(code, result), true)
                    try {
                        scenario.onActivity { panel(it).findViewWithTag<Button>("fitting.import").performClick() }
                        instrumentation.waitForIdleSync()
                        assertEquals("System picker intent must be intercepted", 1, monitor.hits)
                    } finally { instrumentation.removeMonitor(monitor) }
                }
                choose(Activity.RESULT_CANCELED)
                scenario.onActivity { assertFalse(panel(it).findViewWithTag<Button>("fitting.import.generate").isEnabled) }
                choose(Activity.RESULT_OK)
                await(scenario) { panel(it).findViewWithTag<Button>("fitting.import.generate").isEnabled }
                scenario.onActivity {
                    assertEquals(FittingPhase.READY, model(it).controller!!.state.value.phase)
                    assertNull(model(it).state.value.job)
                    assertFalse(model(it).running)
                    assertFalse(it.window.decorView.findViewWithTag<Button>("fitting.generate").isEnabled)
                }
                scenario.recreate(); instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertFalse(panel(it).findViewWithTag<Button>("fitting.import.generate").isEnabled)
                    panel(it).load { source.inputStream() }
                }
                await(scenario) { !panel(it).busy }
                scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity {
                    assertFalse(panel(it).findViewWithTag<Button>("fitting.import.generate").isEnabled)
                    panel(it).load { source.inputStream() }
                }
                await(scenario) { panel(it).findViewWithTag<Button>("fitting.import.generate").isEnabled }
                scenario.onActivity {
                    panel(it).findViewWithTag<Button>("fitting.import.generate").performClick()
                    panel(it).findViewWithTag<Button>("fitting.import.generate").performClick()
                }
                await(scenario) { model(it).state.value.phase == FittingPhase.SUCCEEDED }
                scenario.onActivity {
                    assertEquals(true, model(it).state.value.job?.isMock)
                    assertNotNull(model(it).state.value.original); assertNotNull(model(it).state.value.result)
                    panel(it).load { source.inputStream() }
                }
                await(scenario) { !panel(it).busy }
                scenario.onActivity { it.window.decorView.findViewWithTag<Button>("fitting.end").performClick() }
                await(scenario) { model(it).state.value.phase == FittingPhase.CLOSED }
                scenario.onActivity {
                    assertFalse(panel(it).findViewWithTag<Button>("fitting.import.generate").isEnabled)
                    assertNull(model(it).state.value.original); assertNull(model(it).state.value.result)
                    assertTrue(source.exists())
                }
            }
        } finally { source.delete() }
    }
}
