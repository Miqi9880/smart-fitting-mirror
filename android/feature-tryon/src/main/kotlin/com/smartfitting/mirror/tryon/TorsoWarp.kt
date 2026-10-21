package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.*
import kotlin.math.abs

/** Pixel-center correspondences in canonical, upright, unmirrored image coordinates. */
object TorsoWarp {
    data class Points(val source: FloatArray, val destination: FloatArray)
    fun points(frame: VisionFrame, garment: Garment): Points? {
        if (garment.category != GarmentCategory.UPPER_BODY || frame.trackingState != TrackingState.TRACKING) return null
        val pose = listOf(frame.keypoints.leftShoulder, frame.keypoints.rightShoulder,
            frame.keypoints.rightHip, frame.keypoints.leftHip).map { it ?: return null }
        if (pose.any { it.confidence < .5f }) return null
        val anchors = (garment.previewImage.anchors ?: return null).let { listOf(it.leftShoulder, it.rightShoulder, it.rightHip, it.leftHip) }
        val target = pose.flatMap { listOf(it.x, it.y) }.toFloatArray()
        val source = anchors.flatMap { listOf(it.x, it.y) }.toFloatArray()
        if (!usable(target) || !usable(source)) return null
        fun pixels(values: FloatArray, width: Int, height: Int) = values.mapIndexed { index, v ->
            .5f + v * (if (index % 2 == 0) width - 1 else height - 1)
        }.toFloatArray()
        return Points(pixels(source, garment.previewImage.width, garment.previewImage.height),
            pixels(target, frame.image.width, frame.image.height))
    }
    private fun usable(points: FloatArray): Boolean {
        var winding = 0f
        var area = 0f
        for (i in 0..3) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val dx = points[j * 2] - points[i * 2]
            val dy = points[j * 2 + 1] - points[i * 2 + 1]
            if (dx * dx + dy * dy < .0001f) return false
            val cross = dx * (points[k * 2 + 1] - points[j * 2 + 1]) - dy * (points[k * 2] - points[j * 2])
            if (abs(cross) < .0001f || (winding != 0f && cross * winding <= 0f)) return false
            winding = cross
            area += points[i * 2] * points[j * 2 + 1] - points[j * 2] * points[i * 2 + 1]
        }
        return abs(area) > .002f
    }
}
