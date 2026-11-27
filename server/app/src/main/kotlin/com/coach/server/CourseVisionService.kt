package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeResult
import com.coach.ai.ChatRequest
import com.coach.ai.ImageAttachment
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

data class CourseVisionReply(
    val text: String,
    val model: String = BridgeClient.OPUS,
    val inputTokens: Int = 1500,
    val outputTokens: Int = 400,
    val elapsedMs: Long = 0L,
)

interface CourseVisionTransport {
    suspend fun recognizePage(
        pageNumber: Int,
        pngBase64: String,
        pageSha256: String,
    ): CourseVisionReply

    suspend fun recognizePage(
        pageNumber: Int,
        pngBase64: String,
        pageSha256: String,
        friend: String?,
        device: String?,
    ): CourseVisionReply = recognizePage(pageNumber, pngBase64, pageSha256)
}

/**
 * Routes all board/handwriting vision OCR calls through Coach Bridge so every provider
 * attempt (Anthropic primary, OpenAI cross-vendor fallback, and friend Anthropic-only)
 * is reserved and settled against the shared 95% grant ledger.
 */
class BridgeCourseVisionTransport(
    private val bridge: BridgeClient,
    private val model: String = BridgeClient.OPUS,
    private val friendModel: String = BridgeClient.CLAUDE,
    private val maxTokens: Int = 2048,
) : CourseVisionTransport {
    override suspend fun recognizePage(
        pageNumber: Int,
        pngBase64: String,
        pageSha256: String,
    ): CourseVisionReply = recognizePage(pageNumber, pngBase64, pageSha256, friend = null, device = null)

    override suspend fun recognizePage(
        pageNumber: Int,
        pngBase64: String,
        pageSha256: String,
        friend: String?,
        device: String?,
    ): CourseVisionReply = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val systemPrompt = "你是严谨的课堂板书与手写笔记转写助手。请严格按原文转写，不要补充正文之外的评论。"
        val promptText = "请完整转写这页课堂板书/手写笔记的文字、公式和图表标注，保留清晰段落结构与原文繁简字体（繁体原文保持繁体、简体保持简体），不要补充正文之外的评论。"
        val targetModel = if (friend != null) friendModel else model
        val result = bridge.chat(
            ChatRequest(
                task = if (friend != null) "READER_SELECTION" else "course_vision_ocr",
                model = targetModel,
                system = systemPrompt,
                user = promptText,
                stream = false,
                background = false,
                maxTokens = maxTokens,
                friendId = friend?.let(::friendId),
                friendDevice = if (friend != null) device else null,
                images = listOf(ImageAttachment("image/png", pngBase64)),
            ),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        when (result) {
            is BridgeResult.Success -> {
                val text = result.text.trim()
                val served = result.terminal.servedBy?.takeIf { it.isNotBlank() } ?: targetModel
                val inTok = result.terminal.promptTokens ?: 1500
                val outTok = result.terminal.completionTokens ?: TokenLedgerPricing.estimateTokens(text)
                CourseVisionReply(
                    text = text,
                    model = served,
                    inputTokens = inTok,
                    outputTokens = outTok,
                    elapsedMs = elapsedMs,
                )
            }
            is BridgeResult.Failure -> throw mapFailure(result)
        }
    }

    private fun mapFailure(failure: BridgeResult.Failure): MockError {
        val terminal = failure.terminal
        val reason = terminal?.reason
        // Only claim "not sent" when the bridge explicitly confirmed zero upstream attempts/reservations.
        val attemptedUpstream = (terminal?.physicalCalls ?: 1) > 0
        return when {
            reason == "missing_key" ->
                MockError(HttpStatusCode.ServiceUnavailable, "视觉识别密钥尚未配置，暂时无法识别手写或扫描板书。")
            freeGrantExhausted(reason) ->
                if (attemptedUpstream) {
                    MockError(
                        HttpStatusCode.BadGateway,
                        "视觉识别主用服务失败（可能已产生预留），且$FREE_GRANT_EXHAUSTED_MESSAGE，未能切换备用服务。",
                    )
                } else {
                    MockError(HttpStatusCode.TooManyRequests, "$FREE_GRANT_EXHAUSTED_MESSAGE，本次请求未发送。")
                }
            reason == "grant_allocation_unavailable" ->
                if (attemptedUpstream) {
                    MockError(
                        HttpStatusCode.BadGateway,
                        "视觉识别主用服务失败（可能已产生预留），且备用赠额配额尚未就绪，未能切换备用服务。",
                    )
                } else {
                    MockError(
                        HttpStatusCode.ServiceUnavailable,
                        "视觉识别赠额配额尚未就绪，本次请求未发送，请检查配额配置。",
                    )
                }
            reason == "api_http_429" ->
                MockError(HttpStatusCode.TooManyRequests, "视觉识别服务当前繁忙，请稍后再试。")
            reason in setOf("unsupported_image", "unsupported_context_price", "unsupported_output_limit") ||
                failure.reason.startsWith("http_400") ->
                MockError(HttpStatusCode.BadRequest, "板书图片格式或大小不符合识别要求，请检查后重试。")
            else ->
                MockError(HttpStatusCode.BadGateway, "视觉识别服务暂时不可用（${reason ?: "bridge_error"}）。")
        }
    }
}

