package com.coach.desktop.books

import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.min

internal data class CourseDayInput(
    val courseId: String,
    val title: String,
    val examDate: LocalDate?,
    val points: List<ExamPointWork>,
    val pins: List<ExamTaskPin> = emptyList(),
)

internal data class CourseDayTask(
    val courseId: String,
    val title: String,
    val tasks: TodayExamTasks,
    val minutes: Int,
    val reason: String,
    val lines: List<String>,
    val readFirstTitles: List<String>,
)

internal data class MultiCourseDay(
    val date: LocalDate,
    val budgetMinutes: Int,
    val courses: List<CourseDayTask>,
) {
    val totalMinutes: Int get() = courses.sumOf { it.minutes }
}

/**
 * One daily cap for every course still being prepared.
 * A course closer to its exam, or with more unmastered points, gets more of that cap.
 * A missed day is not added on top: the next day is planned again from what is left and how many days remain.
 * A point with no question is a "read this section" task, not an empty question.
 */
internal object MultiCourseDailyPlanner {
    fun preview(
        today: LocalDate,
        courses: List<CourseDayInput>,
        budgetMinutes: Int,
        days: Int = 5,
    ): List<MultiCourseDay> =
        (0 until days.coerceIn(1, 14)).map { offset ->
            plan(today.plusDays(offset.toLong()), courses, budgetMinutes)
        }

    fun plan(today: LocalDate, courses: List<CourseDayInput>, budgetMinutes: Int): MultiCourseDay {
        val cap = budgetMinutes.coerceIn(15, 240)
        val demands = courses.map { input ->
            val natural = ExamDailyTaskPlanner.plan(today, input.examDate, input.points, 240, input.pins)
            Demand(input, natural, weight(input, natural))
        }
        val active = demands
            .filter { it.weight > 0.0 && it.natural.minutes > 0 }
            .sortedBy { it.input.courseId }
        val fitted = fit(today, active, cap).associateBy { it.first.input.courseId }
        val tasks = demands.map { demand ->
            val planned = fitted[demand.input.courseId]?.second ?: zeroed(demand.natural)
            val reason = reason(demand, planned.minutes)
            val titles = demand.input.points.associateBy { it.id }
            CourseDayTask(
                courseId = demand.input.courseId,
                title = demand.input.title.ifBlank { demand.input.courseId },
                tasks = planned,
                minutes = planned.minutes,
                reason = reason,
                lines = taskLines(demand.input, planned, reason, titles),
                readFirstTitles = planned.pointsWithoutQuestions.map { id -> titles[id]?.title?.ifBlank { id } ?: id },
            )
        }
        val ordered = tasks.sortedWith(
            compareByDescending<CourseDayTask> { it.minutes }
                .thenBy { dayRank(it.tasks.daysRemaining) }
                .thenBy { it.courseId },
        )
        return MultiCourseDay(today, cap, ordered)
    }

    private data class Demand(
        val input: CourseDayInput,
        val natural: TodayExamTasks,
        val weight: Double,
    )

    private fun weight(input: CourseDayInput, natural: TodayExamTasks): Double {
        val days = natural.daysRemaining ?: return 0.0
        if (days < 0 || natural.minutes <= 0) return 0.0
        if (natural.timing != ExamTaskTiming.UPCOMING && natural.timing != ExamTaskTiming.EXAM_DAY) return 0.0
        val weak = input.points.count { !it.mastered } + input.points.count { it.wrongQuestions > 0 }
        return (1.0 / (days + 1)) * weak.coerceAtLeast(1)
    }

    private fun reason(demand: Demand, minutes: Int): String {
        val days = demand.natural.daysRemaining
        val weak = demand.input.points.count { !it.mastered }
        val whenPart = when {
            days == null -> "还没设考试日期"
            days < 0 -> "考试已过"
            days == 0 -> "今天考试"
            days == 1 -> "还剩 1 天"
            else -> "距考试 $days 天"
        }
        val weakPart = if (weak > 0) "$weak 个考点还没掌握" else "没有新的薄弱考点"
        val share = when {
            days != null && days < 0 -> "不再占今天的时间"
            minutes > 0 && weak > 0 -> "离考试近、薄弱考点多，所以今天 $minutes 分钟"
            minutes > 0 -> "今天 $minutes 分钟"
            demand.natural.minutes > 0 -> "今天的时间先给了更近、更薄弱的课，这门排进后面的天数"
            else -> "今天没有要排的任务"
        }
        return "$whenPart，$weakPart，$share"
    }

