package com.coach.client

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubmissionTraceTest {
    @AfterTest fun reset() { SubmissionTrace.enabled = false; SubmissionTrace.clear() }

    @Test fun offByDefaultStoresNothing() {
        SubmissionTrace.enabled = false
        SubmissionTrace.mark("abc", "ui.effectStart")
        assertTrue(SubmissionTrace.snapshot().isEmpty())
    }

    @Test fun recordsOnlyIdEventAndMonotonicTime() {
        SubmissionTrace.enabled = true
        SubmissionTrace.mark("abc", "http.postStart")
        SubmissionTrace.mark(null, "http.postStart")
        SubmissionTrace.mark("abc", "http.postReturned")
        val events = SubmissionTrace.snapshot()
        assertEquals(listOf("http.postStart", "http.postReturned"), events.map { it.event })
        assertTrue(events.all { it.id == "abc" })
        assertTrue(events[0].nanos <= events[1].nanos)
    }

    private val chain = listOf("ui.pendingSaved", "ui.effectStart", "ui.ioStart", "http.postStart")

    @Test fun longOfflinePollingDoesNotEvictSubmissionHead() {
        SubmissionTrace.enabled = true
        chain.forEach { SubmissionTrace.mark("sub1", it) }
        repeat(5000) { SubmissionTrace.mark("test", "test.queryStart") }
        val events = SubmissionTrace.snapshot()
        assertEquals(SubmissionTrace.LIMIT, events.size)
        assertEquals(chain, events.filter { it.id == "sub1" }.map { it.event })
        val s = SubmissionTrace.stats()
        assertEquals(5000L - (SubmissionTrace.LIMIT - chain.size), s.droppedQuery)
        assertEquals(0L, s.droppedSubmission)
        assertTrue(s.complete)
    }

    @Test fun retriesWithSameIdSurviveQueryFlood() {
        SubmissionTrace.enabled = true
        chain.forEach { SubmissionTrace.mark("sub1", it) }
        repeat(600) { SubmissionTrace.mark("test", "test.queryEnd") }
        repeat(2) { SubmissionTrace.mark("sub1", "http.postStart"); repeat(300) { SubmissionTrace.mark("test", "test.queryStart") } }
        val kept = SubmissionTrace.snapshot().filter { it.id == "sub1" }.map { it.event }
        assertEquals(chain + listOf("http.postStart", "http.postStart"), kept)
        assertTrue(SubmissionTrace.stats().complete)
    }

    @Test fun normalSubmissionIsCompleteWithNoDrops() {
        SubmissionTrace.enabled = true
        chain.forEach { SubmissionTrace.mark("sub1", it) }
        val s = SubmissionTrace.stats()
        assertEquals(0L, s.droppedQuery); assertEquals(0L, s.droppedSubmission)
        assertTrue(s.complete); assertTrue(s.incompleteIds.isEmpty())
    }

    @Test fun overflowOfOnlySubmissionEventsIsReportedAsIncomplete() {
        SubmissionTrace.enabled = true
        repeat(SubmissionTrace.LIMIT + 10) { SubmissionTrace.mark("sub$it".takeIf { _ -> it < 10 } ?: "late", "http.postStart") }
        assertEquals(SubmissionTrace.LIMIT, SubmissionTrace.snapshot().size)
        val s = SubmissionTrace.stats()
        assertEquals(10L, s.droppedSubmission)
        assertEquals(0L, s.droppedQuery)
        assertTrue(!s.complete)
        assertEquals((0 until 10).map { "sub$it" }.toSet(), s.incompleteIds)
    }

    @Test fun queryEventsAreDroppedWhenBufferIsAllSubmissionEvents() {
        SubmissionTrace.enabled = true
        repeat(SubmissionTrace.LIMIT) { SubmissionTrace.mark("sub1", "http.postStart") }
        SubmissionTrace.mark("test", "test.queryStart")
        val s = SubmissionTrace.stats()
        assertEquals(1L, s.droppedQuery)
        assertEquals(0L, s.droppedSubmission)
        assertEquals(SubmissionTrace.LIMIT, SubmissionTrace.snapshot().size)
    }

    @Test fun disabledCountsNothing() {
        SubmissionTrace.enabled = false
        repeat(2000) { SubmissionTrace.mark("test", "test.queryStart") }
        val s = SubmissionTrace.stats()
        assertEquals(0L, s.droppedQuery); assertEquals(0L, s.droppedSubmission)
    }

    @Test fun memoryIsBounded() {
        SubmissionTrace.enabled = true
        repeat(SubmissionTrace.LIMIT + 50) { SubmissionTrace.mark("id", "e$it") }
        val events = SubmissionTrace.snapshot()
        assertEquals(SubmissionTrace.LIMIT, events.size)
        assertEquals("e50", events.first().event)
    }
}
