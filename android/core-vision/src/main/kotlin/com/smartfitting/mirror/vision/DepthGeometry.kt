package com.smartfitting.mirror.vision

import com.smartfitting.mirror.contracts.DepthMetadata
import com.smartfitting.mirror.contracts.Keypoints
import kotlin.math.roundToInt

/** Accepts vendor-aligned Y16, preserves invalid pixels and rotates with the RGB image. */
object DepthGeometry {
    fun millimeterPlane(
        id: String, width: Int, height: Int, bytes: ByteArray, unitMm: Float, rotation: Int,
    ): AlignedDepthPixels {
        require(width in 1..1920 && height in 1..1920 && bytes.size == width * height * 2)
        require(unitMm.isFinite() && unitMm > 0f && rotation in setOf(0, 90, 180, 270))
        val swapped = rotation == 90 || rotation == 270
        val outputWidth = if (swapped) height else width
        val outputHeight = if (swapped) width else height
        if (unitMm == 1f && rotation == 0) {
            return AlignedDepthPixels(DepthMetadata(id, width, height, width * 2), bytes)
        }
        val result = ByteArray(bytes.size)
        for (y in 0 until height) for (x in 0 until width) {
            val index = (y * width + x) * 2
            val raw = (bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8)
            val mm = (raw * unitMm).roundToInt().takeIf { it in 1..65535 } ?: 0
            val target = when (rotation) {
                90 -> x * outputWidth + height - 1 - y
                180 -> (height - 1 - y) * outputWidth + width - 1 - x
                270 -> (width - 1 - x) * outputWidth + y
                else -> y * outputWidth + x
            } * 2
            result[target] = mm.toByte()
            result[target + 1] = (mm ushr 8).toByte()
        }
        return AlignedDepthPixels(DepthMetadata(id, outputWidth, outputHeight, outputWidth * 2), result)
    }

    /** The center of the tracked torso is less sensitive to the background and hands. */
    fun torsoDistanceM(depth: AlignedDepthPixels, pose: Keypoints, mask: PersonMaskPixels?): Float? {
        val torso = pose.torso().filterNotNull()
        if (torso.size != 4 || torso.any { it.confidence < .6f }) return null
        val (leftShoulder, rightShoulder, leftHip, rightHip) = torso
        val shoulderWidth = kotlin.math.abs(leftShoulder.x - rightShoulder.x)
        val torsoHeight = (leftHip.y + rightHip.y - leftShoulder.y - rightShoulder.y) / 2
        if (shoulderWidth < .05f || torsoHeight < .10f) return null
        val candidates = ArrayList<Int>(25)
        for (row in 0..4) {
            val down = .30f + row * .12f
            val lx = interpolate(leftShoulder.x, leftHip.x, down)
            val ly = interpolate(leftShoulder.y, leftHip.y, down)
            val rx = interpolate(rightShoulder.x, rightHip.x, down)
            val ry = interpolate(rightShoulder.y, rightHip.y, down)
            for (column in 0..4) {
                val across = .28f + column * .11f
                val x = interpolate(lx, rx, across)
                val y = interpolate(ly, ry, across)
                if (mask != null && mask.confidenceAt(x, y) < 166) continue
                val mm = depth.millimetersAt((x * (depth.metadata.width - 1)).roundToInt(),
                    (y * (depth.metadata.height - 1)).roundToInt())
                if (mm in 250..8000) candidates += mm
            }
        }
        if (candidates.size < 9) return null
        candidates.sort()
        val quarter = candidates.size / 4
        if (candidates[candidates.lastIndex - quarter] - candidates[quarter] > 280) return null
        return candidates[candidates.size / 2] / 1000f
    }

    private fun interpolate(a: Float, b: Float, t: Float) = a + (b - a) * t
}
