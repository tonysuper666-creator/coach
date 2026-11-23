package com.coach.server

import com.coach.ai.BridgeClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration

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
}

class AnthropicHaikuVisionTransport(
    private val apiKeyProvider: () -> String = {
        System.getenv("COACH_ANTHROPIC_API_KEY")?.trim().orEmpty()
            .ifBlank { System.getenv("ANTHROPIC_API_KEY")?.trim().orEmpty() }
    },
    private val openAiKeyProvider: () -> String = {
        System.getenv("COACH_OPENAI_API_KEY")?.trim().orEmpty()
            .ifBlank { System.getenv("OPENAI_API_KEY")?.trim().orEmpty() }
    },
    private val model: String = BridgeClient.OPUS,
    private val fallbackModel: String = BridgeClient.GPT_62_ASTRA,
    private val endpoint: String = "https://api.anthropic.com/v1/messages",
    private val openAiEndpoint: String = "https://api.openai.com/v1/chat/completions",
    private val connectTimeout: Duration = Duration.ofSeconds(10),
    private val requestTimeout: Duration = Duration.ofSeconds(60),
) : CourseVisionTransport {
    private val http = HttpClient.newBuilder().connectTimeout(connectTimeout).build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun recognizePage(
        pageNumber: Int,
        pngBase64: String,
        pageSha256: String,
    ): CourseVisionReply = withContext(Dispatchers.IO) {
        val promptText = "请完整转写这页课堂板书/手写笔记的文字、公式和图表标注，保留清晰段落结构与原文繁简字体（繁体原文保持繁体、简体保持简体），不要补充正文之外的评论。"
        val apiKey = apiKeyProvider()
        if (apiKey.isNotBlank()) {
            try {
                return@withContext callAnthropic(apiKey, pngBase64, promptText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (primaryErr: Exception) {
                val oaiKey = openAiKeyProvider()
                if (oaiKey.isBlank()) {
                    if (primaryErr is MockError) throw primaryErr
                    throw MockError(HttpStatusCode.BadGateway, "视觉识别服务暂时不可用（${primaryErr.javaClass.simpleName}）。")
                }
                return@withContext callOpenAiVision(oaiKey, pngBase64, promptText)
            }
        }
        val oaiKey = openAiKeyProvider()
        if (oaiKey.isNotBlank()) {
            return@withContext callOpenAiVision(oaiKey, pngBase64, promptText)
        }
        throw MockError(HttpStatusCode.ServiceUnavailable, "视觉识别密钥尚未配置，暂时无法识别手写或扫描板书。")
    }

    private fun callAnthropic(apiKey: String, pngBase64: String, promptText: String): CourseVisionReply {
        val started = System.nanoTime()
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", 2048)
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        addJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", "image/png")
                                put("data", pngBase64)
                            })
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", promptText)
                        }
                    })
                }
            })
        }
        val req = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            throw MockError(HttpStatusCode.BadGateway, "视觉识别主用服务暂时不可用（${e.javaClass.simpleName}）。")
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        if (resp.statusCode() !in 200..299) {
            throw MockError(HttpStatusCode.BadGateway, "视觉识别服务暂时不可用（HTTP ${resp.statusCode()}）。")
        }
        val parsed = json.parseToJsonElement(resp.body()).jsonObject
        val text = ((parsed["content"] as? JsonArray) ?: buildJsonArray {})
            .mapNotNull { el -> (el as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull } }
            .joinToString("\n")
            .trim()
        val usage = parsed["usage"] as? JsonObject
        val inTok = (usage?.get("input_tokens") as? JsonPrimitive)?.intOrNull ?: 1500
        val outTok = (usage?.get("output_tokens") as? JsonPrimitive)?.intOrNull ?: TokenLedgerPricing.estimateTokens(text)
        return CourseVisionReply(
            text = text,
            model = model,
            inputTokens = inTok,
            outputTokens = outTok,
            elapsedMs = elapsedMs,
        )
    }

    private fun callOpenAiVision(apiKey: String, pngBase64: String, promptText: String): CourseVisionReply {
        val started = System.nanoTime()
        val payload = buildJsonObject {
            put("model", fallbackModel)
            put("max_completion_tokens", 2048)
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        addJsonObject {
                            put("type", "text")
                            put("text", promptText)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/png;base64,$pngBase64")
                            })
                        }
                    })
                }
            })
        }
        val req = HttpRequest.newBuilder(URI.create(openAiEndpoint))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            throw MockError(HttpStatusCode.BadGateway, "视觉识别兜底服务暂时不可用（${e.javaClass.simpleName}）。")
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        if (resp.statusCode() !in 200..299) {
            throw MockError(HttpStatusCode.BadGateway, "视觉识别兜底服务暂时不可用（HTTP ${resp.statusCode()}）。")
        }
        val parsed = json.parseToJsonElement(resp.body()).jsonObject
        val firstChoice = (parsed["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val msg = firstChoice?.get("message") as? JsonObject
        val text = (msg?.get("content") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val usage = parsed["usage"] as? JsonObject
        val inTok = (usage?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull ?: 1500
        val outTok = (usage?.get("completion_tokens") as? JsonPrimitive)?.intOrNull ?: TokenLedgerPricing.estimateTokens(text)
        return CourseVisionReply(
            text = text,
            model = fallbackModel,
            inputTokens = inTok,
            outputTokens = outTok,
            elapsedMs = elapsedMs,
        )
    }
}

/**
 * On-demand Haiku Vision OCR service for <=25-page handwritten/scanned PDFs.
 * Caches recognized page text by `page_sha256` in SQLite to prevent repeat billing.
 */
class CourseVisionService(
    directory: Path,
    private val transport: CourseVisionTransport = AnthropicHaikuVisionTransport(),
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
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS vision_cache(
                        page_sha256 TEXT PRIMARY KEY,
                        text TEXT NOT NULL,
                        model TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""",
                )
            }
        }
    }

    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:$file").apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    fun lookupCached(pageSha256: String): Pair<String, String>? = database().use { db ->
        db.prepareStatement("SELECT text, model FROM vision_cache WHERE page_sha256=?").use { sql ->
            sql.setString(1, pageSha256)
            sql.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) to rows.getString(2) else null
            }
        }
    }

    private fun storeCached(pageSha256: String, text: String, model: String) {
        if (text.isBlank()) return
        database().use { db ->
            db.prepareStatement(
                """INSERT INTO vision_cache(page_sha256, text, model, updated_at)
                   VALUES(?,?,?,?)
                   ON CONFLICT(page_sha256) DO UPDATE SET text=excluded.text, model=excluded.model, updated_at=excluded.updated_at""",
            ).use { sql ->
                sql.setString(1, pageSha256)
                sql.setString(2, text)
                sql.setString(3, model)
                sql.setLong(4, System.currentTimeMillis())
                sql.executeUpdate()
            }
        }
    }

    suspend fun recognizeBatch(input: JsonObject): JsonObject {
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
        var cacheHits = 0
        var billedCalls = 0
        for (el in pages) {
            val obj = el as? JsonObject ?: continue
            val pageNum = (obj["pageNumber"] as? JsonPrimitive)?.intOrNull ?: continue
            val sha = (obj["pageSha256"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val pngB64 = (obj["pngBase64"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            require(sha.isNotBlank()) { "缺少页面摘要（pageSha256）。" }

            val hit = lookupCached(sha)
            if (hit != null) {
                cacheHits++
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
            val reply = transport.recognizePage(pageNum, pngB64, sha)
            storeCached(sha, reply.text, reply.model)
            billedCalls++
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
