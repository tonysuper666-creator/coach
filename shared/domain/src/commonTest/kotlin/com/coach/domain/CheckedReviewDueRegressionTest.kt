package com.coach.domain

import kotlinx.serialization.json.JsonArray
import kotlin.test.*

/** Intentionally uses the baseline public API too: this same counterexample can run on 253d66c8. */
class CheckedReviewDueRegressionTest {
    @Test fun checkedThreeStarsMoveTheRealDueDateWhileOneAndTwoStarsKeepCoverage() {
        val now = 1_794_052_800_000L
        val day = logicalDay(now, "Europe/London")
        val facts = mutableListOf<Pair<String, kotlinx.serialization.json.JsonObject>>()
        facts += "outline_revised" to json("ops" to JsonArray((1..3).map { json("kpId" to "K$it", "title" to "Synthetic $it") }))
        for (i in 1..3) {
            facts += "legacy_passed" to json("kpId" to "K$i")
            facts += "legacy_schedule" to json("kpId" to "K$i", "dueDay" to day + 20, "interval" to 20)
        }
        facts += "course_configured" to json("timeZone" to "Europe/London", "reviewPriorities" to
            json(*((1..3).map { "K$it" to json("stars" to it, "reviewed" to true) }.toTypedArray())))
        val events = facts.mapIndexed { i, (type, payload) -> Event(i + 1L, "synthetic-$i", "C", type,
            recordedAt = now, occurredAt = now, deviceId = "synthetic", actor = "SYSTEM", payload = payload) }
        val baseline = project(events.dropLast(1))
        val weighted = project(events)
        assertEquals(day + 20, baseline.review.getValue("K3").dueDay)
        assertEquals(day + 9, weighted.review.getValue("K3").dueDay)
        assertEquals(9, weighted.review.getValue("K3").intervalDays)
        assertEquals(listOf("K3"), weighted.reviewsOn(day + 9).keys.toList())
        assertEquals(setOf("K1", "K2", "K3"), weighted.reviewsOn(day + 20).keys)
        assertEquals(baseline.review.getValue("K1"), weighted.review.getValue("K1"))
        assertEquals(baseline.review.getValue("K2"), weighted.review.getValue("K2"))
        println("SYNTHETIC_RETENTION referenceTarget=0.90 checkedThreeStarTarget=0.95 referenceInterval=20 weightedInterval=9 dueAdvance=11d oneTwoStarInterval=20")
    }
}
