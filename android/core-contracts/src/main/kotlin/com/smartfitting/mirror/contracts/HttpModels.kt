@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.smartfitting.mirror.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class Session(@SerialName("session_id") val sessionId: String,
    @SerialName("session_token") val sessionToken: String, @SerialName("expires_at") val expiresAt: String)
@Serializable data class Anchor(val x: Float, val y: Float) { init { require(x in 0f..1f && y in 0f..1f) } }
@Serializable data class GarmentAnchors(@SerialName("left_shoulder") val leftShoulder: Anchor,
    @SerialName("right_shoulder") val rightShoulder: Anchor, @SerialName("left_hip") val leftHip: Anchor,
    @SerialName("right_hip") val rightHip: Anchor)
@Serializable data class PreviewImage(val url: String, val width: Int, val height: Int,
    val anchors: GarmentAnchors? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("attachment_anchors") val attachmentAnchors: Map<String, Anchor> = emptyMap(),
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("hem_ratio") val hemRatio: Float? = null) {
    init { require(hemRatio == null || hemRatio in .1f..2f) }
}
@Serializable enum class GarmentCategory {
    @SerialName("upper_body") UPPER_BODY,
    @SerialName("lower_body") LOWER_BODY,
    @SerialName("headwear") HEADWEAR,
    @SerialName("dress") DRESS,
    @SerialName("skirt") SKIRT,
    @SerialName("shorts") SHORTS;
    val label: String get() = when (this) {
        UPPER_BODY -> "上衣"; LOWER_BODY -> "长裤"; HEADWEAR -> "帽子"
        DRESS -> "连衣裙"; SKIRT -> "半裙"; SHORTS -> "短裤"
    }
}
/** Optional metadata adapted from SmartMirror Clothing; old servers remain compatible. */
@Serializable data class GarmentDetails(val brand: String = "", val material: String = "",
    val description: String = "", val sizes: List<String> = emptyList(), val colors: List<String> = emptyList(),
    val season: String = "", @SerialName("care_instructions") val careInstructions: String = "",
    @SerialName("price_cents") val priceCents: Int? = null)
@Serializable data class GarmentWrite(@SerialName("garment_id") val garmentId: String, val name: String,
    val category: GarmentCategory = GarmentCategory.UPPER_BODY, val tags: List<String> = emptyList(),
    @SerialName("asset_id") val assetId: String, val details: GarmentDetails = GarmentDetails())
@Serializable data class Garment(@SerialName("garment_id") val garmentId: String, val name: String,
    val category: GarmentCategory, val tags: List<String>, @SerialName("thumbnail_url") val thumbnailUrl: String,
    @SerialName("preview_image") val previewImage: PreviewImage,
    @SerialName("generation_reference_url") val generationReferenceUrl: String?,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val details: GarmentDetails = GarmentDetails())
@Serializable enum class AccessoryCategory(val label: String) {
    @SerialName("belt") BELT("腰带"),
    @SerialName("earrings") EARRINGS("耳饰")
}
@Serializable data class OutfitAccessory(
    @SerialName("accessory_id") val accessoryId: String, val name: String, val category: AccessoryCategory,
    @SerialName("thumbnail_url") val thumbnailUrl: String, val source: String)
@Serializable data class GarmentList(
    val items: List<Garment>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("outfit_available") val outfitAvailable: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val accessories: List<OutfitAccessory> = emptyList())
@Serializable data class Upload(@SerialName("upload_id") val uploadId: String, val width: Int, val height: Int,
    @SerialName("expires_at") val expiresAt: String)
@Serializable data class OutfitRequest(
    @SerialName("garment_ids") val garmentIds: List<String>,
    @SerialName("accessory_ids") val accessoryIds: List<String>)
@Serializable data class CreateJob(
    @SerialName("upload_id") val uploadId: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("garment_id") val garmentId: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val outfit: OutfitRequest? = null)
@Serializable data class ErrorDetail(val code: String, val message: String, val retryable: Boolean,
    @SerialName("request_id") val requestId: String)
@Serializable data class ErrorResponse(val error: ErrorDetail)
@Serializable enum class JobStatus {
    @SerialName("queued") QUEUED, @SerialName("running") RUNNING, @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED, @SerialName("cancelled") CANCELLED;
    val terminal get() = this in setOf(SUCCEEDED, FAILED, CANCELLED)
}
@Serializable data class JobResult(@SerialName("image_url") val imageUrl: String, val width: Int, val height: Int,
    @SerialName("expires_at") val expiresAt: String)
@Serializable data class TryOnJob(@SerialName("job_id") val jobId: String, @SerialName("session_id") val sessionId: String,
    @SerialName("upload_id") val uploadId: String, @SerialName("garment_id") val garmentId: String,
    val status: JobStatus, @SerialName("is_mock") val isMock: Boolean,
    @SerialName("created_at") val createdAt: String, @SerialName("updated_at") val updatedAt: String,
    val result: JobResult?, val error: ErrorDetail?,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val outfit: OutfitRequest? = null) {
    init { require((result != null) == (status == JobStatus.SUCCEEDED)); require((error != null) == (status == JobStatus.FAILED)) }
}

/** Logical client boundary; implementation and transport dependencies belong to core-network. */
interface FittingApi {
    suspend fun createSession(): Session
    suspend fun garments(sessionToken: String): GarmentList
    suspend fun upload(sessionToken: String, photo: CapturedPhoto): Upload
    suspend fun createJob(sessionToken: String, request: CreateJob, idempotencyKey: String): TryOnJob
    suspend fun job(sessionToken: String, jobId: String): TryOnJob
    suspend fun cancel(sessionToken: String, jobId: String): TryOnJob
    suspend fun endSession(sessionToken: String)
}
