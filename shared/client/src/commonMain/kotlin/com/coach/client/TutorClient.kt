package com.coach.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.header
import io.ktor.websocket.Frame

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * 到 Mac 服务端 `/v1/ws` 的导师连接（与账本同步各用一条 socket，服务端按帧广播）。
 * 断线就是断线：没连上时 [send] 直接返回 false，由界面如实提示，不排队、不假装已发出。
 * 已交给连接、但服务端还没回 `msg.user` 确认的发问，会在下一条连接上补发（服务端按 clientMsgId 去重）。
 */
class TutorClient(
    baseUrl: String,
    private val deviceId: String,
    private val token: String,
    val model: TutorModel,
    val telemetry: UsageTelemetry? = null,
) {
    init { validateCoachBaseUrl(baseUrl); model.bindDevice(deviceId) }
    private val url = baseUrl.replaceFirst("http", "ws").trimEnd('/') + "/v1/ws"
    private val http = HttpClient(CIO) {
        followRedirects = false
        install(WebSockets) { maxFrameSize = MAX_SOCKET_FRAME_BYTES }
        install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = 5_000 }
        defaultRequest { header("Authorization", "Bearer $token"); header("X-Coach-Device", deviceId) }
    }
    @kotlin.concurrent.Volatile private var live: Channel<String>? = null
    private val lifecycle = Any()
    private var running: Job? = null
    private var closed = false
    // clientMsgId → 已交给连接、还没被服务端确认的发问。连接断了，出站缓冲里的帧随之丢掉；只有 msg.user 才证明送达。
    private data class Unconfirmed(val threadId: String, val frame: String, var resends: Int = 0)
    private val unconfirmed = LinkedHashMap<String, Unconfirmed>()
    // null means no outcome yet; retries while offline must not create new events.
    private var observedConnection: Boolean? = null

    private fun observeConnection(connected: Boolean) = synchronized(lifecycle) {
        if (observedConnection != connected) {
            if (!connected) telemetry?.record("disconnect", "connection")
            else if (observedConnection == false) telemetry?.record("reconnect", "connection")
            observedConnection = connected
        }
    }

    // 在线缺号由模型发现，续传帧走当前连接的发送队列（非阻塞 trySend，满了/断线返回 false 由模型重试）。
    init { model.resumeSender = { id -> send(resumeFrame(id)) } }
    private fun resumeFrame(id: String) = buildJsonObject { put("t", "msg.resume"); put("msg", id) }

    init {
        model.addTtftReporter { sample -> send(buildJsonObject {
            put("t", "client.ttft"); put("threadId", sample.threadId); put("msg", sample.messageId)
            put("kind", sample.kind); put("ms", sample.ms); put("model", sample.model); put("fallback", sample.fallback)
            put("courseId", sample.courseId)
        }) }
    }

    // One owner runs every connection attempt and its children. A second start is idempotent,
    // including while cancellation is still unwinding: an old reader cannot outlive its successor.
    fun start(scope: CoroutineScope): Job = synchronized(lifecycle) {
        check(!closed) { "TutorClient is closed" }
        running?.takeUnless { it.isCompleted } ?: scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            telemetry?.record("open")
            val reporting = telemetry?.start(this)
            val backoff = ReconnectBackoff()
            try {
                while (isActive) {
                    val queue = Channel<String>(capacity = 64)
                    var readyAt: TimeSource.Monotonic.ValueTimeMark? = null
                    try {
                        // Receive raw control frames so the watchdog observes this socket's pong,
                        // independently of Ktor's default session close handshake (which can stall).
                        http.prepareRequest(url).body<ClientWebSocketSession, Unit> { socket ->
                            try {
                                conversation(socket, queue) { readyAt = TimeSource.Monotonic.markNow() }
                            } finally {
                                queue.cancel()
                                // Abort the transport; never wait for a frozen peer to acknowledge close.
                                socket.cancel()
                            }
                        }
                    } catch (error: CancellationException) {
                        // A heartbeat/handshake timeout retires only this attempt. Owner cancellation exits.
                        if (!isActive) throw error
                        observeConnection(false)
                    } catch (_: Exception) {
                        if (isActive) observeConnection(false)
                    } finally {
                        queue.cancel()
                    }
                    delay(backoff.next(readyAt?.elapsedNow()?.inWholeMilliseconds ?: 0))
                }
            } finally { reporting?.cancel() }
        }.also { running = it; it.start() }
    }

    private suspend fun conversation(socket: ClientWebSocketSession, queue: Channel<String>, onReady: () -> Unit) = coroutineScope {
        // Only messages carried over from the previous connection need a bootstrap resume.
        // The reader may receive fresh history before the first pong; its gaps use resumeSender.
        val carriedMessages = model.unfinishedMessageIds().toSet()
        val pongs = Channel<ByteArray>(Channel.CONFLATED)
        val verified = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reader = launch {
            var body = ByteArray(0)
            // 服务端按帧广播：连上时整份账本也走这条 socket。导师用不上的超大消息丢掉即可，不能为它断线。
            var oversized = false
            for (frame in socket.incoming) when (frame) {
                is Frame.Ping -> socket.send(Frame.Pong(frame.data))
                is Frame.Pong -> pongs.trySend(frame.data)
                is Frame.Close -> break
                is Frame.Text -> {
                    if (!oversized && body.size + frame.data.size > MAX_MESSAGE_BYTES) { oversized = true; body = ByteArray(0) }
                    if (!oversized) body += frame.data
                    if (frame.fin) {
                        if (!oversized) {
                            val text = body.decodeToString(throwOnInvalidSequence = true)
                            synchronized(lifecycle) { if (!closed) { confirm(text); model.onFrame(text) } }
                        }
                        body = ByteArray(0)
                        oversized = false
                    }
                }
                else -> error("Unexpected binary tutor frame")
            }
        }
        val heartbeat = launch {
            var sequence = 0L
            while (isActive) {
                val nonce = "coach:${sequence++}".encodeToByteArray()
                // Include sending in the deadline: a backed-up outgoing socket is also unusable.
                check(withTimeoutOrNull(PONG_TIMEOUT_MS) {
                    socket.send(Frame.Ping(nonce))
                    while (!pongs.receive().contentEquals(nonce)) { /* Ignore unsolicited/stale pong. */ }
                    true
                } == true) { "Tutor pong timed out" }
                verified.complete(Unit)
                delay(PING_INTERVAL_MS)
            }
        }
        var sender: Job? = null
        var ticker: Job? = null
        try {
            // An upgrade alone does not prove the socket can exchange frames.
            withTimeout(PONG_TIMEOUT_MS) { verified.await() }
            val reconnectResumes = synchronized(lifecycle) {
                check(!closed)
                model.onConnection(true)
                val ids = model.unfinishedMessageIds().filter { it in carriedMessages }
                // Register before publishing the queue, under the reader's lifecycle lock.
                ids.forEach { model.noteResumeSent(it) }
                live = queue
                observeConnection(true)
                ids
            }
            onReady()
            reconnectResumes.forEach { id ->
                socket.send(Frame.Text(resumeFrame(id).toString()))
            }
            val resends = synchronized(lifecycle) {
                unconfirmed.values.removeAll { it.resends >= MAX_ASK_RESENDS }
                unconfirmed.values.onEach { it.resends++ }.map { it.frame }
            }
            resends.forEach { socket.send(Frame.Text(it)) }
            sender = launch { for (text in queue) socket.send(Frame.Text(text)) }
            ticker = launch { while (isActive) { delay(RESUME_TICK_MS); model.resumeTick() } }
            reader.join()
        } finally {
            // Publish offline before coroutine/transport cleanup, even if close cannot finish.
            synchronized(lifecycle) {
                if (live === queue) {
                    live = null
                    model.onConnection(false)
                    if (isActive) observeConnection(false)
                }
            }
            sender?.cancel(); ticker?.cancel(); heartbeat.cancel(); reader.cancel(); pongs.cancel()
            socket.cancel()
        }
    }

    fun close() {
        synchronized(lifecycle) {
            closed = true
            live?.cancel()
            live = null
            model.onConnection(false)
            running?.cancel()
        }
        http.coroutineContext.cancel()
        http.close()
    }

    private companion object {
        const val RESUME_TICK_MS = 1_000L
        const val PING_INTERVAL_MS = 5_000L
        const val PONG_TIMEOUT_MS = 5_000L
        const val MAX_MESSAGE_BYTES = 1_048_576
        const val MAX_UNCONFIRMED_ASKS = 16
        const val MAX_ASK_RESENDS = 3
        const val MAX_SOCKET_FRAME_BYTES = 64L * 1_048_576
    }

    /** 发出一帧；没有在线连接返回 false。 */
    fun send(frame: JsonObject, recordTap: Boolean = true): Boolean {
        val target = when ((frame["t"] as? JsonPrimitive)?.content) {
            "tutor.open" -> "open_kp"; "tutor.next" -> "next"; "tutor.test" -> "test"
            "msg.ask" -> "ask"; "tutor.answer" -> "answer"; "msg.cancel" -> "cancel"
            "tutor.settings" -> "settings"; "tutor.explain" -> "explain"; "quick.ask" -> "quick_ask"
            else -> null
        }
        if (recordTap && target != null && target != "open_kp") telemetry?.record("tap", target)
        val sent = live?.trySend(frame.toString())?.isSuccess == true
        if (!sent) telemetry?.record("error", "connection")
        return sent
    }

    // ---- 协议帧（与 server `LedgerRoutes` 的 tutor.* / msg.* 对应） ----

    fun open(threadId: String, courseId: String, kpId: String, conceptIds: List<String>, title: String) = send(buildJsonObject {
        put("t", "tutor.open"); put("threadId", threadId); put("courseId", courseId); put("kpId", kpId); put("title", title)
        put("conceptIds", JsonArray(conceptIds.map(::JsonPrimitive)))
    })

    /** 「下一步」/「换个例子再讲」/ 讲解；`test=true` 是「出题」。 */
    fun next(threadId: String, courseId: String, pack: String, test: Boolean, recordTap: Boolean = true) = send(buildJsonObject {
        put("t", if (test) "tutor.test" else "tutor.next"); put("threadId", threadId); put("courseId", courseId); put("pack", pack)
    }, recordTap)

    fun ask(threadId: String, courseId: String, text: String, pack: String, clientMsgId: String = Uuid.random().toString()): Boolean {
        if (live == null) return false
        model.onSent(threadId,"ask",text,clientMsgId)
        val frame = buildJsonObject {
            put("t", "msg.ask"); put("threadId", threadId); put("courseId", courseId); put("text", text); put("pack", pack)
            put("clientMsgId",clientMsgId)
        }
        synchronized(lifecycle) {
            unconfirmed[clientMsgId] = Unconfirmed(threadId, frame.toString())
            while (unconfirmed.size > MAX_UNCONFIRMED_ASKS) unconfirmed.remove(unconfirmed.keys.first())
        }
        val sent = send(frame)
        if (!sent) {
            synchronized(lifecycle) { unconfirmed.remove(clientMsgId) }
            model.onSendRejected(threadId,clientMsgId)
        }
        return sent
    }

    /** Caller holds [lifecycle]. 本设备的 msg.user（或针对这句的 msg.error）到了，这句就不用再补发。 */
    private fun confirm(text: String) {
        if (unconfirmed.isEmpty()) return
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        when (str("t")) {
            "msg.user" -> if (str("deviceId") == deviceId) str("clientMsgId")?.let(unconfirmed::remove)
            "msg.error" -> {
                str("msg")?.let(unconfirmed::remove)
                str("threadId")?.let { thread -> unconfirmed.values.removeAll { it.threadId == thread } }
            }
        }
    }

    fun cancel(messageId: String) = send(buildJsonObject { put("t", "msg.cancel"); put("msg", messageId) })

    /** 一次提交整组；answers: attemptId → 字母。 */
    fun answer(threadId: String, courseId: String, groupId: String, answers: Map<String, String>, submissionId: String) = send(buildJsonObject {
        put("t", "tutor.answer"); put("threadId", threadId); put("courseId", courseId); put("groupId", groupId); put("submissionId", submissionId)
        put("answers", JsonObject(answers.mapValues { JsonPrimitive(it.value) }))
    })
}
