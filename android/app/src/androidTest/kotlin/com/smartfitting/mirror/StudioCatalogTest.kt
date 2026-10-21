package com.smartfitting.mirror

import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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

/** Actual native gallery and authenticated local PNGs, with no camera source. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=29)
class StudioCatalogTest {
    private fun descendants(view: View): List<View> = listOf(view)+if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun windows()=WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }
    private fun images()=windows().filterIsInstance<ImageView>().filter { it.contentDescription?.startsWith("UNIQLO") == true }
    private fun model(a: TryOnActivity)=ViewModelProvider(a)[TryOnModel::class.java]
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
                assertEquals(51,model(it).state.value.garments.size)
                it.window.decorView.findViewWithTag<Button>("fitting.catalog").performClick()
                windows().filterIsInstance<EditText>().single { it.tag=="catalog.search" }.setText("474062")
            }
            await { images().size==3 && images().all { it.drawable is BitmapDrawable } }
            scenario.onActivity { windows().filterIsInstance<EditText>().single { it.tag=="catalog.search" }.setText("") }
            await { images().size==51 && images().all { it.drawable is BitmapDrawable } }
            scenario.onActivity {
                assertTrue(images().none { it.contentDescription.contains("加载失败") })
                model(it).controller!!.end()
            }
            await { model(it).state.value.phase==FittingPhase.CLOSED }
        }
    }
}
