package com.smartfitting.mirror.tryon

/** Adapts upstream garment_fs depth comparison to aligned metric millimeters.
 * No NDC/mm mixing and no synthetic default distance. Zero depth is unknown.
 */
object DepthOcclusion {
    fun alpha(depth: ByteArray, width: Int, height: Int, stride: Int,
              garmentDepthMm: Int, toleranceMm: Int = 20): ByteArray {
        require(width > 0 && height > 0 && stride.toLong() >= width.toLong()*2 &&
            depth.size.toLong() >= stride.toLong()*height && garmentDepthMm > 0 && toleranceMm >= 0)
        val output = ByteArray(Math.multiplyExact(width,height))
        for (y in 0 until height) for (x in 0 until width) {
            val offset = y*stride+x*2
            val scene = (depth[offset].toInt() and 255) or ((depth[offset+1].toInt() and 255) shl 8)
            output[y*width+x] = if (scene != 0 && scene < garmentDepthMm-toleranceMm) 0 else 255.toByte()
        }
        return output
    }
}
