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
    @Test fun cleanupUsesAFreshConnectionAfterPreviewAndDoesNotReplayUnacknowledgedDeletes() = runBlocking {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            HttpFittingApi("http://127.0.0.1:${server.port}/").use { api ->
                server.enqueue(json("session.json",201))
                val session=api.createSession()
                assertEquals(0,server.takeRequest(3,TimeUnit.SECONDS)!!.sequenceNumber)
                server.enqueue(MockResponse().setResponseCode(204))
                api.endSession(session.sessionToken)
                val ended=server.takeRequest(3,TimeUnit.SECONDS)!!
                assertEquals("DELETE",ended.method); assertEquals("/api/v1/sessions/current",ended.path)
                assertEquals("Bearer ${session.sessionToken}",ended.getHeader("Authorization"))
                assertEquals("Cleanup must not reuse the idle preview connection",0,ended.sequenceNumber)
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
                try { api.endSession(session.sessionToken); fail("An unacknowledged delete must surface failure") }
                catch (_: IOException) { }
                assertEquals("DELETE",server.takeRequest(3,TimeUnit.SECONDS)!!.method)
                assertEquals("No transparent request replay",3,server.requestCount)
                server.enqueue(MockResponse().setResponseCode(204))
                api.endSession(session.sessionToken)
                assertEquals(0,server.takeRequest(3,TimeUnit.SECONDS)!!.sequenceNumber)
            }
        }
    }

    @Test fun truncatedAssetFailsAndTheNextAssetCanBeDownloaded() = runBlocking {
        MockWebServer().use { server ->
            // Linux may resolve localhost to both loopback families. After the
            // injected disconnect OkHttp can choose ::1, which this server did
            // not bind. Pin both ends so this checks body recovery, not DNS.
            server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            val base = "http://127.0.0.1:${server.port}/"
            HttpFittingApi(base).use { api ->
                server.enqueue(MockResponse().setHeader("Content-Type","image/png")
                    .setBody(okio.Buffer().write(ByteArray(8192) { 17 })).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                try { api.download("test","${base}asset.png"); fail("Truncated image must fail") }
                catch (_: IOException) { }
                assertEquals("/asset.png",server.takeRequest(3,TimeUnit.SECONDS)?.path)
                server.enqueue(MockResponse().setHeader("Content-Type","image/png").setBody("complete"))
                assertArrayEquals("complete".toByteArray(),api.download("test","${base}asset.png"))
                assertEquals(2,server.requestCount)
            }
        }
    }
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
