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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * 到 Mac 服务端 `/v1/ws` 的导师连接（与账本同步各用一条 socket，服务端按帧广播）。
 * 断线就是断线：没连上时 [send] 直接返回 false，由界面如实提示，不排队、不假装已发出。
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
        install(WebSockets) { maxFrameSize = MAX_MESSAGE_BYTES.toLong() }
        install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = 5_000 }
        defaultRequest { header("Authorization", "Bearer $token"); header("X-Coach-Device", deviceId) }
    }
    @kotlin.concurrent.Volatile private var live: Channel<String>? = null
    private val lifecycle = Any()
    private var running: Job? = null
    private var closed = false

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
            var backoff = 250L
            try {
                while (isActive) {
                    val queue = Channel<String>(capacity = 64)
                    try {
                        // Receive raw control frames so the watchdog observes this socket's pong,
                        // independently of Ktor's default session close handshake (which can stall).
                        http.prepareRequest(url).body<ClientWebSocketSession, Unit> { socket ->
                            try {
                                conversation(socket, queue) { backoff = 250 }
                            } finally {
                                queue.cancel()
                                // Abort the transport; never wait for a frozen peer to acknowledge close.
                                socket.cancel()
                            }
                        }
                    } catch (error: CancellationException) {
                        // A heartbeat/handshake timeout retires only this attempt. Owner cancellation exits.
                        if (!isActive) throw error
                        telemetry?.record("error", "network")
                    } catch (_: Exception) {
                        telemetry?.record("error", "network")
                    } finally {
                        queue.cancel()
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(3_000)
                }
            } finally { reporting?.cancel() }
        }.also { running = it; it.start() }
    }

    private suspend fun conversation(socket: ClientWebSocketSession, queue: Channel<String>, onReady: () -> Unit) = coroutineScope {
        val pongs = Channel<ByteArray>(Channel.CONFLATED)
        val verified = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reader = launch {
            var body = ByteArray(0)
            for (frame in socket.incoming) when (frame) {
                is Frame.Ping -> socket.send(Frame.Pong(frame.data))
                is Frame.Pong -> pongs.trySend(frame.data)
                is Frame.Close -> break
                is Frame.Text -> {
                    check(body.size + frame.data.size <= MAX_MESSAGE_BYTES) { "Tutor frame too large" }
                    body += frame.data
                    if (frame.fin) {
                        val text = body.decodeToString(throwOnInvalidSequence = true)
                        body = ByteArray(0)
                        synchronized(lifecycle) { if (!closed) model.onFrame(text) }
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
            synchronized(lifecycle) {
                check(!closed)
                live = queue
                model.onConnection(true)
                telemetry?.record("reconnect", "connection")
            }
            onReady()
            model.unfinishedMessageIds().forEach { id ->
                socket.send(Frame.Text(resumeFrame(id).toString()))
                model.noteResumeSent(id)
            }
            sender = launch { for (text in queue) socket.send(Frame.Text(text)) }
            ticker = launch { while (isActive) { delay(RESUME_TICK_MS); model.resumeTick() } }
            reader.join()
        } finally {
            // Publish offline before coroutine/transport cleanup, even if close cannot finish.
            synchronized(lifecycle) {
                if (live === queue) {
                    live = null
                    model.onConnection(false)
                    telemetry?.record("disconnect", "connection")
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
    }

    /** 发出一帧；没有在线连接返回 false。 */
    fun send(frame: JsonObject): Boolean {
        val target = when ((frame["t"] as? JsonPrimitive)?.content) {
            "tutor.open" -> "open_kp"; "tutor.next" -> "next"; "tutor.test" -> "test"
            "msg.ask" -> "ask"; "tutor.answer" -> "answer"; "msg.cancel" -> "cancel"
            "tutor.settings" -> "settings"; "tutor.explain" -> "explain"; "quick.ask" -> "quick_ask"
            else -> "unknown"
        }
        if (target != "open_kp") telemetry?.record("tap", target)
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
    fun next(threadId: String, courseId: String, pack: String, test: Boolean) = send(buildJsonObject {
        put("t", if (test) "tutor.test" else "tutor.next"); put("threadId", threadId); put("courseId", courseId); put("pack", pack)
    })

    fun ask(threadId: String, courseId: String, text: String, pack: String, clientMsgId: String = Uuid.random().toString()): Boolean {
        if (live == null) return false
        model.onSent(threadId,"ask",text,clientMsgId)
        val sent = send(buildJsonObject {
            put("t", "msg.ask"); put("threadId", threadId); put("courseId", courseId); put("text", text); put("pack", pack)
            put("clientMsgId",clientMsgId)
        })
        if (!sent) model.onSendRejected(threadId,clientMsgId)
        return sent
    }

    fun cancel(messageId: String) = send(buildJsonObject { put("t", "msg.cancel"); put("msg", messageId) })

    /** 一次提交整组；answers: attemptId → 字母。 */
    fun answer(threadId: String, courseId: String, groupId: String, answers: Map<String, String>, submissionId: String) = send(buildJsonObject {
        put("t", "tutor.answer"); put("threadId", threadId); put("courseId", courseId); put("groupId", groupId); put("submissionId", submissionId)
        put("answers", JsonObject(answers.mapValues { JsonPrimitive(it.value) }))
    })
}
