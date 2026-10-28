package com.smartfitting.mirror

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Asymmetric generated pixels reveal accidental mirroring; no camera, files or network. */
@RunWith(AndroidJUnit4::class)
class ResultComparisonTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun panel(activity: TryOnActivity) = activity.window.decorView.findViewWithTag<ResultComparisonPanel>("fitting.comparison.panel")
    private fun image(panel: ResultComparisonPanel, before: Boolean) = panel.findViewWithTag<ImageView>(if (before) "fitting.before" else "fitting.after")
    private fun await(scenario: ActivityScenario<TryOnActivity>, check: (TryOnActivity) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        var done = false
        while (!done && SystemClock.elapsedRealtime() < end) {
            scenario.onActivity { done = check(it) }
            if (!done) SystemClock.sleep(40)
        }
        assertTrue("Expected comparison image state", done)
        instrumentation.waitForIdleSync()
    }
    private fun source(left: Int, right: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
        for (y in 0 until 96) for (x in 0 until 64) bitmap.setPixel(x, y, if (x < 32) left else right)
        return try { ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    private fun assertLeftPixel(view: ImageView, expected: Int) {
        val source = (view.drawable as BitmapDrawable).bitmap
        val point = floatArrayOf(source.width * .25f, source.height * .5f)
        view.imageMatrix.mapPoints(point)
        val output = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(output))
            assertEquals(expected, output.getPixel(point[0].toInt() + view.paddingLeft, point[1].toInt() + view.paddingTop))
        } finally { output.recycle() }
    }

    @Test fun missingOrPendingImagesCannotSelectUnavailableResult() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            val bytes = source(Color.RED, Color.GREEN)
            scenario.onActivity {
                val panel = panel(it)
                for (tag in listOf("original", "result", "pair"))
                    assertFalse(panel.findViewWithTag<Button>("fitting.comparison.$tag").isEnabled)
                panel.render(bytes, null, ResultComparisonMode.RESULT)
                assertFalse(panel.findViewWithTag<Button>("fitting.comparison.result").isEnabled)
                assertFalse(panel.findViewWithTag<Button>("fitting.comparison.pair").isEnabled)
                assertTrue(panel.findViewWithTag<Button>("fitting.comparison.original").isSelected)
                panel.findViewWithTag<Button>("fitting.comparison.result").performClick()
                assertTrue(panel.findViewWithTag<Button>("fitting.comparison.original").isSelected)
                assertFalse(image(panel, false).isShown)
            }
            await(scenario) { image(panel(it), true).drawable != null }
            scenario.onActivity { panel(it).clear() }
        }
    }

    @Test fun singleImageSwitchUsesSameViewportAndPixelsWithoutRedecodingOrMirroring() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            val before = source(Color.RED, Color.GREEN); val after = source(Color.BLUE, Color.YELLOW)
            val beforeCopy = before.copyOf(); val afterCopy = after.copyOf()
            scenario.onActivity { panel(it).render(before, after, ResultComparisonMode.SIDE_BY_SIDE) }
            await(scenario) { image(panel(it), true).drawable != null && image(panel(it), false).drawable != null }
            lateinit var beforeBitmap: Bitmap; lateinit var afterBitmap: Bitmap
            scenario.onActivity {
                val panel = panel(it)
                beforeBitmap = (image(panel, true).drawable as BitmapDrawable).bitmap
                afterBitmap = (image(panel, false).drawable as BitmapDrawable).bitmap
                assertTrue(image(panel, true).isShown); assertTrue(image(panel, false).isShown)
                panel.findViewWithTag<Button>("fitting.comparison.original").performClick()
            }
            instrumentation.waitForIdleSync()
            var width = 0; var height = 0
            scenario.onActivity {
                val panel = panel(it); val original = image(panel, true)
                width = original.width; height = original.height
                assertTrue(original.isShown); assertFalse(image(panel, false).isShown)
                assertLeftPixel(original, Color.RED)
                panel.findViewWithTag<Button>("fitting.comparison.result").performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val panel = panel(it); val result = image(panel, false)
                assertEquals(width, result.width); assertEquals(height, result.height)
                assertFalse(image(panel, true).isShown); assertTrue(result.isShown)
                assertLeftPixel(result, Color.BLUE)
                repeat(20) {
                    panel.findViewWithTag<Button>("fitting.comparison.original").performClick()
                    panel.findViewWithTag<Button>("fitting.comparison.result").performClick()
                }
                assertSame(beforeBitmap, (image(panel, true).drawable as BitmapDrawable).bitmap)
                assertSame(afterBitmap, (image(panel, false).drawable as BitmapDrawable).bitmap)
                assertArrayEquals(beforeCopy, before); assertArrayEquals(afterCopy, after)
                assertFalse(ViewModelProvider(it)[TryOnModel::class.java].running)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity {
                assertNull(image(panel(it), true).drawable); assertNull(image(panel(it), false).drawable)
            }
            assertTrue(beforeBitmap.isRecycled); assertTrue(afterBitmap.isRecycled)
        }
    }

    @Test fun replacementDecodeFailureAndLateCompletionNeverRestoreOldResult() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            val first = source(Color.RED, Color.GREEN); val second = source(Color.BLUE, Color.YELLOW)
            scenario.onActivity { panel(it).render(first, second, ResultComparisonMode.RESULT) }
            await(scenario) { image(panel(it), false).drawable != null }
            lateinit var old: Bitmap
            scenario.onActivity {
                val panel = panel(it)
                old = (image(panel, false).drawable as BitmapDrawable).bitmap
                panel.render(second, null, ResultComparisonMode.RESULT)
                assertNull(image(panel, false).drawable)
                assertFalse(panel.findViewWithTag<Button>("fitting.comparison.result").isEnabled)
                panel.render(second, "bad image".toByteArray(), ResultComparisonMode.RESULT)
            }
            await(scenario) { panel(it).findViewWithTag<TextView>("fitting.comparison.status").text.contains("无法读取") }
            scenario.onActivity {
                val panel = panel(it)
                assertNull(image(panel, false).drawable)
                assertFalse(panel.findViewWithTag<Button>("fitting.comparison.result").isEnabled)
                panel.render(first, second, ResultComparisonMode.RESULT); panel.clear()
            }
            instrumentation.waitForIdleSync(); SystemClock.sleep(150)
            scenario.onActivity {
                val panel = panel(it)
                assertNull(image(panel, true).drawable); assertNull(image(panel, false).drawable)
                assertFalse(panel.findViewWithTag<Button>("fitting.comparison.result").isEnabled)
                assertTrue(panel.findViewWithTag<TextView>("fitting.comparison.status").text.contains("尚无"))
            }
            assertTrue(old.isRecycled)
        }
    }

    @Test fun largeFontNarrowAndWideControlsKeepReadableTextAndSelectedLabels() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (scale in listOf(1.3f, 1.5f)) {
                    val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { fontScale = scale })
                    val panel = ResultComparisonPanel(context, activity.lifecycleScope, {}, {})
                    panel.render(source(Color.RED, Color.GREEN), source(Color.BLUE, Color.YELLOW), ResultComparisonMode.RESULT)
                    for (widthDp in listOf(320, 700)) {
                        val width = (widthDp * context.resources.displayMetrics.density).toInt()
                        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        panel.layout(0, 0, width, panel.measuredHeight)
                        for (tag in listOf("original", "result", "pair")) {
                            val button = panel.findViewWithTag<Button>("fitting.comparison.$tag")
                            assertTrue(button.isFocusable); assertTrue(button.contentDescription.isNotBlank())
                            assertTrue(button.height >= 48 * context.resources.displayMetrics.density)
                            assertTrue("font=$scale width=$widthDp $tag", button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                        }
                        assertTrue(panel.findViewWithTag<Button>("fitting.comparison.result").contentDescription.contains("当前"))
                    }
                    panel.clear()
                }
            }
        }
    }
}
