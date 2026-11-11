package com.smartfitting.mirror

import com.smartfitting.mirror.vision.BodyPresence

internal enum class GuestEndReason { MANUAL, INACTIVITY, NO_PERSON, SESSION_LIMIT, SESSION_EXPIRED }
internal data class GuestTimeouts(val idleMs: Long = 5 * 60_000L, val absentMs: Long = 30_000L,
    val sessionMs: Long = 30 * 60_000L, val frameFreshMs: Long = 2_000L) {
    init { require(idleMs > 0 && absentMs > 0 && sessionMs >= idleMs && frameFreshMs > 0) }
}

/** Monotonic deadlines. Waiting for generation pauses the unattended timers. */
internal class GuestExperienceClock(val policy: GuestTimeouts = GuestTimeouts()) {
    var active = false
        private set
    private var started = 0L
    private var lastInteraction = 0L
    private var lastFrame: Long? = null
    private var absentSince: Long? = null
    private var generating = false

    fun start(nowMs: Long) {
        require(nowMs >= 0)
        active = true; started = nowMs; lastInteraction = nowMs; generating = false; sourceStopped()
    }
    fun interact(nowMs: Long) { if (active) lastInteraction = maxOf(lastInteraction, nowMs) }
    fun setGenerating(value: Boolean, nowMs: Long) {
        if (!active || value == generating) return
        generating = value
        sourceStopped()
        if (!generating) interact(nowMs)
    }
    fun observe(presence: BodyPresence, nowMs: Long) {
        if (!active || generating) return
        val previous = lastFrame
        if (previous != null && (nowMs < previous || nowMs - previous > policy.frameFreshMs)) absentSince = null
        lastFrame = nowMs
        if (presence == BodyPresence.ABSENT) { if (absentSince == null) absentSince = nowMs }
        else absentSince = null
    }
    fun sourceStopped() { lastFrame = null; absentSince = null }
    fun expired(nowMs: Long): GuestEndReason? {
        if (!active) return null
        if (nowMs - started >= policy.sessionMs) return GuestEndReason.SESSION_LIMIT
        if (generating) return null
        if (nowMs - lastInteraction >= policy.idleMs) return GuestEndReason.INACTIVITY
        val frame = lastFrame
        if (frame == null || nowMs < frame || nowMs - frame > policy.frameFreshMs) { sourceStopped(); return null }
        return if (absentSince?.let { nowMs - it >= policy.absentMs } == true) GuestEndReason.NO_PERSON else null
    }
    fun end() { active = false; sourceStopped() }
}
