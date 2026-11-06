package com.coach.client

import com.coach.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ReviewSchedulingTest {
    private val noon = 1_794_052_800_000L // synthetic clock; dates are course logical days
    private val day = logicalDay(noon, "Europe/London")
    private fun ledger(exam: Long? = day + 9): List<Event> {
        val facts = mutableListOf<Pair<String, JsonObject>>()
        facts += "outline_revised" to json("course" to json("tz" to "Europe/London"),
            "ops" to JsonArray(listOf(json("kpId" to "K", "title" to "Synthetic review"))))
        facts += "legacy_passed" to json("kpId" to "K")
        facts += "legacy_schedule" to json("kpId" to "K", "dueDay" to day + 12, "interval" to 15)
        if (exam != null) facts += "exam_set" to json("examDay" to exam)
        return facts.mapIndexed { i, (type, p) -> Event(i + 1L, "e$i", "C", type,
            recordedAt = noon, occurredAt = noon, deviceId = "d", actor = "SYSTEM", payload = p) }
    }
    private fun view(events: List<Event>) = CourseView(events, project(events))

    @Test fun eightDaysBeforeExamKeepsTheNormalInterval() {
        val events = ledger(day + 8)
        val r = project(events).review.getValue("K")
        assertEquals(15, r.intervalDays)
        assertEquals(day + 12, r.dueDay)
    }

    @Test fun enteringTheFinalWeekMakesOldReviewsDueWithoutNewEvents() {
        val events = ledger()
        val v = view(events)
        assertTrue(v.dueReviews(noon + 86_400_000).isEmpty()) // eight days out
        val due = v.dueReviews(noon + 2 * 86_400_000).single() // seven days out
        assertEquals("K", due.kp.id)
        assertEquals(day + 2, due.dueDay) // first day in the exam window, not the older interval anchor
    }

    @Test fun finalWeekQueueReplaysIdenticallyAndExamPostponementRestoresTheSchedule() {
        val events = ledger(day + 7)
        val v = view(events)
        assertEquals(listOf("K"), v.dueReviews(noon + 3 * 86_400_000).map { it.kp.id })
        assertEquals(v.dueReviews(noon + 3 * 86_400_000), view(events.reversed()).dueReviews(noon + 3 * 86_400_000))
        val postponed = events + events.last().copy(seq = 5, id = "postponed", idemKey = "postponed",
            payload = json("examDay" to day + 30))
        assertTrue(view(postponed).dueReviews(noon + 3 * 86_400_000).isEmpty())
        assertTrue(view(ledger(null)).dueReviews(noon + 3 * 86_400_000).isEmpty())
    }

    @Test fun projectionAndQueueUseTheSameCourseZoneAndFourAmBoundary() {
        val events = ledger(null).map { e -> if (e.type == "outline_revised") e.copy(payload =
            JsonObject(e.payload + ("course" to json("tz" to "Asia/Shanghai")))) else e }
        val v = view(events)
        assertEquals("Asia/Shanghai", v.projection.timeZone)
        assertEquals(v.projection.timeZone, v.zone)
        // Shanghai 04:00 is 20:00 UTC on the previous calendar date.
        val utcMidnight = noon / 86_400_000 * 86_400_000
        val boundary = utcMidnight + 20 * 3_600_000
        assertEquals(v.logicalToday(boundary - 1) + 1, v.logicalToday(boundary))
    }

    @Test fun anEmptyLedgerHasNoDueReviewsOrExamDate() {
        val v = view(emptyList())
        assertTrue(v.dueReviews(noon).isEmpty())
        assertNull(v.examDay)
    }

    @Test fun completedTodayDoesNotReenterTheCompressedQueueAndSnoozeStillWorks() {
        val events = ledger(day + 1)
        assertEquals(1, view(events).dueReviews(noon).size)
        val completed = events + events.last().copy(seq = 5, id = "done", idemKey = "done",
            type = "review_completed", payload = json("kpId" to "K", "result" to "PASS"))
        assertTrue(view(completed).dueReviews(noon).isEmpty())
        val snoozed = events + events.last().copy(seq = 5, id = "snooze", idemKey = "snooze",
            type = "review_snoozed", payload = json("kpId" to "K", "untilDay" to day + 1))
        assertTrue(view(snoozed).dueReviews(noon).isEmpty())
        assertEquals(1, view(snoozed).dueReviews(noon + 86_400_000).size)
    }

    @Test fun queueAndChatFollowCourseBoundaryAndActiveOutline() {
        val events = ledger(day + 9).map { e -> if (e.type == "outline_revised") e.copy(payload =
            JsonObject(e.payload + ("course" to json("tz" to "Asia/Shanghai")))) else e }
        val state = project(events)
        val v = CourseView(events, state)
        val boundary = noon / 86_400_000 * 86_400_000 + 20 * 3_600_000
        val boundaryDay = logicalDay(boundary, "Asia/Shanghai")
        val boundaryState = state.copy(examDay = null, review = mapOf("K" to Review(boundaryDay)))
        val boundaryView = CourseView(events, boundaryState)
        assertTrue(boundaryState.today(boundary - 1).dueKpIds.isEmpty())
        assertEquals(listOf("K"), boundaryState.today(boundary).dueKpIds)
        assertEquals(boundaryState.today(boundary).dueKpIds, boundaryView.dueReviews(boundary).map { it.kp.id })
        for (time in listOf(boundary - 1, boundary, boundary + 86_400_000)) {
            assertEquals(v.dueReviews(time).map { it.kp.id }, state.today(time).dueKpIds)
        }
        val retire = events.last().copy(seq = 5, id = "retired", idemKey = "retired", type = "outline_revised",
            payload = json("ops" to JsonArray(listOf(json("op" to "retire", "kpId" to "K")))))
        val retired = project(events + retire)
        assertTrue(retired.today(noon + 3 * 86_400_000).dueKpIds.isEmpty())
        assertTrue(CourseView(events + retire, retired).dueReviews(noon + 3 * 86_400_000).isEmpty())
        val restore = retire.copy(seq = 6, id = "restored", idemKey = "restored",
            payload = json("ops" to JsonArray(listOf(json("op" to "add", "kpId" to "K")))))
        assertEquals(listOf("K"), project(events + retire + restore).today(noon + 3 * 86_400_000).dueKpIds)
        val replaced = retire.copy(id = "replacement", idemKey = "replacement",
            payload = json("knowledgePoints" to JsonArray(listOf(json("id" to "other", "title" to "Other")))))
        val afterReplacement = project(events + replaced)
        assertTrue(afterReplacement.today(noon + 3 * 86_400_000).dueKpIds.isEmpty())
        assertTrue(CourseView(events + replaced, afterReplacement).dueReviews(noon + 3 * 86_400_000).isEmpty())
    }
}
