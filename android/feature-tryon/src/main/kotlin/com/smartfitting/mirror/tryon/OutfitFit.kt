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
        return patches.map { patch ->
            val dst = patch.destination.copyOf()
            for (i in dst.indices step 2) {
                val dx = dst[i]-cx; val dy = dst[i+1]-cy
                val across = (dx*ux+dy*uy)*fit.width
                val down = (-dx*uy+dy*ux)*fit.length + fit.offset*span
                dst[i] = cx+across*ux-down*uy
                dst[i+1] = cy+across*uy+down*ux
            }
            patch.copy(destination = dst)
        }
    }
}
