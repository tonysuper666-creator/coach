package com.coach.desktop

import java.time.Clock
import java.time.Duration

/** System property read only when [COACH_URL] is set. Formal paired runs ignore it. */
internal const val TODAY_OFFSET_PROPERTY = "coach.todayOffsetDays"

/**
 * Moves the library clock forward by whole days for an isolated desktop run.
 * LocalDate.now of the returned clock and clock.millis move together.
 * A blank URL, a missing property, zero, or a negative value leaves the base clock unchanged.
 */
internal fun isolatedTodayClock(
    coachUrl: String?,
    offsetDays: String?,
    base: Clock = Clock.systemDefaultZone(),
): Clock {
    if (coachUrl.isNullOrBlank()) return base
    val days = offsetDays?.trim()?.toLongOrNull() ?: return base
    if (days <= 0L) return base
    return Clock.offset(base, Duration.ofDays(days))
}
