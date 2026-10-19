package com.smartfitting.mirror

import android.content.Intent
import android.widget.Button
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigratedControlsTest {
    @Test fun inventoryReportsSystemFactsAndExplicitCapabilityLimits() {
        val report = HardwareInventory.collect(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(report.contains("支持 ABI")); assertTrue(report.contains("Camera2")); assertTrue(report.contains("USB"))
        assertTrue(report.contains("不证明摄像头已出图"))
    }
    @Test fun displayControlsSurviveRecreationAndStopStillDisablesCapture() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.window.decorView.findViewWithTag<Button>("fitting.generate").isEnabled)
                activity.window.decorView.findViewWithTag<Button>("fitting.mock").performClick()
                activity.window.decorView.findViewWithTag<Switch>("fitting.landmarks").isChecked = true
                activity.window.decorView.findViewWithTag<Switch>("fitting.mesh").isChecked = true
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.findViewWithTag<Switch>("fitting.landmarks").isChecked)
                assertTrue(activity.window.decorView.findViewWithTag<Switch>("fitting.mesh").isChecked)
                assertFalse(activity.window.decorView.findViewWithTag<Button>("fitting.generate").isEnabled)
            }
        }
    }
    @Test fun mirrorCanBeChosenAsHomeWithoutChangingTheCurrentDefault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidates = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName),0)
        assertTrue(candidates.any { it.activityInfo.name.endsWith(".TryOnActivity") })
    }
}
