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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicReference

private const val LEDGER_FRAME_BYTES = 256 * 1024
private const val LEDGER_READ_PAGE = 128

class SuspensionDetector(private var wall: Long = System.currentTimeMillis(), private var mono: Long = System.nanoTime()) {
    fun sample(now: Long, nano: Long): Boolean {
        val wallDelta = now-wall;val monoDelta=(nano-mono)/1_000_000
        wall=now;mono=nano
        return monoDelta >= 30000 || wallDelta-monoDelta >= 30000
    }
}

fun Application.ledgerRoutes(actor: LedgerActor, pushes: MutableSharedFlow<String>, chat: ChatService? = null, adminDevice: String = "desktop", observation: UsageObservation? = null) =
    ledgerRoutes(actor, pushes, chat, adminDevice, observation) { }

// Test seam: pause an individual connection at the point its nonempty frame is sent.
internal fun Application.ledgerRoutes(actor: LedgerActor, pushes: MutableSharedFlow<String>, chat: ChatService?, adminDevice: String, observation: UsageObservation?, beforeLedgerFrame: suspend (String) -> Unit) {
    if (observation != null) observeUsage(observation)
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
            call.attributes.put(observedDevice, device)
        }
    }

    // Enforce management permission inside the matched route: encoded paths must
    // not bypass a string-prefix check in the application interceptor.
    suspend fun ApplicationCall.requireAdmin(): Boolean {
        if (request.header("X-Coach-Device") == adminDevice) return true
        respondText("Forbidden", status=HttpStatusCode.Forbidden)
        return false
    }
    routing {
        post("/v1/usage") {
            val observer = observation ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            // Bound streamed/chunked bodies as well as Content-Length before parsing.
            val channel = call.receiveChannel()
            val bytes = ByteArray(32_769)
            var size = 0
            while (size < bytes.size) {
                val n = channel.readAvailable(bytes,size,bytes.size-size)
                if (n == -1) break
                size += n
            }
            if (size > 32_768) return@post call.respond(HttpStatusCode.PayloadTooLarge)
            val events = runCatching { LedgerJson.parseToJsonElement(bytes.decodeToString(0,size)).jsonArray.map { it.jsonObject } }.getOrNull()
            if (events == null || events.size !in 1..50 || !events.all(com.coach.client.UsageVocabulary::valid)) return@post call.respond(HttpStatusCode.BadRequest)
            observer.accept(call.attributes[observedDevice], events)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/v1/courses/{course}/constructed") {
            val input=LedgerJson.parseToJsonElement(call.receiveText()).jsonObject
            val course=call.parameters["course"]!!
            val card=actor.execute { takeConstructed(course,input.str("kpId"),input.str("attemptId"),input.str("purpose","CLOSED_BOOK")) }
            pushes.emit(course)
            call.respondText(LedgerJson.encodeToString(card),ContentType.Application.Json)
        }
        get("/v1/courses/{course}/attempts/{attempt}") {
            val record=actor.execute { constructedRecord(call.parameters["course"]!!,call.parameters["attempt"]!!) }
            if(record==null) call.respond(HttpStatusCode.NotFound)
            else call.respondText(record.toString(),ContentType.Application.Json)
        }
        get("/v1/courses/{course}/sources") {
            val docs=actor.execute { browsableSources(call.parameters["course"]!!) }
            call.respondText(json("docs" to docs).toString(),ContentType.Application.Json)
        }
        get("/v1/courses/{course}/sources/{doc}/pages/{page}") {
            val page=call.parameters["page"]!!.toIntOrNull()
            val found=if(page==null) null else actor.execute { browsablePage(call.parameters["course"]!!,call.parameters["doc"]!!,page) }
            if(found==null) call.respond(HttpStatusCode.NotFound) else call.respondText(found.toString(),ContentType.Application.Json)
        }
        get("/v1/courses/{course}/pool") {
            val supply=actor.execute { constructedSupply(call.parameters["course"]!!) }
            call.respondText(json("constructed" to JsonObject(supply.mapValues { JsonPrimitive(it.value) })).toString(),ContentType.Application.Json)
        }
        post("/v1/prewarm") {
            val input=LedgerJson.parseToJsonElement(call.receiveText()).jsonObject
            val course=input.str("courseId")
            val kps=(input["kpIds"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            require(kps.size in 1..5)
            val result=checkNotNull(chat).prepareLessons(course,kps,input.flag("questionsOnly"))
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
            if (!call.requireAdmin()) return@post
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val code = actor.execute { createPairingCode() }
            call.respondText(json("code" to code.code, "expiresAt" to code.expiresAt).toString(), ContentType.Application.Json)
        }
        get("/v1/devices") {
            if (!call.requireAdmin()) return@get
            val devices = actor.execute { pairedDevices() }.map { json("deviceId" to it.id, "name" to it.name, "createdAt" to it.createdAt, "revokedAt" to it.revokedAt) }
            call.respondText(JsonArray(devices).toString(), ContentType.Application.Json)
        }
        delete("/v1/devices/{device}") {
            if (!call.requireAdmin()) return@delete
            val device = call.parameters["device"]!!
            if (device == adminDevice) { call.respond(HttpStatusCode.Forbidden); return@delete }
            actor.execute { revokeDevice(device) }
            pushes.emit("revoked")
            call.respond(HttpStatusCode.NoContent)

        }
        get("/offline-pack") {
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10
            val course = call.request.queryParameters["courseId"] ?: actor.execute { courses().singleOrNull() }
            if (size !in 1..30 || course.isNullOrBlank() || course == "__system__") { call.respond(HttpStatusCode.BadRequest); return@get }
            call.response.headers.append(HttpHeaders.CacheControl,"no-store")
            val pack = actor.execute { downloadOfflinePack(course,call.request.header("X-Coach-Device")!!,size) }
            call.respondText(LedgerJson.encodeToString(pack),ContentType.Application.Json)
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
        get("/v1/courses/{course}/today") {
            call.respondText(LedgerJson.encodeToString(actor.execute { todayProgress(call.parameters["course"]!!) }),ContentType.Application.Json)
        }
        get("/v1/courses/{course}/quick/{day}") {
            val day = call.parameters["day"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respondText(actor.execute { quickHistory(call.parameters["course"]!!,day) }.toString(),ContentType.Application.Json)
        }
        get("/v1/courses/{course}/journal/{day}") {
            val day = call.parameters["day"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respondText(LedgerJson.encodeToString(actor.execute { journalDay(call.parameters["course"]!!,day) }),ContentType.Application.Json)
        }
        post("/v1/courses/{course}/journal/{day}/summary") {
            val day = call.parameters["day"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val service = chat ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val result = service.journals.summarize(call.parameters["course"]!!,day)
            if (result == null) call.respond(HttpStatusCode.BadGateway)
            else {
                pushes.emit(call.parameters["course"]!!)
                call.respondText(LedgerJson.encodeToString(result),ContentType.Application.Json)
            }
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
            val socketTicket = observation?.coverage?.begin("ws")
            if (observation != null && socketTicket == null) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Service switching"))
                return@webSocket
            }
            try {
            val device=call.request.header("X-Coach-Device")!!
            val token=call.request.header("Authorization")!!.removePrefix("Bearer ")
            suspend fun stillAuthorized(): Boolean {
                if (actor.execute { authorize(device,token) }) return true
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,"Unauthorized"))
                return false
            }
            val cursors=mutableMapOf<String,Long>()
            val notified=mutableMapOf<String,Long>()
            val httpCourses=mutableSetOf<String>()
            var streaming=false
            var epoch=""
            suspend fun catchUp() {
                // Recheck revocation on established sockets before sending any learning data.
                if (!stillAuthorized()) return
                val (meta,heads)=actor.execute { hello() to courseHeads() }
                if(meta.epoch!=epoch) {
                    epoch=meta.epoch;cursors.clear();notified.clear();httpCourses.clear()
                    send(Frame.Text(LedgerJson.encodeToString(meta)))
                }
                suspend fun hint(course: String, head: Long) {
                    if(head <= (notified[course] ?: 0)) return
                    // Old HttpLedgerTransport watches `t=ledger` and pulls /events over HTTP.
                    // Retain that signal without pushing the same history into tutor sockets.
                    // Oversized events also use HTTP: remain in hint mode for this course until
                    // a subsequent hello acknowledges recovery via ledgerAfter. Never claim an
                    // empty frame delivered the missing event or stream past that gap.
                    val after=cursors[course] ?: 0
                    val notice=json("t" to "ledger","course" to course,"epoch" to epoch,
                        "events" to JsonArray(emptyList()),
                        "httpCatchUp" to json("after" to after,"throughSeq" to head,
                            "url" to "/v1/courses/${course.encodeURLPathPart()}/events?after=$after"))
                    if (!stillAuthorized()) return
                    val text=notice.toString()
                    // An unusually long course ID can itself exceed the envelope budget.
                    // Hello is also an existing HTTP-sync signal and has no course identifier.
                    send(Frame.Text(if(text.toByteArray(Charsets.UTF_8).size <= LEDGER_FRAME_BYTES) text else LedgerJson.encodeToString(meta)))
                    notified[course]=head
                }
                for((course,head) in heads) {
                    if(!streaming || course in httpCourses) { hint(course,head);continue }
                    // A fixed high-water mark bounds this pass even when writers keep appending.
                    // Subscription is established before the initial pass, so a queued push
                    // drains anything committed after this watermark in the next pass.
                    while((cursors[course] ?: 0) < head) {
                        val es=actor.execute {
                            if(hello().epoch != epoch) null
                            else events(course,cursors[course] ?: 0,LEDGER_READ_PAGE,head)
                        } ?: return
                        if(es.isEmpty()) break
                        val prefix=json("t" to "ledger","course" to course,"epoch" to epoch).toString().dropLast(1)+",\"events\":["
                        val envelopeBytes=prefix.toByteArray(Charsets.UTF_8).size+2
                        val encoded=mutableListOf<String>()
                        var bytes=envelopeBytes
                        var through=cursors[course] ?: 0
                        suspend fun flush(): Boolean {
                            if(encoded.isEmpty()) return true
                            if(!stillAuthorized()) return false
                            beforeLedgerFrame(device)
                            send(Frame.Text(prefix+encoded.joinToString(",")+"]}"))
                            cursors[course]=through
                            encoded.clear();bytes=envelopeBytes
                            return true
                        }
                        for(event in es) {
                            val text=LedgerJson.encodeToString(event)
                            val size=text.toByteArray(Charsets.UTF_8).size
                            if(bytes+size+(if(encoded.isEmpty()) 0 else 1) > LEDGER_FRAME_BYTES && !flush()) return
                            if(envelopeBytes+size > LEDGER_FRAME_BYTES) {
                                httpCourses.add(course)
                                hint(course,head)
                                break
                            }
                            bytes+=size+(if(encoded.isEmpty()) 0 else 1)
                            encoded.add(text);through=event.seq
                        }
                        if(!flush()) return
                        if(course in httpCourses) break
                    }
                }
            }
            // A slow socket must never suspend the shared push collector. The ledger is
            // durable; coalesced wakeups only ask this socket's drainer to read its heads.
            val wake=Channel<Unit>(Channel.CONFLATED)
            val pendingHello=AtomicReference<JsonObject?>(null)
            val sender=launch(start = CoroutineStart.UNDISPATCHED) { pushes.collect { wake.trySend(Unit) } }
            val drainer=launch {
                for (ignored in wake) {
                    pendingHello.getAndSet(null)?.let { message ->
                        val currentEpoch=actor.execute { hello().epoch }
                        // A cursor without its epoch cannot distinguish a restored ledger.
                        // Replay from zero so newly reused sequence numbers are not skipped.
                        val compatible=message.str("epoch") == currentEpoch
                        if(epoch!=currentEpoch) {
                            epoch=currentEpoch;cursors.clear();notified.clear();httpCourses.clear()
                            send(Frame.Text(LedgerJson.encodeToString(actor.execute { hello() })))
                        }
                        streaming=true
                        cursors.clear();notified.clear();httpCourses.clear()
                        if(compatible) for((course,seq) in (message["ledgerAfter"] as? JsonObject).orEmpty()) {
                            (seq as? JsonPrimitive)?.longOrNull?.let { cursors[course]=it.coerceAtLeast(0) }
                        }
                    }
                    catchUp()
                }
            }
            // 导师流式帧：所有连接共享（同一账号多设备看同一条回答）；重连靠 msg.resume 拿 snapshot。
            // Serialize resume snapshot creation + delivery against this socket's deltas.
            val firstWords = WsFirstWords(device, observation)
            val tutorSend = Mutex()
            val tutor=chat?.let { service -> launch(start = CoroutineStart.UNDISPATCHED) {
                service.hub.frames.collect { frame -> tutorSend.withLock {
                    if (stillAuthorized()) { send(Frame.Text(frame.toString())); firstWords.sent(frame) }
                } }
            } }
            val revocation = launch { while (isActive) { delay(1000); if (!stillAuthorized()) break } }
            try {
            wake.trySend(Unit)
            for(frame in incoming) if(frame is Frame.Text) {
                val receivedAt = System.nanoTime()
                if (!stillAuthorized()) break
                val message=runCatching { LedgerJson.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                firstWords.received(message)
                try { when(message.str("t")) {
                    "hello" -> {
                        // Latest hello replaces any pending hello while a prior replay is
                        // blocked. The drainer owns all cursor state and applies the latest.
                        pendingHello.set(message)
                        wake.trySend(Unit)
                    }
                    "msg.ask" -> {
                        val service=chat
                        val threadId=message.str("threadId")
                        val courseId=message.str("courseId")
                        val question=message.str("text")
                        if(service==null) Unit
                        else if(threadId.isBlank()||courseId.isBlank()||question.isBlank()) {
                            send(Frame.Text(LedgerJson.encodeToString(json("t" to "msg.error","msg" to message.str("clientMsgId"),"error" to "missing_field"))))
                        } else service.ask(threadId,courseId,question,message.str("pack"),message.str("clientMsgId").ifBlank { java.util.UUID.randomUUID().toString() },device,receivedAt)
                    }
                    "quick.ask" -> {
                        require(message.str("courseId").isNotBlank() && message.str("text").isNotBlank()) { "Missing quick-ask fields" }
                        chat?.quickAsk(message.str("courseId"),message.str("text"),message.str("pack"),message.str("clientMsgId").ifBlank { java.util.UUID.randomUUID().toString() },device,receivedAt)
                    }
                    "tutor.open" -> {
                        chat?.openTutor(message.str("threadId"),message.str("courseId"),message.str("kpId"),message.strings("conceptIds").toSet(),message.str("title"),message["review"]?.jsonPrimitive?.booleanOrNull == true)
                        tutorSend.withLock { chat?.history(message.str("courseId"),message.str("threadId"))?.let { send(Frame.Text(it.toString())) } }
                    }
                    "msg.history" -> tutorSend.withLock {
                        chat?.history(message.str("courseId"),message.str("threadId"))?.let { send(Frame.Text(it.toString())) }
                    }
                    "tutor.next", "tutor.test" -> chat?.nextStep(message.str("threadId"),message.str("courseId"),message.str("pack"),message.str("t") == "tutor.test",receivedAt=receivedAt)
                    "tutor.settings" -> chat?.configureTutor(message.str("threadId"),message.str("courseId"),
                        message["groupSize"]?.jsonPrimitive?.int, message["mode"]?.jsonPrimitive?.content?.let { TeachingMode.valueOf(it) })
                    "tutor.explain" -> chat?.explain(message.str("threadId"),message.str("courseId"),TeachingMode.valueOf(message.str("mode")),message.str("pack"),receivedAt)
                    "tutor.answer" -> chat?.submitGroup(message.str("threadId"),message.str("courseId"),message.str("groupId"),
                        (message["answers"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content },device,message.str("submissionId"))
                    "client.ttft" -> chat?.reportClientTtft(device,message.str("courseId"),message.str("threadId"),
                        message.str("kind"),message["ms"]?.jsonPrimitive?.long ?: -1,
                        message.str("model"),message["fallback"]?.jsonPrimitive?.content == "true")
                    "msg.cancel" -> chat?.cancel(message.str("msg"))
                    "msg.resume" -> {
                        tutorSend.withLock {
                            val snapshot=chat?.snapshot(message.str("msg"))
                            if(snapshot!=null) send(Frame.Text(snapshot.toString()))
                        }
                    }
                } } catch (error: IllegalArgumentException) {
                    send(Frame.Text(json("t" to "msg.error","threadId" to message.str("threadId"),"clientMsgId" to message.str("clientMsgId").ifBlank { null },"error" to (error.message ?: "invalid_command")).toString()))
                }
            } } finally { sender.cancelAndJoin();drainer.cancelAndJoin();wake.close();tutor?.cancelAndJoin();revocation.cancelAndJoin() }
            } finally { socketTicket?.let { observation?.coverage?.end(it) } }
        }
    }
}
