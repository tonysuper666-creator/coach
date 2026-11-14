package com.coach.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class ReviewRetentionPolicyTest {
    private val now = 1_794_052_800_000L
    private val day = logicalDay(now, "Europe/London")
    private fun ledger(priorities: JsonObject = json("K" to json("stars" to 3, "reviewed" to true))): List<Event> =
        listOf("outline_revised" to json("ops" to JsonArray(listOf(json("kpId" to "K", "title" to "Synthetic")))),
            "legacy_passed" to json("kpId" to "K"),
            "legacy_schedule" to json("kpId" to "K", "dueDay" to day + 20, "interval" to 20),
            "course_configured" to json("timeZone" to "Europe/London", "reviewPriorities" to priorities))
            .mapIndexed { i, (type, p) -> Event(i + 1L, "e$i", "C", type, recordedAt = now, occurredAt = now,
                deviceId = "synthetic", actor = "SYSTEM", payload = p) }

    @Test fun replayAndSerializationDoNotCutTheIntervalAgain() {
        val events = ledger()
        val p = project(events)
        val r = p.review.getValue("K")
        assertEquals(.95, r.targetRetention)
        assertEquals(20, r.baseIntervalDays)
        assertEquals(day + 20, r.baseDueDay)
        assertEquals(r, r.onDay(day + 9, null))
        assertEquals(p, project(events.reversed()))
        val restored = LedgerJson.decodeFromString(Projection.serializer(), p.bytes())
        assertEquals(p, restored)
        assertEquals(p.reviewsOn(day + 9), restored.reviewsOn(day + 9))
        assertEquals(ReviewRetentionPolicy.BASELINE, ReviewRetentionPolicy.target(null))
    }

    @Test fun onlyExplicitCheckedStarsChangeRetentionNotExamPointWeights() {
        val unchecked = ledger(json("K" to json("stars" to 3, "reviewed" to false)))
        val points = unchecked + unchecked.last().copy(seq = 5, id = "points", idemKey = "points", type = "exam_weights_set",
            payload = json("weights" to json("K" to 300)))
        assertEquals(.90, project(points).review.getValue("K").targetRetention)
        assertEquals(day + 20, project(points).review.getValue("K").dueDay)
        assertTrue(project(points).reviewStars.isEmpty())
        for (stars in 1..2) assertEquals(20,
            project(ledger(json("K" to json("stars" to stars, "reviewed" to true)))).review.getValue("K").intervalDays)
    }

    @Test fun removingPriorityOrPostponingExamRestoresTheCorrectReference() {
        val events = ledger()
        val r = project(events).review.getValue("K")
        val exam = r.onDay(day + 7, day + 14)
        assertEquals(3, exam.intervalDays)
        assertEquals(r, exam.onDay(day + 7, day + 30))
        val removed = events + events.last().copy(seq = 5, id = "removed", idemKey = "removed",
            payload = json("timeZone" to "Europe/London", "reviewPriorities" to json()))
        val restored = project(removed).review.getValue("K")
        assertEquals(20, restored.intervalDays)
        assertEquals(day + 20, restored.dueDay)
        assertNull(restored.baseDueDay)
        val zoneOnly = events + events.last().copy(seq = 5, id = "zone", idemKey = "zone", payload = json("timeZone" to "Europe/London"))
        assertEquals(r, project(zoneOnly).review.getValue("K"))
        assertEquals(day + 15, r.copy(snoozedUntilDay = day + 15).onDay(day + 7, day + 14).dueDay)
    }

    @Test fun replayPreservesLegacyDueIdentityAndMasteryEvidence() {
        val weighted = project(ledger())
        val baseline = project(ledger().dropLast(1))
        assertEquals(baseline.mastery, weighted.mastery)
        assertEquals(baseline.journal, weighted.journal)
        val scope = com.coach.domain.reviewqueue.QueueScope("C")
        assertEquals(com.coach.domain.reviewqueue.legacyDueId(scope, "K", baseline.review.getValue("K")),
            com.coach.domain.reviewqueue.legacyDueId(scope, "K", weighted.review.getValue("K")))
    }

    @Test fun malformedPrioritiesCannotBoostAndDoNotFallBackToPointWeights() {
        for (bad in listOf(JsonNull, JsonArray(emptyList()), json("K" to json("stars" to 4, "reviewed" to true)),
                json("K" to json("stars" to "3", "reviewed" to true)), json("K" to json("stars" to 3)))) {
            assertFalse(validReviewPriorities(bad), bad.toString())
        }
        assertTrue(validReviewPriorities(null))
        assertTrue(validReviewPriorities(json()))
        assertFailsWith<IllegalArgumentException> { ReviewRetentionPolicy.interval(20, 1.0) }
        assertEquals(1, ReviewRetentionPolicy.interval(1, .95))
    }

    @Test fun agentPriorityDoesNotRetagLearnerSchedules() {
        val events = ledger().dropLast(1)
        val agent = ledger().last().copy(threadId = agentThreadId("synthetic-agent", "priority"))
        assertEquals(20, project(events + agent).review.getValue("K").intervalDays)
        assertTrue(project(events + agent).reviewStars.isEmpty())
    }
}
