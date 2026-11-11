package com.smartfitting.mirror.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.smartfitting.mirror.contracts.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Owns one synchronized, already registered RGB and depth sample. */
class RgbdSample(val rgb: Bitmap, val depth: AlignedDepthPixels, val timestampMs: Long)

/** The reader opens and closes its device on the worker thread. Its callback transfers sample ownership. */
fun interface RgbdReader {
    fun stream(active: () -> Boolean, onSample: (RgbdSample) -> Unit)
}

class RgbdVisionSource(context: Context, private val reader: RgbdReader) : VisionSource<BitmapPixels> {
    override val capabilities = VisionCapabilities(source = VisionSourceKind.DEPTH_CAMERA,
        rgb = true, pose = true, personMask = true, alignedDepth = true)
    private val main = ContextCompat.getMainExecutor(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val photoWorker = Executors.newSingleThreadExecutor()
    private val generation = SessionGeneration()
    private val latest = LatestFrame<BitmapPixels>()
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = FrameWatchdog(SystemClock::elapsedRealtime,
        { task, delay -> handler.postDelayed(task, delay); Unit }, { handler.removeCallbacks(it) })
    private var run: Run? = null
    private var latestArrival = 0L
    private var closed = false

    private class Run(val ticket: Long, val id: String,
        val onFrame: (FrameLease<BitmapPixels>) -> Unit, val onStatus: (VisionStatus) -> Unit)

    override fun start(sessionId: String, onFrame: (FrameLease<BitmapPixels>) -> Unit,
                       onStatus: (VisionStatus) -> Unit) {
        checkMain()
        check(!closed)
        require(sessionId.isNotBlank())
        stop()
        val current = Run(generation.begin(), sessionId, onFrame, onStatus)
        run = current
        watchdog.start { first ->
            fail(current, VisionError.CAMERA_UNAVAILABLE, if (first)
                "未收到配准深度画面，请检查 Gemini 2、USB 授权与相机占用后重试"
            else "深度画面已中断，预览已清理；请检查 USB 连接后重试")
        }
        onStatus(VisionStatus(SourceState.STARTING, message = "正在启动 Gemini 2 彩色与深度"))
        worker.execute { stream(current) }
    }

    private fun stream(current: Run) {
        fun active() = generation.accepts(current.ticket)
        if (!active()) return
        val detector = try {
            PoseDetection.getClient(PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
        } catch (_: Exception) {
            main.execute { fail(current, VisionError.MODEL_UNAVAILABLE, "人体姿态模型初始化失败") }
            return
        }
        val segmenter = try {
            Segmentation.getClient(SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).enableRawSizeMask().build())
        } catch (error: Exception) {
            Log.w("RgbdVisionSource", "Segmentation initialization failed", error)
            null
        }
        val stabilizer = PoseStabilizer()
        var frameId = 0L
        try {
            reader.stream(::active) sampleFrame@{ sample ->
                var lease: FrameLease<BitmapPixels>? = null
                try {
                    if (!active()) return@sampleFrame
                    val rgb = sample.rgb
                    val depth = sample.depth
                    require(rgb.width == depth.metadata.width && rgb.height == depth.metadata.height)
                    val input = InputImage.fromBitmap(rgb, 0)
                    val pose = Tasks.await(detector.process(input))
                    val rawPoints = CameraRgbSource.landmarkTypes.mapNotNull { (name, type) ->
                        pose.getPoseLandmark(type)?.let {
                            name to PoseMapper.PixelPoint(it.position.x, it.position.y, it.inFrameLikelihood)
                        }
                    }.toMap()
                    val points = stabilizer.update(PoseMapper.map(rawPoints, rgb.width, rgb.height),
                        sample.timestampMs, rgb.width, rgb.height)
                    val id = ++frameId
                    val mask = if (points.state() != TrackingState.LOST && active()) try {
                        segmenter?.let { maskPixels("mask_${current.ticket}_$id", Tasks.await(it.process(input))) }
                    } catch (error: Exception) {
                        Log.w("RgbdVisionSource", "Segmentation unavailable", error)
                        null
                    } else null
                    val metadata = VisionFrame(sessionId = current.id, frameId = id, timestampMs = sample.timestampMs,
                        image = ImageMetadata("rgbd_${current.ticket}_$id", rgb.width, rgb.height),
                        trackingState = points.state(), keypoints = points,
                        standingHint = PoseMapper.standingHint(rawPoints, rgb.width, rgb.height),
                        distanceM = DepthGeometry.torsoDistanceM(depth, points, mask),
                        mask = mask?.metadata, depth = depth.metadata)
                    val frame = FrameLease(metadata, BitmapPixels(rgb, depth, personMask = mask)) {
                        it.bitmap.recycle()
                        it.alignedDepth?.bytes?.fill(0)
                        it.personMask?.bytes?.fill(0)
                    }
                    lease = frame
                    publish(current, frame)
                } finally {
                    if (lease != null) lease.release()
                    else {
                        sample.rgb.recycle()
                        sample.depth.bytes.fill(0)
                    }
                }
            }
        } catch (error: Exception) {
            Log.w("RgbdVisionSource", "RGB-D stream ended", error)
            main.execute { fail(current, VisionError.CAMERA_UNAVAILABLE,
                "Gemini 2 预览不可用，请检查 USB 授权、连接或相机占用，再重试或切换 RGB 摄像头") }
        } catch (error: LinkageError) {
            Log.w("RgbdVisionSource", "Gemini 2 native library unavailable", error)
            main.execute { fail(current, VisionError.CAMERA_UNAVAILABLE, "当前设备无法载入 Gemini 2 SDK") }
        } finally {
            segmenter?.close()
            detector.close()
        }
    }

    private fun publish(current: Run, frame: FrameLease<BitmapPixels>) {
        val delivered = CountDownLatch(1)
        main.execute {
            try {
                if (!generation.accepts(current.ticket)) return@execute
                latest.offer(frame)
                latestArrival = SystemClock.elapsedRealtime()
                watchdog.frameReceived()
                current.onFrame(frame)
            } finally { delivered.countDown() }
        }
        delivered.await()
    }

    override fun captureOriginal(callback: (Result<CapturedPhoto>) -> Unit) {
        checkMain()
        val current = run
        val frame = latest.acquire()
        if (current == null || frame == null || SystemClock.elapsedRealtime() - latestArrival > 1500) {
            frame?.release()
            callback(Result.failure(IllegalStateException("请等待新的摄像头画面")))
            return
        }
        photoWorker.execute {
            val result = try {
                runCatching {
                    val data = ByteArrayOutputStream().use {
                        check(frame.pixels.bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it))
                        it.toByteArray()
                    }
                    val meta = frame.metadata
                    CapturedPhoto(meta.sessionId, meta.frameId, meta.image.width, meta.image.height, "image/jpeg", data)
                }
            } finally { frame.release() }
            main.execute {
                if (generation.accepts(current.ticket)) callback(result)
                else {
                    result.getOrNull()?.close()
                    callback(Result.failure(IllegalStateException("会话已结束，拍照结果已清理")))
                }
            }
        }
    }

    private fun fail(current: Run, error: VisionError, message: String) {
        if (run !== current) return
        clear()
        current.onStatus(VisionStatus(SourceState.ERROR, error, message))
    }

    private fun clear() {
        generation.end()
        run = null
        latest.clear()
        latestArrival = 0
        watchdog.stop()
    }

    override fun stop() {
        checkMain()
        val previous = run
        clear()
        previous?.onStatus?.invoke(VisionStatus(SourceState.STOPPED, message = "深度预览已停止"))
    }

    override fun close() {
        checkMain()
        if (closed) return
        stop()
        closed = true
        worker.shutdown()
        photoWorker.shutdown()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}

internal fun maskPixels(id: String, mask: SegmentationMask): PersonMaskPixels {
    val width = mask.width
    val height = mask.height
    val confidence = mask.buffer.asFloatBuffer()
    require(confidence.remaining() == width * height)
    val bytes = ByteArray(width * height) {
        val value = confidence.get()
        if (value.isFinite()) (value.coerceIn(0f, 1f) * 255).roundToInt().toByte() else 0
    }
    return PersonMaskPixels(PlaneMetadata(id, width, height, width, "foreground_uint8"), bytes)
}
