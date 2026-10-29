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
        put("served_by", servedBy ?: requestedModel)
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
        val payload = buildJsonObject {
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
            put("stream", request.stream)
            request.maxTokens?.let { put("max_tokens", it) }
        }
        val builder = HttpRequest.newBuilder(URI.create("${config.baseUrl}/v1/chat/completions"))
            .timeout(config.requestTimeout)
            .header("Content-Type", "application/json")
            .header("X-Request-Id", requestId)
            .header("X-Coach-Fallback", "off")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
        request.effort?.let { builder.header("X-Coach-Effort", it) }
        if (request.background) builder.header("X-Coach-Background", "1")

        val response = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (error: Exception) {
            return BridgeResult.Failure(
                reason = "transport: ${error.javaClass.simpleName}: ${error.message}",
                terminal = null,
                call = AiCall(
                    callId = callId, task = request.task, requestedModel = request.model, servedBy = null,
                    status = "failed", ttftMs = null, durMs = millisSince(started), queueMs = null,
                    physicalCalls = 0, requestId = requestId, inChars = request.system.length + request.user.length,
                    outChars = 0, startedAt = startedAt, error = error.message,
                ),
            )
        }

        response.body().use { body ->
            if (response.statusCode() != 200) {
                val text = body.readBytes().decodeToString()
                return BridgeResult.Failure(
                    reason = "http_${response.statusCode()}: ${text.take(300)}",
                    terminal = null,
                    call = AiCall(
                        callId = callId, task = request.task, requestedModel = request.model,
                        servedBy = null, status = "failed", ttftMs = null, durMs = millisSince(started),
                        queueMs = null, physicalCalls = 0, requestId = requestId,
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
        return when {
            streamError != null -> BridgeResult.Failure(streamError, resolved, call)
            // ADR-0002：收不到终态帧按截断处理
            resolved == null -> BridgeResult.Failure("no_x_coach", null, call)
            !resolved.complete -> BridgeResult.Failure("status_${resolved.status}", resolved, call)
            else -> BridgeResult.Success(text.toString(), resolved, call)
        }
    }

    private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    companion object {
        const val CLAUDE = "claude-sonnet-5-5"
        const val CODEX = "gpt-6.1-sol"
    }
}

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.content != "null" }?.content
