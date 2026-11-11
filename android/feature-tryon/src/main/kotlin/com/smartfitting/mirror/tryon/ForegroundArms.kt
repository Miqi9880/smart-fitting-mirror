package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.KeypointName
import com.smartfitting.mirror.contracts.TrackingState
import com.smartfitting.mirror.contracts.VisionFrame
import com.smartfitting.mirror.vision.ImagePoseHints
import kotlin.math.hypot

/** Approximate exposed arms from image-relative Z when no aligned depth is available. */
internal object ForegroundArms {
    data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val radius: Float)

    fun segments(frame: VisionFrame, hints: ImagePoseHints?): List<Segment> {
        if (frame.trackingState != TrackingState.TRACKING || hints == null ||
            hints.sessionId != frame.sessionId || hints.frameId != frame.frameId) return emptyList()
        fun reliable(name: KeypointName) = hints.points[name]?.takeIf { it.confidence >= .65f && it.z.isFinite() }
        val leftShoulder = reliable(KeypointName.LEFT_SHOULDER) ?: return emptyList()
        val rightShoulder = reliable(KeypointName.RIGHT_SHOULDER) ?: return emptyList()
        val leftHip = reliable(KeypointName.LEFT_HIP) ?: return emptyList()
        val rightHip = reliable(KeypointName.RIGHT_HIP) ?: return emptyList()
        val width = frame.image.width
        val height = frame.image.height
        val shoulderSpan = hypot((leftShoulder.x - rightShoulder.x) * width, (leftShoulder.y - rightShoulder.y) * height)
        val shoulderY = (leftShoulder.y + rightShoulder.y) / 2
        val hipY = (leftHip.y + rightHip.y) / 2
        if (shoulderSpan < 12 || hipY - shoulderY < .04f) return emptyList()
        val shoulderZ = (leftShoulder.z + rightShoulder.z) / 2
        val hipZ = (leftHip.z + rightHip.z) / 2
        val marginZ = .12f * shoulderSpan / width
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        return buildList {
            for ((elbowName, wristName) in listOf(
                KeypointName.LEFT_ELBOW to KeypointName.LEFT_WRIST,
                KeypointName.RIGHT_ELBOW to KeypointName.RIGHT_WRIST,
            )) {
                val elbow = reliable(elbowName) ?: continue
                val wrist = reliable(wristName) ?: continue
                val dx = (wrist.x - elbow.x) * width
                val dy = (wrist.y - elbow.y) * height
                if (hypot(dx, dy) !in shoulderSpan * .12f..shoulderSpan * 1.6f) continue
                fun inFront(t: Float): Boolean {
                    val relativeY = ((lerp(elbow.y, wrist.y, t) - shoulderY) / (hipY - shoulderY)).coerceIn(0f, 1f)
                    return lerp(elbow.z, wrist.z, t) < lerp(shoulderZ, hipZ, relativeY) - marginZ
                }
                fun addSpan(from: Float, to: Float, radius: Float) = add(Segment(
                    elbow.x * width + dx * from, elbow.y * height + dy * from,
                    elbow.x * width + dx * to, elbow.y * height + dy * to, radius * shoulderSpan,
                ))
                for (part in 0..3) {
                    val from = part * .25f
                    val to = from + .25f
                    if (inFront((from + to) / 2)) addSpan(from, to, .105f)
                }
                if (inFront(1f)) addSpan(1f, 1.19f, .093f)
            }
        }
    }
}
