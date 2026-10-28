package com.smartfitting.mirror.video

/**
 * Offline video-recommendation stand-in for FR-VID-01/02/05. The HTTP contract is still
 * a draft (docs/interfaces/video-recommendation-draft.md), so nothing here touches the
 * network: [FakeVideoRepository] serves clearly-marked synthetic test data only.
 */

/** Where the content comes from. Test material is never presented as licensed. */
enum class VideoOrigin { SYNTHETIC_TEST, LICENSED }

enum class ClipOrientation { LANDSCAPE, PORTRAIT }

data class VideoItem(
    val videoId: String,
    val title: String,
    val durationSeconds: Int,
    /** Category/style/color/scene tags, e.g. 上装、休闲、黑色、街头. */
    val tags: List<String>,
    /** Optional garments this clip is related to (studio catalog ids). */
    val garmentIds: List<String> = emptyList(),
    val orientation: ClipOrientation = ClipOrientation.LANDSCAPE,
    val onShelf: Boolean = true,
    val origin: VideoOrigin = VideoOrigin.SYNTHETIC_TEST,
) {
    val isTestData: Boolean get() = origin == VideoOrigin.SYNTHETIC_TEST
}

/** Guest recommendation request: selected garments plus user-picked tags. No identity, photos or generated images. */
data class VideoQuery(
    val garmentIds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

sealed interface VideoResult {
    data class Items(val videos: List<VideoItem>) : VideoResult
    /** Honest empty state; never padded with unrelated clips to reach a count. */
    data class Empty(val message: String) : VideoResult
    data class Failure(val message: String) : VideoResult
}

interface VideoRepository {
    fun recommended(query: VideoQuery): VideoResult
}

/**
 * Deterministic fake for UI development and emulator checks. Recall is simple
 * tag/garment matching on at least one dimension (category, style, color or scene);
 * results are de-duplicated by id, off-shelf content is never returned, and a
 * shortfall is reported as [VideoResult.Empty] instead of being padded.
 */
class FakeVideoRepository(
    private val catalog: List<VideoItem> = defaultCatalog(),
) : VideoRepository {
    /** Test hook: the next call fails once with this message, then recovers. */
    var failNextWith: String? = null

    override fun recommended(query: VideoQuery): VideoResult {
        failNextWith?.let {
            failNextWith = null
            return VideoResult.Failure(it)
        }
        val shelf = catalog.filter { it.onShelf }
        val wantedTags = query.tags.toSet()
        val wantedGarments = query.garmentIds.toSet()
        val picked = if (wantedTags.isEmpty() && wantedGarments.isEmpty()) {
            shelf
        } else {
            shelf.filter { item ->
                item.garmentIds.any(wantedGarments::contains) || item.tags.any(wantedTags::contains)
            }
        }.distinctBy { it.videoId }
        if (picked.isEmpty()) {
            return VideoResult.Empty("暂无相关视频：当前所选服装或标签没有匹配内容。")
        }
        return VideoResult.Items(picked)
    }

    companion object {
        /**
         * Synthetic catalog. Titles carry the 测试 prefix and every entry is
         * [VideoOrigin.SYNTHETIC_TEST]; playback uses program-generated local clips
         * (see [GeneratedClips]), so no unlicensed media is downloaded or claimed.
         * One off-shelf entry guards the shelf filter; one portrait entry covers
         * portrait/landscape adaptation.
         */
        fun defaultCatalog(): List<VideoItem> = listOf(
            VideoItem(
                videoId = "demo-tee-daily",
                title = "测试·黑T恤日常搭配",
                durationSeconds = 3,
                tags = listOf("上装", "休闲", "黑色", "日常"),
                garmentIds = listOf("studio_black_tee"),
                orientation = ClipOrientation.LANDSCAPE,
            ),
            VideoItem(
                videoId = "demo-shirt-commute",
                title = "测试·白衬衫通勤look",
                durationSeconds = 3,
                tags = listOf("上装", "简约", "白色", "通勤"),
                garmentIds = listOf("studio_white_shirt"),
                orientation = ClipOrientation.PORTRAIT,
            ),
            VideoItem(
                videoId = "demo-street-style",
                title = "测试·街头风格混剪",
                durationSeconds = 3,
                tags = listOf("上装", "街头", "黑色", "出街"),
                garmentIds = listOf("studio_black_tee"),
                orientation = ClipOrientation.LANDSCAPE,
            ),
            VideoItem(
                videoId = "demo-sport-casual",
                title = "测试·运动休闲场景",
                durationSeconds = 3,
                tags = listOf("上装", "运动", "灰色", "户外"),
                garmentIds = emptyList(),
                orientation = ClipOrientation.LANDSCAPE,
            ),
            VideoItem(
                videoId = "demo-color-pop",
                title = "测试·亮色穿搭灵感",
                durationSeconds = 3,
                tags = listOf("上装", "休闲", "亮色", "约会"),
                garmentIds = emptyList(),
                orientation = ClipOrientation.PORTRAIT,
            ),
            VideoItem(
                videoId = "demo-archived-clip",
                title = "测试·已下架样片（不应出现）",
                durationSeconds = 3,
                tags = listOf("上装", "休闲"),
                garmentIds = emptyList(),
                orientation = ClipOrientation.LANDSCAPE,
                onShelf = false,
            ),
        )
    }
}
