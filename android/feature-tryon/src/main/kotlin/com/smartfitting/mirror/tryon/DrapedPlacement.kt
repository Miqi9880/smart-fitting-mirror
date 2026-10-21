package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.*
import kotlin.math.abs
import kotlin.math.hypot

/** Current-frame 2-D panels. Skirts stay continuous; shorts end on the thighs. */
internal object DrapedPlacement {
    private data class P(val x: Float, val y: Float) {
        operator fun plus(p: P) = P(x+p.x,y+p.y)
        operator fun minus(p: P) = P(x-p.x,y-p.y)
        operator fun times(t: Float) = P(x*t,y*t)
        fun length() = hypot(x,y)
    }
    private data class Row(val y: Float, val rightX: Float, val leftX: Float, val right: P, val left: P)
    private fun point(frame: VisionFrame, value: Landmark?): P? = value?.takeIf { it.confidence >= .6f }?.let {
        P(.5f+it.x*(frame.image.width-1),.5f+it.y*(frame.image.height-1))
    }
    private fun source(item: Garment, name: String): P? = item.previewImage.attachmentAnchors[name]?.let {
        P(.5f+it.x*(item.previewImage.width-1),.5f+it.y*(item.previewImage.height-1))
    }
    private fun hem(frame: VisionFrame, left: Boolean, ratio: Float): P? {
        val k = frame.keypoints
        val hip = point(frame,if (left) k.leftHip else k.rightHip) ?: return null
        val knee = point(frame,if (left) k.leftKnee else k.rightKnee) ?: return null
        if ((knee-hip).length()<8 || knee.y-hip.y<4) return null
        if (ratio<=1) return hip+(knee-hip)*ratio
        val ankle = point(frame,if (left) k.leftAnkle else k.rightAnkle) ?: return null
        if ((ankle-knee).length()<8 || ankle.y-knee.y<4) return null
        return knee+(ankle-knee)*(ratio-1)
    }
    fun patches(frame: VisionFrame, item: Garment): List<OutfitPlacement.Patch> {
        val ratio = item.previewImage.hemRatio ?: return emptyList()
        if (ratio !in .1f..2f) return emptyList()
        val sr = source(item,"right_hip") ?: return emptyList()
        val sl = source(item,"left_hip") ?: return emptyList()
        val er = source(item,"right_hem") ?: return emptyList()
        val el = source(item,"left_hem") ?: return emptyList()
        val tr = point(frame,frame.keypoints.rightHip) ?: return emptyList()
        val tl = point(frame,frame.keypoints.leftHip) ?: return emptyList()
        val hr = hem(frame,false,ratio) ?: return emptyList()
        val hl = hem(frame,true,ratio) ?: return emptyList()
        val span = tl-tr
        if (sl.x-sr.x<2 || span.x<8 || span.length()>frame.image.width*.8f || el.x-er.x<2) return emptyList()
        // Both hems share the skirt's centre/width, never split into moving legs.
        val centre = (hr+hl)*.5f
        val half = span*((el.x-er.x)/(sl.x-sr.x)*.5f)
        val rows = mutableListOf<Row>()
        if (item.category==GarmentCategory.DRESS) {
            val a = item.previewImage.anchors ?: return emptyList()
            val shoulderR = point(frame,frame.keypoints.rightShoulder) ?: return emptyList()
            val shoulderL = point(frame,frame.keypoints.leftShoulder) ?: return emptyList()
            if (shoulderL.x-shoulderR.x<8 || (shoulderL-shoulderR).length()>frame.image.width*.8f ||
                tr.y-shoulderR.y<8 || tl.y-shoulderL.y<8) return emptyList()
            rows += Row(.5f+(a.rightShoulder.y+a.leftShoulder.y)*.5f*(item.previewImage.height-1),
                .5f+a.rightShoulder.x*(item.previewImage.width-1),.5f+a.leftShoulder.x*(item.previewImage.width-1),shoulderR,shoulderL)
        }
        rows += Row((sr.y+sl.y)*.5f,sr.x,sl.x,tr,tl)
        rows += Row((er.y+el.y)*.5f,er.x,el.x,centre-half,centre+half)
        return panels(item,rows,false,span)
    }
    fun shorts(frame: VisionFrame, item: Garment): List<OutfitPlacement.Patch> {
        val ratio = item.previewImage.hemRatio ?: return emptyList()
        if (ratio !in .1f..1f) return emptyList()
        val sr=source(item,"right_hip") ?: return emptyList()
        val sl=source(item,"left_hip") ?: return emptyList()
        val er=source(item,"right_hem") ?: return emptyList()
        val el=source(item,"left_hem") ?: return emptyList()
        val tr=point(frame,frame.keypoints.rightHip) ?: return emptyList()
        val tl=point(frame,frame.keypoints.leftHip) ?: return emptyList()
        val hr=hem(frame,false,ratio) ?: return emptyList()
        val hl=hem(frame,true,ratio) ?: return emptyList()
        val span=tl-tr
        if (span.x<8 || span.length()>frame.image.width*.8f || hl.x-hr.x<4) return emptyList()
        return panels(item,listOf(Row((sr.y+sl.y)*.5f,sr.x,sl.x,tr,tl),
            Row((er.y+el.y)*.5f,er.x,el.x,hr,hl)),true,span)
    }
    private fun panels(item: Garment, controls: List<Row>, split: Boolean, hip: P): List<OutfitPlacement.Patch> {
        if (controls.any { it.leftX-it.rightX<2 } || controls.zipWithNext().any { (a,b) -> b.y-a.y<2 }) return emptyList()
        fun extend(y: Float, a: Row, b: Row): Row {
            val t=(y-a.y)/(b.y-a.y)
            return Row(y,a.rightX+(b.rightX-a.rightX)*t,a.leftX+(b.leftX-a.leftX)*t,
                a.right+(b.right-a.right)*t,a.left+(b.left-a.left)*t)
        }
        if (controls.first().y<2 || controls.last().y>item.previewImage.height-2) return emptyList()
        val rows=listOf(extend(.5f,controls[0],controls[1]))+controls+
            extend(item.previewImage.height-.5f,controls[controls.size-2],controls.last())
        val s=mutableListOf<P>(); val d=mutableListOf<P>()
        val columns=if (split) 3 else 2
        val hipWidth=controls[0].leftX-controls[0].rightX
        for (row in rows) {
            if (row.leftX-row.rightX<2) return emptyList()
            val xs=if (split) listOf(.5f,item.previewImage.width/2f,item.previewImage.width-.5f)
                else listOf(.5f,item.previewImage.width-.5f)
            for ((i,x) in xs.withIndex()) {
                s+=P(x,row.y)
                d+=when {
                    !split -> row.right+(row.left-row.right)*((x-row.rightX)/(row.leftX-row.rightX))
                    i==0 -> row.right-hip*((row.rightX-x)/hipWidth)
                    i==2 -> row.left+hip*((x-row.leftX)/hipWidth)
                    else -> row.right+(row.left-row.right)*((x-row.rightX)/(row.leftX-row.rightX))
                }
            }
        }
        val result=mutableListOf<OutfitPlacement.Patch>()
        for (row in 0 until rows.size-1) for (col in 0 until columns-1) {
            val a=row*columns+col; val b=a+1; val c=a+columns; val e=c+1
            for (indices in listOf(intArrayOf(a,b,e),intArrayOf(a,e,c))) {
                fun cross(p: List<P>): Float {
                    val u=p[indices[1]]-p[indices[0]]; val v=p[indices[2]]-p[indices[0]]
                    return u.x*v.y-u.y*v.x
                }
                val area=cross(d)
                if (!area.isFinite() || abs(area)<1 || area*cross(s)<=0 ||
                    indices.any { !d[it].x.isFinite() || !d[it].y.isFinite() }) return emptyList()
                fun flatten(p: List<P>)=indices.flatMap { listOf(p[it].x,p[it].y) }.toFloatArray()
                result+=OutfitPlacement.Patch(flatten(s),flatten(d))
            }
        }
        return result
    }
}
