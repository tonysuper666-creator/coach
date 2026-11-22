package com.coach.server

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
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.util.UUID

/**
 * Cross-platform deterministic sentence-level source verification gate for LearningCoach 2.0-AI-3.
 * Matches `bench_course_qa.py` NFKC + ligature normalization, cross-extractor pure-prose trimming
 * (`pdftotext` / `PyMuPDF` / `PDFBox` math-subscript spacing), and `>= 0.85` word-window snapping.
 */
object ServerSentenceCitationGate {
    data class AnchorResult(
        val rawExactMatch: Boolean,
        val verified: Boolean,
        val canonicalAnchor: String,
    )

    private val whitespaceRegex = Regex("""\s+""")
    private val mathSpacingNoiseRegex = Regex("""[∗*^]|[A-Z]{1,2}\s+-[A-Z]{1,2}|\b[A-Z]\s+[A-Z]\b""")
    private val mathSpacingSplitRegex = Regex("""\S*[∗*^]\S*|[A-Z]{1,2}\s+-[A-Z]{1,2}|\b[A-Z]\s+[A-Z]\b""")
    private val tokenRegex = Regex("""[A-Za-z0-9]+|[\u4e00-\u9fff]""")
    private val wordSpanRegex = Regex("""\S+""")
    private val inlineTimestampRegex = Regex("""\[((?:\d{1,2}:)?\d{1,2}:\d{2})(?:[.,]\d{1,3})?]""")

