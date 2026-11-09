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
            s.matches(Regex("(大概能懂了)?学完这些(大概)?学了(期末考试|期末|期中考试|期中)的?(百分之多少|多少)(了)?")) -> ProgressIntent.COVERAGE
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
    /** The current point was opened, explained or answered before (so it reads 在学, not 未学). */
    val currentOpened: Boolean = false,
)

fun Projection.today(epochMillis: Long, kpId: String? = position.current): TodayProgress {
    val day = logicalDay(epochMillis, timeZone)
    val points = examCoverage
    // Only weights marked FINAL are the final exam's; the imported midterm-scope weights must not pass for them.
    val text = if (points.total == 0 || points.scope != "FINAL") "期末分值：还没有期末的分值数据，暂不能算覆盖分。"
        else "期末分值：会了覆盖 ${points.passed} / ${points.total} 分，稳了 ${points.solid} 分"
    return TodayProgress(day, lastSeq, points, text,
        reviewsOn(day).keys.toList(),
        kpId, kpId?.let { mastery[it] },kpTitles,
        currentOpened = kpId != null && kpId in startedKpIds)
}

fun TodayProgress.answer(intent: ProgressIntent): String = when (intent) {
    ProgressIntent.COVERAGE -> coverageText
    ProgressIntent.REMAINING -> if (examCoverage.total == 0 || examCoverage.scope != "FINAL") coverageText else
        "$coverageText。还差 ${examCoverage.total - examCoverage.passed} 分覆盖，${examCoverage.total - examCoverage.solid} 分到稳了。"
    ProgressIntent.REVIEW_TODAY -> if (dueKpIds.isEmpty()) "今天没有到期复习。" else "今天到期复习：${dueKpIds.joinToString("、") { kpTitles[it]?.let(::cleanTitle) ?: "未命名的一节" }}。"
    ProgressIntent.CURRENT_KP -> {
        val title = kpTitles[currentKpId]?.let(::cleanTitle) ?: "当前这一节"
        if (currentKpId == null) "当前还没有打开任何一节。" else when (kpState(currentMastery, currentOpened)) {
            KpState.NEEDS_REPAIR -> "$title：要补，会了之后又答错，已排进复习。"
            KpState.SOLID -> "$title：稳了。"
            KpState.PASSED -> "$title：会了，还没稳。"
            KpState.IN_PROGRESS -> "$title：在学，还没会。"
            KpState.NEW -> "$title：未学。"
        }
    }
}
