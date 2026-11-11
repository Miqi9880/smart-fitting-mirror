package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.VisionFrame
import com.smartfitting.mirror.vision.AlignedDepthPixels
import kotlin.math.roundToInt

/** Adapts upstream garment_fs depth comparison to aligned metric millimeters.
 * No NDC/mm mixing and no synthetic default distance. Zero depth is unknown.
 */
object DepthOcclusion {
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
        val toleranceMm = 20
        validate(depth.bytes, d.width, d.height, d.rowStrideBytes, garmentDepthMm, toleranceMm)
        require(pixels.size == Math.multiplyExact(d.width, d.height))
        forEachPixel(depth.bytes, d.width, d.height, d.rowStrideBytes, garmentDepthMm - toleranceMm) { index, occluded ->
            pixels[index] = if (occluded) 0 else -1
        }
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
