package com.smartfitting.mirror.vision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.smartfitting.mirror.contracts.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Main-thread lifecycle methods; only this adapter binds the RGB camera. */
class CameraRgbSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val frontFacing: Boolean = true,
    private val targetRotation: () -> Int = { Surface.ROTATION_0 },
) : VisionSource<BitmapPixels> {
    override val capabilities = VisionCapabilities(source = VisionSourceKind.RGB_CAMERA,
        rgb = true, pose = true, personMask = false, alignedDepth = false)
    private val main = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val generation = SessionGeneration()
    private val latest = LatestFrame<BitmapPixels>()
    private var provider: ProcessCameraProvider? = null
    private var run: Run? = null
    @Volatile private var latestArrivalMs = 0L
    private var closed = false

    private class Run(val ticket: Long, val sessionId: String, val detector: PoseDetector,
        val onFrame: (FrameLease<BitmapPixels>) -> Unit, val onStatus: (VisionStatus) -> Unit) {
        var analysis: ImageAnalysis? = null
        var frameCounter = 0L
    }

    override fun start(sessionId: String, onFrame: (FrameLease<BitmapPixels>) -> Unit,
                       onStatus: (VisionStatus) -> Unit) {
        checkMain()
        check(!closed) { "Source is closed" }
        require(sessionId.isNotBlank())
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onStatus(VisionStatus(SourceState.ERROR, VisionError.PERMISSION_DENIED, "请授权摄像头后重试"))
            return
        }
        val detector = try {
            PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
        } catch (_: Exception) {
            onStatus(VisionStatus(SourceState.ERROR, VisionError.MODEL_UNAVAILABLE, "人体姿态模型初始化失败"))
            return
        }
        val current = Run(generation.begin(), sessionId, detector, onFrame, onStatus)
        run = current
        onStatus(VisionStatus(SourceState.STARTING, message = "正在启动 RGB 摄像头"))
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!generation.accepts(current.ticket)) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preferred = if (frontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val fallback = if (frontFacing) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val selector = when {
                    cameraProvider.hasCamera(preferred) -> preferred
                    cameraProvider.hasCamera(fallback) -> fallback
                    else -> throw IllegalStateException("No RGB camera")
                }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .setTargetRotation(targetRotation())
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                current.analysis = analysis
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(current, proxy) }
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, analysis)
                onStatus(VisionStatus(SourceState.READY, message = "RGB 已启动；深度与遮挡未启用"))
            } catch (_: Exception) {
                fail(current, VisionError.CAMERA_UNAVAILABLE, "摄像头不可用，请确认设备连接或重试")
            }
        }, main)
    }

    private fun analyze(current: Run, proxy: ImageProxy) {
        if (!generation.accepts(current.ticket)) { proxy.close(); return }
        val timestampMs = proxy.imageInfo.timestamp / 1_000_000
        val bitmap = try { uprightBitmap(proxy.toBitmap(), proxy.imageInfo.rotationDegrees) }
        catch (_: Exception) {
            proxy.close()
            main.execute { fail(current, VisionError.CAMERA_UNAVAILABLE, "无法读取摄像头画面") }
            return
        }
        // Hold the ImageProxy until inference completes: CameraX drops, rather than queues, new frames.
        val input = InputImage.fromBitmap(bitmap, 0)
        try {
            current.detector.process(input).addOnCompleteListener(main) { task ->
                try {
                    if (!generation.accepts(current.ticket)) { bitmap.recycle(); return@addOnCompleteListener }
                    if (!task.isSuccessful) {
                        bitmap.recycle()
                        fail(current, VisionError.MODEL_UNAVAILABLE, "人体姿态推理失败，请重新启动")
                        return@addOnCompleteListener
                    }
                    val points = landmarkTypes.mapNotNull { (name, type) ->
                        task.result.getPoseLandmark(type)?.let {
                            name to PoseMapper.PixelPoint(it.position.x, it.position.y, it.inFrameLikelihood)
                        }
                    }.toMap()
                    val keypoints = PoseMapper.map(points, bitmap.width, bitmap.height)
                    val state = keypoints.state()
                    val frameId = ++current.frameCounter
                    val metadata = VisionFrame(sessionId = current.sessionId, frameId = frameId,
                        timestampMs = timestampMs,
                        image = ImageMetadata("rgb_${current.ticket}_$frameId", bitmap.width, bitmap.height),
                        trackingState = state, keypoints = keypoints,
                        standingHint = PoseMapper.standingHint(points, bitmap.width, bitmap.height))
                    val frame = FrameLease(metadata, BitmapPixels(bitmap)) { it.bitmap.recycle() }
                    latest.offer(frame)
                    latestArrivalMs = SystemClock.elapsedRealtime()
                    try { current.onFrame(frame) } finally { frame.release() }
                } finally { proxy.close() }
            }
        } catch (_: Exception) {
            bitmap.recycle()
            proxy.close()
            main.execute { fail(current, VisionError.MODEL_UNAVAILABLE, "人体姿态模型不可用") }
        }
    }

    override fun captureOriginal(callback: (Result<CapturedPhoto>) -> Unit) {
        checkMain()
        val current = run
        val frame = latest.acquire()
        if (current == null || frame == null || SystemClock.elapsedRealtime() - latestArrivalMs > 1500) {
            frame?.release()
            callback(Result.failure(IllegalStateException("请等待新的摄像头画面")))
            return
        }
        captureExecutor.execute {
            val result = runCatching {
                val bytes = ByteArrayOutputStream().use { stream ->
                    check(frame.pixels.bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream))
                    stream.toByteArray()
                }
                CapturedPhoto(frame.metadata.sessionId, frame.metadata.frameId,
                    frame.metadata.image.width, frame.metadata.image.height, "image/jpeg", bytes)
            }
            frame.release()
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
        if (!generation.accepts(current.ticket)) return
        teardown()
        current.onStatus(VisionStatus(SourceState.ERROR, error, message))
    }
    private fun teardown(): Run? {
        val previous = run
        run = null
        generation.end()
        previous?.analysis?.let { it.clearAnalyzer(); provider?.unbind(it) }
        previous?.detector?.close()
        latest.clear()
        latestArrivalMs = 0
        return previous
    }
    override fun stop() {
        checkMain()
        teardown()?.onStatus?.invoke(VisionStatus(SourceState.STOPPED, message = "会话已结束，临时帧已清理"))
    }
    override fun close() {
        checkMain()
        if (closed) return
        stop()
        closed = true
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Call lifecycle methods on main thread" }

    private companion object {
        val landmarkTypes = mapOf(KeypointName.LEFT_SHOULDER to PoseLandmark.LEFT_SHOULDER,
            KeypointName.RIGHT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
            KeypointName.LEFT_ELBOW to PoseLandmark.LEFT_ELBOW, KeypointName.RIGHT_ELBOW to PoseLandmark.RIGHT_ELBOW,
            KeypointName.LEFT_WRIST to PoseLandmark.LEFT_WRIST, KeypointName.RIGHT_WRIST to PoseLandmark.RIGHT_WRIST,
            KeypointName.LEFT_HIP to PoseLandmark.LEFT_HIP, KeypointName.RIGHT_HIP to PoseLandmark.RIGHT_HIP)
    }
}
