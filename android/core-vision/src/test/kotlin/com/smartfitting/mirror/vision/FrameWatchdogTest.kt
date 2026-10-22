package com.smartfitting.mirror.vision

import org.junit.Assert.*
import org.junit.Test

class FrameWatchdogTest {
    private class Clock {
        var now = 0L
        val pending = mutableMapOf<Runnable, Long>()
        val watchdog = FrameWatchdog({ now }, { task, delay -> pending[task] = now + delay }, { pending.remove(it) })
        fun advanceTo(target: Long) {
            require(target >= now)
            while (true) {
                val next = pending.minByOrNull { it.value }?.takeIf { it.value <= target } ?: break
                now = next.value; pending.remove(next.key); next.key.run()
            }
            now = target
        }
    }

    @Test fun startupCannotWaitForeverAndEmitsOnlyOneError() {
        val clock = Clock(); val errors = mutableListOf<Boolean>()
        clock.watchdog.start { errors += it }
        clock.advanceTo(14_999); assertTrue(errors.isEmpty())
        clock.advanceTo(15_000); assertEquals(listOf(true), errors)
        clock.advanceTo(90_000); assertEquals(listOf(true), errors)
        assertTrue(clock.pending.isEmpty())
    }

    @Test fun firstOutputImmediatelySwitchesToShortStreamDeadline() {
        val clock = Clock(); val errors = mutableListOf<Boolean>()
        clock.watchdog.start { errors += it }
        clock.advanceTo(100); clock.watchdog.frameReceived()
        clock.advanceTo(2_099); assertTrue(errors.isEmpty())
        clock.advanceTo(2_100); assertEquals(listOf(false), errors)
    }

    @Test fun everyOutputKeepsStreamAliveRegardlessOfWhetherAPersonWasFound() {
        val clock = Clock(); val errors = mutableListOf<Boolean>()
        clock.watchdog.start { errors += it }
        // The guard deliberately has no tracking-state input: LOST/PARTIAL frames are live too.
        for (time in 0L..20_000L step 1_000) {
            clock.advanceTo(time); clock.watchdog.frameReceived()
        }
        assertTrue(errors.isEmpty())
        clock.advanceTo(21_999); assertTrue(errors.isEmpty())
        clock.advanceTo(22_000); assertEquals(listOf(false), errors)
    }

    @Test fun stoppingCancelsDeadlineAndLateOutputCannotRearmIt() {
        val clock = Clock(); var errors = 0
        clock.watchdog.start { errors++ }; clock.watchdog.frameReceived()
        val oldCheck = clock.pending.keys.single()
        clock.watchdog.stop(); clock.watchdog.stop(); clock.watchdog.frameReceived()
        oldCheck.run(); clock.advanceTo(30_000)
        assertEquals(0, errors); assertTrue(clock.pending.isEmpty())
    }

    @Test fun restartingRejectsAlreadyQueuedChecksFromTheOldRun() {
        val clock = Clock(); val errors = mutableListOf<String>()
        clock.watchdog.start { errors += "old" }; clock.watchdog.frameReceived()
        val oldCheck = clock.pending.keys.single()
        clock.advanceTo(500)
        clock.watchdog.start { errors += "new:$it" }
        oldCheck.run(); clock.advanceTo(15_499); assertTrue(errors.isEmpty())
        clock.advanceTo(15_500); assertEquals(listOf("new:true"), errors)
    }

    @Test fun timeoutCanBeManuallyRestartedWithoutOldTimerStoppingTheNewRun() {
        val clock = Clock(); val errors = mutableListOf<String>()
        clock.watchdog.start {
            errors += "old:$it"
            clock.watchdog.start { next -> errors += "new:$next" }
            clock.watchdog.frameReceived()
        }
        clock.advanceTo(15_000); assertEquals(listOf("old:true"), errors)
        clock.advanceTo(17_000); assertEquals(listOf("old:true", "new:false"), errors)
        clock.watchdog.frameReceived(); assertTrue(clock.pending.isEmpty())
    }
}
