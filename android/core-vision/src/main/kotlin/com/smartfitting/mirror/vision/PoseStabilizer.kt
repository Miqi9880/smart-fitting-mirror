package com.smartfitting.mirror.vision

import com.smartfitting.mirror.contracts.Keypoints
import com.smartfitting.mirror.contracts.Landmark

/**
 * Adapts the exponential-smoothing block in J0KER-N/SmartMirror's
 * MediaPipeBodyTracker.kt (11b667b) to current-frame 2D landmarks.
 * Course reuse permission and changes: third_party/SmartMirror/NOTICE.md.
 * One instance belongs to one camera run; this does not infer missing points.
 */
internal class PoseStabilizer {
    private var previous = Keypoints()
    private var timestampMs: Long? = null
    private var width = 0
    private var height = 0

    fun update(points: Keypoints, timestampMs: Long, width: Int, height: Int): Keypoints {
        require(timestampMs >= 0 && width > 1 && height > 1)
        val elapsed = this.timestampMs?.let { timestampMs - it }
        val continuous = elapsed != null && elapsed in 1L..MAX_GAP_MS &&
            width == this.width && height == this.height
        val history = if (continuous) previous.asMap() else emptyMap()
        val filtered = Keypoints.fromMap(points.asMap().mapValues { (name, point) ->
            val current = point?.takeIf { it.confidence >= .5f } ?: return@mapValues null
            val prior = history[name]
            if (prior == null) current else Landmark(
                prior.x * (1f - SMOOTHING_FACTOR) + current.x * SMOOTHING_FACTOR,
                prior.y * (1f - SMOOTHING_FACTOR) + current.y * SMOOTHING_FACTOR,
                current.confidence,
            )
        })
        // Dropped joints stay null immediately and cannot reappear from history.
        previous = filtered
        this.timestampMs = timestampMs
        this.width = width
        this.height = height
        return filtered
    }

    fun reset() {
        previous = Keypoints()
        timestampMs = null
        width = 0
        height = 0
    }

    private companion object {
        const val SMOOTHING_FACTOR = .6f
        const val MAX_GAP_MS = 250L
    }
}
