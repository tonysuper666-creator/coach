package com.coach.desktop

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.client.ChatItem
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.CommandBody
import com.coach.server.ChatService
import com.coach.server.ledgerRoutes
import com.coach.store.LedgerStore
import com.sun.net.httpserver.HttpServer
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** SYNTHETIC ONLY: actual desktop session + HTTP/WS, no windows, production or model calls. */
class DesktopAgentRunTest {
    private val run = "desktop_acceptance"
    private val course = "synthetic_course"
    private val token = "synthetic-desktop-agent-token-32-characters"

    private fun settings(root: Path, url: String, agent: Boolean = true): DesktopConnectionSettings {
        val tokenFile = root.resolve("token").also { Files.writeString(it, token) }
        val env = mapOf("COACH_URL" to url, "COACH_DEVICE_ID" to "desktop",
            "COACH_TOKEN_FILE" to tokenFile.toString(), "COACH_CLIENT_DIR" to root.resolve("client").toString()) +
            if (agent) mapOf("COACH_ACCEPTANCE_REPLAY" to "1", "COACH_AGENT_RUN_ID" to run) else emptyMap()
        return DesktopConnectionSettings(root, env::get, mac = false)
    }

    @Test fun explicitRunRequiresValidIdAndIsolation() {
        assertNull(desktopAcceptanceRun { null })
        val isolated = mapOf("COACH_URL" to "http://127.0.0.1:18001", "COACH_TOKEN_FILE" to "/synthetic/token",
            "COACH_CLIENT_DIR" to "/synthetic/client", "COACH_ACCEPTANCE_REPLAY" to "1", "COACH_AGENT_RUN_ID" to run)
        assertEquals(run, desktopAcceptanceRun(isolated::get))
        for (env in listOf(isolated - "COACH_ACCEPTANCE_REPLAY", isolated - "COACH_AGENT_RUN_ID",
                isolated - "COACH_CLIENT_DIR", isolated + ("COACH_AGENT_RUN_ID" to "../learner"),
                isolated + ("COACH_URL" to "http://127.0.0.1:18791"))) {
            assertFailsWith<IllegalArgumentException> { desktopAcceptanceRun(env::get) }
        }
        assertNotNull(refuseProductionDefaults({ if (it == "COACH_ACCEPTANCE_REPLAY") "1" else null }) {
            if (it == "jpackage.app-path") "/synthetic/Coach.app" else null
        })
    }

    @Test fun desktopSessionMarksBothChannelsAndPreservesLearner(): Unit = runBlocking {
        withServer { root, url, actor ->
            val config = settings(root, url)
            assertEquals(root.resolve("client/agent-runs/$run"), config.clientDirectory)
            // The existing learner cache/outbox must never be opened or rewritten by acceptance.
            val sentinel = root.resolve("client/ledger.sqlite")
            Files.createDirectories(sentinel.parent)
            Files.writeString(sentinel, "SYNTHETIC ONLY: learner outbox sentinel")
            val identity = assertNotNull(DesktopPairing(config).load())
            assertFailsWith<IllegalArgumentException> {
                DesktopLearningSession(config, DesktopIdentity("http://127.0.0.1:18002", identity.credentials), course)
            }
            val before = actor.execute { snapshot(course).projection }
            DesktopLearningSession(config, identity, course).use { session ->
                val tutor = session.controller.tutor
                val sync = session.controller.sync
                assertEquals(run, tutor.agentRunId, "The actual desktop constructor must opt into the capability handshake")
                val command = sync.enqueue(CommandBody("open_kp", course, 1, json("kpId" to "agent_kp")))
                val payload = LedgerJson.decodeFromString<CommandBody>(command.payloadBytes).payload
                assertEquals(run, payload.str("agentRunId"))
                assertEquals(AGENT_RUN_LABEL, payload.str("sampleOrigin"))
                sync.syncOnce() // Real HTTP hello, command upload, catch-up and authoritative projection.
                assertEquals("OK", sync.receipt(command.commandId)?.status)
                session.start()
                withTimeout(5_000) { while (!tutor.model.state.value.connected) delay(20) }
                val thread = agentThreadId(run, "lesson")
                assertFalse(tutor.open("human_thread", course, "agent_kp", emptyList(), "SYNTHETIC ONLY"))
                assertTrue(tutor.open(thread, course, "agent_kp", listOf("agent_concept"), "SYNTHETIC ONLY"))
                withTimeout(5_000) { while (tutor.model.state.value.threads[thread]?.historyLoaded != true) delay(20) }
                assertTrue(tutor.open(thread, course, "agent_kp", listOf("agent_concept"), "SYNTHETIC ONLY"),
                    "Existing-course ledger catch-up must not revoke the negotiated capability")
                assertTrue(tutor.next(thread, course, "SYNTHETIC ONLY: shapes have edges.", test = false))
                withTimeout(5_000) {
                    while (tutor.model.state.value.threads[thread]?.items?.filterIsInstance<ChatItem.Tutor>()
                            ?.none { it.message.status == "complete" && it.message.text.contains("SYNTHETIC ONLY") } != false) delay(20)
                }
                val raw = actor.execute { events(course) }
                val agentFacts = raw.filter { it.payload.str("agentRunId") == run }
                assertTrue(agentFacts.isNotEmpty())
                assertTrue("agent_kp" in project(raw, includeAgentRun = run).explainedKpIds,
                    "Real desktop next must finish teaching in its own run")
                assertTrue(agentFacts.all { it.payload.str("sampleOrigin") == AGENT_RUN_LABEL })
                val after = actor.execute { snapshot(course).projection }
                assertEquals(before.copy(lastSeq = after.lastSeq), after,
                    "Every learner projection field except sequence must survive agent activity")
            }
            assertEquals("SYNTHETIC ONLY: learner outbox sentinel", Files.readString(sentinel))
        }
    }

