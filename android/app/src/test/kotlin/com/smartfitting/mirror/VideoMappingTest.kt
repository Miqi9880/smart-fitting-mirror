package com.smartfitting.mirror

import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.video.*
import org.junit.Assert.*
import org.junit.Test

class VideoMappingTest {
    private fun response(kind: VideoSourceKind) = VideoRecommendationList(listOf(VideoRecommendation(
        VideoContent("sample-clip","参考片",12,VideoOrientation.PORTRAIT,null,"https://example.invalid/clip.mp4",
            listOf(VideoTag(VideoDimension.CATEGORY,"upper_body")),emptyList(),emptyList(),VideoSource(kind,"测试元数据来源","仅字段测试，不代表真实授权")),
        listOf(VideoMatch(VideoDimension.CATEGORY,"upper_body",VideoMatchSource.GARMENT_METADATA,"upper_blue"))
    )),5,1)

    @Test fun partialResultsKeepRealUrlsSecondsOrientationAndProvenance() {
        val result=response(VideoSourceKind.LICENSED).forDisplay() as VideoResult.Items
        assertEquals(1,result.videos.size);assertEquals(1,result.availableCount)
        val item=result.videos.single()
        assertEquals(12,item.durationSeconds);assertEquals(ClipOrientation.PORTRAIT,item.orientation)
        assertEquals(VideoOrigin.LICENSED,item.origin);assertFalse(item.isTestData)
        assertEquals("https://example.invalid/clip.mp4",item.playbackUrl)
        assertTrue(item.matchesText.contains("上衣"));assertTrue(item.sourceText.contains("仅字段测试"))
    }
    @Test fun remoteSyntheticMetadataDoesNotImplicitlyBecomeGeneratedLocalMedia() {
        val item=(response(VideoSourceKind.SYNTHETIC_TEST).forDisplay() as VideoResult.Items).videos.single()
        assertTrue(item.isTestData);assertNotNull(item.playbackUrl)
        assertTrue(VideoRecommendationList(emptyList(),5,0).forDisplay() is VideoResult.Empty)
    }
}
