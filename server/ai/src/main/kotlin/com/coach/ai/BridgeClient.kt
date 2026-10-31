package com.coach.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 桥副本 8791 的地址与超时（ADR-0002）。 */
data class BridgeConfig(
    val baseUrl: String = System.getenv("COACH_BRIDGE_URL") ?: "http://127.0.0.1:8791",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val requestTimeout: Duration = Duration.ofSeconds(240),
    val testTraffic: Boolean = System.getenv("COACH_TRAFFIC") == "test",
)

/** 一次逻辑请求。`task` 只用于 `ai_calls` 归类，不发给模型。 */
data class ChatRequest(
    val task: String,
    val model: String,
    val system: String,
    val user: String,
    val stream: Boolean,
    val effort: String? = null,
    val background: Boolean = false,
    val maxTokens: Int? = null,
)

/** `x_coach` 终态帧（ADR-0002：这是唯一可信的结束信号）。 */
data class TerminalState(
    val status: String,
    val reason: String?,
    val finishReason: String?,
    val servedBy: String?,
    val fallback: Boolean,
    val physicalCalls: Int,
    val requestId: String?,
    val queueMs: Long?,
    val ttftMs: Long?,
    val elapsedMs: Long?,
) {
    val complete: Boolean get() = status == "complete"

    companion object {
        fun fromJson(obj: JsonObject): TerminalState = TerminalState(
            status = obj["status"]?.jsonPrimitive?.content ?: "failed",
            reason = obj.stringOrNull("reason"),
            finishReason = obj.stringOrNull("finish_reason"),
            servedBy = obj.stringOrNull("served_by"),
            fallback = obj["fallback"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            physicalCalls = obj["physical_calls"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            requestId = obj.stringOrNull("request_id"),
            queueMs = obj["queue_ms"]?.jsonPrimitive?.content?.toLongOrNull(),
            ttftMs = obj["ttft_ms"]?.jsonPrimitive?.content?.toLongOrNull(),
            elapsedMs = obj["elapsed_ms"]?.jsonPrimitive?.content?.toLongOrNull(),
        )
    }
}

/** `ai_calls` 的一条（DESIGN §5.7 的形状：call_id、task、served_by、ttft、dur、physical_calls）。 */
data class AiCall(
    val callId: String,
    val task: String,
    val requestedModel: String,
    val servedBy: String?,
    val status: String,
    val ttftMs: Long?,
    val durMs: Long,
    val queueMs: Long?,
    val physicalCalls: Int,
    val requestId: String?,
    val inChars: Int,
    val outChars: Int,
    val startedAt: String,
    val error: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("call_id", callId)
        put("task", task)
        put("request_type", "dev_live")
        put("requested_model", requestedModel)
        put("served_by", servedBy?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        put("status", status)
        put("ttft_ms", ttftMs ?: -1)
        put("dur_ms", durMs)
        put("queue_ms", queueMs ?: -1)
        put("physical_calls", physicalCalls)
        put("request_id", requestId ?: "")
        put("in_chars", inChars)
        put("out_chars", outChars)
        put("started_at", startedAt)
        if (error != null) put("error", error)
    }
}

sealed interface BridgeResult {
    val call: AiCall
    val terminal: TerminalState?

    data class Success(
        val text: String,
        override val terminal: TerminalState,
        override val call: AiCall,
    ) : BridgeResult

    data class Failure(
        val reason: String,
        override val terminal: TerminalState?,
        override val call: AiCall,
    ) : BridgeResult
}

/**
 * 桥副本 8791 的客户端：`X-Request-Id`、`X-Coach-Fallback: off`、`X-Coach-Effort`、`X-Coach-Background`，
 * 流式解析 SSE 并在 `[DONE]` 前取 `x_coach`。
 *
 * 收不到终态帧 = 截断；`status != complete` = 不完整。两种都不当成功交给上层。
 */
class BridgeClient(private val config: BridgeConfig = BridgeConfig()) {
    private fun requireIsolatedTestBridge() {
        require(!config.testTraffic || URI(config.baseUrl).port !in setOf(8790,8791)) {
            "Test traffic requires an isolated bridge port (COACH_BRIDGE_URL); 8790/8791 are reserved for the learner"
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(config.connectTimeout)
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    fun health(): JsonObject? = try {
        val request = HttpRequest.newBuilder(URI.create("${config.baseUrl}/health"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) null else json.parseToJsonElement(response.body()).jsonObject
    } catch (error: Exception) {
        null
    }

    fun chat(request: ChatRequest): BridgeResult {
        val callId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val startedAt = Instant.now().toString()
        val started = System.nanoTime()
        val payload = payloadFor(request, request.stream)
        val builder = builderFor(request, payload, requestId)

        val response = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (error: Exception) {
            return BridgeResult.Failure(
                reason = "transport: ${error.javaClass.simpleName}: ${error.message}",
                terminal = null,
                call = AiCall(
                    callId = callId, task = request.task, requestedModel = request.model, servedBy = null,
                    status = "failed", ttftMs = null, durMs = millisSince(started), queueMs = null,
                    // 传输超时的时候 CLI 多半已经跑过：保守按 1 次计，别把每日上限算少
                    physicalCalls = 1, requestId = requestId, inChars = request.system.length + request.user.length,
                    outChars = 0, startedAt = startedAt, error = error.message,
                ),
            )
        }

        response.body().use { body ->
            if (response.statusCode() != 200) {
                val text = body.readBytes().decodeToString()
                // 错误体里也可能带 x_coach（例如 CLI 跑了一半超时后退出）：照它的 physical_calls 记账
                val terminal = runCatching {
                    (json.parseToJsonElement(text).jsonObject["x_coach"] as? JsonObject)?.let { TerminalState.fromJson(it) }
                }.getOrNull()
                return BridgeResult.Failure(
                    reason = "http_${response.statusCode()}: ${text.take(300)}",
                    terminal = terminal,
                    call = AiCall(
                        callId = callId, task = request.task, requestedModel = request.model,
                        servedBy = terminal?.servedBy, status = terminal?.status ?: "failed",
                        ttftMs = null, durMs = millisSince(started),
                        queueMs = terminal?.queueMs, physicalCalls = terminal?.physicalCalls ?: 1,
                        requestId = terminal?.requestId ?: requestId,
                        inChars = request.system.length + request.user.length, outChars = 0,
                        startedAt = startedAt, error = "http_${response.statusCode()}",
                    ),
                )
            }
            return if (request.stream) {
                readStream(body, request, callId, requestId, startedAt, started)
            } else {
                readWhole(body.readBytes().decodeToString(), request, callId, requestId, startedAt, started)
            }
        }
    }

    /**
     * 真流式：正文一落地就交给 [sink]（首字口径 = 第一次 `onDelta`），返回与 [chat] 同形状的结果。
     *
     * 阻塞到流结束；[onHandle] 立刻拿到取消句柄——`cancel()` 断开 HTTP，桥看到断开就杀掉 CLI 整个进程组
     * （ADR-0002 第 2 条、DESIGN §5.5），所以「取消」只有这一条路径。
     */
    fun streamChat(
        request: ChatRequest,
        sink: BridgeStreamSink,
        onHandle: (BridgeStreamHandle) -> Unit = {},
    ): BridgeResult {
        val callId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val startedAt = Instant.now().toString()
        val started = System.nanoTime()
        val handle = BridgeStreamHandle()
        onHandle(handle)
        val future = http.sendAsync(
            builderFor(request, payloadFor(request, stream = true), requestId).build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        handle.future = future
        val response = try {
            future.get()
        } catch (error: Exception) {
            val cancelled = handle.isCancelled
            val result = BridgeResult.Failure(
                reason = if (cancelled) "cancelled" else "transport: ${error.javaClass.simpleName}: ${error.message}",
                terminal = null,
                call = AiCall(
                    callId = callId, task = request.task, requestedModel = request.model, servedBy = null,
                    status = if (cancelled) "cancelled" else "failed", ttftMs = null,
                    durMs = millisSince(started), queueMs = null, physicalCalls = 1, requestId = requestId,
                    inChars = request.system.length + request.user.length, outChars = 0, startedAt = startedAt,
                    error = if (cancelled) "cancelled" else error.message,
                ),
            )
            sink.onTerminal(result)
            return result
        }
        handle.body = response.body()
        response.body().use { body ->
            if (response.statusCode() != 200) {
                val text = body.readBytes().decodeToString()
                val terminal = runCatching {
                    (json.parseToJsonElement(text).jsonObject["x_coach"] as? JsonObject)?.let { TerminalState.fromJson(it) }
                }.getOrNull()
                val result = BridgeResult.Failure(
                    reason = "http_${response.statusCode()}: ${text.take(300)}",
                    terminal = terminal,
                    call = AiCall(
                        callId = callId, task = request.task, requestedModel = request.model,
                        servedBy = terminal?.servedBy, status = terminal?.status ?: "failed",
                        ttftMs = null, durMs = millisSince(started), queueMs = terminal?.queueMs,
                        physicalCalls = terminal?.physicalCalls ?: 1, requestId = terminal?.requestId ?: requestId,
                        inChars = request.system.length + request.user.length, outChars = 0,
                        startedAt = startedAt, error = "http_${response.statusCode()}",
                    ),
                )
                sink.onTerminal(result)
                return result
            }
            return readStreamIncremental(body, request, callId, requestId, startedAt, started, sink, handle)
        }
    }

    /** 边读边发；取消（客户端取消 / 唤醒收尾）时按 `cancelled` 返回，不写成失败。 */
    private fun readStreamIncremental(
        body: java.io.InputStream,
        request: ChatRequest,
        callId: String,
        requestId: String,
        startedAt: String,
        started: Long,
        sink: BridgeStreamSink,
        handle: BridgeStreamHandle,
    ): BridgeResult {
        val text = StringBuilder()
        var terminal: TerminalState? = null
        var ttft: Long? = null
        var failure: String? = null
        var served: String? = null
        BufferedReader(InputStreamReader(body, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = try {
                    reader.readLine() ?: break
                } catch (error: Exception) {
                    // 取消会主动关掉流：这里读到异常是预期路径，不是故障
                    failure = if (handle.isCancelled) "cancelled" else "stream_read: ${error.javaClass.simpleName}"
                    break
                }
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val frame = try {
                    json.parseToJsonElement(payload).jsonObject
                } catch (error: Exception) {
                    continue
                }
                (frame["x_coach"] as? JsonObject)?.let { terminal = TerminalState.fromJson(it) }
                val model = frame.stringOrNull("model")
                if (!model.isNullOrBlank() && model != served) {
                    served = model
                    sink.onMeta(model)
                }
                val delta = (frame["choices"] as? kotlinx.serialization.json.JsonArray)
                    ?.firstOrNull()?.jsonObject
                    ?.get("delta")?.jsonObject
                    ?.stringOrNull("content")
                if (!delta.isNullOrEmpty()) {
                    if (ttft == null) ttft = millisSince(started)
                    text.append(delta)
                    sink.onDelta(delta, millisSince(started))
                }
            }
        }
        val resolved = terminal
        val cancelled = handle.isCancelled
        val status = when {
            cancelled -> "cancelled"
            resolved != null -> resolved.status
            failure != null -> "failed"
            else -> "truncated"
        }
        val call = AiCall(
            callId = callId, task = request.task, requestedModel = request.model,
            servedBy = resolved?.servedBy ?: served, status = status,
            ttftMs = ttft ?: resolved?.ttftMs, durMs = millisSince(started), queueMs = resolved?.queueMs,
            physicalCalls = resolved?.physicalCalls ?: 1, requestId = resolved?.requestId ?: requestId,
            inChars = request.system.length + request.user.length, outChars = text.length,
            startedAt = startedAt,
            error = if (cancelled) "cancelled" else failure ?: if (resolved == null) "no_x_coach" else null,
        )
        val result: BridgeResult = when {
            cancelled -> BridgeResult.Failure("cancelled", resolved, call)
            failure != null -> BridgeResult.Failure(failure, resolved, call)
            resolved == null -> BridgeResult.Failure("no_x_coach", null, call)
            !resolved.complete -> BridgeResult.Failure("status_${resolved.status}", resolved, call)
            else -> independenceProblem(request.model, resolved)?.let { problem ->
                BridgeResult.Failure(problem, resolved, call)
            } ?: BridgeResult.Success(text.toString(), resolved, call)
        }
        sink.onTerminal(result)
        return result
    }

    private fun payloadFor(request: ChatRequest, stream: Boolean): JsonObject = buildJsonObject {
        put("model", request.model)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", request.system)
            }
            addJsonObject {
                put("role", "user")
                put("content", request.user)
            }
        }
        put("stream", stream)
        request.maxTokens?.let { put("max_tokens", it) }
    }

    private fun builderFor(request: ChatRequest, payload: JsonObject, requestId: String): HttpRequest.Builder {
        requireIsolatedTestBridge()
        val builder = HttpRequest.newBuilder(URI.create("${config.baseUrl}/v1/chat/completions"))
            .timeout(config.requestTimeout)
            .header("Content-Type", "application/json")
            .header("X-Request-Id", requestId)
            .header("X-Coach-Fallback", "off")
            .header("X-Coach-Model-Policy", "exact")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
        if (config.testTraffic) builder.header("X-Coach-Traffic", "test")
        request.effort?.let { builder.header("X-Coach-Effort", it) }
        if (request.background) builder.header("X-Coach-Background", "1")
        return builder
    }

    private fun readWhole(
        text: String,
        request: ChatRequest,
        callId: String,
        requestId: String,
        startedAt: String,
        started: Long,
    ): BridgeResult {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (error: Exception) {
            return BridgeResult.Failure(
                reason = "unparsable_response: ${text.take(200)}",
                terminal = null,
                call = AiCall(
                    callId, request.task, request.model, null, "failed", null, millisSince(started), null, 0,
                    requestId, request.system.length + request.user.length, 0, startedAt, "unparsable_response",
                ),
            )
        }
        val terminal = (root["x_coach"] as? JsonObject)?.let { TerminalState.fromJson(it) }
        val content = (root["choices"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.stringOrNull("content")
            ?: ""
        if (terminal != null && terminal.complete) {
            independenceProblem(request.model, terminal)?.let { problem ->
                return BridgeResult.Failure(problem, terminal, callFor(request, callId, requestId, startedAt, started, terminal, content.length).copy(status="failed",error=problem))
            }
        }
        val call = AiCall(
            callId = callId, task = request.task, requestedModel = request.model,
            servedBy = terminal?.servedBy ?: root.stringOrNull("model"), status = terminal?.status ?: "unknown",
            ttftMs = terminal?.ttftMs ?: millisSince(started), durMs = millisSince(started),
            queueMs = terminal?.queueMs, physicalCalls = terminal?.physicalCalls ?: 1,
            requestId = terminal?.requestId ?: requestId,
            inChars = request.system.length + request.user.length, outChars = content.length,
            startedAt = startedAt,
        )
        return when {
            terminal == null -> BridgeResult.Failure("no_x_coach", null, call)
            !terminal.complete -> BridgeResult.Failure("status_${terminal.status}", terminal, call)
            content.isBlank() -> BridgeResult.Failure("empty_content", terminal, call)
            else -> BridgeResult.Success(content, terminal, call)
        }
    }

    private fun readStream(
        body: java.io.InputStream,
        request: ChatRequest,
        callId: String,
        requestId: String,
        startedAt: String,
        started: Long,
    ): BridgeResult {
        val text = StringBuilder()
        var terminal: TerminalState? = null
        var ttft: Long? = null
        var failure: String? = null
        BufferedReader(InputStreamReader(body, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = try {
                    reader.readLine() ?: break
                } catch (error: Exception) {
                    failure = "stream_read: ${error.javaClass.simpleName}"
                    break
                }
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val frame = try {
                    json.parseToJsonElement(payload).jsonObject
                } catch (error: Exception) {
                    continue
                }
                (frame["x_coach"] as? JsonObject)?.let { terminal = TerminalState.fromJson(it) }
                val delta = (frame["choices"] as? kotlinx.serialization.json.JsonArray)
                    ?.firstOrNull()?.jsonObject
                    ?.get("delta")?.jsonObject
                    ?.stringOrNull("content")
                if (!delta.isNullOrEmpty()) {
                    if (ttft == null) ttft = millisSince(started)
                    text.append(delta)
                }
            }
        }
        val resolved = terminal
        val call = AiCall(
            callId = callId, task = request.task, requestedModel = request.model,
            servedBy = resolved?.servedBy, status = resolved?.status ?: "truncated",
            ttftMs = ttft ?: resolved?.ttftMs, durMs = millisSince(started), queueMs = resolved?.queueMs,
            physicalCalls = resolved?.physicalCalls ?: 1, requestId = resolved?.requestId ?: requestId,
            inChars = request.system.length + request.user.length, outChars = text.length,
            startedAt = startedAt,
            error = failure ?: if (resolved == null) "no_x_coach" else null,
        )
        val streamError = failure
        if (resolved != null && resolved.complete) {
            independenceProblem(request.model, resolved)?.let { problem ->
                return BridgeResult.Failure(problem, resolved, call.copy(status="failed",error=problem))
            }
        }
        return when {
            streamError != null -> BridgeResult.Failure(streamError, resolved, call)
            // ADR-0002：收不到终态帧按截断处理
            resolved == null -> BridgeResult.Failure("no_x_coach", null, call)
            !resolved.complete -> BridgeResult.Failure("status_${resolved.status}", resolved, call)
            else -> BridgeResult.Success(text.toString(), resolved, call)
        }
    }

    private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    /**
     * 异厂独立性（ADR-0002 第 3、4 条）：我们关了兜底，桥就不许兜底；`served_by` 必须和请求的是同一家。
     * 否则「异厂复核」可能是同一家模型，上层却照常写结果。
     */
    private fun independenceProblem(requested: String, terminal: TerminalState): String? {
        if (terminal.fallback) return "served_by_mismatch:fallback"
        val served = terminal.servedBy?.takeIf { it.isNotBlank() } ?: return "served_by_missing"
        if (vendorOf(served) != vendorOf(requested)) return "served_by_mismatch:$served"
        return null
    }

    private fun vendorOf(model: String): String = when {
        model.startsWith("claude") -> "claude"
        model.startsWith("gpt") -> "openai"
        else -> model.substringBefore('-')
    }

    private fun callFor(
        request: ChatRequest,
        callId: String,
        requestId: String,
        startedAt: String,
        started: Long,
        terminal: TerminalState,
        outChars: Int,
    ): AiCall = AiCall(
        callId = callId, task = request.task, requestedModel = request.model,
        servedBy = terminal.servedBy, status = terminal.status, ttftMs = terminal.ttftMs,
        durMs = millisSince(started), queueMs = terminal.queueMs, physicalCalls = terminal.physicalCalls,
        requestId = terminal.requestId ?: requestId,
        inChars = request.system.length + request.user.length, outChars = outChars,
        startedAt = startedAt,
    )

    companion object {
        const val CLAUDE = "claude-sonnet-5-5"
        const val CODEX = "gpt-6.1-sol"
    }
}

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.content != "null" }?.content
