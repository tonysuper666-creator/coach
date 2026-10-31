package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

class SuspensionDetector(private var wall: Long = System.currentTimeMillis(), private var mono: Long = System.nanoTime()) {
    fun sample(now: Long, nano: Long): Boolean {
        val wallDelta = now-wall;val monoDelta=(nano-mono)/1_000_000
        wall=now;mono=nano
        return monoDelta >= 30000 || wallDelta-monoDelta >= 30000
    }
}

fun Application.ledgerRoutes(actor: LedgerActor, pushes: MutableSharedFlow<String>, chat: ChatService? = null) {
    install(WebSockets) { pingPeriod = kotlin.time.Duration.parse("5s");timeout=kotlin.time.Duration.parse("15s");maxFrameSize=1_048_576 }
    intercept(ApplicationCallPipeline.Call) {
        val device = call.request.header("X-Coach-Device")
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")
        if(device == null || token == null || !actor.execute { authorize(device,token) }) {
            call.respondText("Unauthorized",status=HttpStatusCode.Unauthorized)
            finish()
        }
    }
    routing {
        get("/v1/hello") { call.respondText(LedgerJson.encodeToString(actor.execute { hello() }),ContentType.Application.Json) }
        get("/v1/courses/{course}/events") {
            val course=call.parameters["course"]!!
            val after=call.request.queryParameters["after"]?.toLongOrNull() ?: 0
            val limit=call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
            call.respondText(LedgerJson.encodeToString(actor.execute { page(course,after,limit) }),ContentType.Application.Json)
        }
        get("/v1/courses/{course}/snapshot") {
            call.respondText(LedgerJson.encodeToString(actor.execute { snapshot(call.parameters["course"]!!) }),ContentType.Application.Json)
        }
        post("/v1/commands") {
            if((call.request.header("Content-Length")?.toLongOrNull() ?: 0)>1_048_576) { call.respond(HttpStatusCode.PayloadTooLarge);return@post }
            val text=call.receiveText()
            if(text.length>1_048_576) { call.respond(HttpStatusCode.PayloadTooLarge);return@post }
            val value=try { LedgerJson.parseToJsonElement(text) } catch(e: Exception) { call.respond(HttpStatusCode.BadRequest);return@post }
            val commands=try { if(value is JsonArray) value.map { LedgerJson.decodeFromJsonElement<WireCommand>(it) } else listOf(LedgerJson.decodeFromJsonElement<WireCommand>(value)) }
                catch(e: Exception) { call.respond(HttpStatusCode.BadRequest);return@post }
            if(commands.size>100 || commands.any { it.deviceId != call.request.header("X-Coach-Device") }) { call.respond(HttpStatusCode.Forbidden);return@post }
            val receipts = commands.map { actor.execute { command(it) } }
            for((command,receipt) in commands.zip(receipts)) if(receipt.eventSeqs.isNotEmpty()) {
                val course=LedgerJson.decodeFromString<CommandBody>(command.payloadBytes).courseId
                pushes.emit(course)
            }
            val response=if(value is JsonArray) LedgerJson.encodeToString(receipts) else LedgerJson.encodeToString(receipts.single())
            val status=if(value !is JsonArray && receipts.single().status=="CONFLICT") HttpStatusCode.Conflict else HttpStatusCode.OK
            call.respondText(response,ContentType.Application.Json,status)
        }
        webSocket("/v1/ws") {
            val device=call.request.header("X-Coach-Device")!!
            val token=call.request.header("Authorization")!!.removePrefix("Bearer ")
            val cursors=mutableMapOf<String,Long>()
            var epoch=""
            suspend fun catchUp() {
                // Recheck revocation on established sockets before sending any learning data.
                if(!actor.execute { authorize(device,token) }) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,"revoked"));return }
                val meta=actor.execute { hello() }
                if(meta.epoch!=epoch) { epoch=meta.epoch;cursors.clear();send(Frame.Text(LedgerJson.encodeToString(meta))) }
                for(course in actor.execute { courses() }) {
                    val es=actor.execute { events(course,cursors[course] ?: 0) }
                    if(es.isNotEmpty()) { send(Frame.Text(LedgerJson.encodeToString(LedgerFrame(course=course,epoch=epoch,events=es))));cursors[course]=es.last().seq }
                }
            }
            catchUp()
            // Receive and push concurrently; StateFlow is not a durable ledger, reconnect catches up from SQLite.
            val sender=launch { pushes.collect { catchUp() } }
            // 导师流式帧：所有连接共享（同一账号多设备看同一条回答）；重连靠 msg.resume 拿 snapshot。
            // Serialize resume snapshot creation + delivery against this socket's deltas.
            val tutorSend = Mutex()
            val tutor=chat?.let { service -> launch { service.hub.frames.collect { frame -> tutorSend.withLock { send(Frame.Text(frame.toString())) } } } }
            try { for(frame in incoming) if(frame is Frame.Text) {
                val message=runCatching { LedgerJson.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                when(message.str("t")) {
                    "hello" -> {
                        for((course,seq) in (message["ledgerAfter"] as? JsonObject).orEmpty()) cursors[course]=seq.jsonPrimitive.long
                        catchUp()
                    }
                    "msg.ask" -> {
                        val service=chat
                        val threadId=message.str("threadId")
                        val courseId=message.str("courseId")
                        val question=message.str("text").trim()
                        if(service==null) Unit
                        else if(threadId.isBlank()||courseId.isBlank()||question.isEmpty()) {
                            send(Frame.Text(LedgerJson.encodeToString(json("t" to "msg.error","msg" to message.str("clientMsgId"),"error" to "missing_field"))))
                        } else service.ask(threadId,courseId,question,message.str("pack"))
                    }
                    "msg.cancel" -> chat?.cancel(message.str("msg"))
                    "msg.resume" -> {
                        tutorSend.withLock {
                            val snapshot=chat?.snapshot(message.str("msg"))
                            if(snapshot!=null) send(Frame.Text(snapshot.toString()))
                        }
                    }
                }
            } } finally { sender.cancelAndJoin();tutor?.cancelAndJoin() }
        }
    }
}
