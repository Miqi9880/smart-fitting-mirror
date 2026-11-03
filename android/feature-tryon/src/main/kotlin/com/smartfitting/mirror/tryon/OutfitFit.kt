package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.*
import kotlin.math.hypot

/** Local preview calibration, not a body measurement or a shared HTTP field. */
data class OutfitFit(val width: Float = 1f, val length: Float = 1f, val offset: Float = 0f) {
    init {
        require(width in .6f..2.4f && length in .6f..1.6f && offset in -.75f.. .75f)
    }
    companion object {
        fun defaultFor(category: GarmentCategory) = when (category) {
            GarmentCategory.HEADWEAR -> OutfitFit(1.35f, 1.35f, -.38f)
            GarmentCategory.UPPER_BODY -> OutfitFit(1.25f)
            GarmentCategory.LOWER_BODY -> OutfitFit(1.12f, 1.12f)
            GarmentCategory.DRESS -> OutfitFit(1.30f, 1.05f)
            GarmentCategory.SKIRT, GarmentCategory.SHORTS -> OutfitFit(1.45f, 1.06f, -.18f)
            else -> OutfitFit()
        }
    }
}

/** Apply ease around this frame's head/shoulder/hip line, including head roll. */
internal object FitPlacement {
    fun apply(patches: List<OutfitPlacement.Patch>, frame: VisionFrame, item: Garment,
              fit: OutfitFit): List<OutfitPlacement.Patch> {
        if (patches.isEmpty()) return patches
        val k = frame.keypoints
        val pair = when (item.category) {
            GarmentCategory.HEADWEAR -> k.rightEar to k.leftEar
            GarmentCategory.UPPER_BODY, GarmentCategory.DRESS -> k.rightShoulder to k.leftShoulder
            else -> k.rightHip to k.leftHip
        }
        val right = pair.first ?: return emptyList()
        val left = pair.second ?: return emptyList()
        val rx = .5f + right.x * (frame.image.width - 1)
        val ry = .5f + right.y * (frame.image.height - 1)
        val lx = .5f + left.x * (frame.image.width - 1)
        val ly = .5f + left.y * (frame.image.height - 1)
        val span = hypot(lx-rx, ly-ry)
        if (lx-rx < 8 || !span.isFinite()) return emptyList()
        val ux = (lx-rx)/span; val uy = (ly-ry)/span
        val cx = (rx+lx)/2; val cy = (ry+ly)/2
        val legs = if (item.category == GarmentCategory.LOWER_BODY) legLines(frame,item) ?: return emptyList() else null
        val fitted = patches.map { patch ->
            val dst = patch.destination.copyOf()
            for (i in dst.indices step 2) {
                val dx = dst[i]-cx; val dy = dst[i+1]-cy
                val x = dx*ux+dy*uy
                // Widen each leg's fabric around its own leg line. Between the
                // two leg lines the mapping crosses over continuously from one
                // line to the other, so both edges of every leg keep widening
                // while the crotch fabric stays single-valued (no tear, no fold).
                val across = if (legs == null) x*fit.width else {
                    val rightAxis = legs.axis(patch.source[i+1],false).let { p -> (p.first-cx)*ux+(p.second-cy)*uy }
                    val leftAxis = legs.axis(patch.source[i+1],true).let { p -> (p.first-cx)*ux+(p.second-cy)*uy }
                    val d = leftAxis-rightAxis
                    if (d < 8f) {
                        val pivot = (rightAxis+leftAxis)*.5f
                        pivot+(x-pivot)*fit.width
                    } else {
                        // Crossover half-width: wide enough to bridge the gap
                        // between the legs at ease levels, shrunk for extreme
                        // widths so the crossover stays strictly monotone.
                        val h = minOf(.35f*d,.45f*d/fit.width)
                        val t = ((x-(rightAxis+h))/(d-2*h)).coerceIn(0f,1f)
                        val rightWide = rightAxis+(x-rightAxis)*fit.width
                        val leftWide = leftAxis+(x-leftAxis)*fit.width
                        (1-t)*rightWide+t*leftWide
                    }
                }
                val down = (-dx*uy+dy*ux)*fit.length + fit.offset*span
                dst[i] = cx+across*ux-down*uy
                dst[i+1] = cy+across*uy+down*ux
            }
            patch.copy(destination = dst)
        }
        // Independent leg expansion must not render an inverted/degenerate panel.
        if (legs != null && fitted.any { patch ->
            fun cross(p: FloatArray) = (p[2]-p[0])*(p[5]-p[1])-(p[3]-p[1])*(p[4]-p[0])
            val area=cross(patch.destination)
            patch.destination.any { !it.isFinite() } || kotlin.math.abs(area)<1 || area*cross(patch.source)<=0
        }) return emptyList()
        return fitted
    }

    private class LegLines(val rows: List<Float>, val right: List<Pair<Float,Float>>, val left: List<Pair<Float,Float>>) {
        /** Leg line at source row [y], interpolated along hip-knee-ankle. */
        fun axis(y: Float, leftLeg: Boolean): Pair<Float,Float> {
            val points = if (leftLeg) left else right
            val index = if (y<=rows[1]) 0 else 1
            val t = (y-rows[index])/(rows[index+1]-rows[index])
            val a=points[index]; val b=points[index+1]
            return (a.first+(b.first-a.first)*t) to (a.second+(b.second-a.second)*t)
        }
    }
    private fun legLines(frame: VisionFrame,item: Garment): LegLines? {
        val anchors=item.previewImage.attachmentAnchors
        val rows=listOf("hip","knee","ankle").map { name ->
            val r=anchors["right_$name"] ?: return null
            val l=anchors["left_$name"] ?: return null
            .5f+(r.y+l.y)*.5f*(item.previewImage.height-1)
        }
        if (rows.zipWithNext().any { (a,b) -> b-a<2 }) return null
        val k=frame.keypoints
        fun pixels(points: List<Landmark?>): List<Pair<Float,Float>>? = points.map {
            val p=it?.takeIf { value -> value.confidence>=.6f } ?: return null
            (.5f+p.x*(frame.image.width-1)) to (.5f+p.y*(frame.image.height-1))
        }
        return LegLines(rows,pixels(listOf(k.rightHip,k.rightKnee,k.rightAnkle)) ?: return null,
            pixels(listOf(k.leftHip,k.leftKnee,k.leftAnkle)) ?: return null)
    }
}
