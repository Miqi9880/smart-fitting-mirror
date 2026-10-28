package com.smartfitting.mirror

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
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

/** Explicitly opt-in local MOCK with generated pixels; never a camera or paid provider. */
@RunWith(AndroidJUnit4::class)
class ResultComparisonIntegrationTest {
    @Test fun mockComparisonRetainsModeAcrossRotationAndBackgroundAndClearsForNextJobAndEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = InstrumentationRegistry.getArguments().getString("studio_test_host")
        assumeTrue(host != null)
        require(host in listOf("http://10.0.2.2:8010", "http://127.0.0.1:8010"))
        val bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val input = try { ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray() }
        finally { bitmap.recycle() }
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            fun model(activity: TryOnActivity) = ViewModelProvider(activity)[TryOnModel::class.java]
            fun image(activity: TryOnActivity, before: Boolean) = activity.window.decorView.findViewWithTag<ImageView>(if (before) "fitting.before" else "fitting.after")
            fun button(activity: TryOnActivity, name: String) = activity.window.decorView.findViewWithTag<Button>("fitting.comparison.$name")
            fun await(check: (TryOnActivity) -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 30_000
                var done = false
                while (!done && SystemClock.elapsedRealtime() < end) {
                    scenario.onActivity { done = check(it) }; if (!done) SystemClock.sleep(60)
                }
                var detail = ""
                if (!done) scenario.onActivity { detail = "${model(it).state.value.phase} retry=${model(it).state.value.retry}: ${model(it).state.value.message}" }
                assertTrue("Expected comparison/session state: $detail", done)
                instrumentation.waitForIdleSync()
            }
            fun evidence(name: String) {
                if (InstrumentationRegistry.getArguments().getString("comparison_evidence") != "true") return
                scenario.onActivity {
                    val root = it.window.decorView
                    val panel = root.findViewWithTag<View>("fitting.comparison.panel")
                    val scroll = root.findViewWithTag<ScrollView>("fitting.scroll")
                    val rect = Rect(0, 0, panel.width, panel.height)
                    scroll.offsetDescendantRectToMyCoords(panel, rect)
                    scroll.scrollTo(0, (rect.top - 32 * it.resources.displayMetrics.density).toInt().coerceAtLeast(0))
                }
                instrumentation.waitForIdleSync(); SystemClock.sleep(250)
                val shot = instrumentation.uiAutomation.takeScreenshot()
                val file = File(instrumentation.targetContext.getExternalFilesDir(null), "evidence/result-comparison-$name.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }; shot.recycle()
            }
            fun importAndSubmit() {
                scenario.onActivity { it.window.decorView.findViewWithTag<StaticImagePanel>("fitting.import.panel").load { ByteArrayInputStream(input) } }
                await { it.window.decorView.findViewWithTag<Button>("fitting.import.generate").isEnabled }
                scenario.onActivity { it.window.decorView.findViewWithTag<Button>("fitting.import.generate").performClick() }
            }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await { it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            scenario.onActivity { model(it).connect(host!!) }
            await { model(it).state.value.canCapture }
            importAndSubmit()
            await { model(it).state.value.phase == FittingPhase.SUCCEEDED && image(it, true).drawable != null && image(it, false).drawable != null }
            var jobId: String? = null
            lateinit var oldOriginal: ByteArray; lateinit var oldResult: ByteArray
            scenario.onActivity {
                val state = model(it).state.value
                jobId = state.job!!.jobId; oldOriginal = state.original!!; oldResult = state.result!!
                assertTrue(state.job!!.isMock); assertFalse(model(it).running)
                assertTrue(it.window.decorView.findViewWithTag<TextView>("fitting.result.kind").text.contains("MOCK"))
                button(it, "result").performClick()
            }
            evidence("result")
            scenario.onActivity {
                assertTrue(image(it, false).isShown); assertFalse(image(it, true).isShown)
                button(it, "original").performClick()
            }
            evidence("original")
            scenario.onActivity {
                assertEquals(jobId, model(it).state.value.job!!.jobId)
                assertSame(oldOriginal, model(it).state.value.original); assertSame(oldResult, model(it).state.value.result)
            }
            scenario.recreate()
            await { image(it, true).drawable != null && image(it, true).isShown && !image(it, false).isShown }
            scenario.onActivity {
                assertEquals(ResultComparisonMode.ORIGINAL, model(it).resultComparisonMode)
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await { it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && image(it, true).drawable != null }
            scenario.onActivity { assertEquals(ResultComparisonMode.ORIGINAL, model(it).resultComparisonMode); button(it, "result").performClick() }
            evidence("landscape-result")
            lateinit var decoded: Bitmap
            scenario.onActivity { decoded = (image(it, false).drawable as BitmapDrawable).bitmap }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { assertNull(image(it, true).drawable); assertNull(image(it, false).drawable) }
            assertTrue(decoded.isRecycled)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { image(it, false).drawable != null && image(it, false).isShown }
            scenario.onActivity {
                assertEquals(jobId, model(it).state.value.job!!.jobId)
                assertEquals(ResultComparisonMode.RESULT, model(it).resultComparisonMode)
            }
            // The local Uvicorn server closes idle keep-alive connections at 5s.
            SystemClock.sleep(6_000)
            importAndSubmit()
            await { model(it).state.value.result == null && image(it, false).drawable == null }
            scenario.onActivity { assertFalse(button(it, "result").isEnabled) }
            assertTrue(oldOriginal.all { it == 0.toByte() }); assertTrue(oldResult.all { it == 0.toByte() })
            await { model(it).state.value.phase == FittingPhase.SUCCEEDED && image(it, false).drawable != null }
            lateinit var original: ByteArray; lateinit var result: ByteArray
            scenario.onActivity {
                assertNotEquals(jobId, model(it).state.value.job!!.jobId)
                original = model(it).state.value.original!!; result = model(it).state.value.result!!
                button(it, "pair").performClick()
                assertTrue(image(it, true).isShown); assertTrue(image(it, false).isShown)
                it.window.decorView.findViewWithTag<Button>("fitting.end").performClick()
            }
            await { model(it).state.value.phase == FittingPhase.CLOSED }
            scenario.onActivity {
                assertNull(image(it, true).drawable); assertNull(image(it, false).drawable)
                for (name in listOf("original", "result", "pair")) assertFalse(button(it, name).isEnabled)
                assertEquals(ResultComparisonMode.SIDE_BY_SIDE, model(it).resultComparisonMode)
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            assertTrue(original.all { it == 0.toByte() }); assertTrue(result.all { it == 0.toByte() })
        }
    }
}
