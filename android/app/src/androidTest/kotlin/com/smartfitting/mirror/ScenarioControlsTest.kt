package com.smartfitting.mirror

import android.Manifest
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.smartfitting.mirror.vision.*

@RunWith(AndroidJUnit4::class)
class ScenarioControlsTest {
    @get:Rule val cameraPermission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun MainActivity.button(text: String) = descendants(window.decorView).filterIsInstance<Button>().single { it.text == text }
    private fun awaitUi(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var matched = false
        while (!matched && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { matched = condition(it) }
            if (!matched) SystemClock.sleep(50)
        }
        assertTrue("Expected diagnostic state was not reached", matched)
    }

    @Test fun sceneViewportPauseAndStepSurviveRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitUi(scenario) { it.button("查看原始帧").isEnabled }
            scenario.onActivity {
                it.window.decorView.findViewWithTag<Spinner>("synthetic.scenario").setSelection(SyntheticScenario.PARTIAL.ordinal)
                it.window.decorView.findViewWithTag<Spinner>("synthetic.viewport").setSelection(SyntheticViewport.LANDSCAPE.ordinal)
                it.window.decorView.findViewWithTag<Button>("synthetic.play").performClick()
            }
            awaitUi(scenario) {
                descendants(it.window.decorView).filterIsInstance<VisionDebugView>().single()
                    .contentDescription.toString().contains("PARTIAL，640×480")
            }
            scenario.onActivity {
                it.window.decorView.findViewWithTag<Button>("synthetic.reset").performClick()
                it.window.decorView.findViewWithTag<Button>("synthetic.step").performClick()
                val summary = it.window.decorView.findViewWithTag<TextView>("synthetic.summary").text.toString()
                assertTrue(summary.contains("已暂停") && summary.contains("样本 1"))
            }
            SystemClock.sleep(250)
            scenario.onActivity {
                assertTrue(it.window.decorView.findViewWithTag<TextView>("synthetic.summary").text.contains("样本 1"))
            }
            scenario.recreate()
            awaitUi(scenario) { it.window.decorView.findViewWithTag<TextView>("synthetic.summary").text.contains("PARTIAL") }
            scenario.onActivity {
                assertEquals(SyntheticScenario.PARTIAL.ordinal, it.window.decorView.findViewWithTag<Spinner>("synthetic.scenario").selectedItemPosition)
                assertEquals(SyntheticViewport.LANDSCAPE.ordinal, it.window.decorView.findViewWithTag<Spinner>("synthetic.viewport").selectedItemPosition)
                assertEquals("播放", it.window.decorView.findViewWithTag<Button>("synthetic.play").text.toString())
                assertTrue(it.window.decorView.findViewWithTag<TextView>("synthetic.summary").text.contains("样本 0"))
            }
        }
    }

    @Test fun rapidSourceChangesAndStopDoNotShowOldCaptures() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitUi(scenario) { it.button("查看原始帧").isEnabled }
            scenario.onActivity { activity ->
                activity.button("查看原始帧").performClick()
                repeat(4) {
                    activity.button("RGB 摄像头").performClick()
                    assertFalse(activity.window.decorView.findViewWithTag<Spinner>("synthetic.scenario").isEnabled)
                    activity.button("合成样例").performClick()
                    activity.button("停止并清理").performClick()
                    assertFalse(activity.button("查看原始帧").isEnabled)
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<VisionDebugView>().single().contentDescription.contains("无活动帧"))
                    activity.button("重新开始").performClick()
                }
            }
            awaitUi(scenario) { it.button("查看原始帧").isEnabled }
            SystemClock.sleep(500)
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.findViewWithTag<Spinner>("synthetic.scenario").isEnabled)
                assertTrue(activity.window.decorView.hasWindowFocus()) // An old capture must not open a dialog.
                assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains("MOCK") })
            }
        }
    }
}
