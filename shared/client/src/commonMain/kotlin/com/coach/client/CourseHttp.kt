package com.coach.client

import io.ktor.client.request.parameter

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import com.coach.protocol.ReaderTimeoutPolicy
import com.coach.domain.MainlineEssayIdentity
import com.coach.domain.MainlineEssayDocument
import com.coach.domain.LedgerJson
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.fromHttpToGmtDate
import io.ktor.util.date.GMTDate
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import com.coach.domain.reviewqueue.QueueAvailability
import com.coach.domain.reviewqueue.QueueQuestion
import com.coach.domain.reviewqueue.VariantCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** 课程原文的一个文档（教材一章）。页号是 PDF 页；书页范围来自各页自己的书页号（有的页没有）。 */
data class SourceDoc(val docId: String, val title: String, val firstPdfPage: Int, val lastPdfPage: Int, val firstBookPage: Int?, val lastBookPage: Int?)
data class SourcePage(val docId: String, val pdfPage: Int, val bookPage: Int?, val text: String)

/** 模拟考接口的一次回答：成功时 [body] 是服务端原样的 JSON；失败时 [error] 是服务端写的原因，连不上 Mac 时两者都是 null。 */
/** [code] 是服务端给程序看的类别，只用来决定怎么提示，从不显示给人。 */
class MockReply(val body: JsonObject?, val error: String?, val retryable: Boolean = false, val statusCode: Int? = null, val code: String? = null, val retryAfterMs: Long? = null)
private fun HttpResponse.readerRetryDelay(body: JsonObject?): Long? {
    val value = headers[HttpHeaders.RetryAfter]?.trim()
    val seconds = value?.toLongOrNull() ?: (body?.get("retryAfterSec") as? JsonPrimitive)?.content?.toLongOrNull()
    if (seconds != null && seconds >= 0) return seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
    return value?.let { runCatching { (it.fromHttpToGmtDate().timestamp - GMTDate().timestamp).coerceAtLeast(0) }.getOrNull() }
}

/** Windows reader retries one explicit HTTP failure while the original context is still current. */
suspend fun withReaderRetry(
    stillCurrent: suspend () -> Boolean,
    request: suspend () -> MockReply,
): MockReply {
    val reply = request()
    if (reply.statusCode != 429 && reply.statusCode !in 500..599) return reply
    val waitMs = reply.retryAfterMs ?: 1000L
    kotlinx.coroutines.delay(waitMs)
    return if (stillCurrent()) request() else reply
}

sealed interface UnifiedServerCapability {
    data class Supported(val accountScope: String?) : UnifiedServerCapability
    data class Unsupported(val accountScope: String?) : UnifiedServerCapability
    data object Unauthorized : UnifiedServerCapability
    data object Offline : UnifiedServerCapability
}
enum class ServerReachability { ONLINE, UNAUTHORIZED, OFFLINE }
class MainlineEssayReply(val document: MainlineEssayDocument?, val error: String?, val retryable: Boolean = false)
/** 题库里一道可以限时练的论述题。[fromMockPaper] 的题出自整套模拟卷，先练了那套卷就不再是生题。 */
data class EssayPrompt(val id: String, val week: Int, val marks: Double, val wordTarget: Int, val stem: String, val pack: String, val fromMockPaper: Boolean)
/** [weeks] 是课程大纲里已有知识点的周，自己贴题时从这里选；[unmatched] 是因为那一周还没有知识点而暂时练不了的题数。 */
data class EssayBankView(val prompts: List<EssayPrompt>, val weeks: List<Int>, val unmatched: Int)

/**
 * 只读的课程查询：记录页要的冻结题与评分标准、资料页要的原文。全部要联网问 Mac；
 * 返回 null 的意思就是「这次拿不到」（离线、或 Mac 上的服务还没更新到有这个接口），界面照实说，不拿占位内容顶。
 */
