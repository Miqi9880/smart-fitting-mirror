package com.smartfitting.mirror.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.smartfitting.mirror.contracts.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Main-thread controls for a deterministic, explicitly synthetic integration source. */
class SyntheticVisionSource : VisionSource<BitmapPixels> {
    override val capabilities = VisionCapabilities(source = VisionSourceKind.MOCK, rgb = true, pose = true,
        personMask = false, alignedDepth = false)
    var settings = SyntheticSettings()
        private set
    var playing = true
        private set
    val lastSampleStep: Long? get() = run?.lastStep
    private val handler = Handler(Looper.getMainLooper())
    private val latest = LatestFrame<BitmapPixels>()
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val generation = SessionGeneration()
    private var run: Run? = null
    private var closed = false
    private class Run(val ticket: Long, val sessionId: String,
        val onFrame: (FrameLease<BitmapPixels>) -> Unit, val onStatus: (VisionStatus) -> Unit) {
        var frameId = 0L
        var nextStep = 0L
        var lastStep: Long? = null
    }
    private val tick = object : Runnable {
        override fun run() {
            val current = run ?: return
            emit(current)
            if (run === current && playing) schedule()
        }
    }

    fun configure(settings: SyntheticSettings) {
        checkUsable()
        if (this.settings == settings) return
        this.settings = settings
        seekTo(0)
    }
    fun setPlaying(value: Boolean) {
        checkUsable()
        if (playing == value) return
        playing = value
        handler.removeCallbacks(tick)
        // A paused start still needs an initial image.
        if (run?.lastStep == null && run != null) handler.post(tick)
        else if (playing && run != null) schedule()
    }
    fun step() {
        checkUsable()
        check(!playing) { "Pause before stepping" }
        handler.removeCallbacks(tick)
        run?.let { emit(it) }
    }
    fun seekTo(step: Long) {
        checkUsable()
        require(step >= 0)
        handler.removeCallbacks(tick)
        val current = run ?: return
        current.nextStep = step
        emit(current)
        if (run === current && playing) schedule()
    }
    private fun schedule() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, SyntheticScene.STEP_MS)
    }
    override fun start(sessionId: String, onFrame: (FrameLease<BitmapPixels>) -> Unit,
                       onStatus: (VisionStatus) -> Unit) {
        checkUsable()
        require(sessionId.isNotBlank())
        stop()
        val current = Run(generation.begin(), sessionId, onFrame, onStatus)
        run = current
        onStatus(VisionStatus(SourceState.READY, message = "合成样例：非摄像头、非模型识别；可暂停、单步或从头重放"))
        if (run === current) handler.post(tick)
    }
    private fun emit(current: Run) {
        if (run !== current) return
        val step = current.nextStep++
        current.lastStep = step
        val points = SyntheticScene.sample(settings.scenario, step)
        val width = settings.viewport.width
        val height = settings.viewport.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(20, 32, 45))
        val paint = Paint().apply { color = Color.rgb(40, 58, 72); strokeWidth = 1f }
        for (x in 0..width step 40) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint)
        for (y in 0..height step 40) canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
        paint.color = Color.rgb(110, 160, 180)
        canvas.drawCircle(70f, 70f, 22f, paint)
        val frameId = ++current.frameId
        val metadata = VisionFrame(sessionId = current.sessionId, frameId = frameId,
            timestampMs = SystemClock.elapsedRealtime(), image = ImageMetadata("synthetic_${current.ticket}_$frameId", width, height),
            trackingState = points.state(), keypoints = points)
        val frame = FrameLease(metadata, BitmapPixels(bitmap)) { it.bitmap.recycle() }
        latest.offer(frame)
        try { current.onFrame(frame) } finally { frame.release() }
    }
    override fun captureOriginal(callback: (Result<CapturedPhoto>) -> Unit) {
        checkMain()
        val current = run
        val frame = latest.acquire()
        if (current == null || frame == null) {
            frame?.release()
            callback(Result.failure(IllegalStateException("还没有样例帧")))
            return
        }
        captureExecutor.execute {
            val result = runCatching {
                val bytes = ByteArrayOutputStream().use {
                    check(frame.pixels.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
                }
                CapturedPhoto(frame.metadata.sessionId, frame.metadata.frameId, frame.metadata.image.width,
                    frame.metadata.image.height, "image/png", bytes)
            }
            frame.release()
            handler.post {
                if (generation.accepts(current.ticket)) callback(result)
                else {
                    result.getOrNull()?.close()
                    callback(Result.failure(IllegalStateException("会话已结束，拍照结果已清理")))
                }
            }
        }
    }
    override fun stop() {
        checkMain()
        val previous = run
        run = null
        generation.end()
        handler.removeCallbacks(tick)
        latest.clear()
        previous?.onStatus?.invoke(VisionStatus(SourceState.STOPPED, message = "样例已停止，帧已清理"))
    }
    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        stop()
        captureExecutor.shutdown()
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun checkUsable() { checkMain(); check(!closed) { "Source is closed" } }
}
