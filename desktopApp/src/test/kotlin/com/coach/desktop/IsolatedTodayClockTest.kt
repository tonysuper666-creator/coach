package com.coach.desktop

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class IsolatedTodayClockTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val base = Clock.fixed(Instant.parse("2026-11-16T16:30:00Z"), zone)

    @Test fun offsetMovesTheDateAndTheMillisTogetherOnlyWhenIsolated() {
        val shifted = isolatedTodayClock("http://127.0.0.1:18742", "10", base)
        assertEquals(base.millis() + Duration.ofDays(10).toMillis(), shifted.millis())
        assertEquals(LocalDate.of(2026, 11, 27), LocalDate.now(shifted))
        assertEquals(LocalDate.of(2026, 11, 17), LocalDate.now(base))

        val three = isolatedTodayClock("http://127.0.0.1:18742", " 3 ", base)
        assertEquals(base.millis() + Duration.ofDays(3).toMillis(), three.millis())
        assertEquals(LocalDate.of(2026, 11, 20), LocalDate.now(three))
    }

    @Test fun pairedRunAndBadValuesLeaveTheClockAlone() {
        assertSame(base, isolatedTodayClock(null, "10", base))
        assertSame(base, isolatedTodayClock("  ", "10", base))
        assertSame(base, isolatedTodayClock("http://127.0.0.1:18742", null, base))
        assertSame(base, isolatedTodayClock("http://127.0.0.1:18742", "", base))
        assertSame(base, isolatedTodayClock("http://127.0.0.1:18742", "0", base))
        assertSame(base, isolatedTodayClock("http://127.0.0.1:18742", "-2", base))
        assertSame(base, isolatedTodayClock("http://127.0.0.1:18742", "soon", base))
    }
}
