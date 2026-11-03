package com.coach.server

import com.coach.domain.LedgerJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FirstWordTraceTest {
    @Test fun sameClockDistinguishesPreparationBridgeAndFiltering() {
        var clock = 0L
        val trace = FirstWordTrace("synthetic", null, clock) { clock }
        clock = 100_000_000; trace.prepared()
        clock = 120_000_000; trace.bridge()
        clock = 320_000_000; trace.raw("private raw")
        clock = 820_000_000; trace.visible("private answer")
        clock = 900_000_000
        val line = trace.finish("complete", 7, 42)
        val data = LedgerJson.parseToJsonElement(line.substringAfter("COACH first_word ")).jsonObject
        fun n(key: String) = data.getValue(key).jsonPrimitive.content.toLong()
        assertEquals(100, n("prepared_ms"))
        assertEquals(20, n("bridge_start_ms") - n("prepared_ms"))
        assertEquals(200, n("raw_first_ms") - n("bridge_start_ms"))
        assertEquals(500, n("visible_first_ms") - n("raw_first_ms"))
        assertEquals(7, n("bridge_queue_ms"))
        assertEquals(42, n("bridge_ttft_ms"))
        assertFalse(line.contains("private"))
    }
}