    fun normalizeText(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            when (ch) {
                '\u2212', '\u2013', '\u2014' -> sb.append('-')
                '\u2018', '\u2019' -> sb.append('\'')
                '\u201c', '\u201d' -> sb.append('"')
                '\u00a0' -> sb.append(' ')
                '\ufb01' -> sb.append("fi")
                '\ufb02' -> sb.append("fl")
                '\ufb00' -> sb.append("ff")
                '\ufb03' -> sb.append("ffi")
                '\ufb04' -> sb.append("ffl")
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(whitespaceRegex, " ").trim()
    }

    fun verifyOrSnapAnchor(quote: String, rawPageText: String): AnchorResult {
        val normPage = normalizeText(rawPageText)
        val q = normalizeText(quote)
        if (normPage.isEmpty() || q.length < 5) {
            return AnchorResult(rawExactMatch = false, verified = false, canonicalAnchor = "")
        }

        val idx = normPage.indexOf(q, ignoreCase = true)
        if (idx >= 0) {
            val exactSlice = normPage.substring(idx, idx + q.length)
            return AnchorResult(
                rawExactMatch = true,
                verified = true,
                canonicalAnchor = trimPureProseAnchor(exactSlice),
            )
        }

        val qTokens = tokenRegex.findAll(q.lowercase()).map { it.value }.toList()
        if (qTokens.size < 3) {
            return AnchorResult(rawExactMatch = false, verified = false, canonicalAnchor = "")
        }

        val words = wordSpanRegex.findAll(normPage).toList()
        if (words.isEmpty()) {
            return AnchorResult(rawExactMatch = false, verified = false, canonicalAnchor = "")
        }

        val win = minOf(words.size, maxOf(4, qTokens.size + 2))
        var bestRatio = 0.0
        var bestSlice = ""
        val maxStart = (words.size - win).coerceAtLeast(0)
        for (i in 0..maxStart) {
            val sPos = words[i].range.first
            val ePos = words[minOf(words.size - 1, i + win - 1)].range.last + 1
            val cand = normPage.substring(sPos, ePos)
            val candLow = cand.lowercase()
            var hits = 0
            for (t in qTokens) {
                if (t in candLow) hits++
            }
            val ratio = hits.toDouble() / qTokens.size.toDouble()
            if (ratio > bestRatio) {
                bestRatio = ratio
                bestSlice = cand
            }
        }
        if (bestRatio >= 0.85 && bestSlice.isNotEmpty()) {
            return AnchorResult(
                rawExactMatch = false,
                verified = true,
                canonicalAnchor = trimPureProseAnchor(bestSlice),
            )
        }
        return AnchorResult(rawExactMatch = false, verified = false, canonicalAnchor = "")
    }

    fun extractNearestTimestamp(rawPageText: String, canonicalAnchor: String): String? {
        if (canonicalAnchor.isBlank() || "[" !in rawPageText) return null
        val normAnchor = normalizeText(canonicalAnchor)
        val lines = rawPageText.replace("\r\n", "\n").lines()
        var currentTs: String? = null
        for (line in lines) {
            val tsMatch = inlineTimestampRegex.findAll(line).lastOrNull()
            if (tsMatch != null) {
                currentTs = canonicalTimestampLabel(tsMatch.groupValues[1]) ?: currentTs
            }
            val normLine = normalizeText(line)
            if (normAnchor.length >= 6 && normLine.contains(normAnchor, ignoreCase = true)) {
                return currentTs
            }
        }
        val anchorWords = tokenRegex.findAll(normAnchor.lowercase()).map { it.value }.toList()
        if (anchorWords.size >= 3) {
            var bestTs: String? = null
            var bestScore = 0
            currentTs = null
            for (line in lines) {
                val tsMatch = inlineTimestampRegex.findAll(line).lastOrNull()
                if (tsMatch != null) {
                    currentTs = canonicalTimestampLabel(tsMatch.groupValues[1]) ?: currentTs
                }
                val normLine = normalizeText(line).lowercase()
                val hits = anchorWords.count { it in normLine }
                if (hits > bestScore && currentTs != null) {
                    bestScore = hits
                    bestTs = currentTs
                }
            }
            if (bestScore >= maxOf(2, (anchorWords.size + 1) / 2)) return bestTs
        }
        return null
    }

    private fun trimPureProseAnchor(slice: String): String {
        if (!mathSpacingNoiseRegex.containsMatchIn(slice)) return slice
        val proseParts = mathSpacingSplitRegex.split(slice)
            .map { it.trim() }
            .filter { part ->
                part.length >= 16 && part.split(whitespaceRegex).count { it.isNotEmpty() } >= 3
            }
        return proseParts.maxByOrNull { it.length } ?: slice
    }

    private fun canonicalTimestampLabel(raw: String): String? {
        val parts = raw.trim().split(':')
        if (parts.size !in 2..3) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return if (nums.size == 3) {
            "%02d:%02d:%02d".format(nums[0], nums[1], nums[2])
        } else {
            "%02d:%02d".format(nums[0], nums[1])
        }
    }
}

data class CollectionQaBlockInput(
    val blockId: String,
    val bookId: String,
    val docLabel: String,
    val authorityRank: Int,
    val page: Int,
    val timestampLabel: String? = null,
    val text: String,
)

data class CollectionQaDiscrepancyCitationInput(
    val bookId: String,
    val docLabel: String,
    val page: Int,
    val timestampLabel: String? = null,
    val quoteAnchor: String,
)

data class CollectionQaDiscrepancyInput(
    val id: String,
    val point: String,
    val authoritativeView: String,
    val secondaryView: String,
    val advice: String,
    val directAspectMatch: Boolean = false,
    val citations: List<CollectionQaDiscrepancyCitationInput> = emptyList(),
)

data class CollectionQaTransportReply(
    val text: String,
    val servedBy: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val ttftMs: Long,
    val elapsedMs: Long,
    val status: String = "complete",
)

interface CollectionQaTransport {
    suspend fun ask(
        systemPrompt: String,
        userPrompt: String,
        friend: String? = null,
        device: String? = null,
        maxTokens: Int = 6144,
    ): CollectionQaTransportReply
}

/**
 * Streaming Haiku 5.5 transport via Coach Bridge (`task = "READER_SELECTION"`, `model = "claude-haiku-5-5"`).
 * Respects the bridge 95% API grant guard (`CreditLedger`) for both owner and friend traffic.
 */
class HaikuStreamingCollectionQaTransport(
    private val bridge: BridgeClient,
) : CollectionQaTransport {
    override suspend fun ask(
        systemPrompt: String,
        userPrompt: String,
        friend: String?,
        device: String?,
        maxTokens: Int,
    ): CollectionQaTransportReply = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        var firstDeltaAtMs: Long? = null
        var servedModel = BridgeClient.HAIKU
        val buf = StringBuilder()
        val sink = object : BridgeStreamSink {
            override val supportsReset: Boolean = true
            override fun onReset() {
                buf.setLength(0)
                firstDeltaAtMs = null
            }

            override fun onMeta(servedBy: String) {
                if (servedBy.isNotBlank()) servedModel = servedBy
            }

            override fun onDelta(text: String, atMs: Long) {
                if (firstDeltaAtMs == null && text.isNotEmpty()) firstDeltaAtMs = atMs
                buf.append(text)
            }
        }
        val result = bridge.streamChat(
            ChatRequest(
                task = "READER_SELECTION",
                model = BridgeClient.HAIKU,
                system = systemPrompt,
                user = userPrompt,
                stream = true,
                background = false,
                maxTokens = maxTokens,
                friendId = friend,
                friendDevice = if (friend != null) device else null,
            ),
            sink = sink,
        )
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        if (result !is BridgeResult.Success) {
            val failure = result as? BridgeResult.Failure
            if (friend != null) {
                val code = failure?.terminal?.reason?.takeIf { it in setOf("daily_quota", "monthly_quota") } ?: "unavailable"
                throw FriendServiceError(code)
            }
            val msg = failure?.terminal?.reason?.takeIf { it in setOf("daily_quota", "monthly_quota") }
                ?.let { "当前问答额度已用尽，请稍后再试。" }
                ?: "学习服务暂时繁忙，请稍后再试。"
            throw MockError(HttpStatusCode.ServiceUnavailable, msg)
        }
        val finalText = result.text.ifBlank { buf.toString() }
        val inTokens = TokenLedgerPricing.estimateTokens(systemPrompt) + TokenLedgerPricing.estimateTokens(userPrompt)
        val outTokens = TokenLedgerPricing.estimateTokens(finalText)
        CollectionQaTransportReply(
            text = finalText,
            servedBy = result.terminal.servedBy?.takeIf { it.isNotBlank() } ?: servedModel,
            inputTokens = inTokens,
            outputTokens = outTokens,
            ttftMs = result.terminal.ttftMs ?: firstDeltaAtMs ?: elapsedMs,
            elapsedMs = result.terminal.elapsedMs ?: elapsedMs,
            status = result.terminal.status,
        )
    }
}

