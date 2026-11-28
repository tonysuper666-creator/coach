package com.coach.desktop.books

import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

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
    val deferral: String? = null,
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
    private const val UNKNOWN_START = "离考试近了会开始排"

    fun preview(
        today: LocalDate,
        courses: List<CourseDayInput>,
        budgetMinutes: Int,
        days: Int = 5,
    ): List<MultiCourseDay> {
        val preview = (0 until days.coerceIn(1, 14)).map { offset ->
            planDay(today.plusDays(offset.toLong()), courses, budgetMinutes)
        }
        if (preview.none { day -> day.courses.any { it.deferral != null } }) return preview
        // Only look past the preview for courses that still need a start date at its end.
        val waiting = mutableSetOf<String>()
        for (day in preview) {
            for (course in day.courses) {
                if (course.deferral != null) waiting.add(course.courseId)
                if (course.minutes > 0) waiting.remove(course.courseId)
            }
        }
        val starts = mutableMapOf<String, LocalDate>()
        val exams = courses.mapNotNull { c -> c.examDate?.let { c.courseId to it } }.toMap()
        var date = preview.last().date.plusDays(1)
        // Bound work on the home screen even for an exam date entered years in the future.
        val horizon = today.plusYears(1)
        while (date <= horizon && waiting.any { date < exams.getValue(it) }) {
            for ((demand, tasks) in allocate(date, courses, preview.first().budgetMinutes)) {
                if (tasks.minutes > 0 && waiting.remove(demand.input.courseId)) starts[demand.input.courseId] = date
            }
            date = date.plusDays(1)
        }
        // Walk back so each zero-minute row uses the next funded day after that row.
        return preview.asReversed().map { day ->
            val scheduled = day.courses.filter { it.minutes > 0 }
            for (course in scheduled) starts[course.courseId] = day.date
            val recipients = scheduled.joinToString("、") { "${it.title}（${exams.getValue(it.courseId).monthValue} 月考）" }
            val priority = if (scheduled.isEmpty()) "今天的 ${day.budgetMinutes} 分钟排不下一项学习任务" else "今天的时间先给了$recipients"
            val annotated = day.courses.map { course ->
                val note = course.deferral
                if (note == null) course else {
                    val whenPart = starts[course.courseId]?.let { "预计 ${it.monthValue} 月 ${it.dayOfMonth} 日开始" } ?: UNKNOWN_START
                    course.copy(reason = "$priority，$whenPart", deferral = "$note，$whenPart")
                }
            }
            day.copy(courses = annotated)
        }.asReversed()
    }

    fun plan(today: LocalDate, courses: List<CourseDayInput>, budgetMinutes: Int): MultiCourseDay =
        preview(today, courses, budgetMinutes, days = 1).first()

    private fun planDay(today: LocalDate, courses: List<CourseDayInput>, budgetMinutes: Int): MultiCourseDay {
        val cap = budgetMinutes.coerceIn(15, 240)
        val tasks = allocate(today, courses, cap).map { (demand, planned) ->
            val reason = reason(demand, planned.minutes)
            val titles = demand.input.points.associateBy { it.id }
            CourseDayTask(
                courseId = demand.input.courseId,
                title = demand.input.title.ifBlank { demand.input.courseId },
                tasks = planned,
                minutes = planned.minutes,
                reason = reason,
                lines = taskLines(planned, reason, titles),
                readFirstTitles = planned.pointsWithoutQuestions.map { id -> titles[id]?.title?.ifBlank { id } ?: id },
                deferral = if (demand.weight > 0 && planned.minutes == 0) "按每天要过的量分配，今天的 $cap 分钟里排不下" else null,
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
    ) {
        val remaining = input.points.count { !it.mastered }
        val withErrors = input.points.count { it.wrongQuestions > 0 }
        val weight = if (natural.timing == ExamTaskTiming.UPCOMING && natural.minutes > 0) {
            (remaining + withErrors).coerceAtLeast(1).toDouble() / requireNotNull(natural.daysRemaining)
        } else 0.0
    }

    private fun allocate(today: LocalDate, courses: List<CourseDayInput>, cap: Int): List<Pair<Demand, TodayExamTasks>> {
        val demands = courses.map { input ->
            Demand(input, ExamDailyTaskPlanner.plan(today, input.examDate, input.points, 240, input.pins))
        }
        val active = demands.filter { it.weight > 0 }.sortedBy { it.input.courseId }
        val fitted = fit(today, active, cap).associate { (demand, tasks) -> demand.input.courseId to tasks }
        return demands.map { it to (fitted[it.input.courseId] ?: zeroed(it.natural)) }
    }

    private fun reason(demand: Demand, minutes: Int): String {
        val days = demand.natural.daysRemaining ?: return "还没设考试日期"
        if (days < 0) return "考试已过"
        if (days == 0) return "今天考试，备考时间留空"
        if (demand.natural.minutes == 0) return "距考试 $days 天，今天没有要排的任务"
        val remaining = demand.remaining
        val workload = when {
            remaining == 0 -> "新考点已学完"
            remaining < days -> "$remaining 个考点还没掌握，约每 ${(days.toDouble() / remaining).roundToInt()} 天过 1 个"
            else -> "$remaining 个考点还没掌握，每天约需过 ${(remaining.toDouble() / days).roundToInt()} 个"
        }
        val review = if (demand.withErrors > 0) "，${demand.withErrors} 个有错题的考点各多算一份复习量" else ""
        return "距考试 $days 天，$workload$review，今天 $minutes 分钟"
    }

    private fun taskLines(
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
        if (budget >= demand.natural.minutes) return demand.natural.copy(budgetMinutes = budget)
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
