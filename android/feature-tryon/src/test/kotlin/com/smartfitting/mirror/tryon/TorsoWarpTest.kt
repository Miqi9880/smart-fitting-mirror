package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.*
import org.junit.Assert.*
import org.junit.Test

class TorsoWarpTest {
    private val garment = Garment("blue", "Blue", GarmentCategory.UPPER_BODY, emptyList(), "http://local/blue.png",
        PreviewImage("http://local/blue.png", 600, 800,
            GarmentAnchors(Anchor(.75f,.15f), Anchor(.25f,.15f), Anchor(.7f,.85f), Anchor(.3f,.85f))), null)
    private fun frame(shift: Float = 0f, scale: Float = 1f, width: Int = 480, height: Int = 640): VisionFrame {
        fun p(x: Float, y: Float) = Landmark(.5f + (x-.5f)*scale+shift, .45f+(y-.45f)*scale, 1f)
        return VisionFrame(sessionId = "s", frameId = 1, timestampMs = 0, image = ImageMetadata("i",width,height),
            trackingState = TrackingState.TRACKING,
            keypoints = Keypoints(leftShoulder = p(.65f,.25f), rightShoulder = p(.35f,.25f), leftHip = p(.6f,.65f), rightHip = p(.4f,.65f)))
    }
    @Test fun translationKeepsSizeAndMovesEveryAnchor() {
        val a = TorsoWarp.points(frame(), garment)!!
        val b = TorsoWarp.points(frame(.1f), garment)!!
        for (index in a.destination.indices) {
            assertEquals(if (index % 2 == 0) 47.9f else 0f, b.destination[index]-a.destination[index], .001f)
        }
        assertArrayEquals(a.source, b.source, 0f)
    }
    @Test fun scaleAndInputDimensionsPreserveNormalizedCorrespondences() {
        val first = TorsoWarp.points(frame(), garment)!!
        val larger = TorsoWarp.points(frame(scale = 1.2f), garment)!!
        assertEquals(1.2f, (larger.destination[0]-larger.destination[2])/(first.destination[0]-first.destination[2]), .001f)
        val landscape = TorsoWarp.points(frame(width = 640,height = 480), garment)!!
        assertEquals(.5f+.65f*639, landscape.destination[0], .001f)
        assertEquals(.5f+.25f*479, landscape.destination[1], .001f)
    }
    @Test fun missingLostAndCrossedTorsoNeverRenderOldGarment() {
        val normal = frame()
        val partial = normal.copy(trackingState = TrackingState.PARTIAL, keypoints = normal.keypoints.copy(leftShoulder = null))
        val lost = normal.copy(trackingState = TrackingState.LOST, keypoints = Keypoints())
        val crossed = normal.copy(keypoints = normal.keypoints.copy(leftHip = normal.keypoints.rightHip, rightHip = normal.keypoints.leftHip))
        assertNull(TorsoWarp.points(partial, garment))
        assertNull(TorsoWarp.points(lost, garment))
        assertNull(TorsoWarp.points(crossed, garment))
        assertNotNull(TorsoWarp.points(normal, garment))
    }
    @Test fun degenerateAssetCannotProduceExplodingPerspective() {
        val bad = garment.copy(previewImage = garment.previewImage.copy(anchors = GarmentAnchors(Anchor(.5f,.5f),Anchor(.5f,.5f),Anchor(.5f,.5f),Anchor(.5f,.5f))))
        assertNull(TorsoWarp.points(frame(),bad))
    }
}
