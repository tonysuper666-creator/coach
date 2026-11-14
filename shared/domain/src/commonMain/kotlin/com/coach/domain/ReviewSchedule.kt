package com.coach.domain

import kotlinx.serialization.json.*

/** One course clock for replay, online queues and downloaded review packs. Explicit settings win. */
fun courseTimeZone(events: List<Event>): String {
    val ordered = events.sortedBy { it.seq }
    return ordered.lastOrNull { it.type == "course_configured" && it.schema == 1 }
        ?.payload?.get("timeZone")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: ordered.asReversed().firstNotNullOfOrNull { e ->
            if (e.type != "outline_revised") null else
                (e.payload["course"] as? JsonObject)?.get("tz")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } ?: "Europe/London"
}

/**
 * The final-exam date every screen and the review scheduler read. Two sources, the later ledger write wins:
 * a manual `exam_set`, or the course content's `course.examDate` ("YYYY-MM-DD") carried by `outline_revised`.
 * A null manual date clears the date set before it.
 */
fun courseExamDay(events: List<Event>, zone: String): Long? {
    val manual = events.filter { it.type == "exam_set" }.maxByOrNull { it.seq }
    val configured = events.filter { it.type == "outline_revised" }.mapNotNull { e ->
        parseIsoDay((e.payload["course"] as? JsonObject)?.get("examDate")?.jsonPrimitive?.contentOrNull)?.let { e.seq to it }
    }.maxByOrNull { it.first }
    if (configured != null && (manual == null || configured.first > manual.seq)) return configured.second
    return manual?.payload?.let { p ->
        p["examDay"]?.jsonPrimitive?.longOrNull
            ?: p["examAt"]?.jsonPrimitive?.longOrNull?.let { logicalDay(it, zone) }
    }
}

/** "2026-12-15" → epoch day; anything else (blank, wrong shape, impossible date) is no date. */
fun parseIsoDay(text: String?): Long? {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(text?.trim().orEmpty()) ?: return null
    val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
    val leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
    val max = when (mo) { 1, 3, 5, 7, 8, 10, 12 -> 31; 4, 6, 9, 11 -> 30; 2 -> if (leap) 29 else 28; else -> return null }
    if (d !in 1..max) return null
    // Hinnant days_from_civil.
    val yy = if (mo <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}

/**
 * Checked priority and exam compression are overlays, never destructive changes to the ease schedule.
 * baseDueDay/baseIntervalDays retain the reference schedule for replay, priority correction and
 * stable legacy due identity. Reapplying either overlay is idempotent, not another interval cut.
 * A look-again or snooze changes the due date independently of the interval's origin.
 * During days 7..0, cap the interval at max(1, floor((daysToExam - 1) / 2)) and
 * bring the due date forward from the normal interval's anchor, no earlier than entry into
 * the window unless the normal schedule was already due before then.
 * Outside that window (including after exam), restore the priority-adjusted schedule.
 */
fun Review.onDay(day: Long, examDay: Long?, targetRetention: Double = this.targetRetention): Review {
    val normalDue = baseDueDay ?: dueDay
    val normalInterval = baseIntervalDays ?: intervalDays
    val anchor = intervalAnchorDay ?: (normalDue - normalInterval)
    val priorityInterval = ReviewRetentionPolicy.interval(normalInterval, targetRetention)
    val priorityDue = minOf(normalDue, anchor + priorityInterval)
    val days = examDay?.minus(day)
    if (days == null || days !in 0L..7L) return if (targetRetention == ReviewRetentionPolicy.BASELINE) copy(
        dueDay = normalDue, intervalDays = normalInterval,
        baseDueDay = null, baseIntervalDays = null, targetRetention = targetRetention)
    else copy(dueDay = maxOf(snoozedUntilDay ?: Long.MIN_VALUE, priorityDue), intervalDays = priorityInterval,
        baseDueDay = normalDue, baseIntervalDays = normalInterval, targetRetention = targetRetention)
    val interval = priorityInterval.coerceAtMost(((days - 1) / 2).toInt().coerceAtLeast(1))
    return copy(dueDay = maxOf(snoozedUntilDay ?: Long.MIN_VALUE,
        minOf(priorityDue, maxOf(examDay - 7, minOf(anchor + interval, examDay - 1)))),
        intervalDays = interval, baseDueDay = normalDue, baseIntervalDays = normalInterval, targetRetention = targetRetention)
}

/** The actionable queue for a course day, shared by chat, UI and offline selection; a point to look at again joins it on its due day. */
fun Projection.reviewsOn(day: Long): Map<String, Review> = (revisit.filterKeys { it !in review }.mapValues { (kp, due) ->
    Review(due, lastResult = REVISIT, targetRetention = ReviewRetentionPolicy.target(reviewStars[kp]))
} + review)
    .filterKeys { it in (activeKpIds ?: kpTitles.keys) }
    .mapValues { it.value.onDay(day, examDay) }
    .filterValues { it.dueDay <= day && it.lastReviewDay != day }
    .entries.sortedWith(compareBy({ it.value.dueDay }, { it.key }))
    .associate { it.key to it.value }

/** [Review.lastResult] of a queue entry that comes from a missed or guessed answer rather than the spaced schedule. */
const val REVISIT = "REVISIT"
