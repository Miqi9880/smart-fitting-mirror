package com.smartfitting.mirror.contracts

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LifecycleTest {
    private fun metadata(id: Long = 1) = VisionFrame(sessionId = "test", frameId = id, timestampMs = 0,
        image = ImageMetadata("image_$id", 4, 4), trackingState = TrackingState.LOST, keypoints = Keypoints())
    @Test fun asyncConsumerRetainsUntilDoneAndCannotResurrect() {
        val disposed = AtomicInteger()
        val lease = FrameLease(metadata(), "pixels") { disposed.incrementAndGet() }
        lease.retain()
        lease.release()
        assertEquals("pixels", lease.pixels)
        assertEquals(0, disposed.get())
        lease.release()
        assertEquals(1, disposed.get())
        assertThrows(IllegalStateException::class.java) { lease.retain() }
        assertThrows(IllegalStateException::class.java) { lease.release() }
        assertThrows(IllegalStateException::class.java) { lease.pixels }
    }
    @Test fun latestSlotReleasesReplacedFrameButHonorsConsumer() {
        val disposed = mutableListOf<String>()
        val latest = LatestFrame<String>()
        val first = FrameLease(metadata(), "first") { disposed += it }
        latest.offer(first); first.release()
        val consumer = latest.acquire()!!
        val second = FrameLease(metadata(2), "second") { disposed += it }
        latest.offer(second); second.release()
        assertTrue(disposed.isEmpty())
        consumer.release()
        assertEquals(listOf("first"), disposed)
        latest.clear(); latest.clear()
        assertEquals(listOf("first", "second"), disposed)
        assertNull(latest.acquire())
    }
    @Test fun concurrentConsumersDisposeExactlyOnce() {
        val disposed = AtomicInteger()
        val lease = FrameLease(metadata(), 42) { disposed.incrementAndGet() }
        val pool = Executors.newFixedThreadPool(4)
        val jobs = (1..32).map { lease.retain(); pool.submit { assertEquals(42, lease.pixels); lease.release() } }
        lease.release()
        jobs.forEach { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, disposed.get())
    }
    @Test fun restartAndStopRejectAllOldResults() {
        val gate = SessionGeneration()
        val old = gate.begin()
        assertTrue(gate.accepts(old))
        val current = gate.begin()
        assertFalse(gate.accepts(old))
        assertTrue(gate.accepts(current))
        gate.end(); gate.end()
        assertFalse(gate.accepts(current))
        assertTrue(gate.accepts(gate.begin()))
    }
}
