package com.coach.android

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Recompute a wall-clock time each day; a day need not be 24 hours across DST. */
internal fun nextReminderAt(now: Long, hour: Int, minute: Int, zone: ZoneId): Long {
    val instant = Instant.ofEpochMilli(now)
    val today = instant.atZone(zone).toLocalDate()
    val time = LocalTime.of(hour, minute)
    val candidate = today.atTime(time).atZone(zone).toInstant()
    return (if (candidate > instant) candidate else today.plusDays(1).atTime(time).atZone(zone).toInstant()).toEpochMilli()
}
