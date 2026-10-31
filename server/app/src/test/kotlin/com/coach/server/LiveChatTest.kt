package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.client.ChatStreamAssembler
import com.coach.client.ChatUpdate
import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import com.coach.domain.json
import com.coach.store.LedgerStore
import com.sun.net.httpserver.HttpServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Opt-in acceptance. Production services, real CLI, local corpus; no mock in this path. */
class LiveChatTest {
    private val home = Path.of(System.getProperty("user.home"))
    private val output = Path.of(System.getenv("COACH_LIVE_OUT") ?: home.resolve("Coach/corpus/m1-stream/${Instant.now().epochSecond}").toString())
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val rows = mutableListOf<JsonObject>()

    private fun record(vararg fields: Pair<String, Any?>) {
        val row = json("at" to Instant.now().toString(), *fields)
        Files.createDirectories(output)
        Files.writeString(output.resolve("results.jsonl"), "$row\n", CREATE, APPEND)
        rows.add(row)
        println("LIVE $row")
    }

    private fun pack(): String {
        val corpus = Path.of(System.getenv("COACH_CORPUS_DIR") ?: home.resolve("Coach/corpus/bridge-8791").toString())
        require(Files.isDirectory(corpus)) { "缺语料，未运行；设置 COACH_CORPUS_DIR" }
        val files = Files.list(corpus).use { paths -> paths.filter { it.toString().endsWith(".md") }.sorted().toList() }
        require(files.isNotEmpty()) { "缺语料，未运行；设置 COACH_CORPUS_DIR" }
        return files.sortedBy { if (it.fileName.toString().contains("Adverse selection")) 0 else 1 }
            .asSequence().map { Files.readString(it) }.runningFold("") { a, b -> a + b + "\n\n" }
            .firstOrNull { it.length >= 40_000 }?.take(40_000)
            ?: files.joinToString("\n\n") { Files.readString(it) }.take(40_000)
    }

    private fun health(url: String = "http://127.0.0.1:8791"): JsonObject = LedgerJson.parseToJsonElement(http.send(
        HttpRequest.newBuilder(URI.create("$url/health")).timeout(Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()).jsonObject

    private fun preflight(requiredCalls: Int = 1): JsonObject {
        val now = Instant.now()
        require(now < Instant.parse("2026-11-02T00:00:00Z") || now >= Instant.parse("2026-11-02T15:00:00Z")) {
            "复习保护时段：不运行 live"
        }
        val h = health()
        return h
    }

    private inner class Bridge(private val stub: Boolean = false, mode: String = "protocol") : AutoCloseable {
        val port = if (stub) ServerSocket(0).use { it.localPort } else 8791
        val url get() = "http://127.0.0.1:$port"
        private var process: Process? = null
        private val root: ProcessHandle
        init {
            Files.createDirectories(output)
            val attachPid = if (stub) null else System.getenv("COACH_LIVE_BRIDGE_PID")?.toLong()
            if (attachPid != null) {
                root = ProcessHandle.of(attachPid).orElseThrow()
                require(root.isAlive && root.info().arguments().orElse(emptyArray()).any { it.endsWith("subscription_bridge.py") }) {
                    "COACH_LIVE_BRIDGE_PID 必须是现有桥进程"
                }
                val h = health(url)
                require(h.getValue("concurrency").jsonObject.getValue("claude").jsonObject.str("active") == "0") { "现有桥正在处理请求，本批未开始" }
                record("stage" to "bridge", "event" to "attach", "pid" to root.pid(), "port" to port, "daily" to h["daily"], "rate_limit" to h["rate_limit"])
            } else {
                // Only a bridge started by this test may be stopped; never touch 8790.
                ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port)) }
                val python = System.getenv("COACH_BRIDGE_PYTHON") ?: "tools/bridge/.venv/bin/python"
                val stubDir = output.resolve("stub-$mode-cli")
                if (stub) {
                    Files.createDirectories(stubDir)
                    Files.copy(Path.of("tools/bridge/tests/stub_cli.py"), stubDir.resolve("claude"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    stubDir.resolve("claude").toFile().setExecutable(true)
                    Files.writeString(stubDir.resolve("mode"), mode)
                }
                val owned = ProcessBuilder(python, "tools/bridge/subscription_bridge.py").apply {
                    environment().remove("BRIDGE_CHILD_PATH")
                    environment()["BRIDGE_PORT"] = port.toString()
                    environment()["BRIDGE_CLAUDE_CONCURRENCY"] = "1"
                    environment()["BRIDGE_AGY_ENABLED"] = "0"
                    environment()["COACH_BRIDGE_HOME"] = if (stub) output.resolve("stub-$mode-bridge").toString() else home.resolve("Coach/bridge").toString()
                    if (stub) {
                        environment()["BRIDGE_CHILD_PATH"] = stubDir.toString()
                        environment()["STUB_DIR"] = stubDir.toString()
                        environment()["STUB_SERVED"] = BridgeClient.CLAUDE
                    }
                    redirectOutput(output.resolve(if (stub) "stub-$mode.stdout.log" else "bridge.stdout.log").toFile())
                    redirectError(output.resolve(if (stub) "stub-$mode.stderr.log" else "bridge.stderr.log").toFile())
                }.start()
                process = owned
                root = owned.toHandle()
                record("stage" to "bridge", "event" to "start", "pid" to owned.pid(), "port" to port, "stub" to stub, "mode" to mode)
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    while (runCatching { health(url) }.getOrNull() == null) {
                        check(owned.isAlive && System.nanoTime() < deadline) { "桥未就绪；见本地 bridge.stderr.log" }
                        Thread.sleep(100)
                    }
                } catch (t: Throwable) { close(); throw t }
            }
        }
        fun descendants(): Set<Long> = root.descendants().use { stream -> stream.map { it.pid() }.toList().toSet() }
        override fun close() {
            val owned = process
            if (owned == null) {
                record("stage" to "bridge", "event" to "detach", "pid" to root.pid(), "alive" to root.isAlive)
                return
            }
            owned.destroy()
            if (!owned.waitFor(10, TimeUnit.SECONDS)) {
                owned.descendants().use { stream -> stream.forEach { it.destroy() } }
                owned.destroyForcibly().waitFor()
            }
            record("stage" to "bridge", "event" to "stop", "pid" to owned.pid(), "exit" to owned.exitValue(), "stub" to stub)
        }
    }

