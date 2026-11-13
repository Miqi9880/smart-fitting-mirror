package com.smartfitting.mirror.garment3d

import org.junit.Assert.*
import org.junit.Test

class LiveFrameGateTest {
    @Test fun retainsAtMostOneFrameAndRejectsDuplicatesOrTooFastArrivals() {
        val gate=LiveFrameGate();gate.start()
        val first=requireNotNull(gate.acquire(1,0));assertNull(gate.acquire(2,1000))
        gate.complete(first);assertNull(gate.acquire(1,1000));assertNull(gate.acquire(2,50))
        assertNotNull(gate.acquire(3,100))
    }
    @Test fun stopAndRestartCannotBeReopenedByAnOldEncodingOrJavascriptCallback() {
        val gate=LiveFrameGate();gate.start();val old=requireNotNull(gate.acquire(1,0))
        gate.stop();assertFalse(gate.accepts(old));gate.start();val next=requireNotNull(gate.acquire(1,200))
        gate.complete(old);assertTrue(gate.accepts(next));assertNull(gate.acquire(2,400))
    }
    @Test fun replayHasDistinctHandsForwardCuesAndBoundedCoordinates() {
        for(i in 0..300){
            val points=ReplayPose.points(i/10.0)
            assertEquals(8,points.size)
            points.values.forEach { assertTrue(it.x in 0f..1f);assertTrue(it.y in 0f..1f);assertTrue(it.z.isFinite()) }
        }
        assertNotEquals(ReplayPose.points(0.0),ReplayPose.points(1.0))
    }
}
