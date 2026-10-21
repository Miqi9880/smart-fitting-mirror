package com.smartfitting.mirror

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.GarmentCategory
import com.smartfitting.mirror.tryon.OutfitFit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FitControlsTest {
    @Test fun visibleLandmarkSwitchAndIndependentFitSettingsSurviveRotationAndReset() {
        val host=InstrumentationRegistry.getArguments().getString("studio_test_host")
        assumeTrue(host!=null)
        require(host in listOf("http://10.0.2.2:8010","http://127.0.0.1:8010"))
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            fun model(a: TryOnActivity)=ViewModelProvider(a)[TryOnModel::class.java]
            fun await(check: (TryOnActivity)->Boolean) {
                val deadline=SystemClock.elapsedRealtime()+30_000; var ready=false
                while (!ready && SystemClock.elapsedRealtime()<deadline) {
                    scenario.onActivity { ready=check(it) }; if (!ready) SystemClock.sleep(100)
                }
                assertTrue("Expected fit UI state",ready)
            }
            fun select(id: String) {
                scenario.onActivity { model(it).controller!!.select(id) }
                await { model(it).state.value.selectedId==id &&
                    it.window.decorView.findViewWithTag<TextView>("fitting.fit.summary").text.contains(model(it).state.value.selected!!.category.label) }
            }
            scenario.onActivity { activity ->
                val root=activity.window.decorView
                val toggle=root.findViewWithTag<Switch>("fitting.landmarks")
                assertTrue(toggle.isChecked)
                val togglePosition=IntArray(2); val previewPosition=IntArray(2)
                toggle.getLocationInWindow(togglePosition)
                root.findViewWithTag<View>("fitting.preview").getLocationInWindow(previewPosition)
                assertTrue("Landmark switch must precede the tall preview",togglePosition[1]<previewPosition[1])
                root.findViewWithTag<EditText>("fitting.address").setText(host)
                root.findViewWithTag<Button>("fitting.connect").performClick()
            }
            await { model(it).state.value.canSelect }
            select("studio_chino_shorts_32")
            scenario.onActivity { activity ->
                val root=activity.window.decorView
                root.findViewWithTag<Button>("fitting.fit.toggle").performClick()
                val args=Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,100f) }
                assertTrue(root.findViewWithTag<SeekBar>("fitting.fit.width").performAccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,args))
                assertEquals(1.6f,model(activity).outfitFits.getValue(GarmentCategory.SHORTS).width,.001f)
            }
            select("studio_black_beanie")
            scenario.onActivity {
                assertEquals(1.35f,OutfitFit.defaultFor(GarmentCategory.HEADWEAR).width,.001f)
                assertFalse(model(it).outfitFits.containsKey(GarmentCategory.HEADWEAR))
                assertEquals(1.6f,model(it).outfitFits.getValue(GarmentCategory.SHORTS).width,.001f)
                it.window.decorView.findViewWithTag<Switch>("fitting.landmarks").isChecked=false
            }
            scenario.recreate()
            await { model(it).state.value.canSelect }
            scenario.onActivity {
                assertFalse(it.window.decorView.findViewWithTag<Switch>("fitting.landmarks").isChecked)
                assertEquals(1.6f,model(it).outfitFits.getValue(GarmentCategory.SHORTS).width,.001f)
            }
            select("studio_chino_shorts_32")
            scenario.onActivity {
                val root=it.window.decorView
                root.findViewWithTag<Button>("fitting.fit.toggle").performClick()
                root.findViewWithTag<Button>("fitting.fit.reset").performClick()
                assertEquals(OutfitFit.defaultFor(GarmentCategory.SHORTS),model(it).outfitFits[GarmentCategory.SHORTS])
                assertFalse("Connecting and calibration must not start a camera",model(it).running)
                root.findViewWithTag<Button>("fitting.end").performClick()
            }
        }
    }
}
