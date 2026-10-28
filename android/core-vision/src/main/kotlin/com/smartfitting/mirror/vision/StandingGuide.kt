package com.smartfitting.mirror.vision

import com.smartfitting.mirror.contracts.*

enum class BodyPresence { PRESENT, ABSENT, UNCERTAIN }
enum class StandingCue { NO_PERSON, UNCERTAIN, HEAD, TORSO, LEGS, OUTSIDE, CENTER, READY, TOO_NEAR, TOO_FAR }

/** Scalar feedback only: no frame, pixels or landmarks retained by the UI. */
data class StandingAssessment(val presence: BodyPresence, val cue: StandingCue, val distanceM: Float? = null)

/** Only install a range after site calibration; RGB body size is never a distance measurement. */
data class StandingRange(val nearM: Float, val farM: Float) {
    init { require(nearM.isFinite() && farM.isFinite() && nearM > 0 && farM > nearM) }
}

object StandingGuide {
    fun assess(frame: VisionFrame, alignedDepthAvailable: Boolean = false,
               calibratedRange: StandingRange? = null): StandingAssessment {
        fun reliable(point: Landmark?) = point != null && point.confidence >= .6f
        val points = frame.keypoints
        val torso = points.torso()
        val presence = when {
            torso.count(::reliable) >= 2 -> BodyPresence.PRESENT
            frame.trackingState == TrackingState.LOST && frame.standingHint != StandingHint.OUT_OF_FRAME -> BodyPresence.ABSENT
            else -> BodyPresence.UNCERTAIN
        }
        val distance = frame.distanceM.takeIf { alignedDepthAvailable && frame.depth != null }
        fun result(cue: StandingCue) = StandingAssessment(presence, cue, distance)
        if (presence == BodyPresence.ABSENT) return result(StandingCue.NO_PERSON)
        if (frame.standingHint == StandingHint.OUT_OF_FRAME) return result(StandingCue.OUTSIDE)
        if (presence == BodyPresence.UNCERTAIN) return result(StandingCue.UNCERTAIN)
        if (!torso.all(::reliable)) return result(StandingCue.TORSO)
        val head = listOf(points.leftEar, points.rightEar)
        if (!head.all(::reliable)) return result(StandingCue.HEAD)
        val legs = listOf(points.leftKnee, points.rightKnee, points.leftAnkle, points.rightAnkle)
        if (!legs.all(::reliable)) return result(StandingCue.LEGS)
        val required = (torso + head + legs).filterNotNull()
        if (required.any { it.x !in .06f.. .94f || it.y !in .03f.. .97f }) return result(StandingCue.OUTSIDE)
        fun y(vararg values: Landmark?) = values.filterNotNull().map { it.y }.average()
        val headY = y(points.leftEar, points.rightEar)
        val shoulderY = y(points.leftShoulder, points.rightShoulder)
        val hipY = y(points.leftHip, points.rightHip)
        val kneeY = y(points.leftKnee, points.rightKnee)
        val ankleY = y(points.leftAnkle, points.rightAnkle)
        if (!(headY + .01 < shoulderY && shoulderY + .03 < hipY && hipY + .03 < kneeY && kneeY + .03 < ankleY))
            return result(StandingCue.UNCERTAIN)
        val center = torso.filterNotNull().map { it.x }.average()
        if (center !in .35.. .65) return result(StandingCue.CENTER)
        if (distance != null && calibratedRange != null) {
            if (distance < calibratedRange.nearM) return result(StandingCue.TOO_NEAR)
            if (distance > calibratedRange.farM) return result(StandingCue.TOO_FAR)
        }
        return result(StandingCue.READY)
    }
}
