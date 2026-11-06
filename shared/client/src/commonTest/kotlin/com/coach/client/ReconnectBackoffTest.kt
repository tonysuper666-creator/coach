package com.coach.client

import kotlin.random.Random
import kotlin.test.*

class ReconnectBackoffTest {
    @Test fun waitsGrowAndStayCappedWhileConnectionsKeepDroppingQuickly() {
        val backoff = ReconnectBackoff(baseMs = 500, maxMs = 10_000, stableMs = 10_000, random = Random(7))
        // 连上 300ms 就被踢：这不算稳定，不能回到起点。
        val waits = List(12) { backoff.next(upMs = 300) }
        val ceilings = listOf(500L, 1_000, 2_000, 4_000, 8_000) + List(7) { 10_000L }
        waits.zip(ceilings).forEach { (wait, ceiling) -> assertTrue(wait in ceiling / 2..ceiling, "$waits") }
        assertTrue(waits.sum() >= 40_000, "两分钟内最多十几次重连，而不是 235 次：$waits")
    }

    @Test fun onlyAStableConnectionResetsTheWait() {
        val backoff = ReconnectBackoff(baseMs = 500, maxMs = 10_000, stableMs = 10_000, random = Random(1))
        repeat(5) { backoff.next(upMs = 0) }
        assertTrue(backoff.next(upMs = 9_999) >= 5_000)
        assertTrue(backoff.next(upMs = 10_000) <= 500)
    }

    @Test fun jitterSpreadsDevicesApart() {
        val waits = (1..20).map { seed -> ReconnectBackoff(random = Random(seed)).apply { repeat(3) { next(0) } }.next(0) }
        assertTrue(waits.toSet().size > 10, "$waits")
    }
}
