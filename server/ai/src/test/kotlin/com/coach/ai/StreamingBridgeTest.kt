package com.coach.ai

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 真流式：delta 边到边发、首字用单调钟、取消 = 断开 HTTP（桥据此杀 CLI 进程组）。 */
class StreamingBridgeTest {
    @Test fun resetReplacesPartialTextInBothStreamingReaders() {
        StubBridge().use { stub ->
            val reset = "data: {\"choices\":[],\"x_coach_reset\":true}\n\n"
            repeat(2) { stub.enqueueSse(StubBridge.frame("partial"), reset, StubBridge.frame("replacement"),
                StubBridge.terminal("complete"), StubBridge.DONE) }
            val client = BridgeClient(stubConfig(stub))
            val visible = StringBuilder()
            var resets = 0
            val result = client.streamChat(request(), object : BridgeStreamSink {
                override val supportsReset = true
                override fun onReset() { visible.setLength(0); resets++ }
                override fun onDelta(text: String, atMs: Long) { visible.append(text) }
            })
            assertTrue(result is BridgeResult.Success)
            assertEquals("replacement", result.text)
            assertEquals("replacement", visible.toString())
            assertEquals(1, resets)
            assertEquals("replacement", (client.chat(request()) as BridgeResult.Success).text)
            assertTrue(stub.requestedHeaders.all { h -> h.entries.any { it.key.equals("X-Coach-Stream-Reset", true) && it.value == listOf("v1") } })
        }
    }

    @Test fun haikuGetsTwoFormatAttemptsThenOneExplicitSonnetEscalation() {
        StubBridge().use { stub ->
            repeat(2) { stub.enqueue(200, StubBridge.completion("invalid JSON", "claude-haiku-5-5")) }
            stub.enqueue(200, StubBridge.completion("[]", "claude-sonnet-5-5"))
            val result = BridgeClient(stubConfig(stub)).chat(request().copy(task = "gen_mcq", stream = false))
            assertTrue(result is BridgeResult.Success)
            assertEquals(3, result.call.physicalCalls)
            val attempts = stub.requestedHeaders.map { h -> h.entries.single { it.key.equals("X-Coach-Format-Attempt", true) }.value.single() }
            assertEquals(listOf("0", "1", "2"), attempts)
        }
    }

    @Test fun malformedTerraReviewEscalatesOnceWithoutReversingTheVendor() {
        StubBridge().use { stub ->
            stub.enqueue(200, StubBridge.completion("invalid JSON", "gpt-6.1-terra"))
            stub.enqueue(200, StubBridge.completion("{\"verdict\":\"REJECT\"}", "gpt-6.1-sol"))
            val result = BridgeClient(stubConfig(stub)).chat(request().copy(task = "review_constructed", model = BridgeClient.CODEX, stream = false))
            assertTrue(result is BridgeResult.Success)
            assertEquals("gpt-6.1-sol", result.call.servedBy)
            assertEquals(2, result.call.physicalCalls)
            val attempts = stub.requestedHeaders.map { h -> h.entries.single { it.key.equals("X-Coach-Format-Attempt", true) }.value.single() }
            assertEquals(listOf("0", "2"), attempts)
        }
    }

    private fun request() = ChatRequest(
        task = "TEACH",
        model = BridgeClient.CLAUDE,
        system = "synthetic system",
        user = "synthetic question",
        stream = true,
    )

    @Test
    fun deltasArriveIncrementallyAndFirstTokenIsMeasured() {
        StubBridge().use { stub ->
            stub.enqueueStream(
                60,
                StubBridge.frame("Hello "),
                StubBridge.frame("world"),
                StubBridge.terminal("complete"),
                StubBridge.DONE,
            )
            val deltas = Collections.synchronizedList(ArrayList<Pair<String, Long>>())
            val result = BridgeClient(stubConfig(stub)).streamChat(
                request(),
                object : BridgeStreamSink {
                    override fun onDelta(text: String, atMs: Long) {
                        deltas.add(text to atMs)
                    }
                },
            )
            assertTrue(result is BridgeResult.Success, "expected success, got $result")
            assertEquals(listOf("Hello ", "world"), deltas.map { it.first })
            assertEquals("Hello world", (result as BridgeResult.Success).text)
            // 首字必须落在第一帧到达之后、而不是回包之后
            assertTrue(deltas[0].second >= 40, "ttft too early: ${deltas[0].second}")
            assertTrue(deltas[1].second > deltas[0].second, "second delta not later than the first")
            assertTrue((result.call.ttftMs ?: -1) >= 40, "call.ttft_ms not the client-visible first token")
            assertEquals("complete", result.terminal.status)
        }
    }

    @Test
    fun cancelClosesTheHttpRequestInsteadOfWaitingForTheStreamToEnd() {
        StubBridge().use { stub ->
            stub.enqueueStream(
                400,
                StubBridge.frame("first "),
                StubBridge.frame("second "),
                StubBridge.frame("third "),
                StubBridge.terminal("complete"),
                StubBridge.DONE,
            )
            var handle: BridgeStreamHandle? = null
            val canceller = Thread {
                Thread.sleep(250)
                handle?.cancel()
            }.apply { start() }
            val startedAt = System.nanoTime()
            val result = BridgeClient(stubConfig(stub)).streamChat(
                request(),
                object : BridgeStreamSink {},
            ) { handle = it }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            canceller.join(2_000)
            assertTrue(result is BridgeResult.Failure, "cancel must not look like success")
            assertEquals("cancelled", (result as BridgeResult.Failure).reason)
            assertEquals("cancelled", result.call.status)
            // 三帧 × 400ms 的流被提前掐断，2 秒内必须返回（§5.5 取消 2 秒内收尾）
            assertTrue(elapsedMs < 2_000, "cancel took ${elapsedMs}ms")
            assertTrue(stub.framesWritten < 5, "the stream kept running after cancel: ${stub.framesWritten} frames written")
        }
    }

    @Test
    fun missingTerminalFrameIsTruncatedNotComplete() {
        assertEquals("truncated", StreamStatus.fromBridge(null, cancelled = false, hasText = true))
        assertEquals("truncated", StreamStatus.fromBridge("truncated", cancelled = false, hasText = true))
        assertEquals("failed", StreamStatus.fromBridge("failed", cancelled = false, hasText = false))
        assertEquals("truncated", StreamStatus.fromBridge("failed", cancelled = false, hasText = true))
        assertEquals("complete", StreamStatus.fromBridge("complete", cancelled = false, hasText = true))
        assertEquals("failed", StreamStatus.fromBridge("complete", cancelled = false, hasText = false))
        assertEquals("cancelled", StreamStatus.fromBridge("complete", cancelled = true, hasText = true))
    }

    @Test
    fun streamWithoutTerminalReportsStreamReadFailureAsTruncatedText() {
        StubBridge().use { stub ->
            // 有正文、没终态帧 → 按截断处理（ADR-0002）
            stub.enqueue(200, StubBridge.frame("half an answer"))
            val texts = Collections.synchronizedList(ArrayList<String>())
            val result = BridgeClient(stubConfig(stub)).streamChat(
                request(),
                object : BridgeStreamSink {
                    override fun onDelta(text: String, atMs: Long) {
                        texts.add(text)
                    }
                },
            )
            assertEquals(listOf("half an answer"), texts.toList())
            assertTrue(result is BridgeResult.Failure)
            assertEquals("no_x_coach", (result as BridgeResult.Failure).reason)
        }
    }
}
