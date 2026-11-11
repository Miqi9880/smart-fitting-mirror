package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.Garment
import com.smartfitting.mirror.contracts.Landmark
import com.smartfitting.mirror.contracts.TrackingState
import com.smartfitting.mirror.contracts.VisionFrame
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Keeps the torso warp, with a smooth bend outside each shoulder seam. */
internal object SleeveWarp {
    data class Profile(val cuffAcross: Float, val cuffDrop: Float, val longSleeves: Boolean)
    private val tee = Profile(.125f, .34f, false)
    private val polo = Profile(.145f, .25f, false)
    private val oxford = Profile(.105f, .61f, true)
    private val sweatshirt = Profile(.16f, .58f, true)
    private val profiles = buildMap {
        for (color in listOf("black", "blue", "red")) put("studio_${color}_tee", tee)
        for (color in listOf("00", "09", "69")) put("studio_pique_polo_$color", polo)
        for (color in listOf("01", "06", "64")) put("studio_oxford_shirt_$color", oxford)
        for (color in listOf("01", "09", "69")) put("studio_crew_sweatshirt_$color", sweatshirt)
    }

    fun profile(item: Garment): Profile? =
        profiles[item.previewImage.url.substringBefore('?').substringAfterLast('/').removeSuffix(".png")]

    fun bend(source: FloatArray, fitted: FloatArray, frame: VisionFrame, item: Garment,
             profile: Profile, mapToFrame: (FloatArray) -> Unit): Boolean {
        if (frame.trackingState != TrackingState.TRACKING) return false
        val anchors = item.previewImage.anchors ?: return false
        val width = item.previewImage.width.toFloat()
        val height = item.previewImage.height.toFloat()
        val pose = frame.keypoints
        var driven = false
        for (side in listOf(-1f, 1f)) {
            val right = side < 0
            val shoulder = (if (right) pose.rightShoulder else pose.leftShoulder).reliable() ?: continue
            val elbow = (if (right) pose.rightElbow else pose.leftElbow).reliable() ?: continue
            val wrist = (if (right) pose.rightWrist else pose.leftWrist).reliable()
            val anchor = if (right) anchors.rightShoulder else anchors.leftShoulder
            val startX = anchor.x * width
            val startY = anchor.y * height
            val cuffX = side * profile.cuffAcross * width
            val cuffY = profile.cuffDrop * height
            val projected = floatArrayOf(startX, startY, startX + cuffX, startY + cuffY)
            mapToFrame(projected)
            val baseX = projected[2] - projected[0]
            val baseY = projected[3] - projected[1]
            val upperX = (elbow.x - shoulder.x) * frame.image.width
            val upperY = (elbow.y - shoulder.y) * frame.image.height
            if (!projected.all { it.isFinite() } || hypot(baseX, baseY) < 5f || hypot(upperX, upperY) < 8f) continue
            val upper = rotation(baseX, baseY, upperX, upperY)
            val lower = if (profile.longSleeves && wrist != null) {
                val x = (wrist.x - elbow.x) * frame.image.width
                val y = (wrist.y - elbow.y) * frame.image.height
                if (hypot(x, y) >= 8f) rotation(baseX, baseY, x, y) else upper
            } else upper
            val upperLength = if (profile.longSleeves) .54f else 1f
            val elbowX = baseX * upperLength
            val elbowY = baseY * upperLength
            val cuffSquared = cuffX * cuffX + cuffY * cuffY
            for (i in source.indices step 2) {
                val dx = source[i] - startX
                val dy = source[i + 1] - startY
                if (side * dx <= width * .012f || dy <= -height * .03f || dy > cuffY + height * .11f) continue
                val along = (dx * cuffX + dy * cuffY) / cuffSquared
                val blend = smooth((side * dx - width * .012f) / (width * .06f)) * smooth(along / .3f)
                if (blend <= 0f) continue
                val x = fitted[i] - projected[0]
                val y = fitted[i + 1] - projected[1]
                val lowerBlend = if (profile.longSleeves) smooth((along - .43f) / .22f) else 0f
                val bentX = upper.x(x, y) * (1f - lowerBlend) +
                    (upper.x(elbowX, elbowY) + lower.x(x - elbowX, y - elbowY)) * lowerBlend
                val bentY = upper.y(x, y) * (1f - lowerBlend) +
                    (upper.y(elbowX, elbowY) + lower.y(x - elbowX, y - elbowY)) * lowerBlend
                fitted[i] += (bentX - x) * blend
                fitted[i + 1] += (bentY - y) * blend
            }
            driven = true
        }
        return driven
    }

    private fun Landmark?.reliable() = this?.takeIf { it.confidence >= .65f }
    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private data class Rotation(val cosine: Float, val sine: Float) {
        fun x(x: Float, y: Float) = x * cosine - y * sine
        fun y(x: Float, y: Float) = x * sine + y * cosine
    }
    private fun rotation(x: Float, y: Float, targetX: Float, targetY: Float): Rotation {
        val angle = atan2(x * targetY - y * targetX, x * targetX + y * targetY).coerceIn(-2f, 2f)
        return Rotation(cos(angle), sin(angle))
    }
}
