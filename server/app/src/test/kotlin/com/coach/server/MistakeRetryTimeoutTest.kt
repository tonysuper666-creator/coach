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
import kotlin.test.*

class MistakeRetryTimeoutTest {
    @Test fun ignoredRequestReportsTimeout() = exercise(null)
    @Test fun cardsCancelTimeout() = exercise("tutor.cards")
    @Test fun serverErrorCancelsTimeout() = exercise("msg.error")

    private fun exercise(reply: String?) = runBlocking {
        val received = CompletableDeferred<Unit>()
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing { webSocketRaw("/v1/ws") {
                for (frame in incoming) when (frame) {
                    is Frame.Ping -> send(Frame.Pong(frame.data))
                    is Frame.Text -> {
                        val obj = Json.parseToJsonElement(frame.readText()).jsonObject
                        if (obj["t"]?.jsonPrimitive?.content == "tutor.retryMistakes") {
                            received.complete(Unit)
                            if (reply != null) send(Frame.Text(buildJsonObject {
                                put("t", reply); put("threadId", "review:C:kp")
                                put("courseId", "C"); put("groupId", "g"); put("cards", JsonArray(emptyList()))
                                put("error", "synthetic rejection")
                            }.toString()))
                        }
                    }
                    else -> {}
                }
            } }
        }.apply { start(wait = false) }
        val port = engine.engine.resolvedConnectors().first().port
        check(port !in setOf(8790, 8791, 18791, 3790))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = TutorModel(System::currentTimeMillis)
        val client = TutorClient("http://127.0.0.1:$port", "synthetic", "synthetic", model, retryTimeoutMillis = 400)
        try {
            client.start(scope)
            withTimeout(10_000) { while (!model.state.value.connected) delay(10) }
            model.onSent("review:C:kp", "retry", null)
            assertTrue(client.retryMistakes("review:C:kp", "C"))
            withTimeout(5_000) { received.await() }
            if (reply == null) {
                withTimeout(5_000) { while (model.state.value.threads["review:C:kp"]?.error?.contains("超时") != true) delay(10) }
            } else {
                delay(900)
                assertFalse(model.state.value.threads["review:C:kp"]?.error.orEmpty().contains("超时"))
            }
        } finally { client.close(); scope.cancel(); engine.stop(0, 500) }
    }
}
