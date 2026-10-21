package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.*
import kotlin.math.abs
import kotlin.math.hypot

/** 2-D photo placement. Every patch uses only reliable joints from this frame. */
object OutfitPlacement {
    data class Patch(val source: FloatArray, val destination: FloatArray)
    private data class P(val x: Float, val y: Float) {
        operator fun plus(p: P) = P(x+p.x,y+p.y)
        operator fun minus(p: P) = P(x-p.x,y-p.y)
        operator fun times(t: Float) = P(x*t,y*t)
        fun length() = hypot(x,y)
    }
    fun patches(frame: VisionFrame, item: Garment): List<Patch> = when (item.category) {
        GarmentCategory.UPPER_BODY -> TorsoWarp.points(frame,item)?.let { listOf(Patch(it.source,it.destination)) } ?: emptyList()
        GarmentCategory.HEADWEAR -> head(frame,item)
        GarmentCategory.LOWER_BODY -> legs(frame,item)
        GarmentCategory.DRESS, GarmentCategory.SKIRT -> DrapedPlacement.patches(frame,item)
        GarmentCategory.SHORTS -> DrapedPlacement.shorts(frame,item)
    }
    private fun source(item: Garment, key: String): P? = item.previewImage.attachmentAnchors[key]?.let {
        P(.5f+it.x*(item.previewImage.width-1),.5f+it.y*(item.previewImage.height-1))
    }
    private fun target(frame: VisionFrame, p: Landmark?, threshold: Float = .6f): P? =
        p?.takeIf { it.confidence >= threshold }?.let { P(.5f+it.x*(frame.image.width-1),.5f+it.y*(frame.image.height-1)) }
    private fun floats(vararg p: P) = p.flatMap { listOf(it.x,it.y) }.toFloatArray()
    private fun head(frame: VisionFrame, item: Garment): List<Patch> {
        val sr = source(item,"right_ear") ?: return emptyList()
        val sl = source(item,"left_ear") ?: return emptyList()
        val tr = target(frame,frame.keypoints.rightEar,.7f) ?: return emptyList()
        val tl = target(frame,frame.keypoints.leftEar,.7f) ?: return emptyList()
        // A reversed/edge-on head must not flip or explode the product image.
        if (sl.x-sr.x < 2 || tl.x-tr.x < 8 || (tl-tr).length() > frame.image.width*.65f) return emptyList()
        return listOf(Patch(floats(sr,sl),floats(tr,tl)))
    }
    private fun legs(frame: VisionFrame, item: Garment): List<Patch> {
        val names = listOf("right_hip","left_hip","right_knee","left_knee","right_ankle","left_ankle")
        val src = names.map { source(item,it) ?: return emptyList() }
        val k = frame.keypoints
        val dst = listOf(k.rightHip,k.leftHip,k.rightKnee,k.leftKnee,k.rightAnkle,k.leftAnkle)
            .map { target(frame,it) ?: return emptyList() }
        val hip = dst[1]-dst[0]
        val sourceHipWidth = src[1].x-src[0].x
        if (sourceHipWidth < 2 || hip.x < 8 || hip.length() > frame.image.width*.8f) return emptyList()
        if (dst[3].x-dst[2].x < 4 || dst[5].x-dst[4].x < 4) return emptyList()
        for (i in listOf(0,1,2,3)) if ((dst[i+2]-dst[i]).length() < 8) return emptyList()
        // Five source rows: canvas edge, hip, knee, ankle, canvas edge.
        // This keeps each leg's width tied to hip span rather than leg separation.
        val y = floatArrayOf(.5f,(src[0].y+src[1].y)/2,(src[2].y+src[3].y)/2,
            (src[4].y+src[5].y)/2,item.previewImage.height-.5f)
        if ((0..3).any { y[it+1]-y[it] < 2 }) return emptyList()
        val s = mutableListOf<P>(); val d = mutableListOf<P>()
        for (row in 0..4) {
            val joint = when (row) { 0,1 -> 0; 2 -> 2; else -> 4 }
            var right = dst[joint]; var left = dst[joint+1]
            if (row == 0) {
                val t = (y[0]-y[1])/(y[2]-y[1])
                right += (dst[2]-dst[0])*t; left += (dst[3]-dst[1])*t
            } else if (row == 4) {
                val t = (y[4]-y[3])/(y[3]-y[2])
                right += (dst[4]-dst[2])*t; left += (dst[5]-dst[3])*t
            }
            val middleX = item.previewImage.width/2f
            val ratio = (middleX-src[joint].x)/(src[joint+1].x-src[joint].x)
            if (!ratio.isFinite() || ratio !in .1f.. .9f) return emptyList()
            s += listOf(P(.5f,y[row]),P(middleX,y[row]),P(item.previewImage.width-.5f,y[row]))
            d += listOf(right-hip*((src[joint].x-.5f)/sourceHipWidth),
                right+(left-right)*ratio,
                left+hip*((item.previewImage.width-.5f-src[joint+1].x)/sourceHipWidth))
        }
        val patches = mutableListOf<Patch>()
        for (row in 0..3) for (col in 0..1) {
            val a=row*3+col; val b=a+1; val c=a+3; val e=c+1
            for (tri in listOf(intArrayOf(a,b,e),intArrayOf(a,e,c))) {
                fun cross(p: List<P>) = (p[tri[1]].x-p[tri[0]].x)*(p[tri[2]].y-p[tri[0]].y)-
                    (p[tri[1]].y-p[tri[0]].y)*(p[tri[2]].x-p[tri[0]].x)
                val area = cross(d)
                if (!area.isFinite() || abs(area)<1 || area*cross(s)<=0) return emptyList()
                patches += Patch(floats(*tri.map { s[it] }.toTypedArray()),floats(*tri.map { d[it] }.toTypedArray()))
            }
        }
        return patches
    }
}
