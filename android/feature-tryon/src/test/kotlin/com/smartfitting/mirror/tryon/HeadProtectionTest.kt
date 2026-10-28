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
        assertEquals(127.8f*.58f,region.radiusX,.001f)
        // The bottom stops at the chin instead of reaching into the collar.
        assertEquals(127.8f*.62f,region.y+region.radiusY-(.5f+.2f*479),.01f)
        assertEquals(0f,region.angleDegrees,.001f)
        val moved = HeadProtection.region(frame(points.copy(leftEar=points.leftEar!!.copy(x=.7f),rightEar=points.rightEar!!.copy(x=.5f))))!!
        assertEquals(63.9f,moved.x-region.x,.001f)
    }
    @Test fun inflatedEarSpanIsClampedAboveTheShoulderLineInsteadOfCuttingTheCollar() {
        val wide = points.copy(rightEar=points.rightEar!!.copy(x=.35f,y=.28f),
            leftEar=points.leftEar!!.copy(x=.65f,y=.28f),
            rightShoulder=Landmark(.3f,.5f,.9f),leftShoulder=Landmark(.7f,.5f,.9f))
        val region = HeadProtection.region(frame(wide))!!
        val bottom = region.y+region.radiusY
        val shoulderY = .5f+.5f*479
        assertTrue("Cut must stay above the shoulder line",bottom<shoulderY)
        assertEquals(shoulderY-.4f*639f*.06f,bottom,.01f)
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