class CourseHttp internal constructor(baseUrl: String, private val deviceId: String, private val token: String, private val course: String, createEngine: () -> HttpClientEngine, connectTimeoutMs: Long = 3_000L) {
    constructor(baseUrl: String, deviceId: String, token: String, course: String) : this(baseUrl, deviceId, token, course, { createCoachHttpEngine() }, 3_000L)
    constructor(baseUrl: String, deviceId: String, token: String, course: String, connectTimeoutMs: Long) : this(baseUrl, deviceId, token, course, { createCoachHttpEngine() }, connectTimeoutMs)
    private val base = baseUrl.trimEnd('/')
    // Only a digest of this paired account scope reaches SQLite; credentials never enter note data.
    fun readerState(sync: SyncEngine, week: Int): JsonObject = sync.readerState(course, week, "$base\u0000$deviceId\u0000$token")
    fun saveReaderState(sync: SyncEngine, week: Int, state: JsonObject) = sync.saveReaderState(course, week, "$base\u0000$deviceId\u0000$token", state)
    init { validateCoachBaseUrl(baseUrl) }
    // Use a factory so HttpClient owns and closes the engine, including the injected test engine.
    private val http = HttpClient(object : HttpClientEngineFactory<HttpClientEngineConfig> {
        override fun create(block: HttpClientEngineConfig.() -> Unit) = createEngine()
    }) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = connectTimeoutMs }
        defaultRequest {
            header("Authorization", "Bearer $token"); header("X-Coach-Device", deviceId)
            coachVersionOrNull()?.let { header("X-Coach-Version", it) }
        }
    }

    private suspend fun getObject(path: String): JsonObject? = try {
        val response = http.get("$base/v1/courses/${course.encodeURLPathPart()}/$path")
        if (!response.status.isSuccess()) null else Json.parseToJsonElement(response.bodyAsText()).jsonObject
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    private suspend fun mockCall(path: String, body: JsonObject?, traceId: String? = null): MockReply = try {
        val url = "$base/v1/courses/${course.encodeURLPathPart()}/$path"
        SubmissionTrace.mark(traceId, "http.postStart")
        val response: HttpResponse = if (body == null) http.get(url) else http.post(url) { setBody(TextContent(body.toString(), ContentType.Application.Json)) }
        // postReturned = the suspending call returned; it makes no claim about when headers arrived.
        SubmissionTrace.mark(traceId, "http.postReturned")
        val parsed = runCatching {
            SubmissionTrace.mark(traceId, "http.bodyAsTextStart")
            val text = response.bodyAsText()
            SubmissionTrace.mark(traceId, "http.bodyAsTextDone")
            Json.parseToJsonElement(text).jsonObject.also { SubmissionTrace.mark(traceId, "http.parsed") }
        }.getOrNull()
        if (response.status.isSuccess()) MockReply(parsed, null, parsed == null, response.status.value) else MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "服务端拒绝了这次请求（${response.status.value}）", response.status.value in setOf(408, 429) || response.status.value >= 500, response.status.value)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        MockReply(null, null, true)
    }

    /** 模拟考首页：可考的卷子、速刷题量、未交卷的考试、历次成绩。 */
    suspend fun mocks(): MockReply = mockCall("mocks", null)
    suspend fun startPaper(paperId: String): MockReply = mockCall("mocks", buildJsonObject { put("paperId", paperId) })
    /** [week] 为 null 时按押题权重抽。 */
    suspend fun startDrill(week: Int?): MockReply = mockCall("mocks", buildJsonObject { put("drill", buildJsonObject { week?.let { put("week", it) } }) })
    /** 进行中返回卷面（不含答案与细则）；交卷后返回成绩。 */
    suspend fun mock(mockId: String): MockReply = mockCall("mocks/${mockId.encodeURLPathPart()}", null)
    /** [seq] must grow with every save of this exam; the Mac ignores a save numbered no higher than one it already stored. */
    suspend fun saveMock(mockId: String, answers: JsonObject, seq: Long): MockReply =
        mockCall("mocks/${mockId.encodeURLPathPart()}/answers", buildJsonObject { put("answers", answers); put("seq", seq) })
    suspend fun submitMock(mockId: String, answers: JsonObject, auto: Boolean): MockReply =
        mockCall("mocks/${mockId.encodeURLPathPart()}/submit", buildJsonObject { put("answers", answers); put("auto", auto) })

    /** 选择题主线：各周的题数与覆盖面板。 */
    suspend fun mainline(agentRunId: String? = null): MockReply = mockCall("mainline" + (agentRunId?.let { "?agentRunId=${it.encodeURLParameter()}" } ?: ""), null)
    /** 一周的题，按铺垫 → 细节 → 桥接排好；不含答案和讲解。 */
    suspend fun mainlineWeek(week: Int, agentRunId: String? = null): MockReply = mockCall("mainline/$week?layout=shuffled-v1&learning=v1" + (agentRunId?.let { "&agentRunId=${it.encodeURLParameter()}" } ?: ""), null)
    suspend fun explainSelection(week: Int, input: JsonObject): MockReply = try {
        val response = http.post("$base/v1/courses/${course.encodeURLPathPart()}/mainline/$week/explain") {
            // Includes server processing and its final JSON response; coordinate with COACH_READER_TOTAL_TIMEOUT_MS.
            timeout { requestTimeoutMillis = ReaderTimeoutPolicy.CLIENT_REQUEST_MS; socketTimeoutMillis = ReaderTimeoutPolicy.CLIENT_REQUEST_MS }
            setBody(TextContent(input.toString(), ContentType.Application.Json))
        }
        val parsed = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (response.status.isSuccess()) MockReply(parsed, null)
        else MockReply(null, parsed.s("error").ifBlank { "暂时无法确认，请稍后重试。" })
    } catch (_: HttpRequestTimeoutException) { MockReply(null, "讲解请求等待超时。可继续阅读或手动重试。") }
      catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { MockReply(null, "连不上 Coach 服务，阅读位置已保存。可返回做题或手动重试。") }
    /** 本机书架里的书：选中段加相邻原文块（带块编号）发给已连接的 Coach 服务，讲解只引用这些块编号。 */
    suspend fun explainLibrary(input: JsonObject, onPartial: (suspend (JsonObject) -> Unit)? = null): MockReply = try {
        http.preparePost("$base/v1/library/explain") {
            timeout { requestTimeoutMillis = ReaderTimeoutPolicy.CLIENT_REQUEST_MS; socketTimeoutMillis = ReaderTimeoutPolicy.CLIENT_REQUEST_MS }
            if (onPartial != null) header(HttpHeaders.Accept, "application/x-ndjson")
            setBody(TextContent(input.toString(), ContentType.Application.Json))
        }.execute { response ->
            if (response.status.value == 401) MockReply(null, "授权已失效，请重新登录 Coach 服务。", statusCode = 401)
            else if (onPartial != null && response.status.isSuccess() &&
                response.contentType()?.withoutParameters() == ContentType.parse("application/x-ndjson")) {
                MockReply(readReaderAnswer(response.bodyAsChannel(), onPartial), null, statusCode = response.status.value)
            } else {
                val text = response.bodyAsText()
                val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                val status = response.status.value
                val code = parsed?.s("code")?.ifBlank { null }
                when {
                    parsed == null && status != 403 && text.trimStart().startsWith("<") ->
                        MockReply(null, "需要联网才能使用，读书和做题照常。", retryable = true, code = "offline")
                    // Older friend services return a quota sentence. Use the same service guidance on both clients.
                    status == 429 || code == "daily_quota" || code == "monthly_quota" ->
                        MockReply(null, "讲解服务暂时不可用，请稍后再试。", retryable = true, statusCode = status,
                            code = "unavailable", retryAfterMs = response.readerRetryDelay(parsed))
                    status in 500..599 ->
                        MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "讲解服务暂时不可用，请稍后再试。",
                            retryable = true, statusCode = status, code = code ?: "unavailable",
                            retryAfterMs = response.readerRetryDelay(parsed))
                    parsed == null && status != 403 ->
                        MockReply(null, "需要联网才能使用，读书和做题照常。", retryable = true, code = "offline")
                    response.status.isSuccess() -> MockReply(parsed, null, statusCode = status)
                    else -> MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "暂时无法确认，请稍后重试。", statusCode = status, code = code)
                }
            }
        }
    } catch (_: HttpRequestTimeoutException) { MockReply(null, "需要联网才能使用，读书和做题照常。", retryable = true, code = "offline") }
      catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { MockReply(null, "需要联网才能使用，读书和做题照常。", retryable = true, code = "offline") }

    /** Lightweight reachability probe; distinguishes unreachable/gateway errors from live 401/2xx-4xx responses. */
    suspend fun probeReachability(role: String, timeoutMs: Long = 1_500L): ServerReachability = try {
        val path = if (role == "friend") "$base/v1/public/status" else "$base/v1/hello"
        val response = http.get(path) {
            timeout {
                connectTimeoutMillis = timeoutMs
                requestTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
        val status = response.status.value
        when {
            status == 401 -> ServerReachability.UNAUTHORIZED
            status == 408 -> ServerReachability.OFFLINE
            status in 200..499 -> ServerReachability.ONLINE
            status >= 500 -> {
                val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                if (parsed != null) ServerReachability.ONLINE else ServerReachability.OFFLINE
            }
            else -> ServerReachability.OFFLINE
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (e: ResponseException) {
        when (e.response.status.value) {
            401 -> ServerReachability.UNAUTHORIZED
            408 -> ServerReachability.OFFLINE
            else -> ServerReachability.ONLINE
        }
      }
      catch (_: Exception) { ServerReachability.OFFLINE }

    /** Check this same server and device without sending any book text. Only 401 proves revocation. */
    suspend fun libraryAuthStatus(): Int? = try {
        http.get("$base/v1/public/status").status.value
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { null }

    suspend fun learningProfile(): JsonObject? {
        val reply = http.get("$base/learning-sync/profile")
        if (!reply.status.isSuccess() || reply.contentType()?.withoutParameters() != ContentType.Application.Json) return null
        val body = reply.bodyAsText()
        return try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        }
    }

    suspend fun learningChanges(epoch: String?, after: Long, stream: String, chunks: Boolean = false): JsonObject? {
        val reply = http.get("$base/learning-sync/changes") {
            if (chunks) parameter("chunks",1)
            parameter("stream",stream)
            parameter("after", after)
            epoch?.let { parameter("epoch", it) }
        }
        return if (reply.status.isSuccess()) Json.parseToJsonElement(reply.bodyAsText()).jsonObject else null
    }

    suspend fun learningSnapshot(epoch: String, token: String?, after: Long, chunks: Boolean = false): JsonObject? {
        val reply = http.get("$base/learning-sync/snapshot") {
            if (chunks) parameter("chunks",1)
            parameter("epoch",epoch)
            parameter("after",after)
            token?.let { parameter("snapshotId",it) }
        }
        if (reply.status.value == 410) return buildJsonObject { put("expired",true) }
        return if (reply.status.isSuccess()) Json.parseToJsonElement(reply.bodyAsText()).jsonObject else null
    }

    suspend fun learningChunk(epoch: String, transport: String, snapshot: String?, seq: Long, offset: Int): JsonObject? {
        val reply = http.get("$base/learning-sync/chunk") {
            parameter("epoch",epoch); parameter("transportEpoch",transport)
            parameter("seq",seq); parameter("offset",offset)
            snapshot?.let { parameter("snapshotId",it) }
        }
        return if (reply.status.isSuccess()) Json.parseToJsonElement(reply.bodyAsText()).jsonObject else null
    }

    suspend fun pushLearningChange(operation: JsonObject): JsonObject? {
        val reply = http.post("$base/learning-sync/push") {
            contentType(ContentType.Application.Json)
            setBody(operation.toString())
        }
        return if (reply.status.isSuccess()) Json.parseToJsonElement(reply.bodyAsText()).jsonObject else null
    }

    /** Probe server capability for unified book upload (TON-1823). Never falls back to legacy fragments. */
    suspend fun unifiedBooksCapability(role: String): UnifiedServerCapability = try {
        val path = if (role == "friend") "$base/v1/public/status" else "$base/v1/hello"
        val response = http.get(path)
        when (response.status.value) {
            401 -> UnifiedServerCapability.Unauthorized
            in 200..299 -> {
                val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                val supported = if (role == "friend") {
                    (parsed?.get("unifiedBooks") as? JsonPrimitive)?.booleanOrNull == true
                } else {
                    (parsed?.get("unifiedBooksSupported") as? JsonPrimitive)?.booleanOrNull == true
                }
                val accountScope = parsed?.s("accountScope")?.ifBlank { null }
                if (supported) UnifiedServerCapability.Supported(accountScope)
                else UnifiedServerCapability.Unsupported(accountScope)
            }
            else -> UnifiedServerCapability.Offline
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        UnifiedServerCapability.Offline
    }

    /** Stream original book bytes to unified server intake; full bytes are verified server-side. */
    suspend fun uploadUnifiedBook(
        contentLength: Long,
        format: String,
        title: String,
        sha256: String,
        streamChunks: suspend (writeChunk: suspend (ByteArray, Int, Int) -> Unit) -> Unit,
    ): MockReply = try {
        val response = http.post("$base/v1/books/upload") {
            timeout { requestTimeoutMillis = 600_000L; socketTimeoutMillis = 120_000L }
            header("X-Coach-Book-Format", format)
            header("X-Coach-Book-Title", title.encodeURLParameter())
            header("X-Coach-Book-Sha256", sha256)
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType: ContentType = ContentType.Application.OctetStream
                    override val contentLength: Long = contentLength
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        streamChunks { buf, offset, len ->
                            channel.writeFully(buf, offset, offset + len)
                        }
                    }
                },
            )
        }
        val status = response.status.value
        if (status == 401) {
            MockReply(null, "授权已失效，请重新登录学习服务。", statusCode = 401, code = "unauthorized")
        } else if (status == 404) {
            MockReply(null, "等待服务支持", retryable = true, statusCode = 404, code = "unsupported")
        } else {
            val text = response.bodyAsText()
            val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            val code = parsed?.s("code")?.ifBlank { null }
            when {
                response.status.isSuccess() && parsed != null -> MockReply(parsed, null, statusCode = status)
                status == 413 || status == 507 -> MockReply(
                    null,
                    parsed?.s("error")?.ifBlank { null } ?: if (status == 507) "存储空间不足，请稍后再试" else "文件太大",
                    retryable = status == 507,
                    statusCode = status,
                    code = code,
                )
                else -> MockReply(
                    null,
                    parsed?.s("error")?.ifBlank { null } ?: "上传失败，请稍后重试。",
                    retryable = status >= 500 || status == 408 || status == 429,
                    statusCode = status,
                    code = code,
                )
            }
        }
    } catch (_: HttpRequestTimeoutException) {
        MockReply(null, "等待联网", retryable = true, code = "offline")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        MockReply(null, "等待联网", retryable = true, code = "offline")
    }

    suspend fun unifiedBookStatus(shelfBookId: String): MockReply = try {
        val response = http.get("$base/v1/books/${shelfBookId.encodeURLPathPart()}/status")
        val status = response.status.value
        val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        when {
            status == 401 -> MockReply(null, "授权已失效", statusCode = 401, code = "unauthorized")
            status == 404 -> MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "等待服务支持", statusCode = 404, code = "not_found")
            response.status.isSuccess() && parsed != null -> MockReply(parsed, null, statusCode = status)
            else -> MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "状态查询失败", retryable = status >= 500, statusCode = status)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        MockReply(null, "等待联网", retryable = true, code = "offline")
    }

    suspend fun listUnifiedBooks(): MockReply = try {
        val response = http.get("$base/v1/books")
        val status = response.status.value
        val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        when {
            status == 401 -> MockReply(null, "授权已失效", statusCode = 401, code = "unauthorized")
            response.status.isSuccess() && parsed != null -> MockReply(parsed, null, statusCode = status)
            else -> MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "无法获取书架列表", retryable = status >= 500, statusCode = status)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        MockReply(null, "等待联网", retryable = true, code = "offline")
    }

    suspend fun deleteUnifiedBook(shelfBookId: String): MockReply = try {
        val response = http.delete("$base/v1/books/${shelfBookId.encodeURLPathPart()}")
        val status = response.status.value
        val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        val code = parsed?.s("code")?.ifBlank { null }
        when {
            status == 401 -> MockReply(null, "授权已失效", statusCode = 401, code = "unauthorized")
            status == 404 && code == "not_found" -> MockReply(parsed, null, statusCode = 404, code = "not_found")
            status == 404 -> MockReply(null, "等待服务支持", retryable = true, statusCode = 404, code = "unsupported")
            response.status.isSuccess() -> MockReply(parsed ?: buildJsonObject { put("deleted", true) }, null, statusCode = status)
            else -> MockReply(
                null,
                parsed?.s("error")?.ifBlank { null } ?: "解绑失败",
                retryable = status >= 500 || status == 408 || status == 429,
                statusCode = status,
                code = code,
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        MockReply(null, "等待联网", retryable = true, code = "offline")
    }

    /** Streams the account's verified original book bytes from the server for cross-device pullback. */
    suspend fun downloadUnifiedBookOriginal(
        shelfBookId: String,
        onChunk: suspend (buf: ByteArray, offset: Int, len: Int, serverSha256: String?, contentLength: Long?) -> Unit,
    ): MockReply = try {
        http.prepareGet("$base/v1/books/${shelfBookId.encodeURLPathPart()}/original") {
            timeout { requestTimeoutMillis = 600_000L; socketTimeoutMillis = 120_000L }
        }.execute { response ->
            val status = response.status.value
            when {
                status == 401 -> MockReply(null, "授权已失效", statusCode = 401, code = "unauthorized")
                status == 404 -> {
                    val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                    val code = parsed?.s("code")?.ifBlank { null } ?: "not_found"
                    MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "书架中找不到这本书。", statusCode = 404, code = code)
                }
                response.status.isSuccess() -> {
                    val sha = response.headers["X-Coach-Book-Sha256"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                    val len = response.headers[HttpHeaders.ContentLength]?.trim()?.toLongOrNull()
                    val channel = response.bodyAsChannel()
                    val buf = ByteArray(65_536)
                    var total = 0L
                    while (true) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        if (n == 0) continue
                        onChunk(buf, 0, n, sha, len)
                        total += n
                    }
                    MockReply(
                        buildJsonObject {
                            if (sha != null) put("sha256", sha)
                            put("sizeBytes", len ?: total)
                        },
                        null,
                        statusCode = status,
                    )
                }
                else -> {
                    val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                    MockReply(
                        null,
                        parsed?.s("error")?.ifBlank { null } ?: "下载原文件失败，请稍后重试。",
                        retryable = status >= 500 || status == 408 || status == 429,
                        statusCode = status,
                        code = parsed?.s("code")?.ifBlank { null },
                    )
                }
            }
        }
    } catch (_: HttpRequestTimeoutException) {
        MockReply(null, "等待联网", retryable = true, code = "offline")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        MockReply(null, "等待联网", retryable = true, code = "offline")
    }

    suspend fun unifiedBookArtifact(shelfBookId: String): MockReply = try {
        val response = http.get("$base/v1/books/${shelfBookId.encodeURLPathPart()}/artifact")
        val status = response.status.value
        val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        val code = parsed?.s("code")?.ifBlank { null }
        when {
            status == 401 -> MockReply(null, "授权已失效", statusCode = 401, code = "unauthorized")
            status == 404 -> MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "书架中找不到这本书。", statusCode = 404, code = code ?: "not_found")
            status == 409 -> MockReply(parsed, parsed?.s("error")?.ifBlank { null } ?: "这本书还在整理中。", retryable = true, statusCode = 409, code = code ?: "not_ready")
            response.status.isSuccess() && parsed != null -> MockReply(parsed, null, statusCode = status)
            else -> MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "无法获取书籍结构。", retryable = status >= 500, statusCode = status, code = code)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        MockReply(null, "等待联网", retryable = true, code = "offline")
    }

    suspend fun deleteUnifiedAccount(): MockReply = try {
        val response = http.delete("$base/v1/account")
        val status = response.status.value
        val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        when {
            status == 401 -> MockReply(null, "授权已失效", statusCode = 401, code = "unauthorized")
            status == 404 -> MockReply(null, "等待服务支持", statusCode = 404, code = "unsupported")
            response.status.isSuccess() -> MockReply(parsed ?: buildJsonObject { put("deleted", true) }, null, statusCode = status)
            else -> MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "删号失败，请稍后重试。", retryable = status >= 500, statusCode = status)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        MockReply(null, "等待联网", retryable = true, code = "offline")
    }

    /** 2.0-W2-B: 按节并发批量生成单书章节伴读五模块（要点/读前自测/推导模板/选做练习/章末习题三级提示）。 */
    suspend fun companionChapter(input: JsonObject): MockReply = try {
        val response = http.post("$base/v1/library/companion/chapter") {
            timeout { requestTimeoutMillis = 180_000L; socketTimeoutMillis = 180_000L }
            setBody(TextContent(input.toString(), ContentType.Application.Json))
        }
        if (response.status.value == 401) MockReply(null, "授权已失效，请重新登录学习服务。", statusCode = 401)
        else {
            val text = response.bodyAsText()
            val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            val status = response.status.value
            val code = parsed?.s("code")?.ifBlank { null }
            when {
                parsed == null && status != 403 -> MockReply(null, "连不上学习服务，可继续阅读或稍后重试。")
                response.status.isSuccess() -> MockReply(parsed, null, statusCode = status)
                status == 429 || code == "daily_quota" || code == "monthly_quota" ->
                    MockReply(null, "伴读服务暂时繁忙，请稍后再试。", retryable = true, statusCode = status, code = "unavailable")
                else -> MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "章节伴读暂时不可用，请稍后重试。", statusCode = status, code = code)
            }
        }
    } catch (_: HttpRequestTimeoutException) { MockReply(null, "需要联网才能使用，本地阅读与草稿已保留。", retryable = true, code = "offline") }
      catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { MockReply(null, "需要联网才能使用，本地阅读与草稿已保留。", retryable = true, code = "offline") }
    suspend fun courseAlign(input: JsonObject): MockReply =
        postCourseEndpoint("v1/library/course/align", input, timeoutMs = 120_000L, timeoutMsg = "课程自动对齐超时，已先使用本地规则。")

    suspend fun courseTopicPack(input: JsonObject): MockReply =
        postCourseEndpoint("v1/library/course/topic-pack", input, timeoutMs = 180_000L, timeoutMsg = "专题合卷超时，请稍后重试。")

    suspend fun examPointDrill(input: JsonObject): MockReply =
        postCourseEndpoint("v1/library/course/exam-point-drill", input, timeoutMs = 900_000L, timeoutMsg = "考点出题等待超时，已保留原来的题。")

    suspend fun courseVision(input: JsonObject): MockReply =
        postCourseEndpoint("v1/library/course/vision", input, timeoutMs = 180_000L, timeoutMsg = "视觉识别等待超时，请稍后重试。")

    /** 2.0-AI-2: 课程框架逐点批改（零代写）+ 四类错因诊断与同构变式闭环。 */
    suspend fun gradeAndDiagnosePractice(input: JsonObject): MockReply =
        postCourseEndpoint("v1/library/practice/grade-and-diagnose", input, timeoutMs = 180_000L, timeoutMsg = "逐点批改等待超时，你的草稿已保留。")

    /** TON-1910: one open question for a chapter. Generated once, then stored on the device. */
    suspend fun generateChapterOpenQuestion(input: JsonObject): MockReply =
        postCourseEndpoint(
            "v1/library/practice/chapter-open-question",
            input,
            timeoutMs = 180_000L,
            timeoutMsg = "这一章的开放题需要联网。读过的内容还在。",
        )

    /** 2.0-AI-3: 整门课与已读书目跨材料逐句核验问答与分歧直指。 */
    suspend fun askCollection(input: JsonObject): MockReply =
        postCourseEndpoint("v1/library/collection/ask", input, timeoutMs = 120_000L, timeoutMsg = "跨材料问答等待超时，请重试。")

    /** 2.0-AI-4: 按公开课程手册规格生成整门课考前限时模拟卷与精确验算核对。 */
    suspend fun generateCourseMock(input: JsonObject): MockReply =
        postCourseEndpoint("v1/library/course/mock/generate", input, timeoutMs = 240_000L, timeoutMsg = "模考出卷等待超时，请重试。")

    /** 2.0-MODES-2: 按学习模式流式拉取单章结构化伴读首屏（SSE 优先，兼容普通 JSON 响应）。 */
    suspend fun streamStudyModePack(
        bookId: String,
        mode: String,
        input: JsonObject,
        onProgress: (chars: Int, firstTokenMs: Long?, delta: String) -> Unit = { _, _, _ -> },
    ): MockReply = try {
        val encodedBook = bookId.encodeURLPathPart()
        val encodedMode = mode.encodeURLPathPart()
        val wireBody = buildJsonObject {
            input.forEach { (k, v) -> put(k, v) }
            if ("stream" !in input) put("stream", true)
        }
        http.preparePost("$base/api/books/$encodedBook/modes/$encodedMode") {
            timeout { requestTimeoutMillis = 60_000L; socketTimeoutMillis = 60_000L }
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            setBody(TextContent(wireBody.toString(), ContentType.Application.Json))
        }.execute { response ->
            val status = response.status.value
            if (status == 401) {
                MockReply(null, "授权已失效，请重新登录学习服务。", statusCode = 401)
            } else if (!response.status.isSuccess()) {
                val errText = response.bodyAsText()
                val parsed = runCatching { Json.parseToJsonElement(errText).jsonObject }.getOrNull()
                val code = parsed?.s("code")?.ifBlank { null }
                if (status == 429 || code == "daily_quota" || code == "monthly_quota") {
                    MockReply(null, "课程服务暂时繁忙，请稍后再试。", retryable = true, statusCode = status, code = "unavailable")
                } else {
                    MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "学法伴读请求失败，请稍后重试。", statusCode = status, code = code)
                }
            } else if (response.contentType()?.match(ContentType.Application.Json) == true) {
                val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                if (parsed != null) MockReply(parsed, null, statusCode = status)
                else MockReply(null, "伴读响应格式异常，请稍后重试。", statusCode = status)
            } else {
                val ch = response.bodyAsChannel()
                var currentEvent = ""
                var finalPack: JsonObject? = null
                var streamError: String? = null
                while (!ch.isClosedForRead) {
                    val line = ch.readUTF8Line() ?: break
                    val trimmed = line.trimEnd()
                    when {
                        trimmed.startsWith("event:") -> currentEvent = trimmed.removePrefix("event:").trim()
                        trimmed.startsWith("data:") -> {
                            val dataRaw = trimmed.removePrefix("data:").trim()
                            val obj = runCatching { Json.parseToJsonElement(dataRaw).jsonObject }.getOrNull() ?: continue
                            when (currentEvent) {
                                "first_screen" -> {
                                    val chars = obj["chars"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
                                    val ttft = (obj["firstTokenMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                                    val delta = (obj["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                    onProgress(chars, ttft, delta)
                                }
                                "chunk" -> {
                                    val chars = (obj["chars"] as? JsonPrimitive)?.intOrNull ?: 0
                                    val delta = (obj["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                    onProgress(chars, null, delta)
                                }
                                "pack" -> finalPack = obj
                                "error" -> streamError = obj.s("error").ifBlank { "生成失败" }
                            }
                        }
                        trimmed.isEmpty() -> currentEvent = ""
                    }
                }
                when {
                    finalPack != null -> MockReply(finalPack, null, statusCode = status)
                    streamError != null -> MockReply(null, streamError, statusCode = status)
                    else -> MockReply(null, "未收到完整学法内容，请重试。", statusCode = status)
                }
            }
        }
    } catch (_: HttpRequestTimeoutException) {
        MockReply(null, "需要联网才能使用，可先查看本地章节结构。", retryable = true, code = "offline")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        MockReply(null, "需要联网才能使用，可先查看本地章节结构。", retryable = true, code = "offline")
    }

    /** 2.0-MODES-2: 整本书 24 章一页纸 Batch 管线（全书地图）。 */
    suspend fun fetchBookMapBatch(bookId: String, input: JsonObject): MockReply =
        postCourseEndpoint(
            "api/books/${bookId.encodeURLPathPart()}/book-map/batch",
            input,
            timeoutMs = 300_000L,
            timeoutMsg = "全书地图生成等待超时，请重试。",
        )

    private suspend fun postCourseEndpoint(
        path: String,
        input: JsonObject,
        timeoutMs: Long,
        timeoutMsg: String,
    ): MockReply = try {
        val response = http.post("$base/$path") {
            timeout { requestTimeoutMillis = timeoutMs; socketTimeoutMillis = timeoutMs }
            setBody(TextContent(input.toString(), ContentType.Application.Json))
        }
        if (response.status.value == 401) {
            MockReply(null, "授权已失效，请重新登录学习服务。", statusCode = 401)
        } else {
            val text = response.bodyAsText()
            val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            val status = response.status.value
            val code = parsed?.s("code")?.ifBlank { null }
            when {
                path == "v1/library/collection/ask" && (status == 429 || status in 500..599) ->
                    MockReply(null, "课程服务暂时繁忙，请稍后再试。", retryable = true, statusCode = status,
                        code = "unavailable", retryAfterMs = response.readerRetryDelay(parsed))
                parsed == null && status != 403 -> MockReply(null, "连不上学习服务，可继续本地阅读与练习。")
                response.status.isSuccess() -> MockReply(parsed, null, statusCode = status)
                code == "daily_quota" || code == "monthly_quota" ->
                    MockReply(null, "课程服务暂时繁忙，请稍后再试。", retryable = true, statusCode = status, code = "unavailable")
                status == 429 ->
                    MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "当前提问较集中，请几秒后重试。", retryable = true, statusCode = status, code = "unavailable")
                else -> MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "请求暂时无法完成，请稍后重试。", statusCode = status, code = code)
            }
        }
    } catch (_: HttpRequestTimeoutException) {
        MockReply(null, timeoutMsg, retryable = true, code = "offline")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        MockReply(null, "需要联网才能使用，读书和做题照常。", retryable = true, code = "offline")
    }

    /** 2.0-W2-D: 书单跨书概念两阶段语义对齐（阶段 2 远端语义裁决）。 */
    suspend fun alignBooklistConcepts(input: JsonObject): MockReply = try {
        val response = http.post("$base/v1/library/booklist/align") {
            timeout { requestTimeoutMillis = 120_000L; socketTimeoutMillis = 120_000L }
            setBody(TextContent(input.toString(), ContentType.Application.Json))
        }
        val status = response.status.value
        if (status == 401) MockReply(null, "授权已失效，请重新登录学习服务。", statusCode = 401)
        else {
            val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            if (response.status.isSuccess() && parsed != null) MockReply(parsed, null, statusCode = status)
            else MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "书单主题对齐暂时不可用。", statusCode = status)
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { MockReply(null, "需要联网才能使用，已保留本地对齐结果。", retryable = true, code = "offline") }

    /** 同一概念在各书里的一句话说法。批次未完成时正文里带 pending。 */
    suspend fun alignConceptSayings(input: JsonObject): MockReply = try {
        val response = http.post("$base/v1/library/concepts/sayings") {
            timeout { requestTimeoutMillis = 120_000L; socketTimeoutMillis = 120_000L }
            setBody(TextContent(input.toString(), ContentType.Application.Json))
        }
        val status = response.status.value
        if (status == 401) MockReply(null, "授权已失效，请重新登录学习服务。", statusCode = 401)
        else {
            val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            if (response.status.isSuccess() && parsed != null) MockReply(parsed, null, statusCode = status)
            else MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "各书说法暂时对不上。", statusCode = status)
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { MockReply(null, "需要联网才能核对各书说法，已保留本机结果。", retryable = true, code = "offline") }

    suspend fun cancelLibraryExplain(id: String) {
        runCatching { http.post("$base/v1/library/explain/cancel") {
            setBody(TextContent(buildJsonObject { put("requestId", id) }.toString(), ContentType.Application.Json)) } }
    }
    suspend fun cancelSelection(week: Int, id: String) {
        mockCall("mainline/$week/explain/cancel", buildJsonObject { put("requestId", id) })
    }
    /**
     * 一道题答一次：返回对错、全部讲解、复习安排和新的覆盖数。同一个 [submissionId] 重发只算一次。
     * [choice] 是屏幕上的第几个选项，[layout] 原样带回这道题读下来时的 `layout`，Mac 据此认出是哪一种打乱顺序。
     */
    suspend fun answerMainline(week: Int, itemId: String, choice: Int, guessed: Boolean, submissionId: String, layout: Int?, dontKnow: Boolean = false, learning: Boolean = false, assisted: Boolean = false, agentRunId: String? = null): MockReply =
        mockCall("mainline/$week/answer", buildJsonObject {
            put("itemId", itemId); put("choice", choice); put("guessed", guessed); put("submissionId", submissionId); layout?.let { put("layout", it) }
            put("dontKnow", dontKnow); put("learning", learning)
            put("assisted", assisted); agentRunId?.let { put("agentRunId", it) }
        }, submissionId)

    /** Always fetched with this paired account; a cached answer never authorizes an asset read. */
    suspend fun mainlineEssay(week: Int, identity: MainlineEssayIdentity): MainlineEssayReply {
        if (!identity.valid) return MainlineEssayReply(null, "论述关联资料待核，暂不能查看示范全文与评分点。")
        val response = mockCall("mainline/$week/essay", LedgerJson.encodeToJsonElement(identity).jsonObject)
        val raw = response.body ?: return MainlineEssayReply(null, when (response.statusCode) {
            401, 403 -> "当前设备无权读取这份示范，请重新确认课程访问权限。"
            409 -> "示范资料身份或版本已改变，请返回题目后重试。"
            404, 501, 503 -> "全文读取尚未接通，示范全文与评分点仍待核。"
            else -> "暂时无法读取示范，阅读位置已保存。"
        }, response.retryable)
        val document = runCatching { LedgerJson.decodeFromJsonElement<MainlineEssayDocument>(raw) }.getOrNull()
        return if (document?.matches(course, identity) == true) MainlineEssayReply(document, null)
            else MainlineEssayReply(null, "示范资料与当前题目不一致，请返回题目后重试。")
    }

    /** 提交过的作答的完整记录（冻结题、评分标准、批改与复核）。 */
    suspend fun attemptRecord(attemptId: String): JsonObject? = getObject("attempts/${attemptId.encodeURLPathPart()}")

    /** Reserve/pose exactly this selected identity; retries reuse its attempt and never request a KP fallback. */
    suspend fun takeReviewVariant(target: ReviewPracticeTarget): MockReply {
        if (target.courseId != course || target.deviceId != deviceId) return MockReply(null, "REVIEW_SCOPE_MISMATCH")
        return mockCall("review-practice", com.coach.domain.json("courseId" to target.courseId,"deviceId" to target.deviceId,
            "agentRunId" to target.agentRunId,"kpId" to target.kpId,"entryId" to target.entryId,"attemptId" to target.attemptId,
            "question" to com.coach.domain.json("id" to target.question.id,"version" to target.question.version,"fingerprint" to target.question.fingerprint)))
    }

    suspend fun sources(): List<SourceDoc>? = getObject("sources")?.let { root ->
        (root["docs"] as? JsonArray)?.mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            SourceDoc(o.s("docId"), o.s("title"), o.i("firstPdfPage"), o.i("lastPdfPage"), (o["firstBookPage"] as? JsonPrimitive)?.intOrNull, (o["lastBookPage"] as? JsonPrimitive)?.intOrNull)
        }
    }

    suspend fun page(docId: String, pdfPage: Int): SourcePage? = getObject("sources/${docId.encodeURLPathPart()}/pages/$pdfPage")?.let { o ->
        SourcePage(o.s("docId"), o.i("pdfPage"), (o["bookPage"] as? JsonPrimitive)?.intOrNull, o.s("text"))
    }

    /** 每个知识点已复核的构答题数；没有这个接口时返回 null（不假装知道）。 */
    suspend fun constructedSupply(): Map<String, Int>? = getObject("pool")?.let { root ->
        (root["constructed"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.intOrNull ?: 0 }
    }

    /**
     * 复习变式的题级候选：按考点向 Mac 要「同课程、同考点、已核键、还没出过题」的真实题。
     * 返回 null 表示这次拿不到（离线，或服务端还没有这个字段）；空列表表示题库如实没有多余的题。
     * 解析这一步不把没见过的状态当成可练：未知 availability 的行直接丢掉，未核键的照实标成
     * [VariantCandidate.keyVerified] = false，选择器还会再复核一遍；这里不补题、不改版本。
     */
    suspend fun reviewVariantCandidates(kpId: String): List<VariantCandidate>? =
        getObject("pool?kp=${kpId.encodeURLPathPart()}")?.let { root ->
            val rows = root["variants"] as? JsonArray ?: return@let null
            rows.mapNotNull { row ->
                val o = row as? JsonObject ?: return@mapNotNull null
                val questionId = o.s("questionId")
                val availability = QueueAvailability.entries.firstOrNull { it.name == o.s("availability") }
                    ?: return@mapNotNull null
                if (questionId.isBlank() || kpId.isBlank() || o.s("kpId") != kpId) return@mapNotNull null
                VariantCandidate(
                    courseId = course,
                    kpId = o.s("kpId"),
                    question = QueueQuestion(questionId, null, o.s("fingerprint").ifBlank { null }),
                    availability = availability,
                    keyVerified = o.s("keyState") == "VERIFIED",
                )
            }
        }

    /** 期末押题权重（服务端私有配置）；没有配置或接口不通时返回 null，面板就只按掌握度排。 */
    suspend fun examFocus(): ExamFocus? = ExamFocus.parse(getObject("exam-focus"))
    suspend fun essays(): EssayBankView? = getObject("essays")?.let { root ->
        EssayBankView((root["prompts"] as? JsonArray).orEmpty().mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            EssayPrompt(o.s("id"), o.i("week"), (o["marks"] as? JsonPrimitive)?.doubleOrNull ?: 20.0, o.i("wordTarget"), o.s("stem"), o.s("pack"),
                (o["fromMockPaper"] as? JsonPrimitive)?.booleanOrNull == true)
        }, (root["weeks"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }, root.i("unmatched"))
    }

    /** Separate private reader surface; it shares auth/timeouts but has no connection to the AI client. */
    val reader = ReaderHttp(
        courseId = course,
        request = { path, body ->
            try {
                val url = readerUrl(path)
                val r = if (body == null) http.get(url) else http.post(url) { setBody(TextContent(body.toString(), ContentType.Application.Json)) }
                ReaderReply(r.status.value, runCatching { Json.parseToJsonElement(r.bodyAsText()).jsonObject }.getOrNull())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { ReaderReply(null, null) }
        },
        bytes = { path ->
            try { http.get(readerUrl(path)).let { if (it.status.isSuccess()) it.bodyAsBytes() else null } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        },
        text = { path ->
            try { http.get(readerUrl(path)).let { if (it.status.isSuccess()) it.bodyAsText() else null } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        },
    )
    private fun readerUrl(path: String) = "$base/v1/courses/${course.encodeURLPathPart()}/reader" + if (path.isBlank()) "" else "/$path"

    fun close() { http.close() }

    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.i(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
}
