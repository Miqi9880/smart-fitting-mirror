package com.smartfitting.mirror.vision

/** Main-thread RGB output liveness. Uses elapsed time, never a camera timestamp or pose state. */
internal class FrameWatchdog(
    private val nowMs: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val startupTimeoutMs: Long = 15_000,
    private val frameTimeoutMs: Long = 2_000,
) {
    init { require(startupTimeoutMs > 0 && frameTimeoutMs > 0) }

    private class Run(val startedAt: Long, val onTimeout: (waitingForFirstFrame: Boolean) -> Unit) {
        var lastFrameAt: Long? = null
        lateinit var check: Runnable
    }
    private var run: Run? = null

    fun start(onTimeout: (waitingForFirstFrame: Boolean) -> Unit) {
        stop()
        val current = Run(nowMs(), onTimeout)
        current.check = Runnable {
            if (run !== current) return@Runnable
            val first = current.lastFrameAt == null
            val remaining = (current.lastFrameAt ?: current.startedAt) +
                (if (first) startupTimeoutMs else frameTimeoutMs) - nowMs()
            if (remaining > 0) postDelayed(current.check, remaining)
            else {
                run = null
                current.onTimeout(first)
            }
        }
        run = current
        postDelayed(current.check, startupTimeoutMs)
    }

    fun frameReceived() {
        val current = run ?: return
        val first = current.lastFrameAt == null
        current.lastFrameAt = nowMs()
        // Switch to the short deadline immediately, rather than waiting out startup.
        if (first) {
            removeCallbacks(current.check)
            postDelayed(current.check, frameTimeoutMs)
        }
    }

    fun stop() {
        val previous = run
        run = null
        previous?.let { removeCallbacks(it.check) }
    }
}
