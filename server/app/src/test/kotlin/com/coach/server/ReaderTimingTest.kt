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

        val guide = ReaderBodyProgress(
            maxSize = 16_000,
            progressPaths = ReaderBodyProgress.GUIDE_PROGRESS_PATHS,
            progressArrayPaths = ReaderBodyProgress.GUIDE_PROGRESS_ARRAY_PATHS,
        )
        assertTrue(guide.push("""{"brief":["要"""))
        assertTrue(guide.push("""点"],"summary":["小"""))
        assertTrue(guide.push("""结"],"hardPoints":[{"blockId":"S1","explanation":"难"""))
        assertFalse(guide.invalid)

        val struct = ReaderBodyProgress(
            maxSize = 16_000,
            progressPaths = ReaderBodyProgress.STRUCTURE_PROGRESS_PATHS,
            allowNumbers = true,
        )
        assertTrue(struct.push("""{"sections":[{"startBlockId":"S1","title":"主题一","level":1}]}"""))
        assertFalse(struct.invalid)
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
        val cases = listOf(
            "```python\n" to StreamJsonError.FENCE_BEFORE_ROOT,
            "Here is the answer:" to StreamJsonError.PROSE_ASCII_BEFORE_ROOT,
            "{\"paragraphs\":[{\"text\":\"合\\q" to StreamJsonError.BAD_ESCAPE,
        )
        for ((prefix, expectedError) in cases) {
            StubTutor().use { stub ->
                stub.stream(30, prefix, *Array(100) { " " }, StubTutor.terminal("complete"), StubTutor.DONE)
                val timing = ReaderTiming(ReaderTimeouts(2000, 2000, 5000))
                val e = assertFailsWith<ReaderFormat> {
                    timing.collect(BridgeClient(BridgeConfig(stub.baseUrl)), ChatRequest(task="READER_SELECTION", model=BridgeClient.CLAUDE,
                        stream=true, effort="medium", system="Synthetic", user="Synthetic"))
                }
                assertEquals(ReaderFormatReason.STREAM_JSON, e.reason)
                assertEquals(expectedError, e.streamError)
                assertTrue((System.nanoTime()-timing.started)/1_000_000 < 1800)
                withTimeout(3000) { while (!stub.aborted) delay(10) }
            }
        }
    }

    @Test fun acceptsCleanMarkdownJsonFenceAcrossArbitraryFragments() {
        for (sample in listOf(
            "```json\n{\"paragraphs\":[{\"text\":\"有依据\",\"anchors\":[\"S1\"]}]}\n```",
            "```JSON\r\n{\"paragraphs\":[{\"text\":\"有依据\",\"anchors\":[\"S1\"]}]}\r\n```\n",
            "```\n{\"paragraphs\":[{\"text\":\"有依据\",\"anchors\":[\"S1\"]}]}\n```",
        )) {
            val whole = ReaderBodyProgress()
            assertTrue(whole.push(sample), sample)
            assertFalse(whole.invalid, sample)

            val fragmented = ReaderBodyProgress()
            var progressCount = 0
            for (ch in sample) {
                if (fragmented.push(ch.toString())) progressCount++
                assertFalse(fragmented.invalid, "Unexpected invalid at '$ch' in $sample: ${fragmented.invalidReason}")
            }
            assertEquals(3, progressCount)
        }
    }

    @Test fun streamJsonErrorClassifiesFailureKindsWithoutLeakingText() {
        fun reasonFor(input: String, maxSize: Int = 12_000, allowNumbers: Boolean = false): StreamJsonError? {
            val p = ReaderBodyProgress(maxSize = maxSize, allowNumbers = allowNumbers)
            p.push(input)
            assertTrue(p.invalid, "Expected invalid for: $input")
            return p.invalidReason
        }
        assertEquals(StreamJsonError.FENCE_BEFORE_ROOT, reasonFor("```python\n"))
        assertEquals(StreamJsonError.FENCE_BEFORE_ROOT, reasonFor("``not-fence"))
        assertEquals(StreamJsonError.FENCE_BEFORE_ROOT, reasonFor("```json extra\n"))
        assertEquals(StreamJsonError.FENCE_BEFORE_ROOT, reasonFor("```json\n```"))
        assertEquals(StreamJsonError.PROSE_ASCII_BEFORE_ROOT, reasonFor("Here is the answer:"))
        assertEquals(StreamJsonError.PROSE_ASCII_BEFORE_ROOT, reasonFor("```json\nHere is the answer:"))
        assertEquals(StreamJsonError.PROSE_CJK_BEFORE_ROOT, reasonFor("好的，以下是解答"))
        assertEquals(StreamJsonError.BEFORE_ROOT, reasonFor("* bullet"))
        assertEquals(StreamJsonError.AFTER_ROOT, reasonFor("""{"paragraphs":[{"text":"合","anchors":["S1"]}]}```"""))
        assertEquals(StreamJsonError.AFTER_ROOT, reasonFor("```json\n{\"paragraphs\":[{\"text\":\"合\",\"anchors\":[\"S1\"]}]}\n```\nWait"))
        assertEquals(StreamJsonError.UNEXPECTED_AFTER_VALUE, reasonFor("""{"paragraphs":[{"text":"这里讲"概念"本身","anchors":["S1"]}]}"""))
        assertEquals(StreamJsonError.BAD_ESCAPE, reasonFor("""{"paragraphs":[{"text":"合\q","anchors":["S1"]}]}"""))
        assertEquals(StreamJsonError.BAD_ESCAPE, reasonFor("""{"paragraphs":[{"text":"10\%","anchors":["S1"]}]}"""))
        assertEquals(StreamJsonError.BAD_UNICODE_ESCAPE, reasonFor("""{"paragraphs":[{"text":"\u123G","anchors":["S1"]}]}"""))
        assertEquals(StreamJsonError.UNEXPECTED_NUMBER, reasonFor("""{"paragraphs":[{"text":"合","anchors":[1]}]}"""))
        assertEquals(StreamJsonError.UNEXPECTED_LITERAL, reasonFor("""{"paragraphs":[{"text":null,"anchors":["S1"]}]}"""))
        assertEquals(StreamJsonError.ROOT_NON_OBJECT, reasonFor("""[{"text":"合"}]"""))
        assertEquals(StreamJsonError.MAX_SIZE, reasonFor("""{"paragraphs":[{"text":"abcdef"}]}""", maxSize = 24))
        assertEquals(StreamJsonError.MAX_DEPTH, reasonFor("""{"a":{"b":{"c":{"d":{"e":{"f":{"g":{"h":{"i":"x"}}}}}}}}}"""))
        assertEquals(StreamJsonError.KEY_TOO_LONG, reasonFor("""{"${"k".repeat(65)}":"v"}"""))
        assertEquals(StreamJsonError.BAD_SURROGATE, reasonFor("""{"paragraphs":[{"text":"\uD83Dx"}]}"""))
        assertEquals(StreamJsonError.BAD_SURROGATE, reasonFor("""{"paragraphs":[{"text":"\uDC00"}]}"""))
        assertEquals(StreamJsonError.SYNTAX, reasonFor("""{"paragraphs":,}"""))
        assertEquals(StreamJsonError.BAD_NUMBER, reasonFor("""{"a":-}""", allowNumbers = true))
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
