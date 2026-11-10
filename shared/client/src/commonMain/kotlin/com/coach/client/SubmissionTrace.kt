package com.coach.client

import kotlin.time.TimeSource

/**
 * Opt-in, in-memory timeline for one mainline answer submission (TON-1394 latency diagnosis).
 * Records only the random submissionId, a fixed event name and a monotonic timestamp: no question text,
 * answers, URLs or credentials. Off by default; when off, [mark] returns immediately and nothing is stored,
 * written to disk or sent over the network. Never holds more than [LIMIT] events.
 *
 * Eviction when full: instrumentation query events (`test.*`, high-frequency polling) are dropped first,
 * oldest first, so they cannot push out submission/retry events. Only if the buffer holds nothing but
 * submission events is the oldest one dropped. Every drop is counted by kind and the affected submission
 * ids are remembered, so a truncated chain is reported as incomplete instead of being passed off as whole.
 * All timestamps share one process-wide monotonic domain.
 */
object SubmissionTrace {
    const val LIMIT = 512
    private const val MAX_TRACKED_IDS = 64
    class Event(val id: String, val event: String, val nanos: Long)
    /** [droppedQuery]/[droppedSubmission] count evictions; [incompleteIds] are submission ids that lost events. */
    class Stats(val droppedQuery: Long, val droppedSubmission: Long, val incompleteIds: Set<String>, val idsOverflow: Boolean) {
        val complete: Boolean get() = droppedSubmission == 0L
    }

    @kotlin.concurrent.Volatile var enabled = false
    private val lock = Any()
    private val origin = TimeSource.Monotonic.markNow()
    private val events = ArrayDeque<Event>()
    private var droppedQuery = 0L
    private var droppedSubmission = 0L
    private var idsOverflow = false
    private val incomplete = LinkedHashSet<String>()

    private fun isQuery(e: Event) = e.event.startsWith("test.")

    /** Monotonic nanoseconds since a process-local origin. */
    fun now(): Long = origin.elapsedNow().inWholeNanoseconds

    fun mark(id: String?, event: String) {
        if (!enabled || id == null) return
        val e = Event(id, event, now())
        synchronized(lock) {
            if (events.size >= LIMIT) {
                val q = events.indexOfFirst { isQuery(it) }
                if (q >= 0) { events.removeAt(q); droppedQuery++ }
                else if (isQuery(e)) { droppedQuery++; return@synchronized }
                else {
                    val old = events.removeFirst(); droppedSubmission++
                    if (incomplete.size < MAX_TRACKED_IDS || old.id in incomplete) incomplete.add(old.id) else idsOverflow = true
                }
            }
            events.addLast(e)
        }
    }

    fun snapshot(): List<Event> = synchronized(lock) { events.toList() }
    fun stats(): Stats = synchronized(lock) { Stats(droppedQuery, droppedSubmission, incomplete.toSet(), idsOverflow) }
    fun clear() { synchronized(lock) { events.clear(); droppedQuery = 0; droppedSubmission = 0; incomplete.clear(); idsOverflow = false } }
}
