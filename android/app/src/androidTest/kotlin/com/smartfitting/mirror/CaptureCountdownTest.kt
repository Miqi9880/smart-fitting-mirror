package com.smartfitting.mirror

import android.os.SystemClock
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.network.FittingPhase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureCountdownTest {
    @Test fun countdownCanBeCancelledAndOnlyCapturesAfterItExpires() {
        val host = InstrumentationRegistry.getArguments().getString("studio_test_host")
        assumeTrue(host != null)
        require(host in listOf("http://127.0.0.1:8010", "http://10.0.2.2:8010"))

        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            fun model(activity: TryOnActivity) = ViewModelProvider(activity)[TryOnModel::class.java]
            fun button(activity: TryOnActivity, tag: String) = activity.window.decorView.findViewWithTag<Button>(tag)
            fun countdown(activity: TryOnActivity) = activity.window.decorView.findViewWithTag<TextView>("fitting.preview.countdown")
            fun await(check: (TryOnActivity) -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 30_000
                do {
                    var ready = false
                    scenario.onActivity { ready = check(it) }
                    if (ready) return
                    SystemClock.sleep(60)
                } while (SystemClock.elapsedRealtime() < end)
                fail("Timed out waiting for capture UI or fitting result")
            }
            fun startCountdown() {
                scenario.onActivity {
                    val generate = button(it, "fitting.generate")
                    assertTrue(generate.isEnabled)
                    generate.performClick()
                    assertTrue(model(it).previewFullscreen)
                    assertTrue(countdown(it).isShown)
                    assertEquals("3", countdown(it).text.toString())
                    assertFalse(generate.isEnabled)
                    assertNull(model(it).state.value.job)
                }
            }

            scenario.onActivity {
                val delay = it.window.decorView.findViewWithTag<Spinner>("fitting.capture.delay")
                assertEquals("拍摄倒计时 · 关闭", delay.selectedItem.toString())
                delay.setSelection(1)
                model(it).connect(host!!)
                button(it, "fitting.mock").performClick()
            }
            await { model(it).captureDelaySeconds == 3 && button(it, "fitting.generate").isEnabled }
            scenario.onActivity {
                it.window.decorView.findViewWithTag<Switch>("fitting.generation.outfit").isChecked = true
            }
            await { button(it, "fitting.generate").isEnabled }
            startCountdown()
            scenario.onActivity { button(it, "fitting.preview.countdown.cancel").performClick() }
            SystemClock.sleep(3_200)
            scenario.onActivity {
                assertFalse(model(it).previewFullscreen)
                assertFalse(countdown(it).isShown)
                assertEquals(FittingPhase.READY, model(it).state.value.phase)
                assertNull(model(it).state.value.original)
            }

            startCountdown()
            scenario.recreate()
            SystemClock.sleep(3_200)
            await { button(it, "fitting.generate").isEnabled }
            scenario.onActivity {
                assertFalse(model(it).previewFullscreen)
                assertFalse(countdown(it).isShown)
                assertEquals(3, model(it).captureDelaySeconds)
                assertNull(model(it).state.value.job)
            }

            startCountdown()
            await { model(it).state.value.phase == FittingPhase.SUCCEEDED }
            scenario.onActivity {
                val result = model(it).state.value
                assertFalse(model(it).previewFullscreen)
                assertFalse(countdown(it).isShown)
                assertTrue(result.job!!.isMock)
                assertNotNull(result.job!!.outfit)
                assertNotNull(result.original)
                button(it, "fitting.end").performClick()
            }
            await { model(it).state.value.phase == FittingPhase.CLOSED }
        }
    }
}
