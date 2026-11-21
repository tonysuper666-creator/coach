package com.coach.domain.fsrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pre-exam compression the V2 plan promises: inside the last three weeks the target retention
 * rises to 0.94 and no interval may outlive a quarter of the days left, so every card comes back
 * about four times before the exam. These tests are the arithmetic; `FsrsLoadSimulationTest`
 * measures what it costs per day.
 */
class FsrsExamWindowTest {
    private val base = FsrsConfig()

    @Test
    fun outsideTheWindowNothingChanges() {
        assertEquals(base, base.forExamWindow(22))
        assertEquals(base, base.forExamWindow(120))
    }

    @Test
    fun insideTheWindowRetentionRisesAndTheCeilingTightens() {
        val cram = base.forExamWindow(21)
        assertEquals(FsrsConfig.EXAM_DESIRED_RETENTION, cram.desiredRetention)
        assertEquals(5, cram.maximumIntervalDays)

        assertEquals(1, base.forExamWindow(0).maximumIntervalDays)
        assertEquals(1, base.forExamWindow(3).maximumIntervalDays)
        assertEquals(1, base.forExamWindow(4).maximumIntervalDays)
        assertEquals(2, base.forExamWindow(8).maximumIntervalDays)
        assertEquals(5, base.forExamWindow(21).maximumIntervalDays)
    }

    @Test
    fun theCeilingOnlyEverTightensACallerSuppliedConfig() {
        val strict = FsrsConfig(desiredRetention = 0.97, maximumIntervalDays = 2)
        val cram = strict.forExamWindow(20)
        assertEquals(0.97, cram.desiredRetention, "a stricter target must not be loosened to 0.94")
        assertEquals(2, cram.maximumIntervalDays, "a tighter ceiling must survive the window")
    }

    @Test
    fun aStrongCardCannotEscapeTheExam() {
        val strong = FsrsState(
            dueDay = 77,
            stability = 3_000.0,
            difficulty = 5.0,
            lastReviewDay = 70,
            reps = 9,
        )
        val relaxed = Fsrs5.review(strong, FsrsRating.GOOD, day = 77, config = base)
        assertTrue(relaxed.dueDay - 77 > 60, "outside the window a strong card may rest for months")

        val daysToExam = 14L
        val crammed = Fsrs5.review(strong, FsrsRating.GOOD, day = 77, config = base.forExamWindow(daysToExam))
        assertEquals(77 + 3, crammed.dueDay, "fourteen days left allows at most a three-day interval")
    }

    @Test
    fun theQueuePullsForwardWhatAnEarlierIntervalScheduledPastTheExam() {
        val queue = FsrsDailyQueue()
        val farOut = FsrsQueueCard(
            cardId = "c1",
            materialId = "book/ch01",
            kpId = "kp1",
            syllabusWeight = 1.0,
            state = FsrsState(dueDay = 200, stability = 300.0, difficulty = 5.0, lastReviewDay = 60),
        )
        assertEquals(0, queue.plan(listOf(farOut), day = 70, examDay = 98).priorityCount)
        assertEquals(1, queue.plan(listOf(farOut), day = 77, examDay = 98).priorityCount)

        val nearlyDue = farOut.copy(cardId = "c2", state = farOut.state.copy(dueDay = 79))
        assertEquals(0, queue.plan(listOf(nearlyDue), day = 77, examDay = 98).priorityCount)
    }

    @Test
    fun theLastDayPullsEveryCardBack() {
        val card = FsrsState(dueDay = 97, stability = 40.0, difficulty = 5.0, lastReviewDay = 90)
        val eve = Fsrs5.review(card, FsrsRating.GOOD, day = 97, config = base.forExamWindow(1))
        assertEquals(98, eve.dueDay)
    }
}
