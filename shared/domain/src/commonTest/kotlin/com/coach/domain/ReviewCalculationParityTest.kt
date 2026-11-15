package com.coach.domain

import kotlin.test.*

/** Characterization of PR207 before extracting its calculation. No local-book facts enter this fold. */
class ReviewCalculationParityTest {
    private val start = 1793188800000L
    private val day = logicalDay(start, "Europe/London")
    private fun ledger(): MutableList<Event> = mutableListOf<Event>().also {
        add(it, 0, "legacy_passed", json("kpId" to "kp"))
    }
    private fun add(events: MutableList<Event>, offset: Int, type: String, payload: kotlinx.serialization.json.JsonObject) {
        val seq = events.size + 1L
        events += Event(seq, "parity-$seq", "synthetic", type, recordedAt = start + offset * 86400000L,
            occurredAt = start + offset * 86400000L, deviceId = "test", actor = "SYSTEM", idemKey = "parity-$seq", payload = payload)
    }
    @Test fun originalInitializationAndDailyTransitionsKeepFullReviewJournalAndMastery() {
        val events = ledger()
        val initial = project(events)
        assertEquals(Review(day + 1, 1), initial.review.getValue("kp"))
        assertEquals(listOf("kp"), initial.journal.getValue(day).newlyPassed)
        assertFalse(initial.mastery.getValue("kp").needsRepair)
        val expectations = listOf(
            "PASS" to Review(day + 3, 2, 2.4, lastResult = "PASS", lastReviewDay = day + 1),
            "NEEDS_WORK" to Review(day + 3, 1, 2.25, lastResult = "NEEDS_WORK", lastReviewDay = day + 2),
            "FAIL" to Review(day + 4, 1, 2.05, lapses = 1, lastResult = "FAIL", lastReviewDay = day + 3),
            "PASS" to Review(day + 6, 2, 2.15, lapses = 1, lastResult = "PASS", lastReviewDay = day + 4))
        for ((i, pair) in expectations.withIndex()) {
            val (result, expected) = pair
            add(events, i + 1, "review_completed", json("kpId" to "kp", "result" to result))
            val actual = project(events)
            assertEquals(expected, actual.review.getValue("kp"))
            assertEquals(result != "PASS", actual.mastery.getValue("kp").needsRepair)
            val journal = actual.journal.getValue(day + i + 1)
            assertEquals(if (result == "PASS") 1 else 0, journal.reviewPass)
            assertEquals(if (result == "NEEDS_WORK") 1 else 0, journal.reviewNeedsWork)
            assertEquals(if (result == "FAIL") 1 else 0, journal.reviewFail)
            add(events, i + 1, "review_completed", json("kpId" to "kp", "result" to "FAIL"))
            val repeated = project(events + events.last())
            assertEquals(actual.review, repeated.review)
            assertEquals(actual.mastery, repeated.mastery)
            assertEquals(actual.journal, repeated.journal)
        }
    }

    @Test fun sharedCalculationMatchesLedgerInitializationAndConsecutiveResults() {
        val events = ledger()
        var calculated = initialReview(day)
        assertEquals(project(events).review.getValue("kp"), calculated)
        for ((i, result) in listOf("PASS", "NEEDS_WORK", "FAIL", "PASS", "PASS", "FAIL").withIndex()) {
            val on = day + i + 1
            calculated = nextReview(calculated, on, result)
            add(events, i + 1, "review_completed", json("kpId" to "kp", "result" to result))
            assertEquals(project(events).review.getValue("kp"), calculated)
            assertEquals(calculated, nextReview(calculated, on, "FAIL"))
        }
        assertEquals(Review(day + 3, 3), initialReview(day, solid = true))
        // Preserve every non-calculated field too (including reversible exam overlay fields).
        val snoozed = Review(day + 20, 8, 2.9, 2, "FAIL", day - 1, day + 30, 12, day + 20)
        assertEquals(snoozed.copy(dueDay = day + 23, intervalDays = 23, ease = 3.0,
            lastResult = "PASS", lastReviewDay = day, snoozedUntilDay = null), nextReview(snoozed, day, "PASS"))
    }
}
