package com.smartfitting.mirror.vision

import com.smartfitting.mirror.contracts.*

/** Vendor-independent mapping, also testable on a host JVM. Do not clamp off-screen joints. */
object PoseMapper {
    data class PixelPoint(val x: Float, val y: Float, val confidence: Float)
    fun standingHint(points: Map<KeypointName, PixelPoint>, width: Int, height: Int): StandingHint =
        if (points.values.any { it.confidence >= .5f && it.x.isFinite() && it.y.isFinite() &&
                (it.x < 0 || it.y < 0 || it.x > width - 1 || it.y > height - 1) }) StandingHint.OUT_OF_FRAME
        else StandingHint.UNKNOWN

    fun map(points: Map<KeypointName, PixelPoint>, width: Int, height: Int): Keypoints {
        require(width > 1 && height > 1)
        val mapped = KeypointName.entries.associateWith { name ->
            points[name]?.takeIf {
                it.x.isFinite() && it.y.isFinite() && it.confidence in .5f..1f &&
                    it.x in 0f..(width - 1).toFloat() && it.y in 0f..(height - 1).toFloat()
            }?.let { Landmark(it.x / (width - 1), it.y / (height - 1), it.confidence) }
        }
        val result = Keypoints.fromMap(mapped)
        return if (result.state() == TrackingState.LOST) Keypoints() else result
    }
}
