package com.smartfitting.mirror.garment3d

/** Main-thread backpressure: at most one retained/encoded frame, no queue. */
internal class LiveFrameGate {
    data class Ticket(val generation: Long,val sequence: Long)
    private var generation = 0L
    private var enabled = false
    private var busy: Ticket? = null
    private var sequence = -1L
    private var arrival = Long.MIN_VALUE
    fun start() { stop(); enabled = true }
    fun stop() { generation++; enabled = false; busy = null; sequence = -1; arrival = Long.MIN_VALUE }
    fun acquire(frame: Long,now: Long): Ticket? {
        if (!enabled || busy != null || frame <= sequence || (arrival != Long.MIN_VALUE && now-arrival < 100)) return null
        return Ticket(generation,frame).also { busy = it; sequence = frame; arrival = now }
    }
    fun accepts(ticket: Ticket) = enabled && busy == ticket && generation == ticket.generation
    fun complete(ticket: Ticket) { if (accepts(ticket)) busy = null }
}
