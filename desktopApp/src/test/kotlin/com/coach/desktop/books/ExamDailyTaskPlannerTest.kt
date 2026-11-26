package com.coach.desktop.books

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExamDailyTaskPlannerTest {
    private val today = LocalDate.of(2026, 11, 26)

    private fun points(count: Int, wrong: Int = 0, bank: Int = 0) =
        (1..count).map { ExamPointWork("p$it", "考点$it", mastered = false, wrongQuestions = wrong, bankQuestions = bank) }

    @Test fun pastExamDoesNotDumpTheBacklog() {
        val plan = ExamDailyTaskPlanner.plan(today, today.minusDays(1), points(10, wrong = 2, bank = 2), 90)
        assertEquals(-1, plan.daysRemaining)
        assertEquals(ExamTaskTiming.PAST, plan.timing)
        assertTrue("已过" in plan.header)
        assertTrue(plan.newPointIds.isEmpty())
        assertTrue(plan.reviewWrongCount <= ExamDailyTaskPlanner.PAST_REVIEW_CAP)
        assertTrue(plan.practiceQuestionCount <= ExamDailyTaskPlanner.PAST_PRACTICE_CAP)
        assertTrue(plan.reviewWrongCount < 20)
        assertTrue(plan.minutes <= plan.budgetMinutes)
    }

    @Test fun oneDayLeftStaysInsideTheDailyMinutes() {
        val plan = ExamDailyTaskPlanner.plan(today, today.plusDays(1), points(20), 60)
        assertEquals(1, plan.daysRemaining)
        assertEquals(3, plan.newPointIds.size)
        assertEquals(60, plan.minutes)
        assertEquals(17, plan.unscheduledNewPoints)
        assertTrue(plan.minutes <= plan.budgetMinutes)
    }

    @Test fun missingThreeDaysReschedulesWithoutPilingUp() {
        val work = points(10, wrong = 1, bank = 2)
        val exam = today.plusDays(10)
        val first = ExamDailyTaskPlanner.plan(today, exam, work, 90)
        assertEquals(10, first.daysRemaining)
        assertEquals(1, first.newPointIds.size)
        assertEquals(1, first.reviewWrongCount)
        assertEquals(2, first.practiceQuestionCount)
        assertEquals(36, first.minutes)
        val later = ExamDailyTaskPlanner.plan(today.plusDays(3), exam, work, 90)
        assertEquals(7, later.daysRemaining)
        assertEquals(2, later.newPointIds.size)
        assertEquals(2, later.reviewWrongCount)
        assertEquals(3, later.practiceQuestionCount)
        assertEquals(66, later.minutes)
        assertTrue(later.newPointIds.size < 4)
        assertTrue(later.minutes <= later.budgetMinutes)
    }

    @Test fun pointWithoutBankQuestionsIsStillLearnedAndNamed() {
        val plan = ExamDailyTaskPlanner.plan(
            today,
            today.plusDays(1),
            listOf(
                ExamPointWork("a", "有题", mastered = false, bankQuestions = 3),
                ExamPointWork("b", "没题", mastered = false, bankQuestions = 0),
            ),
            90,
        )
        assertEquals(listOf("a", "b"), plan.newPointIds)
        assertEquals(3, plan.practiceQuestionCount)
        assertEquals(listOf("b"), plan.pointsWithoutQuestions)
        assertEquals(58, plan.minutes)
    }

    @Test fun todaysPinComesFirstInsideTheSameBudget() {
        val plan = ExamDailyTaskPlanner.plan(
            today,
            today.plusDays(10),
            points(10),
            90,
            listOf(ExamTaskPin("p10", today)),
        )
        assertEquals("p10", plan.newPointIds.first())
        assertEquals(1, plan.newPointIds.size)
    }

    @Test fun smallBudgetIsNotRaisedToFortyFive() {
        val plan = ExamDailyTaskPlanner.plan(
            today,
            today.plusDays(1),
            listOf(ExamPointWork("a", "A", mastered = false)),
            20,
        )
        assertEquals(20, plan.budgetMinutes)
        assertEquals(listOf("a"), plan.newPointIds)
        assertEquals(20, plan.minutes)
    }

    @Test fun passedExamDateStaysPassedOnTheOldCoursePlanner() {
        val past = ExamCountdownPlanner.compute(
            "c",
            today.minusDays(1).toString(),
            emptyList(),
            emptyList(),
            today = today,
        )
        assertEquals(-1, past.daysRemaining)
        assertEquals("考试已过", past.headerSummaryLine)
        assertEquals("考试已过", past.primaryCtaLabel)
        assertTrue(past.dailySlots.isEmpty())
        assertEquals(0, past.overdueAlertCount)
        val ahead = ExamCountdownPlanner.compute(
            "c",
            today.plusDays(14).toString(),
            emptyList(),
            emptyList(),
            today = today,
        )
        assertEquals(14, ahead.daysRemaining)
    }
}
