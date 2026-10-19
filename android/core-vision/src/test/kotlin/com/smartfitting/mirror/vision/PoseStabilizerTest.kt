package com.smartfitting.mirror.vision

import com.smartfitting.mirror.contracts.*
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class PoseStabilizerTest {
    private fun pose(x: Float = .5f, confidence: Float = .9f) = Keypoints(
        leftShoulder = Landmark(x, .25f, confidence),
        rightShoulder = Landmark(.25f, .25f, .9f),
        leftHip = Landmark(.6f, .65f, .9f), rightHip = Landmark(.4f, .65f, .9f))

    @Test fun currentMeasurementIsBlendedAndItsConfidenceIsNotInflated() {
        val filter = PoseStabilizer()
        assertEquals(pose(.4f), filter.update(pose(.4f), 0, 640, 480))
        val current = filter.update(pose(.5f, .55f), 33, 640, 480)
        val shoulder = current.leftShoulder!!
        assertEquals(.46f, shoulder.x, .00001f)
        assertEquals(.25f, shoulder.y, .00001f)
        assertEquals(.55f, shoulder.confidence, 0f)
        assertEquals(TrackingState.TRACKING, current.state())
    }

    @Test fun alternatingMeasurementNoiseHasLessFrameToFrameMovement() {
        val filter = PoseStabilizer()
        filter.update(pose(.5f), 0, 640, 480)
        var rawMovement = 0f
        var filteredMovement = 0f
        var lastRaw = .5f
        var lastFiltered = .5f
        for (index in 1..40) {
            val raw = if (index % 2 == 0) .48f else .52f
            val filtered = filter.update(pose(raw), index * 33L, 640, 480).leftShoulder!!.x
            rawMovement += abs(raw - lastRaw)
            filteredMovement += abs(filtered - lastFiltered)
            lastRaw = raw
            lastFiltered = filtered
        }
        assertTrue("Small alternating noise should be reduced", filteredMovement < rawMovement * .5f)
    }

    @Test fun missingJointIsNullImmediatelyAndReturnsWithoutAnOldPosition() {
        val filter = PoseStabilizer()
        filter.update(pose(.3f), 0, 640, 480)
        val partial = filter.update(pose(.8f).copy(leftShoulder = null), 33, 640, 480)
        assertNull(partial.leftShoulder)
        assertEquals(TrackingState.PARTIAL, partial.state())
        assertEquals(.8f, filter.update(pose(.8f), 66, 640, 480).leftShoulder!!.x, 0f)
    }

    @Test fun lostFrameHasNoStalePointsAndReappearanceStartsAtTheNewPerson() {
        val filter = PoseStabilizer()
        filter.update(pose(.3f), 0, 640, 480)
        val lost = filter.update(Keypoints(), 33, 640, 480)
        assertEquals(Keypoints(), lost)
        assertEquals(TrackingState.LOST, lost.state())
        assertEquals(pose(.8f), filter.update(pose(.8f), 66, 640, 480))
    }

    @Test fun lowConfidenceCurrentJointCannotBeRevivedFromHistory() {
        val filter = PoseStabilizer()
        filter.update(pose(.4f), 0, 640, 480)
        val current = filter.update(pose(.8f, .1f), 33, 640, 480)
        assertNull(current.leftShoulder)
        assertEquals(TrackingState.PARTIAL, current.state())
    }

    @Test fun stalledOrNonIncreasingTimestampsStartFresh() {
        for (next in listOf(99L, 100L, 351L)) {
            val filter = PoseStabilizer()
            filter.update(pose(.3f), 100, 640, 480)
            assertEquals(pose(.8f), filter.update(pose(.8f), next, 640, 480))
        }
    }

    @Test fun aNewImageGeometryCannotBlendOldCoordinateAxes() {
        for ((width, height) in listOf(480 to 640, 1280 to 960)) {
            val filter = PoseStabilizer()
            filter.update(pose(.3f), 0, 640, 480)
            assertEquals(pose(.8f), filter.update(pose(.8f), 33, width, height))
        }
    }

    @Test fun resetAndIndependentCameraRunsHaveNoSharedHistory() {
        val oldRun = PoseStabilizer()
        oldRun.update(pose(.3f), 0, 640, 480)
        assertEquals(pose(.8f), PoseStabilizer().update(pose(.8f), 33, 640, 480))
        oldRun.reset()
        assertEquals(pose(.8f), oldRun.update(pose(.8f), 33, 640, 480))
    }
}
