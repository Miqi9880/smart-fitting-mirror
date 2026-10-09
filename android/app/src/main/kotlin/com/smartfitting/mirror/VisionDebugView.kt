package com.smartfitting.mirror

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.vision.BitmapPixels

/** Diagnostic overlay only, not garment rendering. Background and points share one transform. */
class VisionDebugView(context: Context) : View(context) {
    private val latest = LatestFrame<BitmapPixels>()
    var mirrored = true
        set(value) { field = value; invalidate() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(66, 225, 187); strokeWidth = 2.5f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 17 * resources.displayMetrics.scaledDensity }

    fun submit(frame: FrameLease<BitmapPixels>) {
        latest.offer(frame)
        contentDescription = "人体关键点预览：${frame.metadata.trackingState}，${frame.metadata.image.width}×${frame.metadata.image.height}"
        invalidate()
    }
    fun clear() { latest.clear(); contentDescription = "人体关键点预览：无活动帧"; invalidate() }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(13, 22, 32))
        val frame = latest.acquire()
        if (frame == null) { canvas.drawText("选择数据源开始验证", 24f, height / 2f, empty); return }
        try {
            val image = frame.metadata.image
            val transform = FitCenter(image.width, image.height, width, height, mirrored)
            val checkpoint = canvas.save()
            canvas.translate(transform.left, transform.top)
            canvas.scale(transform.scale, transform.scale)
            if (mirrored) { canvas.translate(image.width.toFloat(), 0f); canvas.scale(-1f, 1f) }
            canvas.drawBitmap(frame.pixels.bitmap, null, RectF(0f, 0f, image.width.toFloat(), image.height.toFloat()), imagePaint)
            if (frame.metadata.trackingState != TrackingState.LOST) {
                val points = frame.metadata.keypoints.asMap()
                for ((a, b) in edges) {
                    val p = points[a] ?: continue
                    val q = points[b] ?: continue
                    canvas.drawLine(.5f + p.x * (image.width - 1), .5f + p.y * (image.height - 1),
                        .5f + q.x * (image.width - 1), .5f + q.y * (image.height - 1), line)
                }
                points.values.filterNotNull().forEach { p ->
                    canvas.drawCircle(.5f + p.x * (image.width - 1), .5f + p.y * (image.height - 1), 5f, dot)
                }
            }
            canvas.restoreToCount(checkpoint)
        } finally { frame.release() }
    }
    private companion object {
        val edges = listOf(KeypointName.LEFT_SHOULDER to KeypointName.RIGHT_SHOULDER,
            KeypointName.LEFT_SHOULDER to KeypointName.LEFT_HIP, KeypointName.RIGHT_SHOULDER to KeypointName.RIGHT_HIP,
            KeypointName.LEFT_HIP to KeypointName.RIGHT_HIP, KeypointName.LEFT_SHOULDER to KeypointName.LEFT_ELBOW,
            KeypointName.LEFT_ELBOW to KeypointName.LEFT_WRIST, KeypointName.RIGHT_SHOULDER to KeypointName.RIGHT_ELBOW,
            KeypointName.RIGHT_ELBOW to KeypointName.RIGHT_WRIST)
    }
}
