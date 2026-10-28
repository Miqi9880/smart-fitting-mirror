package com.smartfitting.mirror.video

import org.junit.Assert.*
import org.junit.Test

class FakeVideoRepositoryTest {
    private fun items(result: VideoResult) = (result as VideoResult.Items).videos

    @Test fun emptyQueryReturnsEveryOnShelfClipOnly() {
        val result = FakeVideoRepository().recommended(VideoQuery())
        val ids = items(result).map { it.videoId }
        assertEquals(5, ids.size)
        assertFalse("已下架内容不得返回", "demo-archived-clip" in ids)
    }

    @Test fun everyCandidateIsClearlyMarkedTestData() {
        val result = FakeVideoRepository().recommended(VideoQuery())
        assertTrue(items(result).all { it.isTestData && it.origin == VideoOrigin.SYNTHETIC_TEST })
    }

    @Test fun recallRequiresAtLeastOneSharedDimension() {
        val result = FakeVideoRepository().recommended(VideoQuery(tags = listOf("街头")))
        val ids = items(result).map { it.videoId }
        assertEquals(listOf("demo-street-style"), ids)
    }

    @Test fun garmentSelectionRecallsLinkedClips() {
        val result = FakeVideoRepository().recommended(VideoQuery(garmentIds = listOf("studio_black_tee")))
        val ids = items(result).map { it.videoId }
        assertEquals(setOf("demo-tee-daily", "demo-street-style"), ids.toSet())
    }

    @Test fun unmatchedQueryIsAnHonestEmptyStateNotPadding() {
        val result = FakeVideoRepository().recommended(VideoQuery(tags = listOf("不存在标签")))
        assertTrue(result is VideoResult.Empty)
        assertTrue((result as VideoResult.Empty).message.isNotBlank())
    }

    @Test fun duplicateIdsAreReturnedOnce() {
        val item = VideoItem("dup", "测试·重复", 3, listOf("休闲"))
        val repository = FakeVideoRepository(listOf(item, item.copy(title = "测试·重复2")))
        assertEquals(1, items(repository.recommended(VideoQuery())).size)
    }

    @Test fun simulatedFailureIsReportedOnceThenRecovers() {
        val repository = FakeVideoRepository()
        repository.failNextWith = "测试：模拟加载失败"
        val failed = repository.recommended(VideoQuery())
        assertTrue(failed is VideoResult.Failure)
        assertEquals("测试：模拟加载失败", (failed as VideoResult.Failure).message)
        assertTrue(repository.recommended(VideoQuery()) is VideoResult.Items)
    }

    @Test fun portraitAndLandscapeClipsAreBothCovered() {
        val orientations = items(FakeVideoRepository().recommended(VideoQuery())).map { it.orientation }.toSet()
        assertTrue(ClipOrientation.LANDSCAPE in orientations)
        assertTrue(ClipOrientation.PORTRAIT in orientations)
    }
}
