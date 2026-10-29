package com.smartfitting.mirror.video

import org.junit.Assert.*
import org.junit.Test

class PlaybackControllerTest {
    @Test fun startsMutedAndRejectsNonFiniteVolume() {
        val controller=controller()
        assertEquals(0f,controller.snapshot.volume,0f)
        controller.setVolume(Float.NaN);controller.setVolume(Float.POSITIVE_INFINITY)
        assertEquals(0f,controller.snapshot.volume,0f)
    }
    private val clips = listOf(
        VideoItem("a", "测试·甲", 3, listOf("休闲")),
        VideoItem("b", "测试·乙", 3, listOf("街头"), orientation = ClipOrientation.PORTRAIT),
    )
    private fun controller() = PlaybackController(clips)

    @Test fun cardTapLoadsThenPreparedStartsPlayback() {
        val controller = controller()
        controller.select(0)
        assertEquals(PlaybackStatus.LOADING, controller.snapshot.status)
        controller.onPrepared()
        assertEquals(PlaybackStatus.PLAYING, controller.snapshot.status)
        assertEquals("a", controller.current?.videoId)
    }

    @Test fun pauseResumeToggleRoundTrips() {
        val controller = controller()
        controller.select(0)
        controller.onPrepared()
        controller.toggle()
        assertEquals(PlaybackStatus.PAUSED, controller.snapshot.status)
        controller.toggle()
        assertEquals(PlaybackStatus.PLAYING, controller.snapshot.status)
    }

    @Test fun nextAdvancesAndStopsAtTheEndOfTheList() {
        val controller = controller()
        controller.select(0)
        controller.onPrepared()
        assertTrue(controller.next())
        assertEquals(1, controller.snapshot.index)
        assertEquals(PlaybackStatus.LOADING, controller.snapshot.status)
        controller.onPrepared()
        assertFalse("最后一条后没有下一条", controller.next())
        assertEquals(1, controller.snapshot.index)
        assertEquals(PlaybackStatus.PLAYING, controller.snapshot.status)
    }

    @Test fun completionAllowsReplayButNotPause() {
        val controller = controller()
        controller.select(0)
        controller.onPrepared()
        controller.onCompleted()
        assertEquals(PlaybackStatus.COMPLETED, controller.snapshot.status)
        controller.toggle()
        assertEquals(PlaybackStatus.LOADING, controller.snapshot.status)
        controller.onPrepared()
        assertEquals(PlaybackStatus.PLAYING, controller.snapshot.status)
    }

    @Test fun errorKeepsIndexAndCarriesAReadableMessage() {
        val controller = controller()
        controller.select(1)
        controller.onError("无法播放")
        assertEquals(PlaybackStatus.ERROR, controller.snapshot.status)
        assertEquals(1, controller.snapshot.index)
        assertEquals("无法播放", controller.snapshot.message)
    }

    @Test fun releaseReturnsToIdleButKeepsTheGuestsVolume() {
        val controller = controller()
        controller.setVolume(0.3f)
        controller.select(0)
        controller.onPrepared()
        controller.release()
        assertEquals(PlaybackStatus.IDLE, controller.snapshot.status)
        assertEquals(-1, controller.snapshot.index)
        assertNull(controller.current)
        assertEquals(0.3f, controller.snapshot.volume, 0.001f)
        controller.select(1)
        assertEquals(0.3f, controller.snapshot.volume, 0.001f)
    }

    @Test fun volumeIsClampedToAudibleRange() {
        val controller = controller()
        controller.setVolume(1.4f)
        assertEquals(1f, controller.snapshot.volume, 0.001f)
        controller.setVolume(-0.2f)
        assertEquals(0f, controller.snapshot.volume, 0.001f)
    }

    @Test fun outOfRangeSelectionIsIgnored() {
        val controller = controller()
        controller.select(5)
        assertEquals(PlaybackStatus.IDLE, controller.snapshot.status)
        controller.select(-1)
        assertEquals(PlaybackStatus.IDLE, controller.snapshot.status)
    }

    @Test fun listenerObservesEveryTransition() {
        val controller = controller()
        val seen = mutableListOf<PlaybackStatus>()
        controller.listener = { seen.add(it.status) }
        controller.select(0)
        controller.onPrepared()
        controller.pause()
        controller.release()
        assertEquals(
            listOf(PlaybackStatus.LOADING, PlaybackStatus.PLAYING, PlaybackStatus.PAUSED, PlaybackStatus.IDLE),
            seen,
        )
    }
}