/**
 * LearningCoach 2.0-AI-3 server-side whole-course & booklist grounded Q&A service.
 * Enforces full-page prompts (<= 4,200 chars/page), past-exam leakage rejection,
 * sentence-by-sentence source verification (`ServerSentenceCitationGate`), offline discrepancy merging,
 * and `BookTokenLedger` accounting. Stateless with zero cross-user page cache.
 */
class CollectionQaService(
    private val transport: CollectionQaTransport,
    private val ledger: BookTokenLedger? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val MAX_BLOCKS = 12
        const val MAX_BLOCK_CHARS = 4_200
        const val MAX_DISCREPANCIES = 6
        const val MAX_PROMPT_UTF8_BYTES = 78_000
        private val BLOCK_ID_REGEX = Regex("""B\d{1,2}""")
        private val LEAKAGE_REGEX = Regex(
            """(?i)(?:202[0-5]\s*年?\s*(?:期末|真题|考过)|期末考过|真题详解|押题|留出集|dec(?:ember)?\s*2024\s*(?:exam|final|paper)|past\s*paper\s*question|(?:历年|往年)?真题|历年(?:试卷|考题))""",
        )

        private val COLLECTION_QA_SYSTEM = """
            You are LearningCoach's whole-course grounded Q&A synthesis engine (NO external tools).
            Answer the student's question completely and accurately in plain Simplified Chinese using ONLY the provided evidence blocks [B1]..[Bk].
            Strict Rules:
            1. Read EVERY line of ALL provided blocks [B1]..[Bk] carefully (including page bottoms, footnotes, figure notes, and timestamped transcript lines). Answer ALL sub-questions explicitly.
            2. Break your answer into 2 to 4 factual sentences (`sentences` array). EVERY sentence MUST cite one `blockId` (e.g. "B1") and copy an EXACT character-for-character contiguous ASCII/Chinese substring `quoteAnchor` (4 to 7 consecutive words copied verbatim from that block without ellipses `...`, without brackets, without ligature edits, and preferring clean prose without subscript spacing noise).
            3. Cross-Material / Lecture Discrepancy Rule: Compare ALL blocks [B1]..[Bk] on the exact concept, formula, sign/direction, upper-bound applicability, empirical cause, authorship citation, or lecturer transcript self-correction that the student's question asks about.
               - If `Pre-indexed Discrepancy Candidates` are supplied below, verify whether any candidate directly applies to the asked question. Return matching ids in `matchedDiscrepancyIds`.
               - Set `hasMaterialConflict = true` ONLY when two teaching sources (or two timestamps in a lecturer transcript, or a lecture formula vs a tutorial key hitting a boundary cap, or a slide hypothesis vs a required paper's empirical refutation, or conflicting author names/notation across slides and textbook, or a directly matched candidate discrepancy) genuinely diverge or self-correct ON THE ASKED QUESTION (NEVER flag end-of-chapter True/False quiz statements, and NEVER flag unasked background notation when sources agree on the asked question!).
               - When `hasMaterialConflict = true`, summarize both sides and their citations in `conflictSummaryZh`; otherwise set `hasMaterialConflict = false`, `conflictSummaryZh = ""`, and `matchedDiscrepancyIds = []`.
            Output ONE JSON object only:
            {"sentences": [{"claim": "...。", "blockId": "B1", "quoteAnchor": "exact words from B1"}], "hasMaterialConflict": false, "conflictSummaryZh": "", "matchedDiscrepancyIds": []}
        """.trimIndent()
    }

    suspend fun ask(
        input: JsonObject,
        friend: String? = null,
        device: String? = null,
    ): JsonObject {
        val collectionId = input.str("collectionId").trim().also {
            require(it.length in 1..120) { "合集编号无效。" }
        }
        val collectionKind = input.str("collectionKind").trim().uppercase().ifBlank { "COURSE" }
        require(collectionKind in setOf("COURSE", "BOOKLIST", "SINGLE_BOOK", "CUSTOM")) { "合集类型无效。" }
        val collectionTitle = input.str("collectionTitle").trim().take(200)
        val question = input.str("question").trim().also {
            require(it.length in 1..600) { "问题长度需要在 1 到 600 字之间。" }
        }
        if (LEAKAGE_REGEX.containsMatchIn(question)) {
            throw MockError(HttpStatusCode.BadRequest, "问题内容包含受限词，请改用课程概念提问。")
        }

        val rawBlocks = input["blocks"] as? JsonArray ?: throw IllegalArgumentException("缺少检索原文块。")
        require(rawBlocks.size in 1..MAX_BLOCKS) { "检索原文块数量超出上限。" }
        val seenIds = HashSet<String>()
        val blocks = rawBlocks.map { el ->
            val obj = el as? JsonObject ?: throw IllegalArgumentException("原文块格式无效。")
            val blockId = obj.str("blockId").trim()
            require(BLOCK_ID_REGEX.matches(blockId) && seenIds.add(blockId)) { "原文块编号无效或重复。" }
            val bookId = obj.str("bookId").trim().also { require(it.length in 1..120) { "材料编号无效。" } }
            val docLabel = obj.str("docLabel").trim().also { require(it.length in 1..160) { "材料标题无效。" } }
            val authorityRank = ((obj["authorityRank"] as? JsonPrimitive)?.intOrNull ?: 2).coerceIn(1, 4)
            val page = ((obj["page"] as? JsonPrimitive)?.intOrNull ?: 1).coerceIn(1, 100_000)
            val tsLabel = obj.str("timestampLabel").trim().takeIf { it.isNotEmpty() }?.take(16)
            val text = obj.str("text").take(MAX_BLOCK_CHARS)
            require(text.isNotBlank()) { "原文块正文不能为空。" }
            if (LEAKAGE_REGEX.containsMatchIn(text)) {
                throw MockError(HttpStatusCode.Conflict, "检测到不合规范的外部考题文本，已拒绝发送。")
            }
            CollectionQaBlockInput(
                blockId = blockId,
                bookId = bookId,
                docLabel = docLabel,
                authorityRank = authorityRank,
                page = page,
                timestampLabel = tsLabel,
                text = text,
            )
        }
        val blockById = blocks.associateBy { it.blockId }
        val blockByBookPage = blocks.associateBy { it.bookId to it.page }

        val rawDiscrepancies = (input["discrepancies"] as? JsonArray).orEmpty().take(MAX_DISCREPANCIES)
        val discrepancies = rawDiscrepancies.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id").trim().ifBlank { "disc-${UUID.randomUUID().toString().take(8)}" }
            val point = obj.str("point").trim().take(200)
            val authView = obj.str("authoritativeView").trim().take(300)
            val secView = obj.str("secondaryView").trim().take(300)
            val advice = obj.str("advice").trim().take(300)
            val directMatch = (obj["directAspectMatch"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (point.isBlank() && authView.isBlank() && secView.isBlank()) return@mapNotNull null
            val cites = (obj["citations"] as? JsonArray).orEmpty().mapNotNull { cEl ->
                val cObj = cEl as? JsonObject ?: return@mapNotNull null
                val cBookId = cObj.str("bookId").trim()
                val cLabel = cObj.str("docLabel").trim()
                val cPage = (cObj["page"] as? JsonPrimitive)?.intOrNull ?: 1
                val cTs = cObj.str("timestampLabel").trim().takeIf { it.isNotEmpty() }?.take(16)
                val cQuote = cObj.str("quoteAnchor").trim()
                if (cBookId.isBlank() || cQuote.isBlank()) null
                else CollectionQaDiscrepancyCitationInput(cBookId, cLabel, cPage, cTs, cQuote)
            }
            CollectionQaDiscrepancyInput(id, point, authView, secView, advice, directMatch, cites)
        }

        val userPrompt = buildUserPrompt(collectionTitle, collectionKind, question, blocks, discrepancies)
        val reply = transport.ask(
            systemPrompt = COLLECTION_QA_SYSTEM,
            userPrompt = userPrompt,
            friend = friend,
            device = device,
            maxTokens = 6144,
        )

        val parsed = extractJsonObject(reply.text)?.let { rawJson ->
            runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
        }

        val rawSentences = (parsed?.get("sentences") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val verifiedSentences = mutableListOf<JsonObject>()
        var droppedUnverified = 0

        for (item in rawSentences) {
            val bid = item.str("blockId").trim()
            val claim = item.str("claim").trim()
            val quote = item.str("quoteAnchor").trim()
            val blk = blockById[bid]
            if (blk == null || claim.isBlank()) {
                droppedUnverified++
                continue
            }
            val gate = ServerSentenceCitationGate.verifyOrSnapAnchor(quote, blk.text)
            if (!gate.verified) {
                droppedUnverified++
                continue
            }
            val resolvedTs = ServerSentenceCitationGate.extractNearestTimestamp(blk.text, gate.canonicalAnchor)
                ?: blk.timestampLabel
            verifiedSentences += buildJsonObject {
                put("claim", claim)
                put("blockId", blk.blockId)
                put("bookId", blk.bookId)
                put("docLabel", blk.docLabel)
                put("page", blk.page)
                resolvedTs?.let { put("timestampLabel", it) }
                put("quoteAnchor", gate.canonicalAnchor)
                put("rawExactMatch", gate.rawExactMatch)
                put("verifiedOnSourcePage", true)
            }
        }

        val modelHasConflict = (parsed?.get("hasMaterialConflict") as? JsonPrimitive)?.booleanOrNull ?: false
        val conflictSummary = parsed?.str("conflictSummaryZh")?.trim().orEmpty()
        val modelMatchedIds = (parsed?.get("matchedDiscrepancyIds") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .toSet()

        val mergedConflicts = mutableListOf<JsonObject>()
        val selectedDiscrepancies = discrepancies.filter { d ->
            d.directAspectMatch || d.id in modelMatchedIds
        }
        for (d in selectedDiscrepancies) {
            val verifiedCites = buildJsonArray {
                for (c in d.citations) {
                    val blk = blockByBookPage[c.bookId to c.page] ?: continue
                    val gate = ServerSentenceCitationGate.verifyOrSnapAnchor(c.quoteAnchor, blk.text)
                    if (!gate.verified) continue
                    val ts = c.timestampLabel ?: ServerSentenceCitationGate.extractNearestTimestamp(blk.text, gate.canonicalAnchor)
                    add(buildJsonObject {
                        put("bookId", c.bookId)
                        put("docLabel", c.docLabel.ifBlank { blk.docLabel })
                        put("page", c.page)
                        ts?.let { put("timestampLabel", it) }
                        put("quoteAnchor", gate.canonicalAnchor)
                    })
                }
            }
            mergedConflicts += buildJsonObject {
                put("id", d.id)
                put("point", d.point)
                put("authoritativeView", d.authoritativeView)
                put("secondaryView", d.secondaryView)
                put("advice", d.advice)
                put("citations", verifiedCites)
            }
        }

        val effectiveConflictSummary = when {
            conflictSummary.length > 5 -> conflictSummary
            mergedConflicts.isNotEmpty() -> selectedDiscrepancies.first().let { first ->
                buildString {
                    append(first.point)
                    if (first.authoritativeView.isNotBlank()) append("：").append(first.authoritativeView)
                    if (first.secondaryView.isNotBlank()) append("；另一材料：").append(first.secondaryView)
                }
            }
            else -> ""
        }
        val finalHasConflict = mergedConflicts.isNotEmpty() || (modelHasConflict && effectiveConflictSummary.length > 5)

        ledger?.let { bookLedger ->
            runCatching {
                bookLedger.record(
                    TokenLedgerEntry(
                        bookId = "collection:${collectionId.take(64)}",
                        chapterId = collectionKind,
                        sectionId = "ask",
                        task = "COLLECTION_QA",
                        routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                        model = reply.servedBy,
                        inputTokens = reply.inputTokens,
                        outputTokens = reply.outputTokens,
                        ttftMs = reply.ttftMs,
                        elapsedMs = reply.elapsedMs,
                        status = reply.status,
                    ),
                )
            }
        }

        return buildJsonObject {
            put("sentences", JsonArray(verifiedSentences))
            put("hasMaterialConflict", finalHasConflict)
            put("conflictSummaryZh", if (finalHasConflict) effectiveConflictSummary else "")
            put("conflicts", JsonArray(mergedConflicts))
            put("droppedUnverifiedCount", droppedUnverified)
            put("stats", buildJsonObject {
                put("model", reply.servedBy)
                put("ttftMs", reply.ttftMs)
                put("elapsedMs", reply.elapsedMs)
                put("rawSentenceCount", rawSentences.size)
                put("verifiedSentenceCount", verifiedSentences.size)
            })
        }
    }

    private fun buildUserPrompt(
        collectionTitle: String,
        collectionKind: String,
        question: String,
        blocks: List<CollectionQaBlockInput>,
        discrepancies: List<CollectionQaDiscrepancyInput>,
    ): String {
        val discrepancySection = buildString {
            if (discrepancies.isNotEmpty()) {
                append("Pre-indexed Discrepancy Candidates (confirm ONLY if directly relevant to the student's asked question):\n")
                for (d in discrepancies) {
                    append("- id=").append(d.id).append(" | point=").append(d.point)
                    if (d.authoritativeView.isNotBlank()) append(" | authoritative=").append(d.authoritativeView)
                    if (d.secondaryView.isNotBlank()) append(" | secondary=").append(d.secondaryView)
                    if (d.advice.isNotBlank()) append(" | advice=").append(d.advice)
                    append('\n')
                }
            }
        }
        val systemBytes = COLLECTION_QA_SYSTEM.toByteArray(Charsets.UTF_8).size
        val discrepancyBytes = discrepancySection.toByteArray(Charsets.UTF_8).size
        val sb = StringBuilder()
        if (collectionTitle.isNotBlank()) {
            sb.append("Collection (").append(collectionKind).append("): ").append(collectionTitle).append('\n')
        }
        sb.append("Student Question: ").append(question).append("\n\n")
        sb.append("Retrieved Evidence Blocks:\n")

        var usedBytes = systemBytes + sb.toString().toByteArray(Charsets.UTF_8).size + discrepancyBytes
        var includedBlocks = 0
        for (b in blocks) {
            val header = buildString {
                append('[').append(b.blockId).append("] (").append(b.docLabel)
                append(" · L").append(b.authorityRank).append(" · Page/Seg ").append(b.page)
                if (!b.timestampLabel.isNullOrBlank()) append(" · ").append(b.timestampLabel)
                append("):\n")
            }
            val entry = "$header${b.text}\n\n"
            val entryBytes = entry.toByteArray(Charsets.UTF_8).size
            if (usedBytes + entryBytes > MAX_PROMPT_UTF8_BYTES) {
                if (includedBlocks > 0) break
                val overheadBytes = "$header\n\n".toByteArray(Charsets.UTF_8).size
                val remainingTextBytes = (MAX_PROMPT_UTF8_BYTES - usedBytes - overheadBytes).coerceAtLeast(256)
                val trimmedBody = truncateUtf8ToBytes(b.text, remainingTextBytes)
                val finalEntry = "$header$trimmedBody\n\n"
                sb.append(finalEntry)
                usedBytes += finalEntry.toByteArray(Charsets.UTF_8).size
                includedBlocks++
                break
            }
            sb.append(entry)
            usedBytes += entryBytes
            includedBlocks++
        }
        sb.append(discrepancySection)
        return sb.toString()
    }

    private fun truncateUtf8ToBytes(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var bytes = 0
        var end = 0
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val charCount = Character.charCount(cp)
            val cpBytes = text.substring(i, i + charCount).toByteArray(Charsets.UTF_8).size
            if (bytes + cpBytes > maxBytes) break
            bytes += cpBytes
            i += charCount
            end = i
        }
        return text.substring(0, end)
    }

    private fun extractJsonObject(raw: String): String? {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return trimmed.substring(start, end + 1)
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

fun Application.collectionQaRoutes(
    actor: LedgerActor,
    friends: FriendAccess? = null,
    service: CollectionQaService? = null,
) {
    routing {
        post("/v1/library/collection/ask") {
            call.withReaderConnection {
                val activeService = service ?: return@withReaderConnection call.respondText(
                    buildJsonObject { put("error", "跨材料问答服务暂未开启，请稍后重试。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                val friend = call.attributes.getOrNull(observedFriend)
                val device = call.attributes[observedDevice]
                val bearer = call.request.header("Authorization")?.removePrefix("Bearer ").orEmpty()
                try {
                    if (friend != null && friends != null) {
                        val allowed = actor.execute { authorize(device, bearer) && friendName(device) == friend }
                        if (!allowed) throw MockError(HttpStatusCode.Conflict, "设备授权已失效，无法发起跨材料问答。")
                        val quota = withContext(Dispatchers.IO) { friends.status(friend, device) }
                        val callsRem = quota["callsRemaining"]?.jsonPrimitive?.longOrNull
                        val tokensRem = quota["tokensRemaining"]?.jsonPrimitive?.longOrNull
                        if ((callsRem != null && callsRem <= 0L) || (tokensRem != null && tokensRem <= 0L)) {
                            throw FriendServiceError("daily_quota")
                        }
                    }
                    val channel = call.receiveChannel()
                    val maxBytes = 512 * 1024
                    val bytes = ByteArray(maxBytes + 1)
                    var size = 0
                    while (size < bytes.size) {
                        val n = channel.readAvailable(bytes, size, bytes.size - size)
                        if (n < 0) break
                        size += n
                    }
                    if (size > maxBytes) {
                        call.response.header(HttpHeaders.Connection, "close")
                        throw MockError(HttpStatusCode.PayloadTooLarge, "跨材料问答请求体积超出上限，请精简后重试。")
                    }
                    val input = LedgerJson.parseToJsonElement(bytes.decodeToString(0, size)).jsonObject
                    val reply = activeService.ask(input, friend = friend, device = device)
                    if (friend != null && friends != null) {
                        val requestId = (input["requestId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        withContext(Dispatchers.IO) {
                            runCatching { friends.record(friend, device, requestId, "complete", reply) }
                        }
                    }
                    call.respondText(reply.toString(), ContentType.Application.Json)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FriendServiceError) {
                    call.respondText(e.body.toString(), ContentType.Application.Json, e.status)
                } catch (e: MockError) {
                    call.respondText(
                        buildJsonObject { put("error", e.message ?: "跨材料问答请求无效。") }.toString(),
                        ContentType.Application.Json,
                        e.status,
                    )
                } catch (e: IllegalArgumentException) {
                    call.respondText(
                        buildJsonObject { put("error", e.message ?: "跨材料问答参数不完整。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                } catch (_: Exception) {
                    call.respondText(
                        buildJsonObject { put("error", "跨材料问答暂时不可用，请稍后重试。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadGateway,
                    )
                }
            }
        }
    }
}