    private class Ws(port: Int, token: String) : AutoCloseable {
        private val queue = LinkedBlockingQueue<JsonObject>()
        private val socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .header("Authorization", "Bearer $token").header("X-Coach-Device", "live-client")
            .buildAsync(URI.create("ws://127.0.0.1:$port/v1/ws"), object : WebSocket.Listener {
                private val buffer = StringBuilder()
                override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    buffer.append(data)
                    if (last) { queue.add(LedgerJson.parseToJsonElement(buffer.toString()).jsonObject); buffer.setLength(0) }
                    ws.request(1)
                    return null
                }
            }).get(5, TimeUnit.SECONDS)
        init { await("hello") }
        fun send(frame: JsonObject) { socket.sendText(frame.toString(), true).get(5, TimeUnit.SECONDS) }
        fun await(vararg types: String, timeoutMs: Long = 195_000): JsonObject {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                val frame = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (frame.str("t") in types || ("hello" in types && "epoch" in frame && "lastSeq" in frame)) return frame
            }
            error("WS timeout waiting for ${types.toList()}")
        }
        override fun close() { socket.abort() }
    }

    private class Harness(baseUrl: String = "http://127.0.0.1:8791", directory: Path) : AutoCloseable {
        val token = UUID.randomUUID().toString()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(directory).also { it.enroll("live-client", token) }
        val actor = LedgerActor(scope, store)
        val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val chat = ChatService(actor, scope, BridgeClient(BridgeConfig(baseUrl)), fallbackOnFirstTokenTimeout = false, log = { logs.add(it) })
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 64), chat)
        }.apply {
            engineConfig.apply { connectionGroupSize = 1; workerGroupSize = 1; callGroupSize = 2 }
            start(wait = false)
        }
        fun ws() = Ws(runBlocking { server.engine.resolvedConnectors().single().port }, token)
        fun snapshot(id: String) = runBlocking { actor.execute { messageSnapshot(id) } }!!
        fun terminalLog(id: String): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline) {
                synchronized(logs) { logs.firstOrNull { it.startsWith("COACH msg.end msg=$id ") } }?.let { return it }
                Thread.sleep(10)
            }
            error("missing terminal diagnostic $id")
        }
        override fun close() {
            chat.abortAll("live_cleanup")
            runBlocking { server.stopSuspend(0, 500); actor.close() }
            scope.cancel()
        }
    }

    /** Forward the real request, then break its SSE connection after the first real content delta. */
    private class CutProxy : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url get() = "http://127.0.0.1:${server.address.port}"
        init {
            server.createContext("/v1/chat/completions") { exchange ->
                try {
                    val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8791/v1/chat/completions"))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(exchange.requestBody.readBytes()))
                    for (key in listOf("Content-Type", "X-Coach-Effort", "X-Coach-Fallback", "X-Request-Id")) {
                        exchange.requestHeaders.getFirst(key)?.let { request.header(key, it) }
                    }
                    val response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(response.statusCode(), 0)
                    response.body().bufferedReader().use { input ->
                        while (true) {
                            val line = input.readLine() ?: break
                            exchange.responseBody.write((line + "\n").toByteArray())
                            exchange.responseBody.flush()
                            val content = if (line.startsWith("data:")) runCatching {
                                LedgerJson.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject["choices"]
                                    ?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject?.str("content")
                            }.getOrNull() else null
                            if (!content.isNullOrEmpty()) {
                                exchange.responseBody.write("\n".toByteArray()); exchange.responseBody.flush()
                                break
                            }
                        }
                    }
                } finally { exchange.close() }
            }
            server.start()
        }
        override fun close() { server.stop(0) }
    }

    private fun ask(ws: Ws, pack: String, question: String): Long {
        preflight()
        val started = System.nanoTime()
        ws.send(json("t" to "msg.ask", "threadId" to UUID.randomUUID().toString(), "courseId" to "live-course", "text" to question, "pack" to pack))
        return started
    }

    private fun finish(ws: Ws, assembler: ChatStreamAssembler, id: String): JsonObject {
        while (true) {
            val frame = ws.await("msg.delta", "msg.math", "msg.end")
            if (frame.str("msg") != id) continue
            assembler.handle(frame)
            if (frame.str("t") == "msg.end") return frame
        }
    }

    private fun verify(h: Harness, assembler: ChatStreamAssembler, id: String, end: JsonObject, stage: String, sample: Int, vararg extra: Pair<String, Any?>) {
        val stored = h.snapshot(id)
        val diagnostic = h.terminalLog(id)
        val terminal = Regex(" terminal=(\\S+)").find(diagnostic)!!.groupValues[1]
        val bridgeTtft = Regex(" ttft=(\\S+)").find(diagnostic)!!.groupValues[1].toLongOrNull()
        val clientHash = sha(assembler.text(id))
        val serverHash = sha(stored.content)
        record("stage" to stage, "sample" to sample, "msg" to id, "status" to end.str("status"),
            "x_coach_status" to terminal, "bridge_reader_ttft_ms" to bridgeTtft,
            "client_sha256" to clientHash, "server_sha256" to serverHash, "chars" to stored.content.length,
            "stored_status" to stored.status, "reason" to stored.reason, *extra)
        assertEquals(stored.status, end.str("status"))
        assertEquals(serverHash, clientHash, "client/server answer diverged")
        assertTrue(assembler.text(id).isNotBlank(), "no visible content")
        assertFalse(Regex("placeholder|malformed|tool_call|<tool_calls>|x_coach|【需查原文", RegexOption.IGNORE_CASE).containsMatchIn(assembler.text(id)))
        if (terminal != "null") assertEquals(terminal, end.str("status"), "bridge/client terminal mismatch")
    }

    @Test
    fun realCliAcrossTheStreamingChain() {
        val pack = pack() // Missing local material is an explicit failure, never a skipped pass.
        val stages = (System.getenv("COACH_LIVE_STAGES") ?: "first,resume,cancel,cut,replay,early").split(",").toMutableList()
        require(stages.isNotEmpty() && stages.all { it in listOf("first", "resume", "cancel", "cut", "replay", "early") }) { "Unknown live stage" }
        val n = System.getenv("COACH_LIVE_FIRST_N")?.toInt() ?: 20
        val resumeN = System.getenv("COACH_LIVE_RESUME_N")?.toInt() ?: 10
        require(n > 0 && resumeN > 0)
        fun plannedCalls() = (if ("first" in stages) n else 0) + (if ("resume" in stages) resumeN else 0) +
            (if ("cancel" in stages) 2 else 0) + (if ("cut" in stages) 1 else 0)
        Bridge().use { bridge ->
            val dailyBefore = preflight().getValue("daily").jsonObject
            val remaining = dailyBefore["remaining"]?.jsonPrimitive?.intOrNull
            if (remaining != null && remaining < plannedCalls()) {
                require("first" in stages && remaining >= n) { "额度不足完整首字组；未发起调用" }
                record("stage" to "deferred", "reason" to "daily_budget", "stages" to stages.filter { it !in listOf("first", "replay") })
                stages.retainAll(listOf("first", "replay", "early"))
            }
            val revision = ProcessBuilder("git", "rev-parse", "HEAD").start().inputStream.bufferedReader().use { it.readText().trim() }
            record("stage" to "preflight", "daily" to dailyBefore, "planned_calls" to plannedCalls(), "git_revision" to revision)
            try {
                Harness(directory = output.resolve("ledger")).use { h ->
                    if ("first" in stages) repeat(n) { sample ->
                        h.ws().use { ws ->
                            val assembler = ChatStreamAssembler()
                            val started = ask(ws, pack, "请根据课程材料，用三到五句话解释逆向选择为什么可能导致保险市场失灵。")
                            val id = ws.await("msg.start").str("msg")
                            var firstMs: Long? = null
                            var end: JsonObject
                            while (true) {
                                val frame = ws.await("msg.delta", "msg.math", "msg.end", "msg.progress")
                                val updates = assembler.handle(frame)
                                if (firstMs == null && updates.any { (it is ChatUpdate.Text && it.text.isNotBlank()) || it is ChatUpdate.Math }) {
                                    firstMs = (System.nanoTime() - started) / 1_000_000
                                }
                                if (frame.str("t") == "msg.end") { end = frame; break }
                            }
                            verify(h, assembler, id, end, "first", sample, "client_ttft_ms" to firstMs)
                            assertNotNull(firstMs, "first visible character never arrived")
                            assertEquals("complete", end.str("status"))
                        }
                    }
                    if ("resume" in stages) repeat(resumeN) { sample ->
                        val assembler = ChatStreamAssembler()
                        val id = h.ws().use { ws ->
                            ask(ws, pack, "根据课程材料分十段解释逆向选择、市场失灵与保险合同的关系，每段两句，先直接写正文。")
                            val id = ws.await("msg.start").str("msg")
                            assembler.handle(ws.await("msg.delta", "msg.math"))
                            id
                        }
                        val disconnectedAt = System.nanoTime()
                        Thread.sleep(10_000) // Real client socket is gone; upstream stream must survive.
                        h.ws().use { ws ->
                            ws.send(json("t" to "msg.resume", "msg" to id))
                            val snapshot = ws.await("msg.snapshot")
                            assembler.handle(snapshot)
                            val end = if (snapshot.str("status") == "streaming") finish(ws, assembler, id)
                                else json("t" to "msg.end", "msg" to id, "status" to snapshot.str("status"))
                            verify(h, assembler, id, end, "resume", sample, "offline_ms" to (System.nanoTime() - disconnectedAt) / 1_000_000,
                                "snapshot_status" to snapshot.str("status"), "snapshot_i" to snapshot.str("i"),
                                "end_source" to if (snapshot.str("status") == "streaming") "msg.end" else "msg.snapshot")
                            assertEquals("complete", end.str("status"))
                        }
                    }
                    if ("cancel" in stages) repeat(2) { sample ->
                        val baseline = bridge.descendants()
                        h.ws().use { ws ->
                            val assembler = ChatStreamAssembler()
                            ask(ws, pack, "请根据课程材料写一篇很长的中文讲解，至少五十段，讨论逆向选择，直接开始正文。")
                            val id = ws.await("msg.start").str("msg")
                            assembler.handle(ws.await("msg.delta", "msg.math"))
                            val owned = bridge.descendants() - baseline
                            assertTrue(owned.isNotEmpty(), "no real CLI process observed")
                            val started = System.nanoTime()
                            ws.send(json("t" to "msg.cancel", "msg" to id))
                            val end = finish(ws, assembler, id)
                            val endMs = (System.nanoTime() - started) / 1_000_000
                            while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3) && owned.any { ProcessHandle.of(it).map { p -> p.isAlive }.orElse(false) }) Thread.sleep(10)
                            val exitMs = (System.nanoTime() - started) / 1_000_000
                            val survivors = owned.filter { ProcessHandle.of(it).map { p -> p.isAlive }.orElse(false) }
                            verify(h, assembler, id, end, "cancel", sample, "msg_end_ms" to endMs, "process_exit_ms" to exitMs,
                                "owned_pids" to owned.joinToString(","), "survivors" to survivors.joinToString(","))
                            assertEquals("cancelled", end.str("status"))
                            assertTrue(survivors.isEmpty() && exitMs < 2_000, "cancel process group exceeded 2 seconds")
                        }
                    }
                }
                if ("cut" in stages) CutProxy().use { proxy ->
                    Harness(proxy.url, output.resolve("cut-ledger")).use { h ->
                        h.ws().use { ws ->
                            val assembler = ChatStreamAssembler()
                            ask(ws, pack, "请用十段解释课程中的逆向选择问题。")
                            val id = ws.await("msg.start").str("msg")
                            val end = finish(ws, assembler, id)
                            verify(h, assembler, id, end, "cut", 0)
                            assertEquals("truncated", end.str("status"))
                        }
                    }
                }
            } finally {
                val after = health().getValue("daily").jsonObject
                record("stage" to "accounting", "daily_before" to dailyBefore, "daily_after" to after,
                    "physical_calls" to (after.str("claude_calls").toInt() - dailyBefore.str("claude_calls").toInt()))
                val first = rows.filter { it.str("stage") == "first" }.mapNotNull { it.str("client_ttft_ms").toLongOrNull() }.sorted()
                if (first.isNotEmpty()) record("stage" to "summary", "n" to first.size, "p50_ms" to percentile(first, 0.5), "p90_ms" to percentile(first, 0.9))
            }
        }
        if ("replay" in stages) Bridge(stub = true).use { bridge ->
            Harness(bridge.url, output.resolve("replay-ledger")).use { h ->
                h.ws().use { ws ->
                    val assembler = ChatStreamAssembler()
                    ask(ws, "Synthetic course context", "合成协议回放，不调用真实订阅 CLI")
                    val id = ws.await("msg.start").str("msg")
                    val end = finish(ws, assembler, id)
                    verify(h, assembler, id, end, "protocol_replay", 0, "real_calls" to 0)
                    assertEquals("正常正文。\n正常结尾。\n", assembler.text(id))
                    assertEquals("complete", end.str("status"))
                    assertTrue(h.logs.any { it.startsWith("COACH visible-drop") })
                }
            }
        }
        if ("early" in stages) Bridge(stub = true, mode = "hang").use { bridge ->
            Harness(bridge.url, output.resolve("early-ledger")).use { h ->
                for (waitForSpawn in listOf(false, true)) h.ws().use { ws ->
                    val baseline = bridge.descendants()
                    ask(ws, "Synthetic context", "合成立即取消，不调用真实订阅")
                    val id = ws.await("msg.start").str("msg")
                    val spawnDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (waitForSpawn && (bridge.descendants() - baseline).isEmpty() && System.nanoTime() < spawnDeadline) Thread.sleep(5)
                    val observed = bridge.descendants() - baseline
                    if (waitForSpawn) assertTrue(observed.isNotEmpty(), "stub CLI did not spawn")
                    val started = System.nanoTime()
                    ws.send(json("t" to "msg.cancel", "msg" to id))
                    val end = ws.await("msg.end", timeoutMs = 2_000)
                    // Also allow a just-sent HTTP request time to expose any late CLI spawn.
                    Thread.sleep(200)
                    while ((bridge.descendants() - baseline).isNotEmpty() && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2)) Thread.sleep(5)
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val survivors = bridge.descendants() - baseline
                    record("stage" to "early_cancel", "wait_for_spawn" to waitForSpawn, "status" to end.str("status"),
                        "process_exit_ms" to elapsed, "observed_pids" to observed.joinToString(","), "survivors" to survivors.joinToString(","), "real_calls" to 0)
                    assertEquals("cancelled", end.str("status"))
                    assertEquals("cancelled", h.snapshot(id).status)
                    assertTrue(survivors.isEmpty() && elapsed < 2_000)
                }
            }
        }
    }

    private fun percentile(values: List<Long>, p: Double) = values[(kotlin.math.ceil(values.size * p).toInt() - 1).coerceAtLeast(0)]
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()
