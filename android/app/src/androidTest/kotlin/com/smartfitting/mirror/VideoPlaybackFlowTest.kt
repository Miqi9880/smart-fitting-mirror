package com.smartfitting.mirror

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
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
import com.smartfitting.mirror.video.VideoPanel
import com.smartfitting.mirror.video.VideoResult
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

    private fun panel(scenario: ActivityScenario<VideoActivity>): VideoPanel {
        var found: VideoPanel? = null
        scenario.onActivity {
            found = it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as VideoPanel
        }
        return found!!
    }

    private fun focus(scenario: ActivityScenario<VideoActivity>, tag: String) {
        val target = view<View>(scenario, tag)
        assertNotNull("找不到控件 $tag", target)
        key(KeyEvent.KEYCODE_TAB)
        instrumentation.runOnMainSync { assertTrue(target!!.requestFocus()) }
    }

    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)

    private fun awaitFocus(scenario: ActivityScenario<VideoActivity>, tag: String) {
        await { view<View>(scenario, tag)?.isFocused == true }
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

    @Test fun cardReadsSourceAndMatchOnceWithoutDuplicateChildText() {
        launch().use { scenario ->
            val item = VideoItem(
                videoId = "a11y-card",
                title = "测试·读屏卡片",
                durationSeconds = 65,
                tags = listOf("上衣", "黑色"),
                sourceText = "测试作者 · 本地合成来源\n仅用于无障碍测试的授权说明",
                matchesText = "品类：上衣 · 颜色：黑色",
            )
            val videoPanel = panel(scenario)
            scenario.onActivity { videoPanel.present(VideoResult.Items(listOf(item))) }
            await { view<View>(scenario, "video.card.0") != null }
            val card = view<ViewGroup>(scenario, "video.card.0")!!
            val description = card.contentDescription.toString()
            assertTrue(description.contains(item.title))
            assertTrue(description.contains("1:05"))
            assertTrue(description.contains("相关依据：${item.matchesText}"))
            assertTrue(description.contains("来源：测试作者 · 本地合成来源"))
            assertFalse(description.contains("仅用于无障碍测试的授权说明"))
            for (child in 0 until card.childCount) {
                val text = card.getChildAt(child) as? TextView ?: continue
                if (text is Button) continue
                assertEquals("卡片子文本不应与汇总描述重复朗读", View.IMPORTANT_FOR_ACCESSIBILITY_NO, text.importantForAccessibility)
            }
            val source = view<Button>(scenario, "video.source.0")!!
            assertTrue(source.contentDescription.contains(item.title))
            assertTrue(source.isFocusable && source.isClickable)
        }
    }

    @Test fun keyboardFocusFollowsCardPlayerControlsAndBackToOriginalCard() {
        launch().use { scenario ->
            await { view<View>(scenario, "video.card.1") != null }
            focus(scenario, "video.card.1")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { panel(scenario).isPlayerVisible() }
            awaitFocus(scenario, "video.toggle")

            key(KeyEvent.KEYCODE_DPAD_LEFT)
            awaitFocus(scenario, "video.back")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus(scenario, "video.toggle")
            key(KeyEvent.KEYCODE_TAB)
            awaitFocus(scenario, "video.next")
            key(KeyEvent.KEYCODE_TAB)
            awaitFocus(scenario, "video.volume")

            val volume = view<SeekBar>(scenario, "video.volume")!!
            val node = volume.createAccessibilityNodeInfo()
            assertEquals("音量", node.contentDescription.toString())
            assertNotNull("音量应暴露当前数值给读屏", node.rangeInfo)
            assertEquals(volume.progress.toFloat(), node.rangeInfo.current, 0f)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view<TextView>(scenario, "video.volume.label")!!.importantForAccessibility)
            for (tag in listOf("video.back", "video.toggle", "video.next")) {
                val button = view<Button>(scenario, tag)!!
                assertTrue(button.text.isNotBlank())
                assertNull("按钮已有可见名称，不应再叠加描述：$tag", button.contentDescription)
            }

            focus(scenario, "video.back")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { !panel(scenario).isPlayerVisible() }
            awaitFocus(scenario, "video.card.1")
            key(KeyEvent.KEYCODE_TAB)
            awaitFocus(scenario, "video.card.2")
        }
    }

    @Test fun keyboardFocusIsRecreatedInsideVisiblePlayerAfterRotationAndBackground() {
        launch().use { scenario ->
            await { view<View>(scenario, "video.card.0") != null }
            focus(scenario, "video.card.0")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus(scenario, "video.toggle")
            val removed = view<View>(scenario, "video.toggle")!!

            scenario.recreate()
            await { panel(scenario).isPlayerVisible() }
            awaitFocus(scenario, "video.toggle")
            assertNotSame(removed, view<View>(scenario, "video.toggle"))
            assertFalse(removed.isFocused)
            assertNotNull(view<View>(scenario, "video.player")!!.findFocus())
            assertNull(view<View>(scenario, "video.list")!!.findFocus())

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { panel(scenario).isPlayerVisible() }
            awaitFocus(scenario, "video.toggle")
            assertNotNull(view<View>(scenario, "video.player")!!.findFocus())
            assertNull(view<View>(scenario, "video.list")!!.findFocus())
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
            key(KeyEvent.KEYCODE_TAB)
            awaitFocus(scenario, "video.state.back")
        }
    }

    @Test fun simulatedFailureShowsMessageAndRetryRecovers() {
        launch({ putExtra(VideoActivity.EXTRA_FAIL, true) }).use { scenario ->
            await { view<View>(scenario, "video.message") != null }
            assertTrue(text(scenario, "video.message")!!.contains("失败"))
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, view<TextView>(scenario, "video.message")!!.accessibilityLiveRegion)
            key(KeyEvent.KEYCODE_TAB)
            awaitFocus(scenario, "video.retry")
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
                first!!.requestedOrientation = if (first!!.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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
