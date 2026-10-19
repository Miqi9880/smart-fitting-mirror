package com.smartfitting.mirror.vision

import android.graphics.Bitmap
import android.graphics.Matrix

/** Pixels are upright, unmirrored ARGB_8888. Access only while owning a FrameLease reference. */
class BitmapPixels(val bitmap: Bitmap, val alignedDepth: AlignedDepthPixels? = null)

/** Optional provider-owned depth paired with the same RGB frame. Released with its FrameLease. */
class AlignedDepthPixels(val metadata: com.smartfitting.mirror.contracts.DepthMetadata, val bytes: ByteArray) {
    init { require(bytes.size.toLong() >= metadata.rowStrideBytes.toLong()*metadata.height) }
}

internal fun uprightBitmap(input: Bitmap, rotationDegrees: Int): Bitmap {
    require(rotationDegrees in setOf(0, 90, 180, 270))
    if (rotationDegrees == 0) return input
    val rotated = Bitmap.createBitmap(input, 0, 0, input.width, input.height,
        Matrix().apply { postRotate(rotationDegrees.toFloat()) }, false)
    if (rotated !== input) input.recycle()
    return rotated
}
