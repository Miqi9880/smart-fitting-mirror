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
    @Test fun trouserEaseWidensOutsideEachOwnLegLineAndKeepsTheCrotchColumnSingleValued() {
        val base=frame().keypoints
        val spread=base.copy(rightKnee=base.rightKnee!!.copy(x=.28f),leftKnee=base.leftKnee!!.copy(x=.72f),
            rightAnkle=base.rightAnkle!!.copy(x=.24f),leftAnkle=base.leftAnkle!!.copy(x=.76f))
        val tilt=base.copy(rightKnee=base.rightKnee!!.copy(x=.36f,y=.68f),leftKnee=base.leftKnee!!.copy(x=.64f,y=.72f),
            rightAnkle=base.rightAnkle!!.copy(x=.33f,y=.85f),leftAnkle=base.leftAnkle!!.copy(x=.67f,y=.89f))
        val rows=listOf(.12f,.55f,.94f).map { .5f+it*1399 }
        fun legX(pose: Keypoints, y: Float, left: Boolean): Float {
            val row=if (y<=rows[1]) 0 else 1
            val t=(y-rows[row])/(rows[row+1]-rows[row])
            val a=if (row==0) (if (left) pose.leftHip!! else pose.rightHip!!) else (if (left) pose.leftKnee!! else pose.rightKnee!!)
            val b=if (row==0) (if (left) pose.leftKnee!! else pose.rightKnee!!) else (if (left) pose.leftAnkle!! else pose.rightAnkle!!)
            return .5f+(a.x+(b.x-a.x)*t)*479
        }
        for (pose in listOf(base,spread,tilt)) for (width in listOf(.6f,.7f,1f,1.12f,1.6f,2.4f)) {
            val raw=OutfitPlacement.patches(frame(pose),pants,OutfitFit())
            val fitted=OutfitPlacement.patches(frame(pose),pants,OutfitFit(width=width))
            assertFalse(raw.isEmpty()); assertEquals(raw.size,fitted.size)
            for ((a,b) in raw.zip(fitted)) for (i in a.source.indices step 2) {
                val right=legX(pose,a.source[i+1],false); val left=legX(pose,a.source[i+1],true)
                val x=a.destination[i]
                val expected=when {
                    x<right -> right+(x-right)*width
                    x>left -> left+(x-left)*width
                    else -> x
                }
                // Inside the two leg lines nothing may move at all: that is what keeps
                // the crotch column shared and the distance between the legs unchanged.
                assertEquals("Ease stays outside this leg's own line, crotch untouched",
                    expected,b.destination[i],if (x in right..left) 0f else .002f)
                assertEquals("Leave the trouser length/height unchanged",a.destination[i+1],b.destination[i+1],.002f)
            }
            // A source point drawn by both panels must have exactly one destination.
            val seen=HashMap<String,Float>()
            for (p in fitted) for (i in p.source.indices step 2) {
                val key="%.2f,%.2f".format(p.source[i],p.source[i+1])
                val previous=seen.put(key,p.destination[i])
                if (previous!=null) assertEquals("Shared source point must stay continuous",previous,p.destination[i],0f)
            }
            val hipY=.5f+(pose.rightHip!!.y+pose.leftHip!!.y)*.5f*639
            val lengthened=OutfitPlacement.patches(frame(pose),pants)
            for ((a,b) in raw.zip(lengthened)) for (i in a.destination.indices step 2) {
                assertEquals("Scale length from hips, not the image origin",hipY+(a.destination[i+1]-hipY)*1.12f,b.destination[i+1],.002f)
            }
        }
    }
    @Test fun hatUsesHeadIndependentlyOfTheTorsoAndNeverUsesStalePoints() {
        val ears=Keypoints(leftEar=Landmark(.6f,.2f,.95f),rightEar=Landmark(.4f,.2f,.95f))
        assertEquals(TrackingState.PARTIAL,ears.state())
        val patch=OutfitPlacement.patches(frame(ears),hat,OutfitFit()).single()
        assertEquals(4,patch.source.size)
        assertArrayEquals(floatArrayOf(.5f+.4f*479,.5f+.2f*639,.5f+.6f*479,.5f+.2f*639),patch.destination,.001f)
        assertTrue(OutfitPlacement.patches(frame(ears.copy(leftEar=null)),hat).isEmpty())
        assertTrue(OutfitPlacement.patches(frame(Keypoints()),hat).isEmpty())
    }
    @Test fun defaultHatIsWiderThanTheEarSpanAndLiftedAboveItIncludingHeadRoll() {
        val ears=Keypoints(leftEar=Landmark(.6f,.23f,.95f),rightEar=Landmark(.4f,.17f,.95f))
        val baseline=OutfitPlacement.patches(frame(ears),hat,OutfitFit()).single().destination
        val fitted=OutfitPlacement.patches(frame(ears),hat).single().destination
        val dx=baseline[2]-baseline[0]; val dy=baseline[3]-baseline[1]
        val span=kotlin.math.hypot(dx,dy)
        assertEquals(span*1.35f,kotlin.math.hypot(fitted[2]-fitted[0],fitted[3]-fitted[1]),.001f)
        val cx=(baseline[0]+baseline[2])/2; val cy=(baseline[1]+baseline[3])/2
        val fx=(fitted[0]+fitted[2])/2; val fy=(fitted[1]+fitted[3])/2
        assertEquals(-.38f*span,(-(fx-cx)*dy+(fy-cy)*dx)/span,.001f)
        assertTrue(fy<cy)
        val unreliable=ears.copy(leftEar=ears.leftEar!!.copy(confidence=.5f))
        assertTrue(OutfitPlacement.patches(frame(unreliable),hat).isEmpty())
    }
    @Test fun accessoriesDoNotAcceptTorsoAnchorsOrDegenerateMetadata() {
        assertTrue(OutfitPlacement.patches(frame(),hat.copy(previewImage=hat.previewImage.copy(attachmentAnchors=emptyMap()))).isEmpty())
        val bad=pants.previewImage.attachmentAnchors.toMutableMap().apply { this["left_hip"]=getValue("right_hip") }
        assertTrue(OutfitPlacement.patches(frame(),pants.copy(previewImage=pants.previewImage.copy(attachmentAnchors=bad))).isEmpty())
        assertNull(TorsoWarp.points(frame(),hat))
    }
    @Test fun upperBodyAddsHorizontalEaseWithoutLengtheningOrLoweringTheShirt() {
        val shirt=item(GarmentCategory.UPPER_BODY,emptyMap()).let {
            it.copy(previewImage=it.previewImage.copy(anchors=GarmentAnchors(
                Anchor(.75f,.15f),Anchor(.25f,.15f),Anchor(.7f,.85f),Anchor(.3f,.85f))))
        }
        val raw=OutfitPlacement.patches(frame(),shirt,OutfitFit()).single().destination
        val fitted=OutfitPlacement.patches(frame(),shirt).single().destination
        val centre=(raw[0]+raw[2])/2
        for (i in raw.indices step 2) {
            assertEquals(centre+(raw[i]-centre)*1.25f,fitted[i],.001f)
            assertEquals(raw[i+1],fitted[i+1],.001f)
        }
        assertTrue(OutfitPlacement.patches(frame(Keypoints()),shirt).isEmpty())
    }
}
