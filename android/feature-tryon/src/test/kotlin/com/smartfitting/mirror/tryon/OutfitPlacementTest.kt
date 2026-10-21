package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.vision.*
import org.junit.Assert.*
import org.junit.Test

class OutfitPlacementTest {
    private fun frame(points: Keypoints = SyntheticScene.sample(SyntheticScenario.STILL,0)) = VisionFrame(
        sessionId="test",frameId=0,timestampMs=0,image=ImageMetadata("rgb",480,640),trackingState=points.state(),keypoints=points)
    private fun item(category: GarmentCategory, anchors: Map<String,Anchor>) = Garment("test","test",category,emptyList(),"http://localhost/test.png",
        PreviewImage("http://localhost/test.png",1000,1400,null,anchors),null)
    private val pants = item(GarmentCategory.LOWER_BODY,mapOf("right_hip" to Anchor(.33f,.12f),"left_hip" to Anchor(.68f,.12f),
        "right_knee" to Anchor(.37f,.55f),"left_knee" to Anchor(.64f,.55f),"right_ankle" to Anchor(.36f,.94f),"left_ankle" to Anchor(.65f,.94f)))
    private val hat = item(GarmentCategory.HEADWEAR,mapOf("right_ear" to Anchor(.12f,.92f),"left_ear" to Anchor(.88f,.92f)))
    @Test fun pantsUseBothLegsAndMoveWithTheCurrentBody() {
        val base = OutfitPlacement.patches(frame(),pants)
        assertEquals(16,base.size)
        val moved = OutfitPlacement.patches(frame(SyntheticScene.sample(SyntheticScenario.TRANSLATE,20)),pants)
        for ((a,b) in base.zip(moved)) {
            assertTrue(a.destination.all { it.isFinite() })
            for (i in a.destination.indices) assertEquals(if (i%2==0) 47.9f else 0f,b.destination[i]-a.destination[i],.001f)
        }
    }
    @Test fun missingOrUnreliableLegsHidePantsEvenWhenTorsoIsTracking() {
        val good=frame(); assertEquals(TrackingState.TRACKING,good.trackingState)
        for (points in listOf(good.keypoints.copy(leftKnee=null),good.keypoints.copy(rightAnkle=null),
            good.keypoints.copy(leftAnkle=good.keypoints.leftAnkle!!.copy(confidence=.1f)))) {
            assertTrue(OutfitPlacement.patches(frame(points),pants).isEmpty())
        }
        val crossed = good.keypoints.copy(leftAnkle=good.keypoints.rightAnkle,rightAnkle=good.keypoints.leftAnkle)
        assertTrue(OutfitPlacement.patches(frame(crossed),pants).isEmpty())
    }
    @Test fun hatUsesHeadIndependentlyOfTheTorsoAndNeverUsesStalePoints() {
        val ears=Keypoints(leftEar=Landmark(.6f,.2f,.95f),rightEar=Landmark(.4f,.2f,.95f))
        assertEquals(TrackingState.PARTIAL,ears.state())
        val patch=OutfitPlacement.patches(frame(ears),hat).single()
        assertEquals(4,patch.source.size)
        assertArrayEquals(floatArrayOf(.5f+.4f*479,.5f+.2f*639,.5f+.6f*479,.5f+.2f*639),patch.destination,.001f)
        assertTrue(OutfitPlacement.patches(frame(ears.copy(leftEar=null)),hat).isEmpty())
        assertTrue(OutfitPlacement.patches(frame(Keypoints()),hat).isEmpty())
    }
    @Test fun accessoriesDoNotAcceptTorsoAnchorsOrDegenerateMetadata() {
        assertTrue(OutfitPlacement.patches(frame(),hat.copy(previewImage=hat.previewImage.copy(attachmentAnchors=emptyMap()))).isEmpty())
        val bad=pants.previewImage.attachmentAnchors.toMutableMap().apply { this["left_hip"]=getValue("right_hip") }
        assertTrue(OutfitPlacement.patches(frame(),pants.copy(previewImage=pants.previewImage.copy(attachmentAnchors=bad))).isEmpty())
        assertNull(TorsoWarp.points(frame(),hat))
    }
}
