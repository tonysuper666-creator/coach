package com.coach.client

import kotlin.time.TimeSource

/**
 * Opt-in, in-memory timeline for one mainline answer submission (TON-1394 latency diagnosis).
 * Records only the random submissionId, a fixed event name and a monotonic timestamp: no question text,
 * answers, URLs or credentials. Off by default; when off, [mark] returns immediately and nothing is stored,
 * written to disk or sent over the network. Bounded to [LIMIT] events (oldest dropped).
 * All timestamps share one process-wide monotonic domain, so UI, client and instrumentation events line up.
 */
object SubmissionTrace {
    const val LIMIT = 512
    class Event(val id: String, val event: String, val nanos: Long)

    @kotlin.concurrent.Volatile var enabled = false
    private val lock = Any()
    private val origin = TimeSource.Monotonic.markNow()
    private val events = ArrayDeque<Event>()

    /** Monotonic nanoseconds since a process-local origin. */
    fun now(): Long = origin.elapsedNow().inWholeNanoseconds

    fun mark(id: String?, event: String) {
        if (!enabled || id == null) return
        val e = Event(id, event, now())
        synchronized(lock) { if (events.size >= LIMIT) events.removeFirst(); events.addLast(e) }
    }

    fun snapshot(): List<Event> = synchronized(lock) { events.toList() }
    fun clear() { synchronized(lock) { events.clear() } }
}
