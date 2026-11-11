package com.coach.server

import com.coach.client.TutorClient
import com.coach.client.TutorModel
import com.coach.domain.agentThreadId
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocketRaw
import io.ktor.websocket.Frame
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AgentRunSocketTest {
    @Test fun agentConnectionCannotSendToLegacyServer(): Unit = runBlocking {
        val received = AtomicInteger()
        val connected = CompletableDeferred<Unit>()
        val engine = embeddedServer(Netty, host="127.0.0.1", port=0) {
            install(WebSockets)
            routing { webSocketRaw("/v1/ws") {
                send(Frame.Text("""{"epoch":"legacy","lastSeq":0}"""))
                connected.complete(Unit)
                for (frame in incoming) when (frame) {
                    is Frame.Ping -> send(Frame.Pong(frame.data))
                    is Frame.Text -> received.incrementAndGet()
                    else -> Unit
                }
            } }
        }.apply { start(wait=false) }
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val model = TutorModel(System::currentTimeMillis)
        val client = TutorClient("http://127.0.0.1:${engine.engine.resolvedConnectors().first().port}",
            "device","t".repeat(43),model,agentRunId="oppo")
        try {
            client.start(scope)
            withTimeout(5_000) { connected.await() }
            delay(300)
            assertFalse(model.state.value.connected)
            assertFalse(client.open(agentThreadId("oppo","thread"),"course","kp",emptyList(),"title"))
            assertEquals(0,received.get())
        } finally { client.close();scope.cancel();engine.stop(0,200) }
    }
}
