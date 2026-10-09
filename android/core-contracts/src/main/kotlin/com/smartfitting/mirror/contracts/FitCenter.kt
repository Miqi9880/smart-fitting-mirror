package com.smartfitting.mirror.contracts

import kotlin.math.min

/** Complete image fit, no crop. Use the same transform for pixels and every overlay. */
class FitCenter(val imageWidth: Int, val imageHeight: Int, viewWidth: Int, viewHeight: Int, val mirrored: Boolean) {
    init { require(imageWidth > 0 && imageHeight > 0 && viewWidth >= 0 && viewHeight >= 0) }
    val scale = min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
    val left = (viewWidth - imageWidth * scale) / 2f
    val top = (viewHeight - imageHeight * scale) / 2f
    fun map(x: Float, y: Float): Pair<Float, Float> {
        val pixelX = .5f + x * (imageWidth - 1)
        return (left + (if (mirrored) imageWidth - pixelX else pixelX) * scale) to
            (top + (.5f + y * (imageHeight - 1)) * scale)
    }
}
