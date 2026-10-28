package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.VisionFrame
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Approximate head ellipse in raw RGB pixels. No face detector, segmentation or stale pose. */
object HeadProtection {
    data class Region(val x: Float, val y: Float, val radiusX: Float, val radiusY: Float, val angleDegrees: Float)

    fun region(frame: VisionFrame): Region? {
        val right = frame.keypoints.rightEar ?: return null
        val left = frame.keypoints.leftEar ?: return null
        if (right.confidence < .7f || left.confidence < .7f) return null
        val dx = (left.x - right.x) * (frame.image.width - 1)
        val dy = (left.y - right.y) * (frame.image.height - 1)
        val span = hypot(dx, dy)
        // Degenerate/crossed/steep side poses must not punch a large hole into the garment.
        if (dx < 8f || span > frame.image.width * .5f || abs(dy) > dx) return null
        val x = .5f + (right.x + left.x) * .5f * (frame.image.width - 1)
        val earY = .5f + (right.y + left.y) * .5f * (frame.image.height - 1)
        // Head-sized cut: the top covers the hair, the bottom stops at the chin. The former
        // 0.88*span bottom reached below the chin into the collar and sliced it flat.
        val top = earY - span * .70f
        var bottom = earY + span * .62f
        // An inflated ear span inflates that chin estimate too, so also clamp the cut above
        // the shoulder line: no neckline starts above it, so the collar always survives.
        val shoulderRight = frame.keypoints.rightShoulder
        val shoulderLeft = frame.keypoints.leftShoulder
        if (shoulderRight != null && shoulderLeft != null &&
            shoulderRight.confidence >= .5f && shoulderLeft.confidence >= .5f) {
            val shoulderSpan = hypot((shoulderLeft.x - shoulderRight.x) * (frame.image.width - 1),
                (shoulderLeft.y - shoulderRight.y) * (frame.image.height - 1))
            if (shoulderSpan > 8f) bottom = min(bottom,
                .5f + (shoulderRight.y + shoulderLeft.y) * .5f * (frame.image.height - 1) - shoulderSpan * .06f)
        }
        // A clamp that collapses the region is a pose we cannot protect; keep the garment whole.
        if (bottom - top < span * .3f) return null
        return Region(x, (top + bottom) * .5f, span * .58f, (bottom - top) * .5f,
            Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat())
    }
}
