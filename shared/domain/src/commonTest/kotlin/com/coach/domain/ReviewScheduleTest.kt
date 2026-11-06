package com.coach.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class ReviewScheduleTest {
    @Test fun examOverlayOnlyAppliesInTheFinalSevenLogicalDaysAndIsReversible() {
        val normal = Review(dueDay = 120, intervalDays = 20)
        assertEquals(normal, normal.onDay(102, 110)) // eight days out
        val compressed = normal.onDay(103, 110)
        assertEquals(3, compressed.intervalDays)
        assertEquals(103, compressed.dueDay)
        assertEquals(compressed, compressed.onDay(103, 110))
        assertEquals(101, compressed.onDay(109, 110).dueDay)
        assertEquals(1, compressed.onDay(110, 110).intervalDays)
        assertEquals(normal, compressed.onDay(111, 110))
        assertEquals(normal, compressed.onDay(103, 130))
        assertEquals(normal, compressed.onDay(103, null))
        assertEquals(109, Review(110, 1).onDay(109, 110).dueDay)
    }

    @Test fun latestExplicitClockWinsAndExamAtUsesThatClock() {
        fun event(seq: Long, type: String, payload: JsonObject) = Event(seq, "e$seq", "C", type,
            recordedAt = 0, occurredAt = 0, deviceId = "d", actor = "SYSTEM", payload = payload)
        val events = listOf(
            event(1, "outline_revised", json("course" to json("tz" to "Asia/Shanghai"))),
            event(2, "course_configured", json("timeZone" to "Europe/London")),
            event(3, "exam_set", json("examAt" to 1_794_052_800_000L)))
        assertEquals("Europe/London", courseTimeZone(events.reversed()))
        assertEquals(logicalDay(1_794_052_800_000L, "Europe/London"), courseExamDay(events, courseTimeZone(events)))
        assertNull(courseExamDay(events + event(4, "exam_set", json("examDay" to JsonNull)), courseTimeZone(events)))
    }

    @Test fun compressionSwitchesAtFourAmAcrossTheLondonDstChange() {
        // 2026-10-25: the UK clock moved back before the logical-day boundary, 04:00Z.
        val boundary = 1_792_900_800_000L
        assertEquals(logicalDay(boundary - 1, "Europe/London") + 1, logicalDay(boundary, "Europe/London"))
        val day = logicalDay(boundary, "Europe/London")
        val normal = Review(day + 10, 15)
        assertEquals(15, normal.onDay(day - 1, day + 7).intervalDays)
        assertEquals(3, normal.onDay(day, day + 7).intervalDays)
    }
}
