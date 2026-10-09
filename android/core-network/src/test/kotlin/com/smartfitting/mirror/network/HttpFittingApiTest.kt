package com.smartfitting.mirror.network

import com.smartfitting.mirror.contracts.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpFittingApiTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun json(name: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(fixture(name))

    @Test fun mapsSessionUploadAndIdempotentCreate() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            HttpFittingApi(server.url("/").toString()).use { api ->
                server.enqueue(json("session.json", 201))
                val session = api.createSession()
                assertEquals("/api/v1/sessions", server.takeRequest().path)
                server.enqueue(json("upload.json", 201))
                val original = byteArrayOf(1, 2, 3, 4)
                val photo = CapturedPhoto("local", 1, 2, 2, "image/png", original)
                val uploaded = api.upload(session.sessionToken, photo)
                val request = server.takeRequest()
                assertEquals("Bearer ${session.sessionToken}", request.getHeader("Authorization"))
                assertTrue(request.body.readUtf8().contains("name=\"file\"; filename=\"capture.png\""))
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), original)
                photo.close()
                server.enqueue(json("job-queued.json", 202))
                api.createJob(session.sessionToken, CreateJob(uploaded.uploadId, "upper_blue"), "stable-key")
                assertEquals("stable-key", server.takeRequest().getHeader("Idempotency-Key"))
            }
        }
    }

    @Test fun rateLimitPreservesRetryAfterAndStructuredError() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            HttpFittingApi(server.url("/").toString()).use { api ->
                server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7")
                    .setBody("""{"error":{"code":"RATE_LIMITED","message":"稍后重试","retryable":true,"request_id":"test"}}"""))
                try { api.garments("test"); fail("Expected 429") }
                catch (error: ApiFailure) {
                    assertEquals(429, error.status); assertEquals(7L, error.retryAfterSeconds)
                    assertEquals("RATE_LIMITED", error.code); assertTrue(error.retryable)
                }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun credentialsNeverFollowImageRedirectOrCrossOriginUrl() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { other ->
            first.start(); other.start()
            HttpFittingApi(first.url("/").toString()).use { api ->
                try { api.download("private", other.url("/image.png").toString()); fail("Expected origin check") }
                catch (_: IllegalArgumentException) { }
                assertEquals(0, other.requestCount)
                first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/image.png")))
                try { api.download("private", first.url("/image.png").toString()); fail("Expected redirect rejection") }
                catch (error: ApiFailure) { assertEquals(302, error.status) }
                assertEquals(0, other.requestCount)
            }
        } }
    }

    @Test fun cancellationStopsPendingCallAndLeavesOriginalOwnershipWithCaller() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            HttpFittingApi(server.url("/").toString()).use { api ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val original = ByteArray(2048) { 17 }
                val photo = CapturedPhoto("local", 1, 32, 32, "image/png", original)
                val pending = launch(Dispatchers.Default) { api.upload("test", photo) }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                withTimeout(1500) { pending.cancelAndJoin() }
                assertTrue(original.all { it == 17.toByte() })
                photo.close()
                assertTrue(original.all { it == 0.toByte() })
            }
        }
    }

    @Test fun oversizedResponseAndNonImageContentAreRejected() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            HttpFittingApi(server.url("/").toString()).use { api ->
                server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", 2 * 1024 * 1024))
                try { api.garments("test"); fail("Expected size rejection") } catch (_: IOException) { }
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>error</html>"))
                try { api.download("test", server.url("/result").toString()); fail("Expected image validation") } catch (_: IOException) { }
            }
        }
    }
}
