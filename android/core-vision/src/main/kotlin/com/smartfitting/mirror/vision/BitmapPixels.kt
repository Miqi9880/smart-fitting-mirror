package com.smartfitting.mirror.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import com.smartfitting.mirror.contracts.DepthMetadata
import com.smartfitting.mirror.contracts.Landmark
import com.smartfitting.mirror.contracts.PlaneMetadata

/** Pixels are upright, unmirrored ARGB_8888. Access only while owning a FrameLease reference. */
class BitmapPixels(
    val bitmap: Bitmap,
    val alignedDepth: AlignedDepthPixels? = null,
    val imagePose: ImagePoseHints? = null,
    val personMask: PersonMaskPixels? = null,
    val handLandmarks: HandLandmarks? = null,
)

/** Finger landmarks belong only to the RGB pixels of this lease. */
data class HandLandmarks(val left: List<Landmark>, val right: List<Landmark>)

/** Optional provider-owned depth paired with the same RGB frame. Released with its FrameLease. */
class AlignedDepthPixels(val metadata: DepthMetadata, val bytes: ByteArray) {
    init { require(bytes.size.toLong() >= metadata.rowStrideBytes.toLong()*metadata.height) }

    fun millimetersAt(x: Int, y: Int): Int {
        val index = y * metadata.rowStrideBytes + x * 2
        return (bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8)
    }
}

/** Foreground confidence, 0 to 255, in the same normalized coordinates as RGB. */
class PersonMaskPixels(val metadata: PlaneMetadata, val bytes: ByteArray) {
    init { require(bytes.size.toLong() >= metadata.rowStrideBytes.toLong() * metadata.height) }

    fun confidenceAt(x: Float, y: Float): Int {
        val column = (x * metadata.width).toInt().coerceIn(0, metadata.width - 1)
        val row = (y * metadata.height).toInt().coerceIn(0, metadata.height - 1)
        return bytes[row * metadata.rowStrideBytes + column].toInt() and 255
    }
}

internal fun uprightBitmap(input: Bitmap, rotationDegrees: Int): Bitmap {
    require(rotationDegrees in setOf(0, 90, 180, 270))
    if (rotationDegrees == 0) return input
    val rotated = Bitmap.createBitmap(input, 0, 0, input.width, input.height,
        Matrix().apply { postRotate(rotationDegrees.toFloat()) }, false)
    if (rotated !== input) input.recycle()
    return rotated
}
