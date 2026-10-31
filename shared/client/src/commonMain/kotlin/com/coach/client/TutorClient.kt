package com.coach.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
        install(WebSockets) { pingIntervalMillis = 5_000 }
        defaultRequest { header("Authorization", "Bearer $token"); header("X-Coach-Device", deviceId) }
    }
    @kotlin.concurrent.Volatile private var live: Channel<String>? = null

    // 在线缺号由模型发现，续传帧走当前连接的发送队列（非阻塞 trySend，满了/断线返回 false 由模型重试）。
    init { model.resumeSender = { id -> send(resumeFrame(id)) } }
    private fun resumeFrame(id: String) = buildJsonObject { put("t", "msg.resume"); put("msg", id) }

    // Handshake nonce initialization can block; keep CPU workers free on two-core Android devices.
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        telemetry?.record("open")
        val reporting = telemetry?.start(this)
        var backoff = 250L
        try {
            while (isActive) {
                val queue = Channel<String>(capacity = 64)
                try {
                    http.webSocket(urlString = url) {
                        live = queue
                        telemetry?.record("reconnect", "connection")
                        model.onConnection(true)
                        backoff = 250
                        // The stream may have finished while the network was absent. Request its
                        // authoritative snapshot before consuming new deltas on this connection.
                        model.unfinishedMessageIds().forEach { id ->
                            send(Frame.Text(resumeFrame(id).toString()))
                            model.noteResumeSent(id)
                        }
                        val sender = launch { for (text in queue) send(Frame.Text(text)) }
                        // 补发没发出去/没等到快照的续传，并对长时间无帧的消息主动对一次快照。
                        val ticker = launch { while (isActive) { delay(RESUME_TICK_MS); model.resumeTick() } }
                        try {
                            for (frame in incoming) if (frame is Frame.Text) {
                                model.onFrame(frame.readText())
                            }
                        } finally { sender.cancel(); ticker.cancel() }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    telemetry?.record("error", "network")
                    // 下面统一标记离线并退避重连
                } finally {
                    live = null
                    queue.close()
                    telemetry?.record("disconnect", "connection")
                    model.onConnection(false)
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(3_000)
            }
        } finally { reporting?.cancel() }
    }

    fun close() { http.close() }

    private companion object { const val RESUME_TICK_MS = 1_000L }

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
