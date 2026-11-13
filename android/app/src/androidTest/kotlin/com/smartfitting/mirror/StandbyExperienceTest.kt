package com.smartfitting.mirror

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import android.widget.*
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.network.FittingPhase
import com.smartfitting.mirror.vision.BodyPresence
import com.smartfitting.mirror.vision.StandingAssessment
import com.smartfitting.mirror.vision.StandingCue
import com.smartfitting.mirror.vision.SyntheticScenario
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/** Local MOCK and generated/synthetic data only. No test enables RGB sensing. */
@RunWith(AndroidJUnit4::class)
class StandbyExperienceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun model(activity: TryOnActivity) = ViewModelProvider(activity)[TryOnModel::class.java]
    private fun homeModel(activity: StandbyActivity) = ViewModelProvider(activity)[StandbyModel::class.java]
    private fun localHost(failure: Boolean = false): String {
        val value = InstrumentationRegistry.getArguments().getString(if (failure) "standby_failure_host" else "studio_test_host")
        assumeTrue(value != null)
        val port = if (failure) 8014 else 8010
        require(value in listOf("http://10.0.2.2:$port", "http://127.0.0.1:$port"))
        return value!!
    }
    private fun await(timeoutMs: Long = 30_000, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (check()) return
            SystemClock.sleep(60)
        }
        assertTrue("Expected standby/guest state before timeout", check())
    }
    private fun onChild(child: TryOnActivity, action: (TryOnActivity) -> Unit) = instrumentation.runOnMainSync { action(child) }
    private fun childReady(child: TryOnActivity): Boolean {
        var ready = false
        onChild(child) { ready = model(it).state.value.canCapture }; return ready
    }
    private fun start(scenario: ActivityScenario<StandbyActivity>, host: String): TryOnActivity {
        val monitor = instrumentation.addMonitor(TryOnActivity::class.java.name, null, false)
        try {
            scenario.onActivity { homeModel(it).baseUrl = host; it.window.decorView.findViewWithTag<Button>("home.start").performClick() }
            val child = monitor.waitForActivityWithTimeout(5000) as? TryOnActivity
            assertNotNull("Guest starts the same app's fitting page", child)
            await { childReady(child!!) }
            onChild(child!!) { assertFalse(model(it).running); assertFalse(model(it).previewFullscreen) }
            return child
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun returned(scenario: ActivityScenario<StandbyActivity>, expected: String) {
        await(40_000) {
            var ready = false
            scenario.onActivity { ready = !homeModel(it).waitingGuest &&
                it.window.decorView.findViewWithTag<TextView>("home.status").text.contains(expected) &&
                it.window.decorView.findViewWithTag<Button>("home.start").isEnabled }
            ready
        }
    }
    private fun submit(child: TryOnActivity) {
        val bitmap = Bitmap.createBitmap(100, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val bytes = try { ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray() }
        finally { bitmap.recycle() }
        onChild(child) { it.window.decorView.findViewWithTag<StaticImagePanel>("fitting.import.panel").load { ByteArrayInputStream(bytes) } }
        await {
            var ready = false
            onChild(child) { ready = it.window.decorView.findViewWithTag<Button>("fitting.import.generate").isEnabled }; ready
        }
        onChild(child) { it.window.decorView.findViewWithTag<Button>("fitting.import.generate").performClick() }
    }
    private fun generate(child: TryOnActivity) {
        submit(child)
        awaitResult(child)
    }
    private fun awaitResult(child: TryOnActivity) {
        await {
            var ready = false
            onChild(child) { ready = model(it).state.value.phase == FittingPhase.SUCCEEDED &&
                it.window.decorView.findViewWithTag<ImageView>("fitting.after").drawable != null }; ready
        }
    }
    private fun evidence(name: String) {
        if (InstrumentationRegistry.getArguments().getString("standby_evidence") != "true") return
        instrumentation.waitForIdleSync(); SystemClock.sleep(800)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "evidence/standby-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { parcel ->
        FileInputStream(parcel.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }

    @Test fun coldHomeRequiresOptInSyntheticWelcomeAndLargeFontRotationAreUsable() {
        val previous = shell("settings get system font_scale").toFloatOrNull() ?: 1f
        try {
            ActivityScenario.launch(StandbyActivity::class.java).use { scenario ->
                scenario.onActivity {
                    assertFalse(it.sensingActive); assertFalse(homeModel(it).sensingRequested)
                    assertTrue(WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars)
                    val existingOrigin = homeModel(it).baseUrl
                    homeModel(it).baseUrl = "http://127.0.0.1:8010"; homeModel(it).saveConnection()
                    val recreatedModel = StandbyModel(it.application)
                    assertEquals("http://127.0.0.1:8010", recreatedModel.baseUrl)
                    assertFalse(recreatedModel.sensingRequested); assertFalse(recreatedModel.waitingGuest)
                    homeModel(it).baseUrl = existingOrigin; homeModel(it).saveConnection()
                    assertTrue(it.window.decorView.findViewWithTag<TextView>("home.status").text.contains("未开启"))
                    assertTrue(it.window.decorView.findViewWithTag<Button>("home.video").isEnabled)
                    assertFalse(it.window.decorView.findViewWithTag<Button>("home.makeup").isEnabled)
                    it.window.decorView.findViewWithTag<Button>("home.demo").performClick()
                }
                await {
                    var ready = false
                    scenario.onActivity { ready = it.sensingActive && it.window.decorView.findViewWithTag<TextView>("home.title").text.contains("合成演示") }
                    ready
                }
                evidence("synthetic-welcome")
                scenario.onActivity { it.window.decorView.findViewWithTag<Button>("home.sensing").performClick(); assertFalse(it.sensingActive) }
                for (scale in listOf(1.3f, 1.5f)) {
                    shell("settings put system font_scale $scale")
                    for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                        scenario.onActivity { it.requestedOrientation = orientation }
                        await {
                            var ready = false
                            scenario.onActivity { ready = kotlin.math.abs(it.resources.configuration.fontScale - scale) < .01f &&
                                it.resources.configuration.orientation == if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) 1 else 2 }
                            ready
                        }
                        instrumentation.waitForIdleSync()
                        for (tag in listOf("home.start", "home.sensing", "home.demo")) {
                            scenario.onActivity { activity ->
                                val button = activity.window.decorView.findViewWithTag<Button>(tag)
                                button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                            }
                            instrumentation.waitForIdleSync()
                            scenario.onActivity { activity ->
                                val button = activity.window.decorView.findViewWithTag<Button>(tag)
                                assertTrue(button.isFocusable); assertTrue(button.text.isNotBlank())
                                assertTrue(button.height >= 48 * activity.resources.displayMetrics.density)
                                assertTrue(button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                                val rect = Rect(); assertTrue(button.getGlobalVisibleRect(rect))
                                assertTrue("Critical button reachable: $tag", rect.height() >= button.height - 2)
                                assertFalse(activity.sensingActive)
                            }
                        }
                        evidence("font-$scale-orientation-$orientation")
                    }
                }
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        } finally { shell("settings put system font_scale $previous") }
    }

    @Test fun guestStartsWithoutCameraShowsSyntheticStandingAndManualEndClearsAndReturns() {
        val host = localHost()
        ActivityScenario.launch(StandbyActivity::class.java).use { scenario ->
            val child = start(scenario, host)
            onChild(child) { it.window.decorView.findViewWithTag<Spinner>("synthetic.scenario").setSelection(SyntheticScenario.STILL.ordinal) }
            instrumentation.waitForIdleSync()
            onChild(child) { it.window.decorView.findViewWithTag<Button>("fitting.mock").performClick() }
            await {
                var ready = false
                onChild(child) { ready = it.window.decorView.findViewWithTag<TextView>("fitting.standing.hint").text.contains("基本合适") }; ready
            }
            instrumentation.waitForIdleSync()
            onChild(child) {
                val target = it.window.decorView.findViewWithTag<View>("fitting.standing.panel")
                val scroll = it.window.decorView.findViewWithTag<ScrollView>("fitting.scroll")
                val rect = Rect(0, 0, target.width, target.height)
                scroll.offsetDescendantRectToMyCoords(target, rect)
                it.currentFocus?.clearFocus()
                scroll.scrollTo(0, (rect.top - 8 * it.resources.displayMetrics.density).toInt().coerceAtLeast(0))
                assertTrue(it.window.decorView.findViewWithTag<TextView>("fitting.standing.detail").text.contains("尚未测得距离"))
                assertFalse(model(it).rgb)
            }
            evidence("standing-synthetic")
            generate(child)
            lateinit var original: ByteArray; lateinit var result: ByteArray; lateinit var decoded: Bitmap
            onChild(child) {
                original = model(it).state.value.original!!; result = model(it).state.value.result!!
                decoded = (it.window.decorView.findViewWithTag<ImageView>("fitting.after").drawable as BitmapDrawable).bitmap
                it.window.decorView.findViewWithTag<Button>("fitting.end").performClick()
            }
            returned(scenario, "已清理")
            assertTrue(original.all { it == 0.toByte() }); assertTrue(result.all { it == 0.toByte() }); assertTrue(decoded.isRecycled)
            assertTrue(child.isFinishing)
            scenario.onActivity {
                assertFalse(it.sensingActive); assertEquals(host, homeModel(it).baseUrl)
                assertFalse(it.window.decorView.findViewWithTag<TextView>("home.status").text.contains("未确认"))
            }
            evidence("manual-ended")
        }
    }

    @Test fun virtualInactivityDeadlineClearsGeneratedImagesWithoutAnotherTap() {
        val host = localHost()
        ActivityScenario.launch(StandbyActivity::class.java).use { scenario ->
            val child = start(scenario, host); generate(child)
            lateinit var bytes: ByteArray
            onChild(child) {
                bytes = model(it).state.value.original!!
                model(it).checkExperienceTimeout(SystemClock.elapsedRealtime() + model(it).experienceClock.policy.idleMs)
            }
            returned(scenario, "无操作")
            assertTrue(bytes.all { it == 0.toByte() }); assertTrue(child.isFinishing)
            evidence("inactivity-ended")
        }
    }

    @Test fun waitingForGenerationShowsProgressAndKeepsGuestAfterThirtySecondsWithoutAPerson() {
        val host = localHost()
        ActivityScenario.launch(StandbyActivity::class.java).use { scenario ->
            val child = start(scenario, host)
            submit(child)
            await {
                var waiting = false
                onChild(child) { waiting = model(it).state.value.phase == FittingPhase.WAITING }
                waiting
            }
            onChild(child) {
                val root = it.window.decorView
                val model = model(it)
                assertTrue(model.state.value.generationInProgress)
                assertEquals("当前：原始照片，搭配生成中...",
                    root.findViewWithTag<TextView>("fitting.comparison.status").text.toString())
                val progress = root.findViewWithTag<ProgressBar>("fitting.comparison.progress")
                assertTrue(progress.isShown)
                assertTrue(progress.isIndeterminate)
                val start = SystemClock.elapsedRealtime()
                val absence = StandingAssessment(BodyPresence.ABSENT, StandingCue.NO_PERSON)
                val timeout = model.experienceClock.policy.absentMs
                for (elapsed in 0L..timeout step 1000) {
                    model.observeBody(absence, start + elapsed)
                    assertNull(model.checkExperienceTimeout(start + elapsed))
                }
                assertFalse(it.isFinishing)
            }
            awaitResult(child)
            onChild(child) {
                val root = it.window.decorView
                assertFalse(root.findViewWithTag<ProgressBar>("fitting.comparison.progress").isShown)
                assertTrue(root.findViewWithTag<TextView>("fitting.comparison.status").text.contains("并排对比"))
                root.findViewWithTag<Button>("fitting.end").performClick()
            }
            returned(scenario, "已清理")
        }
    }

    @Test fun thirtySecondsOfFreshSyntheticLostFramesEndsGuestWithoutCamera() {
        val host = localHost()
        ActivityScenario.launch(StandbyActivity::class.java).use { scenario ->
            val child = start(scenario, host)
            onChild(child) { it.window.decorView.findViewWithTag<Spinner>("synthetic.scenario").setSelection(SyntheticScenario.LOST.ordinal) }
            instrumentation.waitForIdleSync()
            onChild(child) { it.window.decorView.findViewWithTag<Button>("fitting.mock").performClick() }
            SystemClock.sleep(500)
            onChild(child) { assertFalse(it.isFinishing); assertTrue(model(it).experienceClock.active) }
            returned(scenario, "未检测到人体")
            assertTrue(child.isFinishing)
            evidence("absence-ended")
        }
    }

    @Test fun delayedMockCleanupReturnsAfterLocalClearAndDoesNotClaimServerAcknowledgement() {
        val host = localHost(true)
        ActivityScenario.launch(StandbyActivity::class.java).use { scenario ->
            val child = start(scenario, host); generate(child)
            lateinit var bytes: ByteArray
            val started = SystemClock.elapsedRealtime()
            onChild(child) {
                bytes = model(it).state.value.original!!
                it.window.decorView.findViewWithTag<Button>("fitting.end").performClick()
            }
            returned(scenario, "未确认")
            assertTrue("Local standby must not wait for the 10s MOCK cleanup reply", SystemClock.elapsedRealtime() - started < 9_000)
            assertTrue(bytes.all { it == 0.toByte() }); assertTrue(child.isFinishing)
            evidence("cleanup-unconfirmed")
        }
    }
}
