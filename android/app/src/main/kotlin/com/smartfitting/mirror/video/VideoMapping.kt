package com.smartfitting.mirror.video

import com.smartfitting.mirror.contracts.*

/** Wire-to-view mapping; never creates a new session or substitutes generated clips. */
fun VideoRecommendationList.forDisplay(): VideoResult {
    if (items.isEmpty()) return VideoResult.Empty("暂无相关可播放视频，请更换服装或选择其他场景。")
    fun dimension(value: VideoDimension) = when (value) {
        VideoDimension.CATEGORY -> "品类"; VideoDimension.STYLE -> "风格"
        VideoDimension.COLOR -> "颜色"; VideoDimension.SCENE -> "场景"
    }
    fun label(value: VideoTag) = when (value.value) {
        "upper_body" -> "上衣"; "lower_body" -> "长裤"; "headwear" -> "帽子"
        "dress" -> "连衣裙"; "skirt" -> "半裙"; "shorts" -> "短裤"
        else -> value.value
    }
    return VideoResult.Items(items.map { item ->
        val video = item.video
        VideoItem(video.videoId, video.title, video.durationSeconds, video.tags.map(::label), video.garmentIds,
            if (video.orientation == VideoOrientation.LANDSCAPE) ClipOrientation.LANDSCAPE else ClipOrientation.PORTRAIT,
            origin = if (video.source.kind == VideoSourceKind.LICENSED) VideoOrigin.LICENSED else VideoOrigin.SYNTHETIC_TEST,
            playbackUrl = video.playbackUrl, coverUrl = video.coverUrl,
            sourceText = video.source.attribution + "\n" + video.source.authorization,
            matchesText = item.matches.map { "${dimension(it.dimension)}：${label(VideoTag(it.dimension, it.value))}" }.distinct().joinToString(" · "))
    }, availableCount)
}
