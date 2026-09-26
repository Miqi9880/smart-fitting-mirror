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
import kotlin.math.sin

/** Explicitly synthetic frames for integration without a camera or model. */
class SyntheticVisionSource : VisionSource<BitmapPixels> {
    override val capabilities = VisionCapabilities(source = VisionSourceKind.MOCK, rgb = true, pose = true,
        personMask = false, alignedDepth = false)
    private val handler = Handler(Looper.getMainLooper())
    private val latest = LatestFrame<BitmapPixels>()
    private var runnable: Runnable? = null
    private var onStatus: ((VisionStatus) -> Unit)? = null
    private var counter = 0L
    private var closed = false

    override fun start(sessionId: String, onFrame: (FrameLease<BitmapPixels>) -> Unit,
                       onStatus: (VisionStatus) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed)
        stop()
        this.onStatus = onStatus
        counter = 0
        onStatus(VisionStatus(SourceState.READY, message = "合成样例：非摄像头、非模型识别；每 8 秒模拟一次丢失"))
        val tick = object : Runnable {
            override fun run() {
                if (runnable !== this) return
                counter++
                val shift = (sin(counter / 15.0) * .10).toFloat()
                val lost = counter % 80 in 60L..69L
                fun point(x: Float, y: Float) = Landmark(x + shift, y, .95f)
                val points = if (lost) Keypoints() else Keypoints(
                    point(.65f, .25f), point(.35f, .25f), point(.7f, .43f), point(.3f, .43f),
                    point(.72f, .6f), point(.28f, .6f), point(.6f, .65f), point(.4f, .65f))
                val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(20, 32, 45))
                val paint = Paint().apply { color = Color.rgb(40, 58, 72); strokeWidth = 1f }
                for (x in 0..480 step 40) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 640f, paint)
                for (y in 0..640 step 40) canvas.drawLine(0f, y.toFloat(), 480f, y.toFloat(), paint)
                paint.color = Color.rgb(110, 160, 180)
                canvas.drawCircle(70f, 70f, 22f, paint) // Asymmetric raw marker proves mirror direction.
                val metadata = VisionFrame(sessionId = sessionId, frameId = counter,
                    timestampMs = SystemClock.elapsedRealtime(), image = ImageMetadata("synthetic_$counter", 480, 640),
                    trackingState = points.state(), keypoints = points)
                val frame = FrameLease(metadata, BitmapPixels(bitmap)) { it.bitmap.recycle() }
                latest.offer(frame)
                try { onFrame(frame) } finally { frame.release() }
                if (runnable === this) handler.postDelayed(this, 100)
            }
        }
        runnable = tick
        handler.post(tick)
    }
    override fun captureOriginal(callback: (Result<CapturedPhoto>) -> Unit) {
        val frame = latest.acquire()
        if (frame == null) { callback(Result.failure(IllegalStateException("还没有样例帧"))); return }
        val result = runCatching {
            val bytes = ByteArrayOutputStream().use {
                check(frame.pixels.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
            }
            CapturedPhoto(frame.metadata.sessionId, frame.metadata.frameId, frame.metadata.image.width,
                frame.metadata.image.height, "image/png", bytes)
        }
        frame.release()
        callback(result)
    }
    override fun stop() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        latest.clear()
        onStatus?.invoke(VisionStatus(SourceState.STOPPED, message = "样例已停止，帧已清理"))
        onStatus = null
    }
    override fun close() { stop(); closed = true }
}
