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
    fun snapshot(thread: String): Map<String,Double> = synchronized(lock) { stages[thread]?.toMap().orEmpty() }
}
