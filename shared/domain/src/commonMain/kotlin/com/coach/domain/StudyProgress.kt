package com.coach.domain

import com.coach.domain.grade.nfkc
import kotlinx.serialization.Serializable

/** Explicit whole-utterance grammar. Unrecognized questions always go to the tutor. */
enum class ProgressIntent { COVERAGE, REMAINING, REVIEW_TODAY, CURRENT_KP }

fun progressIntent(text: String): ProgressIntent? {
    val s = nfkc(text).lowercase().filterNot { it.isWhitespace() || it in "，。？！,.?!～~：:" }
        .removePrefix("请问").removePrefix("请告诉我").removeSuffix("谢谢")
    return when {
        s.matches(Regex("(我|我们)?(已经|现在|目前)?(学了多少|学到哪了|学了百分之多少)(了|吗)?")) ||
            s.matches(Regex("(大概能懂了)?学完这些(大概)?学了(期中考试|期中)的?(百分之多少|多少)(了)?")) -> ProgressIntent.COVERAGE
        s.matches(Regex("(我|我们)?(现在|目前)?(还差多少|还剩多少|还有多少没学)(了|吗)?")) -> ProgressIntent.REMAINING
        s.matches(Regex("(我)?今天(该|要|需要)?复习(什么|哪些)(知识点)?")) -> ProgressIntent.REVIEW_TODAY
        s.matches(Regex("(这个点|这个知识点|当前知识点|这一个)(已经|应该)?(过了|通过了)(没|吗|吧)?")) ||
            s.matches(Regex("(检查)?(应该)?通过了(吧|吗)(你看看)?(问了我几次了)?")) -> ProgressIntent.CURRENT_KP
        else -> null
    }
}

/** Both today's page and deterministic chat answers consume this same value. */
@Serializable data class TodayProgress(
    val day: Long, val fromSeq: Long, val examCoverage: ExamCoverage,
    val coverageText: String, val dueKpIds: List<String>, val currentKpId: String?, val currentMastery: Mastery?,
    val kpTitles: Map<String, String> = emptyMap(),
)

fun Projection.today(epochMillis: Long, kpId: String? = position.current): TodayProgress {
    val day = logicalDay(epochMillis, timeZone)
    val points = examCoverage
    val text = if (points.total == 0) "尚未设置期中分值，暂不能计算覆盖分。"
        else "按期中分值：已通过覆盖 ${points.passed} / ${points.total} 分，稳固 ${points.solid} 分"
    return TodayProgress(day, lastSeq, points, text,
        review.filterValues { it.dueDay <= day }.entries.sortedWith(compareBy({ it.value.dueDay }, { it.key })).map { it.key },
        kpId, kpId?.let { mastery[it] },kpTitles)
}

fun TodayProgress.answer(intent: ProgressIntent): String = when (intent) {
    ProgressIntent.COVERAGE -> coverageText
    ProgressIntent.REMAINING -> if (examCoverage.total == 0) coverageText else
        "$coverageText。还差 ${examCoverage.total - examCoverage.passed} 分覆盖，${examCoverage.total - examCoverage.solid} 分达到稳固。"
    ProgressIntent.REVIEW_TODAY -> if (dueKpIds.isEmpty()) "今天没有到期复习。" else "今天到期复习：${dueKpIds.joinToString("、") { kpTitles[it] ?: "未命名知识点" }}。"
    ProgressIntent.CURRENT_KP -> when {
        currentKpId == null -> "当前还没有打开知识点。"
        currentMastery?.solid == true -> "${kpTitles[currentKpId] ?: "当前知识点"}：已通过，已稳固。"
        currentMastery?.passed == true -> "${kpTitles[currentKpId] ?: "当前知识点"}：已通过，尚未稳固。"
        else -> "${kpTitles[currentKpId] ?: "当前知识点"}：尚未通过。"
    }
}
