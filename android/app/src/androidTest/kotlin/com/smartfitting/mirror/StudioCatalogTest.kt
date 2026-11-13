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
            val top = "studio_blue_tee"
            fun button(tag: String) = windows().filterIsInstance<Button>().single { it.tag == tag }
            fun select(activity: TryOnActivity, id: String) {
                val name = model(activity).state.value.garments.single { it.garmentId == id }.name
                windows().single { it.contentDescription == "查看${name}详情" }.performClick()
                windows().filterIsInstance<Button>().single { it.text == "试穿这件" }.performClick()
            }
            scenario.onActivity {
                val root = it.window.decorView
                val catalog = root.findViewWithTag<Button>("fitting.catalog")
                assertEquals("浏览衣柜", catalog.text.toString())
                assertNull(root.findViewWithTag<View>("fitting.generation.accessories"))
                assertNull(root.findViewWithTag<View>("fitting.outfit.add"))
                assertNull(root.findViewWithTag<View>("fitting.outfit.remove"))
                val mesh = root.findViewWithTag<View>("fitting.mesh")
                val tail = mesh.parent as ViewGroup
                assertSame(mesh, tail.getChildAt(tail.childCount - 1))
                catalog.performClick()
                button("catalog.tab.tops").performClick()
                select(it, top)
            }
            await("Top selected without leaving the wardrobe") {
                model(it).state.value.selectedId == top && windows().any { it.tag == "catalog.close" }
            }
            scenario.onActivity {
                assertTrue(button("catalog.tab.tops").isSelected)
                button("catalog.tab.bottoms").performClick()
                val labels = windows().filterIsInstance<TextView>().map { view -> view.text.toString() }
                assertTrue(labels.containsAll(listOf("长裤", "短裤", "半裙")))
                assertEquals(24, images().size)
                select(it, jeans)
            }
            await("Top and jeans selected in one visit") {
                model(it).outfit.values.map { item -> item.garmentId }.containsAll(listOf(top, jeans)) &&
                    windows().any { it.tag == "catalog.close" }
            }
            scenario.onActivity {
                assertTrue(button("catalog.tab.bottoms").isSelected)
                button("catalog.close").performClick()
                button("fitting.outfit.remove.garment.$top").performClick()
                assertEquals(jeans, model(it).state.value.selectedId)
                assertEquals(listOf(jeans), model(it).outfit.values.map { item -> item.garmentId })
                button("fitting.outfit.remove.garment.$jeans").performClick()
                assertEquals(jeans, model(it).state.value.selectedId)
                assertTrue(model(it).outfit.isEmpty())
            }
            scenario.onActivity {
                button("fitting.catalog").performClick()
                button("catalog.tab.bottoms").performClick()
                select(it, jeans)
            }
            await("Trying on the same jeans restores them to the outfit") {
                model(it).outfit.values.map { item -> item.garmentId } == listOf(jeans)
            }
            scenario.onActivity {
                button("catalog.tab.tops").performClick()
                select(it, top)
            }
            await("Top and jeans are back in the outfit") {
                model(it).outfit.values.map { item -> item.garmentId }.toSet() == setOf(top, jeans)
            }
            scenario.onActivity {
                button("catalog.tab.accessories").performClick()
                assertTrue(button("catalog.tab.accessories").isSelected)
                assertTrue(images().isEmpty())
            }
            await("Belt and earrings groups and their four thumbnails visible") {
                val views = windows()
                val labels = views.filterIsInstance<TextView>().map { view -> view.text.toString() }
                "腰带" in labels && "耳饰" in labels && views.filterIsInstance<ImageView>().count {
                    view -> view.drawable is BitmapDrawable && view.scaleType == ImageView.ScaleType.FIT_CENTER
                } >= 4
            }
            scenario.onActivity {
                val studs = button("fitting.accessory.silver_studs")
                studs.performClick()
                assertTrue(studs.isSelected)
                studs.performClick()
                assertFalse(studs.isSelected)
                studs.performClick()
                button("catalog.close").performClick()
            }
            scenario.onActivity {
                button("fitting.outfit.remove.accessory.silver_studs").performClick()
                assertTrue(model(it).outfitAccessories.isEmpty())
                assertEquals(setOf(top, jeans), model(it).outfit.values.map { item -> item.garmentId }.toSet())
                button("fitting.catalog").performClick()
                button("catalog.tab.accessories").performClick()
                button("fitting.accessory.silver_studs").performClick()
                button("catalog.close").performClick()
            }
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
