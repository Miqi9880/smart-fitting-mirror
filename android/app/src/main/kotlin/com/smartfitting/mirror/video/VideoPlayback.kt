package com.smartfitting.mirror.video

/**
 * Pure-Kotlin playback state machine for the video panel. It owns no Android player;
 * the view layer (VideoPanel) mirrors these transitions onto a MediaPlayer, which keeps
 * every rule here unit-testable on the JVM.
 */
enum class PlaybackStatus { IDLE, LOADING, PLAYING, PAUSED, COMPLETED, ERROR }

data class PlaybackSnapshot(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val index: Int = -1,
    /** 0f..1f, survives release so a reopened clip keeps the guest's volume. */
    val volume: Float = 0f,
    val message: String = "",
)

class PlaybackController(private val items: List<VideoItem>) {
    var snapshot = PlaybackSnapshot()
        private set
    var listener: ((PlaybackSnapshot) -> Unit)? = null

    val current: VideoItem? get() = items.getOrNull(snapshot.index)
    val hasNext: Boolean get() = snapshot.index in 0 until items.size - 1

    /** A card tap starts loading that clip; playback begins only after onPrepared. */
    fun select(index: Int) {
        if (index !in items.indices) return
        update(snapshot.copy(status = PlaybackStatus.LOADING, index = index, message = ""))
    }

    fun onPrepared() {
        if (snapshot.status == PlaybackStatus.LOADING) update(snapshot.copy(status = PlaybackStatus.PLAYING))
    }

    fun pause() {
        if (snapshot.status == PlaybackStatus.PLAYING) update(snapshot.copy(status = PlaybackStatus.PAUSED))
    }

    fun resume() {
        if (snapshot.status == PlaybackStatus.PAUSED) update(snapshot.copy(status = PlaybackStatus.PLAYING))
    }

    /** Replay from the top once a clip has finished. */
    fun replay() {
        if (snapshot.status == PlaybackStatus.COMPLETED && snapshot.index in items.indices) {
            update(snapshot.copy(status = PlaybackStatus.LOADING, message = ""))
        }
    }

    fun toggle() {
        when (snapshot.status) {
            PlaybackStatus.PLAYING -> pause()
            PlaybackStatus.PAUSED -> resume()
            PlaybackStatus.COMPLETED -> replay()
            else -> Unit
        }
    }

    /** Advances to the next clip; returns false (and stays put) at the end of the list. */
    fun next(): Boolean {
        if (!hasNext) return false
        select(snapshot.index + 1)
        return true
    }

    fun onCompleted() {
        if (snapshot.status == PlaybackStatus.PLAYING || snapshot.status == PlaybackStatus.PAUSED) {
            update(snapshot.copy(status = PlaybackStatus.COMPLETED))
        }
    }

    fun onError(message: String) {
        if (snapshot.status == PlaybackStatus.IDLE) return
        update(snapshot.copy(status = PlaybackStatus.ERROR, message = message))
    }

    fun setVolume(volume: Float) {
        if (!volume.isFinite()) return
        val clamped = volume.coerceIn(0f, 1f)
        if (clamped != snapshot.volume) update(snapshot.copy(volume = clamped))
    }

    /** Stops and releases everything; volume preference is kept for the next clip. */
    fun release() {
        if (snapshot.status == PlaybackStatus.IDLE) return
        update(PlaybackSnapshot(volume = snapshot.volume))
    }

    private fun update(next: PlaybackSnapshot) {
        snapshot = next
        listener?.invoke(next)
    }
}
