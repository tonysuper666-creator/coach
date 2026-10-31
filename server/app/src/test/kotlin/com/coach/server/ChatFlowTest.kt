package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.client.ChatStreamAssembler
import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun JsonObject.str(key: String): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

/**
 * Ktor ↔ 桥（桩）↔ WS 三段的端到端：首字、续传、取消、中途断流、终态对应、可见流过滤。
 *
 * 全程桩桥，不调用任何真实订阅 CLI（DESIGN §13.2 期中保护）。
 */
class ChatFlowTest {

    @Test
    fun cancelBeforeTheStreamHandleExistsIsAppliedWhenItArrives() {
        Harness().use { h ->
            h.tutor.stream(600, "迟到的正文", "不应读完整条流", StubTutor.terminal("complete"), StubTutor.DONE)
            val session = ChatService.Session("cancel-before-handle", "thread", "course", BridgeClient.CLAUDE, false)
            session.abort("cancelled", "cancelled")
            val started = System.nanoTime()
            val result = BridgeClient(BridgeConfig(h.tutor.baseUrl)).streamChat(
                com.coach.ai.teachRequest("合成问题", ""), object : com.coach.ai.BridgeStreamSink {},
            ) { session.attach(it) }
            assertEquals("cancelled", result.call.status)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        }
    }

