package com.smartfitting.mirror

import android.view.View
import android.Manifest
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun launchPauseResumeAndRecreatePreservesStopAndMirrorChoices() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val all = descendants(activity.window.decorView)
                assertTrue(all.filterIsInstance<TextView>().any { it.text.contains("人体视觉") })
                all.filterIsInstance<Switch>().single().isChecked = false
                all.filterIsInstance<Button>().single { it.text == "停止并清理" }.performClick()
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.recreate()
            scenario.onActivity { activity ->
                val all = descendants(activity.window.decorView)
                assertFalse(all.filterIsInstance<Switch>().single().isChecked)
                assertFalse(all.filterIsInstance<Button>().single { it.text == "查看原始帧" }.isEnabled)
                all.filterIsInstance<Button>().single { it.text == "合成样例" }.performClick()
            }
        }
    }

    @Test fun rgbCameraRunsInferenceAndRecoversAfterBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.text == "RGB 摄像头" }.performClick()
            }
            fun awaitRgbFrame() {
                val deadline = SystemClock.elapsedRealtime() + 30_000
                var received = false
                while (!received && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        val views = descendants(activity.window.decorView)
                        received = views.filterIsInstance<TextView>().any { it.text.contains("RGB_CAMERA") }
                    }
                    if (!received) SystemClock.sleep(100)
                }
                assertTrue("RGB camera must produce a frame after ML Kit inference", received)
            }
            awaitRgbFrame()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitRgbFrame()
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.text == "停止并清理" }.performClick()
                assertFalse(descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.text == "查看原始帧" }.isEnabled)
            }
        }
    }
}
