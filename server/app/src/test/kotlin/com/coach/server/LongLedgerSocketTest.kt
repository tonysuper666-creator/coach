package com.coach.server

import com.coach.client.TutorClient
import com.coach.client.TutorModel
import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import com.coach.domain.json
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** A few weeks of real study puts more than 1 MiB in one course; the tutor socket must survive it. */
class LongLedgerSocketTest {
    // TutorClient refuses any socket message above this size and drops the connection.
    private val clientLimit = 1_048_576
    private val events = 320

    @Test fun longLedgerKeepsTheTutorSocketConnected() {
        val root = Files.createTempDirectory("coach-long-ledger-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val token = "long-ledger-isolated-token-32-characters"
        val store = LedgerStore(root).also { it.enroll("phone", token) }
        val note = "复习笔记".repeat(350) // ~4 KB per event, ~1.3 MB in the course
        repeat(events) { store.append("course", "note_saved", json("noteId" to "n$it", "text" to note)) }
        val actor = LedgerActor(scope, store)
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), ChatService(actor, scope))
        }.apply { start(false) }
        val port = runBlocking { engine.engine.resolvedConnectors().single().port }
        val messages = LinkedBlockingQueue<String>()
        val partial = StringBuilder()
        val socket = HttpClient.newHttpClient().newWebSocketBuilder().header("X-Coach-Device", "phone").header("Authorization", "Bearer $token")
            .buildAsync(URI("ws://127.0.0.1:$port/v1/ws"), object : WebSocket.Listener {
                override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    partial.append(data); if (last) { messages.add(partial.toString()); partial.clear() }
                    ws.request(1); return null
                }
            }).get(5, TimeUnit.SECONDS)
        val tutor = TutorClient("http://127.0.0.1:$port", "phone", token, TutorModel(System::currentTimeMillis))
        try {
            var delivered = 0
            var largest = 0
            while (delivered < events) {
                val message = assertNotNull(messages.poll(10, TimeUnit.SECONDS), "catch-up stopped after $delivered events")
                largest = maxOf(largest, message.encodeToByteArray().size)
                val frame = LedgerJson.parseToJsonElement(message).jsonObject
                if (frame["t"]?.jsonPrimitive?.content == "ledger") delivered += frame["events"]!!.jsonArray.size
            }
            assertTrue(largest <= clientLimit, "catch-up sent a $largest-byte message")
            tutor.start(scope)
            runBlocking {
                withTimeout(15_000) { while (!tutor.model.state.value.connected) delay(20) }
                // A rejected catch-up drops the socket moments after the pong; hold long enough to see it.
                repeat(150) { assertTrue(tutor.model.state.value.connected, "tutor socket dropped"); delay(20) }
            }
            println("LONG_LEDGER events=$delivered largestMessage=$largest tutorConnected=true")
        } finally {
            tutor.close(); socket.abort()
            runBlocking { engine.stopSuspend(0, 500); actor.close() }; scope.cancel(); root.toFile().deleteRecursively()
        }
    }
}
