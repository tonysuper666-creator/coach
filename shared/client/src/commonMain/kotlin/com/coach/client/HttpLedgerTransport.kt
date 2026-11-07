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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.decodeFromString

class DeviceAuthorizationException : IllegalStateException("Device pairing required")

/** 服务端永久拒收这条命令（格式、设备不符、超过大小上限）：重发也不会成功，不能挡住后面的同步。 */
class CommandRefused(val status: Int) : IllegalStateException("Command refused: HTTP $status")

class HttpLedgerTransport(baseUrl: String, private val deviceId: String,
    private val token: String) : LedgerTransport {
    private val baseUrl = baseUrl.trimEnd('/')
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
        // 409 带回执；400/403/413 没有回执体，是永久拒收；其余（5xx 等）是暂时故障，下次再传。
        when (response.status) {
            HttpStatusCode.Unauthorized -> throw DeviceAuthorizationException()
            HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.PayloadTooLarge -> throw CommandRefused(response.status.value)
            HttpStatusCode.Conflict -> {}
            else -> if (!response.status.isSuccess()) error("Command upload failed: HTTP ${response.status.value}")
        }
        return LedgerJson.decodeFromString(response.bodyAsText())
    }
    override suspend fun offlinePack(course: String, size: Int): OfflineChoicePack = LedgerJson.decodeFromString(
        client.get("$baseUrl/offline-pack") { parameter("size",size);parameter("courseId",course) }.bodyAsText())
    override suspend fun watch(onLedger: suspend () -> Unit) = watch({}, onLedger)
    override suspend fun watch(onOpen: () -> Unit, onLedger: suspend () -> Unit) {
        val url = baseUrl.replaceFirst("http","ws")+"/v1/ws"
        client.webSocket(urlString=url) {
            onOpen()
            for(frame in incoming) if(frame is Frame.Text) {
                val obj=runCatching { LedgerJson.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                if(obj.str("t") == "tutor.cards") PoolTrace.mark(obj.str("threadId"),"watch_cards_received")
                // Text/math deltas update the message stream, not the learning ledger.
                // Catch up on durable facts rather than issuing HTTP reads for every token.
                if(obj.str("t") in setOf("ledger","tutor.state","tutor.cards","tutor.feedback","msg.end") ||
                    (obj.str("t").isEmpty() && "epoch" in obj)) onLedger()
            }
        }
    }
    fun close() { client.close() }
}
