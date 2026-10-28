package com.smartfitting.mirror.vision

import com.smartfitting.mirror.contracts.*
import org.junit.Assert.*
import org.junit.Test

class StandingGuideTest {
    private fun frame(points: Keypoints = SyntheticScene.sample(SyntheticScenario.STILL, 0),
        hint: StandingHint = StandingHint.UNKNOWN, distance: Float? = null, depth: Boolean = false) = VisionFrame(
        sessionId = "generated", frameId = 1, timestampMs = 1, image = ImageMetadata("generated", 480, 640),
        trackingState = points.state(), keypoints = points, standingHint = hint, distanceM = distance,
        depth = if (depth) DepthMetadata("generated-depth", 480, 640, 960) else null)

    @Test fun completeGeneratedStandingPoseIsReadyWithoutClaimingMeasuredDistance() {
        for (viewport in SyntheticViewport.entries) {
            val assessment = StandingGuide.assess(frame(SyntheticScene.sample(SyntheticScenario.STILL, 0, viewport)))
            assertEquals(BodyPresence.PRESENT, assessment.presence)
            assertEquals(StandingCue.READY, assessment.cue); assertNull(assessment.distanceM)
        }
    }
    @Test fun missingHeadTorsoAndFeetReceiveSpecificInstructions() {
        val pose = frame().keypoints
        assertEquals(StandingCue.HEAD, StandingGuide.assess(frame(pose.copy(leftEar = null))).cue)
        assertEquals(StandingCue.TORSO, StandingGuide.assess(frame(pose.copy(leftShoulder = null))).cue)
        assertEquals(StandingCue.LEGS, StandingGuide.assess(frame(pose.copy(leftAnkle = null))).cue)
    }
    @Test fun partialOrLowConfidenceEvidenceIsUncertainRatherThanNoPerson() {
        val partial = Keypoints(leftWrist = Landmark(.5f, .5f, .9f))
        assertEquals(BodyPresence.UNCERTAIN, StandingGuide.assess(frame(partial)).presence)
        val uncertain = frame().keypoints.let { Keypoints.fromMap(it.asMap().mapValues { (_, p) -> p?.copy(confidence = .55f) }) }
        assertEquals(BodyPresence.UNCERTAIN, StandingGuide.assess(frame(uncertain)).presence)
        assertEquals(BodyPresence.ABSENT, StandingGuide.assess(frame(Keypoints())).presence)
        assertEquals(BodyPresence.UNCERTAIN, StandingGuide.assess(frame(Keypoints(), StandingHint.OUT_OF_FRAME)).presence)
    }
    @Test fun outsideAndOffCenterDoNotInventDepthBasedMovement() {
        val pose = frame().keypoints
        assertEquals(StandingCue.OUTSIDE, StandingGuide.assess(frame(pose, StandingHint.OUT_OF_FRAME)).cue)
        assertEquals(StandingCue.OUTSIDE, StandingGuide.assess(frame(pose.copy(leftAnkle = Landmark(.6f, .99f, .9f)))).cue)
        val shifted = Keypoints.fromMap(pose.asMap().mapValues { (_, p) -> p?.copy(x = p.x + .2f) })
        assertEquals(StandingCue.CENTER, StandingGuide.assess(frame(shifted)).cue)
        assertNull(StandingGuide.assess(frame(shifted)).distanceM)
    }
    @Test fun collapsedOrReversedLegGeometryNeverClaimsStandingReady() {
        val pose = frame().keypoints
        val collapsed = Keypoints.fromMap(pose.asMap().mapValues { (_, p) -> p?.copy(y = .5f) })
        assertEquals(StandingCue.UNCERTAIN, StandingGuide.assess(frame(collapsed)).cue)
        assertEquals(StandingCue.UNCERTAIN, StandingGuide.assess(frame(pose.copy(leftAnkle = pose.leftHip, rightAnkle = pose.rightHip))).cue)
    }
    @Test fun distanceRequiresAlignedDepthAndACalibratedRangeBeforeNearFarInstructions() {
        val range = StandingRange(1.5f, 2.5f)
        assertEquals(StandingCue.READY, StandingGuide.assess(frame(distance = .5f), true, range).cue)
        assertNull(StandingGuide.assess(frame(distance = .5f), true, range).distanceM)
        assertNull(StandingGuide.assess(frame(distance = .5f, depth = true), false, range).distanceM)
        assertEquals(StandingCue.READY, StandingGuide.assess(frame(distance = .5f, depth = true), true).cue)
        assertEquals(StandingCue.TOO_NEAR, StandingGuide.assess(frame(distance = .5f, depth = true), true, range).cue)
        assertEquals(StandingCue.TOO_FAR, StandingGuide.assess(frame(distance = 3f, depth = true), true, range).cue)
        assertEquals(StandingCue.READY, StandingGuide.assess(frame(distance = 2f, depth = true), true, range).cue)
    }
    @Test fun unreliablePoseNeverBecomesReadyBecauseDepthLooksValid() {
        assertEquals(StandingCue.LEGS, StandingGuide.assess(frame(frame().keypoints.copy(leftAnkle = null), distance = 2f, depth = true), true, StandingRange(1.5f, 2.5f)).cue)
    }
}
