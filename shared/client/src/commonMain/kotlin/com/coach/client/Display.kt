package com.coach.client

/*
 * One place for the words the learner reads (TON-1444). Raw outline titles keep their 【W3·slides】 markers because the
 * week calendar is derived from them; screens must show titles through these functions, never the raw string.
 */

private val BRACKETED = Regex("【[^】]*】")

/** Display title: first segment of the raw title with every 【…】 marker removed (shared with the chat answers). */
fun titleMain(raw: String): String = com.coach.domain.cleanTitle(raw)

/** Same as [titleMain]; a title that is only markers never renders empty. */
fun displayTitle(raw: String): String = com.coach.domain.cleanTitle(raw)

/** Plain-language source line from the markers: 【W3·slides】【Essay】 → 「第 3 周 · 课件 · 论述常考」. */
fun titleSource(raw: String): String {
    val parts = BRACKETED.findAll(raw).flatMap { it.value.removeSurrounding("【", "】").split('·', '・') }
        .map { it.trim() }.filter { it.isNotEmpty() }
        .map { part ->
            Regex("^[Ww](\\d+)$").matchEntire(part)?.let { "第 ${it.groupValues[1]} 周" }
                ?: Regex("^Ch\\.?(\\d+)(?:[–-](\\d+))?$", RegexOption.IGNORE_CASE).matchEntire(part)?.let { m ->
                    "教材第 ${m.groupValues[1]}${m.groupValues[2].takeIf { it.isNotEmpty() }?.let { e -> "–$e" }.orEmpty()} 章"
                }
                ?: when (part.lowercase()) { "slides", "slide" -> "课件"; "essay" -> "论述常考"; else -> part }
        }
    return parts.distinct().joinToString(" · ")
}

/** Learned = 会了 or 稳了 (稳了 is 会了 that held up), the number weeks and the course count. */
val KpStatus.isLearned: Boolean get() = this == KpStatus.PASSED || this == KpStatus.SOLID

/** 「会了 3 / 8」 — the only count shown for a week or the course. */
fun learnedCount(rows: List<KpRow>): String = "${KpStatus.PASSED.label} ${rows.count { it.status.isLearned }} / ${rows.size}"

/** 「8 节 · 会了 3 · 在学 1 · 要补 1」, zero parts omitted. */
fun statusSummary(rows: List<KpRow>): String = buildList {
    add("${rows.size} 节")
    add("${KpStatus.PASSED.label} ${rows.count { it.status.isLearned }}")
    rows.count { it.status == KpStatus.IN_PROGRESS }.takeIf { it > 0 }?.let { add("${KpStatus.IN_PROGRESS.label} $it") }
    rows.count { it.status == KpStatus.NEEDS_REPAIR }.takeIf { it > 0 }?.let { add("${KpStatus.NEEDS_REPAIR.label} $it") }
}.joinToString(" · ")

/** Week name from the course, not the module number: 「02 工具：基础计算」 → 「工具：基础计算」. */
fun weekName(rows: List<KpRow>): String =
    rows.firstOrNull()?.let { it.module.replace(Regex("^\\s*\\d+[.\\s、:：-]*"), "").trim().ifBlank { displayTitle(it.title) } }.orEmpty()

/** 「第 3 周 · 医院与医生 · 3/8」; an exempt week reads 「第 7 周 · 期中周，无新课」. */
fun CourseView.weekDirectoryLine(week: Int): String {
    if (week in midtermExemptionWeeks) return "第 $week 周 · 期中周，无新课"
    val rows = weekKps(week)
    return listOf("第 $week 周", weekName(rows).takeIf { it.isNotBlank() }, "${rows.count { it.status.isLearned }}/${rows.size}")
        .filterNotNull().joinToString(" · ")
}

/** 「距期末 N 天」; the day count already follows the local calendar (see [CourseView.daysToExam]). */
fun CourseView.examCountdownText(nowMillis: Long): String = when {
    examDay == null -> "未设期末日期"
    daysToExam(nowMillis) == null -> "期末已结束"
    else -> "距期末 ${daysToExam(nowMillis)} 天"
}
