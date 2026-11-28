package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeResult
import com.coach.ai.ChatRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

data class CourseAlignReply(
    val text: String,
    val routeChannel: CompanionRouteChannel,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val elapsedMs: Long,
)

interface CourseAlignTransport {
    suspend fun alignCourse(
        systemPrompt: String,
        userPrompt: String,
        batchKey: String,
        maxTokens: Int = 3200,
    ): CourseAlignReply

    /** Friends use their own Bridge route and quota. Transports without one refuse instead of spending the owner's. */
    suspend fun alignCourseForFriend(
        systemPrompt: String,
        userPrompt: String,
        batchKey: String,
        maxTokens: Int = 3200,
        friend: String,
        device: String,
    ): CourseAlignReply = throw FriendServiceError()
}

/**
 * Course week/topic planner backed by Sonnet 5.5 via Coach Bridge (`task = "COURSE_PLAN"` -> `planning` route).
 */
class SonnetBridgeAlignTransport(
    private val bridge: BridgeClient,
) : CourseAlignTransport {
    override suspend fun alignCourse(
        systemPrompt: String,
        userPrompt: String,
        batchKey: String,
        maxTokens: Int,
    ): CourseAlignReply = plan(systemPrompt, userPrompt, maxTokens, friend = null, device = null)

    override suspend fun alignCourseForFriend(
        systemPrompt: String,
        userPrompt: String,
        batchKey: String,
        maxTokens: Int,
        friend: String,
        device: String,
    ): CourseAlignReply = plan(systemPrompt, userPrompt, maxTokens, friend, device)

    private suspend fun plan(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        friend: String?,
        device: String?,
    ): CourseAlignReply = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val result = bridge.chat(
            ChatRequest(
                task = if (friend != null) "READER_SELECTION" else "COURSE_PLAN",
                model = BridgeClient.CLAUDE,
                system = systemPrompt,
                user = userPrompt,
                stream = false,
                background = false,
                maxTokens = maxTokens,
                friendId = friend?.let(::friendId),
                friendDevice = if (friend != null) device else null,
            ),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        when (result) {
            is BridgeResult.Failure -> {
                result.rejectIfFreeGrantExhausted()
                if (friend != null) throw FriendServiceError(friendFailureCode(result.terminal?.reason))
                throw IllegalStateException("课程自动对齐失败：${result.reason}")
            }
            is BridgeResult.Success -> CourseAlignReply(
                text = result.text,
                routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                model = result.terminal.servedBy ?: BridgeClient.CLAUDE,
                inputTokens = TokenLedgerPricing.estimateTokens(systemPrompt) + TokenLedgerPricing.estimateTokens(userPrompt),
                outputTokens = TokenLedgerPricing.estimateTokens(result.text),
                elapsedMs = elapsedMs,
            )
        }
    }
}

class CourseTopicPackCache(directory: Path) {
    private val file = directory.resolve("course-topic-cache.sqlite")

