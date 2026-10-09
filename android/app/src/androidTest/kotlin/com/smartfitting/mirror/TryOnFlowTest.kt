package com.smartfitting.mirror

import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.network.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real HTTP integration: four local CLI servers, never a paid AI endpoint. */
@RunWith(AndroidJUnit4::class)
class TryOnFlowTest {
    private lateinit var host: String
    @Before fun requireLocalServers() {
        val configured = InstrumentationRegistry.getArguments().getString("fitting_test_host")
        assumeTrue("Start local backend modes and pass fitting_test_host", configured != null)
        host = configured!!
        require(host in listOf("http://10.0.2.2", "http://127.0.0.1"))
    }
    private fun TryOnActivity.model() = ViewModelProvider(this)[TryOnModel::class.java]
    private fun TryOnActivity.button(tag: String) = window.decorView.findViewWithTag<Button>("fitting.$tag")
    private fun waitFor(scenario: ActivityScenario<TryOnActivity>, predicate: (TryOnActivity) -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 30_000
        var okay = false
        while (!okay && SystemClock.elapsedRealtime() < until) {
            scenario.onActivity { okay = predicate(it) }
            if (!okay) SystemClock.sleep(100)
        }
        assertTrue("Expected fitting state before timeout", okay)
    }
    private fun connect(scenario: ActivityScenario<TryOnActivity>, port: Int) {
        scenario.onActivity {
            it.window.decorView.findViewWithTag<EditText>("fitting.address").setText("$host:$port")
            it.button("connect").performClick()
        }
        waitFor(scenario) { it.button("generate").isEnabled && it.window.decorView.findViewWithTag<TextView>("fitting.asset").text.contains("四点形变") }
    }
    private fun end(scenario: ActivityScenario<TryOnActivity>) {
        scenario.onActivity { it.button("end").performClick() }
        waitFor(scenario) { it.model().state.value.phase == FittingPhase.CLOSED }
        scenario.onActivity {
            assertNull(it.window.decorView.findViewWithTag<ImageView>("fitting.before").drawable)
            assertNull(it.window.decorView.findViewWithTag<ImageView>("fitting.after").drawable)
            assertFalse(it.button("generate").isEnabled)
            assertTrue(it.button("connect").isEnabled)
        }
    }
    private fun evidence(scenario: ActivityScenario<TryOnActivity>, name: String, tag: String? = null) {
        if (InstrumentationRegistry.getArguments().getString("fitting_evidence") != "true") return
        scenario.onActivity {
            val play = it.window.decorView.findViewWithTag<Button>("synthetic.play")
            if (play.text == "暂停" && play.isEnabled) play.performClick()
            if (tag == null) {
                val content = it.findViewById<ViewGroup>(android.R.id.content)
                (content.getChildAt(0) as ScrollView).scrollTo(0, 0)
            } else {
                val target = it.window.decorView.findViewWithTag<View>(tag)
                target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
            }
        }
        SystemClock.sleep(600)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "evidence/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        SystemClock.sleep(1200)
    }

    @Test fun successPreviewOnlySelectionComparisonAndCleanup() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            connect(scenario, 8000)
            evidence(scenario, "preview")
            scenario.onActivity {
                it.window.decorView.findViewWithTag<Spinner>("fitting.garments").setSelection(2)
            }
            waitFor(scenario) { it.model().state.value.selected?.generationReferenceUrl == null }
            scenario.onActivity {
                assertFalse(it.button("generate").isEnabled)
                it.window.decorView.findViewWithTag<Spinner>("fitting.garments").setSelection(0)
            }
            waitFor(scenario) { it.button("generate").isEnabled }
            scenario.onActivity { it.button("generate").performClick() }
            waitFor(scenario) { it.model().state.value.phase == FittingPhase.SUCCEEDED && it.window.decorView.findViewWithTag<ImageView>("fitting.after").drawable != null }
            lateinit var original: ByteArray
            lateinit var result: ByteArray
            scenario.onActivity {
                val state = it.model().state.value
                original = state.original!!; result = state.result!!
                assertTrue(state.job!!.isMock)
                assertFalse(original.contentEquals(result))
                assertTrue(it.window.decorView.findViewWithTag<TextView>("fitting.result.kind").text.contains("MOCK"))
                assertNotNull(it.window.decorView.findViewWithTag<ImageView>("fitting.before").drawable)
            }
            evidence(scenario, "comparison", "fitting.after")
            end(scenario)
            evidence(scenario, "ended")
            assertTrue(original.all { it == 0.toByte() } && result.all { it == 0.toByte() })
            scenario.recreate()
            scenario.onActivity { assertEquals(FittingPhase.CLOSED, it.model().state.value.phase); assertFalse(it.button("generate").isEnabled) }
        }
    }

    @Test fun failureIsVisibleAndAllowsAnotherCapture() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            connect(scenario, 8001)
            scenario.onActivity { it.button("generate").performClick() }
            waitFor(scenario) { it.model().state.value.phase == FittingPhase.FAILED }
            scenario.onActivity {
                assertEquals("MOCK_GENERATION_FAILED", it.model().state.value.job!!.error!!.code)
                assertTrue(it.button("generate").isEnabled)
                assertNull(it.window.decorView.findViewWithTag<ImageView>("fitting.after").drawable)
            }
            end(scenario)
        }
    }

    @Test fun rotationAndBackgroundRetainOneJobThenCancel() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            connect(scenario, 8002)
            scenario.onActivity { it.button("generate").performClick() }
            waitFor(scenario) { it.model().state.value.phase == FittingPhase.WAITING }
            var id: String? = null
            scenario.onActivity { id = it.model().state.value.job!!.jobId }
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(1200)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertEquals(id, it.model().state.value.job!!.jobId)
                assertFalse(it.button("generate").isEnabled)
                it.button("cancel").performClick()
            }
            waitFor(scenario) { it.model().state.value.phase == FittingPhase.CANCELLED }
            end(scenario)
        }
    }

    @Test fun rateLimitRetriesAndEndingPendingJobPreventsLateResult() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            connect(scenario, 8003)
            scenario.onActivity { it.button("generate").performClick() }
            waitFor(scenario) { it.model().state.value.phase == FittingPhase.SUCCEEDED }
            end(scenario)
            connect(scenario, 8002)
            scenario.onActivity { it.button("generate").performClick() }
            waitFor(scenario) { it.model().state.value.phase == FittingPhase.WAITING }
            end(scenario)
            SystemClock.sleep(2200)
            scenario.onActivity { assertEquals(FittingPhase.CLOSED, it.model().state.value.phase); assertNull(it.model().state.value.result) }
        }
    }
}
