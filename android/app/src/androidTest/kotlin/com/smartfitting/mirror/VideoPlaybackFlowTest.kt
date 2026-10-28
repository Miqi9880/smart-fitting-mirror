package com.smartfitting.mirror

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.video.ClipOrientation
import com.smartfitting.mirror.video.FakeVideoRepository
import com.smartfitting.mirror.video.GeneratedClips
import com.smartfitting.mirror.video.VideoActivity
import com.smartfitting.mirror.video.VideoItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator checks for the #7 video card + playback flow. Everything here is local and
 * synthetic: FakeVideoRepository metadata plus program-generated clips, no network, no
 * camera, no RGB sensing.
 */
@RunWith(AndroidJUnit4::class)
class VideoPlaybackFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val catalog = FakeVideoRepository.defaultCatalog().filter { it.onShelf }

    private fun launch(configure: Intent.() -> Unit = {}): ActivityScenario<VideoActivity> {
        val intent = Intent(instrumentation.targetContext, VideoActivity::class.java).apply(configure)
        return ActivityScenario.launch(intent)
    }

    private fun <T : View> view(scenario: ActivityScenario<VideoActivity>, tag: String): T? {
        var found: T? = null
        scenario.onActivity { found = it.window.decorView.findViewWithTag(tag) }
        return found
    }

    private fun text(scenario: ActivityScenario<VideoActivity>, tag: String): String? =
        view<TextView>(scenario, tag)?.text?.toString()

    private fun click(scenario: ActivityScenario<VideoActivity>, tag: String) {
        val target = view<View>(scenario, tag)
        assertNotNull("找不到控件 $tag", target)
        instrumentation.runOnMainSync { target!!.performClick() }
    }

    private fun await(timeoutMs: Long = 60_000, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (check()) return
            SystemClock.sleep(100)
        }
        assertTrue("等待界面状态超时", check())
    }

    @Test fun listShowsClearlyMarkedTestCards() {
        launch().use { scenario ->
            await { view<View>(scenario, "video.card.0") != null }
            val note = text(scenario, "video.listnote")
            assertTrue(note!!.contains("测试数据"))
            assertEquals(catalog.size, countCards(scenario))
            val card = view<View>(scenario, "video.card.0")!!
            assertTrue(card.contentDescription.toString().contains("测试数据"))
            assertTrue(card.contentDescription.toString().contains("时长"))
        }
    }

    @Test fun cardTapPlaysThenPauseResumeNextAndBack() {
        launch().use { scenario ->
            await { view<View>(scenario, "video.card.0") != null }
            click(scenario, "video.card.0")
            await { text(scenario, "video.status")?.contains("播放中") == true }
            assertEquals(catalog[0].title, text(scenario, "video.title"))
            assertTrue(text(scenario, "video.badge")!!.contains("测试数据"))
            assertEquals("暂停", text(scenario, "video.toggle"))

            click(scenario, "video.toggle")
            await { text(scenario, "video.status") == "已暂停" }
            assertEquals("继续", text(scenario, "video.toggle"))

            click(scenario, "video.toggle")
            await { text(scenario, "video.status")?.contains("播放中") == true }

            click(scenario, "video.next")
            await {
                text(scenario, "video.title") == catalog[1].title &&
                    text(scenario, "video.status")?.contains("播放中") == true
            }

            click(scenario, "video.back")
            await { view<View>(scenario, "video.card.0") != null && view<View>(scenario, "video.player")?.visibility != View.VISIBLE }
            await { view<View>(scenario, "video.exit") != null }
        }
    }

    @Test fun emptyQueryShowsReadableMessageAndReturnPath() {
        launch({ putExtra(VideoActivity.EXTRA_EMPTY, true) }).use { scenario ->
            await { view<View>(scenario, "video.message") != null }
            assertTrue(text(scenario, "video.message")!!.contains("暂无相关视频"))
            assertNotNull(view<View>(scenario, "video.state.back"))
        }
    }

    @Test fun simulatedFailureShowsMessageAndRetryRecovers() {
        launch({ putExtra(VideoActivity.EXTRA_FAIL, true) }).use { scenario ->
            await { view<View>(scenario, "video.message") != null }
            assertTrue(text(scenario, "video.message")!!.contains("失败"))
            click(scenario, "video.retry")
            await { view<View>(scenario, "video.card.0") != null }
        }
    }

    @Test fun backgroundReleaseAndForegroundResumeRestorePlayback() {
        launch().use { scenario ->
            await { view<View>(scenario, "video.card.0") != null }
            click(scenario, "video.card.0")
            await { text(scenario, "video.status")?.contains("播放中") == true }

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { text(scenario, "video.status")?.contains("播放中") == true }

            // Real configuration change (rotation) retains the VideoModel and must resume playback.
            var first: VideoActivity? = null
            scenario.onActivity { first = it }
            instrumentation.runOnMainSync {
                first!!.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await {
                var current: VideoActivity? = null
                scenario.onActivity { current = it }
                current !== first
            }
            await { text(scenario, "video.status")?.contains("播放中") == true }
            assertEquals(catalog[0].title, text(scenario, "video.title"))
        }
    }

    @Test fun generatedPortraitClipIsActuallyPlayable() {
        val context = instrumentation.targetContext
        val item = VideoItem(
            videoId = "test-portrait-probe",
            title = "测试·竖屏探针",
            durationSeconds = 2,
            tags = listOf("测试"),
            orientation = ClipOrientation.PORTRAIT,
        )
        val file = GeneratedClips.ensure(context, item)
        assertTrue(file.exists() && file.length() > 0)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            assertTrue("生成的短片时长应接近2秒，实际 $duration", duration in 1500..2600)
            assertEquals(360, width)
            assertEquals(640, height)
        } finally {
            retriever.release()
        }
    }

    private fun countCards(scenario: ActivityScenario<VideoActivity>): Int {
        var count = 0
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            while (decor.findViewWithTag<View>("video.card.$count") != null) count++
        }
        return count
    }
}
