package com.smartfitting.mirror.vision

import com.smartfitting.mirror.contracts.KeypointName

/** ML Kit image-relative coordinates, NOT metric world points or Gemini depth.
 * x/y are normalized by image width/height; z is normalized by image width.
 * Negative z points toward the camera. Kept outside the public VisionFrame contract.
 */
data class ImagePosePoint(val x: Float, val y: Float, val z: Float, val confidence: Float)
data class ImagePoseHints(val sessionId: String, val frameId: Long, val points: Map<KeypointName, ImagePosePoint>)

object ImagePoseMapper {
    fun point(x: Float,y: Float,z: Float,confidence: Float,width: Int,height: Int): ImagePosePoint? {
        if (width <= 0 || height <= 0 || !listOf(x,y,z,confidence).all { it.isFinite() }) return null
        if (x !in 0f..width.toFloat() || y !in 0f..height.toFloat() || confidence !in 0f..1f) return null
        return ImagePosePoint(x/width,y/height,z/width,confidence)
    }
}