    @Test
    fun everyBridgeTerminalStatusReachesTheClientUnchanged() {
        for (status in listOf("complete", "truncated", "failed", "cancelled")) Harness().use { h ->
            val frames = if (status == "failed") emptyArray() else arrayOf("导师回答。")
            h.tutor.stream(10, *frames, StubTutor.terminal(status), StubTutor.DONE)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val end = ws.await("msg.end")
                assertEquals(status, end.str("status"))
            }
        }
    }

    private class Harness(timings: ChatTimings = defaultTimings()) : AutoCloseable {
        val tutor = StubTutor()
        val token = "stream-test-token-32-characters-minimum"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(Files.createTempDirectory("coach-chat-")).also { it.enroll("desktop", token) }
        val actor = LedgerActor(scope, store)
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
        val chat = ChatService(
            actor = actor,
            scope = scope,
            bridge = BridgeClient(BridgeConfig(tutor.baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(30))),
            timings = timings,
            fallbackOnFirstTokenTimeout = false,
            log = { logs.add(it) },
        )
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), chat)
        }.apply { start(wait = false) }

        val port: Int get() = runBlocking { engine.engine.resolvedConnectors().first().port }

        suspend fun snapshot(messageId: String) = actor.execute { messageSnapshot(messageId) }

        override fun close() {
            runBlocking { engine.stopSuspend(0, 500); actor.close() }
            scope.cancel()
            tutor.close()
        }
    }

    private class Ws(port: Int, token: String) : AutoCloseable {
        private val queue = LinkedBlockingQueue<JsonObject>()
        private val pending = ArrayList<JsonObject>()
        private val socket: WebSocket

        init {
            val listener = object : WebSocket.Listener {
                private val text = StringBuilder()
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    text.append(data)
                    if (last) {
                        runCatching { com.coach.domain.LedgerJson.parseToJsonElement(text.toString()).jsonObject }
                            .getOrNull()?.let { queue.add(it) }
                        text.setLength(0)
                    }
                    webSocket.request(1)
                    return null
                }
            }
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer $token")
                .header("X-Coach-Device", "desktop")
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:$port/v1/ws"), listener)
                .get(5, TimeUnit.SECONDS)
        }

        fun send(text: String) {
            socket.sendText(text, true).get(5, TimeUnit.SECONDS)
        }

        fun await(vararg types: String, timeoutMs: Long = 5_000): JsonObject {
            val wanted = types.toSet()
            pending.firstOrNull { it.str("t") in wanted }?.let { matched ->
                pending.remove(matched)
                return matched
            }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                val frame = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (frame.str("t") in wanted) return frame
                pending.add(frame)
            }
            throw AssertionError("timeout waiting for $wanted; saw ${pending.map { it.str("t") }}")
        }

        override fun close() {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS) }
            runCatching { socket.abort() }
        }
    }

    private fun ask(ws: Ws, threadId: String = "thread-1") = ws.send(
        """{"t":"msg.ask","threadId":"$threadId","courseId":"course-1","text":"什么是机会成本？"}""",
    )

    @Test
    fun streamedAnswerReachesTheClientAndLandsComplete() {
        Harness().use { h ->
            h.tutor.stream(40, "你好，", "这是导师的", "回答。", StubTutor.terminal("complete"), StubTutor.DONE)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val start = ws.await("msg.start")
                val messageId = start.str("msg")
                val text = StringBuilder()
                var end: JsonObject? = null
                while (end == null) {
                    val frame = ws.await("msg.delta", "msg.end")
                    if (frame.str("t") == "msg.end") end = frame else text.append(frame.str("text"))
                }
                assertEquals("你好，这是导师的回答。", text.toString())
                assertEquals("complete", end!!.str("status"))
                val stored = runBlocking { h.snapshot(messageId) }
                assertNotNull(stored)
                assertEquals("complete", stored.status)
                assertEquals("你好，这是导师的回答。", stored.content)
            }
        }
    }

    @Test
    fun reconnectResumesFromSnapshotAndTheAssembledTextMatchesTheServerCopy() {
        Harness().use { h ->
            h.tutor.stream(
                150, "第一段。", "第二段。", "第三段。", "第四段。", "第五段。",
                StubTutor.terminal("complete"), StubTutor.DONE,
            )
            var messageId: String
            Ws(h.port, h.token).use { first ->
                ask(first)
                val start = first.await("msg.start")
                messageId = start.str("msg")
                first.await("msg.delta")
            }
            // 断网（socket 直接关闭），服务端流继续；再接回来必须从 snapshot 续上
            Thread.sleep(200)
            val assembler = ChatStreamAssembler()
            Ws(h.port, h.token).use { second ->
                second.send("""{"t":"msg.resume","msg":"$messageId"}""")
                val snapshot = second.await("msg.snapshot")
                assertEquals(messageId, snapshot.str("msg"))
                assembler.handle(snapshot)
                var end: JsonObject? = null
                while (end == null) {
                    val frame = second.await("msg.delta", "msg.end", "msg.math")
                    assembler.handle(frame)
                    if (frame.str("t") == "msg.end") end = frame
                }
                val server = runBlocking { h.snapshot(messageId) }
                assertNotNull(server)
                val clientText = assembler.text(messageId)
                assertEquals(server.content, clientText)
                assertEquals(sha256(server.content), sha256(clientText))
                assertEquals("complete", end!!.str("status"))
            }
        }
    }

    @Test
    fun cancelEndsTheMessageWithinTwoSecondsAndStopsTheBridgeStream() {
        Harness().use { h ->
            h.tutor.stream(
                400, "一", "二", "三", "四", "五", "六",
                StubTutor.terminal("complete"), StubTutor.DONE,
            )
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val messageId = ws.await("msg.start").str("msg")
                ws.await("msg.delta")
                val startedAt = System.nanoTime()
                ws.send("""{"t":"msg.cancel","msg":"$messageId"}""")
                val end = ws.await("msg.end", timeoutMs = 3_000)
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                assertEquals("cancelled", end.str("status"))
                assertTrue(elapsedMs < 2_000, "cancel took ${elapsedMs}ms")
                val stored = runBlocking { h.snapshot(messageId) }
                assertEquals("cancelled", stored?.status)
                // 剩余帧没有再发完（桩桥最多写出取消前的那几帧）
                assertTrue(h.tutor.framesWritten < 8, "bridge kept streaming: ${h.tutor.framesWritten}")
            }
        }
    }

    @Test
    fun internalTextInjectedByTheStubNeverReachesTheVisibleStream() {
        Harness().use { h ->
            h.tutor.stream(
                30,
                "这是正文，应该出现。\n",
                "placeholder: {\"status\":\"complete\"}\n",
                "tool_call: {\"name\":\"read_file\"}\n",
                "{\"x_coach\":{\"status\":\"complete\"}}\n",
                "这是结尾，也应该出现。\n",
                StubTutor.terminal("complete"),
                StubTutor.DONE,
            )
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val messageId = ws.await("msg.start").str("msg")
                val text = StringBuilder()
                var end: JsonObject? = null
                while (end == null) {
                    val frame = ws.await("msg.delta", "msg.end")
                    if (frame.str("t") == "msg.end") end = frame else text.append(frame.str("text"))
                }
                val visible = text.toString()
                assertTrue(visible.contains("这是正文"), visible)
                assertTrue(visible.contains("这是结尾"), visible)
                val leaked = listOf("placeholder", "tool_call", "x_coach").filter { visible.contains(it) }
                assertTrue(leaked.isEmpty(), "protocol text leaked: $leaked\n$visible")
                assertTrue(h.logs.any { it.contains("visible-drop") }, "drops were not logged: ${h.logs}")
                val stored = runBlocking { h.snapshot(messageId) }
                assertEquals(visible, stored?.content)
            }
        }
    }

    @Test
    fun bridgeTerminalStatusMapsOneToOneOntoMessageStatus() {
        Harness().use { h ->
            h.tutor.stream(30, "半句话", StubTutor.terminal("truncated", "timeout"), StubTutor.DONE)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val messageId = ws.await("msg.start").str("msg")
                var end: JsonObject? = null
                while (end == null) {
                    val frame = ws.await("msg.delta", "msg.end")
                    if (frame.str("t") == "msg.end") end = frame
                }
                assertEquals("truncated", end!!.str("status"))
                assertEquals("timeout", end.str("reason"))
                val stored = runBlocking { h.snapshot(messageId) }
                assertEquals("truncated", stored?.status)
                assertEquals("timeout", stored?.reason)
            }
        }
    }

    @Test
    fun firstTokenDeadlineAbortsTheStreamInsteadOfWaitingForever() {
        Harness(defaultTimings(firstTokenDeadlineMs = 400)).use { h ->
            // 每帧 700ms：首字截止 400ms 内拿不到正文，必须主动断开并写 failed(first_token_timeout)
            h.tutor.stream(700, "迟到的一句话", StubTutor.terminal("complete"), StubTutor.DONE)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val messageId = ws.await("msg.start").str("msg")
                val end = ws.await("msg.end", timeoutMs = 5_000)
                assertEquals("failed", end.str("status"))
                assertEquals("first_token_timeout", end.str("reason"))
                val stored = runBlocking { h.snapshot(messageId) }
                assertEquals("failed", stored?.status)
            }
        }
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        fun defaultTimings(
            firstTokenDeadlineMs: Long = 3_000,
        ) = ChatTimings(
            thinkingAtMs = 200,
            firstTokenDeadlineMs = firstTokenDeadlineMs,
            stallMs = 5_000,
            totalMs = 20_000,
            persistEveryMs = 150,
            pollMs = 25,
        )
    }
}
