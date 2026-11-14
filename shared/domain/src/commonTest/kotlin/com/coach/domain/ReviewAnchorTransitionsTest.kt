package com.coach.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class ReviewAnchorTransitionsTest {
    private val start = 1_793_188_800_000L
    private val day = logicalDay(start, "Europe/London")
    private val events = mutableListOf<Event>()
    private fun e(type: String, payload: JsonObject, offset: Long = 0) {
        val seq = events.size + 1L
        val at = start + offset * 86_400_000L
        events += Event(seq, "synthetic-$seq", "C", type, recordedAt = at + 1000, occurredAt = at,
            deviceId = "synthetic", actor = "SYSTEM", payload = payload)
    }
    private fun base() {
        e("outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "K", "title" to "Synthetic")))))
        e("legacy_passed", json("kpId" to "K"))
        e("legacy_schedule", json("kpId" to "K", "dueDay" to day + 20, "interval" to 20))
        e("course_configured", json("timeZone" to "Europe/London", "reviewPriorities" to
            json("K" to json("stars" to 3, "reviewed" to true))))
    }
    private fun mainline(id: String, verified: Boolean = true) {
        e("question_posed", json("attemptId" to id, "questionId" to id, "kpIds" to listOf("K"),
            "fingerprint" to id, "format" to "SINGLE_CHOICE", "origin" to "COACH", "mainline" to true,
            "keyState" to if (verified) "VERIFIED" else "UNREVIEWED", "policyVersion" to 1))
        e("answer_submitted", json("attemptId" to id, "answer" to "synthetic"))
        e("attempt_graded", json("attemptId" to id, "score" to if (verified) 0 else 10, "max" to 10, "status" to "EXACT"))
    }
    private fun snooze(offset: Long) = e("review_snoozed", json("kpId" to "K", "untilDay" to day + offset))

    @Test fun uncheckedMainlineLookAgainKeepsItsOriginAcrossReplayAndExamChanges() {
        base()
        val mastery = project(events).mastery
        mainline("unchecked", verified = false)
        val p = project(events)
        val r = p.review.getValue("K")
        assertEquals(day + 1, r.dueDay)
        assertEquals(day, r.intervalAnchorDay)
        assertEquals(mastery, p.mastery)
        assertTrue(p.reviewsOn(day).isEmpty())
        assertEquals(listOf("K"), p.reviewsOn(day + 1).keys.toList())
        assertEquals(p, project(events.reversed()))
        val restored = LedgerJson.decodeFromString(Projection.serializer(), p.bytes())
        assertEquals(p, restored)
        val exam = restored.review.getValue("K").onDay(day, day + 7)
        assertEquals(day + 1, exam.dueDay)
        assertEquals(exam, exam.onDay(day, day + 7))
        assertEquals(r, exam.onDay(day, null))
    }

    @Test fun newerLookAgainSupersedesOldSnoozeButLaterSnoozeStillWins() {
        base()
        snooze(10)
        mainline("miss")
        val afterMiss = project(events).review.getValue("K")
        assertEquals(day + 1, afterMiss.dueDay)
        assertNull(afterMiss.snoozedUntilDay)
        assertEquals(day, afterMiss.intervalAnchorDay)
        snooze(12)
        val postponed = project(events)
        assertEquals(day + 12, postponed.review.getValue("K").dueDay)
        assertEquals(day, postponed.review.getValue("K").intervalAnchorDay)
        assertTrue(postponed.reviewsOn(day + 11).isEmpty())
        assertEquals(listOf("K"), postponed.reviewsOn(day + 12).keys.toList())
        assertEquals(day + 12, postponed.review.getValue("K").onDay(day + 7, day + 14).dueDay)
        e("course_configured", json("reviewPriorities" to json()))
        val unconfigured = project(events).review.getValue("K")
        assertEquals(.90, unconfigured.targetRetention)
        assertEquals(day + 12, unconfigured.dueDay)
        assertEquals(20, unconfigured.intervalDays)
    }

    @Test fun realSpacedResultStartsANewIntervalWithoutChangingEaseGrowth() {
        base()
        snooze(10)
        mainline("miss")
        snooze(12)
        e("review_completed", json("kpId" to "K", "result" to "PASS"), offset = 12)
        val p = project(events)
        val r = p.review.getValue("K")
        assertEquals(46, r.baseIntervalDays) // The existing 20 * 2.3 ease schedule, unchanged by look-again.
        assertEquals(day + 58, r.baseDueDay)
        assertEquals(22, r.intervalDays)
        assertEquals(day + 34, r.dueDay) // 95% starts at the actual new review day (d0+12).
        assertEquals(day + 12, r.lastReviewDay)
        assertEquals(2.4, r.ease)
        assertEquals(0, r.lapses)
        assertNull(r.intervalAnchorDay)
        assertNull(r.snoozedUntilDay)
        assertEquals(1, p.journal.getValue(day + 12).reviewPass)
        val restored = LedgerJson.decodeFromString(Projection.serializer(), p.bytes())
        assertEquals(r, restored.review.getValue("K").onDay(day + 13, null))
    }

    @Test fun legacyReviewWithoutAnchorStillRestoresWithTheOriginalDueDate() {
        val old = LedgerJson.decodeFromString(Review.serializer(), """{"dueDay":120,"intervalDays":20}""")
        assertNull(old.intervalAnchorDay)
        assertEquals(old, old.onDay(100, null))
        assertEquals(109, old.onDay(100, null, .95).dueDay)
    }
}
