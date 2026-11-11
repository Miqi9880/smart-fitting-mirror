package com.smartfitting.mirror.vision

import com.smartfitting.mirror.contracts.*
import org.junit.Assert.*
import org.junit.Test

class DepthGeometryTest {
    @Test fun scalesUnsignedY16AndRotatesWithTheColorFrame() {
        val raw = byteArrayOf(0, 0, 0xd0.toByte(), 0x07, 4, 0, 8, 0, 12, 0, 16, 0)
        val depth = DepthGeometry.millimeterPlane("depth", 3, 2, raw, .5f, 90)
        assertEquals(2, depth.metadata.width)
        assertEquals(3, depth.metadata.height)
        assertEquals(listOf(4, 0, 6, 1000, 8, 2),
            (0 until 6).map { depth.millimetersAt(it % 2, it / 2) })
    }

    @Test fun foregroundTorsoMedianRejectsMissingAndInconsistentDepth() {
        val pose = standingPose()
        val width = 80
        val height = 80
        fun plane(sample: (Int, Int) -> Int): AlignedDepthPixels {
            val bytes = ByteArray(width * height * 2) { index ->
                val pixel = index / 2
                val mm = sample(pixel % width, pixel / width)
                (mm ushr ((index % 2) * 8)).toByte()
            }
            return DepthGeometry.millimeterPlane("d", width, height, bytes, 1f, 0)
        }
        val maskMeta = PlaneMetadata("mask", width, height, width, "foreground_uint8")
        val blocked = PersonMaskPixels(maskMeta, ByteArray(width * height))
        val foreground = PersonMaskPixels(maskMeta, ByteArray(width * height) { 255.toByte() })
        val depth = plane { x, _ -> if (x in 38..42) 0 else 2150 }
        assertEquals(2.15f, DepthGeometry.torsoDistanceM(depth, pose, foreground)!!, .001f)
        assertNull(DepthGeometry.torsoDistanceM(depth, pose, blocked))
        assertNull(DepthGeometry.torsoDistanceM(plane { x, _ -> if (x < 40) 1600 else 4100 }, pose, foreground))
        assertNull(DepthGeometry.torsoDistanceM(depth, pose.copy(leftShoulder = null), foreground))
    }

    @Test fun calibratedTooNearCueCanGuideACroppedPersonButRequiresAlignedDepth() {
        val pose = standingPose().copy(leftAnkle = null, rightAnkle = null)
        val depth = DepthMetadata("depth", 80, 80, 160)
        val frame = VisionFrame(sessionId = "depth-test", frameId = 1, timestampMs = 1,
            image = ImageMetadata("rgb", 80, 80), trackingState = pose.state(), keypoints = pose,
            standingHint = StandingHint.OUT_OF_FRAME, distanceM = 1.1f, depth = depth)
        val range = StandingRange(1.5f, 2.5f)
        assertEquals(StandingCue.TOO_NEAR, StandingGuide.assess(frame, true, range).cue)
        assertEquals(StandingCue.OUTSIDE, StandingGuide.assess(frame, false, range).cue)
        assertNull(StandingGuide.assess(frame, false, range).distanceM)
    }

    private fun standingPose() = Keypoints(
        leftShoulder = Landmark(.33f, .29f, .9f), rightShoulder = Landmark(.67f, .29f, .9f),
        leftHip = Landmark(.35f, .60f, .9f), rightHip = Landmark(.65f, .60f, .9f),
        leftEar = Landmark(.44f, .17f, .9f), rightEar = Landmark(.56f, .17f, .9f),
        leftKnee = Landmark(.38f, .75f, .9f), rightKnee = Landmark(.62f, .75f, .9f),
        leftAnkle = Landmark(.38f, .93f, .9f), rightAnkle = Landmark(.62f, .93f, .9f))
}
