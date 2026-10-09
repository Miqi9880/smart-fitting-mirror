package com.smartfitting.mirror.tryon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.vision.BitmapPixels

/** Main-thread view. Owns the asset bitmap; only retains (never mutates) the camera frame. */
class TryOnView(context: Context) : View(context), TryOnRenderer<BitmapPixels> {
    private val latest = LatestFrame<BitmapPixels>()
    private var garment: Garment? = null
    private var assetId: String? = null
    private var asset: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val warp = Matrix()
    var mirrored: Boolean = true
        set(value) { field = value; invalidate() }

    /** Ownership transfers to the view, including replacement and clear. */
    fun setAsset(item: Garment?, bitmap: Bitmap?) {
        if (bitmap != null) require(item != null && bitmap.width == item.previewImage.width && bitmap.height == item.previewImage.height)
        if (asset !== bitmap) asset?.recycle()
        asset = bitmap
        assetId = item?.garmentId
        garment = item // Selection also changes on a paused input frame.
        invalidate()
    }
    override fun submit(frame: FrameLease<BitmapPixels>, garment: Garment?) {
        latest.offer(frame)
        this.garment = garment
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(25, 39, 49))
        val frame = latest.acquire() ?: return
        try {
            val metadata = frame.metadata
            val fit = FitCenter(metadata.image.width, metadata.image.height, width, height, mirrored)
            canvas.save()
            try {
                canvas.translate(fit.left, fit.top)
                canvas.scale(fit.scale, fit.scale)
                canvas.clipRect(0f, 0f, metadata.image.width.toFloat(), metadata.image.height.toFloat())
                if (mirrored) { canvas.translate(metadata.image.width.toFloat(), 0f); canvas.scale(-1f, 1f) }
                canvas.drawBitmap(frame.pixels.bitmap, 0f, 0f, paint)
                val selected = garment
                val bitmap = asset
                if (selected != null && bitmap != null && assetId == selected.garmentId) {
                    val points = TorsoWarp.points(metadata, selected)
                    if (points != null && warp.setPolyToPoly(points.source, 0, points.destination, 0, 4)) {
                        canvas.drawBitmap(bitmap, warp, paint)
                    }
                }
            } finally { canvas.restore() }
        } finally { frame.release() }
    }
    override fun clear() {
        latest.clear()
        garment = null
        setAsset(null, null)
        invalidate()
    }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
}