    init {
        Files.createDirectories(directory)
        database().use { db ->
            db.createStatement().use { sql ->
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS course_topic_cache(
                        cache_key TEXT PRIMARY KEY,
                        course_id TEXT NOT NULL,
                        topic_id TEXT NOT NULL,
                        sources_hash TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""",
                )
            }
        }
    }

    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:$file").apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    fun get(cacheScope: String, courseId: String, topicId: String, sourcesHash: String): JsonObject? {
        val key = "textbook-first|$cacheScope|$courseId|$topicId|$sourcesHash"
        val raw = database().use { db ->
            db.prepareStatement("SELECT payload_json FROM course_topic_cache WHERE cache_key=?").use { sql ->
                sql.setString(1, key)
                sql.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
        } ?: return null
        return runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    fun put(cacheScope: String, courseId: String, topicId: String, sourcesHash: String, payload: JsonObject) {
        val key = "textbook-first|$cacheScope|$courseId|$topicId|$sourcesHash"
        database().use { db ->
            db.prepareStatement(
                """INSERT INTO course_topic_cache(cache_key, course_id, topic_id, sources_hash, payload_json, updated_at)
                   VALUES(?,?,?,?,?,?)
                   ON CONFLICT(cache_key) DO UPDATE SET payload_json=excluded.payload_json, updated_at=excluded.updated_at""",
            ).use { sql ->
                sql.setString(1, key)
                sql.setString(2, courseId)
                sql.setString(3, topicId)
                sql.setString(4, sourcesHash)
                sql.setString(5, payload.toString())
                sql.setLong(6, System.currentTimeMillis())
                sql.executeUpdate()
            }
        }
    }
}

/**
 * Multi-material course alignment using Sonnet (`COURSE_PLAN`) with Gate-1 anti-leakage sanitization
 * and strict lock preservation hints (`userLocked=true` materials stay pinned).
 */
class CourseAlignmentPipeline(
    private val transport: CourseAlignTransport,
    private val tokenLedger: BookTokenLedger? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun align(input: JsonObject, friend: String? = null, device: String? = null): JsonObject {
        val courseId = (input["courseId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val courseTitle = (input["courseTitle"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val materials = (input["materials"] as? JsonArray) ?: buildJsonArray {}
        var sanitizedCount = 0
        val cleanMaterials = buildJsonArray {
            for (el in materials) {
                val obj = el as? JsonObject ?: continue
                val title = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val role = (obj["role"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (role.equals("PAST_EXAM", ignoreCase = true) || CourseQualityGates.isPastExamTitle(title)) continue
                val preview = (obj["previewText"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val (cleanPreview, redactions) = if (role == "USER_NOTES") {
                    CourseQualityGates.sanitizeUserNote(preview)
                } else {
                    preview to 0
                }
                sanitizedCount += redactions
                add(buildJsonObject {
                    put("bookId", (obj["bookId"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    put("title", title)
                    put("format", (obj["format"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    put("role", role)
                    (obj["weekNum"] as? JsonPrimitive)?.intOrNull?.let { put("weekNum", it) }
                    put("userLocked", (obj["userLocked"] as? JsonPrimitive)?.booleanOrNull == true)
                    put("pageCount", (obj["pageCount"] as? JsonPrimitive)?.intOrNull ?: 1)
                    put("previewText", cleanPreview.take(1200))
                })
            }
        }

        val teachingBookIds = cleanMaterials.mapNotNull { (it.jsonObject["bookId"] as? JsonPrimitive)?.contentOrNull }.toSet()
        val chapters = (input["textbookChapters"] as? JsonArray).orEmpty().filter { chapter ->
            ((chapter as? JsonObject)?.get("bookId") as? JsonPrimitive)?.contentOrNull in teachingBookIds
        }
        val systemPrompt = """
            你是 LearningCoach 课程结构规划器。请根据课程大纲、讲义、习题课/答案、课堂转写、教材章节和笔记，输出按周组织的备考专题 JSON。
            严格规则：
            1) 任何标记 userLocked=true 的材料，禁止修改其 weekNum 与 role。
            2) 只在大纲或讲义出现过的教材章节关联到对应 EXAMINABLE 专题；未覆盖的教材章节放进 examStatus="EXCLUDED"。
            3) 大纲提到但暂无讲义/转写材料的周次标为 examStatus="PENDING_MATERIALS"。
            4) 若大纲中的考试日期或题型未最终确定，examDateTentative=true / examFormatTentative=true。
            5) 严格输出 JSON 对象，包含 examProfile、materialUpdates、topics 三个字段。
        """.trimIndent()
        val userPrompt = buildJsonObject {
            put("courseId", courseId)
            put("courseTitle", courseTitle)
            put("materials", cleanMaterials)
            put("textbookChapters", JsonArray(chapters))
        }.toString()

        val reply = if (friend != null) {
            transport.alignCourseForFriend(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                batchKey = "course-align-$courseId",
                friend = friend,
                device = device.orEmpty(),
            )
        } else {
            transport.alignCourse(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                batchKey = "course-align-$courseId",
            )
        }
        // The owner's AI-call table only records the owner's calls; the friend's usage is kept by the Bridge.
        if (friend == null) tokenLedger?.record(
            TokenLedgerEntry(
                bookId = courseId.ifBlank { "course" },
                chapterId = "align",
                sectionId = "plan",
                task = "course_align",
                routeChannel = reply.routeChannel,
                model = reply.model,
                inputTokens = reply.inputTokens,
                outputTokens = reply.outputTokens,
                elapsedMs = reply.elapsedMs,
            ),
        )

        val parsed = extractJsonObject(reply.text)
        return buildJsonObject {
            parsed["examProfile"]?.let { put("examProfile", it) }
            put("materialUpdates", (parsed["materialUpdates"] as? JsonArray) ?: buildJsonArray {})
            put("topics", (parsed["topics"] as? JsonArray) ?: buildJsonArray {})
            put("spoilerSanitizedCount", sanitizedCount)
            put("model", reply.model)
        }
    }

    private fun extractJsonObject(raw: String): JsonObject {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val slice = if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
        return json.parseToJsonElement(slice).jsonObject
    }
}

/**
 * Multi-material topic study-pack pipeline:
 * Map (parallel per bounded source chunk via CodeBuddy/Haiku batch) ->
 * Gate 2 (UTF-16 verbatim anchor verification) ->
 * Reduce (L1->L4 authority ordering + discrepancies + essay prompt) ->
 * Gate 3 (bilingual zero n-gram overlap) & Gate 4 (`BigDecimal` 2dp math verifier).
 */
class CourseTopicSynthesisPipeline(
    private val transport: BatchAiTransport,
    private val tokenLedger: BookTokenLedger? = null,
    private val cache: CourseTopicPackCache? = null,
    maxMapConcurrency: Int = 2,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSemaphore = Semaphore(maxMapConcurrency.coerceAtLeast(1))

    suspend fun generateTopicPack(
        input: JsonObject,
        cacheScope: String = "owner",
        bypassCache: Boolean = false,
        currentCheck: suspend () -> Boolean = { true },
        friend: String? = null,
        device: String? = null,
    ): JsonObject = coroutineScope {
        // A friend's calls use their own Bridge route and quota and never land in the owner's AI-call table.
        val activeTransport = if (friend != null) FriendScopedBatchTransport(transport, friend, device.orEmpty()) else transport
        val ownerLedger = tokenLedger.takeIf { friend == null }
        val courseId = (input["courseId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val topicId = (input["topicId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val topicTitle = (input["topicTitle"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val sourcesHash = (input["sourcesHash"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val examDateTentative = (input["examDateTentative"] as? JsonPrimitive)?.booleanOrNull ?: true
        val examFormatTentative = (input["examFormatTentative"] as? JsonPrimitive)?.booleanOrNull ?: true
        val examFormat = (input["examFormat"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        if (!bypassCache && sourcesHash.isNotBlank()) {
            cache?.get(cacheScope, courseId, topicId, sourcesHash)?.let { cached ->
                return@coroutineScope cached
            }
        }

        // Gate 1: sanitize USER_NOTES and reject any past-paper source blocks
        var redactedCount = 0
        val rawBlocks = ((input["blocks"] as? JsonArray) ?: buildJsonArray {}).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val bookTitle = (o["bookTitle"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val role = (o["role"] as? JsonPrimitive)?.contentOrNull ?: "LECTURE_SLIDES"
            if (role.equals("PAST_EXAM", ignoreCase = true) || CourseQualityGates.isPastExamTitle(bookTitle)) return@mapNotNull null
            val rawText = (o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val (cleanText, hits) = if (role == "USER_NOTES") {
                CourseQualityGates.sanitizeUserNote(rawText)
            } else {
                rawText to 0
            }
            redactedCount += hits
            if (cleanText.isBlank()) return@mapNotNull null
            CourseMaterialTextBlock(
                blockId = (o["blockId"] as? JsonPrimitive)?.contentOrNull ?: "${o["bookId"]?.jsonPrimitive?.contentOrNull}:p${o["page"]?.jsonPrimitive?.intOrNull ?: 1}:0",
                bookId = (o["bookId"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                bookTitle = bookTitle,
                role = role,
                authorityRank = when (role) {
                    "CORE_TEXTBOOK" -> 1
                    "LECTURE_SLIDES" -> 2
                    "USER_NOTES" -> 4
                    else -> 3
                },
                page = ((o["page"] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtLeast(1),
                startUtf16 = (o["startUtf16"] as? JsonPrimitive)?.intOrNull ?: 0,
                text = cleanText,
            )
        }.sortedWith(compareBy<CourseMaterialTextBlock>({ it.authorityRank }, { it.page }))

        val blocksByBookPage = rawBlocks.groupBy { it.bookId to it.page }
        val allBlocksByBook = rawBlocks.groupBy { it.bookId }
        val sourceTexts = rawBlocks.map { it.text }

        // Map stage: bound concurrent map chunks so opening a large course never floods upstream rate limits.
        val chunkSize = 4
        val chunks = rawBlocks.chunked(chunkSize)
        val mapDeferred = chunks.mapIndexed { chunkIdx, group ->
            async {
                mapSemaphore.withPermit {
                    check(currentCheck()) { "设备授权或来源校验已失效。" }
                    val mapPrompt = buildJsonObject {
                        put("topicId", topicId)
                        put("topicTitle", topicTitle)
                        put("blocks", buildJsonArray {
                            for (b in group) {
                                add(buildJsonObject {
                                    put("blockId", b.blockId)
                                    put("bookId", b.bookId)
                                    put("bookTitle", b.bookTitle)
                                    put("role", b.role)
                                    put("authorityRank", b.authorityRank)
                                    put("page", b.page)
                                    put("startUtf16", b.startUtf16)
                                    put("text", b.text)
                                })
                            }
                        })
                    }.toString()
                    val reply = activeTransport.generateSection(
                        systemPrompt = """
                            请从以下多源课程块提取本专题要点、交叉印证线索及精确逐字引用。
                            每条 citation 必须给出 bookId、page、startUtf16、endUtf16、quote（必须逐字复制原文连续片段，课堂转写请附带 timestampLabel）。
                            严格输出 JSON：{"claims":[{"title":"...","detail":"...","role":"...","authorityRank":1}],"citations":[{"bookId":"...","page":1,"startUtf16":0,"endUtf16":10,"timestampLabel":null,"quote":"..."}]}
                        """.trimIndent(),
                        userPrompt = mapPrompt,
                        task = "course_topic_map",
                        batchKey = "course-map-$topicId-$chunkIdx",
                        maxTokens = 16_000,
                    )
                    ownerLedger?.record(
                        TokenLedgerEntry(
                            bookId = courseId.ifBlank { topicId },
                            chapterId = topicId,
                            sectionId = "map-$chunkIdx",
                            task = "course_topic_map",
                            routeChannel = reply.routeChannel,
                            model = reply.model,
                            inputTokens = reply.inputTokens,
                            outputTokens = reply.outputTokens,
                            cachedTokens = reply.cachedTokens,
                            ttftMs = reply.ttftMs,
                            elapsedMs = reply.elapsedMs,
                        ),
                    )
                    val parsed = extractJsonObject(reply.text)
                    val verifiedCitations = ((parsed["citations"] as? JsonArray) ?: buildJsonArray {}).mapNotNull { cEl ->
                        (cEl as? JsonObject)?.let { cObj ->
                            CourseQualityGates.verifyCitation(cObj, blocksByBookPage, allBlocksByBook)
                        }
                    }
                    parsed to verifiedCitations
                }
            }
        }
        val mapped = mapDeferred.awaitAll()
        val verifiedMapCitations = mapped.flatMap { it.second }.distinctBy { Triple(it.bookId, it.page, it.startUtf16) }

        check(currentCheck()) { "设备授权或来源校验已失效。" }
        // Reduce stage: merge into L1->L4 authority structured study pack + discrepancy block + original 300-word essay prompt
        val reducePrompt = buildJsonObject {
            put("topicId", topicId)
            put("topicTitle", topicTitle)
            put("examDateTentative", examDateTentative)
            put("examFormatTentative", examFormatTentative)
            put("examFormat", examFormat)
            val allowedTechniques = setOf("CALCULATION", "COMPARISON", "APPLICATION", "EXPLANATION", "RECALL")
            put("examTechniques", JsonArray((input["examTechniques"] as? JsonArray).orEmpty()
                .filter { (it as? JsonPrimitive)?.contentOrNull in allowedTechniques }.distinct()))
            put("mappedClaims", buildJsonArray { mapped.forEach { add(it.first) } })
            put("verifiedCitations", buildJsonArray { verifiedMapCitations.forEach { add(it.toJson()) } })
        }.toString()

        val reduceSystem = """
            你是 LearningCoach 课程总控合卷器。请基于已核验的 map 提取结果生成专题备考包 JSON。
            严格规则：
            1) 权威顺序：L1（CORE_TEXTBOOK）> L2（LECTURE_SLIDES）> L3（HANDOUT/SYLLABUS/TUTORIAL/SOLUTION_KEY/TRANSCRIPT/REQUIRED_READING）> L4（USER_NOTES）。
            2) 若 L1 与 L2/L3 对同一机制、定义或重点范围讲法不同，必须在 discrepancies 明确标出，结论优先采信 L1。
            3) 按 examTechniques 指定的考法（如有）用全新情境编写原创 practicePrompt（建议 300 词短论述），严禁连续照搬来源原文超过 8 个英文词或 12 个汉字。
            4) 若生成含算式的示例，必须附带 mathAssertions 数组 [{"expression":"...","expected":123.45}] 供独立复算核验。
            5) citations 只引用已提供的 verifiedCitations，不可编造页码或引号。
            输出 JSON 字段：summary, authorityNotice, examFocus, keyPoints, discrepancies, practicePrompt, mathAssertions, citations。
        """.trimIndent()

        val reduceReply = activeTransport.generateSection(
            systemPrompt = reduceSystem,
            userPrompt = reducePrompt,
            task = "course_topic_reduce",
            batchKey = "course-reduce-$topicId",
            maxTokens = 16_000,
        )
        ownerLedger?.record(
            TokenLedgerEntry(
                bookId = courseId.ifBlank { topicId },
                chapterId = topicId,
                sectionId = "reduce",
                task = "course_topic_reduce",
                routeChannel = reduceReply.routeChannel,
                model = reduceReply.model,
                inputTokens = reduceReply.inputTokens,
                outputTokens = reduceReply.outputTokens,
                cachedTokens = reduceReply.cachedTokens,
                ttftMs = reduceReply.ttftMs,
                elapsedMs = reduceReply.elapsedMs,
            ),
        )
        val reduced = extractJsonObject(reduceReply.text)

        // Gate 2 again on reduced pack citations
        val packCitations = ((reduced["citations"] as? JsonArray) ?: buildJsonArray {}).mapNotNull { el ->
            (el as? JsonObject)?.let { CourseQualityGates.verifyCitation(it, blocksByBookPage, allBlocksByBook) }
        }.ifEmpty { verifiedMapCitations }

        // Gate 4: Independent BigDecimal math audit (2 decimal places) across mathAssertions + inline equations
        fun parseMathAssertions(obj: JsonObject): List<CourseMathAssertion> =
            ((obj["mathAssertions"] as? JsonArray) ?: buildJsonArray {}).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val expr = (o["expression"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val expRaw = (o["expected"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val expDec = runCatching { BigDecimal(expRaw) }.getOrNull() ?: return@mapNotNull null
                if (expr.isBlank()) return@mapNotNull null
                CourseMathAssertion(expr, expDec)
            }

        fun collectProseStrings(obj: JsonObject): List<String> = buildList {
            (obj["summary"] as? JsonPrimitive)?.contentOrNull?.let(::add)
            (obj["examFocus"] as? JsonPrimitive)?.contentOrNull?.let(::add)
            (obj["authorityNotice"] as? JsonPrimitive)?.contentOrNull?.let(::add)
            for (kEl in ((obj["keyPoints"] as? JsonArray) ?: buildJsonArray {})) {
                val k = kEl as? JsonObject ?: continue
                (k["title"] as? JsonPrimitive)?.contentOrNull?.let(::add)
                (k["explanation"] as? JsonPrimitive)?.contentOrNull?.let(::add)
            }
            for (dEl in ((obj["discrepancies"] as? JsonArray) ?: buildJsonArray {})) {
                val d = dEl as? JsonObject ?: continue
                (d["point"] as? JsonPrimitive)?.contentOrNull?.let(::add)
                (d["authoritativeView"] as? JsonPrimitive)?.contentOrNull?.let(::add)
                (d["secondaryView"] as? JsonPrimitive)?.contentOrNull?.let(::add)
                (d["advice"] as? JsonPrimitive)?.contentOrNull?.let(::add)
            }
            val pObj = obj["practicePrompt"] as? JsonObject
            (pObj?.get("stem") as? JsonPrimitive)?.contentOrNull?.let(::add)
            for (ptEl in ((pObj?.get("points") as? JsonArray) ?: buildJsonArray {})) {
                (ptEl as? JsonPrimitive)?.contentOrNull?.let(::add)
            }
        }

        fun collectCombinedMathAssertions(obj: JsonObject): List<CourseMathAssertion> {
            val explicit = parseMathAssertions(obj)
            val inline = CourseQualityGates.extractInlineMathAssertions(collectProseStrings(obj))
            return (explicit + inline).distinctBy { it.expression.trim() to it.expected.setScale(2, java.math.RoundingMode.HALF_UP) }
        }

        var activeReduced = reduced
        var combinedAssertions = collectCombinedMathAssertions(activeReduced)
        var mathAudit = CourseQualityGates.auditMathAssertions(combinedAssertions)
        if (combinedAssertions.isNotEmpty() && !mathAudit.passed) {
            val repairReply = activeTransport.generateSection(
                systemPrompt = "以下数学表达式或数值结论未通过独立 2 位小数校验，请修正 mathAssertions 与对应说明后严格返回 JSON。",
                userPrompt = buildJsonObject {
                    put("topicId", topicId)
                    put("failures", buildJsonArray { mathAudit.failures.forEach { add(JsonPrimitive(it)) } })
                    put("prior", activeReduced)
                }.toString(),
                task = "course_topic_math_repair",
                batchKey = "course-math-repair-$topicId",
                maxTokens = 6_000,
            )
            ownerLedger?.record(
                TokenLedgerEntry(
                    bookId = courseId.ifBlank { topicId },
                    chapterId = topicId,
                    sectionId = "math-repair",
                    task = "course_topic_math_repair",
                    routeChannel = repairReply.routeChannel,
                    model = repairReply.model,
                    inputTokens = repairReply.inputTokens,
                    outputTokens = repairReply.outputTokens,
                    cachedTokens = repairReply.cachedTokens,
                    ttftMs = repairReply.ttftMs,
                    elapsedMs = repairReply.elapsedMs,
                ),
            )
            val repairedObj = extractJsonObject(repairReply.text)
            if (repairedObj.isNotEmpty()) {
                activeReduced = JsonObject(activeReduced + repairedObj)
                combinedAssertions = collectCombinedMathAssertions(activeReduced)
                mathAudit = CourseQualityGates.auditMathAssertions(combinedAssertions)
            }
        }

        // Gate 3: Audit & scrub EVERY generated prose field (summary, examFocus, authorityNotice, keyPoints, discrepancies, practicePrompt)
        val allFinalGeneratedProse = mutableListOf<String>()
        fun scrubField(text: String): String {
            if (text.isBlank()) return text
            return if (CourseQualityGates.auditZeroOverlap(listOf(text), sourceTexts).passed) {
                text
            } else {
                CourseQualityGates.scrubPromptOverlap(text, sourceTexts)
            }
        }

        val activePackCitations = ((activeReduced["citations"] as? JsonArray) ?: buildJsonArray {}).mapNotNull { el ->
            (el as? JsonObject)?.let { CourseQualityGates.verifyCitation(it, blocksByBookPage, allBlocksByBook) }
        }.ifEmpty { packCitations }

        val cleanSummary = scrubField(((activeReduced["summary"] as? JsonPrimitive)?.contentOrNull).orEmpty())
            .also { if (it.isNotBlank()) allFinalGeneratedProse += it }
        val cleanExamFocus = scrubField(((activeReduced["examFocus"] as? JsonPrimitive)?.contentOrNull ?: examFormat))
            .also { if (it.isNotBlank()) allFinalGeneratedProse += it }
        val baseAuthorityNotice = scrubField(
            ((activeReduced["authorityNotice"] as? JsonPrimitive)?.contentOrNull).orEmpty()
                .ifBlank { "优先参考教材，其次是课件、讲义。" },
        ).also { if (it.isNotBlank()) allFinalGeneratedProse += it }

        val finalPromptObj = activeReduced["practicePrompt"] as? JsonObject
        val finalPrompt = finalPromptObj?.let { p ->
            val cleanStem = scrubField(
                ((p["stem"] as? JsonPrimitive)?.contentOrNull).orEmpty()
                    .ifBlank { "请用自己的话系统阐述「$topicTitle」的核心概念、前提条件及课堂强调的判别要点（约 300 词）。" },
            )
            allFinalGeneratedProse += cleanStem
            val cleanPoints = ((p["points"] as? JsonArray) ?: buildJsonArray {}).mapNotNull { ptEl ->
                (ptEl as? JsonPrimitive)?.contentOrNull?.let { pt ->
                    scrubField(pt).also { allFinalGeneratedProse += it }
                }
            }
            buildJsonObject {
                put("promptId", (p["promptId"] as? JsonPrimitive)?.contentOrNull ?: "$topicId-essay")
                put("kind", (p["kind"] as? JsonPrimitive)?.contentOrNull ?: "ESSAY")
                put("stem", cleanStem)
                put("wordTarget", (p["wordTarget"] as? JsonPrimitive)?.intOrNull ?: 300)
                put("points", buildJsonArray { cleanPoints.forEach { add(JsonPrimitive(it)) } })
                put("citations", buildJsonArray { activePackCitations.take(3).forEach { add(it.toJson()) } })
            }
        }

        val finalDiscrepancies = buildJsonArray {
            for (dEl in ((activeReduced["discrepancies"] as? JsonArray) ?: buildJsonArray {})) {
                val d = dEl as? JsonObject ?: continue
                val verified = ((d["citations"] as? JsonArray) ?: buildJsonArray {}).mapNotNull { c ->
                    (c as? JsonObject)?.let { CourseQualityGates.verifyCitation(it, blocksByBookPage, allBlocksByBook) }
                }
                val cleanPoint = scrubField((d["point"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    .also { if (it.isNotBlank()) allFinalGeneratedProse += it }
                val cleanAuthView = scrubField((d["authoritativeView"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    .also { if (it.isNotBlank()) allFinalGeneratedProse += it }
                val cleanSecView = scrubField((d["secondaryView"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    .also { if (it.isNotBlank()) allFinalGeneratedProse += it }
                val cleanAdvice = scrubField((d["advice"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    .also { if (it.isNotBlank()) allFinalGeneratedProse += it }
                add(buildJsonObject {
                    put("point", cleanPoint)
                    put("authoritativeView", cleanAuthView)
                    put("secondaryView", cleanSecView)
                    put("advice", cleanAdvice)
                    put("citations", buildJsonArray { verified.forEach { add(it.toJson()) } })
                })
            }
        }

        val finalKeyPoints = buildJsonArray {
            for (kEl in ((activeReduced["keyPoints"] as? JsonArray) ?: buildJsonArray {})) {
                val k = kEl as? JsonObject ?: continue
                val verified = ((k["citations"] as? JsonArray) ?: buildJsonArray {}).mapNotNull { c ->
                    (c as? JsonObject)?.let { CourseQualityGates.verifyCitation(it, blocksByBookPage, allBlocksByBook) }
                }
                val cleanTitle = scrubField((k["title"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    .also { if (it.isNotBlank()) allFinalGeneratedProse += it }
                val cleanExp = scrubField((k["explanation"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    .also { if (it.isNotBlank()) allFinalGeneratedProse += it }
                add(buildJsonObject {
                    put("title", cleanTitle)
                    put("explanation", cleanExp)
                    put("citations", buildJsonArray { verified.forEach { add(it.toJson()) } })
                })
            }
        }

        val authorityNotice = buildString {
            append(baseAuthorityNotice)
            if (!mathAudit.passed && mathAudit.checkedCount > 0) {
                append(" [数值核验提示：部分衍生算式待复核]")
            }
        }

        val finalGate3Audit = CourseQualityGates.auditZeroOverlap(allFinalGeneratedProse, sourceTexts)

        val resultPayload = buildJsonObject {
            put("topicId", topicId)
            put("summary", cleanSummary)
            put("authorityNotice", authorityNotice)
            put("examDateTentative", examDateTentative)
            put("examFormatTentative", examFormatTentative)
            put("examFocus", cleanExamFocus)
            put("keyPoints", finalKeyPoints)
            put("discrepancies", finalDiscrepancies)
            finalPrompt?.let { put("practicePrompt", it) }
            put("citations", buildJsonArray { activePackCitations.forEach { add(it.toJson()) } })
            put("generatedFromHash", sourcesHash)
            put("serverBacked", true)
            put("gates", buildJsonObject {
                put("gate1SpoilerRedactions", redactedCount)
                put("gate2VerifiedCitations", activePackCitations.size)
                put("gate3ZeroOverlapPassed", finalGate3Audit.passed)
                put("gate4MathPassed", mathAudit.passed)
                put("gate4CheckedCount", mathAudit.checkedCount)
                put("gate4Failures", buildJsonArray { mathAudit.failures.forEach { add(JsonPrimitive(it)) } })
            })
        }

        if (sourcesHash.isNotBlank()) {
            cache?.put(cacheScope, courseId, topicId, sourcesHash, resultPayload)
        }
        resultPayload
    }

    private fun extractJsonObject(raw: String): JsonObject {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val slice = if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
        return runCatching { json.parseToJsonElement(slice).jsonObject }.getOrElse { buildJsonObject {} }
    }
}
