package com.smartfitting.mirror

import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.network.FittingPhase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Actual native gallery and authenticated local PNGs, with no camera source. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=29)
class StudioCatalogTest {
    private fun descendants(view: View): List<View> = listOf(view)+if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun windows()=WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }
    private fun images()=windows().filterIsInstance<ImageView>().filter { it.contentDescription?.startsWith("UNIQLO") == true }
    private fun model(a: TryOnActivity)=ViewModelProvider(a)[TryOnModel::class.java]

    @Test fun categorizedAccessoriesAndTwoGarmentsGenerateTogetherFromImportedImage() {
        val host = InstrumentationRegistry.getArguments().getString("studio_test_host")
        assumeTrue(host != null)
        require(host in listOf("http://10.0.2.2:8010", "http://127.0.0.1:8010"))
        val png = ByteArrayOutputStream().use { out ->
            val image = Bitmap.createBitmap(100, 160, Bitmap.Config.ARGB_8888)
            try {
                image.eraseColor(Color.CYAN)
                image.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            } finally { image.recycle() }
        }
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            fun await(description: String, check: (TryOnActivity) -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 20_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    var ready = false
                    scenario.onActivity { ready = check(it) }
                    if (ready) return
                    SystemClock.sleep(60)
                }
                fail(description)
            }
            scenario.onActivity { model(it).connect(host!!) }
            await("Outfit catalog ready") { model(it).state.value.outfitAvailable }
            val jeans = "studio_straight_jeans_67"
            val top = "studio_black_tee"
            scenario.onActivity { activity ->
                val garments = model(activity).state.value.garments
                activity.window.decorView.findViewWithTag<Spinner>("fitting.garments")
                    .setSelection(garments.indexOfFirst { it.garmentId == jeans })
            }
            await("Top and jeans selected") { model(it).outfit.values.map { item -> item.garmentId }.containsAll(listOf(top, jeans)) }
            scenario.onActivity {
                it.window.decorView.findViewWithTag<Button>("fitting.generation.accessories").performClick()
            }
            await("Belt and earrings groups and their four thumbnails visible") {
                val views = windows()
                val labels = views.filterIsInstance<TextView>().map { view -> view.text.toString() }
                "腰带" in labels && "耳饰" in labels && views.filterIsInstance<ImageView>().count {
                    view -> view.drawable is BitmapDrawable && view.scaleType == ImageView.ScaleType.FIT_CENTER
                } >= 4
            }
            scenario.onActivity { windows().filterIsInstance<Button>().single { it.tag == "fitting.accessory.silver_studs" }.performClick() }
            scenario.onActivity {
                val summary = it.window.decorView.findViewWithTag<TextView>("fitting.generation.summary").text.toString()
                assertTrue(summary.contains("牛仔") && summary.contains("耳钉"))
                assertFalse(model(it).running)
                it.window.decorView.findViewWithTag<StaticImagePanel>("fitting.import.panel").load { ByteArrayInputStream(png) }
            }
            await("Photo ready to generate") { it.window.decorView.findViewWithTag<Button>("fitting.import.generate").isEnabled }
            scenario.onActivity { it.window.decorView.findViewWithTag<Button>("fitting.import.generate").performClick() }
            await("Outfit result ready") { model(it).state.value.phase == FittingPhase.SUCCEEDED }
            scenario.onActivity {
                val job = checkNotNull(model(it).state.value.job)
                assertTrue(job.isMock)
                val outfit = checkNotNull(job.outfit)
                assertEquals(setOf(top, jeans), outfit.garmentIds.toSet())
                assertEquals(listOf("silver_studs"), outfit.accessoryIds)
            }
        }
    }

    @Test fun allStudioThumbnailsLoadAfterSearchCancelsPendingReads() {
        val host=InstrumentationRegistry.getArguments().getString("studio_test_host")
        assumeTrue("Requires studio service and local pack",host!=null)
        require(host in listOf("http://10.0.2.2:8010","http://127.0.0.1:8010"))
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            fun await(check: (TryOnActivity)->Boolean) {
                val until=SystemClock.elapsedRealtime()+60_000;var ready=false
                while (!ready && SystemClock.elapsedRealtime()<until) {
                    scenario.onActivity { ready=check(it) };if (!ready) SystemClock.sleep(100)
                }
                var detail=""
                if (!ready) scenario.onActivity { detail=images().filter { it.drawable !is BitmapDrawable }.joinToString { it.contentDescription.toString() } }
                assertTrue("Gallery images did not finish loading: $detail",ready)
            }
            scenario.onActivity {
                it.window.decorView.findViewWithTag<EditText>("fitting.address").setText(host)
                it.window.decorView.findViewWithTag<Button>("fitting.connect").performClick()
            }
            await { model(it).state.value.canSelect }
            scenario.onActivity {
                assertFalse(model(it).running)
                assertEquals(48,model(it).state.value.garments.size)
                it.window.decorView.findViewWithTag<Button>("fitting.catalog").performClick()
                windows().filterIsInstance<EditText>().single { it.tag=="catalog.search" }.setText("474062")
            }
            await { images().size==3 && images().all { it.drawable is BitmapDrawable } }
            scenario.onActivity { windows().filterIsInstance<EditText>().single { it.tag=="catalog.search" }.setText("") }
            await { images().size==48 && images().all { it.drawable is BitmapDrawable } }
            scenario.onActivity {
                assertTrue(images().none { it.contentDescription.contains("加载失败") })
                model(it).controller!!.end()
            }
            await { model(it).state.value.phase==FittingPhase.CLOSED }
        }
    }
}
