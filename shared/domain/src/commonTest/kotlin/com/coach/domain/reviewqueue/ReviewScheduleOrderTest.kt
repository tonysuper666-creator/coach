package com.coach.domain.reviewqueue

import kotlin.test.*

/** The queue order the review entry really renders: weights are an input, not a label. */
class ReviewScheduleOrderTest {
    private val scope = QueueScope("synthetic-course")
    private val day = 20_000L

    private fun due(vararg kpAndDue: Pair<String, Long>): List<QueueEntry> {
        var q = ReviewQueueCandidate(scope)
        for ((kp, dueDay) in kpAndDue) {
            q = q.observe(scope, kp, QueueQuestion("q-$kp", "v1", "fp-$kp"),
                QueueSource("wrong-$kp", QueueReason.WRONG), dueDay)
        }
        return q.dueEntries(day)
    }

    private fun order(entries: List<QueueEntry>, weights: Map<String, Int>, examDay: Long? = null, on: Long = day) =
        scheduleOrder(entries, on, weights, examDay).map { it.kpId }

    @Test fun examWeightsChangeTheActualScheduleNotJustTheDisplayedLabel() {
        val entries = due("kp-a" to day, "kp-b" to day)
        assertEquals(listOf("kp-b", "kp-a"), order(entries, mapOf("kp-b" to 3)))
        assertEquals(listOf("kp-a", "kp-b"), order(entries, emptyMap()))
    }

    @Test fun aLowFrequencyPointIsNotStarvedForever() {
        val entries = due("kp-a" to day - 2, "kp-b" to day)
        val weights = mapOf("kp-b" to 3, "kp-a" to 0)
        // Two days overdue is not yet enough: the weighted point still goes first.
        assertEquals(listOf("kp-b", "kp-a"), order(entries, weights, on = day))
        // Once the low-frequency point has waited long enough it is reached, despite the weight.
        assertEquals(listOf("kp-a", "kp-b"), order(entries, weights, on = day + 1))
    }

    @Test fun theExamWeekComesFromTheCourseConfigurationAndShortensTheWait() {
        val entries = due("kp-a" to day - 2, "kp-b" to day)
        val weights = mapOf("kp-b" to 3, "kp-a" to 0)
        // No configured exam date, or one far away: the normal bound applies.
        assertEquals(listOf("kp-b", "kp-a"), order(entries, weights, examDay = null))
        assertEquals(listOf("kp-b", "kp-a"), order(entries, weights, examDay = day + 40))
        // A configured exam three days out pulls everything due forward, so the low point is reached now.
        assertEquals(listOf("kp-a", "kp-b"), order(entries, weights, examDay = day + 3))
        // The same configuration is read, not a hard-coded course date: moving the exam out of the window restores the bound.
        assertEquals(listOf("kp-b", "kp-a"), order(entries, weights, examDay = day + 9))
    }

    @Test fun orderingIsStableAndNeverDependsOnInputOrder() {
        val entries = due("kp-b" to day, "kp-a" to day, "kp-c" to day - 1)
        val weights = mapOf("kp-a" to 2, "kp-b" to 2)
        assertEquals(listOf("kp-a", "kp-b", "kp-c"), order(entries, weights))
        assertEquals(order(entries, weights), order(entries.reversed(), weights))
    }
}
