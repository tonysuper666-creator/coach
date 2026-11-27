package com.coach.server

import com.coach.ai.BatchPending
import com.coach.ai.BridgeClient
import com.coach.ai.BridgeResult
import com.coach.ai.BridgeStreamSink
import com.coach.ai.ChatRequest
import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.accept
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** Canonical 6 study modes for LearningCoach 2.0 (`TON-1808`). */
enum class ServerStudyMode {
    READ_THROUGH,
    BOOK_MAP,
    EXAM_FOCUS,
    DIAGNOSTIC_SKIP,
    PROBLEM_DRIVEN,
    QUESTION_DRIVEN,
    ;

    companion object {
        fun parse(raw: String): ServerStudyMode? =
            entries.firstOrNull { it.name.equals(raw.trim().uppercase(Locale.US), ignoreCase = true) }
    }
}

enum class ServerDetailLevel {
    ORIGINAL_ONLY,
    BRIEF,
    DETAILED,
    ;

    companion object {
        fun parse(raw: String?): ServerDetailLevel =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DETAILED
    }
}

enum class ServerExplainLang {
    ZH,
    BILINGUAL,
    EN,
    ;

    companion object {
        fun parse(raw: String?): ServerExplainLang =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: BILINGUAL
    }
}

data class StudyPageInput(
    val pdfPage: Int,
    val text: String,
)

data class StudySectionInput(
    val sectionId: String,
    val title: String,
    val startPdfPage: Int,
    val endPdfPage: Int,
    val text: String = "",
)

data class StudyModeRequest(
    val bookId: String,
    val bookTitle: String,
    val chapterNum: Int,
    val chapterTitle: String,
    val mode: ServerStudyMode,
    val detailLevel: ServerDetailLevel,
    val explainLang: ServerExplainLang,
    val inquiryQuestion: String = "",
    val courseTopics: List<String> = emptyList(),
    val pages: List<StudyPageInput> = emptyList(),
    val sections: List<StudySectionInput> = emptyList(),
    val pastExamForbiddenFragments: List<String> = emptyList(),
    val bypassCache: Boolean = false,
    val requestId: String = "",
    val friend: String? = null,
    val device: String = "owner",
)

data class BookMapChapterInput(
    val chapterNum: Int,
    val chapterTitle: String,
    val pages: List<StudyPageInput>,
    val sections: List<StudySectionInput> = emptyList(),
)

data class BookMapBatchRequest(
    val bookId: String,
    val bookTitle: String,
    val detailLevel: ServerDetailLevel,
    val explainLang: ServerExplainLang,
    val courseTopics: List<String> = emptyList(),
    val chapters: List<BookMapChapterInput>,
    val useBatchApi: Boolean = true,
    val bypassCache: Boolean = false,
    val requestId: String = "",
    val friend: String? = null,
    val device: String = "owner",
)

data class StudyModeTransportReply(
    val text: String,
    val routeChannel: CompanionRouteChannel,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val firstTokenMs: Long,
    val elapsedMs: Long,
)

data class StudyModeAnchorStats(
    val total: Int = 0,
    val verified: Int = 0,
    val unverified: Int = 0,
    val relocated: Int = 0,
    val dropped: Int = 0,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("total", total)
        put("verified", verified)
        put("unverified", unverified)
        put("relocated", relocated)
        put("dropped", dropped)
    }
}

data class StudyModeMathStats(
    val total: Int = 0,
    val verified: Int = 0,
    val repaired: Int = 0,
    val failed: Int = 0,
    val dropped: Int = 0,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("total", total)
        put("verified", verified)
        put("repaired", repaired)
        put("failed", failed)
        put("dropped", dropped)
    }
}

/** Streaming Haiku 5.5 transport for single-chapter cold first-screen (<10s). Never routes to CodeBuddy. */
interface StudyModeStreamTransport {
    fun streamGenerate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        friend: String?,
        device: String?,
        onDelta: (delta: String, atMs: Long) -> Unit,
    ): StudyModeTransportReply
}

/** Batch/completion Haiku 5.5 transport for whole-book Book-Map batch & targeted repair. Never routes to CodeBuddy. */
interface StudyModeBatchTransport {
    fun complete(
        task: String,
        jobKey: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        useBatchApi: Boolean,
        friend: String?,
        device: String?,
    ): StudyModeTransportReply
}

class HaikuStudyModeStreamTransport(
    private val bridge: BridgeClient,
) : StudyModeStreamTransport {
    override fun streamGenerate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        friend: String?,
        device: String?,
        onDelta: (delta: String, atMs: Long) -> Unit,
    ): StudyModeTransportReply {
        val startedNs = System.nanoTime()
        val firstDeltaMs = AtomicLong(0L)
        val buffer = StringBuilder()
        val targetTask = if (friend != null) "READER_SELECTION" else "STUDY_MODE_STREAM"
        val targetModel = if (friend != null) BridgeClient.CLAUDE else BridgeClient.GPT_62_ASTRA
        var servedByName = targetModel
        val result = bridge.streamChat(
            ChatRequest(
                task = targetTask,
                model = targetModel,
                system = systemPrompt,
                user = userPrompt,
                stream = true,
                effort = "low",
                background = false,
                maxTokens = maxTokens.coerceIn(512, 8192),
                friendId = friend?.let(::friendId),
                friendDevice = if (friend != null) device else null,
            ),
            object : BridgeStreamSink {
                override val supportsReset: Boolean = true
                override fun onReset() {
                    buffer.setLength(0)
                    firstDeltaMs.set(0L)
                }

                override fun onMeta(servedBy: String) {
                    if (servedBy.isNotBlank()) servedByName = servedBy
                }

                override fun onDelta(text: String, atMs: Long) {
                    if (text.isNotEmpty()) {
                        firstDeltaMs.compareAndSet(0L, atMs.coerceAtLeast(1L))
                        buffer.append(text)
                        onDelta(text, atMs)
                    }
                }
            },
        )
        val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
        return when (result) {
            is BridgeResult.Failure -> throw IllegalStateException("学法流式生成失败：${result.reason}")
            is BridgeResult.Success -> {
                val finalText = result.text.ifBlank { buffer.toString() }
                StudyModeTransportReply(
                    text = finalText,
                    routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                    model = result.terminal.servedBy?.ifBlank { servedByName } ?: servedByName,
                    inputTokens = TokenLedgerPricing.estimateTokens(systemPrompt) + TokenLedgerPricing.estimateTokens(userPrompt),
                    outputTokens = TokenLedgerPricing.estimateTokens(finalText),
                    firstTokenMs = firstDeltaMs.get().let { if (it > 0L) it else elapsedMs },
                    elapsedMs = elapsedMs,
                )
            }
        }
    }
}

class HaikuStudyModeBatchTransport(
    private val bridge: BridgeClient,
) : StudyModeBatchTransport {
    override fun complete(
        task: String,
        jobKey: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        useBatchApi: Boolean,
        friend: String?,
        device: String?,
    ): StudyModeTransportReply {
        val startedNs = System.nanoTime()
        val batchEligible = useBatchApi && friend == null
        val targetModel = if (friend != null) BridgeClient.CLAUDE else BridgeClient.GPT_62_ASTRA
        val client = if (batchEligible) bridge.forBatchJob("studymode", jobKey) else bridge
        val request = ChatRequest(
            task = if (friend != null) "READER_SELECTION" else task,
            model = targetModel,
            system = systemPrompt,
            user = userPrompt,
            stream = false,
            effort = if (batchEligible) null else "low",
            background = batchEligible,
            maxTokens = maxTokens.coerceIn(512, 8192),
            friendId = friend?.let(::friendId),
            friendDevice = if (friend != null) device else null,
        )
        var attempts = 0
        while (true) {
            try {
                val result = client.chat(request)
                val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
                return when (result) {
                    is BridgeResult.Failure -> throw IllegalStateException("全书地图批处理失败：${result.reason}")
                    is BridgeResult.Success -> StudyModeTransportReply(
                        text = result.text,
                        routeChannel = if (batchEligible) CompanionRouteChannel.HAIKU_BATCH else CompanionRouteChannel.HAIKU_REALTIME,
                        model = result.terminal.servedBy?.ifBlank { targetModel } ?: targetModel,
                        inputTokens = TokenLedgerPricing.estimateTokens(systemPrompt) + TokenLedgerPricing.estimateTokens(userPrompt),
                        outputTokens = TokenLedgerPricing.estimateTokens(result.text),
                        firstTokenMs = elapsedMs,
                        elapsedMs = elapsedMs,
                    )
                }
            } catch (pending: BatchPending) {
                attempts += 1
                if (attempts > 400) throw IllegalStateException("全书地图批处理等待超时，请重试。")
                Thread.sleep(pending.retryAfterMillis.coerceIn(500L, 15_000L))
            }
        }
    }
}

/** Thread-safe SSE sink wrapping Ktor's synchronous Writer so streaming deltas push immediately to the socket. */
interface StudyModeEventSink {
    fun emit(event: String, data: JsonObject)
}

internal class WriterStudyModeEventSink(private val writer: Writer) : StudyModeEventSink {
    private val lock = Any()
    override fun emit(event: String, data: JsonObject) {
        synchronized(lock) {
            writer.write("event: ")
            writer.write(event)
            writer.write("\ndata: ")
            writer.write(data.toString())
            writer.write("\n\n")
            writer.flush()
        }
    }
}

internal object StudyModeJsonHelpers {
    private val fenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    fun extractJsonObject(raw: String): JsonObject {
        val cleaned = raw.trim().let { text ->
            val match = fenceRegex.find(text)
            (match?.groupValues?.getOrNull(1) ?: text).trim()
        }
        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            throw IllegalStateException("模型输出缺少 JSON 对象。")
        }
        val slice = cleaned.substring(firstBrace, lastBrace + 1)
        return LedgerJson.parseToJsonElement(slice).jsonObject
    }

    fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

internal object StudyModeAntiGhostwriteGuard {
    private val essayBannerRegex = Regex(
        """(参考范文|完整范文|全文如下|以下为您代写|可直接提交的论文|In conclusion,\s*this essay|To conclude,\s*we have shown throughout this paper)""",
        RegexOption.IGNORE_CASE,
    )

