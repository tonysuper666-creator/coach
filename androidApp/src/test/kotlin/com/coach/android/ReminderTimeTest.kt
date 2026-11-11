package com.coach.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReminderTimeTest {
    private fun millis(text: String) = Instant.parse(text).toEpochMilli()
    private fun check(now: String, hour: Int, minute: Int, zone: String, expected: String) =
        assertEquals(millis(expected), nextReminderAt(millis(now), hour, minute, ZoneId.of(zone)))

    @Test fun beforeTimeUsesToday() = check("2026-11-06T10:00:00Z", 19, 0, "Europe/London", "2026-11-06T19:00:00Z")
    @Test fun exactTimeUsesTomorrow() = check("2026-11-06T19:00:00Z", 19, 0, "Europe/London", "2026-11-07T19:00:00Z")
    @Test fun midnightCrossesYear() = check("2026-12-31T23:59:59Z", 0, 0, "UTC", "2027-01-01T00:00:00Z")
    @Test fun timezoneChangeRecomputesWallTime() {
        check("2026-11-06T10:00:00Z", 19, 0, "Asia/Shanghai", "2026-11-06T11:00:00Z")
        check("2026-11-06T12:00:00Z", 19, 0, "Asia/Shanghai", "2026-11-07T11:00:00Z")
    }
    @Test fun springDayHas23Hours() = check("2026-03-28T19:01:00Z", 19, 0, "Europe/London", "2026-03-29T18:00:00Z")
    @Test fun fallDayHas25Hours() = check("2026-10-24T18:01:00Z", 19, 0, "Europe/London", "2026-10-25T19:00:00Z")
    @Test fun nonexistentTimeShiftsForward() = check("2026-03-29T00:00:00Z", 1, 30, "Europe/London", "2026-03-29T01:30:00Z")
    @Test fun repeatedHourDoesNotNotifyTwice() = check("2026-10-25T00:45:00Z", 1, 30, "Europe/London", "2026-10-26T01:30:00Z")
}
