package com.smartfitting.mirror

import com.smartfitting.mirror.vision.BodyPresence
import org.junit.Assert.*
import org.junit.Test

class GuestExperienceClockTest {
    private val policy = GuestTimeouts(idleMs = 1000, absentMs = 300, sessionMs = 2000, frameFreshMs = 100)
    private fun clock() = GuestExperienceClock(policy).apply { start(0) }
    @Test fun inactiveClockDoesNotEndAnExperience() {
        val clock = GuestExperienceClock(policy)
        assertNull(clock.expired(100_000)); clock.observe(BodyPresence.ABSENT, 1); assertNull(clock.expired(100_000))
    }
    @Test fun framesAndSuccessfulTrackingDoNotRefreshInactivity() {
        val clock = clock()
        for (time in 0L..1000L step 50) clock.observe(BodyPresence.PRESENT, time)
        assertNull(clock.expired(999)); assertEquals(GuestEndReason.INACTIVITY, clock.expired(1000))
    }
    @Test fun onlyInteractionRefreshesIdleButDoesNotExtendAbsoluteSessionLimit() {
        val clock = clock()
        clock.interact(900); assertNull(clock.expired(1800))
        clock.interact(1800); assertEquals(GuestEndReason.SESSION_LIMIT, clock.expired(2000))
    }
    @Test fun continuousFreshAbsenceHasGraceBeforeExit() {
        val clock = clock()
        for (time in 0L..250L step 50) { clock.observe(BodyPresence.ABSENT, time); assertNull(clock.expired(time)) }
        clock.observe(BodyPresence.ABSENT, 300)
        assertEquals(GuestEndReason.NO_PERSON, clock.expired(300))
    }
    @Test fun uncertainOrPresentEvidenceBreaksAbsenceStreak() {
        for (presence in listOf(BodyPresence.PRESENT, BodyPresence.UNCERTAIN)) {
            val clock = clock()
            clock.observe(BodyPresence.ABSENT, 0); clock.observe(BodyPresence.ABSENT, 100)
            clock.observe(presence, 150); clock.observe(BodyPresence.ABSENT, 200); clock.observe(BodyPresence.ABSENT, 300)
            assertNull(clock.expired(350))
        }
    }
    @Test fun stoppedFailedOrPausedStreamDoesNotCountAsSomeoneLeaving() {
        val clock = clock()
        clock.observe(BodyPresence.ABSENT, 0)
        assertNull(clock.expired(301))
        clock.observe(BodyPresence.ABSENT, 302); assertNull(clock.expired(350))
        clock.sourceStopped(); assertNull(clock.expired(600))
        assertEquals(GuestEndReason.INACTIVITY, clock.expired(1000))
    }
    @Test fun longGapBetweenFramesRestartsAbsenceGrace() {
        val clock = clock()
        clock.observe(BodyPresence.ABSENT, 0); clock.observe(BodyPresence.ABSENT, 400)
        assertNull(clock.expired(400)); assertNull(clock.expired(450))
    }
    @Test fun endingAndStartingANewGuestDiscardPreviousDeadlines() {
        val clock = clock()
        clock.observe(BodyPresence.ABSENT, 0); clock.end(); assertFalse(clock.active)
        assertNull(clock.expired(2000)); clock.start(3000); assertNull(clock.expired(3300))
        assertEquals(GuestEndReason.INACTIVITY, clock.expired(4000))
    }
    @Test fun backwardInteractionAndFrameTimestampsCannotExtendOrFabricateAbsence() {
        val clock = clock(); clock.interact(900); clock.interact(500)
        assertNull(clock.expired(1800)); assertEquals(GuestEndReason.INACTIVITY, clock.expired(1900))
        clock.start(0); clock.observe(BodyPresence.ABSENT, 100); clock.observe(BodyPresence.ABSENT, 10)
        assertNull(clock.expired(100))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidTimeoutPolicyIsRejected() { GuestTimeouts(idleMs = 0) }
}
