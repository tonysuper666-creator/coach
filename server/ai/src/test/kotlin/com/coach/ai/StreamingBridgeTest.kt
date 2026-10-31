package com.coach.ai

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 真流式：delta 边到边发、首字用单调钟、取消 = 断开 HTTP（桥据此杀 CLI 进程组）。 */
class StreamingBridgeTest {

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
