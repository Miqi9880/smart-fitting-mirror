package com.smartfitting.mirror.network

import com.smartfitting.mirror.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.UUID

enum class FittingPhase { IDLE, CONNECTING, READY, UPLOADING, CREATING, WAITING, DOWNLOADING, SUCCEEDED, FAILED, CANCELLED, ENDING, CLOSED, ERROR }
enum class RetryAction { NONE, CONNECT, CREATE, POLL, RESULT, CANCEL, END }
data class FittingState(
    val phase: FittingPhase = FittingPhase.IDLE,
    val garments: List<Garment> = emptyList(), val selectedId: String? = null,
    val job: TryOnJob? = null, val original: ByteArray? = null, val result: ByteArray? = null,
    val message: String = "连接本地服务后开始试衣", val retry: RetryAction = RetryAction.NONE,
    val canCapture: Boolean = false, val connected: Boolean = false,
) {
    val selected get() = garments.firstOrNull { it.garmentId == selectedId }
    val canSelect get() = connected && phase in setOf(FittingPhase.READY, FittingPhase.SUCCEEDED, FittingPhase.FAILED, FittingPhase.CANCELLED)
    val canCancel get() = job?.status?.terminal == false && phase !in setOf(FittingPhase.ENDING, FittingPhase.CREATING)
}

