package com.smartfitting.mirror.network

import com.smartfitting.mirror.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FittingSessionTest {
    private val garment = Garment("upper_blue", "演示上衣", GarmentCategory.UPPER_BODY, listOf("demo"), "http://localhost/blue.png",
        PreviewImage("http://localhost/blue.png", 600, 800, GarmentAnchors(Anchor(.75f,.15f), Anchor(.25f,.15f), Anchor(.7f,.85f), Anchor(.3f,.85f))),
        "http://localhost/blue.png")
    private fun photo() = CapturedPhoto("local", 1, 2, 2, "image/png", byteArrayOf(1,2,3))

    private inner class Fake : FittingTransport {
        var createFailures = 0
        var endFails = false
        var resultFails = false
        var cancelReturnsSuccess = false
        var nextStatus = JobStatus.SUCCEEDED
        var uploads = 0
        var polls = 0
        var items = listOf(garment)
        var catalogReadFails = false
        var saves = 0
        var ends = 0
        var onDownload: suspend () -> Unit = {}
        var lastDownload: ByteArray? = null
        val keys = mutableListOf<String>()
        val time = "2026-10-09T10:00:00Z"
        fun job(status: JobStatus) = TryOnJob("job_one", "session_one", "upload_one", "upper_blue", status, true, time, time,
            if (status == JobStatus.SUCCEEDED) JobResult("http://localhost/result.png", 2, 2, time) else null,
            if (status == JobStatus.FAILED) ErrorDetail("MOCK_FAILURE", "模拟失败", false, "test") else null)
        override suspend fun createSession() = Session("session_one", "private", time)
        override suspend fun garments(sessionToken: String): GarmentList {
            if (catalogReadFails) throw IOException("Catalog disconnected")
            return GarmentList(items)
        }
        override suspend fun saveGarment(sessionToken: String, item: GarmentWrite, replace: Boolean): Garment {
            saves++
            val next = garment.copy(garmentId=item.garmentId,name=item.name,tags=item.tags,details=item.details)
            items = items.filterNot { it.garmentId == next.garmentId }+next
            return next
        }
        override suspend fun deleteGarment(sessionToken: String, id: String) { items = items.filterNot { it.garmentId == id } }
        override suspend fun upload(sessionToken: String, photo: CapturedPhoto): Upload { photo.bytes; uploads++; return Upload("upload_one", 2,2,time) }
        override suspend fun createJob(sessionToken: String, request: CreateJob, idempotencyKey: String): TryOnJob {
            keys += idempotencyKey
            if (createFailures-- > 0) throw IOException("创建响应超时")
            return job(JobStatus.QUEUED)
        }
        override suspend fun job(sessionToken: String, jobId: String): TryOnJob { polls++; return job(nextStatus) }
        override suspend fun cancel(sessionToken: String, jobId: String) = job(if (cancelReturnsSuccess) JobStatus.SUCCEEDED else JobStatus.CANCELLED)
        override suspend fun endSession(sessionToken: String) { ends++; if (endFails) throw IOException("离线，未确认远端清理") }
        override suspend fun download(sessionToken: String, url: String): ByteArray {
            if (resultFails) throw IOException("结果下载暂时失败")
            onDownload()
            return byteArrayOf(4,5,6).also { lastDownload=it }
        }
        override fun close() { }
    }

    @Test fun outfitCanLoadOtherRegionsButEndedSessionsStillZeroReturnedBytes() = runTest {
        val second=garment.copy(garmentId="other",name="Other")
        val fake=Fake().apply { items=listOf(garment,second) }
        val controller=FittingSession(fake,this,nowMs={testScheduler.currentTime})
        controller.connect();runCurrent();controller.select(second.garmentId)
        assertArrayEquals(byteArrayOf(4,5,6),controller.outfitPreview(garment))
        fake.onDownload={ controller.end();runCurrent() }
        try { controller.outfitPreview(garment);fail("Ended session must reject late image bytes") }
        catch (_: CancellationException) {}
        assertTrue(fake.lastDownload!!.all { it==0.toByte() })
    }

    @Test fun catalogSaveSelectsNewItemDeleteFallsBackAndReadRetryDoesNotReplayWrites() = runTest {
        val fake = Fake(); val controller = FittingSession(fake,this,nowMs={testScheduler.currentTime})
        controller.connect(); runCurrent()
        val input = GarmentWrite("my_tee","My tee",assetId="upper_blue",details=GarmentDetails(material="cotton"))
        controller.saveGarment(input,false); runCurrent()
        assertEquals("my_tee",controller.state.value.selectedId)
        assertEquals("cotton",controller.state.value.selected!!.details.material)
        controller.deleteGarment("my_tee"); runCurrent()
        assertEquals("upper_blue",controller.state.value.selectedId)
        fake.catalogReadFails = true
        controller.saveGarment(input,false); runCurrent()
        assertEquals(RetryAction.CATALOG,controller.state.value.retry)
        assertEquals(2,fake.saves)
        fake.catalogReadFails = false
        controller.retry(); runCurrent()
        assertEquals(FittingPhase.READY,controller.state.value.phase)
        assertEquals(2,fake.saves)
        assertTrue(controller.state.value.garments.any { it.garmentId == "my_tee" })
        controller.end(); runCurrent()
        assertTrue(controller.state.value.garments.isEmpty())
    }

    @Test fun successfulFlowClearsPhotoAndImagesOnEnd() = runTest {
        val fake = Fake()
        val controller = FittingSession(fake, this, nowMs = { testScheduler.currentTime })
        controller.connect(); runCurrent()
        val input = photo()
        controller.generate(input); advanceUntilIdle()
        assertEquals(FittingPhase.SUCCEEDED, controller.state.value.phase)
        assertTrue(controller.state.value.job!!.isMock)
        try { input.bytes; fail("Photo must close after upload") } catch (_: IllegalStateException) { }
        val original = controller.state.value.original!!
        val result = controller.state.value.result!!
        controller.end(); advanceUntilIdle()
        assertEquals(FittingPhase.CLOSED, controller.state.value.phase)
        assertTrue(original.all { it == 0.toByte() } && result.all { it == 0.toByte() })
        assertEquals(1, fake.ends)
    }

    @Test fun unknownCreateOutcomeRetriesOriginalKeyAndNeverReuploads() = runTest {
        val fake = Fake().apply { createFailures = 3 }
        val controller = FittingSession(fake, this, nowMs = { testScheduler.currentTime })
        controller.connect(); runCurrent()
        controller.generate(photo()); advanceUntilIdle()
        assertEquals(RetryAction.CREATE, controller.state.value.retry)
        assertFalse(controller.state.value.canCapture)
        controller.retry(); advanceUntilIdle()
        assertEquals(FittingPhase.SUCCEEDED, controller.state.value.phase)
        assertEquals(1, fake.uploads)
        assertEquals(4, fake.keys.size)
        assertEquals(1, fake.keys.distinct().size)
        controller.end(); advanceUntilIdle()
    }

    @Test fun backgroundPausesPollingAndForegroundResumesOriginalJob() = runTest {
        val fake = Fake().apply { nextStatus = JobStatus.RUNNING }
        val controller = FittingSession(fake, this, nowMs = { testScheduler.currentTime })
        controller.connect(); runCurrent(); controller.generate(photo()); runCurrent()
        controller.setVisible(false); advanceTimeBy(20_000); runCurrent()
        assertEquals(0, fake.polls)
        fake.nextStatus = JobStatus.SUCCEEDED
        controller.setVisible(true); advanceUntilIdle()
        assertEquals(FittingPhase.SUCCEEDED, controller.state.value.phase)
        assertEquals(1, fake.keys.size)
        controller.end(); advanceUntilIdle()
    }

    @Test fun cancelHonorsServerSuccessWinner() = runTest {
        val fake = Fake().apply { cancelReturnsSuccess = true }
        val controller = FittingSession(fake, this, nowMs = { testScheduler.currentTime })
        controller.connect(); runCurrent(); controller.generate(photo()); runCurrent()
        controller.cancel(); advanceUntilIdle()
        assertEquals(FittingPhase.SUCCEEDED, controller.state.value.phase)
        assertNotNull(controller.state.value.result)
        controller.end(); advanceUntilIdle()
    }

    @Test fun endingBeforeQueuedUploadStartsStillClosesPhotoAndStopsWork() = runTest {
        val fake = Fake()
        val controller = FittingSession(fake, this, nowMs = { testScheduler.currentTime })
        controller.connect(); runCurrent()
        val input = photo()
        controller.generate(input)
        controller.end(); advanceUntilIdle()
        assertEquals(0, fake.uploads)
        try { input.bytes; fail("Cancelled photo must close") } catch (_: IllegalStateException) { }
        assertEquals(FittingPhase.CLOSED, controller.state.value.phase)
        assertNull(controller.state.value.original)
    }

    @Test fun failedRemoteCleanupIsNotReportedAsSuccessAndCanRetry() = runTest {
        val fake = Fake().apply { endFails = true }
        val controller = FittingSession(fake, this, nowMs = { testScheduler.currentTime })
        controller.connect(); runCurrent(); controller.end(); advanceUntilIdle()
        assertEquals(FittingPhase.ERROR, controller.state.value.phase)
        assertEquals(RetryAction.END, controller.state.value.retry)
        assertFalse(controller.state.value.canCapture)
        fake.endFails = false
        controller.retry(); advanceUntilIdle()
        assertEquals(FittingPhase.CLOSED, controller.state.value.phase)
    }

    @Test fun failedDownloadRetriesSameResultWithoutCreatingAnotherJob() = runTest {
        val fake = Fake().apply { resultFails = true }
        val controller = FittingSession(fake, this, nowMs = { testScheduler.currentTime })
        controller.connect(); runCurrent(); controller.generate(photo()); advanceUntilIdle()
        assertEquals(RetryAction.RESULT, controller.state.value.retry)
        fake.resultFails = false
        controller.retry(); advanceUntilIdle()
        assertEquals(1, fake.keys.size)
        assertEquals(FittingPhase.SUCCEEDED, controller.state.value.phase)
        controller.end(); advanceUntilIdle()
    }
}
