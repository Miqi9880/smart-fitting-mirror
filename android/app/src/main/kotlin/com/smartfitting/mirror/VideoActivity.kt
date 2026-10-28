package com.smartfitting.mirror.video

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel

/** Rotation/background survival state; the MediaPlayer itself is always released on onStop. */
class VideoModel : ViewModel() {
    var index = -1
    var positionMs = 0
    var playing = false
    var volume = 1f
}

/**
 * Standalone host for [VideoPanel] (#7, FR-VID-01/02/05). Deliberately not linked from
 * StandbyActivity's disabled `home.video` entry yet — wiring that shared entry is
 * coordinated on the issue card first. Reviewers can launch it directly:
 * `adb shell am start -n com.smartfitting.mirror/.video.VideoActivity`
 *
 * Uses [FakeVideoRepository] only: synthetic test metadata plus program-generated local
 * clips, no network, no camera, no uploads. Leaving the screen (background, rotation,
 * exit, guest-session end via finish) stops and releases the player in onStop.
 */
class VideoActivity : ComponentActivity() {
    private val model: VideoModel by viewModels()
    private val repository = FakeVideoRepository()
    private lateinit var panel: VideoPanel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panel = VideoPanel(this)
        setContentView(panel)
        panel.onExit = { finish() }
        panel.onRetry = { load() }
        if (intent.getBooleanExtra(EXTRA_FAIL, false)) {
            repository.failNextWith = "测试：模拟视频加载失败，请重试。"
        }
        load()
    }

    override fun onStart() {
        super.onStart()
        panel.setVolumePreference(model.volume)
        if (model.index >= 0) {
            val index = model.index
            val position = model.positionMs
            val playing = model.playing
            model.index = -1
            panel.resumeClip(index, position, autoplay = playing)
        }
    }

    override fun onStop() {
        model.index = panel.currentIndex()
        model.positionMs = panel.currentPositionMs()
        model.playing = panel.isPlaying()
        model.volume = panel.volume()
        panel.releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        panel.releaseAll()
        super.onDestroy()
    }

    private fun load() {
        val query = if (intent.getBooleanExtra(EXTRA_EMPTY, false)) {
            VideoQuery(tags = listOf("无匹配测试标签"))
        } else {
            VideoQuery(
                garmentIds = intent.getStringArrayListExtra(EXTRA_GARMENTS)?.toList() ?: emptyList(),
                tags = intent.getStringArrayListExtra(EXTRA_TAGS)?.toList() ?: emptyList(),
            )
        }
        panel.present(repository.recommended(query))
    }

    companion object {
        const val EXTRA_TAGS = "com.smartfitting.mirror.video.TAGS"
        const val EXTRA_GARMENTS = "com.smartfitting.mirror.video.GARMENTS"
        /** Test hooks for emulator checks of the failure / empty states. */
        const val EXTRA_FAIL = "com.smartfitting.mirror.video.SIMULATE_FAILURE"
        const val EXTRA_EMPTY = "com.smartfitting.mirror.video.SIMULATE_EMPTY"
    }
}
