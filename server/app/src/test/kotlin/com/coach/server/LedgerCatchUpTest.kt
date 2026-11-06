package com.coach.server

import com.coach.client.HttpLedgerTransport
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real loopback Netty + disposable SQLite; no production ports, bridge or model calls. */
class LedgerCatchUpTest {
    private class Harness : AutoCloseable {
        val directory = Files.createTempDirectory("coach-ton1261-")
        val token = "ton1261-synthetic-token-32-characters"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(directory).also { it.enroll("desktop", token) }
        val actor = LedgerActor(scope, store)
        val pushes = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, pushes) }
            .start(false)
        val port get() = runBlocking { server.engine.resolvedConnectors().single().port }
        val baseUrl get() = "http://127.0.0.1:$port"
        fun seed(n: Int, text: String = "small", course: String = "c"): List<Event> = runBlocking {
            actor.execute { List(n) { append(course, "sync_fixture", json("text" to text)) } }
        }
        fun page(after: Long, limit: Int = 500): EventsPage {
            val request = HttpRequest.newBuilder(URI("$baseUrl/v1/courses/c/events?after=$after&limit=$limit"))
                .header("X-Coach-Device", "desktop").header("Authorization", "Bearer $token").build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            return LedgerJson.decodeFromString(response.body())
        }
        override fun close() {
            server.stop(0, 500)
            runBlocking { actor.close() }
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    private class Ws(h: Harness) : AutoCloseable {
        val frames = LinkedBlockingQueue<String>()
        val socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .header("X-Coach-Device", "desktop").header("Authorization", "Bearer ${h.token}")
            .buildAsync(URI("ws://127.0.0.1:${h.port}/v1/ws"), object : WebSocket.Listener {
                val pending = StringBuilder()
                override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    pending.append(data)
                    if (last) { frames.add(pending.toString()); pending.setLength(0) }
                    ws.request(1)
                    return null
                }
            }).get(5, TimeUnit.SECONDS)
        fun hello(after: Long = 0) {
            socket.sendText("""{"t":"hello","ledgerAfter":{"c":$after}}""", true).get(5, TimeUnit.SECONDS)
        }
        fun next(): String = assertNotNull(frames.poll(10, TimeUnit.SECONDS), "timed out waiting for ledger")
        fun ledger(): JsonObject {
            while (true) {
                val text = next()
                assertTrue(text.toByteArray(Charsets.UTF_8).size <= 262_144,
                    "ledger frame exceeded 256 KiB: ${text.toByteArray(Charsets.UTF_8).size}")
                val frame = LedgerJson.parseToJsonElement(text).jsonObject
                if (frame.str("t") == "ledger") return frame
            }
        }
        fun events(n: Int): Pair<List<Event>, List<Int>> {
            val events = mutableListOf<Event>()
            val sizes = mutableListOf<Int>()
            while (events.size < n) {
                val frame = ledger()
                sizes += frame.toString().toByteArray(Charsets.UTF_8).size
                events += LedgerJson.decodeFromJsonElement<LedgerFrame>(frame).events
            }
            return events to sizes
        }
        override fun close() { socket.abort() }
    }

    @Test fun largeLedgerIsBoundedAndReconnectStartsAtClientCursor() {
        Harness().use { h ->
            // UTF-8 and JSON escaping both affect the wire budget.
            val expected = h.seed(600, "测🙂\"\\\n".repeat(160))
            val total = LedgerJson.encodeToString(expected).toByteArray(Charsets.UTF_8).size
            assertTrue(total > 1_048_576)
            Ws(h).use { ws ->
                ws.hello()
                val (actual, sizes) = ws.events(expected.size)
                assertEquals(expected, actual)
                assertTrue(sizes.size > 1)
                println("TON1261 isolated_port=${h.port} ledger_bytes=$total frames=${sizes.joinToString(",")}")
                val added = h.seed(1).single()
                runBlocking { h.pushes.emit("c") }
                assertEquals(listOf(added), ws.events(1).first)
            }
            Ws(h).use { ws ->
                ws.hello(expected[589].seq)
                assertEquals(expected.drop(590) + h.page(expected.last().seq).events, ws.events(11).first)
            }
        }
    }

    @Test fun appendWhileDrainingAndAnotherCourseAreNotLostOrDuplicated() {
        Harness().use { h ->
            val expected = h.seed(600, "x".repeat(4000))
            val other = h.seed(2, course = "other")
            Ws(h).use { ws ->
                ws.hello()
                var frame: JsonObject
                do { frame = ws.ledger() } while (frame.getValue("events").jsonArray.isEmpty())
                val actual = LedgerJson.decodeFromJsonElement<LedgerFrame>(frame).events.toMutableList()
                val added = h.seed(1).single()
                runBlocking { h.pushes.emit("c") }
                val foreign = mutableListOf<Event>()
                while (actual.size < expected.size + 1 || foreign.size < other.size) {
                    val batch = LedgerJson.decodeFromJsonElement<LedgerFrame>(ws.ledger())
                    if (batch.course == "c") actual += batch.events else foreign += batch.events
                }
                assertEquals(expected + added, actual)
                assertEquals(other, foreign)
            }
        }
    }

    @Test fun noHelloSendsOnlyHintsAndOldTransportStillNoticesNewEvents() = runBlocking {
        Harness().use { h ->
            h.seed(600, "x".repeat(2500))
            Ws(h).use { ws ->
                val hint = ws.ledger()
                assertTrue(hint.getValue("events").jsonArray.isEmpty(), "unnegotiated tutor socket got history")
            }
            val transport = HttpLedgerTransport(h.baseUrl, "desktop", h.token)
            val observed = Channel<Long>(Channel.UNLIMITED)
            val watcher = launch(Dispatchers.IO) {
                transport.watch {
                    var after = 0L
                    do {
                        val page = transport.events("c", after)
                        after = page.throughSeq
                    } while (page.hasMore)
                    observed.send(after)
                }
            }
            try {
                withTimeout(10_000) { observed.receive() }
                val added = h.seed(1).single()
                h.pushes.emit("c")
                withTimeout(10_000) { while (observed.receive() != added.seq) Unit }
            } finally { watcher.cancelAndJoin(); transport.close(); observed.close() }
        }
    }

    @Test fun oversizedEventRequiresLosslessHttpCatchUpBeforeStreamingResumes() {
        Harness().use { h ->
            val first = h.seed(1)
            val huge = h.seed(1, "超大🙂".repeat(70_000))
            val tail = h.seed(1)
            Ws(h).use { ws ->
                ws.hello()
                assertEquals(first, ws.events(1).first)
                val notice = ws.ledger()
                assertTrue(notice.getValue("events").jsonArray.isEmpty())
                val recovery = notice.getValue("httpCatchUp").jsonObject
                assertEquals(first.single().seq, recovery.getValue("after").jsonPrimitive.long)
                assertEquals(tail.single().seq, recovery.getValue("throughSeq").jsonPrimitive.long)
                assertTrue(recovery.getValue("url").jsonPrimitive.content.startsWith("/v1/courses/c/events?after="))
                val recovered = h.page(first.single().seq, 1)
                assertEquals(huge, recovered.events)
                assertTrue(recovered.hasMore)
                assertEquals(tail, h.page(recovered.throughSeq).events)
                val added = h.seed(1).single()
                runBlocking { h.pushes.emit("c") }
                // Until the client acknowledges HTTP recovery, remain in hint mode.
                val nextNotice = ws.ledger()
                assertEquals(added.seq, nextNotice.getValue("httpCatchUp").jsonObject.getValue("throughSeq").jsonPrimitive.long)
                assertTrue(nextNotice.getValue("events").jsonArray.isEmpty())
                ws.hello(tail.single().seq)
                assertEquals(listOf(added), ws.events(1).first)
                println("TON1261 oversized_event_bytes=${LedgerJson.encodeToString(huge.single()).toByteArray().size} recovered_losslessly=true")
            }
        }
    }

    @Test fun cursorFromAnotherEpochCannotSkipRestoredHistory() {
        Harness().use { h ->
            val expected = h.seed(3)
            Ws(h).use { ws ->
                ws.socket.sendText("""{"t":"hello","epoch":"before-restore","ledgerAfter":{"c":9223372036854775807}}""", true)
                    .get(5, TimeUnit.SECONDS)
                assertEquals(expected, ws.events(expected.size).first)
            }
        }
    }
}
