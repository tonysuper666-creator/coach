package com.coach.client

import kotlin.time.TimeSource

/** Opt-in acceptance tracing. No question text, credentials or learner answers. */
object PoolTrace {
    @kotlin.concurrent.Volatile var enabled=false
    private val lock=Any()
    private val starts=HashMap<String,TimeSource.Monotonic.ValueTimeMark>()
    private val stages=HashMap<String,MutableMap<String,Double>>()
    private val timelines=HashMap<String,MutableList<Pair<String,Double>>>()
    fun begin(thread: String) { if(enabled) synchronized(lock) {
        starts[thread]=TimeSource.Monotonic.markNow();stages[thread]=linkedMapOf();timelines[thread]=mutableListOf()
    } }
    fun mark(thread: String,stage: String) { if(enabled) synchronized(lock) {
        val start=starts[thread] ?: return@synchronized
        record(thread,stage,start)
    } }
    fun markActive(stage: String) { if(enabled) synchronized(lock) {
        starts.forEach { (thread,start) ->
            record(thread,stage,start)
        }
    } }
    fun snapshot(thread: String): Map<String,Double> = synchronized(lock) { stages[thread]?.toMap().orEmpty() }
    fun timeline(thread: String): List<Pair<String,Double>> = synchronized(lock) { timelines[thread]?.toList().orEmpty() }
    private fun record(thread: String,stage: String,start: TimeSource.Monotonic.ValueTimeMark) {
        val elapsed=start.elapsedNow().inWholeNanoseconds/1_000_000.0
        if(stage !in stages.getValue(thread)) stages.getValue(thread)[stage]=elapsed
        timelines.getValue(thread).let { if(it.size<128) it.add(stage to elapsed) }
    }
}