/** Call public methods on the supplied scope's UI dispatcher. The controller owns image bytes. */
class FittingSession(private val api: FittingTransport, private val scope: CoroutineScope,
                     private val pollIntervalMs: Long = 2000,
                     private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) : AutoCloseable {
    private val mutable = MutableStateFlow(FittingState())
    val state: StateFlow<FittingState> = mutable.asStateFlow()
    private var session: Session? = null
    private data class Attempt(val uploadId: String, val garmentId: String, val key: String = UUID.randomUUID().toString())
    private var attempt: Attempt? = null
    private var work: Job? = null
    private var poll: Job? = null
    private var version = 0L
    private var visible = true
    private var disposed = false
    private var retryNotBefore = 0L

    fun connect() {
        if (disposed || session != null || state.value.phase == FittingPhase.CONNECTING) return
        begin(RetryAction.CONNECT) { ticket ->
            mutable.value = FittingState(phase = FittingPhase.CONNECTING, message = "正在创建临时会话")
            val created = api.createSession()
            if (!current(ticket)) return@begin
            session = created
            mutable.value = state.value.copy(connected = true)
            val garments = api.garments(created.sessionToken).items
            if (!current(ticket)) return@begin
            val selected = garments.firstOrNull { it.generationReferenceUrl != null } ?: garments.firstOrNull()
            mutable.value = FittingState(FittingPhase.READY, garments, selected?.garmentId,
                message = "选择上衣；拍摄生成会上传未镜像原图至当前服务", connected = true,
                canCapture = selected?.generationReferenceUrl != null)
        }
    }

    fun select(id: String) {
        val value = state.value
        if (!value.canSelect || value.garments.none { it.garmentId == id }) return
        mutable.value = value.copy(selectedId = id, canCapture = value.garments.first { it.garmentId == id }.generationReferenceUrl != null)
    }

    suspend fun preview(garment: Garment): ByteArray {
        val active = session ?: throw IllegalStateException("请先连接服务")
        require(state.value.selectedId == garment.garmentId)
        val bytes = api.download(active.sessionToken, garment.previewImage.url)
        if (disposed || session !== active || state.value.selectedId != garment.garmentId) {
            bytes.fill(0)
            throw CancellationException("服装或会话已切换")
        }
        return bytes
    }

    /** Takes ownership even when the request is rejected or cancelled before it starts. */
    fun generate(photo: CapturedPhoto) {
        val value = state.value
        val active = session
        val garment = value.selected
        if (disposed || !value.canCapture || active == null || garment?.generationReferenceUrl == null) { photo.close(); return }
        clearImages()
        attempt = null
        mutable.value = state.value.copy(phase = FittingPhase.UPLOADING, original = photo.bytes.copyOf(), job = null,
            canCapture = false, retry = RetryAction.NONE, message = "正在上传原始帧（无衣服与界面叠加）")
        val uploadWork = begin(RetryAction.NONE) { ticket ->
            val uploaded = api.upload(active.sessionToken, photo)
            if (!current(ticket)) return@begin
            attempt = Attempt(uploaded.uploadId, garment.garmentId)
            create(ticket)
        }
        uploadWork?.invokeOnCompletion { photo.close() } ?: photo.close()
    }

    private suspend fun create(ticket: Long) {
        val active = session ?: return
        val request = attempt ?: return
        mutable.value = state.value.copy(phase = FittingPhase.CREATING, canCapture = false,
            message = "正在创建生成任务", retry = RetryAction.NONE)
        try {
            val job = retryRequest { api.createJob(active.sessionToken, CreateJob(request.uploadId, request.garmentId), request.key) }
            if (current(ticket)) accept(job, ticket)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (current(ticket)) fail(error, RetryAction.CREATE)
        }
    }

    fun retry() {
        if (disposed) return
        when (state.value.retry) {
            RetryAction.CONNECT -> {
                // Session creation may have succeeded before catalog retrieval failed.
                if (session == null) connect() else begin(RetryAction.CONNECT) { ticket ->
                    val items = retryRequest { api.garments(session!!.sessionToken).items }
                    if (current(ticket)) {
                        val selected = items.firstOrNull { it.generationReferenceUrl != null } ?: items.firstOrNull()
                        mutable.value = state.value.copy(phase = FittingPhase.READY, garments = items, selectedId = selected?.garmentId,
                            retry = RetryAction.NONE, canCapture = selected?.generationReferenceUrl != null, message = "服务已连接")
                    }
                }
            }
            RetryAction.CREATE -> begin(RetryAction.CREATE) { create(it) }
            RetryAction.POLL, RetryAction.RESULT -> {
                val job = state.value.job ?: return
                begin(state.value.retry) { ticket ->
                    if (job.status == JobStatus.SUCCEEDED) accept(job, ticket)
                    else accept(retryRequest { api.job(session!!.sessionToken, job.jobId) }, ticket)
                }
            }
            RetryAction.CANCEL -> cancel()
            RetryAction.END -> end()
            RetryAction.NONE -> Unit
        }
    }

    fun cancel() {
        val active = session ?: return
        val job = state.value.job ?: return
        if (job.status.terminal) return
        begin(RetryAction.CANCEL) { ticket ->
            mutable.value = state.value.copy(message = "正在请求取消", retry = RetryAction.NONE)
            val latest = retryRequest { api.cancel(active.sessionToken, job.jobId) }
            if (current(ticket)) accept(latest, ticket) // Success may have won the race.
        }
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (!value) { poll?.cancel(); poll = null }
        else if (state.value.phase == FittingPhase.WAITING) startPolling(version)
    }

    fun end() {
        if (disposed) return
        val active = session
        attempt = null
        clearImages()
        mutable.value = state.value.copy(phase = FittingPhase.ENDING, job = null, canCapture = false,
            message = "本地图像已清理，正在结束远端会话", retry = RetryAction.NONE)
        begin(RetryAction.END) { ticket ->
            if (active != null) retryRequest { api.endSession(active.sessionToken) }
            if (current(ticket)) {
                session = null
                mutable.value = FittingState(phase = FittingPhase.CLOSED, message = "会话已结束，服务已确认清理")
            }
        }
    }

    private suspend fun accept(job: TryOnJob, ticket: Long) {
        if (!current(ticket)) return
        mutable.value = state.value.copy(job = job, retry = RetryAction.NONE)
        when (job.status) {
            JobStatus.QUEUED, JobStatus.RUNNING -> {
                mutable.value = state.value.copy(phase = FittingPhase.WAITING,
                    message = "${if (job.isMock) "模拟" else "生成"}任务：${job.status}；可取消或结束", canCapture = false)
                startPolling(ticket)
            }
            JobStatus.SUCCEEDED -> {
                attempt = null
                mutable.value = state.value.copy(phase = FittingPhase.DOWNLOADING, message = "正在下载${if (job.isMock) "模拟" else "生成"}结果")
                try {
                    val bytes = retryRequest { api.download(session!!.sessionToken, job.result!!.imageUrl) }
                    if (!current(ticket)) { bytes.fill(0); return }
                    mutable.value = state.value.copy(phase = FittingPhase.SUCCEEDED, result = bytes,
                        message = if (job.isMock) "模拟结果：静态演示合成，非真实 AI 换装" else "生成完成",
                        canCapture = state.value.selected?.generationReferenceUrl != null)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (current(ticket)) fail(error, RetryAction.RESULT)
                }
            }
            JobStatus.FAILED, JobStatus.CANCELLED -> {
                attempt = null
                mutable.value = state.value.copy(phase = if (job.status == JobStatus.FAILED) FittingPhase.FAILED else FittingPhase.CANCELLED,
                    message = job.error?.message ?: "任务已取消", canCapture = state.value.selected?.generationReferenceUrl != null)
            }
        }
    }

    private fun startPolling(ticket: Long) {
        if (!visible || poll?.isActive == true || !current(ticket)) return
        poll = scope.launch {
            try {
                while (visible && current(ticket)) {
                    delay(pollIntervalMs)
                    val active = session ?: break
                    val job = state.value.job ?: break
                    if (job.status.terminal) break
                    val latest = retryRequest { api.job(active.sessionToken, job.jobId) }
                    if (!current(ticket)) break
                    accept(latest, ticket)
                    if (latest.status.terminal) break
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(ticket)) fail(error, RetryAction.POLL)
            }
        }
    }

    private fun begin(retry: RetryAction, action: suspend (Long) -> Unit): Job? {
        if (disposed) return null
        val ticket = ++version
        work?.cancel(); poll?.cancel(); poll = null
        return scope.launch {
            try { action(ticket) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(ticket)) fail(error, retry)
            }
        }.also { work = it }
    }

    private fun fail(error: Exception, retry: RetryAction) {
        if (error is ApiFailure && error.status == 401) {
            clearImages(); session = null; attempt = null
            mutable.value = FittingState(FittingPhase.CLOSED, message = "服务会话已失效，请重新连接")
            return
        }
        val canRetry = error !is ApiFailure || error.retryable
        mutable.value = state.value.copy(phase = FittingPhase.ERROR, message = error.message ?: "网络请求失败",
            retry = if (canRetry) retry else RetryAction.NONE,
            canCapture = retry == RetryAction.NONE && attempt == null && session != null && state.value.selected?.generationReferenceUrl != null)
    }

    private suspend fun <T> retryRequest(block: suspend () -> T): T {
        repeat(3) { count ->
            val remaining = retryNotBefore - nowMs()
            if (remaining > 0) delay(remaining)
            try { return block() }
            catch (error: IOException) {
                if (error is ApiFailure && !error.retryable) throw error
                val wait = ((error as? ApiFailure)?.retryAfterSeconds?.coerceAtMost(86400)?.times(1000)) ?: (1000L shl count)
                retryNotBefore = nowMs() + wait
                if (count == 2 || wait > 60_000) throw error
            }
        }
        error("unreachable")
    }

    private fun current(ticket: Long) = !disposed && ticket == version
    private fun clearImages() {
        state.value.original?.fill(0); state.value.result?.fill(0)
        mutable.value = state.value.copy(original = null, result = null)
    }

    override fun close() {
        if (disposed) return
        disposed = true; ++version
        work?.cancel(); poll?.cancel(); clearImages()
        val token = session?.sessionToken
        session = null; attempt = null
        mutable.value = FittingState(FittingPhase.CLOSED, message = "本地会话已关闭")
        // ViewModel destruction cancels its scope; make one bounded revocation attempt independently.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(10_000) { if (token != null) api.endSession(token) } }
            catch (_: Exception) { /* The server TTL remains the fallback; no successful deletion is claimed. */ }
            finally { api.close(); coroutineContext.cancel() }
        }
    }
}