    private fun taskLines(
        input: CourseDayInput,
        tasks: TodayExamTasks,
        reason: String,
        titles: Map<String, ExamPointWork>,
    ): List<String> {
        val lines = mutableListOf<String>()
        for (id in tasks.newPointIds) {
            val point = titles[id]
            val name = point?.title?.ifBlank { id } ?: id
            if (id in tasks.pointsWithoutQuestions) {
                val why = when (point?.questionGap) {
                    QuestionGap.DROPPED -> "题被核对丢掉了，先读这一节，不排空题"
                    else -> "还没有题，先读这一节，不排空题"
                }
                lines += "$name · $why · $reason"
            } else {
                lines += "$name · 新学这个考点 · $reason"
            }
        }
        if (tasks.reviewWrongCount > 0) lines += "复习 ${tasks.reviewWrongCount} 道错题 · $reason"
        if (tasks.practiceQuestionCount > 0) lines += "做 ${tasks.practiceQuestionCount} 道题 · $reason"
        if (lines.isEmpty()) lines += reason
        return lines
    }

    private fun fit(today: LocalDate, active: List<Demand>, cap: Int): List<Pair<Demand, TodayExamTasks>> {
        if (active.isEmpty()) return emptyList()
        val weights = active.map { it.weight }
        val ceilings = active.map { it.natural.minutes }
        val budgets = apportion(weights, cap, ceilings)
        val plans = active.mapIndexed { index, demand -> fitPlan(today, demand, budgets[index]) }.toMutableList()
        while (true) {
            val left = cap - plans.sumOf { it.minutes }
            if (left <= 0) break
            val order = active.indices.sortedWith(
                compareByDescending<Int> { active[it].weight }
                    .thenBy { active[it].natural.daysRemaining ?: Int.MAX_VALUE }
                    .thenBy { active[it].input.courseId },
            )
            var grew = false
            for (index in order) {
                if (plans[index].minutes >= ceilings[index]) continue
                val give = min(left, ceilings[index] - plans[index].minutes)
                val trialBudget = plans[index].minutes + give
                if (trialBudget < 15) continue
                val trial = fitPlan(today, active[index], trialBudget)
                val delta = trial.minutes - plans[index].minutes
                if (delta <= 0 || delta > left) continue
                plans[index] = trial
                grew = true
                break
            }
            if (!grew) break
        }
        return active.mapIndexed { index, demand -> demand to plans[index] }
    }

    private fun fitPlan(today: LocalDate, demand: Demand, budget: Int): TodayExamTasks {
        if (budget < 15) return zeroed(demand.natural)
        return ExamDailyTaskPlanner.plan(
            today,
            demand.input.examDate,
            demand.input.points,
            budget.coerceIn(15, 240),
            demand.input.pins,
        )
    }

    private fun zeroed(natural: TodayExamTasks): TodayExamTasks = natural.copy(
        newPointIds = emptyList(),
        reviewWrongCount = 0,
        practiceQuestionCount = 0,
        minutes = 0,
        budgetMinutes = 0,
        pointsWithoutQuestions = emptyList(),
        unscheduledNewPoints = natural.newPointIds.size + natural.unscheduledNewPoints,
    )

    private fun apportion(weights: List<Double>, cap: Int, ceilings: List<Int>): List<Int> {
        val count = weights.size
        val shares = IntArray(count)
        if (count == 0 || cap <= 0) return shares.toList()
        val total = weights.sum()
        if (total <= 0.0) return shares.toList()
        val raw = DoubleArray(count) { cap * weights[it] / total }
        var used = 0
        for (index in 0 until count) {
            val floor = raw[index].toInt().coerceAtMost(ceilings[index].coerceAtLeast(0))
            shares[index] = floor
            used += floor
        }
        var left = cap - used
        val byFraction = (0 until count).sortedWith(
            compareByDescending<Int> { raw[it] - floor(raw[it]) }.thenBy { it },
        )
        for (index in byFraction) {
            if (left <= 0) break
            if (shares[index] < ceilings[index]) {
                shares[index] += 1
                left -= 1
            }
        }
        val byWeight = (0 until count).sortedWith(compareByDescending<Int> { weights[it] }.thenBy { it })
        while (left > 0) {
            val index = byWeight.firstOrNull { shares[it] < ceilings[it] } ?: break
            shares[index] += 1
            left -= 1
        }
        return shares.toList()
    }

    private fun dayRank(daysRemaining: Int?): Int = when {
        daysRemaining == null -> Int.MAX_VALUE
        daysRemaining < 0 -> Int.MAX_VALUE - 1
        else -> daysRemaining
    }
}
