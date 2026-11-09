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

    @Test fun memoryIsBounded() {
        SubmissionTrace.enabled = true
        repeat(SubmissionTrace.LIMIT + 50) { SubmissionTrace.mark("id", "e$it") }
        val events = SubmissionTrace.snapshot()
        assertEquals(SubmissionTrace.LIMIT, events.size)
        assertEquals("e50", events.first().event)
    }
}
