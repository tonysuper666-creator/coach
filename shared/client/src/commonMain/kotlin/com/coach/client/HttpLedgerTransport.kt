package com.coach.client
import com.coach.domain.*
import com.coach.protocol.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class DeviceAuthorizationException : IllegalStateException("Device pairing required")

class HttpLedgerTransport(private val baseUrl: String, private val deviceId: String,
    private val token: String) : LedgerTransport {
    init { validateCoachBaseUrl(baseUrl) }
    private val client = HttpClient(CIO) {
        followRedirects = false
        install(WebSockets) { pingIntervalMillis=5000 }
        install(HttpTimeout) { requestTimeoutMillis=10000;connectTimeoutMillis=3000;socketTimeoutMillis=10000 }
        expectSuccess=true
        defaultRequest { header("Authorization","Bearer $token");header("X-Coach-Device",deviceId) }
    }
    override suspend fun hello(epoch: String?): Hello = try {
        LedgerJson.decodeFromString(client.get("$baseUrl/v1/hello") { if(epoch!=null) parameter("epoch",epoch) }.bodyAsText())
    } catch (error: ClientRequestException) {
        if (error.response.status == HttpStatusCode.Unauthorized) throw DeviceAuthorizationException()
        throw error
    }
    override suspend fun snapshot(course: String): Snapshot = LedgerJson.decodeFromString(client.get("$baseUrl/v1/courses/${course.encodeURLPathPart()}/snapshot").bodyAsText())
    override suspend fun events(course: String, after: Long): EventsPage = LedgerJson.decodeFromString(client.get("$baseUrl/v1/courses/${course.encodeURLPathPart()}/events") { parameter("after",after) }.bodyAsText())
    override suspend fun command(command: WireCommand): Receipt {
        val response = client.post("$baseUrl/v1/commands") { expectSuccess=false; contentType(ContentType.Application.Json);setBody(LedgerJson.encodeToString(command)) }
        return LedgerJson.decodeFromString(response.bodyAsText())
    }
    override suspend fun watch(onLedger: suspend () -> Unit) {
        val url = baseUrl.replaceFirst("http","ws")+"/v1/ws"
        client.webSocket(urlString=url) { for(frame in incoming) if(frame is Frame.Text) onLedger() }
    }
    fun close() { client.close() }
}
