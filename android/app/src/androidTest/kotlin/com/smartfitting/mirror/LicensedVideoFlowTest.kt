package com.smartfitting.mirror

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Spinner
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.CapturedPhoto
import com.smartfitting.mirror.contracts.GarmentCategory
import com.smartfitting.mirror.network.FittingPhase
import com.smartfitting.mirror.video.VideoPanel
import com.smartfitting.mirror.video.VideoResult
import com.smartfitting.mirror.video.VideoOrigin
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual licensed clips served by loopback MOCK. No camera, private people or paid API. */
@RunWith(AndroidJUnit4::class)
class LicensedVideoFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun model(activity: TryOnActivity) = ViewModelProvider(activity)[TryOnModel::class.java]
    private fun host(name: String = "video_test_host", port: Int = 8018): String {
        val value = InstrumentationRegistry.getArguments().getString(name)
        assumeTrue("Explicit local video test server required", value != null)
        require(value in listOf("http://10.0.2.2:$port", "http://127.0.0.1:$port"))
        return value!!
    }
    private fun await(timeout: Long = 30000, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime()+timeout
        while (SystemClock.elapsedRealtime()<end) { if(check())return; SystemClock.sleep(80) }
        assertTrue("Video UI did not reach the expected state",check())
    }
    private fun <T: View> view(s: ActivityScenario<TryOnActivity>, tag: String): T? {
        var found: T?=null; s.onActivity { found=it.window.decorView.findViewWithTag(tag) }; return found
    }
    private fun text(s: ActivityScenario<TryOnActivity>, tag: String) = view<TextView>(s,tag)?.text?.toString().orEmpty()
    private fun click(s: ActivityScenario<TryOnActivity>, tag: String) = s.onActivity {
        val target=it.window.decorView.findViewWithTag<View>(tag)
        assertNotNull(tag,target); assertTrue(tag,target!!.performClick())
    }
    private fun panel(s: ActivityScenario<TryOnActivity>) = view<VideoPanel>(s,"fitting.video.panel")!!
    private fun launch(address: String): ActivityScenario<TryOnActivity> {
        val s=ActivityScenario.launch(TryOnActivity::class.java)
        s.onActivity { model(it).connect(address) }
        await { var ready=false; s.onActivity { ready=model(it).state.value.canSelect }; ready }
        return s
    }
    private fun open(s: ActivityScenario<TryOnActivity>) {
        click(s,"fitting.videos")
        try { await { view<View>(s,"video.card.0")!=null } }
        catch (failure: AssertionError) {
            evidence(s,"failed-open")
            throw AssertionError("Video open failed: ${text(s,"video.message")}",failure)
        }
        await { view<ImageView>(s,"video.cover.0")?.drawable!=null }
    }
    private fun play(s: ActivityScenario<TryOnActivity>, index: Int=0) {
        click(s,"video.card.$index")
        await { text(s,"video.status").contains("播放中") && panel(s).hasRenderedFrame() }
    }
    private fun evidence(s: ActivityScenario<TryOnActivity>, name: String) {
        if(InstrumentationRegistry.getArguments().getString("video_evidence")!="true")return
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        val image=instrumentation.uiAutomation.takeScreenshot()
        val path=File(instrumentation.targetContext.getExternalFilesDir(null),"evidence/video-$name.png")
        path.parentFile!!.mkdirs(); path.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
    }

    @Test fun fiveUpperReferencesAndTwoDressReferencesReallyRenderFromHttp() {
        launch(host()).use { s ->
            open(s)
            var ids=emptySet<String>()
            s.onActivity {
                val result=model(it).videoExperience.result as VideoResult.Items
                assertEquals(5,result.videos.size); assertTrue(result.videos.all { v -> v.origin==VideoOrigin.LICENSED })
                assertEquals(5,result.videos.map { v->v.videoId }.toSet().size)
                ids=result.videos.map { v->v.videoId }.toSet()
                assertEquals(0f,panel(s).volume(),0f)
            }
            evidence(s,"licensed-list")
            play(s); evidence(s,"landscape-playing")
            repeat(4) { click(s,"video.next"); await { text(s,"video.status").contains("播放中") && panel(s).hasRenderedFrame() } }
            click(s,"video.back"); click(s,"video.exit")
            s.onActivity { activity ->
                val dress=model(activity).state.value.garments.first { it.category==GarmentCategory.DRESS }
                activity.window.decorView.findViewWithTag<Spinner>("fitting.garments").setSelection(model(activity).state.value.garments.indexOf(dress))
            }
            instrumentation.waitForIdleSync()
            await { var selected=false; s.onActivity { selected=model(it).outfit.containsKey(GarmentCategory.DRESS) }; selected }
            open(s)
            s.onActivity {
                val result=model(it).videoExperience.result as VideoResult.Items
                assertTrue(result.videos.any { v->v.videoId=="pexels-7671653" })
                assertTrue(result.videos.any { v->v.videoId=="pexels-9511253" })
                assertEquals(7,(ids+result.videos.map { v->v.videoId }).size)
            }
            var dressIndices=emptyList<Int>()
            s.onActivity { dressIndices=(model(it).videoExperience.result as VideoResult.Items).videos.mapIndexedNotNull { index,v -> index.takeIf { v.videoId in setOf("pexels-7671653","pexels-9511253") } } }
            play(s,dressIndices.first()); evidence(s,"portrait-playing")
            click(s,"video.back"); play(s,dressIndices.last())
            click(s,"video.end.player")
            await { var ended=false; s.onActivity { ended=model(it).state.value.phase==FittingPhase.CLOSED }; ended }
            assertFalse(panel(s).isPlaying()); assertFalse(panel(s).hasRenderedFrame())
        }
    }

    @Test fun selectedStyleUpdatesTheCurrentGuestRecommendationsAndClearsAtEnd() {
        val address = host()
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        launch(address).use { s ->
            open(s)
            fun selectStyle(label: String) {
                click(s, "video.style.list")
                val bounds = Rect()
                await {
                    val option = automation.windows.asSequence().mapNotNull { it.root }
                        .flatMap { it.findAccessibilityNodeInfosByText(label).asSequence() }
                        .firstOrNull { it.isVisibleToUser && it.text?.toString() == label }
                    option?.getBoundsInScreen(bounds)
                    !bounds.isEmpty
                }
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                }
            }
            selectStyle("优雅")
            await {
                var ready = false
                s.onActivity {
                    val first = (model(it).videoExperience.result as? VideoResult.Items)?.videos?.firstOrNull()
                    ready = first?.videoId == "pexels-9511253" && first.matchesText.contains("已选风格：优雅")
                }
                ready
            }
            assertEquals("风格偏好 · 优雅", text(s, "video.style.list"))
            evidence(s,"style-preference")
            click(s, "video.exit")
            open(s)
            assertEquals("风格偏好 · 优雅", text(s, "video.style.list"))
            selectStyle("不限")
            await {
                var refreshed = false
                s.onActivity {
                    val memory = model(it).videoExperience
                    val result = memory.result as? VideoResult.Items
                    refreshed = memory.style == null &&
                        result?.videos?.none { video -> video.matchesText.contains("已选风格") } == true
                }
                refreshed
            }
            selectStyle("休闲")
            await {
                var ready = false
                s.onActivity {
                    val result = model(it).videoExperience.result as? VideoResult.Items
                    ready = result?.videos?.firstOrNull()?.matchesText?.contains("已选风格：休闲") == true
                }
                ready
            }
            click(s, "video.end.list")
            await {
                var ended = false
                s.onActivity { ended = model(it).state.value.phase == FittingPhase.CLOSED }
                ended
            }
            s.onActivity { assertNull(model(it).videoExperience.style) }
        }
    }

    @Test fun realResultOffersExplicitSemanticRecallAndRetainsTheComparison() {
        launch(host("video_semantic_host", 8025)).use { s ->
            open(s)
            assertNull(view<View>(s, "video.ai.list"))
            click(s, "video.exit")
            val image = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            val bytes = ByteArrayOutputStream().apply { image.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
            image.recycle()
            s.onActivity { model(it).controller!!.generate(CapturedPhoto("semantic-test", 1, 200, 300, "image/png", bytes)) }
            await {
                var ready = false
                s.onActivity { ready = model(it).state.value.phase == FittingPhase.SUCCEEDED }
                ready
            }
            open(s)
            assertTrue(text(s, "video.ai.summary").isEmpty())
            click(s, "video.ai.list")
            await(46_000) { text(s, "video.ai.summary").contains("AI 识别标签") }
            s.onActivity {
                val result = model(it).videoExperience.result as VideoResult.Items
                assertTrue(result.videos.first().matchesText.contains("AI 识别"))
                assertNotNull(model(it).state.value.result)
            }
            click(s, "video.end.list")
            await {
                var ended = false
                s.onActivity { ended = model(it).state.value.phase == FittingPhase.CLOSED }
                ended
            }
            s.onActivity { assertNull(model(it).videoExperience.semanticNote) }
        }
    }

    @Test fun pauseRotationBackgroundAndReturnPreserveOriginalAndOutfitWithoutAutoplay() {
        launch(host()).use { s ->
            val image=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            val bytes=ByteArrayOutputStream().apply { image.compress(Bitmap.CompressFormat.PNG,100,this) }.toByteArray(); image.recycle()
            s.onActivity { model(it).controller!!.generate(CapturedPhoto("generated-test",1,200,300,"image/png",bytes)) }
            await { var ready=false; s.onActivity { ready=model(it).state.value.phase==FittingPhase.SUCCEEDED }; ready }
            s.onActivity {
                val m=model(it); val hat=m.state.value.garments.first { it.category==GarmentCategory.HEADWEAR }
                m.controller!!.select(hat.garmentId)
            }
            await { var ready=false; s.onActivity { ready=model(it).outfit.containsKey(GarmentCategory.HEADWEAR) }; ready }
            // A real user must expand the controls before selecting a target. A GONE
            // Spinner need not dispatch its selection callback until its next layout.
            click(s,"fitting.fit.toggle")
            instrumentation.waitForIdleSync()
            s.onActivity {
                val target=it.window.decorView.findViewWithTag<Spinner>("fitting.fit.target")
                target.setSelection((0 until target.adapter.count).first { index -> target.adapter.getItem(index)==GarmentCategory.UPPER_BODY.label })
            }
            await { var ready=false; s.onActivity { ready=model(it).fitTarget==GarmentCategory.UPPER_BODY }; ready }
            s.onActivity { assertTrue(model(it).fitTargetExplicit) }
            var original=byteArrayOf(); var result=byteArrayOf(); var job=""; var garmentIds=emptySet<String>()
            s.onActivity { val m=model(it); original=m.state.value.original!!.copyOf(); result=m.state.value.result!!.copyOf(); job=m.state.value.job!!.jobId; garmentIds=m.outfit.values.map { v->v.garmentId }.toSet() }
            open(s); play(s)
            click(s,"video.toggle"); assertEquals("已暂停",text(s,"video.status"))
            s.onActivity {
                val seek=it.window.decorView.findViewWithTag<SeekBar>("video.volume")
                val t=SystemClock.uptimeMillis(); val x=seek.width*.35f; val y=seek.height*.5f
                seek.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0))
                seek.dispatchTouchEvent(MotionEvent.obtain(t,t+20,MotionEvent.ACTION_UP,x,y,0))
            }
            val chosen=panel(s).volume(); assertTrue(chosen in .15f.. .55f)
            s.recreate()
            await { text(s,"video.status")=="已暂停" }
            assertEquals(chosen,panel(s).volume(),.02f)
            s.moveToState(Lifecycle.State.CREATED); assertFalse(panel(s).isPlaying())
            s.moveToState(Lifecycle.State.RESUMED); await { text(s,"video.status")=="已暂停" }
            click(s,"video.back"); click(s,"video.exit")
            s.onActivity {
                val m=model(it); assertEquals(job,m.state.value.job!!.jobId)
                assertArrayEquals(original,m.state.value.original); assertArrayEquals(result,m.state.value.result)
                assertEquals(garmentIds,m.outfit.values.map { v->v.garmentId }.toSet())
                assertEquals(GarmentCategory.UPPER_BODY,m.fitTarget)
                m.controller!!.end()
            }
        }
    }

    @Test fun sessionTimeoutWhilePlayingStopsMediaAndClearsGuestMemory() {
        launch(host()).use { s ->
            open(s); play(s)
            s.onActivity { model(it).checkExperienceTimeout(SystemClock.elapsedRealtime()+model(it).experienceClock.policy.idleMs) }
            await { var ended=false; s.onActivity { ended=model(it).state.value.phase==FittingPhase.CLOSED }; ended }
            assertFalse(panel(s).hasRenderedFrame()); assertFalse(panel(s).isPlaying())
            s.onActivity { assertNull(model(it).videoExperience.result); assertFalse(model(it).videoExperience.open) }
        }
    }

    @Test fun homeVideoEntryUsesGuestSessionWithoutCameraAndReturnsToStandby() {
        val address=host()
        ActivityScenario.launch(StandbyActivity::class.java).use { home ->
            val monitor=instrumentation.addMonitor(TryOnActivity::class.java.name,null,false)
            try {
                home.onActivity { ViewModelProvider(it)[StandbyModel::class.java].baseUrl=address; it.window.decorView.findViewWithTag<Button>("home.video").performClick() }
                val child=monitor.waitForActivityWithTimeout(5000) as? TryOnActivity
                assertNotNull(child)
                await {
                    var ready=false; instrumentation.runOnMainSync { ready=child!!.window.decorView.findViewWithTag<View>("video.card.0")!=null }; ready
                }
                instrumentation.runOnMainSync {
                    assertFalse(model(child!!).running)
                    child.window.decorView.findViewWithTag<Button>("video.end.list").performClick()
                }
                await { var ready=false; home.onActivity { ready=!ViewModelProvider(it)[StandbyModel::class.java].waitingGuest }; ready }
                home.onActivity { assertTrue(it.window.decorView.findViewWithTag<Button>("home.video").isEnabled); assertFalse(it.sensingActive) }
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test fun metadataFailureShowsErrorAndExplicitRetryRecovers() {
        launch(host("video_failure_host",8019)).use { s ->
            click(s,"fitting.videos"); await { text(s,"video.message").contains("失败") }
            assertNull(view<View>(s,"video.card.0")); click(s,"video.retry")
            await { view<View>(s,"video.card.0")!=null }
        }
    }

    @Test fun emptyLibraryStaysEmptyAndExpiredSessionClearsVideo() {
        launch(host("video_empty_host",8022)).use { s ->
            click(s,"fitting.videos"); await { text(s,"video.message").contains("暂无") }
            assertNull(view<View>(s,"video.card.0")); assertFalse(panel(s).isPlaying())
            click(s,"video.state.back"); assertFalse(view<View>(s,"fitting.video.panel")!!.visibility==View.VISIBLE)
        }
        launch(host("video_expired_host",8021)).use { s ->
            click(s,"fitting.videos")
            await { var closed=false; s.onActivity { closed=model(it).state.value.phase==FittingPhase.CLOSED }; closed }
            assertFalse(panel(s).isPlaying()); s.onActivity { assertNull(model(it).videoExperience.result) }
        }
    }

    @Test fun backgroundDuringQueryRequiresExplicitRetryAndDropsLateResults() {
        launch(host("video_delay_host",8023)).use { s ->
            click(s,"fitting.videos")
            await { text(s,"video.message").contains("正在") }
            s.moveToState(Lifecycle.State.CREATED)
            s.moveToState(Lifecycle.State.RESUMED)
            await { text(s,"video.message").contains("加载已暂停") }
            // Let the abandoned delayed server response arrive; it must not replace the state.
            SystemClock.sleep(11_000)
            assertTrue(text(s,"video.message").contains("加载已暂停"))
            assertNull(view<View>(s,"video.card.0"))
            click(s,"video.retry")
            await { view<View>(s,"video.card.0")!=null }
        }
    }

    @Test fun unavailableMediaShowsRecoverableErrorWithoutFakeFallback() {
        launch(host("video_media_failure_host",8024)).use { s ->
            open(s); click(s,"video.card.0")
            await { text(s,"video.toggle")=="重试" }
            assertFalse(panel(s).hasRenderedFrame()); assertFalse(panel(s).isPlaying())
            assertTrue(text(s,"video.status").contains("无法播放") || text(s,"video.status").contains("超时"))
            click(s,"video.next")
            await { panel(s).hasRenderedFrame() && panel(s).isPlaying() }
            assertTrue(text(s,"video.status").contains("授权参考片"))
        }
    }

    @Test fun enlargedFontsAndOrientationKeepVideoControlsReachable() {
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
        }
        val oldScale=shell("settings get system font_scale").takeIf { it.toFloatOrNull()!=null } ?: "1.0"
        try {
            for (scale in listOf("1.3","1.5")) {
                shell("settings put system font_scale $scale")
                launch(host()).use { s ->
                    open(s); play(s)
                    for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                        s.onActivity { it.requestedOrientation=orientation }
                        await {
                            var correct=false; s.onActivity { correct=it.resources.configuration.orientation ==
                                if(orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT }
                            correct && view<Button>(s,"video.toggle")!=null && (text(s,"video.status")=="已暂停" || panel(s).isPlaying())
                        }
                        s.onActivity {
                            for (tag in listOf("video.toggle","video.next","video.back","video.end.player")) {
                                val button=it.window.decorView.findViewWithTag<Button>(tag)
                                assertTrue(button.height>=48*it.resources.displayMetrics.density)
                                assertTrue(button.height>=button.layout.height+button.compoundPaddingTop+button.compoundPaddingBottom)
                            }
                        }
                        evidence(s,"font-$scale-$orientation")
                    }
                }
            }
        } finally { shell("settings put system font_scale $oldScale") }
    }
}