    @Test fun ordinaryDesktopStillUploadsLearnerEvidence(): Unit = runBlocking {
        withServer { root, url, actor ->
            val config = settings(root, url, agent = false)
            assertEquals(root.resolve("client"), config.clientDirectory)
            DesktopLearningSession(config, assertNotNull(DesktopPairing(config).load()), course).use { session ->
                assertNull(session.controller.tutor.agentRunId)
                val cmd = session.controller.sync.enqueue(CommandBody("open_kp", course, 1, json("kpId" to "human_kp")))
                assertEquals("", LedgerJson.decodeFromString<CommandBody>(cmd.payloadBytes).payload.str("agentRunId"))
                session.controller.sync.syncOnce()
                assertEquals("OK", session.controller.sync.receipt(cmd.commandId)?.status)
                assertTrue("human_kp" in actor.execute { snapshot(course).projection.startedKpIds })
            }
        }
    }

    @Test fun desktopSessionRefusesLegacyHelloOnBothChannels(): Unit = runBlocking {
        val root = Files.createTempDirectory("coach-desktop-legacy-").toRealPath()
        val frames = AtomicInteger()
        val uploads = AtomicInteger()
        val helloSeen = CompletableDeferred<Unit>()
        val legacyHello = """{"epoch":"synthetic","lastSeq":0,"backedUpThroughSeq":0,"lastSeqBefore":0}"""
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                get("/v1/hello") { call.respondText(legacyHello) }
                post("/v1/commands") { uploads.incrementAndGet(); call.respondText("unexpected") }
                webSocketRaw("/v1/ws") {
                    send(Frame.Text(legacyHello)); helloSeen.complete(Unit)
                    for (frame in incoming) when (frame) {
                        is Frame.Ping -> send(Frame.Pong(frame.data))
                        is Frame.Text -> frames.incrementAndGet()
                        else -> Unit
                    }
                }
            }
        }.apply { start(false) }
        try {
            val config = settings(root, "http://127.0.0.1:${engine.engine.resolvedConnectors().single().port}")
            DesktopLearningSession(config, assertNotNull(DesktopPairing(config).load()), course).use { session ->
                val sync = session.controller.sync
                sync.enqueue(CommandBody("open_kp", course, 1, json("kpId" to "agent_kp")))
                assertFailsWith<IllegalStateException> { sync.syncOnce() }
                session.start()
                withTimeout(5_000) { helloSeen.await() }
                delay(300)
                assertFalse(session.controller.tutor.model.state.value.connected)
                assertFalse(session.controller.tutor.open(agentThreadId(run, "lesson"), course, "agent_kp", emptyList(), "SYNTHETIC ONLY"))
                assertEquals(0, uploads.get())
                assertEquals(0, frames.get())
            }
        } finally { engine.stopSuspend(0, 200); root.toFile().deleteRecursively() }
    }

    private suspend fun withServer(block: suspend (Path, String, LedgerActor) -> Unit) {
        val root = Files.createTempDirectory("coach-desktop-agent-").toRealPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(root.resolve("server")).also { it.enroll("desktop", token) }
        store.append(course, "kp_opened", json("kpId" to "existing_human_kp"))
        val actor = LedgerActor(scope, store)
        // Only HTTP/SSE content is synthetic; desktop, transport, service and persistence are real.
        val stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/health") { exchange ->
            val bytes = """{"ok":true,"rate_limit":{"status":"allowed","utilization":0.0}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        stub.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = listOf(
                """{"model":"claude-sonnet-5-5","choices":[{"delta":{"content":"SYNTHETIC ONLY: shapes have edges."}}]}""",
                """{"model":"claude-sonnet-5-5","choices":[],"x_coach":{"status":"complete","finish_reason":"stop","served_by":"claude-sonnet-5-5","physical_calls":0}}""",
                "[DONE]",
            ).joinToString("") { "data: $it\n\n" }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        stub.start()
        val bridge = BridgeClient(BridgeConfig(baseUrl = "http://127.0.0.1:${stub.address.port}"))
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8),
                ChatService(actor, scope, bridge, packsDirectory = root.resolve("packs")))
        }.apply { start(false) }
        try { block(root, "http://127.0.0.1:${engine.engine.resolvedConnectors().single().port}", actor) }
        finally { engine.stopSuspend(0, 200); actor.close(); scope.cancel(); stub.stop(0); root.toFile().deleteRecursively() }
    }
}
