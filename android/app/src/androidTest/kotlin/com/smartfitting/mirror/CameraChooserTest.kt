package com.smartfitting.mirror

import android.app.AlertDialog
import android.Manifest
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraChooserTest {
    @Test fun missingExplicitCameraFailsWithoutStartingAutomaticCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[TryOnModel::class.java].cameraId = "missing-camera-for-test"
                activity.window.decorView.findViewWithTag<Button>("fitting.rgb").performClick()
            }
            var failed = false
            val deadline = SystemClock.elapsedRealtime()+30_000
            while (!failed && SystemClock.elapsedRealtime()<deadline) {
                scenario.onActivity { activity ->
                    val status = activity.window.decorView.findViewWithTag<TextView>("fitting.vision").text
                    failed = status.contains("missing-camera-for-test") && status.contains("不可用")
                    if (failed) {
                        val model = ViewModelProvider(activity)[TryOnModel::class.java]
                        assertEquals("missing-camera-for-test",model.cameraId)
                        assertFalse(model.running)
                    }
                }
                if (!failed) SystemClock.sleep(100)
            }
            assertTrue("Unavailable explicit channel must report an error instead of opening auto",failed)
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val model = ViewModelProvider(activity)[TryOnModel::class.java]
                assertFalse(model.running)
                assertTrue(root.findViewWithTag<TextView>("fitting.preview.source").text.contains("missing-camera-for-test"))
                val retry = root.findViewWithTag<Button>("fitting.preview.input")
                assertEquals("重试预览",retry.text.toString())
                assertTrue(retry.contentDescription.contains("保持已选相机通道"))
                retry.performClick()
                assertEquals("missing-camera-for-test",model.cameraId)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse(ViewModelProvider(activity)[TryOnModel::class.java].running)
                assertEquals("重试预览",activity.window.decorView.findViewWithTag<Button>("fitting.preview.input").text.toString())
                assertFalse(activity.window.decorView.findViewWithTag<Button>("fitting.generate").isEnabled)
            }
        }
    }

    @Test fun selectionRequiresApplyAndCancelDoesNotChangeCameraOrStartCapture() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val options = listOf(CameraChooser.Choice(null,"自动"),
                CameraChooser.Choice("front-ir","前向通道"),CameraChooser.Choice("color-5","彩色候选"))
            var callbacks = 0
            fun dialog(activity: TryOnActivity): AlertDialog {
                val model = ViewModelProvider(activity)[TryOnModel::class.java]
                return CameraChooser.show(activity,model.cameraId,options) { id ->
                    callbacks++; model.cameraId = id
                }
            }
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[TryOnModel::class.java]
                model.cameraId = "front-ir"
                val canceled = dialog(activity)
                assertEquals(1,canceled.listView.checkedItemPosition)
                canceled.listView.performItemClick(null,2,2)
                assertEquals("front-ir",model.cameraId)
                canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(0,callbacks)
                val applied = dialog(activity)
                applied.listView.performItemClick(null,2,2)
                applied.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            // AlertDialog dispatches positive-button callbacks through its Handler.
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[TryOnModel::class.java]
                assertEquals("color-5",model.cameraId)
                assertEquals(1,callbacks)
                assertFalse("Choosing a channel must not start capture",model.running)
                val automatic = dialog(activity)
                automatic.listView.performItemClick(null,0,0)
                automatic.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertNull(ViewModelProvider(activity)[TryOnModel::class.java].cameraId)
                assertEquals(2,callbacks)
            }
        }
    }

    @Test fun explicitUnpluggedSelectionSurvivesRecreationWithoutFallingBackOrOpeningCamera() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[TryOnModel::class.java].cameraId = "missing-camera-for-test"
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[TryOnModel::class.java]
                assertEquals("missing-camera-for-test",model.cameraId)
                val button = activity.window.decorView.findViewWithTag<Button>("fitting.camera.select")
                assertEquals(CameraChooser.label(model.cameraId),button.text.toString())
                val choices = CameraChooser.choices(activity,model.cameraId)
                assertTrue("Disconnected ID stays explicit",choices.any { it.id == model.cameraId })
                assertNull(choices.first().id)
                assertFalse(model.running)
            }
        }
    }
}
