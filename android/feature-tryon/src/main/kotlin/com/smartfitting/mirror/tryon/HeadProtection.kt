package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.VisionFrame
import kotlin.math.abs
import kotlin.math.hypot

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
        val y = .5f + (right.y + left.y) * .5f * (frame.image.height - 1)
        return Region(x, y - span * .08f, span * .64f, span * .88f,
            Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat())
    }
}
