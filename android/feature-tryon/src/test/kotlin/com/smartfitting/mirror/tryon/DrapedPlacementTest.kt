package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.vision.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class DrapedPlacementTest {
    @Test fun allFiftyOneActualCalibrationsProduceFinitePatchesAcrossSyntheticMotion() {
        val manifest=Json.parseToJsonElement(checkNotNull(javaClass.classLoader!!.getResource("manifest.json")).readText()).jsonObject
        val items=manifest.getValue("items").jsonArray
        assertEquals(51,items.size)
        for (element in items) {
            val obj=element.jsonObject
            val image=PreviewImage("http://localhost/asset.png",obj.getValue("width").jsonPrimitive.int,
                obj.getValue("height").jsonPrimitive.int,
                obj["anchors"]?.takeIf { it!=JsonNull }?.let { ContractJson.decodeFromJsonElement<GarmentAnchors>(it) },
                ContractJson.decodeFromJsonElement<Map<String,Anchor>>(obj.getValue("attachment_anchors")),
                obj["hem_ratio"]?.jsonPrimitive?.float)
            val category=ContractJson.decodeFromJsonElement<GarmentCategory>(obj.getValue("category"))
            val id=obj.getValue("garment_id").jsonPrimitive.content
            val garment=Garment(id,id,category,emptyList(),image.url,image,null)
            for (scenario in listOf(SyntheticScenario.STILL,SyntheticScenario.TRANSLATE,SyntheticScenario.SCALE)) {
                for (step in listOf(0L,20L,40L)) {
                    val patches=OutfitPlacement.patches(frame(SyntheticScene.sample(scenario,step)),garment)
                    assertTrue("$id cannot place $scenario/$step",patches.isNotEmpty())
                    assertTrue(patches.all { p -> p.source.all { it.isFinite() } && p.destination.all { it.isFinite() } })
                }
            }
        }
    }
    private val pose = SyntheticScene.sample(SyntheticScenario.STILL,0)
    private fun frame(p: Keypoints = pose)=VisionFrame(sessionId="test",frameId=0,timestampMs=0,
        image=ImageMetadata("rgb",480,640),trackingState=p.state(),keypoints=p)
    private fun item(category: GarmentCategory, ratio: Float=1.7f): Garment {
        val dress=category==GarmentCategory.DRESS
        val y=if (dress) .45f else .12f
        val controls=mapOf("right_hip" to Anchor(.32f,y),"left_hip" to Anchor(.68f,y),
            "right_hem" to Anchor(.3f,.94f),"left_hem" to Anchor(.7f,.94f))
        return Garment(category.name,category.name,category,emptyList(),"http://localhost/item.png",
            PreviewImage("http://localhost/item.png",1000,1400,if (dress) GarmentAnchors(Anchor(.7f,.12f),
                Anchor(.3f,.12f),Anchor(.68f,y),Anchor(.32f,y)) else null,controls,ratio),null)
    }
    @Test fun dressesAndSkirtsStayContinuousAndFollowTheCurrentFrame() {
        for ((category,count) in listOf(GarmentCategory.DRESS to 4,GarmentCategory.SKIRT to 3,GarmentCategory.SHORTS to 12)) {
            val garment=item(category,if (category==GarmentCategory.SHORTS) .8f else 1.7f)
            val before=OutfitPlacement.patches(frame(),garment)
            assertEquals(count,before.size)
            if (category!=GarmentCategory.SHORTS) assertTrue(before.all { it.source.size==8 && it.clipToDestination })
            val after=OutfitPlacement.patches(frame(SyntheticScene.sample(SyntheticScenario.TRANSLATE,20)),garment)
            assertEquals(count,after.size)
            for ((a,b) in before.zip(after)) for (i in a.destination.indices) {
                assertEquals(if (i%2==0) 47.9f else 0f,b.destination[i]-a.destination[i],.002f)
            }
        }
    }
    @Test fun shortHemsNeedNoAnklesButLongHemsAndDressesHideMissingRequiredPoints() {
        val noAnkles=pose.copy(leftAnkle=null,rightAnkle=null)
        assertTrue(OutfitPlacement.patches(frame(noAnkles),item(GarmentCategory.DRESS)).isEmpty())
        assertTrue(OutfitPlacement.patches(frame(noAnkles),item(GarmentCategory.SKIRT)).isEmpty())
        assertFalse(OutfitPlacement.patches(frame(noAnkles),item(GarmentCategory.SKIRT,.6f)).isEmpty())
        assertFalse(OutfitPlacement.patches(frame(noAnkles),item(GarmentCategory.SHORTS,.8f)).isEmpty())
        val noTorso=pose.copy(leftShoulder=null,rightShoulder=null)
        assertFalse(OutfitPlacement.patches(frame(noTorso),item(GarmentCategory.SKIRT)).isEmpty())
        assertTrue(OutfitPlacement.patches(frame(noTorso),item(GarmentCategory.DRESS)).isEmpty())
        assertTrue(OutfitPlacement.patches(frame(pose.copy(leftKnee=pose.leftKnee!!.copy(confidence=.1f))),item(GarmentCategory.DRESS)).isEmpty())
        assertTrue(OutfitPlacement.patches(frame(Keypoints()),item(GarmentCategory.SKIRT)).isEmpty())
        assertFalse(OutfitPlacement.patches(frame(),item(GarmentCategory.DRESS)).isEmpty())
    }
    @Test fun skirtWidthDoesNotSplitOrStretchWithLegSeparation() {
        val garment=item(GarmentCategory.SKIRT)
        val wide=pose.copy(rightKnee=pose.rightKnee!!.copy(x=.1f),leftKnee=pose.leftKnee!!.copy(x=.9f),
            rightAnkle=pose.rightAnkle!!.copy(x=.1f),leftAnkle=pose.leftAnkle!!.copy(x=.9f))
        val before=OutfitPlacement.patches(frame(),garment)
        val after=OutfitPlacement.patches(frame(wide),garment)
        assertEquals(before.size,after.size)
        // Symmetric leg movement does not alter any continuous skirt panel.
        for ((a,b) in before.zip(after)) assertArrayEquals(a.destination,b.destination,.002f)
    }
    @Test fun invalidHemsAndDegeneratePosesNeverRender() {
        val garment=item(GarmentCategory.DRESS)
        val bad=garment.previewImage.attachmentAnchors.toMutableMap().apply { this["left_hem"]=getValue("right_hem") }
        assertTrue(OutfitPlacement.patches(frame(),garment.copy(previewImage=garment.previewImage.copy(attachmentAnchors=bad))).isEmpty())
        assertTrue(OutfitPlacement.patches(frame(),garment.copy(previewImage=garment.previewImage.copy(hemRatio=null))).isEmpty())
        assertTrue(OutfitPlacement.patches(frame(),item(GarmentCategory.SHORTS,1.5f)).isEmpty())
        assertTrue(OutfitPlacement.patches(frame(pose.copy(leftHip=pose.rightHip)),garment).isEmpty())
    }
    @Test fun dressReplacesUpperAndAllBottomsButKeepsHeadwear() {
        val shirt=item(GarmentCategory.UPPER_BODY); val pants=item(GarmentCategory.LOWER_BODY)
        val hat=item(GarmentCategory.HEADWEAR);val dress=item(GarmentCategory.DRESS)
        val before=listOf(shirt,pants,hat).associateBy { it.category }
        val dressed=OutfitSelection.add(before,dress)
        assertEquals(setOf(GarmentCategory.DRESS,GarmentCategory.HEADWEAR),dressed.keys)
        assertEquals(setOf(GarmentCategory.UPPER_BODY,GarmentCategory.HEADWEAR),OutfitSelection.add(dressed,shirt).keys)
        var outfit=before
        for (category in listOf(GarmentCategory.SKIRT,GarmentCategory.SHORTS,GarmentCategory.LOWER_BODY)) {
            outfit=OutfitSelection.add(outfit,item(category))
            assertEquals(setOf(GarmentCategory.UPPER_BODY,GarmentCategory.HEADWEAR,category),outfit.keys)
            assertTrue(OutfitSelection.compatible(outfit.values.toList()))
        }
        assertFalse(OutfitSelection.compatible(listOf(dress,shirt)))
        assertFalse(OutfitSelection.compatible(listOf(item(GarmentCategory.SKIRT),pants)))
    }
}
