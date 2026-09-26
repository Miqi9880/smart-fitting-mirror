package com.smartfitting.mirror.contracts

import java.util.concurrent.atomic.AtomicInteger

/** A reference owns the pixels until released. A released lease can never be resurrected. */
class FrameLease<P : Any>(val metadata: VisionFrame, pixels: P, private val dispose: (P) -> Unit) {
    private val references = AtomicInteger(1)
    private val storage = pixels
    val pixels: P get() { check(references.get() > 0) { "Frame already released" }; return storage }
    fun retain(): FrameLease<P> {
        while (true) {
            val count = references.get()
            check(count > 0 && count < Int.MAX_VALUE) { "Cannot retain released frame" }
            if (references.compareAndSet(count, count + 1)) return this
        }
    }
    fun release() {
        while (true) {
            val count = references.get()
            check(count > 0) { "Frame released more than retained" }
            if (references.compareAndSet(count, count - 1)) {
                if (count == 1) dispose(storage)
                return
            }
        }
    }
}

/** Stores its own reference to one latest frame; callers acquire independent references. */
class LatestFrame<P : Any> {
    private var current: FrameLease<P>? = null
    @Synchronized fun offer(frame: FrameLease<P>) {
        val old = current
        current = frame.retain()
        old?.release()
    }
    @Synchronized fun acquire(): FrameLease<P>? = current?.retain()
    @Synchronized fun clear() { val old = current; current = null; old?.release() }
}

/** Invalidates asynchronous results whenever a session starts or stops. */
class SessionGeneration {
    private var generation = 0L
    private var active = false
    @Synchronized fun begin(): Long { active = true; return ++generation }
    @Synchronized fun end() { active = false; generation++ }
    @Synchronized fun accepts(ticket: Long) = active && generation == ticket
}

enum class SourceState { STARTING, READY, STOPPED, ERROR }
enum class VisionError { PERMISSION_DENIED, CAMERA_UNAVAILABLE, MODEL_UNAVAILABLE }
data class VisionStatus(val state: SourceState, val error: VisionError? = null, val message: String = "")

/** Encoded raw image owned by the caller. close() zeroes its bytes; never contains UI overlays. */
class CapturedPhoto(val sessionId: String, val frameId: Long, val width: Int, val height: Int,
                    val mimeType: String, private val storage: ByteArray) : AutoCloseable {
    @Volatile private var closed = false
    val bytes: ByteArray get() { check(!closed); return storage }
    override fun close() { if (!closed) { storage.fill(0); closed = true } }
}

interface VisionSource<P : Any> : AutoCloseable {
    val capabilities: VisionCapabilities
    fun start(sessionId: String, onFrame: (FrameLease<P>) -> Unit, onStatus: (VisionStatus) -> Unit)
    fun stop()
    fun captureOriginal(callback: (Result<CapturedPhoto>) -> Unit)
}
interface TryOnRenderer<P : Any> {
    fun submit(frame: FrameLease<P>, garment: Garment?)
    fun clear()
}
