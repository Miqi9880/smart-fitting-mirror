package com.smartfitting.mirror.vision

import com.smartfitting.mirror.contracts.*
import org.junit.Assert.*
import org.junit.Test

class PoseMapperTest {
    @Test fun lowConfidenceAloneDoesNotClaimPersonIsOutOfFrame() {
        val ambiguous = mapOf(KeypointName.LEFT_SHOULDER to PoseMapper.PixelPoint(-20f, 10f, .1f))
        assertEquals(StandingHint.UNKNOWN, PoseMapper.standingHint(ambiguous, 101, 81))
        val outside = mapOf(KeypointName.LEFT_SHOULDER to PoseMapper.PixelPoint(-20f, 10f, .9f))
        assertEquals(StandingHint.OUT_OF_FRAME, PoseMapper.standingHint(outside, 101, 81))
    }
    @Test fun normalizesUnmirroredCoordinatesAndPreservesAnatomicalNames() {
        val points = KeypointName.entries.associateWith { PoseMapper.PixelPoint(60f, 40f, .9f) }
        val mapped = PoseMapper.map(points, 101, 81)
        assertEquals(.6f, mapped.leftShoulder!!.x, .0001f)
        assertEquals(.5f, mapped.leftShoulder!!.y, .0001f)
        assertEquals(TrackingState.TRACKING, mapped.state())
    }
    @Test fun rejectsOffscreenLowConfidenceAndNanRatherThanClamping() {
        val points = mapOf(KeypointName.LEFT_SHOULDER to PoseMapper.PixelPoint(-1f, 20f, 1f),
            KeypointName.RIGHT_SHOULDER to PoseMapper.PixelPoint(20f, 20f, .2f),
            KeypointName.LEFT_HIP to PoseMapper.PixelPoint(Float.NaN, 20f, 1f))
        assertEquals(Keypoints(), PoseMapper.map(points, 101, 81))
    }
    @Test fun missingTorsoIsPartialAndNoPersonIsLost() {
        val points = mapOf(KeypointName.LEFT_SHOULDER to PoseMapper.PixelPoint(60f, 40f, .9f))
        assertEquals(TrackingState.PARTIAL, PoseMapper.map(points, 101, 81).state())
        assertEquals(TrackingState.LOST, PoseMapper.map(emptyMap(), 101, 81).state())
    }
}
