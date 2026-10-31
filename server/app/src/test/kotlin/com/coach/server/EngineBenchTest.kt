package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.client.ChatStreamAssembler
import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
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
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DESIGN §14 #7：同一负载下 Netty 与 CIO 各测一遍首字与续传，选一个写进 ADR-0004 附注。
 *
 * 负载是桩桥（不调真实 CLI），量的是「WS 客户端看到正文首字」与「断线重连拿到 snapshot」，
 * 这正是 M1-01 的验收口径；真实模型的首字时间由 11-02 之后的 live 组负责。
 */
class EngineBenchTest {

    private class Bench(val name: String) : AutoCloseable {
        val tutor = StubTutor()
        val token = "engine-bench-token-32-characters-minimum"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(Files.createTempDirectory("coach-bench-")).also { it.enroll("desktop", token) }
        val actor = LedgerActor(scope, store)
        val chat = ChatService(
            actor = actor,
            scope = scope,
            bridge = BridgeClient(BridgeConfig(tutor.baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(30))),
            timings = ChatTimings(
                thinkingAtMs = 5_000, firstTokenDeadlineMs = 10_000, stallMs = 10_000,
                totalMs = 30_000, persistEveryMs = 200, pollMs = 20,
            ),
            fallbackOnFirstTokenTimeout = false,
            log = { },
        )
        private val module: io.ktor.server.application.Application.() -> Unit = {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), chat)
        }
        private val server: EmbeddedServer<*, *> = if (name == "netty") {
            embeddedServer(Netty, host = "127.0.0.1", port = 0, module = module)
        } else {
            embeddedServer(CIO, host = "127.0.0.1", port = 0, module = module)
        }

        init {
            server.start(wait = false)
        }

        val port: Int get() = runBlocking { server.engine.resolvedConnectors().first().port }

        override fun close() {
            runBlocking { server.stopSuspend(0, 500); actor.close() }
            scope.cancel()
            tutor.close()
        }
    }

    private class Ws(port: Int, token: String) : AutoCloseable {
        private val queue = LinkedBlockingQueue<JsonObject>()
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
                .buildAsync(URI.create("ws://127.0.0.1:$port/v1/ws"), listener)
                .get(5, TimeUnit.SECONDS)
        }

        fun send(text: String) = socket.sendText(text, true).get(5, TimeUnit.SECONDS)

        fun await(vararg types: String, timeoutMs: Long = 10_000): JsonObject {
            val wanted = types.toSet()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                val frame = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (frame.str("t") in wanted) return frame
            }
            throw AssertionError("timeout waiting for $wanted")
        }

        override fun close() {
            runCatching { socket.abort() }
        }
    }

    @Test
    fun nettyAndCioUnderTheSameLoad() {
        for (engineName in listOf("netty", "cio")) {
            Bench(engineName).use { bench ->
                val firstTokens = ArrayList<Long>()
                repeat(20) {
                    bench.tutor.stream(10, "第一句。", "第二句。", "第三句。", StubTutor.terminal("complete"), StubTutor.DONE)
                    Ws(bench.port, bench.token).use { ws ->
                        val startedAt = System.nanoTime()
                        ws.send("""{"t":"msg.ask","threadId":"bench-$engineName-$it","courseId":"course-1","text":"合成问题 $it"}""")
                        ws.await("msg.delta")
                        firstTokens.add((System.nanoTime() - startedAt) / 1_000_000)
                        ws.await("msg.end")
                    }
                }
                val resume = ArrayList<Long>()
                repeat(5) {
                    bench.tutor.stream(
                        120, "第一段。", "第二段。", "第三段。", "第四段。",
                        StubTutor.terminal("complete"), StubTutor.DONE,
                    )
                    val messageId = run {
                        val ws = Ws(bench.port, bench.token)
                        ws.send("""{"t":"msg.ask","threadId":"resume-$engineName-$it","courseId":"course-1","text":"合成问题"}""")
                        val id = ws.await("msg.start").str("msg")
                        ws.await("msg.delta")
                        ws.close()
                        id
                    }
                    Thread.sleep(150)
                    Ws(bench.port, bench.token).use { ws ->
                        val startedAt = System.nanoTime()
                        ws.send("""{"t":"msg.resume","msg":"$messageId"}""")
                        val snapshot = ws.await("msg.snapshot")
                        resume.add((System.nanoTime() - startedAt) / 1_000_000)
                        val assembler = ChatStreamAssembler()
                        assembler.handle(snapshot)
                        var end: JsonObject? = null
                        while (end == null) {
                            val frame = ws.await("msg.delta", "msg.end")
                            assembler.handle(frame)
                            if (frame.str("t") == "msg.end") end = frame
                        }
                        assertEquals("complete", end.str("status"))
                        assertTrue(assembler.text(messageId).startsWith("第一段。"), "resume lost the head of the answer")
                    }
                }
                println(
                    "ENGINE_BENCH engine=$engineName n=20 first_token_p50=${pct(firstTokens, 50)}ms " +
                        "first_token_p90=${pct(firstTokens, 90)}ms resume_p50=${pct(resume, 50)}ms resume_n=5",
                )
            }
        }
    }

    private fun pct(values: List<Long>, p: Int): Long {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return -1
        val index = ((sorted.size - 1) * p / 100.0).toInt()
        return sorted[index]
    }

}

private fun JsonObject.str(key: String): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

