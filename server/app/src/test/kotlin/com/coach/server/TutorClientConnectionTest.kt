package com.coach.server

import com.coach.client.ChatItem
import com.coach.client.TutorClient
import com.coach.client.TutorModel
import com.coach.client.UsageTelemetry
import com.coach.client.db.ClientDatabase
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocketRaw
import io.ktor.websocket.Frame
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** No CLI calls: HTTP stays healthy while only the tutor socket stops answering pings. */
class TutorClientConnectionTest {
    private class Stub(private val recoverOnReconnect: Boolean = true) : AutoCloseable {
        val mute = AtomicBoolean(false)
        val connections = AtomicInteger()
        val active = AtomicInteger()
        val closed = AtomicInteger()
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                get("/v1/hello") { call.respondText("healthy") }
                webSocketRaw("/v1/ws") {
                    val number = connections.incrementAndGet()
                    active.incrementAndGet()
                    try {
                        for (frame in incoming) when (frame) {
                            is Frame.Ping -> if (!mute.get() || (recoverOnReconnect && number > 1)) {
                                send(Frame.Pong(frame.data))
                                if (number > 1) {
                                    send(Frame.Text("""{"t":"msg.snapshot","msg":"m","threadId":"thread","i":2,"text":"recovered","status":"complete"}"""))
                                }
                            }
                            is Frame.Text -> {
                                val bytes = """{"t":"msg.snapshot","msg":"m","threadId":"thread","i":1,"text":"中文","status":"streaming"}""".encodeToByteArray()
                                // Split a UTF-8 character across text fragments, with a control frame between them.
                                val split = bytes.indexOfFirst { it < 0 } + 1
                                send(Frame.Text(false, bytes.copyOfRange(0, split)))
                                send(Frame.Ping("server-ping".encodeToByteArray()))
                                send(Frame.Text(true, bytes.copyOfRange(split, bytes.size)))
                            }
                            else -> Unit // Deliberately never acknowledge close.
                        }
                    } finally { active.decrementAndGet(); closed.incrementAndGet() }
                }
            }
        }.apply { start(wait = false) }
        val url get() = "http://127.0.0.1:${runBlocking { engine.engine.resolvedConnectors().first().port }}"
        fun assertHelloHealthy() {
            val connection = java.net.URI("$url/v1/hello").toURL().openConnection() as java.net.HttpURLConnection
            try {
                connection.connectTimeout = 1_000
                connection.readTimeout = 1_000
                assertEquals("healthy", connection.inputStream.bufferedReader().use { it.readText() })
            } finally { connection.disconnect() }
        }
        override fun close() { engine.stop(0, 200) }
    }

    private suspend fun await(check: () -> Boolean) = withTimeout(15_000) {
        while (!check()) delay(20)
    }

    @Test fun missingPongReplacesSocketWithoutCloseAckAndRestoresMessages(): Unit = runBlocking {
        Stub().use { stub ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = TutorModel(System::currentTimeMillis)
            val client = TutorClient(stub.url, "device", "t".repeat(43), model)
            try {
                val owner = client.start(scope)
                assertSame(owner, client.start(scope), "Starting twice must not create competing readers")
                await { model.state.value.connected }
                assertTrue(client.send(buildJsonObject { put("t", "fragment-probe") }))
                await { model.unfinishedMessageIds() == listOf("m") }
                val initial = (model.state.value.threads["thread"]!!.items.single() as ChatItem.Tutor).message
                assertEquals("中文", initial.text)
                stub.mute.set(true)
                stub.assertHelloHealthy()
                val stopped = System.nanoTime()
                await { !model.state.value.connected }
                val offlineMs = (System.nanoTime() - stopped) / 1_000_000
                assertTrue(offlineMs <= 15_000, "Tutor offline latency: $offlineMs ms")
                assertFalse(client.send(buildJsonObject { put("t", "offline-send") }))
                stub.assertHelloHealthy()
                await { model.state.value.connected && stub.connections.get() >= 2 }
                await {
                    (model.state.value.threads["thread"]?.items?.singleOrNull() as? ChatItem.Tutor)?.message?.text == "recovered"
                }
                // A former connection's cleanup must not flip the replacement offline.
                delay(600)
                assertTrue(model.state.value.connected)
                await { stub.closed.get() >= 1 && stub.active.get() == 1 }
                println("TUTOR socketOnlyFailureOfflineMs=$offlineMs replacedWithoutCloseAck=true fragmentsRecovered=true")
                client.close()
                withTimeout(2_000) { owner.join() }
                assertFalse(model.state.value.connected)
                assertFalse(client.send(buildJsonObject { put("t", "after-close") }))
                await { stub.active.get() == 0 }
            } finally { client.close(); scope.cancel() }
        }
    }

    @Test fun upgradeWithoutPongNeverClaimsOnlineAndCloseCancelsRetry(): Unit = runBlocking {
        Stub(recoverOnReconnect = false).use { stub ->
            stub.mute.set(true)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = TutorModel(System::currentTimeMillis)
            val client = TutorClient(stub.url, "device", "t".repeat(43), model)
            try {
                val owner = client.start(scope)
                withTimeout(12_000) {
                    while (stub.connections.get() < 2) {
                        assertFalse(model.state.value.connected)
                        delay(20)
                    }
                }
                val attempts = stub.connections.get()
                stub.assertHelloHealthy()
                assertFalse(model.state.value.connected)
                client.close()
                withTimeout(2_000) { owner.join() }
                delay(300)
                assertEquals(attempts, stub.connections.get())
                assertFalse(model.state.value.connected)
                assertFailsWith<IllegalStateException> { client.start(scope) }
            } finally { client.close(); scope.cancel() }
        }
    }

    @Test fun telemetryRecordsOneOfflineIntervalAndIgnoresAutomaticFrames(): Unit = runBlocking {
        Stub(recoverOnReconnect = false).use { stub ->
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ClientDatabase.Schema.create(it) }
            val db = ClientDatabase(driver)
            val telemetry = UsageTelemetry(db, System::currentTimeMillis) { false }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = TutorModel(System::currentTimeMillis, telemetry)
            val client = TutorClient(stub.url, "device", "t".repeat(43), model, telemetry)
            fun events() = db.ledgerQueries.usageBatch().executeAsList().map { Json.parseToJsonElement(it.payload).jsonObject }
            try {
                client.start(scope)
                await { model.state.value.connected }
                assertTrue(client.send(buildJsonObject { put("t", "client.ttft") }))
                assertTrue(client.send(buildJsonObject { put("t", "msg.resume") }))
                assertTrue(client.next("thread", "course", "", test = false, recordTap = false))
                assertTrue(client.next("thread", "course", "", test = true))
                stub.mute.set(true)
                await { !model.state.value.connected }
                withTimeout(20_000) { while (stub.connections.get() < 3) delay(50) }
                stub.mute.set(false)
                await { model.state.value.connected }
                val observed = events().map { it.getValue("kind").jsonPrimitive.content to it.getValue("target").jsonPrimitive.content }
                assertEquals(1, observed.count { it.first == "disconnect" }, observed.toString())
                assertEquals(1, observed.count { it.first == "reconnect" }, observed.toString())
                assertEquals(0, observed.count { it == ("error" to "network") }, observed.toString())
                assertEquals(listOf("test"), observed.filter { it.first == "tap" }.map { it.second })
            } finally { client.close(); scope.cancel(); driver.close() }
        }
    }
}
