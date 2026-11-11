package com.smartfitting.mirror.network

import com.smartfitting.mirror.contracts.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class VideoApiTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun json(name: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(fixture(name))
    private fun server() = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }

    @Test fun postUsesExistingSessionAndNeverFetchesMediaUploadsOrGenerates() = runBlocking {
        server().use { server -> HttpFittingApi(server.url("/").toString()).use { api ->
            server.enqueue(json("video-shortfall.json"))
            val request = ContractJson.decodeFromString<VideoRecommendationRequest>(fixture("video-request.json"))
            val result = api.recommendVideos("session-test", request)
            assertEquals(1, result.items.size); assertEquals(5, result.requestedLimit)
            val sent = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("POST", sent.method); assertEquals("/api/v1/video-recommendations", sent.path)
            assertEquals("Bearer session-test", sent.getHeader("Authorization"))
            assertNull(sent.getHeader("Idempotency-Key"))
            assertEquals(request, ContractJson.decodeFromString<VideoRecommendationRequest>(sent.body.readUtf8()))
            assertEquals("Only metadata retrieval, no media/AI requests", 1, server.requestCount)
        } }
    }

    @Test fun emptyIsSuccessAndWrongCountsAreContractFailures() = runBlocking {
        server().use { server -> HttpFittingApi(server.url("/").toString()).use { api ->
            server.enqueue(json("video-empty.json"))
            assertTrue(api.recommendVideos("test", VideoRecommendationRequest()).items.isEmpty())
            server.enqueue(json("video-matched.json")) // Server reports limit1 although caller requested5.
            try { api.recommendVideos("test", VideoRecommendationRequest()); fail("Expected mismatch") }
            catch (_: IOException) { }
        } }
    }

    @Test fun authFailureRateLimitAndUnimplementedOldServerNeverFallBackToFakeSuccess() = runBlocking {
        server().use { server -> HttpFittingApi(server.url("/").toString()).use { api ->
            for ((status, code) in listOf(401 to "SESSION_INVALID", 429 to "RATE_LIMITED", 404 to "INVALID_REQUEST")) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "7")
                    .setBody("""{"error":{"code":"$code","message":"test","retryable":${status == 429},"request_id":"synthetic"}}"""))
                try { api.recommendVideos("test", VideoRecommendationRequest()); fail("Expected error") }
                catch (error: ApiFailure) { assertEquals(status, error.status); assertEquals(code, error.code) }
            }
            assertEquals(3, server.requestCount)
        } }
    }

    @Test fun cancellationStopsThePendingMetadataRequestAndDoesNotReplay() = runBlocking {
        server().use { server -> HttpFittingApi(server.url("/").toString()).use { api ->
            server.enqueue(json("video-empty.json").setBodyDelay(3, TimeUnit.SECONDS))
            val call = async { api.recommendVideos("test", VideoRecommendationRequest()) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            call.cancelAndJoin()
            assertTrue(call.isCancelled); assertEquals(1, server.requestCount)
        } }
    }

    @Test fun evidenceForAnotherOutfitOrAnUnrequestedTagIsRejected() = runBlocking {
        server().use { server -> HttpFittingApi(server.url("/").toString()).use { api ->
            server.enqueue(json("video-shortfall.json"))
            try { api.recommendVideos("test", VideoRecommendationRequest(garmentIds = listOf("upper_sand"))); fail("Expected wrong context") }
            catch (_: IOException) { }
            server.enqueue(json("video-shortfall.json"))
            try { api.recommendVideos("test", VideoRecommendationRequest()); fail("Expected empty context rejection") }
            catch (_: IOException) { }
            server.enqueue(MockResponse().setBody(fixture("video-shortfall.json")
                .replace("\"garment_metadata\"", "\"user_selection\"")
                .replace("\"related_id\": \"upper_blue\"", "\"related_id\": null")))
            try { api.recommendVideos("test", VideoRecommendationRequest(garmentIds = listOf("upper_blue"))); fail("Expected unrequested tag") }
            catch (_: IOException) { }
        } }
    }

    @Test fun modelEvidenceRequiresTheRequestedJobAndAnActualReturnedTag() = runBlocking {
        val base = ContractJson.decodeFromString<VideoRecommendationList>(fixture("video-shortfall.json"))
        val item = base.items.single()
        val tag = VideoTag(VideoDimension.STYLE, "casual")
        val result = base.copy(
            items = listOf(item.copy(matches = listOf(VideoMatch(tag.dimension, tag.value, VideoMatchSource.MODEL_SEMANTICS, null)))),
            semantic = VideoSemanticAnalysis("job_test", VideoSemanticStatus.USED, listOf(tag))
        )
        server().use { server -> HttpFittingApi(server.url("/").toString()).use { api ->
            val request = VideoRecommendationRequest(semanticJobId = "job_test")
            fun respond(response: VideoRecommendationList) {
                server.enqueue(MockResponse().setBody(ContractJson.encodeToString(response)))
            }
            respond(result)
            assertEquals(result, api.recommendVideos("test", request))
            val sent = server.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8()
            assertEquals(request, ContractJson.decodeFromString<VideoRecommendationRequest>(sent))
            assertFalse(sent.contains("image"))
            val analysis = requireNotNull(result.semantic)
            for (invalid in listOf(result.copy(semantic = analysis.copy(jobId = "job_other")),
                result.copy(semantic = analysis.copy(tags = listOf(VideoTag(VideoDimension.STYLE, "formal")))))) {
                respond(invalid)
                try { api.recommendVideos("test", request); fail("Expected invalid model provenance") }
                catch (_: IOException) { }
            }
        } }
    }
}
