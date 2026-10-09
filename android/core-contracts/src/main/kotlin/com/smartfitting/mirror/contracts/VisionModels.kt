package com.smartfitting.mirror.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ContractJson = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }

@Serializable enum class TrackingState {
    @SerialName("tracking") TRACKING, @SerialName("partial") PARTIAL, @SerialName("lost") LOST
}
@Serializable enum class StandingHint {
    @SerialName("ok") OK, @SerialName("too_near") TOO_NEAR, @SerialName("too_far") TOO_FAR,
    @SerialName("out_of_frame") OUT_OF_FRAME, @SerialName("unknown") UNKNOWN
}
@Serializable enum class VisionSourceKind {
    @SerialName("mock") MOCK, @SerialName("rgb_camera") RGB_CAMERA, @SerialName("depth_camera") DEPTH_CAMERA
}
enum class KeypointName { LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST, LEFT_HIP, RIGHT_HIP }

@Serializable data class Landmark(val x: Float, val y: Float, val confidence: Float) {
    init { require(x in 0f..1f && y in 0f..1f && confidence in 0f..1f) }
}
@Serializable data class Keypoints(
    @SerialName("left_shoulder") val leftShoulder: Landmark? = null,
    @SerialName("right_shoulder") val rightShoulder: Landmark? = null,
    @SerialName("left_elbow") val leftElbow: Landmark? = null,
    @SerialName("right_elbow") val rightElbow: Landmark? = null,
    @SerialName("left_wrist") val leftWrist: Landmark? = null,
    @SerialName("right_wrist") val rightWrist: Landmark? = null,
    @SerialName("left_hip") val leftHip: Landmark? = null,
    @SerialName("right_hip") val rightHip: Landmark? = null,
) {
    fun asMap(): Map<KeypointName, Landmark?> = KeypointName.entries.zip(listOf(
        leftShoulder, rightShoulder, leftElbow, rightElbow, leftWrist, rightWrist, leftHip, rightHip
    )).toMap()
    fun torso() = listOf(leftShoulder, rightShoulder, leftHip, rightHip)
    fun state(threshold: Float = .5f): TrackingState = when {
        torso().all { it != null && it.confidence >= threshold } -> TrackingState.TRACKING
        asMap().values.any { it != null && it.confidence >= threshold } -> TrackingState.PARTIAL
        else -> TrackingState.LOST
    }
    companion object {
        fun fromMap(points: Map<KeypointName, Landmark?>) = Keypoints(
            points[KeypointName.LEFT_SHOULDER], points[KeypointName.RIGHT_SHOULDER],
            points[KeypointName.LEFT_ELBOW], points[KeypointName.RIGHT_ELBOW],
            points[KeypointName.LEFT_WRIST], points[KeypointName.RIGHT_WRIST],
            points[KeypointName.LEFT_HIP], points[KeypointName.RIGHT_HIP])
    }
}
@Serializable data class ImageMetadata(
    @SerialName("buffer_id") val bufferId: String,
    val width: Int, val height: Int, val orientation: String = "upright", val mirrored: Boolean = false
) { init { require(bufferId.isNotBlank() && width > 0 && height > 0 && orientation == "upright" && !mirrored) } }

@Serializable data class PlaneMetadata(
    @SerialName("buffer_id") val bufferId: String, val width: Int, val height: Int,
    @SerialName("row_stride_bytes") val rowStrideBytes: Int, val format: String,
) { init { require(bufferId.isNotBlank() && width > 0 && height > 0 && rowStrideBytes >= width && format == "foreground_uint8") } }
@Serializable data class DepthMetadata(
    @SerialName("buffer_id") val bufferId: String, val width: Int, val height: Int,
    @SerialName("row_stride_bytes") val rowStrideBytes: Int,
    val format: String = "depth_uint16_le_mm", @SerialName("invalid_value") val invalidValue: Int = 0
) { init { require(bufferId.isNotBlank() && width > 0 && height > 0 && rowStrideBytes.toLong() >= width.toLong() * 2 && format == "depth_uint16_le_mm" && invalidValue == 0) } }

@Serializable data class VisionFrame(
    @SerialName("schema_version") val schemaVersion: String = "0.1.0",
    @SerialName("session_id") val sessionId: String,
    @SerialName("frame_id") val frameId: Long,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val image: ImageMetadata,
    @SerialName("tracking_state") val trackingState: TrackingState,
    val keypoints: Keypoints,
    @SerialName("standing_hint") val standingHint: StandingHint = StandingHint.UNKNOWN,
    @SerialName("distance_m") val distanceM: Float? = null,
    val mask: PlaneMetadata? = null, val depth: DepthMetadata? = null,
) {
    init {
        require(schemaVersion == "0.1.0" && sessionId.isNotBlank() && frameId >= 0 && timestampMs >= 0)
        require(distanceM == null || (distanceM.isFinite() && distanceM > 0))
        require(depth == null || (depth.width == image.width && depth.height == image.height))
        require(trackingState == keypoints.state())
        require(trackingState != TrackingState.LOST || keypoints.asMap().values.all { it == null })
    }
}
@Serializable data class VisionCapabilities(
    @SerialName("schema_version") val schemaVersion: String = "0.1.0",
    val source: VisionSourceKind, val rgb: Boolean, val pose: Boolean,
    @SerialName("person_mask") val personMask: Boolean,
    @SerialName("aligned_depth") val alignedDepth: Boolean
) { init { require(schemaVersion == "0.1.0") } }
