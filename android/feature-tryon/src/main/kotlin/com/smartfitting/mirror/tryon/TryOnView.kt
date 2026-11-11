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
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
    private val headPath = Path()
    private val allowedHeadPath = Path()
    private val headTransform = Matrix()
    private val armPath = Path()
    private val armLine = Path()
    private val armShape = Path()
    private val armOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val warp = Matrix()
    private val sleeveColumns = 28
    private val sleeveRows = 36
    private val sleeveSource = FloatArray((sleeveColumns + 1) * (sleeveRows + 1) * 2)
    private val sleeveVertices = FloatArray(sleeveSource.size)
    private var sleeveImageWidth = 0
    private var sleeveImageHeight = 0
    private val meshDriver = context.assets.open("reference/tshirt.obj").bufferedReader().use {
        GarmentMeshDriver(GarmentMesh.readObj(it.lineSequence()))
    }
    private val meshPath = Path()
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var depthMask: Bitmap? = null
    private var depthMaskPixels = IntArray(0)
    private var depthMaskFrame: VisionFrame? = null
    var mode: Mode = Mode.PHOTO
        set(value) { field = value; invalidate() }
    private val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; strokeWidth = 2f }
    private val landmarkOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    var showLandmarks: Boolean = false
        set(value) { field = value; invalidate() }
    var outfitFits: Map<GarmentCategory, OutfitFit> = emptyMap()
        set(value) { field = value.toMap(); invalidate() }
    var mirrored: Boolean = true
        set(value) { field = value; invalidate() }
    var protectHead: Boolean = true
        set(value) { field = value; invalidate() }
    var gesturesEnabled = false
    var viewportChanged: ((PreviewViewport) -> Unit)? = null
    var viewport: PreviewViewport = PreviewViewport()
        set(value) {
            val frame = latest.acquire()
            val bounded = try { frame?.let {
                value.bounded(width,height,it.metadata.image.width,it.metadata.image.height)
            } ?: value } finally { frame?.release() }
            if (field != bounded) { field = bounded; viewportChanged?.invoke(bounded); invalidate() }
        }
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var multiplePointers = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (width > 0 && height > 0) viewport = viewport.zoomBy(detector.scaleFactor,
                detector.focusX / width, detector.focusY / height)
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gesturesEnabled) return super.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x; lastTouchY = event.y; multiplePointers = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> multiplePointers = true
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !multiplePointers && !scaleDetector.isInProgress) {
                if (width > 0 && height > 0) viewport = viewport.pan((event.x-lastTouchX)/width,(event.y-lastTouchY)/height)
                lastTouchX = event.x; lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh); viewport = viewport
    }

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
        viewport = viewport // Rebound when a new source has a different aspect ratio.
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
                val view = viewport
                canvas.translate(width * (.5f + view.offsetX), height * (.5f + view.offsetY))
                canvas.scale(view.zoom, view.zoom)
                canvas.translate(-width * .5f, -height * .5f)
                canvas.translate(fit.left, fit.top)
                canvas.scale(fit.scale, fit.scale)
                canvas.clipRect(0f, 0f, metadata.image.width.toFloat(), metadata.image.height.toFloat())
                if (mirrored) { canvas.translate(metadata.image.width.toFloat(), 0f); canvas.scale(-1f, 1f) }
                canvas.drawBitmap(frame.pixels.bitmap, 0f, 0f, paint)
                val selected = garment
                val bitmap = asset
                val currentOutfit = outfit
                if (currentOutfit != null) {
                    val order = listOf(GarmentCategory.LOWER_BODY,GarmentCategory.SHORTS,GarmentCategory.SKIRT,
                        GarmentCategory.UPPER_BODY,GarmentCategory.DRESS,GarmentCategory.HEADWEAR)
                    for (category in order) currentOutfit.firstOrNull { it.category == category }?.let { item ->
                        if (mode == Mode.OBJ_MESH && category == GarmentCategory.UPPER_BODY) drawMesh(canvas,metadata)
                        else layers[item.garmentId]?.let { drawPhoto(canvas,frame,item,it.bitmap) }
                    }
                } else if (mode == Mode.OBJ_MESH) drawMesh(canvas, metadata)
                else if (selected != null && bitmap != null && assetId == selected.garmentId) drawPhoto(canvas,frame,selected,bitmap)
                if (showLandmarks && fit.scale > 0) drawLandmarks(canvas, metadata, fit.scale * view.zoom)
            } finally { canvas.restore() }
        } finally { frame.release() }
    }
    private fun drawPhoto(canvas: Canvas, lease: FrameLease<BitmapPixels>, item: Garment, bitmap: Bitmap) {
        val frame = lease.metadata
        val saved = canvas.save()
        try {
            if (protectHead && item.category == GarmentCategory.UPPER_BODY) clipHead(canvas,frame)
            val torsoLayer = item.category == GarmentCategory.UPPER_BODY || item.category == GarmentCategory.DRESS
            val sleeves = if (item.category == GarmentCategory.UPPER_BODY) SleeveWarp.profile(item) else null
            val handsOnly = sleeves?.longSleeves == true
            val mask = if (torsoLayer && !handsOnly) prepareDepthMask(lease) else null
            if (torsoLayer && mask == null) clipArms(canvas, lease, handsOnly)
            if (mask != null) canvas.saveLayer(0f,0f,frame.image.width.toFloat(),frame.image.height.toFloat(),null)
            for (patch in OutfitPlacement.patches(frame,item,outfitFits[item.category] ?: OutfitFit.defaultFor(item.category))) {
                val count = patch.source.size/2
                if (!warp.setPolyToPoly(patch.source,0,patch.destination,0,count)) continue
                canvas.save()
                try {
                    if (patch.clipToDestination) {
                        patchPath.reset(); patchPath.moveTo(patch.destination[0],patch.destination[1])
                        for (i in 1 until count) patchPath.lineTo(patch.destination[i*2],patch.destination[i*2+1])
                        patchPath.close()
                        canvas.clipPath(patchPath)
                    }
                    if (sleeves == null || !drawSleeves(canvas, frame, item, bitmap, sleeves)) {
                        canvas.drawBitmap(bitmap,warp,paint)
                    }
                } finally { canvas.restore() }
            }
            if (mask != null) canvas.drawBitmap(mask,0f,0f,maskPaint)
        } finally { canvas.restoreToCount(saved) }
    }
    private fun drawSleeves(canvas: Canvas, frame: VisionFrame, item: Garment, bitmap: Bitmap,
                            profile: SleeveWarp.Profile): Boolean {
        if (sleeveImageWidth != bitmap.width || sleeveImageHeight != bitmap.height) {
            sleeveImageWidth = bitmap.width
            sleeveImageHeight = bitmap.height
            var index = 0
            for (row in 0..sleeveRows) for (column in 0..sleeveColumns) {
                sleeveSource[index++] = bitmap.width * column.toFloat() / sleeveColumns
                sleeveSource[index++] = bitmap.height * row.toFloat() / sleeveRows
            }
        }
        warp.mapPoints(sleeveVertices, sleeveSource)
        if (!SleeveWarp.bend(sleeveSource, sleeveVertices, frame, item, profile) { warp.mapPoints(it) }) return false
        canvas.drawBitmapMesh(bitmap, sleeveColumns, sleeveRows, sleeveVertices, 0, null, 0, paint)
        return true
    }
    private fun clipHead(canvas: Canvas, frame: VisionFrame) {
        HeadProtection.region(frame)?.let { head ->
            // Clip only this garment; RGB, independent hats and landmarks remain intact.
            headPath.reset()
            headPath.addOval(RectF(-head.radiusX,-head.radiusY,head.radiusX,head.radiusY),Path.Direction.CW)
            headTransform.reset(); headTransform.postRotate(head.angleDegrees); headTransform.postTranslate(head.x,head.y)
            headPath.transform(headTransform)
            allowedHeadPath.reset()
            allowedHeadPath.addRect(0f,0f,frame.image.width.toFloat(),frame.image.height.toFloat(),Path.Direction.CW)
            if (allowedHeadPath.op(headPath,Path.Op.DIFFERENCE)) canvas.clipPath(allowedHeadPath)
        }
    }
    private fun clipArms(canvas: Canvas, frame: FrameLease<BitmapPixels>, handsOnly: Boolean) {
        val segments = ForegroundArms.segments(frame.metadata, frame.pixels.imagePose, handsOnly)
        if (segments.isEmpty()) return
        armPath.reset()
        for (span in segments) {
            armLine.reset()
            armLine.moveTo(span.x1, span.y1)
            armLine.lineTo(span.x2, span.y2)
            armOutline.strokeWidth = span.radius * 2f
            armShape.reset()
            armOutline.getFillPath(armLine, armShape)
            armPath.op(armShape, Path.Op.UNION)
        }
        val image = frame.metadata.image
        allowedHeadPath.reset()
        allowedHeadPath.addRect(0f, 0f, image.width.toFloat(), image.height.toFloat(), Path.Direction.CW)
        if (allowedHeadPath.op(armPath, Path.Op.DIFFERENCE)) canvas.clipPath(allowedHeadPath)
    }
    private fun prepareDepthMask(frame: FrameLease<BitmapPixels>): Bitmap? {
        val depth = frame.pixels.alignedDepth ?: return null
        if (mode != Mode.PHOTO || frame.metadata.trackingState != TrackingState.TRACKING) return null
        if (depthMaskFrame === frame.metadata) return depthMask
        val distance = frame.metadata.distanceM?.let { (it * 1000).toInt() }
            ?: DepthOcclusion.torsoMillimeters(frame.metadata, depth) ?: return null
        val mm = distance - 20
        if (mm !in 1..65535) return null
        val d = depth.metadata
        val prior = depthMask
        if (prior == null || prior.width != d.width || prior.height != d.height) {
            depthMask?.recycle()
            depthMask = Bitmap.createBitmap(d.width,d.height,Bitmap.Config.ARGB_8888)
            depthMaskPixels = IntArray(Math.multiplyExact(d.width, d.height))
        }
        DepthOcclusion.fillMask(depth, mm, depthMaskPixels)
        depthMask?.setPixels(depthMaskPixels,0,d.width,0,0,d.width,d.height)
        depthMaskFrame = frame.metadata
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
    private fun drawLandmarks(canvas: Canvas, frame: VisionFrame, sceneScale: Float) {
        // Keep points legible when a high-resolution camera frame is fitted down.
        val unit = resources.displayMetrics.density / sceneScale
        landmarkPaint.strokeWidth = 1.5f * unit
        landmarkOutline.strokeWidth = 3f * unit
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
            canvas.drawLine(x(start), y(start), x(end), y(end), landmarkOutline)
            canvas.drawLine(x(start), y(start), x(end), y(end), landmarkPaint)
        }
        points.values.filterNotNull().forEach {
            canvas.drawCircle(x(it), y(it), 4.5f * unit, landmarkOutline)
            canvas.drawCircle(x(it), y(it), 3f * unit, landmarkPaint)
        }
    }
    override fun clear() {
        latest.clear()
        clearLayers(); outfit = null
        depthMask?.recycle()
        depthMask = null
        depthMaskPixels = IntArray(0)
        depthMaskFrame = null
        garment = null
        setAsset(null, null)
        invalidate()
    }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
}
