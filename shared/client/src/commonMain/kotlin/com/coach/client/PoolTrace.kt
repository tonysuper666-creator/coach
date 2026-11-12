package com.coach.client

import kotlin.time.TimeSource

/** Opt-in acceptance tracing. No question text, credentials or learner answers. */
object PoolTrace {
    @kotlin.concurrent.Volatile var enabled=false
    private val lock=Any()
    private val starts=HashMap<String,TimeSource.Monotonic.ValueTimeMark>()
    private val stages=HashMap<String,MutableMap<String,Double>>()
    fun begin(thread: String) { if(enabled) synchronized(lock) {
        starts[thread]=TimeSource.Monotonic.markNow();stages[thread]=linkedMapOf()
    } }
    fun mark(thread: String,stage: String) { if(enabled) synchronized(lock) {
        val start=starts[thread] ?: return@synchronized
        if(stage !in stages.getValue(thread)) stages.getValue(thread)[stage]=start.elapsedNow().inWholeNanoseconds/1_000_000.0
    } }
    /** Threads with an open trace, for marks that cannot tell which thread they serve. */
    fun active(): Set<String> = if(!enabled) emptySet() else synchronized(lock) { starts.keys.toSet() }
    fun snapshot(thread: String): Map<String,Double> = synchronized(lock) { stages[thread]?.toMap().orEmpty() }
}
