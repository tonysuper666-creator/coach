package com.coach.server

import com.coach.client.PairingClient
import com.coach.client.HttpLedgerTransport
import com.coach.client.PairedCredentials
import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import com.coach.domain.json
import com.coach.domain.str
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.util.concurrent.*
import kotlin.test.*

class PairingSecurityTest {
    private class Harness : AutoCloseable {
        val root = Files.createTempDirectory("coach-pair-http-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val admin = "isolated-desktop-token-32-characters"
        val actor = LedgerActor(scope, LedgerStore(root).also { it.enroll("desktop", admin) })
        val chat = ChatService(actor,scope)
        val engine = embeddedServer(Netty,host="127.0.0.1",port=0) { ledgerRoutes(actor,MutableSharedFlow(extraBufferCapacity=8),chat) }.apply { start(false) }
        val port get() = runBlocking { engine.engine.resolvedConnectors().single().port }
        val base get() = "http://127.0.0.1:$port"
        val client = HttpClient.newHttpClient()
        fun request(path: String, method: String="GET", device: String?=null, token: String?=null, body: String=""): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI(base+path)).timeout(java.time.Duration.ofSeconds(5))
            if (device != null) builder.header("X-Coach-Device",device)
            if (token != null) builder.header("Authorization",token)
            return client.send(builder.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString())
        }
        fun code(): String {
            val response = request("/v1/pairing-code","POST","desktop","Bearer $admin")
            assertEquals(200,response.statusCode())
            return LedgerJson.parseToJsonElement(response.body()).jsonObject.str("code")
        }
        fun pair(): PairedCredentials = runBlocking {
            val client = PairingClient(base)
            try { client.pair(code(),"synthetic phone") } finally { client.close() }
        }
        override fun close() {
            runBlocking { engine.stopSuspend(0,500); actor.close() }; scope.cancel(); root.toFile().deleteRecursively()
        }
    }

    @Test fun pairsThenUsesHeadersForHttpAndWsAndRejectsReplay() = Harness().use { h ->
        val code=h.code()
        val response=h.request("/v1/pair","POST",body=json("code" to code,"name" to "test phone").toString())
        assertEquals(200,response.statusCode()); assertEquals("no-store",response.headers().firstValue("Cache-Control").get())
        val credential=LedgerJson.parseToJsonElement(response.body()).jsonObject
        val device=credential.str("deviceId"); val token=credential.str("token")
        assertEquals(401,h.request("/v1/pair","POST",body=json("code" to code,"name" to "replay").toString()).statusCode())
        runBlocking {
            val transport=HttpLedgerTransport(h.base,device,token)
            try { assertTrue(transport.hello(null).epoch.isNotBlank()) } finally { transport.close() }
        }
        val socket=h.client.newWebSocketBuilder().header("X-Coach-Device",device).header("Authorization","Bearer $token")
            .buildAsync(URI("ws://127.0.0.1:${h.port}/v1/ws"),object: WebSocket.Listener {}).get(5,TimeUnit.SECONDS)
        socket.abort()
    }

    @Test fun loopbackRejectsMissingWrongRevokedAndUrlTokensForBothTransports() = Harness().use { h ->
        val phone=h.pair()
        val cases=listOf(Triple<String?,String?,String>(null,null,""),Triple(phone.deviceId,"Bearer wrong-token-32-characters-minimum",""),
            Triple(phone.deviceId,phone.token,""), Triple("unknown","Bearer ${phone.token}",""),
            Triple(phone.deviceId,null,"?token=${phone.token}"),Triple(phone.deviceId,"Bearer ${phone.token}","?access_token=${phone.token}"))
        for ((device,header,query) in cases) {
            assertEquals(401,h.request("/v1/hello$query",device=device,token=header).statusCode())
            val builder=h.client.newWebSocketBuilder()
            if(device!=null) builder.header("X-Coach-Device",device)
            if(header!=null) builder.header("Authorization",header)
            val error=assertFailsWith<ExecutionException> { builder.buildAsync(URI("ws://127.0.0.1:${h.port}/v1/ws$query"),object: WebSocket.Listener {}).get(5,TimeUnit.SECONDS) }
            assertEquals(401,(error.cause as WebSocketHandshakeException).response.statusCode())
        }
        assertEquals(204,h.request("/v1/devices/${phone.deviceId}","DELETE","desktop","Bearer ${h.admin}").statusCode())
        assertEquals(401,h.request("/v1/hello",device=phone.deviceId,token="Bearer ${phone.token}").statusCode())
        val error=assertFailsWith<ExecutionException> { h.client.newWebSocketBuilder().header("X-Coach-Device",phone.deviceId).header("Authorization","Bearer ${phone.token}")
            .buildAsync(URI("ws://127.0.0.1:${h.port}/v1/ws"),object: WebSocket.Listener {}).get(5,TimeUnit.SECONDS) }
        assertEquals(401,(error.cause as WebSocketHandshakeException).response.statusCode())
    }

    @Test fun phoneCannotManageDevicesAndRevokedOpenSocketStopsTutorFrames() = Harness().use { h ->
        val phone=h.pair()
        for ((path,method) in listOf("/v1/pairing-code" to "POST","/v1/devices" to "GET","/v1/devices/desktop" to "DELETE"))
            assertEquals(403,h.request(path,method,phone.deviceId,"Bearer ${phone.token}").statusCode())
        val closed=CompletableFuture<Int>()
        val frames=LinkedBlockingQueue<String>()
        val socket=h.client.newWebSocketBuilder().header("X-Coach-Device",phone.deviceId).header("Authorization","Bearer ${phone.token}")
            .buildAsync(URI("ws://127.0.0.1:${h.port}/v1/ws"),object: WebSocket.Listener {
                override fun onText(ws: WebSocket,data: CharSequence,last: Boolean): CompletionStage<*>? { frames.add(data.toString()); ws.request(1); return null }
                override fun onClose(ws: WebSocket,status: Int,reason: String): CompletionStage<*>? { closed.complete(status); return null }
            }).get(5,TimeUnit.SECONDS)
        try {
            assertNotNull(frames.poll(5,TimeUnit.SECONDS)) // initial authorized hello
            assertEquals(204,h.request("/v1/devices/${phone.deviceId}","DELETE","desktop","Bearer ${h.admin}").statusCode())
            runBlocking { h.chat.hub.frames.emit(json("t" to "msg.delta","text" to "private-after-revoke")) }
            assertEquals(1008,closed.get(5,TimeUnit.SECONDS))
            assertFalse(frames.any { it.contains("private-after-revoke") })
        } finally { socket.abort() }
    }

    @Test fun boundsUnauthenticatedBodiesAndHidesFailureReasons() = Harness().use { h ->
        assertEquals(413,h.request("/v1/pair","POST",body="x".repeat(1025)).statusCode())
        val missing=h.request("/v1/pair","POST",body="{}")
        h.code()
        val wrong=h.request("/v1/pair","POST",body="{}")
        assertEquals(401,missing.statusCode()); assertEquals(missing.body(),wrong.body())
        assertEquals(401,h.request("/unregistered-route").statusCode())
    }
}
