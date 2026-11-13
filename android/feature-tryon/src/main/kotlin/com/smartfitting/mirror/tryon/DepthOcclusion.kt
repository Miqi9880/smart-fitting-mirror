package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.Landmark
import com.smartfitting.mirror.contracts.VisionFrame
import com.smartfitting.mirror.vision.AlignedDepthPixels
import com.smartfitting.mirror.vision.HandLandmarks
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Adapts upstream garment_fs depth comparison to aligned metric millimeters.
 * No NDC/mm mixing and no synthetic default distance. Zero depth is unknown.
 */
object DepthOcclusion {
    private const val TORSO_TOLERANCE_MM = 40
    private const val ARM_TOLERANCE_MM = 15
    internal fun torsoMillimeters(frame: VisionFrame, depth: AlignedDepthPixels): Int? {
        val k = frame.keypoints
        val leftShoulder = k.leftShoulder?.takeIf { it.confidence >= .65f } ?: return null
        val rightShoulder = k.rightShoulder?.takeIf { it.confidence >= .65f } ?: return null
        val leftHip = k.leftHip?.takeIf { it.confidence >= .65f } ?: return null
        val rightHip = k.rightHip?.takeIf { it.confidence >= .65f } ?: return null
        val d = depth.metadata
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        val samples = mutableListOf<Int>()
        for (down in listOf(.22f, .48f, .73f)) for (across in listOf(.24f, .5f, .76f)) {
            val x = lerp(lerp(rightShoulder.x, leftShoulder.x, across),
                lerp(rightHip.x, leftHip.x, across), down)
            val y = lerp(lerp(rightShoulder.y, leftShoulder.y, across),
                lerp(rightHip.y, leftHip.y, across), down)
            val offset = (y * (d.height - 1)).roundToInt() * d.rowStrideBytes + (x * (d.width - 1)).roundToInt() * 2
            readMm(depth.bytes, offset).takeIf { it != 0 }?.let(samples::add)
        }
        return if (samples.size >= 5) samples.sorted()[samples.size / 2] else null
    }

    fun alpha(depth: ByteArray, width: Int, height: Int, stride: Int,
              garmentDepthMm: Int, toleranceMm: Int = 20): ByteArray {
        validate(depth, width, height, stride, garmentDepthMm, toleranceMm)
        val output = ByteArray(Math.multiplyExact(width, height))
        forEachPixel(depth, width, height, stride, garmentDepthMm - toleranceMm) { index, occluded ->
            output[index] = if (occluded) 0 else 255.toByte()
        }
        return output
    }

    internal fun fillMask(depth: AlignedDepthPixels, garmentDepthMm: Int, pixels: IntArray) {
        val d = depth.metadata
        validate(depth.bytes, d.width, d.height, d.rowStrideBytes, garmentDepthMm, TORSO_TOLERANCE_MM)
        require(pixels.size == Math.multiplyExact(d.width, d.height))
        forEachPixel(depth.bytes, d.width, d.height, d.rowStrideBytes, garmentDepthMm - TORSO_TOLERANCE_MM) { index, occluded ->
            pixels[index] = if (occluded) 0 else -1
        }
    }

    /** Refine only the tracked limb, using nearby raw depth as foreground evidence. */
    internal fun refineArms(frame: VisionFrame, depth: AlignedDepthPixels, hands: HandLandmarks?,
                            torsoMm: Int, pixels: IntArray) {
        val d = depth.metadata
        val k = frame.keypoints
        val left = k.leftShoulder?.takeIf { it.confidence >= .65f } ?: return
        val right = k.rightShoulder?.takeIf { it.confidence >= .65f } ?: return
        fun x(p: Landmark) = p.x * (d.width - 1)
        fun y(p: Landmark) = p.y * (d.height - 1)
        val shoulderSpan = hypot(x(left) - x(right), y(left) - y(right))
        if (shoulderSpan < 12f) return
        val probe = (shoulderSpan * .015f).roundToInt().coerceIn(1, 3)
        fun refine(from: Landmark, to: Landmark, radius: Float, maxLength: Float) {
            if (from.confidence < .65f || to.confidence < .65f) return
            val ax = x(from)
            val ay = y(from)
            val bx = x(to)
            val by = y(to)
            val dx = bx - ax
            val dy = by - ay
            val length = hypot(dx, dy)
            if (length !in 2f..maxLength) return
            val radiusSquared = radius * radius
            val lengthSquared = length * length
            val xRange = floor(minOf(ax, bx) - radius).toInt().coerceAtLeast(0)..
                ceil(maxOf(ax, bx) + radius).toInt().coerceAtMost(d.width - 1)
            val yRange = floor(minOf(ay, by) - radius).toInt().coerceAtLeast(0)..
                ceil(maxOf(ay, by) + radius).toInt().coerceAtMost(d.height - 1)
            for (row in yRange) for (column in xRange) {
                val index = row * d.width + column
                if (pixels[index] == 0) continue
                val scene = depth.millimetersAt(column, row)
                if (scene >= torsoMm - ARM_TOLERANCE_MM) continue
                val t = (((column - ax) * dx + (row - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
                val offsetX = column - ax - dx * t
                val offsetY = row - ay - dy * t
                if (offsetX * offsetX + offsetY * offsetY > radiusSquared) continue
                if (hasForegroundSupport(depth, column, row, probe, torsoMm - TORSO_TOLERANCE_MM)) pixels[index] = 0
            }
        }
        fun arm(elbow: Landmark?, wrist: Landmark?, fingers: List<Landmark>?) {
            if (wrist == null) return
            elbow?.let { refine(it, wrist, shoulderSpan * .09f, shoulderSpan * 1.6f) }
            fingers?.forEach { refine(wrist, it, shoulderSpan * .045f, shoulderSpan * .55f) }
        }
        arm(k.leftElbow, k.leftWrist, hands?.left)
        arm(k.rightElbow, k.rightWrist, hands?.right)
    }

    private fun hasForegroundSupport(depth: AlignedDepthPixels, x: Int, y: Int, probe: Int, threshold: Int): Boolean {
        val d = depth.metadata
        var count = 0
        for (row in (y - probe).coerceAtLeast(0)..(y + probe).coerceAtMost(d.height - 1)) {
            for (column in (x - probe).coerceAtLeast(0)..(x + probe).coerceAtMost(d.width - 1)) {
                val mm = depth.millimetersAt(column, row)
                if (mm in 1 until threshold && ++count == 2) return true
            }
        }
        return false
    }

    private fun validate(depth: ByteArray, width: Int, height: Int, stride: Int,
                         garmentDepthMm: Int, toleranceMm: Int) {
        require(width > 0 && height > 0 && stride.toLong() >= width.toLong()*2 &&
            depth.size.toLong() >= stride.toLong()*height && garmentDepthMm > 0 && toleranceMm >= 0)
    }

    private inline fun forEachPixel(depth: ByteArray, width: Int, height: Int, stride: Int,
                                    thresholdMm: Int, write: (Int, Boolean) -> Unit) {
        for (y in 0 until height) for (x in 0 until width) {
            val scene = readMm(depth, y * stride + x * 2)
            write(y * width + x, scene != 0 && scene < thresholdMm)
        }
    }

    private fun readMm(depth: ByteArray, offset: Int) =
        (depth[offset].toInt() and 255) or ((depth[offset + 1].toInt() and 255) shl 8)
}
