package com.smartfitting.mirror.tryon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.View
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.vision.BitmapPixels

/** Main-thread view. Owns the asset bitmap; only retains (never mutates) the camera frame. */
class TryOnView(context: Context) : View(context), TryOnRenderer<BitmapPixels> {
    enum class Mode { PHOTO, OBJ_MESH }
    private val latest = LatestFrame<BitmapPixels>()
    private var garment: Garment? = null
    private var assetId: String? = null
    private var asset: Bitmap? = null
    private data class Layer(val item: Garment, val bitmap: Bitmap)
    private var outfit: List<Garment>? = null
    private val layers = mutableMapOf<String,Layer>()
    private val patchPath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val warp = Matrix()
    private val meshDriver = context.assets.open("reference/tshirt.obj").bufferedReader().use {
        GarmentMeshDriver(GarmentMesh.readObj(it.lineSequence()))
    }
    private val meshPath = Path()
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var depthMask: Bitmap? = null
    private var depthMaskFrame: VisionFrame? = null
    var mode: Mode = Mode.PHOTO
        set(value) { field = value; invalidate() }
    private val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; strokeWidth = 2f }
    var showLandmarks: Boolean = false
        set(value) { field = value; invalidate() }
    var mirrored: Boolean = true
        set(value) { field = value; invalidate() }

    /** Ownership transfers to the view, including replacement and clear. */
    fun setAsset(item: Garment?, bitmap: Bitmap?) {
        if (bitmap != null) require(item != null && bitmap.width == item.previewImage.width && bitmap.height == item.previewImage.height)
        clearLayers(); outfit = null
        if (asset !== bitmap) asset?.recycle()
        asset = bitmap
        assetId = item?.garmentId
        garment = item // Selection also changes on a paused input frame.
        invalidate()
    }
    /** One selection per body region. Removed/replaced images are recycled immediately. */
    fun setOutfit(items: List<Garment>) {
        require(OutfitSelection.compatible(items))
        asset?.recycle(); asset = null; assetId = null
        outfit = items.toList()
        val iterator = layers.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (items.none { it.garmentId == entry.key && it.previewImage == entry.value.item.previewImage }) {
                entry.value.bitmap.recycle(); iterator.remove()
            }
        }
        invalidate()
    }
    /** Transfers ownership, including rejection if the selection changed during decoding. */
    fun setLayerAsset(item: Garment, bitmap: Bitmap) {
        require(bitmap.width == item.previewImage.width && bitmap.height == item.previewImage.height)
        if (outfit?.none { it == item } != false) { bitmap.recycle(); return }
        layers.put(item.garmentId,Layer(item,bitmap))?.bitmap?.takeIf { it !== bitmap }?.recycle()
        invalidate()
    }
    private fun clearLayers() { layers.values.forEach { it.bitmap.recycle() }; layers.clear() }
    override fun submit(frame: FrameLease<BitmapPixels>, garment: Garment?) {
        require(frame.metadata.depth == frame.pixels.alignedDepth?.metadata) { "Depth must belong to this aligned RGB frame" }
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
                val mask = if (outfit?.any { it.category != GarmentCategory.UPPER_BODY } == true) null else prepareDepthMask(frame)
                if (mask != null) canvas.saveLayer(0f,0f,metadata.image.width.toFloat(),metadata.image.height.toFloat(),null)
                val currentOutfit = outfit
                if (currentOutfit != null) {
                    val order = listOf(GarmentCategory.LOWER_BODY,GarmentCategory.SHORTS,GarmentCategory.SKIRT,
                        GarmentCategory.UPPER_BODY,GarmentCategory.DRESS,GarmentCategory.HEADWEAR)
                    for (category in order) currentOutfit.firstOrNull { it.category == category }?.let { item ->
                        if (mode == Mode.OBJ_MESH && category == GarmentCategory.UPPER_BODY) drawMesh(canvas,metadata)
                        else layers[item.garmentId]?.let { drawPhoto(canvas,metadata,item,it.bitmap) }
                    }
                } else if (mode == Mode.OBJ_MESH) drawMesh(canvas, metadata)
                else if (selected != null && bitmap != null && assetId == selected.garmentId) drawPhoto(canvas,metadata,selected,bitmap)
                if (mask != null) { canvas.drawBitmap(mask,0f,0f,maskPaint); canvas.restore() }
                if (showLandmarks) drawLandmarks(canvas, metadata)
            } finally { canvas.restore() }
        } finally { frame.release() }
    }
    private fun drawPhoto(canvas: Canvas, frame: VisionFrame, item: Garment, bitmap: Bitmap) {
        for (patch in OutfitPlacement.patches(frame,item)) {
            val count = patch.source.size/2
            if (!warp.setPolyToPoly(patch.source,0,patch.destination,0,count)) continue
            canvas.save()
            try {
                if (count == 3) {
                    patchPath.reset(); patchPath.moveTo(patch.destination[0],patch.destination[1])
                    patchPath.lineTo(patch.destination[2],patch.destination[3]); patchPath.lineTo(patch.destination[4],patch.destination[5]); patchPath.close()
                    canvas.clipPath(patchPath)
                }
                canvas.drawBitmap(bitmap,warp,paint)
            } finally { canvas.restore() }
        }
    }
    private fun prepareDepthMask(frame: FrameLease<BitmapPixels>): Bitmap? {
        val depth = frame.pixels.alignedDepth
        val distance = frame.metadata.distanceM
        if (depth == null || distance == null || mode != Mode.PHOTO) return null
        val mm = (distance*1000-20).toInt()
        if (mm !in 1..65535) return null
        if (depthMaskFrame !== frame.metadata) {
            val d = depth.metadata
            val alpha = DepthOcclusion.alpha(depth.bytes,d.width,d.height,d.rowStrideBytes,mm)
            val pixels = IntArray(alpha.size) { (alpha[it].toInt() and 255) shl 24 or 0x00FFFFFF }
            depthMask?.recycle()
            depthMask = Bitmap.createBitmap(pixels,d.width,d.height,Bitmap.Config.ARGB_8888)
            depthMaskFrame = frame.metadata
        }
        return depthMask
    }
    private fun drawMesh(canvas: Canvas, frame: VisionFrame) {
        val driven = meshDriver.bind(frame) ?: return
        val points = driven.vertices
        for (face in driven.triangles.sortedBy { face -> face.sumOf { points[it].z.toDouble() } }) {
            val a = points[face[0]]; val b = points[face[1]]; val c = points[face[2]]
            val normal = (b-a).cross(c-a)
            val light = .3f+.7f*(kotlin.math.abs(normal.z)/normal.length().coerceAtLeast(.00001f))
            meshPaint.color = Color.argb(220,(55*light).toInt(),(145*light).toInt(),(210*light).toInt())
            meshPath.reset(); meshPath.moveTo(a.x,a.y); meshPath.lineTo(b.x,b.y); meshPath.lineTo(c.x,c.y); meshPath.close()
            canvas.drawPath(meshPath,meshPaint)
        }
    }
    private fun drawLandmarks(canvas: Canvas, frame: VisionFrame) {
        val points = frame.keypoints.asMap().mapValues { (_, p) -> p?.takeIf { it.confidence >= .5f } }
        fun x(p: Landmark) = .5f + p.x * (frame.image.width - 1)
        fun y(p: Landmark) = .5f + p.y * (frame.image.height - 1)
        val edges = listOf(KeypointName.LEFT_SHOULDER to KeypointName.RIGHT_SHOULDER,
            KeypointName.LEFT_SHOULDER to KeypointName.LEFT_HIP, KeypointName.RIGHT_SHOULDER to KeypointName.RIGHT_HIP,
            KeypointName.LEFT_HIP to KeypointName.RIGHT_HIP,
            KeypointName.LEFT_SHOULDER to KeypointName.LEFT_ELBOW, KeypointName.LEFT_ELBOW to KeypointName.LEFT_WRIST,
            KeypointName.RIGHT_SHOULDER to KeypointName.RIGHT_ELBOW, KeypointName.RIGHT_ELBOW to KeypointName.RIGHT_WRIST,
            KeypointName.LEFT_HIP to KeypointName.LEFT_KNEE,KeypointName.LEFT_KNEE to KeypointName.LEFT_ANKLE,
            KeypointName.RIGHT_HIP to KeypointName.RIGHT_KNEE,KeypointName.RIGHT_KNEE to KeypointName.RIGHT_ANKLE,
            KeypointName.LEFT_EAR to KeypointName.RIGHT_EAR)
        for ((a, b) in edges) {
            val start = points[a] ?: continue
            val end = points[b] ?: continue
            canvas.drawLine(x(start), y(start), x(end), y(end), landmarkPaint)
        }
        points.values.filterNotNull().forEach { canvas.drawCircle(x(it), y(it), 5f, landmarkPaint) }
    }
    override fun clear() {
        latest.clear()
        clearLayers(); outfit = null
        depthMask?.recycle(); depthMask = null; depthMaskFrame = null
        garment = null
        setAsset(null, null)
        invalidate()
    }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
}