/**
 * On-demand Vision OCR service for <=25-page handwritten/scanned PDFs.
 * Caches recognized page text by `page_sha256` in SQLite to prevent repeat billing.
 */
class CourseVisionService(
    directory: Path,
    private val transport: CourseVisionTransport = BridgeCourseVisionTransport(BridgeClient()),
    private val tokenLedger: BookTokenLedger? = null,
) {
    companion object {
        const val MAX_VISION_PAGES = 25
    }

    private val file = directory.resolve("course-vision-cache.sqlite")

    init {
        Files.createDirectories(directory)
        database().use { db ->
            db.createStatement().use { sql ->
                // The old table was keyed by page digest alone and served one account's transcript to another.
                // Its rows have no owner, so they are dropped and rebuilt per account.
                sql.execute("DROP TABLE IF EXISTS vision_cache")
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS vision_cache_scoped(
                        scope TEXT NOT NULL,
                        page_sha256 TEXT NOT NULL,
                        text TEXT NOT NULL,
                        model TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(scope, page_sha256)
                    )""",
                )
            }
        }
    }

    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:$file").apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    /** Cache rows belong to one account: the owner, or one friend (by hashed id). */
    internal fun cacheScope(friend: String?): String = friend?.let { "friend:${friendId(it)}" } ?: "owner"

    /** Account deletion: removes every transcript cached for this friend; other accounts keep theirs. */
    internal fun eraseAccount(friend: String) {
        database().use { db ->
            db.prepareStatement("DELETE FROM vision_cache_scoped WHERE scope=?").use { sql ->
                sql.setString(1, cacheScope(friend))
                sql.executeUpdate()
            }
        }
    }

    fun lookupCached(pageSha256: String, friend: String? = null): Pair<String, String>? = database().use { db ->
        db.prepareStatement("SELECT text, model FROM vision_cache_scoped WHERE scope=? AND page_sha256=?").use { sql ->
            sql.setString(1, cacheScope(friend))
            sql.setString(2, pageSha256)
            sql.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) to rows.getString(2) else null
            }
        }
    }

    private fun storeCached(pageSha256: String, text: String, model: String, friend: String?) {
        if (text.isBlank()) return
        database().use { db ->
            db.prepareStatement(
                """INSERT INTO vision_cache_scoped(scope, page_sha256, text, model, updated_at)
                   VALUES(?,?,?,?,?)
                   ON CONFLICT(scope, page_sha256) DO UPDATE SET text=excluded.text, model=excluded.model, updated_at=excluded.updated_at""",
            ).use { sql ->
                sql.setString(1, cacheScope(friend))
                sql.setString(2, pageSha256)
                sql.setString(3, text)
                sql.setString(4, model)
                sql.setLong(5, System.currentTimeMillis())
                sql.executeUpdate()
            }
        }
    }

    private fun enrichBatchFailure(
        failedPage: Int,
        completedPages: List<Int>,
        error: MockError,
    ): MockError {
        if (completedPages.isEmpty()) return error
        val completedSummary = if (completedPages.size == 1) {
            "第 ${completedPages.first()} 页已完成"
        } else {
            "已有 ${completedPages.size} 页已完成"
        }
        val raw = error.message.orEmpty()
        val updated = if ("本次请求未发送" in raw) {
            raw.replace("本次请求未发送", "第 ${failedPage} 页未发送，$completedSummary")
        } else {
            "第 ${failedPage} 页识别失败（$completedSummary）：$raw"
        }
        return MockError(error.status, updated)
    }

    suspend fun recognizeBatch(
        input: JsonObject,
        friend: String? = null,
        device: String? = null,
    ): JsonObject {
        val bookId = (input["bookId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val totalPages = (input["totalPages"] as? JsonPrimitive)?.intOrNull ?: 0
        val pages = (input["pages"] as? JsonArray) ?: buildJsonArray {}
        if (totalPages > MAX_VISION_PAGES || pages.size > MAX_VISION_PAGES) {
            throw MockError(
                HttpStatusCode.BadRequest,
                "手写或扫描 PDF 超过 $MAX_VISION_PAGES 页上限，请拆分后再发起视觉识别。",
            )
        }
        val outputs = mutableListOf<JsonObject>()
        val completedPages = mutableListOf<Int>()
        var cacheHits = 0
        var billedCalls = 0
        for (el in pages) {
            val obj = el as? JsonObject ?: continue
            val pageNum = (obj["pageNumber"] as? JsonPrimitive)?.intOrNull ?: continue
            val sha = (obj["pageSha256"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val pngB64 = (obj["pngBase64"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            require(sha.isNotBlank()) { "缺少页面摘要（pageSha256）。" }

            val hit = lookupCached(sha, friend)
            if (hit != null) {
                cacheHits++
                completedPages += pageNum
                outputs += buildJsonObject {
                    put("pageNumber", pageNum)
                    put("pageSha256", sha)
                    put("text", hit.first)
                    put("model", hit.second)
                    put("cached", true)
                }
                continue
            }

            if (pngB64.isBlank()) {
                throw MockError(HttpStatusCode.BadRequest, "第 $pageNum 页未命中缓存且缺少图片数据。")
            }
            val reply = try {
                transport.recognizePage(pageNum, pngB64, sha, friend = friend, device = device)
            } catch (e: MockError) {
                throw enrichBatchFailure(pageNum, completedPages, e)
            }
            storeCached(sha, reply.text, reply.model, friend)
            completedPages += pageNum
            billedCalls++
            if (friend == null) {
                tokenLedger?.record(
                    TokenLedgerEntry(
                        bookId = bookId.ifBlank { "vision" },
                        chapterId = "vision",
                        sectionId = "p$pageNum",
                        task = "course_vision_ocr",
                        routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                        model = reply.model,
                        inputTokens = reply.inputTokens,
                        outputTokens = reply.outputTokens,
                        elapsedMs = reply.elapsedMs,
                    ),
                )
            }
            outputs += buildJsonObject {
                put("pageNumber", pageNum)
                put("pageSha256", sha)
                put("text", reply.text)
                put("model", reply.model)
                put("cached", false)
            }
        }
        return buildJsonObject {
            put("bookId", bookId)
            put("pages", buildJsonArray { outputs.forEach { add(it) } })
            put("cacheHits", cacheHits)
            put("billedCalls", billedCalls)
        }
    }
}
