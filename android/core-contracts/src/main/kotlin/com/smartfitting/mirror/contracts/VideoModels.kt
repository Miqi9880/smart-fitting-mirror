package com.smartfitting.mirror.contracts

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val videoIdentifier = Regex("^[a-z][a-z0-9_-]{0,63}$")
private val garmentId = Regex("^[a-z][a-z0-9_]{0,63}$")
private fun String.nonBlankTrimmed(max: Int) = isNotBlank() && this == trim() && length <= max
private fun <T> List<T>.unique() = distinct().size == size
private fun mediaUrl(value: String) {
    val uri = URI(value)
    require(value.length <= 2048 && value.none { it.isWhitespace() } && uri.scheme in setOf("http", "https") &&
        !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in -1..65535)
}

@Serializable enum class VideoDimension {
    @SerialName("category") CATEGORY, @SerialName("style") STYLE,
    @SerialName("color") COLOR, @SerialName("scene") SCENE
}
@Serializable data class VideoTag(val dimension: VideoDimension, val value: String) {
    init { require(value.nonBlankTrimmed(64)) }
}
@Serializable data class VideoRecommendationRequest(
    @SerialName("garment_ids") val garmentIds: List<String> = emptyList(),
    @SerialName("makeup_ids") val makeupIds: List<String> = emptyList(),
    @SerialName("user_tags") val userTags: List<VideoTag> = emptyList(), val limit: Int = 5
) {
    init {
        require(garmentIds.size <= 6 && garmentIds.unique() && garmentIds.all(garmentId::matches))
        require(makeupIds.size <= 8 && makeupIds.unique() && makeupIds.all(videoIdentifier::matches))
        require(userTags.size <= 20 && userTags.unique() && limit in 1..20)
    }
}
@Serializable enum class VideoOrientation { @SerialName("landscape") LANDSCAPE, @SerialName("portrait") PORTRAIT }
@Serializable enum class VideoSourceKind { @SerialName("synthetic_test") SYNTHETIC_TEST, @SerialName("licensed") LICENSED }
@Serializable data class VideoSource(val kind: VideoSourceKind, val attribution: String, val authorization: String) {
    init { require(attribution.nonBlankTrimmed(500) && authorization.nonBlankTrimmed(500)) }
}
/** Media URLs are data, never instructions to attach the guest Authorization header. */
@Serializable data class VideoContent(
    @SerialName("video_id") val videoId: String, val title: String,
    @SerialName("duration_seconds") val durationSeconds: Int, val orientation: VideoOrientation,
    @SerialName("cover_url") val coverUrl: String?, @SerialName("playback_url") val playbackUrl: String,
    val tags: List<VideoTag>, @SerialName("garment_ids") val garmentIds: List<String>,
    @SerialName("makeup_ids") val makeupIds: List<String>, val source: VideoSource
) {
    init {
        require(videoIdentifier.matches(videoId) && title.nonBlankTrimmed(100))
        require(durationSeconds in 1..86400 && tags.size in 1..40 && tags.unique())
        require(garmentIds.size <= 100 && garmentIds.unique() && garmentIds.all(garmentId::matches))
        require(makeupIds.size <= 100 && makeupIds.unique() && makeupIds.all(videoIdentifier::matches))
        mediaUrl(playbackUrl); coverUrl?.let(::mediaUrl)
    }
}
@Serializable enum class VideoMatchSource {
    @SerialName("garment_metadata") GARMENT_METADATA, @SerialName("makeup_metadata") MAKEUP_METADATA,
    @SerialName("user_selection") USER_SELECTION, @SerialName("model_semantics") MODEL_SEMANTICS
}
@Serializable data class VideoMatch(val dimension: VideoDimension, val value: String, val source: VideoMatchSource,
    @SerialName("related_id") val relatedId: String?) {
    init {
        require(value.nonBlankTrimmed(64) && (relatedId == null || videoIdentifier.matches(relatedId)))
        require((source in setOf(VideoMatchSource.GARMENT_METADATA, VideoMatchSource.MAKEUP_METADATA)) == (relatedId != null))
    }
}
@Serializable data class VideoRecommendation(val video: VideoContent, val matches: List<VideoMatch>) {
    init {
        require(matches.size in 1..40 && matches.unique())
        require(matches.all { VideoTag(it.dimension, it.value) in video.tags })
    }
}
@Serializable data class VideoRecommendationList(val items: List<VideoRecommendation>,
    @SerialName("requested_limit") val requestedLimit: Int, @SerialName("available_count") val availableCount: Int) {
    init {
        require(requestedLimit in 1..20 && availableCount in 0..100000)
        require(items.size == minOf(requestedLimit, availableCount) && items.map { it.video.videoId }.unique())
    }
}

/** Separate boundary: existing FittingApi fakes do not acquire a new abstract method. */
interface VideoApi {
    suspend fun recommendVideos(sessionToken: String, request: VideoRecommendationRequest): VideoRecommendationList
}
