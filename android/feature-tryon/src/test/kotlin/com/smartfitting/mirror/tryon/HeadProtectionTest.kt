package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.*
import org.junit.Assert.*
import org.junit.Test

class HeadProtectionTest {
    private fun frame(points: Keypoints) = VisionFrame(sessionId="head",frameId=0,timestampMs=0,
        image=ImageMetadata("rgb",640,480),trackingState=points.state(),keypoints=points)
    private val points = Keypoints(rightEar=Landmark(.4f,.2f,.95f),leftEar=Landmark(.6f,.2f,.95f))
    @Test fun currentReliableEarsDefineAHeadSizedEllipseInImagePixels() {
        val region = HeadProtection.region(frame(points))!!
        assertEquals(320f,region.x,.001f)
        assertTrue(region.y<.5f+.2f*479)
        assertEquals(127.8f*.64f,region.radiusX,.001f)
        assertEquals(0f,region.angleDegrees,.001f)
        val moved = HeadProtection.region(frame(points.copy(leftEar=points.leftEar!!.copy(x=.7f),rightEar=points.rightEar!!.copy(x=.5f))))!!
        assertEquals(63.9f,moved.x-region.x,.001f)
    }
    @Test fun missingLowConfidenceCrossedOrDegenerateEarsNeverReuseAnOldRegion() {
        assertNotNull(HeadProtection.region(frame(points)))
        for (bad in listOf(Keypoints(),points.copy(leftEar=null),points.copy(leftEar=points.leftEar!!.copy(confidence=.69f)),
            points.copy(leftEar=points.rightEar),points.copy(leftEar=points.rightEar,rightEar=points.leftEar),
            points.copy(leftEar=points.leftEar!!.copy(y=.9f)))) assertNull(HeadProtection.region(frame(bad)))
    }
    @Test fun tiltedHeadRotatesTheRegionRatherThanUsingDisplayOrientation() {
        val tilted = HeadProtection.region(frame(points.copy(leftEar=points.leftEar!!.copy(y=.3f))))!!
        assertTrue(tilted.angleDegrees in 10f..40f)
    }
}
