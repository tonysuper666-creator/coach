package com.coach.client

import com.coach.domain.weekMistakes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** 一周的期末押题权重（服务端私有配置给出，仓库里只有合成样例）。 */
data class WeekFocus(val week: Int, val weight: Double, val topic: String = "")

data class ExamFocus(val weeks: List<WeekFocus>) {
    fun weight(week: Int): Double? = weeks.firstOrNull { it.week == week }?.weight
    fun topic(week: Int): String = weeks.firstOrNull { it.week == week }?.topic.orEmpty()

    companion object {
        /** 坏条目直接丢掉：周号必须是正整数，权重不能为负。 */
        fun parse(o: JsonObject?): ExamFocus? {
            val rows = (o?.get("weeks") as? JsonArray).orEmpty().mapNotNull { raw ->
                val w = raw as? JsonObject ?: return@mapNotNull null
                val week = (w["week"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
                val weight = (w["weight"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it >= 0 } ?: return@mapNotNull null
                WeekFocus(week, weight, (w["topic"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(40))
            }.distinctBy { it.week }
            return rows.takeIf { it.isNotEmpty() }?.let(::ExamFocus)
        }
    }
}

/** 点一块要做什么：开讲某个知识点，或者这一周都过了就开练这一周。 */
sealed interface ExamAction {
    data class Teach(val kpId: String, val title: String, val repair: Boolean) : ExamAction
    /** 这一周都过了：有待练错题就练错题（[mistakes]），否则打开第一个还没稳固的点开练（[kpId]）。 */
    data class Practice(val week: Int, val kpId: String, val mistakes: Int) : ExamAction
}

data class WeekMastery(
    val week: Int, val topic: String, val total: Int, val passed: Int, val solid: Int, val needsRepair: Int,
    /** 0..1：已通过算一半，稳固算满。 */
    val score: Double, val weight: Double, val priority: Double, val action: ExamAction?,
    /** 大纲明确记了这周没有新课（期中周豁免）：不是缺课，也不算掌握。 */
    val exempt: Boolean = false,
)

sealed interface ExamCountdown {
    data object Unset : ExamCountdown
    data class Days(val days: Int, val examDay: Long) : ExamCountdown
    data class Past(val examDay: Long) : ExamCountdown
}

data class ExamPanel(
    val countdown: ExamCountdown, val weeks: List<WeekMastery>, val focus: List<WeekMastery>, val weighted: Boolean,
    /** 还没掌握、但押题权重为 0 的周：不进前三块，要如实说出来，不能算「稳固」。 */
    val offFocus: List<Int> = emptyList(),
    /** 押题有权重、大纲里却还没有知识点的周：没法开讲，也不能算「稳固」。 */
    val unplanned: List<Int> = emptyList(),
) {
    /** 前三块为空、并且确实是因为各周都稳固了。 */
    val allSolid: Boolean get() = focus.isEmpty() && offFocus.isEmpty() && unplanned.isEmpty()
}

/**
 * 期末面板：距期末天数、各周掌握度（直接取账本回放出的知识点状态）、按「押题权重 × 未掌握程度」排出的前三块。
 * 没有押题配置时每周权重都按 1 算，[ExamPanel.weighted] 为 false。
 */
fun CourseView.examPanel(nowMillis: Long, focus: ExamFocus?): ExamPanel {
    val countdown = examDay?.let { day ->
        val left = (day - com.coach.domain.calendarDay(nowMillis, zone)).toInt()
        if (left >= 0) ExamCountdown.Days(left, day) else ExamCountdown.Past(day)
    } ?: ExamCountdown.Unset
    // 押题表里有、大纲还没排到的周也列出来（total = 0），如实显示「还没有知识点」，不冒充掌握。
    val rows = (weeks + focus?.weeks.orEmpty().map { it.week }).distinct().sorted().map { week ->
        val kps = weekKps(week)
        val passed = kps.count { it.status == KpStatus.PASSED || it.status == KpStatus.SOLID }
        val solid = kps.count { it.status == KpStatus.SOLID }
        val score = if (kps.isEmpty()) 0.0 else (passed + solid) / (2.0 * kps.size)
        val weight = if (focus == null) 1.0 else focus.weight(week) ?: 0.0
        val next = kps.firstOrNull { it.status == KpStatus.NEEDS_REPAIR }
            ?: kps.firstOrNull { it.status != KpStatus.PASSED && it.status != KpStatus.SOLID }
        val action = when {
            next != null -> ExamAction.Teach(next.id, next.title, next.status == KpStatus.NEEDS_REPAIR)
            kps.isNotEmpty() -> ExamAction.Practice(week, (kps.firstOrNull { it.status != KpStatus.SOLID } ?: kps.first()).id, weekMistakes(events, week).size)
            else -> null
        }
        WeekMastery(week, focus?.topic(week).orEmpty(), kps.size, passed, solid, kps.count { it.status == KpStatus.NEEDS_REPAIR },
            score, weight, weight * (1 - score), action, exempt = kps.isEmpty() && week in midtermExemptionWeeks)
    }
    val top = rows.filter { it.priority > 0 && it.action != null }
        .sortedWith(compareByDescending<WeekMastery> { it.priority }.thenByDescending { it.weight }.thenBy { it.week })
        .take(3)
    val offFocus = rows.filter { it.total > 0 && it.score < 1 && it.weight == 0.0 }.map { it.week }
    val unplanned = rows.filter { it.total == 0 && it.weight > 0 && !it.exempt }.map { it.week }
    return ExamPanel(countdown, rows, top, focus != null, offFocus, unplanned)
}

/** [1, 2, 3, 5] → "第 1–3 周、第 5 周"：连着的周合成一段。 */
fun weekSpans(weeks: List<Int>): String {
    val runs = mutableListOf<IntRange>()
    for (w in weeks.distinct().sorted()) {
        val last = runs.lastOrNull()
        if (last != null && w == last.last + 1) runs[runs.lastIndex] = last.first..w else runs += w..w
    }
    return runs.joinToString("、") { if (it.first == it.last) "第 ${it.first} 周" else "第 ${it.first}–${it.last} 周" }
}

/** 纪元日 → 公历年月日（不依赖日期库；Hinnant 的 civil_from_days）。 */
fun civilDate(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return Triple((yoe + era * 400 + if (m <= 2) 1 else 0).toInt(), m, d)
}

fun examDateLabel(epochDay: Long): String = civilDate(epochDay).let { (y, m, d) -> "${y}年${m}月${d}日" }
