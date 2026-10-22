package com.smartfitting.mirror.network

import com.smartfitting.mirror.contracts.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

interface FittingTransport : FittingApi, AutoCloseable {
    /** The caller owns the returned encoded bytes and must clear them after use. */
    suspend fun download(sessionToken: String, url: String): ByteArray
    suspend fun saveGarment(sessionToken: String, item: GarmentWrite, replace: Boolean): Garment =
        throw IOException("服务尚未提供衣柜编辑")
    suspend fun deleteGarment(sessionToken: String, id: String): Unit =
        throw IOException("服务尚未提供衣柜编辑")
}

class ApiFailure(val status: Int, val code: String, override val message: String,
                 val retryable: Boolean, val retryAfterSeconds: Long? = null) : IOException(message)

/** Owns its OkHttp client. No redirects, automatic request replay, logging or disk image cache. */
class HttpFittingApi internal constructor(baseUrl: String, private val client: OkHttpClient) : FittingTransport {
    constructor(baseUrl: String) : this(baseUrl, defaultClient())
    private val root = baseUrl.trim().toHttpUrl().also {
        require(it.encodedPath == "/" && it.query == null && it.fragment == null && it.username.isEmpty() && it.password.isEmpty()) {
            "服务地址只填写协议、主机和端口，例如 http://10.0.2.2:8000"
        }
    }
    // Apply security properties even when a caller supplies a client for timeout testing.
    private val transport = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cache(null).build()

    override suspend fun createSession(): Session = json(request("sessions").post(emptyBody).build())
    override suspend fun garments(sessionToken: String): GarmentList = json(request("garments", sessionToken).get().build())
    override suspend fun saveGarment(sessionToken: String, item: GarmentWrite, replace: Boolean): Garment {
        val body = ContractJson.encodeToString(item).toRequestBody(jsonType)
        val url = root.newBuilder().addPathSegments("api/v1/garments")
            .apply { if (replace) addPathSegment(item.garmentId) }.build()
        return json(Request.Builder().url(url).header("Authorization", "Bearer $sessionToken")
            .apply { if (replace) put(body) else post(body) }.build())
    }
    override suspend fun deleteGarment(sessionToken: String, id: String) {
        val url = root.newBuilder().addPathSegments("api/v1/garments").addPathSegment(id).build()
        exchange(Request.Builder().url(url).header("Authorization", "Bearer $sessionToken").delete().build(), 1024).fill(0)
    }
    override suspend fun upload(sessionToken: String, photo: CapturedPhoto): Upload {
        require(photo.mimeType in setOf("image/png", "image/jpeg") && photo.bytes.size <= 10 * 1024 * 1024)
        // Call cancellation resumes the caller before the socket writer necessarily finishes.
        // Keep an independent array until OkHttp invokes its terminal callback.
        val owned = photo.bytes.copyOf()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", if (photo.mimeType == "image/png") "capture.png" else "capture.jpg",
                owned.toRequestBody(photo.mimeType.toMediaType())).build()
        return json(request("uploads", sessionToken).post(body).build(), owned)
    }
    override suspend fun createJob(sessionToken: String, request: CreateJob, idempotencyKey: String): TryOnJob =
        json(request("tryon-jobs", sessionToken).header("Idempotency-Key", idempotencyKey)
            .post(ContractJson.encodeToString(request).toRequestBody(jsonType)).build())
    override suspend fun job(sessionToken: String, jobId: String): TryOnJob = json(jobRequest(sessionToken, jobId).get().build())
    override suspend fun cancel(sessionToken: String, jobId: String): TryOnJob =
        json(jobRequest(sessionToken, jobId, true).post(emptyBody).build())
    override suspend fun endSession(sessionToken: String) {
        // After a long preview the server may have closed several idle asset
        // sockets. Do not spend every bounded cleanup retry on those old sockets.
        // This client/pool belongs to this session; active calls are not evicted.
        transport.connectionPool.evictAll()
        exchange(request("sessions/current", sessionToken).delete().build(), 1024).fill(0)
    }
    override suspend fun download(sessionToken: String, url: String): ByteArray {
        val target = url.toHttpUrl()
        require(target.scheme == root.scheme && target.host == root.host && target.port == root.port &&
            target.username.isEmpty() && target.password.isEmpty()) { "拒绝向其他来源发送会话凭证" }
        return exchange(Request.Builder().url(target).header("Authorization", "Bearer $sessionToken").get().build(),
            64 * 1024 * 1024, imageOnly = true)
    }

    private fun request(path: String, token: String? = null) = Request.Builder().url(root.resolve("/api/v1/$path")!!)
        .apply { if (token != null) header("Authorization", "Bearer $token") }
    private fun jobRequest(token: String, id: String, cancel: Boolean = false): Request.Builder {
        require(id.isNotBlank() && id.length <= 128)
        val url = root.newBuilder().addPathSegments("api/v1/tryon-jobs").addPathSegment(id)
            .apply { if (cancel) addPathSegment("cancel") }.build()
        return Request.Builder().url(url).header("Authorization", "Bearer $token")
    }
    private suspend inline fun <reified T> json(request: Request, payload: ByteArray? = null): T {
        val bytes = exchange(request, 1024 * 1024, payload)
        return try { ContractJson.decodeFromString(bytes.toString(Charsets.UTF_8)) }
        catch (failure: Exception) { throw IOException("服务响应与接口合同不一致", failure) }
        finally { bytes.fill(0) }
    }

    private suspend fun exchange(request: Request, limit: Int, payload: ByteArray? = null,
                                 imageOnly: Boolean = false): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = transport.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                payload?.fill(0)
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        val body = it.body ?: throw IOException("服务未返回响应内容")
                        val data = readLimited(body, if (it.isSuccessful) limit else 64 * 1024)
                        if (!it.isSuccessful) {
                            val parsed = try { ContractJson.decodeFromString<ErrorResponse>(data.toString(Charsets.UTF_8)).error }
                                catch (_: Exception) { null }
                            data.fill(0)
                            throw ApiFailure(it.code, parsed?.code ?: "HTTP_${it.code}", parsed?.message ?: "服务请求失败（${it.code}）",
                                parsed?.retryable ?: (it.code == 429 || it.code >= 500),
                                it.header("Retry-After")?.toLongOrNull()?.takeIf { value -> value > 0 })
                        }
                        if (imageOnly && body.contentType()?.let { type -> type.type == "image" && type.subtype in setOf("png", "jpeg") } != true) {
                            data.fill(0)
                            throw IOException("结果不是 PNG/JPEG 图片")
                        }
                        data
                    }
                    continuation.resume(bytes) { _, lostBytes, _ -> lostBytes.fill(0) }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } finally { payload?.fill(0) }
            }
        })
    }

    override fun close() {
        transport.dispatcher.cancelAll()
        transport.connectionPool.evictAll()
        transport.dispatcher.executorService.shutdown()
    }
    companion object {
        private val jsonType = "application/json; charset=utf-8".toMediaType()
        private val emptyBody = ByteArray(0).toRequestBody(null)
        private fun defaultClient() = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        private fun readLimited(body: ResponseBody, limit: Int): ByteArray {
            if (body.contentLength() > limit) throw IOException("服务响应超过允许大小")
            return body.byteStream().use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (output.size().toLong() + read > limit) throw IOException("服务响应超过允许大小")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    }
}
