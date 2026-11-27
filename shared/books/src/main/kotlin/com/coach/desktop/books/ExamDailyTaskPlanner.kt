package com.coach.desktop.books

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Today's exam tasks: new points, wrong-question review, and bank practice.
 * A missed day is not a backlog. The next open recomputes a single day from
 * what is still unmastered and how many days remain, then stops at the daily budget.
 */
internal enum class ExamTaskTiming { UNSET, UPCOMING, EXAM_DAY, PAST }

internal enum class QuestionGap { NONE, NEVER_GENERATED, DROPPED }

internal data class ExamPointWork(
    val id: String,
    val title: String,
    val mastered: Boolean,
    val wrongQuestions: Int = 0,
    val bankQuestions: Int = 0,
    val questionGap: QuestionGap = QuestionGap.NONE,
)

internal data class ExamTaskPin(val pointId: String, val date: LocalDate)

internal data class TodayExamTasks(
    val daysRemaining: Int?,
    val timing: ExamTaskTiming,
    val newPointIds: List<String>,
    val reviewWrongCount: Int,
    val practiceQuestionCount: Int,
    val minutes: Int,
    val budgetMinutes: Int,
    val pointsWithoutQuestions: List<String>,
    val unscheduledNewPoints: Int,
    val header: String,
) {
    fun taskLine(): String =
        "新学 ${newPointIds.size} 个考点 · 复习 $reviewWrongCount 道错题 · 做 $practiceQuestionCount 道题 · 约 $minutes 分钟"
}

internal object ExamDailyTaskPlanner {
    const val NEW_POINT_MINUTES = 20
    const val WRONG_MINUTES = 4
    const val PRACTICE_MINUTES = 6
    const val PAST_REVIEW_CAP = 3
    const val PAST_PRACTICE_CAP = 3
    private const val MASTERED_AT = 0.8

    fun plan(
        today: LocalDate,
        examDate: LocalDate?,
        points: List<ExamPointWork>,
        budgetMinutes: Int,
        pins: List<ExamTaskPin> = emptyList(),
    ): TodayExamTasks {
        val budget = budgetMinutes.coerceIn(15, 240)
        if (examDate == null) {
            return TodayExamTasks(
                daysRemaining = null,
                timing = ExamTaskTiming.UNSET,
                newPointIds = emptyList(),
                reviewWrongCount = 0,
                practiceQuestionCount = 0,
                minutes = 0,
                budgetMinutes = budget,
                pointsWithoutQuestions = emptyList(),
                unscheduledNewPoints = 0,
                header = "还没设考试日期",
            )
        }
        val days = ChronoUnit.DAYS.between(today, examDate).toInt()
        val timing = when {
            days < 0 -> ExamTaskTiming.PAST
            days == 0 -> ExamTaskTiming.EXAM_DAY
            else -> ExamTaskTiming.UPCOMING
        }
        val header = when (timing) {
            ExamTaskTiming.PAST -> "考试已过"
            ExamTaskTiming.EXAM_DAY -> "今天考试"
            ExamTaskTiming.UPCOMING -> if (days == 1) "还剩 1 天" else "距考试 $days 天"
            ExamTaskTiming.UNSET -> "还没设考试日期"
        }
        val pinnedToday = pins.filter { it.date == today }.map { it.pointId }.toSet()
        val pool = if (timing == ExamTaskTiming.PAST) {
            points.filter { it.id in pinnedToday }
        } else {
            points.filter { !it.mastered || it.id in pinnedToday }
        }
        val ordered = pool.sortedWith(compareBy<ExamPointWork> { if (it.id in pinnedToday) 0 else 1 })
        val horizon = if (timing == ExamTaskTiming.PAST) 1 else days.coerceAtLeast(1)
        val take = if (timing == ExamTaskTiming.PAST) ordered.size else ceilDiv(ordered.size, horizon)
        var newPoints = ordered.take(take)
        val wrongTotal = points.sumOf { it.wrongQuestions.coerceAtLeast(0) }
        val bankTotal = points.sumOf { it.bankQuestions.coerceAtLeast(0) }
        var review = if (timing == ExamTaskTiming.PAST) {
            minOf(wrongTotal, PAST_REVIEW_CAP)
        } else {
            ceilDiv(wrongTotal, horizon)
        }
        var practice = if (timing == ExamTaskTiming.PAST) {
            minOf(bankTotal, PAST_PRACTICE_CAP)
        } else {
            ceilDiv(bankTotal, horizon)
        }
        fun cost() = newPoints.size * NEW_POINT_MINUTES + review * WRONG_MINUTES + practice * PRACTICE_MINUTES
        while (cost() > budget && practice > 0) practice--
        while (cost() > budget && review > 0) review--
        while (cost() > budget && newPoints.isNotEmpty()) newPoints = newPoints.dropLast(1)
        val gaps = newPoints.filter { it.bankQuestions <= 0 }.map { it.id }
        return TodayExamTasks(
            daysRemaining = days,
            timing = timing,
            newPointIds = newPoints.map { it.id },
            reviewWrongCount = review,
            practiceQuestionCount = practice,
            minutes = cost(),
            budgetMinutes = budget,
            pointsWithoutQuestions = gaps,
            unscheduledNewPoints = (ordered.size - newPoints.size).coerceAtLeast(0),
            header = header,
        )
    }

    fun mastered(pMastery: Double, hasRecord: Boolean): Boolean = hasRecord && pMastery >= MASTERED_AT

    private fun ceilDiv(numerator: Int, denominator: Int): Int {
        if (numerator <= 0 || denominator <= 0) return 0
        return (numerator + denominator - 1) / denominator
    }
}
