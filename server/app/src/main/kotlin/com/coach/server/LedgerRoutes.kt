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
import io.ktor.utils.io.readAvailable
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

fun Application.ledgerRoutes(actor: LedgerActor, pushes: MutableSharedFlow<String>, chat: ChatService? = null, adminDevice: String = "desktop") {
    install(WebSockets) { pingPeriod = kotlin.time.Duration.parse("5s");timeout=kotlin.time.Duration.parse("15s");maxFrameSize=1_048_576 }
    intercept(ApplicationCallPipeline.Call) {
        // Pairing is the only unauthenticated operation, and only this exact POST.
        val pairing = call.request.path() == "/v1/pair" && call.request.httpMethod == HttpMethod.Post
        if (call.request.queryParameters.names().any { it.lowercase() in setOf("token", "access_token", "authorization", "code") }) {
            call.respondText("Unauthorized", status=HttpStatusCode.Unauthorized)
            finish()
            return@intercept
        }
        if (!pairing) {
            val device = call.request.header("X-Coach-Device")
            val authorization = call.request.header("Authorization")
            val token = authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            if (device == null || token == null || call.request.headers.getAll("Authorization")?.size != 1 ||
                !actor.execute { authorize(device,token) }) {
                call.respondText("Unauthorized",status=HttpStatusCode.Unauthorized)
                finish()
                return@intercept
            }
            // A phone token cannot mint codes, enumerate devices, or revoke another phone.
            if (call.request.path().startsWith("/v1/devices") || call.request.path() == "/v1/pairing-code") {
                if (device != adminDevice) {
                    call.respondText("Forbidden", status=HttpStatusCode.Forbidden)
                    finish()
                }
            }
        }
    }

    routing {
        post("/v1/prewarm") {
            val input=LedgerJson.parseToJsonElement(call.receiveText()).jsonObject
            val course=input.str("courseId")
            val kps=(input["kpIds"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            require(kps.size in 1..5)
            val result=checkNotNull(chat).prepareLessons(course,kps)
            call.respondText(result.toString(),ContentType.Application.Json)
        }
        post("/v1/pair") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            // Bound the unauthenticated body, including chunked requests.
            val input = call.receiveChannel()
            val buffer = ByteArray(1025)
            var size = 0
            while (size < buffer.size) {
                val count = input.readAvailable(buffer, size, buffer.size-size)
                if (count == -1) break
                size += count
            }
            if (size > 1024) { call.respond(HttpStatusCode.PayloadTooLarge); return@post }
            val body = runCatching { LedgerJson.parseToJsonElement(buffer.decodeToString(0,size)).jsonObject }.getOrNull()
            val credential = actor.execute { pair(body?.str("code").orEmpty(), body?.str("name").orEmpty()) }
            if (credential == null) call.respondText("Pairing rejected", status=HttpStatusCode.Unauthorized)
            else call.respondText(json("deviceId" to credential.deviceId, "token" to credential.token).toString(), ContentType.Application.Json)
        }
        post("/v1/pairing-code") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val code = actor.execute { createPairingCode() }
            call.respondText(json("code" to code.code, "expiresAt" to code.expiresAt).toString(), ContentType.Application.Json)
        }
        get("/v1/devices") {
            val devices = actor.execute { pairedDevices() }.map { json("deviceId" to it.id, "name" to it.name, "createdAt" to it.createdAt, "revokedAt" to it.revokedAt) }
            call.respondText(JsonArray(devices).toString(), ContentType.Application.Json)
        }
        delete("/v1/devices/{device}") {
            val device = call.parameters["device"]!!
            if (device == adminDevice) { call.respond(HttpStatusCode.Forbidden); return@delete }
            actor.execute { revokeDevice(device) }
            pushes.emit("revoked")
            call.respond(HttpStatusCode.NoContent)

        }
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
            suspend fun stillAuthorized(): Boolean {
                if (actor.execute { authorize(device,token) }) return true
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,"Unauthorized"))
                return false
            }
            val cursors=mutableMapOf<String,Long>()
            var epoch=""
            suspend fun catchUp() {
                // Recheck revocation on established sockets before sending any learning data.
                if (!stillAuthorized()) return
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
            val tutor=chat?.let { service -> launch { service.hub.frames.collect { frame -> tutorSend.withLock { if (stillAuthorized()) send(Frame.Text(frame.toString())) } } } }
            val revocation = launch { while (isActive) { delay(1000); if (!stillAuthorized()) break } }
            try { for(frame in incoming) if(frame is Frame.Text) {
                if (!stillAuthorized()) break
                val message=runCatching { LedgerJson.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                try { when(message.str("t")) {
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
                    "tutor.open" -> chat?.openTutor(message.str("threadId"),message.str("courseId"),message.str("kpId"),message.strings("conceptIds").toSet(),message.str("title"))
                    "tutor.next", "tutor.test" -> chat?.nextStep(message.str("threadId"),message.str("courseId"),message.str("pack"),message.str("t") == "tutor.test")
                    "tutor.settings" -> chat?.configureTutor(message.str("threadId"),message.str("courseId"),
                        message["groupSize"]?.jsonPrimitive?.int, message["mode"]?.jsonPrimitive?.content?.let { TeachingMode.valueOf(it) })
                    "tutor.explain" -> chat?.explain(message.str("threadId"),message.str("courseId"),TeachingMode.valueOf(message.str("mode")),message.str("pack"))
                    "tutor.answer" -> chat?.submitGroup(message.str("threadId"),message.str("courseId"),message.str("groupId"),
                        (message["answers"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content },device,message.str("submissionId"))
                    "msg.cancel" -> chat?.cancel(message.str("msg"))
                    "msg.resume" -> {
                        tutorSend.withLock {
                            val snapshot=chat?.snapshot(message.str("msg"))
                            if(snapshot!=null) send(Frame.Text(snapshot.toString()))
                        }
                    }
                } } catch (error: IllegalArgumentException) {
                    send(Frame.Text(json("t" to "msg.error","threadId" to message.str("threadId"),"error" to (error.message ?: "invalid_command")).toString()))
                }
            } } finally { sender.cancelAndJoin();tutor?.cancelAndJoin();revocation.cancelAndJoin() }
        }
    }
}
