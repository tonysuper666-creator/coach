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

/** The same exam_set fact consumed by the exam panel. A null date clears a previous date. */
fun courseExamDay(events: List<Event>, zone: String): Long? =
    events.filter { it.type == "exam_set" }.maxByOrNull { it.seq }?.payload?.let { p ->
        p["examDay"]?.jsonPrimitive?.longOrNull
            ?: p["examAt"]?.jsonPrimitive?.longOrNull?.let { logicalDay(it, zone) }
    }

/**
 * Exam compression is a date-dependent overlay, never a destructive change to the normal schedule.
 * During days 7..0, cap the interval at max(1, floor((daysToExam - 1) / 2)) and
 * bring the due date forward from the normal interval's anchor, no earlier than entry into
 * the window unless the normal schedule was already due before then.
 * Outside that window (including after exam), restore the normal schedule. Reapplying is idempotent.
 */
fun Review.onDay(day: Long, examDay: Long?): Review {
    val normalDue = baseDueDay ?: dueDay
    val normalInterval = baseIntervalDays ?: intervalDays
    val days = examDay?.minus(day)
    if (days == null || days !in 0L..7L) return copy(
        dueDay = normalDue, intervalDays = normalInterval, baseDueDay = null, baseIntervalDays = null)
    val interval = normalInterval.coerceAtMost(((days - 1) / 2).toInt().coerceAtLeast(1))
    return copy(dueDay = maxOf(snoozedUntilDay ?: Long.MIN_VALUE,
        minOf(normalDue, maxOf(examDay - 7, minOf(normalDue - normalInterval + interval, examDay - 1)))),
        intervalDays = interval, baseDueDay = normalDue, baseIntervalDays = normalInterval)
}

/** The actionable queue for a course day, shared by chat, UI and offline selection. */
fun Projection.reviewsOn(day: Long): Map<String, Review> = review
    .filterKeys { it in (activeKpIds ?: kpTitles.keys) }
    .mapValues { it.value.onDay(day, examDay) }
    .filterValues { it.dueDay <= day && it.lastReviewDay != day }
    .entries.sortedWith(compareBy({ it.value.dueDay }, { it.key }))
    .associate { it.key to it.value }
