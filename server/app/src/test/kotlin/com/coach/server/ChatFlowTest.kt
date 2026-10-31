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

    private class Harness(timings: ChatTimings = defaultTimings(), packDir: java.nio.file.Path = Files.createTempDirectory("coach-packs-")) : AutoCloseable {
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
            packsDirectory = packDir,
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
    fun androidCanStartTeachingWithoutUploadingTheTextbookPack() {
        val packs=Files.createTempDirectory("coach-content-")
        Files.createDirectories(packs.resolve("course-1"))
        Files.writeString(packs.resolve("course-1/kp-1.md"), "Synthetic source: the cost is the next best alternative.")
        Harness(packDir=packs).use { h ->
            h.tutor.stream(10, "教材讲解。", StubTutor.terminal("complete"), StubTutor.DONE)
            Ws(h.port,h.token).use { ws ->
                ws.send("""{"t":"tutor.open","courseId":"course-1","threadId":"thread-1","kpId":"kp-1","conceptIds":["c1"],"title":"机会成本"}""")
                ws.await("tutor.state")
                ws.send("""{"t":"tutor.next","courseId":"course-1","threadId":"thread-1"}""")
                assertEquals("complete",ws.await("msg.end").str("status"))
                assertTrue(h.tutor.requestBodies.single().contains("the cost is the next best alternative"))
            }
        }
    }

    @Test
    fun official429FallsBackWithoutWaitingForFirstTokenDeadline() {
        Harness().use { h ->
            h.tutor.rateLimit()
            h.tutor.completion("兜底的完整回答。", BridgeClient.CODEX)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                ws.await("msg.start")
                val notice = ws.await("tutor.progress")
                assertEquals("Claude 暂时限流，已换 ${BridgeClient.CODEX} 回答", notice.str("text"))
                val replacement = ws.await("msg.snapshot")
                assertEquals("", replacement.str("text"))
                ws.await("msg.end")
                val fallback = ws.await("msg.start")
                assertEquals(BridgeClient.CODEX, fallback.str("model"))
                val end = ws.await("msg.end")
                assertEquals("complete", end.str("status"))
                val row = runBlocking { h.snapshot(fallback.str("msg")) }!!
                assertEquals("兜底的完整回答。", row.content)
                assertEquals(2, h.tutor.requests)
            }
        }
    }

    @Test
    fun officialRateLimitAfterPartialTextReplacesTheIncompleteAnswer() {
        Harness().use { h ->
            h.tutor.stream(10, "未写完的解释。", StubTutor.terminal("truncated", "rate_limited"), StubTutor.DONE)
            h.tutor.completion("换模型后的完整解释。", BridgeClient.CODEX)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val original = ws.await("msg.start")
                ws.await("tutor.progress")
                val replacement = ws.await("msg.snapshot")
                assertEquals("", replacement.str("text"))
                ws.await("msg.end")
                val fallback = ws.await("msg.start")
                assertEquals("complete", ws.await("msg.end").str("status"))
                assertEquals("", runBlocking { h.snapshot(original.str("msg")) }!!.content)
                assertEquals("换模型后的完整解释。", runBlocking { h.snapshot(fallback.str("msg")) }!!.content)
            }
        }
    }

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

    private suspend fun seedTutor(h: Harness) {
        h.actor.execute {
            repeat(3) { i ->
                val options = listOf("opportunity cost $i","sunk expenditure $i","fixed payment $i","total revenue $i").mapIndexed { j,text -> com.coach.domain.Option("q$i-$j",text) }
                registerQuestion("q$i","course-1","","SINGLE_CHOICE","Synthetic question $i","VERIFIED",options.first().optionId,options,listOf("kp-1"))
            }
        }
    }

    @Test
    fun taughtContinueAndWholeGroupSubmissionGoThroughWsWithoutAnotherModelCall() {
        Harness().use { h ->
            runBlocking { seedTutor(h) }
            h.tutor.stream(15,"机会成本是放弃的最佳替代。",StubTutor.terminal("complete"),StubTutor.DONE)
            Ws(h.port,h.token).use { ws ->
                ws.send("""{"t":"tutor.open","threadId":"thread-1","courseId":"course-1","kpId":"kp-1","conceptIds":["cost"],"title":"Synthetic objective"}""")
                assertEquals("IDLE",ws.await("tutor.state").str("state"))
                ws.send("""{"t":"msg.ask","threadId":"thread-1","courseId":"course-1","text":"继续"}""")
                ws.await("msg.start")
                ws.await("msg.end")
                while (ws.await("tutor.state").str("state") != "AWAIT") Unit
                ws.send("""{"t":"msg.ask","threadId":"thread-1","courseId":"course-1","text":"继续"}""")
                val group = ws.await("tutor.cards")
                assertEquals(1,h.tutor.requests)
                val cards = group.getValue("cards") as kotlinx.serialization.json.JsonArray
                assertEquals(3,cards.size)
                val answers = runBlocking { h.actor.execute {
                    kotlinx.serialization.json.JsonObject(cards.associate { element ->
                        val card = element.jsonObject
                        val key = tutorChoiceKey(card.str("questionId"))
                        card.str("attemptId") to card.getValue("letterMap").jsonObject.getValue(key.correctOptionId)
                    })
                } }
                ws.send("""{"t":"tutor.answer","threadId":"thread-1","courseId":"course-1","groupId":"${group.str("groupId")}","submissionId":"ws-submit","answers":$answers}""")
                ws.await("tutor.feedback")
                val state = runBlocking { h.actor.execute { snapshot("course-1").projection } }
                assertTrue(state.mastery.getValue("kp-1").passed)
                assertEquals(3,state.mastery.getValue("kp-1").window.size)
                assertTrue(h.tutor.requestBodies.none { it.contains("Student question: 继续") })
            }
        }
    }

    @Test
    fun rawLeakIsDurableAfterSnapshotRemovesTheLetterSentence() {
        Harness().use { h ->
            runBlocking { seedTutor(h) }
            h.tutor.stream(40,"先想一想，", "选 ", "B，opportunity ", "cost 0。",StubTutor.terminal("complete"),StubTutor.DONE)
            Ws(h.port,h.token).use { ws ->
                ws.send("""{"t":"tutor.open","threadId":"thread-1","courseId":"course-1","kpId":"kp-1","conceptIds":["cost"],"title":"Synthetic objective"}""")
                ws.await("tutor.state")
                ws.send("""{"t":"tutor.test","threadId":"thread-1","courseId":"course-1"}""")
                ws.await("tutor.cards")
                ask(ws)
                val id = ws.await("msg.start").str("msg")
                val assembler = ChatStreamAssembler()
                var end = false
                var revised = false
                while (!end) {
                    val frame = ws.await("msg.delta","msg.snapshot","msg.end")
                    if (frame.str("t") == "msg.snapshot") revised = true
                    if (frame.str("t") == "msg.delta") assertTrue(!frame.str("text").contains("B"))
                    assembler.handle(frame)
                    end = frame.str("t") == "msg.end"
                }
                assertTrue(revised)
                assertEquals("",assembler.text(id))
                val facts = runBlocking { h.actor.execute { events("course-1") } }
                val leak = facts.single { it.type == "hint_revealed" }
                assertEquals("LEAK",leak.payload.str("cause"))
                assertTrue(h.tutor.requestBodies.single().contains("Unordered option texts"))
                assertTrue(!h.tutor.requestBodies.single().contains("letterMap"))
                val callStatus = java.sql.DriverManager.getConnection("jdbc:sqlite:${h.store.directory.resolve("coach.sqlite")}").use { connection ->
                    connection.createStatement().use { statement -> statement.executeQuery("SELECT status FROM ai_calls").use { rows -> rows.next(); rows.getString(1) } }
                }
                assertEquals("letter_ref_stripped",callStatus)
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
