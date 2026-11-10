package com.coach.server

import com.coach.ai.*
import com.coach.domain.*
import com.coach.protocol.ReaderTimeoutPolicy
import kotlinx.coroutines.*
import kotlin.test.*

class ReaderTimingTest {
    @Test fun countsOnlyDecodedExplanationAcrossArbitraryFragments() {
        val p = ReaderBodyProgress()
        for (s in listOf("{", "\"paragraphs\":[{\"anchors\":[\"S1\"],", "\"te", "xt\":\"", " ", "\\", "u", "5", "4", "0")) assertFalse(p.push(s), s)
        assertTrue(p.push("8")) // decoded 合, only after the complete escape
        assertFalse(p.push("\\n "))
        assertTrue(p.push("成"))
        assertFalse(p.push("\"}]}"))
        val supplementary = ReaderBodyProgress()
        assertFalse(supplementary.push("{\"paragraphs\":[{\"text\":\"\\uD83D"))
        assertTrue(supplementary.push("\\uDE00"))
        assertFalse(supplementary.push("\\u200B")) // invisible format characters are not body progress
        val nested = ReaderBodyProgress()
        assertFalse(nested.push("{\"text\":\"decoy\",\"paragraphs\":[{\"anchors\":[{\"text\":\"decoy\"}]}]}"))
        val invalid = ReaderBodyProgress()
        assertFalse(invalid.push("not JSON"))
        assertFalse(invalid.push("{\"paragraphs\":[{\"text\":\"decoy\"}]}"))
    }
    @Test fun validatesAllThreeIndependentSettings() {
        assertEquals(180_000, ReaderTimeouts.environment(emptyMap()).totalMs)
        assertTrue(ReaderTimeoutPolicy.CLIENT_REQUEST_MS > ReaderTimeouts.environment(emptyMap()).totalMs)
        assertEquals(ReaderTimeouts(1, 2, 3), ReaderTimeouts.environment(mapOf(
            "COACH_READER_FIRST_CONTENT_TIMEOUT_MS" to "1", "COACH_READER_CONTENT_GAP_TIMEOUT_MS" to "2", "COACH_READER_TOTAL_TIMEOUT_MS" to "3")))
        for (value in listOf("0", "-1", "garbage", "86400001", "9223372036854775807"))
            assertFailsWith<IllegalArgumentException> { ReaderTimeouts.environment(mapOf("COACH_READER_TOTAL_TIMEOUT_MS" to value)) }
    }
    @Test fun preparationAlsoUsesTheFirstContentDeadline() = runBlocking {
        StubTutor().use { stub ->
            val timing = ReaderTiming(ReaderTimeouts(100, 100, 1000))
            val e = assertFailsWith<ReaderTimeout> {
                timing.collect(BridgeClient(BridgeConfig(stub.baseUrl))) {
                    delay(10_000)
                    error("Preparation must be cancelled before the bridge starts")
                }
            }
            assertEquals("first", e.stage)
            assertEquals(0, stub.requests)
        }
    }
    @Test fun invalidJsonStopsTheBridgeBeforeTheContentDeadline() = runBlocking {
        for (prefix in listOf("```json\n", "Here is the answer:", "{\"paragraphs\":[{\"text\":\"合\\q")) {
            StubTutor().use { stub ->
                stub.stream(30, prefix, *Array(100) { " " }, StubTutor.terminal("complete"), StubTutor.DONE)
                val timing = ReaderTiming(ReaderTimeouts(2000, 2000, 5000))
                assertFailsWith<ReaderFormat> {
                    timing.collect(BridgeClient(BridgeConfig(stub.baseUrl)), ChatRequest(task="READER_SELECTION", model=BridgeClient.CLAUDE,
                        stream=true, effort="medium", system="Synthetic", user="Synthetic"))
                }
                assertTrue((System.nanoTime()-timing.started)/1_000_000 < 1800)
                withTimeout(3000) { while (!stub.aborted) delay(10) }
            }
        }
    }
    @Test fun structureCannotRenewFirstOrGapAndTotalStillCapsActiveBody() = runBlocking {
        suspend fun timeout(stage: String, limits: ReaderTimeouts, chunks: Array<String>) {
            StubTutor().use { stub ->
                stub.stream(30, *chunks, StubTutor.terminal("complete"), StubTutor.DONE)
                val timing = ReaderTiming(limits)
                val e = assertFailsWith<ReaderTimeout> {
                    timing.collect(BridgeClient(BridgeConfig(stub.baseUrl)), ChatRequest(task="READER_SELECTION", model=BridgeClient.CLAUDE,
                        stream=true, effort="medium", system="Synthetic", user="Synthetic"))
                }
                assertEquals(stage, e.stage)
                assertTrue((System.nanoTime()-timing.started)/1_000_000 < 3000)
                withTimeout(3000) { while (!stub.aborted) delay(10) }
            }
        }
        timeout("first", ReaderTimeouts(180, 500, 2000), arrayOf("{\"paragraphs\":[{\"anchors\":[\"S1") + Array(30) { " " })
        timeout("gap", ReaderTimeouts(1000, 180, 2000), arrayOf("{\"paragraphs\":[{\"text\":\"合\",\"anchors\":[\"S1") + Array(30) { " " })
        timeout("total", ReaderTimeouts(1000, 500, 250), arrayOf("{\"paragraphs\":[{\"text\":\"合") + Array(30) { "成" })
    }
}
