package com.coach.client

import com.coach.domain.*
import com.coach.domain.reviewqueue.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ReviewPriorityQueueTest {
    private val now = 1_794_052_800_000L
    private val day = logicalDay(now, "Europe/London")
    private fun facts(rows: List<Pair<String, JsonObject>>) = rows.mapIndexed { i, (type, payload) ->
        Event(i + 1L, "synthetic-$i", "C", type, recordedAt = now, occurredAt = now,
            deviceId = "synthetic", actor = "SYSTEM", payload = payload)
    }
    private fun config() = "course_configured" to json("timeZone" to "Europe/London", "reviewPriorities" to
        json("K1" to json("stars" to 1, "reviewed" to true), "K2" to json("stars" to 2, "reviewed" to true),
            "K3" to json("stars" to 3, "reviewed" to true)))
    private fun outline() = "outline_revised" to json("ops" to JsonArray((1..3).map { json("kpId" to "K$it", "title" to "Synthetic $it") }))
    private val port = VariantCandidatePort { course, run, kp -> (1..20).map {
        VariantCandidate(course, kp, QueueQuestion("fresh-$kp-$it", fingerprint = "fp-$kp-$it"),
            keyVerified = true, agentRunId = run)
    } }

    @Test fun earlierRetentionDeadlineChangesTheActualQueueAndTodayCount() {
        val rows = mutableListOf(outline())
        for (i in 1..3) {
            rows += "legacy_passed" to json("kpId" to "K$i")
            rows += "legacy_schedule" to json("kpId" to "K$i", "dueDay" to day + 20, "interval" to 20)
        }
        val baseline = facts(rows)
        val weighted = facts(rows + config())
        fun board(events: List<Event>, on: Long) = reviewBoard("C", CourseView(events, project(events)), on, port)
        assertTrue(board(baseline, day + 9).items.isEmpty())
        val due = board(weighted, day + 9)
        assertEquals(listOf("K3"), due.items.map { it.kp.id })
        assertEquals(listOf("K3"), project(weighted).today(now + 9 * 86_400_000L).dueKpIds)
        assertEquals("fresh-K3-1", due.items.single().variantQuestion?.id)
        assertEquals(3, board(weighted, day + 20).items.size)
        assertEquals(3, project(weighted).today(now + 20 * 86_400_000L).dueKpIds.size)
        assertEquals(due, board(weighted.reversed(), day + 9))
    }

    @Test fun checkedThreeStarTopicsLeadRoundsButDoNotOccupyEveryCoverageSlot() {
        val rows = mutableListOf(outline(), config(), "exam_weights_set" to json("weights" to json("K1" to 300, "K3" to 1)))
        for ((kp, count) in listOf("K3" to 4, "K2" to 1, "K1" to 1)) for (n in 1..count) {
            val attempt = "original-$kp-$n"
            rows += "question_posed" to json("attemptId" to attempt, "questionId" to "old-$kp-$n", "kpIds" to listOf(kp),
                "format" to "SINGLE_CHOICE", "keyState" to "VERIFIED", "mainline" to true, "posedAt" to now)
            rows += "answer_submitted" to json("attemptId" to attempt, "answer" to "A")
            rows += "attempt_graded" to json("attemptId" to attempt, "score" to 0, "max" to 1, "status" to "EXACT")
        }
        val events = facts(rows)
        assertEquals(.95, project(events).reviewsOn(day + 1).getValue("K3").targetRetention)
        val board = reviewBoard("C", CourseView(events, project(events)), day + 1, port)
        assertEquals(listOf("K3", "K2", "K1"), board.items.take(3).map { it.kp.id })
        val questions = board.items.mapNotNull { it.variantQuestion?.id }
        assertEquals(questions.size, questions.distinct().size)
        assertTrue(questions.all { it.startsWith("fresh-") })
        assertTrue(board.items.all { it.original == null || it.original.id != it.variantQuestion?.id })
        println("SYNTHETIC_COVERAGE firstRound=${board.items.take(3).map { it.kp.id }} unseenVariants=${questions.size}")
    }

    @Test fun oneAndTwoStarTopicsGetTheFirstSlotUnderContinuousThreeStarArrivals() {
        var servedLow = false
        var servedMiddle = false
        for (offset in 0L..5L) {
            val on = day + offset
            var queue = ReviewQueueCandidate(QueueScope("C"))
            if (!servedLow) queue = queue.observe(queue.scope, "K1", QueueQuestion("low"), QueueSource("low-wrong", QueueReason.WRONG), day)
            if (!servedMiddle) queue = queue.observe(queue.scope, "K2", QueueQuestion("middle"), QueueSource("middle-wrong", QueueReason.WRONG), day)
            queue = queue.observe(queue.scope, "K3", QueueQuestion("high-$offset"), QueueSource("high-$offset", QueueReason.WRONG), on)
            val first = scheduleOrder(queue.dueEntries(on), on, mapOf("K3" to 1000),
                reviewStars = mapOf("K1" to 1, "K2" to 2, "K3" to 3)).first().kpId
            if (first == "K1") servedLow = true
            if (first == "K2") servedMiddle = true
        }
        assertTrue(servedLow && servedMiddle, "Both lower-priority topics must be reached with one slot/day")
    }

    @Test fun mainlineMissCannotInventStarvationAheadOfAnOlderLowPriorityDeadline() {
        val rows = mutableListOf(outline(), config())
        for ((kp, due) in listOf("K1" to day - 5, "K3" to day + 20)) {
            rows += "legacy_passed" to json("kpId" to kp)
            rows += "legacy_schedule" to json("kpId" to kp, "dueDay" to due, "interval" to 20)
        }
        rows += "question_posed" to json("attemptId" to "new-miss", "questionId" to "new-miss",
            "kpIds" to listOf("K3"), "fingerprint" to "new-miss", "keyState" to "VERIFIED",
            "format" to "SINGLE_CHOICE", "origin" to "COACH", "mainline" to true, "policyVersion" to 1)
        rows += "answer_submitted" to json("attemptId" to "new-miss", "answer" to "synthetic")
        rows += "attempt_graded" to json("attemptId" to "new-miss", "score" to 0, "max" to 10, "status" to "EXACT")
        val events = facts(rows)
        val p = project(events)
        fun board(on: Long) = reviewBoard("C", CourseView(events, p), on, port)
        assertEquals(listOf("K1"), board(day).items.map { it.kp.id }.distinct())
        assertEquals(listOf("K1", "K3"), board(day + 1).items.map { it.kp.id }.distinct())
        assertEquals(day + 1, p.review.getValue("K3").dueDay)
        assertEquals(day - 5, p.review.getValue("K1").dueDay)
        println("SYNTHETIC_ANCHOR_ORDER today=${board(day).items.map { it.kp.id }} nextDay=${board(day + 1).items.map { it.kp.id }}")
    }
}
