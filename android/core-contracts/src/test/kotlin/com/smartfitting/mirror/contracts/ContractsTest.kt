package com.smartfitting.mirror.contracts

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ContractsTest {
    @Test fun outfitRequestCarriesAllSelectedMaterialsWithoutLegacyGarmentId() {
        val request = roundTrip<CreateJob>("create-outfit-job.json")
        assertNull(request.garmentId)
        assertEquals(listOf("upper_demo_01", "lower_demo_01"), request.outfit!!.garmentIds)
        assertEquals(listOf("silver_studs"), request.outfit.accessoryIds)
    }

    @Test fun extendedCategoriesAndHemCalibrationRoundTripWithoutChangingVision() {
        val items=roundTrip<GarmentList>("extended-garments.json").items
        assertEquals(setOf(GarmentCategory.DRESS,GarmentCategory.SKIRT,GarmentCategory.SHORTS),items.map { it.category }.toSet())
        assertEquals(1.7f,items.first().previewImage.hemRatio!!,0f)
        for (value in listOf(Float.NaN,Float.POSITIVE_INFINITY,0f,2.01f)) {
            assertThrows(IllegalArgumentException::class.java) { items.first().previewImage.copy(hemRatio=value) }
        }
    }
    @Test fun outfitCategoriesAndAnatomicalAttachmentAnchorsRoundTrip() {
        val items=roundTrip<GarmentList>("outfit-garments.json").items
        assertEquals(setOf(GarmentCategory.UPPER_BODY,GarmentCategory.LOWER_BODY,GarmentCategory.HEADWEAR),items.map { it.category }.toSet())
        val pants=items.single { it.category==GarmentCategory.LOWER_BODY }
        assertNull(pants.previewImage.anchors)
        assertTrue(pants.previewImage.attachmentAnchors.keys.containsAll(listOf("left_ankle","right_ankle","left_knee","right_knee")))
    }
    @Test fun optionalGarmentDetailsAndWriteRequestRoundTripAlongsideLegacyCatalog() {
        val garment = roundTrip<Garment>("garment-details.json")
        assertEquals(1448,garment.previewImage.width)
        assertTrue("photo" in garment.tags)
        val write = roundTrip<GarmentWrite>("garment-write.json")
        assertEquals(garment.garmentId,write.garmentId)
        assertEquals(garment.details,write.details)
        assertEquals(GarmentDetails(),ContractJson.decodeFromString<GarmentList>(fixture("garments.json")).items.first().details)
    }
    private fun fixture(name: String) = checkNotNull(javaClass.classLoader!!.getResource(name)).readText()
    private inline fun <reified T> roundTrip(name: String): T {
        val original = fixture(name)
        val value = ContractJson.decodeFromString<T>(original)
        assertEquals(name, Json.parseToJsonElement(original), Json.parseToJsonElement(ContractJson.encodeToString(value)))
        return value
    }
    @Test fun allThirteenSharedFixturesMapWithoutFieldLoss() {
        val frame = roundTrip<VisionFrame>("vision-tracking.json")
        assertEquals(TrackingState.TRACKING, frame.trackingState)
        assertTrue(frame.keypoints.leftShoulder!!.x > frame.keypoints.rightShoulder!!.x)
        assertNull(frame.depth)
        assertEquals(TrackingState.LOST, roundTrip<VisionFrame>("vision-lost.json").trackingState)
        roundTrip<VisionCapabilities>("capabilities-rgb.json")
        roundTrip<Session>("session.json")
        roundTrip<GarmentList>("garments.json")
        roundTrip<Upload>("upload.json")
        roundTrip<CreateJob>("create-job.json")
        roundTrip<ErrorResponse>("error.json")
        for (status in listOf("queued", "running", "succeeded", "failed", "cancelled")) {
            assertTrue(roundTrip<TryOnJob>("job-$status.json").isMock)
        }
    }
    @Test fun trackingRequiresFourTorsoAnchors() {
        val frame = ContractJson.decodeFromString<VisionFrame>(fixture("vision-tracking.json"))
        assertThrows(IllegalArgumentException::class.java) { frame.copy(keypoints = frame.keypoints.copy(leftHip = null)) }
    }
    @Test fun declaredTrackingStateMustMatchReliableKeypoints() {
        val tracked = ContractJson.decodeFromString<VisionFrame>(fixture("vision-tracking.json"))
        val partialPoints = Keypoints(leftShoulder = Landmark(.4f, .3f, .8f))
        assertEquals(TrackingState.PARTIAL, tracked.copy(trackingState = TrackingState.PARTIAL, keypoints = partialPoints).trackingState)
        for (points in listOf(Keypoints(), Keypoints(leftShoulder = Landmark(.4f, .3f, .1f)), tracked.keypoints)) {
            assertThrows(IllegalArgumentException::class.java) {
                tracked.copy(trackingState = TrackingState.PARTIAL, keypoints = points)
            }
        }
    }
    @Test fun bufferIdentifiersCannotBeBlank() {
        for (id in listOf("", "  ")) {
            assertThrows(IllegalArgumentException::class.java) { ImageMetadata(id, 10, 10) }
            assertThrows(IllegalArgumentException::class.java) { PlaneMetadata(id, 10, 10, 10, "foreground_uint8") }
            assertThrows(IllegalArgumentException::class.java) { DepthMetadata(id, 10, 10, 20) }
        }
    }
    @Test fun capabilitiesRejectUnsupportedSchemaVersionOnDecode() {
        assertThrows(IllegalArgumentException::class.java) {
            ContractJson.decodeFromString<VisionCapabilities>(fixture("capabilities-rgb.json").replace("0.1.0", "0.2.0"))
        }
    }
    @Test fun invalidCoordinatesAndMirroredMetadataAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Landmark(Float.NaN, .5f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { Landmark(-.1f, .5f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { ImageMetadata("x", 10, 10, mirrored = true) }
    }
    @Test fun alignedDepthMustHaveRgbDimensions() {
        val frame = ContractJson.decodeFromString<VisionFrame>(fixture("vision-tracking.json"))
        assertThrows(IllegalArgumentException::class.java) { frame.copy(depth = DepthMetadata("d", 2, 2, 4)) }
    }
    @Test fun onlySuccessCarriesResult() {
        val job = ContractJson.decodeFromString<TryOnJob>(fixture("job-succeeded.json"))
        assertThrows(IllegalArgumentException::class.java) { job.copy(status = JobStatus.QUEUED) }
        assertThrows(IllegalArgumentException::class.java) { job.copy(result = null) }
    }
    @Test fun fitCenterPreservesAspectAndMirrorsPixelCenters() {
        val a = FitCenter(480, 640, 1080, 1920, false)
        val b = FitCenter(480, 640, 1080, 1920, true)
        assertEquals(2.25f, a.scale, .0001f)
        assertEquals(240f, a.top, .0001f)
        for (x in listOf(0f, .25f, 1f)) {
            assertEquals(a.map(x, .5f).first, b.map(1 - x, .5f).first, .0001f)
        }
        assertEquals(a.map(.2f, .5f).second, b.map(.2f, .5f).second, 0f)
    }
    @Test fun releasedPhotoIsZeroedAndUnavailable() {
        val bytes = byteArrayOf(1, 2, 3)
        val photo = CapturedPhoto("session", 1, 1, 1, "image/png", bytes)
        photo.close(); photo.close()
        assertArrayEquals(byteArrayOf(0, 0, 0), bytes)
        assertThrows(IllegalStateException::class.java) { photo.bytes }
    }
}