    fun enforce(mode: ServerStudyMode, payload: JsonObject): JsonObject {
        if (mode != ServerStudyMode.QUESTION_DRIVEN && mode != ServerStudyMode.EXAM_FOCUS) return payload
        val mutable = payload.toMutableMap()
        if (mode == ServerStudyMode.QUESTION_DRIVEN) {
            val direct = mutable["directConclusion"]?.jsonPrimitive?.contentOrNull.orEmpty()
            mutable["directConclusion"] = JsonPrimitive(sanitizeConclusion(direct, 220))
            (mutable["argumentationScaffold"] as? JsonArray)?.let { arr ->
                mutable["argumentationScaffold"] = buildJsonArray {
                    arr.forEach { elem ->
                        val text = (elem as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (text.isNotBlank() && !essayBannerRegex.containsMatchIn(text)) {
                            add(JsonPrimitive(truncateAtBoundary(text, 220)))
                        }
                    }
                }
            }
            if (!mutable.containsKey("antiGhostwriteNotice")) {
                mutable["antiGhostwriteNotice"] = JsonPrimitive("仅提供原文证据索引与论证骨架，请用自己的话完成作答。")
            }
        }
        if (mode == ServerStudyMode.EXAM_FOCUS) {
            (mutable["miniChecks"] as? JsonArray)?.let { checks ->
                mutable["miniChecks"] = buildJsonArray {
                    checks.forEach { elem ->
                        val obj = (elem as? JsonObject)?.toMutableMap() ?: return@forEach
                        val rubric = obj["rubricOnly"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        obj["rubricOnly"] = JsonPrimitive(sanitizeConclusion(rubric, 180))
                        add(JsonObject(obj))
                    }
                }
            }
        }
        return JsonObject(mutable)
    }

    private fun sanitizeConclusion(raw: String, maxChars: Int): String {
        val stripped = raw.replace(essayBannerRegex, "").trim()
        val firstPara = stripped.split(Regex("\\n{2,}")).firstOrNull().orEmpty().trim()
        return truncateAtBoundary(firstPara, maxChars)
    }

    private fun truncateAtBoundary(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val stop = maxOf(cut.lastIndexOf('。'), cut.lastIndexOf('.'), cut.lastIndexOf('；'), cut.lastIndexOf(';'))
        return if (stop >= maxChars / 2) cut.substring(0, stop + 1) else "$cut…"
    }
}

internal object StudyModePastExamGuard {
    private val asciiWordRegex = Regex("[a-z0-9]{2,}")
    private val zhCharRegex = Regex("[\\u4e00-\\u9fff]")

    fun checkOverlap(payload: JsonObject, forbiddenFragments: List<String>): Int {
        if (forbiddenFragments.isEmpty()) return 0
        val asciiForbidden = mutableSetOf<String>()
        val zhForbidden = mutableSetOf<String>()
        forbiddenFragments.forEach { frag ->
            val words = asciiWordRegex.findAll(frag.lowercase(Locale.US)).map { it.value }.toList()
            for (i in 0..words.size - 8) {
                asciiForbidden.add(words.subList(i, i + 8).joinToString(" "))
            }
            val zh = zhCharRegex.findAll(frag).map { it.value }.joinToString("")
            for (i in 0..zh.length - 12) {
                zhForbidden.add(zh.substring(i, i + 12))
            }
        }
        var hits = 0
        fun walk(el: JsonElement, key: String) {
            when (el) {
                is JsonObject -> el.forEach { (k, v) -> walk(v, k) }
                is JsonArray -> el.forEach { walk(it, key) }
                is JsonPrimitive -> {
                    if (!el.isString || key == "quoteAnchor" || key == "exerciseQuoteAnchor") return
                    val text = el.content
                    if (asciiForbidden.isNotEmpty()) {
                        val words = asciiWordRegex.findAll(text.lowercase(Locale.US)).map { it.value }.toList()
                        for (i in 0..words.size - 8) {
                            if (words.subList(i, i + 8).joinToString(" ") in asciiForbidden) hits++
                        }
                    }
                    if (zhForbidden.isNotEmpty()) {
                        val zh = zhCharRegex.findAll(text).map { it.value }.joinToString("")
                        for (i in 0..zh.length - 12) {
                            if (zh.substring(i, i + 12) in zhForbidden) hits++
                        }
                    }
                }
            }
        }
        walk(payload, "")
        return hits
    }
}

/**
 * Strict verbatim whole-sentence anchor gate (`TON-1806` Red Team Round 2 contract):
 * - Checks all 5 anchor pair shapes (`pdfPage/quoteAnchor`, `missJumpPdfPage/quoteAnchor`,
 *   `exercisePdfPage/exerciseQuoteAnchor`, `bodyPdfPage/quoteAnchor`, `bodyJumpPage/quoteAnchor`).
 * - Requires NFKC whitespace-collapsed substring match and >= 4 ASCII words (unless source is pure CJK >= 6 chars).
 * - Relocates only when the EXACT same quote appears on another page of the same chapter.
 * - Rejects partial truncation (`idx > 0` suffix tricks) and unrelated fallback replacements.
 * - Triggers 1 targeted Haiku repair call for mismatches, then fail-closed drops any remaining unverified items.
 */
internal object StudyModeAnchorGate {
    private val pairKeys = listOf(
        "pdfPage" to "quoteAnchor",
        "missJumpPdfPage" to "quoteAnchor",
        "exercisePdfPage" to "exerciseQuoteAnchor",
        "bodyPdfPage" to "quoteAnchor",
        "bodyJumpPage" to "quoteAnchor",
    )
    private val asciiTokenRegex = Regex("[A-Za-z0-9]+")
    private val whitespaceRegex = Regex("\\s+")
    private val trailingPunctRegex = Regex("""^[\s“”"']+|[\s“”"']+$""")

    private data class AnchorFailure(
        val id: String,
        val claimedPage: Int,
        val badQuote: String,
        val pageKey: String,
        val quoteKey: String,
        val contextHint: String,
    )

    fun normalize(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace('\u00AD'.toString(), "")
            .replace(whitespaceRegex, " ")
            .trim()
            .lowercase(Locale.US)

    fun hasMinimumLength(quote: String): Boolean {
        val asciiWords = asciiTokenRegex.findAll(quote).count()
        if (asciiWords >= 4) return true
        val cjkCount = quote.count { it in '\u4e00'..'\u9fff' }
        return cjkCount >= 6
    }

    /** Extract candidate whole sentences (>= 5 ASCII words) from a chapter's pages to aid targeted repair. */
    fun extractCandidateSentences(pages: List<StudyPageInput>, centerPage: Int, radius: Int = 2): List<Pair<Int, String>> {
        val candidates = mutableListOf<Pair<Int, String>>()
        pages.filter { kotlin.math.abs(it.pdfPage - centerPage) <= radius }
            .sortedBy { kotlin.math.abs(it.pdfPage - centerPage) }
            .forEach { p ->
                val collapsed = Normalizer.normalize(p.text, Normalizer.Form.NFKC).replace(whitespaceRegex, " ")
                collapsed.split(Regex("(?<=[.!?。；])\\s+"))
                    .map { it.trim() }
                    .filter { it.length in 28..240 && asciiTokenRegex.findAll(it).count() >= 4 }
                    .take(10)
                    .forEach { sent -> candidates.add(p.pdfPage to sent) }
            }
        return candidates
    }

    fun findExactPage(quote: String, claimedPage: Int, pageNormMap: Map<Int, String>): Int? {
        val trimmed = trailingPunctRegex.replace(quote.trim(), "")
        if (trimmed.isBlank() || !hasMinimumLength(trimmed)) return null
        val normQuote = normalize(trimmed)
        if (normQuote.length < 12) return null
        val claimedText = pageNormMap[claimedPage]
        if (claimedText != null && normQuote in claimedText) return claimedPage
        for ((pg, normText) in pageNormMap) {
            if (pg != claimedPage && normQuote in normText) return pg
        }
        return null
    }

    fun verifyAndRepair(
        payload: JsonObject,
        pages: List<StudyPageInput>,
        repairTransport: StudyModeBatchTransport?,
        bookId: String,
        chapterNum: Int,
        friend: String?,
        device: String?,
        repairOnlyWhenZeroValid: Boolean = false,
    ): Triple<JsonObject, StudyModeAnchorStats, StudyModeTransportReply?> {
        if (pages.isEmpty()) return Triple(payload, StudyModeAnchorStats(), null)
        val pageNormMap = pages.associate { it.pdfPage to normalize(it.text) }

        fun activeAnchorPairs(obj: JsonObject): List<Pair<String, String>> {
            val matched = pairKeys.filter { (pk, qk) -> obj.containsKey(pk) || (obj.containsKey(qk) && pairKeys.none { (otherPk, otherQk) -> otherQk == qk && obj.containsKey(otherPk) }) }
            val result = mutableListOf<Pair<String, String>>()
            val seenQuoteKeys = mutableSetOf<String>()
            for (pair in matched) {
                if (obj.containsKey(pair.first)) {
                    result += pair
                    seenQuoteKeys += pair.second
                }
            }
            for (pair in matched) {
                if (!obj.containsKey(pair.first) && pair.second !in seenQuoteKeys) {
                    result += pair
                    seenQuoteKeys += pair.second
                }
            }
            return result
        }

        val firstPassFailures = mutableListOf<AnchorFailure>()
        var initialValidCount = 0
        fun collectFailures(el: JsonElement, prefix: String) {
            when (el) {
                is JsonArray -> el.forEachIndexed { idx, child -> collectFailures(child, "$prefix[$idx]") }
                is JsonObject -> {
                    for ((pk, qk) in activeAnchorPairs(el)) {
                        val pageVal = el[pk]?.jsonPrimitive?.intOrNull
                        val quoteVal = el[qk]?.jsonPrimitive?.contentOrNull
                        val matchedPage = if (pageVal != null && quoteVal != null) findExactPage(quoteVal, pageVal, pageNormMap) else null
                        if (matchedPage != null) {
                            initialValidCount += 1
                        } else {
                            val hint = el["title"]?.jsonPrimitive?.contentOrNull
                                ?: el["topic"]?.jsonPrimitive?.contentOrNull
                                ?: el["claim"]?.jsonPrimitive?.contentOrNull
                                ?: el["trap"]?.jsonPrimitive?.contentOrNull
                                ?: el["howItAnswersQuestion"]?.jsonPrimitive?.contentOrNull
                                ?: el["targetConcept"]?.jsonPrimitive?.contentOrNull
                                ?: ""
                            firstPassFailures.add(
                                AnchorFailure(
                                    id = "$prefix#$pk",
                                    claimedPage = pageVal ?: pages.first().pdfPage,
                                    badQuote = quoteVal.orEmpty(),
                                    pageKey = pk,
                                    quoteKey = qk,
                                    contextHint = hint,
                                ),
                            )
                        }
                    }
                    el.forEach { (k, v) -> if (v is JsonObject || v is JsonArray) collectFailures(v, "$prefix.$k") }
                }
                else -> Unit
            }
        }
        collectFailures(payload, "root")

        var repairReply: StudyModeTransportReply? = null
        val repairsById = mutableMapOf<String, Pair<Int, String>>()
        if (firstPassFailures.isNotEmpty() && repairTransport != null && (!repairOnlyWhenZeroValid || initialValidCount == 0)) {
            repairReply = runCatching {
                runTargetedRepair(
                    failures = firstPassFailures.take(8),
                    pages = pages,
                    transport = repairTransport,
                    bookId = bookId,
                    chapterNum = chapterNum,
                    friend = friend,
                    device = device,
                )
            }.getOrNull()?.also { (reply, fixes) ->
                repairReply = reply
                fixes.forEach { (id, pg, quote) ->
                    if (findExactPage(quote, pg, pageNormMap) != null) {
                        repairsById[id] = pg to quote
                    }
                }
            }?.first
        }

        // Apply fixes and fail-closed filter unverified anchor items.
        var total = 0
        var verified = 0
        var relocated = 0
        var dropped = 0

        fun applyAndFilter(el: JsonElement, prefix: String): Pair<JsonElement?, Boolean> {
            return when (el) {
                is JsonArray -> {
                    val out = buildJsonArray {
                        el.forEachIndexed { idx, child ->
                            val (fixed, keep) = applyAndFilter(child, "$prefix[$idx]")
                            if (keep && fixed != null) add(fixed)
                        }
                    }
                    out to true
                }
                is JsonObject -> {
                    val map = el.toMutableMap()
                    var itemRelocated = 0
                    var itemPairCount = 0
                    for ((pk, qk) in activeAnchorPairs(el)) {
                        val pageVal = map[pk]?.jsonPrimitive?.intOrNull
                        val quoteVal = map[qk]?.jsonPrimitive?.contentOrNull
                        val curPage = pageVal ?: pages.first().pdfPage
                        val curQuote = quoteVal.orEmpty()
                        val matched = if (pageVal != null && quoteVal != null) findExactPage(curQuote, curPage, pageNormMap) else null
                        if (matched != null) {
                            if (matched != pageVal) {
                                map[pk] = JsonPrimitive(matched)
                                itemRelocated += 1
                            }
                            map[qk] = JsonPrimitive(trailingPunctRegex.replace(curQuote.trim(), ""))
                            itemPairCount += 1
                        } else {
                            val fid = "$prefix#$pk"
                            val fix = repairsById[fid]
                            if (fix != null) {
                                val fixedPage = findExactPage(fix.second, fix.first, pageNormMap)
                                if (fixedPage != null) {
                                    map[pk] = JsonPrimitive(fixedPage)
                                    map[qk] = JsonPrimitive(trailingPunctRegex.replace(fix.second.trim(), ""))
                                    if (fixedPage != pageVal) itemRelocated += 1
                                    itemPairCount += 1
                                } else {
                                    dropped += 1
                                    return null to false
                                }
                            } else {
                                dropped += 1
                                return null to false
                            }
                        }
                    }
                    total += itemPairCount
                    verified += itemPairCount
                    relocated += itemRelocated
                    for ((k, v) in map.toMap()) {
                        if (v is JsonObject || v is JsonArray) {
                            val (nested, keepNested) = applyAndFilter(v, "$prefix.$k")
                            if (nested != null && keepNested) map[k] = nested
                        }
                    }
                    JsonObject(map) to true
                }
                else -> el to true
            }
        }

        val (cleaned, _) = applyAndFilter(payload, "root")
        val cleanedObj = (cleaned as? JsonObject) ?: payload
        val stats = StudyModeAnchorStats(
            total = total,
            verified = verified,
            unverified = (total - verified).coerceAtLeast(0),
            relocated = relocated,
            dropped = dropped,
        )
        return Triple(cleanedObj, stats, repairReply)
    }

    private fun runTargetedRepair(
        failures: List<AnchorFailure>,
        pages: List<StudyPageInput>,
        transport: StudyModeBatchTransport,
        bookId: String,
        chapterNum: Int,
        friend: String?,
        device: String?,
    ): Pair<StudyModeTransportReply, List<Triple<String, Int, String>>> {
        val sys = """Pick ONE exact verbatim sentence (>= 4 words) from the candidate lines for each failed anchor item.
Return STRICT JSON: {"repairs":[{"id":"...","pdfPage":123,"quoteAnchor":"EXACT verbatim substring from that page"}]}
Do NOT paraphrase, truncate mid-word, or invent text."""
        val user = buildString {
            append("Book ").append(bookId).append(" Ch.").append(chapterNum).append('\n')
            failures.forEach { f ->
                append("ITEM id=").append(f.id)
                    .append(" hint=\"").append(f.contextHint.take(120)).append("\"")
                    .append(" claimedPage=").append(f.claimedPage).append('\n')
                extractCandidateSentences(pages, f.claimedPage, radius = 2).take(14).forEach { (pg, sent) ->
                    append("  [p.").append(pg).append("] ").append(sent).append('\n')
                }
            }
        }
        val reply = transport.complete(
            task = "STUDY_MODE_ANCHOR_REPAIR",
            jobKey = StudyModeJsonHelpers.sha256Hex("repair:$bookId:$chapterNum:${failures.size}"),
            systemPrompt = sys,
            userPrompt = user,
            maxTokens = 1200,
            useBatchApi = false,
            friend = friend,
            device = device,
        )
        val obj = StudyModeJsonHelpers.extractJsonObject(reply.text)
        val fixes = obj["repairs"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val pg = o["pdfPage"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val quote = o["quoteAnchor"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Triple(id, pg, quote)
        }
        return reply to fixes
    }
}

/**
 * Reuses TON-1804 (`SourcePageMathExtractor`, `DeterministicMathChecks`, `PlaceholderLeakGuard`, `SympySubprocessEngine`)
 * to prevent formula coefficient drift (e.g. 3/2 vs 3, 5/2 vs 1.5倍) and strip/reject placeholder leaks (`[依原文公式]`).
 */
internal object StudyModeMathGate {
    private val ratioOrFormulaRegex = Regex("""(?:\d+/\d+|=\s*[0-9A-Za-z(])""")
    private val fractionTokenRegex = Regex("""(?<![\d.])(\d+)\s*/\s*(\d+)(?![\d.])""")

    fun sanitizePlaceholders(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> buildJsonObject {
            el.forEach { (k, v) -> put(k, sanitizePlaceholders(v)) }
        }
        is JsonArray -> buildJsonArray {
            el.forEach { add(sanitizePlaceholders(it)) }
        }
        is JsonPrimitive -> {
            if (el.isString && PlaceholderLeakGuard.hasLeak(el.content)) {
                JsonPrimitive(PlaceholderLeakGuard.stripLeaks(el.content))
            } else {
                el
            }
        }
    }

    fun verifyAndRepair(
        payload: JsonObject,
        pages: List<StudyPageInput>,
        sympy: SympySubprocessEngine,
        repairTransport: StudyModeBatchTransport?,
        bookId: String,
        chapterNum: Int,
        friend: String?,
        device: String?,
        repairOnlyWhenZeroValid: Boolean = false,
    ): Triple<JsonObject, StudyModeMathStats, StudyModeTransportReply?> {
        if (pages.isEmpty()) return Triple(sanitizePlaceholders(payload) as JsonObject, StudyModeMathStats(), null)

        val cues = SourcePageMathExtractor.extract(
            pages.map { p ->
                SectionBlockInput(
                    id = "p-${p.pdfPage}",
                    pdfPage = p.pdfPage,
                    startUtf16 = 0,
                    text = p.text,
                )
            },
        )
        val pageMap = pages.associate { it.pdfPage to it.text }

        val checkTargets = setOf("formulaSheets", "diagnosticGates", "problems", "sections", "mustKnows", "highFreqTraps", "coreClaims")
        var total = 0
        var verified = 0
        var repairedCount = 0
        var dropped = 0
        var repairReply: StudyModeTransportReply? = null

        val allSpecs = mutableListOf<Pair<String, MathCheckSpec>>()
        val itemsToAudit = mutableListOf<Triple<String, Int, String>>()
        val origObjsById = mutableMapOf<String, JsonObject>()

        fun inspectItem(key: String, idx: Int, obj: JsonObject) {
            val combined = obj.entries
                .filter { (k, _) -> k !in setOf("quoteAnchor", "exerciseQuoteAnchor") }
                .mapNotNull { (_, v) ->
                    when (v) {
                        is JsonPrimitive -> if (v.isString) v.content else null
                        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(" ")
                        else -> null
                    }
                }
                .joinToString(" ")
            val hasLeak = PlaceholderLeakGuard.hasLeak(combined)
            if (!hasLeak &&
                !ratioOrFormulaRegex.containsMatchIn(combined) &&
                obj["formulaLatex"] == null &&
                obj["keyDerivationCheck"] == null
            ) {
                return
            }
            val anchorPage = obj["pdfPage"]?.jsonPrimitive?.intOrNull
                ?: obj["missJumpPdfPage"]?.jsonPrimitive?.intOrNull
                ?: obj["bodyPdfPage"]?.jsonPrimitive?.intOrNull
                ?: obj["exercisePdfPage"]?.jsonPrimitive?.intOrNull
                ?: pages.first().pdfPage
            val id = "$key[$idx]"
            origObjsById[id] = obj
            itemsToAudit.add(Triple(id, anchorPage, combined))
            DeterministicMathChecks.extractInlineChecks(id, combined).forEach { spec ->
                allSpecs.add(id to spec)
            }
        }

        // Detect placeholder leaks and formula mismatches on RAW payload before stripping placeholders.
        payload.forEach { (k, v) ->
            if (k in checkTargets && v is JsonArray) {
                v.forEachIndexed { idx, el -> (el as? JsonObject)?.let { inspectItem(k, idx, it) } }
            }
        }

        val sympyFailuresById = mutableSetOf<String>()
        if (allSpecs.isNotEmpty()) {
            val batch = sympy.runBatch(allSpecs.map { it.second })
            batch.results.forEach { r ->
                if (!r.passed && r.reason.isNotBlank()) {
                    val owner = allSpecs.firstOrNull { it.second.id == r.id }?.first
                    if (owner != null) sympyFailuresById.add(owner)
                }
            }
        }

        val failedReasons = mutableMapOf<String, List<String>>()
        val validCountByArray = mutableMapOf<String, Int>()
        itemsToAudit.forEach { (id, pg, combined) ->
            total += 1
            val reasons = mutableListOf<String>()
            if (PlaceholderLeakGuard.hasLeak(combined)) {
                reasons.add("placeholder_leak")
            }
            if (id in sympyFailuresById) {
                reasons.add("sympy_symbolic_mismatch")
            }
            reasons.addAll(
                DeterministicMathChecks.checkDeterministicConsistency(
                    itemText = combined,
                    sourceCues = cues,
                    anchorPage = pg,
                    declaredChecks = emptyList(),
                ),
            )
            // Guard against exact source rational coefficient loss (e.g. page explicitly contains 3/2 or 5/2 while draft outputs distorted multiplier).
            val pageText = pageMap[pg].orEmpty()
            val pageFractions = fractionTokenRegex.findAll(pageText).map { "${it.groupValues[1]}/${it.groupValues[2]}" }.toSet()
            if ((pageFractions.contains("3/2") && (combined.contains("3 倍") || combined.contains("3倍"))) ||
                (pageFractions.contains("5/2") && (combined.contains("1.5倍") || combined.contains("1.5 倍")))
            ) {
                reasons.add("exact_fraction_coefficient_drift")
            }
            if (reasons.isEmpty()) {
                verified += 1
                val arrKey = id.substringBefore('[')
                validCountByArray[arrKey] = (validCountByArray[arrKey] ?: 0) + 1
            } else {
                failedReasons[id] = reasons
            }
        }

        // Single targeted repair pass if any math item failed (skipped in fast path if sibling array items succeeded)
        val repairedObjs = mutableMapOf<String, JsonObject>()
        val idsToRepair = if (repairOnlyWhenZeroValid) {
            failedReasons.keys.filter { (validCountByArray[it.substringBefore('[')] ?: 0) == 0 }
        } else {
            failedReasons.keys.toList()
        }
        if (idsToRepair.isNotEmpty() && repairTransport != null) {
            val sys = """Repair math & formula consistency against verbatim textbook excerpts. Never use placeholders like [依原文公式].
Preserve EVERY original JSON key and anchor field (problemId, exercisePdfPage, exerciseQuoteAnchor, bodyPdfPage, quoteAnchor, pdfPage, etc.); ONLY fix math/formula/placeholder errors.
Return STRICT JSON: {"items":[{"id":"...","obj":{...exact same keys corrected...}}]}"""
            val user = buildString {
                append("Book ").append(bookId).append(" Ch.").append(chapterNum).append('\n')
                idsToRepair.take(4).forEach { id ->
                    val pg = itemsToAudit.firstOrNull { it.first == id }?.second ?: pages.first().pdfPage
                    append("FAILED ").append(id).append(" reasons=").append(failedReasons[id]).append('\n')
                    origObjsById[id]?.let { orig -> append("ORIGINAL JSON: ").append(orig.toString()).append('\n') }
                    append("SOURCE [p.").append(pg).append("]: ").append(pageMap[pg].orEmpty().take(1800)).append('\n')
                }
            }
            repairReply = runCatching {
                repairTransport.complete(
                    task = "STUDY_MODE_MATH_REPAIR",
                    jobKey = StudyModeJsonHelpers.sha256Hex("mathfix:$bookId:$chapterNum:${idsToRepair.joinToString(",")}"),
                    systemPrompt = sys,
                    userPrompt = user,
                    maxTokens = 1400,
                    useBatchApi = false,
                    friend = friend,
                    device = device,
                )
            }.getOrNull()
            repairReply?.let { r ->
                runCatching { StudyModeJsonHelpers.extractJsonObject(r.text) }.getOrNull()
                    ?.get("items")?.jsonArray.orEmpty().forEach { el ->
                        val entry = el as? JsonObject ?: return@forEach
                        val id = entry["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val obj = entry["obj"] as? JsonObject ?: return@forEach
                        repairedObjs[id] = sanitizePlaceholders(obj) as JsonObject
                    }
            }
        }

        // Apply repairs & fail-closed drop remaining broken items, then sanitize placeholders on any untouched text fields.
        val outMap = payload.toMutableMap()
        checkTargets.forEach { key ->
            val arr = outMap[key] as? JsonArray ?: return@forEach
            val rebuilt = buildJsonArray {
                arr.forEachIndexed { idx, elem ->
                    val id = "$key[$idx]"
                    val obj = elem as? JsonObject
                    if (obj == null) {
                        add(elem)
                    } else if (id !in failedReasons) {
                        add(obj)
                    } else {
                        val rep = repairedObjs[id]
                        if (rep != null) {
                            val repText = rep.toString()
                            val pg = itemsToAudit.firstOrNull { it.first == id }?.second ?: pages.first().pdfPage
                            val stillBad = PlaceholderLeakGuard.hasLeak(repText) ||
                                DeterministicMathChecks.checkDeterministicConsistency(repText, cues, pg, emptyList()).isNotEmpty()
                            if (!stillBad) {
                                repairedCount += 1
                                verified += 1
                                add(rep)
                            } else {
                                dropped += 1
                            }
                        } else {
                            dropped += 1
                        }
                    }
                }
            }
            outMap[key] = rebuilt
        }

        val stats = StudyModeMathStats(
            total = total,
            verified = verified,
            repaired = repairedCount,
            failed = 0, // fail-closed: failed items are repaired or dropped
            dropped = dropped,
        )
        return Triple(sanitizePlaceholders(JsonObject(outMap)) as JsonObject, stats, repairReply)
    }
}

internal object StudyModePromptBuilder {
    private val stopWords = setOf("the", "and", "for", "with", "that", "this", "from", "are", "was", "what", "how", "why")

    fun langInstruction(lang: ServerExplainLang): String = when (lang) {
        ServerExplainLang.ZH -> "MANDATORY LANGUAGE: 全部输出必须使用简洁自然的中文（含标题、要点、选项、 rubric 与解释）；仅 quoteAnchor/exerciseQuoteAnchor 原文引句与极少必要专有名词保留英文，严禁整句英文。"
        ServerExplainLang.BILINGUAL -> "MANDATORY LANGUAGE: Write ALL summaries, titles, questions, options, traps, rubrics, hints, and explanations in concise natural Chinese (中文). You may append at most 1-2 key English terms in parentheses. NEVER write English sentences outside quoteAnchor/exerciseQuoteAnchor!"
        ServerExplainLang.EN -> "Output language: Concise academic English only; keep quoteAnchor strictly verbatim."
    }

    fun buildContextExcerpt(req: StudyModeRequest): String {
        val pages = req.pages
        if (pages.isEmpty()) return ""
        val firstPageNum = pages.first().pdfPage
        val maxTotalChars = when (req.mode) {
            ServerStudyMode.BOOK_MAP -> 1900
            ServerStudyMode.READ_THROUGH -> if (req.detailLevel == ServerDetailLevel.BRIEF) 1900 else 2400
            ServerStudyMode.EXAM_FOCUS -> if (req.detailLevel == ServerDetailLevel.BRIEF) 1800 else 2000
            ServerStudyMode.DIAGNOSTIC_SKIP -> if (req.detailLevel == ServerDetailLevel.BRIEF) 1800 else 2000
            ServerStudyMode.PROBLEM_DRIVEN -> 2100
            ServerStudyMode.QUESTION_DRIVEN -> 2100
        }
        val selected = when (req.mode) {
            ServerStudyMode.QUESTION_DRIVEN -> {
                val qTokens = req.inquiryQuestion.lowercase(Locale.US)
                    .split(Regex("[^a-z0-9\\u4e00-\\u9fff]+"))
                    .filter { it.length >= 2 && it !in stopWords }
                    .toSet()
                pages.sortedByDescending { p ->
                    val low = p.text.lowercase(Locale.US)
                    qTokens.count { it in low } * 10 + (if ("=" in p.text) 2 else 0)
                }.take(2)
            }
            ServerStudyMode.PROBLEM_DRIVEN -> {
                val exPages = pages.sortedByDescending { p ->
                    val low = p.text.lowercase(Locale.US)
                    var score = 0
                    if ("exercise" in low || "problem" in low) score += 14
                    if (p.pdfPage >= pages.last().pdfPage - 5) score += 10
                    score
                }.take(1)
                val bodyPages = pages.filterNot { it in exPages }.sortedByDescending { p ->
                    val low = p.text.lowercase(Locale.US)
                    var score = if ("=" in p.text || "/" in p.text) 10 else 0
                    if ("example" in low || "figure" in low || "equation" in low) score += 6
                    score
                }.take(1)
                (exPages + bodyPages).distinctBy { it.pdfPage }
            }
            ServerStudyMode.EXAM_FOCUS -> {
                val pool = if (pages.size >= 4) pages.filterNot { it.pdfPage == firstPageNum } else pages
                val topics = req.courseTopics.map { it.lowercase(Locale.US) }
                pool.sortedByDescending { p ->
                    val low = p.text.lowercase(Locale.US)
                    var score = topics.count { t -> t.split(' ').any { it.length >= 3 && it in low } } * 8
                    if ("=" in p.text || "theorem" in low || "definition" in low || "principle" in low) score += 5
                    score
                }.take(2)
            }
            ServerStudyMode.DIAGNOSTIC_SKIP -> {
                val pool = if (pages.size >= 4) pages.filterNot { it.pdfPage == firstPageNum } else pages
                val sectionStarts = req.sections.map { it.startPdfPage }.toSet()
                pool.sortedByDescending { p ->
                    (if (p.pdfPage in sectionStarts) 10 else 0) + (if ("=" in p.text) 4 else 0)
                }.take(2)
            }
            ServerStudyMode.BOOK_MAP -> {
                listOfNotNull(pages.firstOrNull(), pages.lastOrNull()).distinctBy { it.pdfPage }
            }
            ServerStudyMode.READ_THROUGH -> pages.take(if (req.detailLevel == ServerDetailLevel.BRIEF) 2 else 3)
        }.sortedBy { it.pdfPage }
        val sb = StringBuilder()
        if (req.sections.isNotEmpty() &&
            req.mode != ServerStudyMode.BOOK_MAP &&
            req.mode != ServerStudyMode.DIAGNOSTIC_SKIP
        ) {
            sb.append("SECTIONS: ")
                .append(req.sections.take(4).joinToString(" | ") { "${it.title}(p.${it.startPdfPage}-${it.endPdfPage})" })
                .append('\n')
        }
        if (req.courseTopics.isNotEmpty() && req.mode == ServerStudyMode.EXAM_FOCUS) {
            sb.append("COURSE_TOPICS: ").append(req.courseTopics.take(4).joinToString("; ")).append('\n')
        }
        val cues = SourcePageMathExtractor.extract(
            selected.map { p -> SectionBlockInput(id = "p-${p.pdfPage}", pdfPage = p.pdfPage, startUtf16 = 0, text = p.text) },
        ).formatForPrompt()
        val cueLimit = if (req.mode == ServerStudyMode.PROBLEM_DRIVEN) 220 else 300
        if (cues.isNotBlank()) sb.append(cues.take(cueLimit)).append('\n')
        val budgetPerPage = ((maxTotalChars - sb.length) / selected.size.coerceAtLeast(1)).coerceIn(450, 950)
        selected.forEach { p ->
            val compact = Normalizer.normalize(p.text, Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()
            val slice = if (compact.length <= budgetPerPage) {
                compact
            } else {
                val cut = compact.take(budgetPerPage)
                val lastDot = cut.lastIndexOf(". ")
                if (lastDot >= budgetPerPage / 2) cut.substring(0, lastDot + 1) else cut
            }
            sb.append("[p.").append(p.pdfPage).append("] ").append(slice).append('\n')
        }
        return sb.toString()
    }

    fun buildModePrompts(req: StudyModeRequest): Pair<String, String> {
        val densityRule = if (req.detailLevel == ServerDetailLevel.BRIEF) {
            "Target <= 150 output tokens: 1-2 items per array, <= 12-word crisp Chinese phrases, start streaming '{' immediately."
        } else {
            "Target <= 180 output tokens: keep arrays minimal (1-2 items) & concise <= 15-word Chinese values, stream '{' immediately with zero fluff."
        }
        val anchorRule = """RULES:
1. Return ONLY one valid JSON object (no markdown fences).
2. Every quoteAnchor/exerciseQuoteAnchor MUST copy 5-10 contiguous words VERBATIM from ONE prose sentence on the matching [p.XXX] line (never copy from SECTIONS/COURSE_TOPICS/MATH lines; no '...' or edits).
3. Keep math ratios/coefficients exact (e.g. 3/2, 5/2); never emit placeholders like [依原文公式].
4. Original prose only (never copy >12 contiguous words outside quote fields). ${langInstruction(req.explainLang)}
5. $densityRule"""

        val system = when (req.mode) {
            ServerStudyMode.READ_THROUGH -> """READ_THROUGH 逐节伴读卡片 (max 2 sections, 1 keyTerm, 2 takeawayChecklist).
$anchorRule
Schema:
{"learningArc":"中文主线一句","sections":[{"sectionId":"s1","title":"中文节名","pdfPage":123,"quoteAnchor":"verbatim 5-10 words","keyConcept":"中文核心概念","plainExplanation":"中文直白解释<=15字","conceptChain":["因果一","因果二"],"formulaNote":"比例关系或空","commonMisreading":"中文常见误读","microCheckPrompt":"中文自测短问","microCheckHint":"中文提示"}],"keyTerms":[{"term":"术语(EN)","definition":"中文释义","pdfPage":123,"quoteAnchor":"verbatim 5-10 words"}],"takeawayChecklist":["中文要点一","中文要点二"]}"""

            ServerStudyMode.BOOK_MAP -> """BOOK_MAP 全章结构骨架一页纸 (max 2 coreClaims, 2 sections, 1 keyTerm; 全字段简练中文).
$anchorRule
Schema:
{"chapterRole":"CORE|FOUNDATION|EXTENSION","oneLiner":"中文一句话定位<=18字","estimatedMinutes":20,"readingDepth":"CORE_SKIM|DEEP|REFERENCE","readingOrderSuggestion":"中文跳读路线","prerequisites":["前置中文"],"unlocks":["后续中文"],"coreClaims":[{"claim":"中文核心论断","importance":"CORE","pdfPage":123,"quoteAnchor":"verbatim 5-10 words"}],"sections":[{"sectionTitle":"中文小节","summary":"中文概括<=15字","skipUnlessExam":false,"pdfPage":123,"quoteAnchor":"verbatim 5-10 words"}],"keyTerms":[{"term":"术语","gloss":"中文释义","pdfPage":123,"quoteAnchor":"verbatim 5-10 words"}]}"""

            ServerStudyMode.EXAM_FOCUS -> """EXAM_FOCUS 考点分诊卡 (max 2 mustKnows, 1 highFreqTrap, 1 miniCheck; set skipableExamples=[] and formulaSheets=[] unless an explicit equation appears on [p.XXX]; target <= 160 output tokens; ALL prose/rubrics/options strictly in concise Chinese 中文! NEVER write essays or English sentences).
$anchorRule
Schema:
{"courseTopicsMatched":["课程主题中文"],"triageSummary":"中文考前总览<=15字","mustKnows":[{"topic":"中文考点","examWeight":"HIGH|CORE","whyTested":"中文命题点","pdfPage":123,"quoteAnchor":"verbatim 5-10 words","scoringRubric":["得分点1","得分点2"]}],"highFreqTraps":[{"trap":"中文易错点","wrongReasoning":"中文错因","correction":"中文正解","pdfPage":123,"quoteAnchor":"verbatim 5-10 words"}],"skipableExamples":[],"formulaSheets":[],"miniChecks":[{"prompt":"中文题干","options":["选项A","选项B","选项C","选项D"],"answerIndex":0,"rubricOnly":"中文判分要点","pdfPage":123,"quoteAnchor":"verbatim 5-10 words"}]}"""

            ServerStudyMode.DIAGNOSTIC_SKIP -> """DIAGNOSTIC_SKIP 先测后读门槛卡 (2 concise Chinese diagnosticGates with 1-3 word Chinese options; use missJumpPdfPage + quoteAnchor).
$anchorRule
Schema:
{"skipPolicy":"中文跳过规则","diagnosticGates":[{"gateId":"diag-1","sectionTitle":"中文小节","targetConcept":"中文待测概念","questionType":"CONCEPT_CHECK|CALCULATION","questionPrompt":"中文题干<=15字","options":["选项A","选项B","选项C","选项D"],"correctIndex":0,"passSkipNote":"中文答对可跳过","missRemediation":"中文错因回看点","missJumpPdfPage":123,"quoteAnchor":"verbatim 5-10 words from missJumpPdfPage"}]}"""

            ServerStudyMode.PROBLEM_DRIVEN -> """PROBLEM_DRIVEN 题驱动逆推卡 (2 concise Chinese problems if distinct exercises/examples exist, else 1; each MUST pair verbatim exercisePdfPage+exerciseQuoteAnchor AND bodyPdfPage+quoteAnchor from [p.XXX]).
$anchorRule
Schema:
{"strategyNote":"中文做题策略","problems":[{"problemId":"prob-1","title":"中文题目标题","exercisePdfPage":123,"exerciseQuoteAnchor":"verbatim 5-10 words from [p.XXX]","attemptPrompt":"中文先试做提示","scaffoldingHints":["中文提示1","中文提示2"],"keyDerivationCheck":"精确推导与比例关系","bodyPdfPage":120,"quoteAnchor":"verbatim 5-10 words from [p.XXX]","answerOutline":["中文要点1","中文要点2"]}]}"""

            ServerStudyMode.QUESTION_DRIVEN -> """QUESTION_DRIVEN 问驱动证据检索卡 (NO essay ghostwriting: 1-sentence Chinese directConclusion, 2 evidenceHits with bodyJumpPage+quoteAnchor, 2 Chinese scaffold steps).
$anchorRule
Schema:
{"questionFocus":"中文核心问法","directConclusion":"中文一句直接结论","evidenceHits":[{"hitId":"ev-1","bodyJumpPage":123,"quoteAnchor":"verbatim 5-10 words from bodyJumpPage","howItAnswersQuestion":"中文如何回应问题","limitOrCounterpoint":"中文边界或反例"}],"argumentationScaffold":["中文论证步骤1","中文论证步骤2"],"antiGhostwriteNotice":"仅提供原文证据索引与论证骨架，请用自己的话完成作答。","followUpQuestions":["中文追问1","中文追问2"]}"""
        }

        val user = buildString {
            append("Book: ").append(req.bookTitle.ifBlank { req.bookId }).append('\n')
            append("Chapter ").append(req.chapterNum).append(": ").append(req.chapterTitle).append('\n')
            if (req.mode == ServerStudyMode.QUESTION_DRIVEN) {
                append("INQUIRY_QUESTION: ").append(req.inquiryQuestion.ifBlank { "What is the central theoretical mechanism and its boundary conditions in this chapter?" }).append('\n')
            }
            append(buildContextExcerpt(req))
        }
        return system to user
    }
}

/** Local persistent SQLite cache keyed strictly by user/friend scope, book, chapter, mode, detailLevel, explainLang, inquiry, topics & content digest. */
class StudyModePackCache(directory: Path) {
    private val file = PrivateFiles().file(directory.resolve("study-mode-cache.sqlite"))

    init {
        database().use { db ->
            db.createStatement().use { sql ->
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS study_mode_cache(
                        cache_key TEXT PRIMARY KEY,
                        scope TEXT NOT NULL,
                        book_id TEXT NOT NULL,
                        chapter_num INTEGER NOT NULL,
                        study_mode TEXT NOT NULL,
                        pack_json TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""",
                )
            }
        }
    }

    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:$file").apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    fun get(cacheKey: String): JsonObject? {
        database().use { db ->
            db.prepareStatement("SELECT pack_json FROM study_mode_cache WHERE cache_key = ?").use { ps ->
                ps.setString(1, cacheKey)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return runCatching { LedgerJson.parseToJsonElement(rs.getString(1)).jsonObject }.getOrNull()
                }
            }
        }
    }

    fun put(cacheKey: String, scope: String, bookId: String, chapterNum: Int, mode: String, pack: JsonObject) {
        database().use { db ->
            db.prepareStatement(
                """INSERT OR REPLACE INTO study_mode_cache(cache_key, scope, book_id, chapter_num, study_mode, pack_json, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
            ).use { ps ->
                ps.setString(1, cacheKey)
                ps.setString(2, scope)
                ps.setString(3, bookId)
                ps.setInt(4, chapterNum)
                ps.setString(5, mode)
                ps.setString(6, pack.toString())
                ps.setLong(7, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }
}

class StudyModePackService(
    private val streamTransport: StudyModeStreamTransport,
    private val batchTransport: StudyModeBatchTransport,
    private val ledger: BookTokenLedger? = null,
    private val cache: StudyModePackCache? = null,
    private val sympy: SympySubprocessEngine = SympySubprocessEngine(),
) {
    fun generateModePack(
        req: StudyModeRequest,
        sink: StudyModeEventSink? = null,
        cacheScope: String = "owner:local",
    ): JsonObject {
        val startedNs = System.nanoTime()
        sink?.emit(
            "meta",
            buildJsonObject {
                put("bookId", req.bookId)
                put("chapterNum", req.chapterNum)
                put("studyMode", req.mode.name)
                put("detailLevel", req.detailLevel.name)
                put("explainLang", req.explainLang.name)
                put("stage", "started")
            },
        )

        // Zero-token fast path for ORIGINAL_ONLY
        if (req.detailLevel == ServerDetailLevel.ORIGINAL_ONLY) {
            val pack = buildOriginalOnlyPack(req, ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L))
            sink?.emit("pack", pack)
            sink?.emit("done", buildJsonObject { put("ok", true); put("zeroAiTokens", true) })
            return pack
        }

        val pageDigest = StudyModeJsonHelpers.sha256Hex(
            req.pages.joinToString("\n") { "${it.pdfPage}:${it.text.take(400)}:${it.text.length}" },
        ).take(16)
        val topicsKey = req.courseTopics.joinToString("|").take(120)
        val cacheKey = StudyModeJsonHelpers.sha256Hex(
            "$cacheScope|${req.bookId}|${req.chapterNum}|${req.mode.name}|${req.detailLevel.name}|${req.explainLang.name}|${req.inquiryQuestion.trim()}|$topicsKey|$pageDigest",
        )
        if (!req.bypassCache && cache != null) {
            cache.get(cacheKey)?.let { cached ->
                val withFlag = JsonObject(cached.toMutableMap().apply { put("cacheHit", JsonPrimitive(true)) })
                sink?.emit("pack", withFlag)
                sink?.emit("done", buildJsonObject { put("ok", true); put("cacheHit", true) })
                return withFlag
            }
        }

        val (sysPrompt, userPrompt) = StudyModePromptBuilder.buildModePrompts(req)
        val maxTokens = when (req.detailLevel) {
            ServerDetailLevel.BRIEF -> 4096
            ServerDetailLevel.DETAILED -> 4096
            ServerDetailLevel.ORIGINAL_ONLY -> 0
        }

        var firstScreenEmitted = false
        val pendingPreview = StringBuilder()
        var lastChunkSentChars = 0

        val reply = streamTransport.streamGenerate(
            systemPrompt = sysPrompt,
            userPrompt = userPrompt,
            maxTokens = maxTokens,
            friend = req.friend,
            device = req.device,
        ) { delta, atMs ->
            pendingPreview.append(delta)
            if (!firstScreenEmitted && pendingPreview.length >= 24) {
                firstScreenEmitted = true
                val firstSlice = pendingPreview.substring(lastChunkSentChars)
                lastChunkSentChars = pendingPreview.length
                sink?.emit(
                    "first_screen",
                    buildJsonObject {
                        put("studyMode", req.mode.name)
                        put("firstTokenMs", atMs)
                        put("chars", pendingPreview.length)
                        put("delta", firstSlice)
                    },
                )
            } else if (sink != null && pendingPreview.length - lastChunkSentChars >= 80) {
                val slice = pendingPreview.substring(lastChunkSentChars)
                lastChunkSentChars = pendingPreview.length
                sink.emit("chunk", buildJsonObject { put("delta", slice); put("chars", pendingPreview.length) })
            }
        }

        if (req.friend == null) ledger?.record(
            TokenLedgerEntry(
                bookId = req.bookId,
                chapterId = "ch-${req.chapterNum}",
                sectionId = req.mode.name,
                task = "STUDY_MODE_${req.mode.name}",
                routeChannel = reply.routeChannel,
                model = reply.model,
                inputTokens = reply.inputTokens,
                outputTokens = reply.outputTokens,
                ttftMs = reply.firstTokenMs,
                elapsedMs = reply.elapsedMs,
                status = "ok",
            ),
        )

        var parsed = StudyModeJsonHelpers.extractJsonObject(reply.text)
        parsed = StudyModeAntiGhostwriteGuard.enforce(req.mode, parsed)

        // Math & placeholder verification FIRST, then anchor verification SECOND so repaired math items' anchors are verified too.
        val (mathVerified, mathStats, mathRepairReply) = StudyModeMathGate.verifyAndRepair(
            payload = parsed,
            pages = req.pages,
            sympy = sympy,
            repairTransport = batchTransport,
            bookId = req.bookId,
            chapterNum = req.chapterNum,
            friend = req.friend,
            device = req.device,
            repairOnlyWhenZeroValid = true,
        )
        mathRepairReply?.let { r ->
            if (req.friend == null) ledger?.record(
                TokenLedgerEntry(
                    bookId = req.bookId,
                    chapterId = "ch-${req.chapterNum}",
                    sectionId = "${req.mode.name}_MATH_REPAIR",
                    task = "STUDY_MODE_MATH_REPAIR",
                    routeChannel = r.routeChannel,
                    model = r.model,
                    inputTokens = r.inputTokens,
                    outputTokens = r.outputTokens,
                    elapsedMs = r.elapsedMs,
                    status = "ok",
                ),
            )
        }

        val (anchorVerified, anchorStats, anchorRepairReply) = StudyModeAnchorGate.verifyAndRepair(
            payload = mathVerified,
            pages = req.pages,
            repairTransport = batchTransport,
            bookId = req.bookId,
            chapterNum = req.chapterNum,
            friend = req.friend,
            device = req.device,
            repairOnlyWhenZeroValid = true,
        )
        anchorRepairReply?.let { r ->
            if (req.friend == null) ledger?.record(
                TokenLedgerEntry(
                    bookId = req.bookId,
                    chapterId = "ch-${req.chapterNum}",
                    sectionId = "${req.mode.name}_ANCHOR_REPAIR",
                    task = "STUDY_MODE_ANCHOR_REPAIR",
                    routeChannel = r.routeChannel,
                    model = r.model,
                    inputTokens = r.inputTokens,
                    outputTokens = r.outputTokens,
                    elapsedMs = r.elapsedMs,
                    status = "ok",
                ),
            )
        }

        // Textbook prose overlap check on non-quote explanations
        val chapterSourceAll = req.pages.joinToString("\n") { it.text }
        val nonQuoteProse = collectNonQuoteProse(anchorVerified)
        val proseAudit = VerbatimOverlapGuard.audit(listOf(nonQuoteProse), chapterSourceAll)
        val pastExamHits = StudyModePastExamGuard.checkOverlap(anchorVerified, req.pastExamForbiddenFragments)

        val totalElapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
        val finalPack = buildJsonObject {
            put("bookId", req.bookId)
            put("bookTitle", req.bookTitle.ifBlank { req.bookId })
            put("chapterNum", req.chapterNum)
            put("chapterTitle", req.chapterTitle)
            put("studyMode", req.mode.name)
            put("detailLevel", req.detailLevel.name)
            put("explainLang", req.explainLang.name)
            put("inquiryQuestion", if (req.mode == ServerStudyMode.QUESTION_DRIVEN) req.inquiryQuestion else "")
            put("zeroAiTokens", false)
            put("cacheHit", false)
            put("anchorStats", anchorStats.toJson())
            put("mathStats", mathStats.toJson())
            put(
                "overlapStats",
                buildJsonObject {
                    put("en8GramHits", proseAudit.en8GramHits)
                    put("zh12GramHits", proseAudit.zh12GramHits)
                    put("passed", proseAudit.passed)
                    put("pastExamOverlap", pastExamHits)
                },
            )
            put(
                "latencyMs",
                buildJsonObject {
                    put("firstTokenMs", reply.firstTokenMs)
                    put("ttftMs", reply.firstTokenMs)
                    put("totalElapsedMs", totalElapsedMs)
                },
            )
            anchorVerified.forEach { (k, v) ->
                if (k !in setOf("bookId", "bookTitle", "chapterNum", "chapterTitle", "studyMode", "detailLevel", "explainLang", "inquiryQuestion")) {
                    put(k, v)
                }
            }
        }

        if (anchorStats.unverified == 0 && mathStats.failed == 0 && pastExamHits == 0) {
            cache?.put(cacheKey, cacheScope, req.bookId, req.chapterNum, req.mode.name, finalPack)
        }
        sink?.emit("pack", finalPack)
        sink?.emit(
            "done",
            buildJsonObject {
                put("ok", true)
                put("firstTokenMs", reply.firstTokenMs)
                put("totalElapsedMs", totalElapsedMs)
            },
        )
        return finalPack
    }

    suspend fun generateBookMapBatch(
        req: BookMapBatchRequest,
        cacheScope: String = "owner:local",
    ): JsonObject = coroutineScope {
        val startedNs = System.nanoTime()
        if (req.detailLevel == ServerDetailLevel.ORIGINAL_ONLY) {
            val totalElapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
            return@coroutineScope buildJsonObject {
                put("bookId", req.bookId)
                put("bookTitle", req.bookTitle)
                put("detailLevel", "ORIGINAL_ONLY")
                put("explainLang", req.explainLang.name)
                put("zeroAiTokens", true)
                put("chapterCount", req.chapters.size)
                put(
                    "chapters",
                    buildJsonArray {
                        req.chapters.forEach { ch ->
                            add(
                                buildJsonObject {
                                    put("chapterNum", ch.chapterNum)
                                    put("chapterTitle", ch.chapterTitle)
                                    put("chapterRole", "CORE")
                                    put("oneLiner", "${ch.chapterTitle}（${ch.pages.size} 页，仅原文骨架）")
                                    put(
                                        "sections",
                                        buildJsonArray {
                                            ch.sections.forEach { sec ->
                                                add(
                                                    buildJsonObject {
                                                        put("sectionTitle", sec.title)
                                                        put("pdfPage", sec.startPdfPage)
                                                    },
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
                put("thematicClusters", buildJsonArray {})
                put("dependencyEdges", buildJsonArray {})
                put("fastSkimOrder40Min", buildJsonArray {})
                put("masterGlossary", buildJsonArray {})
                put("anchorStats", StudyModeAnchorStats().toJson())
                put("costStats", buildJsonObject { put("inputTokens", 0); put("outputTokens", 0); put("estimatedCostUsd", 0.0) })
                put("latencyMs", totalElapsedMs)
            }
        }

        val chaptersHash = StudyModeJsonHelpers.sha256Hex(
            req.chapters.sortedBy { it.chapterNum }.joinToString("|") { ch ->
                val secSig = ch.sections.joinToString(",") { s -> "${s.title}@${s.startPdfPage}-${s.endPdfPage}" }
                val pageSig = ch.pages.joinToString(",") { p ->
                    "${p.pdfPage}:${p.text.length}:${StudyModeJsonHelpers.sha256Hex(p.text.take(900)).take(8)}"
                }
                "${ch.chapterNum}:${ch.chapterTitle}:[$secSig]:[$pageSig]"
            },
        ).take(16)
        val topicsHash = StudyModeJsonHelpers.sha256Hex(req.courseTopics.joinToString("|")).take(12)
        val batchVariantSig = "${req.detailLevel.name}|${req.explainLang.name}|$topicsHash|$chaptersHash"
        val batchCacheKey = StudyModeJsonHelpers.sha256Hex(
            "$cacheScope|BOOK_MAP_BATCH|${req.bookId}|${req.chapters.size}|$batchVariantSig",
        )
        if (!req.bypassCache && cache != null) {
            cache.get(batchCacheKey)?.let { cached ->
                return@coroutineScope JsonObject(cached.toMutableMap().apply { put("cacheHit", JsonPrimitive(true)) })
            }
        }

        // Chunk chapters into groups of 4 (6 chunks for 24 ch) with concise schemas so Batch finishes well under maxTokens=8192.
        val chunks = req.chapters.sortedBy { it.chapterNum }.chunked(4)
        val totalIn = AtomicInteger(0)
        val totalOut = AtomicInteger(0)

        val mapSys = """Build ultra-concise BOOK_MAP chapter one-pagers (4 chapters per JSON response; total output <= 2500 tokens).
CRITICAL RULES:
1. Return ONLY JSON: {"chapters":[{"chapterNum":1,"chapterTitle":"...","chapterRole":"CORE|FOUNDATION|EXTENSION","oneLiner":"中文一句话<=18字","estimatedMinutes":15,"readingDepth":"CORE_SKIM|DEEP|REFERENCE","prerequisites":["Ch.X"],"unlocks":["Ch.Y"],"keyTerms":[{"term":"EN term","gloss":"中文释义<=8字"}],"coreClaims":[{"claim":"中文核心论断<=14字","importance":"CORE","pdfPage":12,"quoteAnchor":"VERBATIM 5-9 words copied directly from [p.12]"}],"sections":[{"sectionTitle":"中文小节名","summary":"<=12字","skipUnlessExam":false,"pdfPage":12}]}]}
2. Emit MAX 1 coreClaim, 1 keyTerm, and 1-2 short sections per chapter; every section MUST include integer pdfPage copied from Sections (p.XXX) or [p.XXX]; coreClaims[*].quoteAnchor strictly verbatim from [p.XXX].
3. ${StudyModePromptBuilder.langInstruction(req.explainLang)}"""

        val chunkFutures = chunks.mapIndexed { chunkIdx, group ->
            async(Dispatchers.IO) {
                val prompt = buildString {
                    append("Book: ").append(req.bookTitle).append('\n')
                    group.forEach { ch ->
                        append("=== CHAPTER ").append(ch.chapterNum).append(": ").append(ch.chapterTitle).append(" ===\n")
                        if (ch.sections.isNotEmpty()) {
                            append("Sections: ").append(ch.sections.take(4).joinToString("; ") { "${it.title}(p.${it.startPdfPage})" }).append('\n')
                        }
                        val firstPage = ch.pages.firstOrNull()
                        if (firstPage != null) {
                            val clean = Normalizer.normalize(firstPage.text, Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()
                            val cut = clean.take(900)
                            val lastDot = cut.lastIndexOf(". ")
                            val trimmed = if (clean.length > 900 && lastDot >= 450) cut.substring(0, lastDot + 1) else cut
                            append("[p.").append(firstPage.pdfPage).append("] ").append(trimmed).append('\n')
                        }
                    }
                }
                val reply = batchTransport.complete(
                    task = "BOOK_SECTION_BATCH",
                    jobKey = StudyModeJsonHelpers.sha256Hex("bm-v4-${req.bookId}-${chunkIdx}-${batchVariantSig}"),
                    systemPrompt = mapSys,
                    userPrompt = prompt,
                    maxTokens = 8192,
                    useBatchApi = req.useBatchApi,
                    friend = req.friend,
                    device = req.device,
                )
                totalIn.addAndGet(reply.inputTokens)
                totalOut.addAndGet(reply.outputTokens)
                if (req.friend == null) ledger?.record(
                    TokenLedgerEntry(
                        bookId = req.bookId,
                        chapterId = "book-map-chunk-$chunkIdx",
                        sectionId = "BOOK_MAP_BATCH",
                        task = "BOOK_MAP_BATCH_CHUNK",
                        routeChannel = reply.routeChannel,
                        model = reply.model,
                        inputTokens = reply.inputTokens,
                        outputTokens = reply.outputTokens,
                        elapsedMs = reply.elapsedMs,
                        status = "ok",
                    ),
                )
                StudyModeJsonHelpers.extractJsonObject(reply.text)["chapters"]?.jsonArray.orEmpty()
                    .mapNotNull { it as? JsonObject }
            }
        }

        val rawChapters = chunkFutures.awaitAll().flatten()
        val chapterByNum = req.chapters.associateBy { it.chapterNum }

        var totalAnchors = 0
        var verifiedAnchors = 0
        var relocatedAnchors = 0
        val verifiedChapters = rawChapters.sortedBy { it["chapterNum"]?.jsonPrimitive?.intOrNull ?: 0 }.map { chObj ->
            val num = chObj["chapterNum"]?.jsonPrimitive?.intOrNull ?: 0
            val inputCh = chapterByNum[num]
            val cleanPlaceholders = StudyModeMathGate.sanitizePlaceholders(chObj) as JsonObject
            val rawSections = cleanPlaceholders["sections"] as? JsonArray
            val sectionsLackQuotes = rawSections != null && rawSections.none { (it as? JsonObject)?.containsKey("quoteAnchor") == true }
            val payloadForAnchorGate = if (sectionsLackQuotes) JsonObject(cleanPlaceholders - "sections") else cleanPlaceholders
            val verifiedChapter = if (inputCh != null && inputCh.pages.isNotEmpty()) {
                val (verifiedObj, st, repairReply) = StudyModeAnchorGate.verifyAndRepair(
                    payload = payloadForAnchorGate,
                    pages = inputCh.pages,
                    repairTransport = batchTransport,
                    bookId = req.bookId,
                    chapterNum = num,
                    friend = req.friend,
                    device = req.device,
                    repairOnlyWhenZeroValid = true,
                )
                repairReply?.let { r ->
                    totalIn.addAndGet(r.inputTokens)
                    totalOut.addAndGet(r.outputTokens)
                }
                totalAnchors += st.total
                verifiedAnchors += st.verified
                relocatedAnchors += st.relocated
                if (sectionsLackQuotes) {
                    JsonObject(verifiedObj.toMutableMap().apply { put("sections", rawSections) })
                } else {
                    verifiedObj
                }
            } else {
                cleanPlaceholders
            }
            if (inputCh != null) normalizeBatchChapterSectionPages(verifiedChapter, inputCh) else verifiedChapter
        }

        // Cross-chapter synthesis call: clusters, dependency edges, 40-min fast skim route, bilingual master glossary
        val graphSys = """Synthesize a whole-book navigation map (concise, total output <= 1600 tokens) from chapter one-pagers.
Return ONLY JSON:
{"thematicClusters":[{"clusterId":"c1","title":"中文主题","themeSummary":"<=14字","chapterNums":[1,2]}],"dependencyEdges":[{"fromChapter":1,"toChapter":2,"relation":"PREREQUISITE|BUILDS_ON|CONTRASTS","reason":"<=12字"}],"fastSkimOrder40Min":[{"chapterNum":1,"minutes":5,"focus":"<=12字"}],"masterGlossary":[{"en":"term","zh":"中文","chapterNum":1}]}
Limits: max 4 thematicClusters, max 8 dependencyEdges, max 8 chapters in fastSkimOrder40Min (summing ~40 min), max 12 masterGlossary terms. ${StudyModePromptBuilder.langInstruction(req.explainLang)}"""
        val graphUser = buildString {
            append("Book: ").append(req.bookTitle).append('\n')
            if (req.courseTopics.isNotEmpty()) {
                append("CourseTopics: ").append(req.courseTopics.joinToString("; ")).append('\n')
            }
            verifiedChapters.forEach { c ->
                append("Ch.").append(c["chapterNum"]?.jsonPrimitive?.intOrNull ?: 0)
                    .append(" ").append(c["chapterTitle"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    .append(": ").append(c["oneLiner"]?.jsonPrimitive?.contentOrNull.orEmpty()).append('\n')
            }
        }
        val graphReply = batchTransport.complete(
            task = "BOOK_SECTION_BATCH",
            jobKey = StudyModeJsonHelpers.sha256Hex("bm-v4-graph-${req.bookId}-${batchVariantSig}"),
            systemPrompt = graphSys,
            userPrompt = graphUser,
            maxTokens = 8192,
            useBatchApi = req.useBatchApi,
            friend = req.friend,
            device = req.device,
        )
        totalIn.addAndGet(graphReply.inputTokens)
        totalOut.addAndGet(graphReply.outputTokens)
        if (req.friend == null) ledger?.record(
            TokenLedgerEntry(
                bookId = req.bookId,
                chapterId = "book-map-graph",
                sectionId = "BOOK_MAP_GRAPH",
                task = "BOOK_MAP_BATCH_GRAPH",
                routeChannel = graphReply.routeChannel,
                model = graphReply.model,
                inputTokens = graphReply.inputTokens,
                outputTokens = graphReply.outputTokens,
                elapsedMs = graphReply.elapsedMs,
                status = "ok",
            ),
        )
        val graphObj = StudyModeMathGate.sanitizePlaceholders(StudyModeJsonHelpers.extractJsonObject(graphReply.text)) as JsonObject

        // Haiku 5.5 Batch pricing per model_routes.json: $0.10/M input, $0.50/M output (Batch 50% off -> $0.05/M, $0.25/M).
        val inRate = if (req.useBatchApi && req.friend == null) 0.05 else 0.10
        val outRate = if (req.useBatchApi && req.friend == null) 0.25 else 0.50
        val estUsd = (totalIn.get() / 1_000_000.0) * inRate + (totalOut.get() / 1_000_000.0) * outRate
        val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)

        val result = buildJsonObject {
            put("bookId", req.bookId)
            put("bookTitle", req.bookTitle)
            put("detailLevel", req.detailLevel.name)
            put("explainLang", req.explainLang.name)
            put("zeroAiTokens", false)
            put("cacheHit", false)
            put("chapterCount", verifiedChapters.size)
            put("chapters", JsonArray(verifiedChapters))
            put("thematicClusters", graphObj["thematicClusters"] as? JsonArray ?: buildJsonArray {})
            put("dependencyEdges", graphObj["dependencyEdges"] as? JsonArray ?: buildJsonArray {})
            put("fastSkimOrder40Min", graphObj["fastSkimOrder40Min"] as? JsonArray ?: buildJsonArray {})
            put("masterGlossary", graphObj["masterGlossary"] as? JsonArray ?: buildJsonArray {})
            put(
                "anchorStats",
                StudyModeAnchorStats(
                    total = totalAnchors,
                    verified = verifiedAnchors,
                    unverified = (totalAnchors - verifiedAnchors).coerceAtLeast(0),
                    relocated = relocatedAnchors,
                ).toJson(),
            )
            put(
                "costStats",
                buildJsonObject {
                    put("inputTokens", totalIn.get())
                    put("outputTokens", totalOut.get())
                    put("estimatedCostUsd", kotlin.math.round(estUsd * 10000.0) / 10000.0)
                    put("batchMode", req.useBatchApi && req.friend == null)
                },
            )
            put("latencyMs", elapsedMs)
        }
        cache?.put(batchCacheKey, cacheScope, req.bookId, 0, "BOOK_MAP_BATCH", result)
        return@coroutineScope result
    }

    private val batchSecNumRegex = Regex("""\b(\d+\.\d+(?:\.\d+)?)\b""")

    internal fun normalizeBatchChapterSectionPages(
        chObj: JsonObject,
        inputCh: BookMapChapterInput,
    ): JsonObject {
        val arr = chObj["sections"] as? JsonArray ?: return chObj
        if (arr.isEmpty()) return chObj
        val sectionStarts = inputCh.sections.map { it.startPdfPage }.filter { it > 0 }
        val pageNums = inputCh.pages.map { it.pdfPage }.filter { it > 0 }
        val minPage = (sectionStarts + pageNums).minOrNull() ?: return chObj
        val maxEnd = (inputCh.sections.map { maxOf(it.startPdfPage, it.endPdfPage) }.filter { it > 0 } + pageNums).maxOrNull() ?: minPage
        val fallbackPage = sectionStarts.firstOrNull() ?: pageNums.firstOrNull() ?: minPage

        val normalizedSections = buildJsonArray {
            arr.forEachIndexed { idx, el ->
                val secObj = el as? JsonObject ?: return@forEachIndexed
                val secTitle = secObj["sectionTitle"]?.jsonPrimitive?.contentOrNull
                    ?: secObj["sectionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val secNum = batchSecNumRegex.find(secTitle)?.groupValues?.get(1)
                val explicitMatch = inputCh.sections.firstOrNull { inSec ->
                    val inNum = batchSecNumRegex.find(inSec.title)?.groupValues?.get(1)
                        ?: batchSecNumRegex.find(inSec.sectionId)?.groupValues?.get(1)
                    (secNum != null && inNum == secNum) ||
                        (secTitle.isNotBlank() && (
                            inSec.title.contains(secTitle, ignoreCase = true) ||
                                secTitle.contains(inSec.title, ignoreCase = true)
                            ))
                }
                val matchedInputSec = explicitMatch ?: inputCh.sections.getOrNull(idx)
                val rawPage = secObj["pdfPage"]?.jsonPrimitive?.intOrNull
                val resolvedPage = when {
                    matchedInputSec != null && matchedInputSec.startPdfPage > 0 -> {
                        val secEnd = maxOf(matchedInputSec.startPdfPage, matchedInputSec.endPdfPage)
                        if (rawPage != null && rawPage in matchedInputSec.startPdfPage..secEnd) rawPage
                        else matchedInputSec.startPdfPage
                    }
                    rawPage != null && rawPage in minPage..maxEnd -> rawPage
                    else -> fallbackPage
                }
                add(
                    JsonObject(
                        secObj.toMutableMap().apply {
                            put("pdfPage", JsonPrimitive(resolvedPage))
                        },
                    ),
                )
            }
        }
        return JsonObject(chObj.toMutableMap().apply { put("sections", normalizedSections) })
    }

    private fun buildOriginalOnlyPack(req: StudyModeRequest, elapsedMs: Long): JsonObject = buildJsonObject {
        put("bookId", req.bookId)
        put("bookTitle", req.bookTitle.ifBlank { req.bookId })
        put("chapterNum", req.chapterNum)
        put("chapterTitle", req.chapterTitle)
        put("studyMode", req.mode.name)
        put("detailLevel", ServerDetailLevel.ORIGINAL_ONLY.name)
        put("explainLang", req.explainLang.name)
        put("inquiryQuestion", req.inquiryQuestion)
        put("zeroAiTokens", true)
        put("cacheHit", false)
        put("anchorStats", StudyModeAnchorStats().toJson())
        put("mathStats", StudyModeMathStats().toJson())
        put("overlapStats", buildJsonObject { put("maxAsciiWords", 0); put("pastExamOverlap", 0) })
        put("latencyMs", buildJsonObject { put("firstTokenMs", elapsedMs); put("ttftMs", elapsedMs); put("totalElapsedMs", elapsedMs) })
        put(
            "sections",
            buildJsonArray {
                req.sections.forEach { sec ->
                    add(
                        buildJsonObject {
                            put("sectionId", sec.sectionId)
                            put("title", sec.title)
                            put("startPdfPage", sec.startPdfPage)
                            put("endPdfPage", sec.endPdfPage)
                        },
                    )
                }
            },
        )
        put(
            "pages",
            buildJsonArray {
                req.pages.forEach { p ->
                    add(buildJsonObject { put("pdfPage", p.pdfPage); put("text", p.text) })
                }
            },
        )
    }

    private fun collectNonQuoteProse(obj: JsonElement): String {
        val sb = StringBuilder()
        fun walk(el: JsonElement, key: String) {
            when (el) {
                is JsonObject -> el.forEach { (k, v) -> walk(v, k) }
                is JsonArray -> el.forEach { walk(it, key) }
                is JsonPrimitive -> {
                    if (el.isString && key !in setOf("quoteAnchor", "exerciseQuoteAnchor", "formulaLatex", "studyMode", "detailLevel", "explainLang")) {
                        sb.append(el.content).append(' ')
                    }
                }
            }
        }
        walk(obj, "")
        return sb.toString()
    }
}

private object StudyModeRequestParser {
    fun parseModeRequest(bookId: String, mode: ServerStudyMode, input: JsonObject, friend: String?, device: String): StudyModeRequest {
        require(bookId.isNotBlank() && bookId.length <= 128) { "bookId 不合法。" }
        val chapterNum = input["chapterNum"]?.jsonPrimitive?.intOrNull
            ?: input["chapter_num"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("缺少 chapterNum。")
        require(chapterNum >= 0) { "chapterNum 必须为非负整数。" }
        val detail = ServerDetailLevel.parse(
            input["detailLevel"]?.jsonPrimitive?.contentOrNull
                ?: input["detail_level"]?.jsonPrimitive?.contentOrNull,
        )
        val lang = ServerExplainLang.parse(
            input["explainLang"]?.jsonPrimitive?.contentOrNull
                ?: input["explain_lang"]?.jsonPrimitive?.contentOrNull,
        )
        val inquiry = (input["inquiryQuestion"]?.jsonPrimitive?.contentOrNull
            ?: input["inquiry_question"]?.jsonPrimitive?.contentOrNull).orEmpty().trim().take(800)
        val sections = parseSections(input["sections"] as? JsonArray)
        val rawPages = parsePages(input["pages"] as? JsonArray).ifEmpty {
            sections.filter { it.text.isNotBlank() && it.startPdfPage > 0 }.map { StudyPageInput(it.startPdfPage, it.text) }
        }
        if (detail != ServerDetailLevel.ORIGINAL_ONLY) {
            require(rawPages.isNotEmpty()) { "非原版原文模式必须提供章节 pages 原文内容以校验逐字锚点。" }
        }
        val topics = (input["courseTopics"] as? JsonArray ?: input["course_topics"] as? JsonArray)
            .orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotBlank() }
        val pastExamForbidden = (input["pastExamForbiddenFragments"] as? JsonArray)
            .orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.length >= 12 }
        return StudyModeRequest(
            bookId = bookId,
            bookTitle = (input["bookTitle"]?.jsonPrimitive?.contentOrNull ?: input["book_title"]?.jsonPrimitive?.contentOrNull).orEmpty(),
            chapterNum = chapterNum,
            chapterTitle = (input["chapterTitle"]?.jsonPrimitive?.contentOrNull ?: input["chapter_title"]?.jsonPrimitive?.contentOrNull).orEmpty(),
            mode = mode,
            detailLevel = detail,
            explainLang = lang,
            inquiryQuestion = inquiry,
            courseTopics = topics,
            pages = rawPages.sortedBy { it.pdfPage },
            sections = sections,
            pastExamForbiddenFragments = pastExamForbidden,
            bypassCache = input["bypassCache"]?.jsonPrimitive?.booleanOrNull == true || input["forceRefresh"]?.jsonPrimitive?.booleanOrNull == true,
            requestId = input["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            friend = friend,
            device = device,
        )
    }

    fun parseBatchRequest(bookId: String, input: JsonObject, friend: String?, device: String): BookMapBatchRequest {
        require(bookId.isNotBlank() && bookId.length <= 128) { "bookId 不合法。" }
        val chaptersArr = input["chapters"] as? JsonArray ?: throw IllegalArgumentException("缺少 chapters 列表。")
        require(chaptersArr.size in 1..60) { "chapters 数量须在 1..60 之间。" }
        val chapters = chaptersArr.map { elem ->
            val obj = elem as? JsonObject ?: throw IllegalArgumentException("chapters 元素必须是对象。")
            val num = obj["chapterNum"]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("章节缺少 chapterNum。")
            val title = obj["chapterTitle"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val sections = parseSections(obj["sections"] as? JsonArray)
            val pages = parsePages(obj["pages"] as? JsonArray).ifEmpty {
                sections.filter { it.text.isNotBlank() && it.startPdfPage > 0 }.map { StudyPageInput(it.startPdfPage, it.text) }
            }
            BookMapChapterInput(num, title, pages.sortedBy { it.pdfPage }, sections)
        }
        val topics = (input["courseTopics"] as? JsonArray ?: input["course_topics"] as? JsonArray)
            .orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotBlank() }
        return BookMapBatchRequest(
            bookId = bookId,
            bookTitle = input["bookTitle"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { bookId },
            detailLevel = ServerDetailLevel.parse(input["detailLevel"]?.jsonPrimitive?.contentOrNull ?: "BRIEF"),
            explainLang = ServerExplainLang.parse(input["explainLang"]?.jsonPrimitive?.contentOrNull),
            courseTopics = topics,
            chapters = chapters,
            useBatchApi = input["useBatchApi"]?.jsonPrimitive?.booleanOrNull ?: true,
            bypassCache = input["bypassCache"]?.jsonPrimitive?.booleanOrNull == true,
            requestId = input["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            friend = friend,
            device = device,
        )
    }

    private fun parsePages(arr: JsonArray?): List<StudyPageInput> = arr.orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val pg = o["pdfPage"]?.jsonPrimitive?.intOrNull ?: o["page"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val text = o["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        if (pg > 0 && text.isNotBlank()) StudyPageInput(pg, text) else null
    }

    private fun parseSections(arr: JsonArray?): List<StudySectionInput> = arr.orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val sid = o["sectionId"]?.jsonPrimitive?.contentOrNull ?: title
        val start = o["startPdfPage"]?.jsonPrimitive?.intOrNull ?: o["pdfPage"]?.jsonPrimitive?.intOrNull ?: 0
        val end = o["endPdfPage"]?.jsonPrimitive?.intOrNull ?: start
        val text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        StudySectionInput(sid, title, start, end, text)
    }
}

fun Application.studyModeRoutes(
    actor: LedgerActor,
    library: ReaderLibrary? = null,
    friends: FriendAccess? = null,
    service: StudyModePackService? = null,
    unifiedBooks: UnifiedBookPipeline? = null,
) {
    routing {
        post("/api/books/{id}/modes/{mode}") {
            call.withReaderConnection {
                val activeService = service ?: return@withReaderConnection call.respondText(
                    buildJsonObject { put("error", "学法伴读服务暂未开启，请稍后重试。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                val bookId = call.parameters["id"].orEmpty().trim()
                val modeRaw = call.parameters["mode"].orEmpty().trim()
                val mode = ServerStudyMode.parse(modeRaw)
                    ?: return@withReaderConnection call.respondText(
                        buildJsonObject { put("error", "不支持的学习模式：$modeRaw") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                val friend = call.attributes.getOrNull(observedFriend)
                val device = call.attributes[observedDevice]
                val unifiedAuthorized = unifiedBooks?.enabled == true && withContext(Dispatchers.IO) {
                    runCatching { unifiedBooks.hasVerifiedShelfBook(UnifiedBookScope.fromCall(device, friend), bookId) }.getOrDefault(false)
                }
                if (!unifiedAuthorized && library?.reservesVersion(bookId) == true) {
                    return@withReaderConnection call.respondText(
                        buildJsonObject { put("error", "该书受课程版权保留约束，不支持该入口。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Conflict,
                    )
                }

                val bearer = call.request.header("Authorization")?.removePrefix("Bearer ").orEmpty()
                val diagnostics = friend?.let { FriendReaderDiagnostics() }
                var requestId = ""

                try {
                    if (friend != null && friends != null) {
                        val allowed = actor.execute { authorize(device, bearer) && friendName(device) == friend }
                        if (!allowed) throw MockError(HttpStatusCode.Conflict, "设备授权已失效，无法使用学法生成。")
                    }

                    val maxBytes = 6 * 1024 * 1024
                    val bodyBytes = call.receiveChannel().readRemaining(maxBytes.toLong() + 1).readByteArray()
                    if (bodyBytes.size > maxBytes) {
                        call.response.header(HttpHeaders.Connection, "close")
                        throw MockError(HttpStatusCode.PayloadTooLarge, "请求体积超过 6MB 上限。")
                    }
                    val input = LedgerJson.parseToJsonElement(bodyBytes.decodeToString()).jsonObject
                    requestId = input["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val req = StudyModeRequestParser.parseModeRequest(bookId, mode, input, friend, device)
                    val cacheScope = friend?.let { "friend:${friendId(it)}" } ?: "owner:$device"
                    val wantsStream = (call.request.accept()?.contains("text/event-stream") == true) ||
                        (input["stream"]?.jsonPrimitive?.booleanOrNull == true)

                    if (wantsStream) {
                        call.response.header(HttpHeaders.CacheControl, "no-cache")
                        call.response.header("X-Accel-Buffering", "no")
                        call.respondTextWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
                            val sink = WriterStudyModeEventSink(this)
                            try {
                                val pack = withContext(Dispatchers.IO) {
                                    activeService.generateModePack(req, sink = sink, cacheScope = cacheScope)
                                }
                                if (friend != null && friends != null) {
                                    withContext(Dispatchers.IO) {
                                        runCatching { friends.record(friend, device, requestId, "complete", pack, diagnostics) }
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                sink.emit("error", buildJsonObject { put("error", e.message ?: "学法生成失败") })
                            }
                        }
                    } else {
                        val pack = withContext(Dispatchers.IO) {
                            activeService.generateModePack(req, sink = null, cacheScope = cacheScope)
                        }
                        if (friend != null && friends != null) {
                            withContext(Dispatchers.IO) {
                                try {
                                    friends.record(friend, device, requestId, "complete", pack, diagnostics)
                                } catch (_: Exception) {
                                    throw FriendServiceError()
                                }
                            }
                        }
                        call.respondText(pack.toString(), ContentType.Application.Json)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FriendServiceError) {
                    if (friend != null) {
                        withContext(Dispatchers.IO) { runCatching { friends?.record(friend, device, requestId, e.code, diagnostics = diagnostics) } }
                    }
                    call.respondText(e.body.toString(), ContentType.Application.Json, e.status)
                } catch (e: MockError) {
                    call.respondText(buildJsonObject { put("error", e.message ?: "请求无效。") }.toString(), ContentType.Application.Json, e.status)
                } catch (e: IllegalArgumentException) {
                    call.respondText(buildJsonObject { put("error", e.message ?: "参数校验失败。") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                } catch (_: kotlinx.serialization.SerializationException) {
                    call.respondText(buildJsonObject { put("error", "请求 JSON 格式错误。") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                } catch (e: Exception) {
                    call.respondText(buildJsonObject { put("error", e.message ?: "学法生成服务异常。") }.toString(), ContentType.Application.Json, HttpStatusCode.BadGateway)
                }
            }
        }

        post("/api/books/{id}/book-map/batch") {
            call.withReaderConnection {
                val activeService = service ?: return@withReaderConnection call.respondText(
                    buildJsonObject { put("error", "全书地图批处理服务暂未开启。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                val bookId = call.parameters["id"].orEmpty().trim()
                val friend = call.attributes.getOrNull(observedFriend)
                val device = call.attributes[observedDevice]
                val unifiedAuthorized = unifiedBooks?.enabled == true && withContext(Dispatchers.IO) {
                    runCatching { unifiedBooks.hasVerifiedShelfBook(UnifiedBookScope.fromCall(device, friend), bookId) }.getOrDefault(false)
                }
                if (!unifiedAuthorized && library?.reservesVersion(bookId) == true) {
                    return@withReaderConnection call.respondText(
                        buildJsonObject { put("error", "该书受课程版权保留约束。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Conflict,
                    )
                }
                val bearer = call.request.header("Authorization")?.removePrefix("Bearer ").orEmpty()
                val diagnostics = friend?.let { FriendReaderDiagnostics() }
                var requestId = ""

                try {
                    if (friend != null && friends != null) {
                        val allowed = actor.execute { authorize(device, bearer) && friendName(device) == friend }
                        if (!allowed) throw MockError(HttpStatusCode.Conflict, "设备授权已失效。")
                    }

                    val maxBytes = 18 * 1024 * 1024
                    val bodyBytes = call.receiveChannel().readRemaining(maxBytes.toLong() + 1).readByteArray()
                    if (bodyBytes.size > maxBytes) {
                        call.response.header(HttpHeaders.Connection, "close")
                        throw MockError(HttpStatusCode.PayloadTooLarge, "全书地图请求体超出 18MB 上限。")
                    }
                    val input = LedgerJson.parseToJsonElement(bodyBytes.decodeToString()).jsonObject
                    requestId = input["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val req = StudyModeRequestParser.parseBatchRequest(bookId, input, friend, device)
                    val cacheScope = friend?.let { "friend:${friendId(it)}" } ?: "owner:$device"
                    val result = activeService.generateBookMapBatch(req, cacheScope = cacheScope)
                    if (friend != null && friends != null) {
                        withContext(Dispatchers.IO) {
                            try {
                                friends.record(friend, device, requestId, "complete", result, diagnostics)
                            } catch (_: Exception) {
                                throw FriendServiceError()
                            }
                        }
                    }
                    call.respondText(result.toString(), ContentType.Application.Json)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FriendServiceError) {
                    if (friend != null) {
                        withContext(Dispatchers.IO) { runCatching { friends?.record(friend, device, requestId, e.code, diagnostics = diagnostics) } }
                    }
                    call.respondText(e.body.toString(), ContentType.Application.Json, e.status)
                } catch (e: MockError) {
                    call.respondText(buildJsonObject { put("error", e.message ?: "请求无效。") }.toString(), ContentType.Application.Json, e.status)
                } catch (e: IllegalArgumentException) {
                    call.respondText(buildJsonObject { put("error", e.message ?: "全书地图参数不完整。") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                } catch (_: kotlinx.serialization.SerializationException) {
                    call.respondText(buildJsonObject { put("error", "JSON 格式错误。") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                } catch (e: Exception) {
                    call.respondText(buildJsonObject { put("error", e.message ?: "全书地图生成失败。") }.toString(), ContentType.Application.Json, HttpStatusCode.BadGateway)
                }
            }
        }
    }
}
