package com.smartfitting.mirror.contracts

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class VideoContractsTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun response(name: String = "video-shortfall.json") = ContractJson.decodeFromString<VideoRecommendationList>(fixture(name))

    @Test fun sharedFixturesPreserveAllTagsSourcesAndHonestCounts() {
        for (name in listOf("video-matched.json", "video-shortfall.json", "video-empty.json")) {
            val data = response(name)
            assertEquals(Json.parseToJsonElement(fixture(name)), Json.parseToJsonElement(ContractJson.encodeToString(data)))
            assertTrue(data.items.all { it.video.source.kind == VideoSourceKind.SYNTHETIC_TEST })
        }
        val request = ContractJson.decodeFromString<VideoRecommendationRequest>(fixture("video-request.json"))
        assertEquals(Json.parseToJsonElement(fixture("video-request.json")), Json.parseToJsonElement(ContractJson.encodeToString(request)))
        assertEquals(VideoRecommendationRequest(), ContractJson.decodeFromString<VideoRecommendationRequest>("{}"))
        assertEquals(1, response().items.size) // Shortfall is useful content, not an error or padded list.
        assertTrue(response("video-empty.json").items.isEmpty())
    }

    @Test fun contextRejectsDuplicatesBadDimensionsAndUnboundedRequests() {
        for (json in listOf("""{"garment_ids":["upper_blue","upper_blue"]}""", """{"limit":0}""",
            """{"limit":21}""", """{"user_tags":[{"dimension":"style","value":"   "}]}""")) {
            assertThrows(IllegalArgumentException::class.java) { ContractJson.decodeFromString<VideoRecommendationRequest>(json) }
        }
        assertThrows(IllegalArgumentException::class.java) { VideoRecommendationRequest(garmentIds = listOf("bad/id")) }
        assertThrows(IllegalArgumentException::class.java) {
            VideoRecommendationRequest(userTags = List(2) { VideoTag(VideoDimension.STYLE, "casual") })
        }
    }

    @Test fun responseCannotFabricateCountRepeatIdsOrClaimAnUnrelatedMatch() {
        val data = response(); val item = data.items.first()
        assertThrows(IllegalArgumentException::class.java) { data.copy(availableCount = 0) }
        assertThrows(IllegalArgumentException::class.java) { data.copy(items = List(5) { item }, availableCount = 5) }
        assertThrows(IllegalArgumentException::class.java) { item.copy(matches = listOf(item.matches.first().copy(value = "unrelated"))) }
        assertThrows(IllegalArgumentException::class.java) { item.matches.first().copy(relatedId = null) }
        assertThrows(IllegalArgumentException::class.java) { item.matches.first().copy(source = VideoMatchSource.USER_SELECTION) }
    }

    @Test fun videoAddressesAreDataAndCannotCarryLocalFilesCredentialsOrFragments() {
        val video = response().items.first().video
        for (url in listOf("file:///private.mp4", "content://media/1", "https://token@example.invalid/x.mp4",
            "https://example.invalid/x.mp4#fragment", "http://example.invalid:99999/x.mp4")) {
            assertThrows(IllegalArgumentException::class.java) { video.copy(playbackUrl = url) }
        }
        assertThrows(IllegalArgumentException::class.java) { video.copy(durationSeconds = 0) }
        assertEquals(VideoOrientation.LANDSCAPE, video.orientation)
    }
}
