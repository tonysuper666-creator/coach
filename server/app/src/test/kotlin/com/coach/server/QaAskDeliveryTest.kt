package com.coach.server

import com.coach.client.TutorClient
import com.coach.client.TutorModel
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocketRaw
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class QaAskDeliveryTest {
    @Test fun exhaustedRetriesMustReportAnUndeliveredQuestion() = runBlocking {
        val connections = AtomicInteger()
        val attempts = AtomicInteger()
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing { webSocketRaw("/v1/ws") {
                connections.incrementAndGet()
                for (frame in incoming) when (frame) {
                    is Frame.Ping -> send(Frame.Pong(frame.data))
                    is Frame.Text -> if (Json.parseToJsonElement(frame.readText()).jsonObject["t"]?.jsonPrimitive?.content == "msg.ask") {
                        attempts.incrementAndGet()
                        return@webSocketRaw // Connection dies before accepting/acknowledging the ask.
                    }
                    else -> {}
                }
            } }
        }.apply { start(wait = false) }
        val port = engine.engine.resolvedConnectors().first().port
        check(port !in setOf(8790, 8791, 18791))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = TutorModel(System::currentTimeMillis)
        val client = TutorClient("http://127.0.0.1:$port", "desktop", "qa-isolated-token", model)
        try {
            client.start(scope)
            withTimeout(10_000) { while (!model.state.value.connected) delay(20) }
            assertTrue(client.ask("t1", "course", "什么是极限", "pack", "qa-1"))
            withTimeout(25_000) { while (connections.get() < 5 || !model.state.value.connected) delay(20) }
            delay(300)
            val view = model.state.value.threads.getValue("t1")
            println("QA attempts=${attempts.get()} connections=${connections.get()} connected=${model.state.value.connected} error=${view.error} rows=${view.items}")
            assertEquals(4, attempts.get())
            assertFalse(view.error.isNullOrBlank(), "发问在重发耗尽后被静默移除，界面仍显示已发送，没有失败或重试提示")
        } finally { client.close(); engine.stop(0, 500); scope.cancel() }
    }

    @Test fun rejectedAskMustNotRemoveOtherUnconfirmedAsksInItsThread() = runBlocking {
        val connections = AtomicInteger()
        val received = Collections.synchronizedList(mutableListOf<String>())
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing { webSocketRaw("/v1/ws") {
                val connection = connections.incrementAndGet()
                for (frame in incoming) when (frame) {
                    is Frame.Ping -> send(Frame.Pong(frame.data))
                    is Frame.Text -> {
                        val obj = Json.parseToJsonElement(frame.readText()).jsonObject
                        if (obj["t"]?.jsonPrimitive?.content != "msg.ask") continue
                        val id = obj.getValue("clientMsgId").jsonPrimitive.content
                        received += "$connection:$id"
                        if (connection == 1 && id == "qa-2") {
                            // LedgerRoutes' IllegalArgumentException response identifies only the
                            // thread. It does not acknowledge receipt of every ask in that thread.
                            send(Frame.Text("""{"t":"msg.error","threadId":"t1","error":"invalid_command"}"""))
                            delay(100)
                            return@webSocketRaw
                        }
                    }
                    else -> {}
                }
            } }
        }.apply { start(wait = false) }
        val port = engine.engine.resolvedConnectors().first().port
        check(port !in setOf(8790, 8791, 18791))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = TutorModel(System::currentTimeMillis)
        val client = TutorClient("http://127.0.0.1:$port", "desktop", "qa-isolated-token", model)
        try {
            client.start(scope)
            withTimeout(10_000) { while (!model.state.value.connected) delay(20) }
            assertTrue(client.ask("t1", "course", "问题一", "pack", "qa-1"))
            assertTrue(client.ask("t1", "course", "问题二", "pack", "qa-2"))
            withTimeout(10_000) { while (connections.get() < 2 || !model.state.value.connected) delay(20) }
            delay(300)
            println("QA rejectedAsk received=$received")
            assertTrue("2:qa-2" in received, "qa-1 的错误清掉了 qa-2 的补发：$received")
        } finally { client.close(); engine.stop(0, 500); scope.cancel() }
    }
}
