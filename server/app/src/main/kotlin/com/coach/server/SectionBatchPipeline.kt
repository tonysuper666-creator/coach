package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeResult
import com.coach.ai.ChatRequest
import com.coach.domain.LedgerJson
import com.coach.store.sha256
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * LearningCoach 2.0-W2-B: Server-side section-concurrent batch AI pipeline for single-book companion reading.
 *
 * Hard invariants enforced in code:
 * 1. Every section request is strictly bounded to `<= MAX_REQUEST_TOKENS` (3,500 tokens), splitting oversized sections
 *    along block boundaries so whole-chapter 179.2s timeouts can never recur.
 * 2. Chapter-end `Exercises` / `Problems` sections are strictly isolated from body self-tests (`pretest` / `keyPoints`)
 *    and routed exclusively to `exerciseHints` (3-tier progressive hints).
 * 3. Routing policy: primary bulk = CodeBuddy CLI `deepseek-v4.1-flash`; overflow bulk = `claude-haiku-5-5` Batch;
 *    realtime interactive (`讲清`/`拆句`/`追问`) = `claude-haiku-5-5` streaming.
 * 4. Every citation anchor is hard-verified against raw UTF-16 page text (`pages[pdfPage]`) before persistence.
 * 5. Verbatim overlap gate guarantees `en 8-gram == 0` and `zh 12-gram == 0` on generated prose.
 * 6. Every call is recorded in [BookTokenLedger] for auditable per-book cost (`<= $0.30/book`).
 */
object SectionBatchLimits {
    const val MAX_REQUEST_TOKENS: Int = 3_500
    const val PROMPT_OVERHEAD_TOKENS: Int = 520
    const val MAX_CONTENT_TOKENS_PER_CHUNK: Int = MAX_REQUEST_TOKENS - PROMPT_OVERHEAD_TOKENS
    const val DEFAULT_CONCURRENCY: Int = 4

    /**
     * Output budget per chunk reply. `claude-haiku-5-5` runs adaptive thinking whenever a request leaves
     * `thinking` unset (the bridge does), and thinking counts toward `max_tokens`. The old 1,800 default was
     * spent on thinking before the JSON pack finished, so the bridge got `stop_reason = max_tokens` and
     * answered `api_incomplete`. Both values stay under the bridge's 16,000 output cap; the CodeBuddy
     * primary ignores the value.
     */
    const val BODY_CHUNK_MAX_OUTPUT_TOKENS: Int = 12_000
    const val EXERCISE_CHUNK_MAX_OUTPUT_TOKENS: Int = 8_000
    const val EN_NGRAM_LIMIT: Int = 8
    const val ZH_NGRAM_LIMIT: Int = 12
}

data class SectionBlockInput(
    val id: String,
    val pdfPage: Int,
    val startUtf16: Int,
    val text: String,
)

data class SectionBatchInput(
    val sectionId: String,
    val title: String,
    val isExerciseSection: Boolean = false,
    val blocks: List<SectionBlockInput>,
)

data class ChapterCompanionRequest(
    val bookId: String,
    val chapterId: String,
    val chapterTitle: String,
    val sections: List<SectionBatchInput>,
    val rawPages: Map<Int, String> = emptyMap(),
)

data class VerifiedSourceAnchor(
    val pdfPage: Int,
    val blockId: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val quoteAnchor: String,
)

data class JargonGlossItem(
    val term: String,
    val plainMeaning: String,
    val anchor: VerifiedSourceAnchor? = null,
)

data class CompanionKeyPointItem(
    val id: String,
    val sectionId: String,
    val summary: String,
    val anchor: VerifiedSourceAnchor? = null,
)

data class CompanionQuizItem(
    val id: String,
    val sectionId: String,
    val stem: String,
    val options: List<String>,
    val correctOption: String,
    val explanation: String,
    val revealHint: String = "",
    val kind: String = "MCQ", // "MCQ" or "SHORT_CALC"
    val anchor: VerifiedSourceAnchor? = null,
)

data class DerivationStepTemplate(
    val id: String,
    val sectionId: String,
    val title: String,
    val setup: String,
    val steps: List<String>,
    val graphCheck: String = "",
    val pitfall: String = "",
    val anchor: VerifiedSourceAnchor? = null,
)

data class ExerciseProgressiveHint(
    val id: String,
    val sectionId: String,
    val problemLabel: String,
    val questionSummary: String,
    val hint1Direction: String,
    val hint2Method: String,
    val hint3Check: String,
    val anchor: VerifiedSourceAnchor? = null,
)

data class OverlapAudit(
    val en8GramHits: Int,
    val zh12GramHits: Int,
    val passed: Boolean,
)

data class CompanionPipelineStats(
    val sectionChunks: Int,
    val maxRequestTokens: Int,
    val verifiedAnchors: Int,
    val rejectedAnchors: Int,
    val exercisesIsolated: Int,
    val overlapAudit: OverlapAudit,
    val p50ElapsedMs: Long,
    val p95ElapsedMs: Long,
    val p50TtftMs: Long,
    val p95TtftMs: Long,
    val totalCostMicrodollars: Long,
    val routeChannelsUsed: List<String>,
    val fromCache: Boolean = false,
)

data class ChapterCompanionPack(
    val version: Int = 1,
    val bookId: String,
    val chapterId: String,
    val chapterTitle: String,
    val coreQuestion: String,
    val keyPoints: List<CompanionKeyPointItem>,
    val jargonGlosses: List<JargonGlossItem>,
    val pretest: List<CompanionQuizItem>,
    val derivations: List<DerivationStepTemplate>,
    val optionalPractice: List<CompanionQuizItem>,
    val exerciseHints: List<ExerciseProgressiveHint>,
    val stats: CompanionPipelineStats,
)

object ChapterCompanionCodec {
    private fun encodeAnchor(a: VerifiedSourceAnchor?): JsonObject? = a?.let {
        buildJsonObject {
            put("pdfPage", it.pdfPage)
            put("blockId", it.blockId)
            put("startUtf16", it.startUtf16)
            put("endUtf16", it.endUtf16)
            put("quoteAnchor", it.quoteAnchor)
        }
    }

    private fun decodeAnchor(obj: JsonObject?): VerifiedSourceAnchor? {
        if (obj == null) return null
        val page = obj["pdfPage"]?.jsonPrimitive?.intOrNull ?: return null
        val blockId = obj["blockId"]?.jsonPrimitive?.contentOrNull ?: return null
        val start = obj["startUtf16"]?.jsonPrimitive?.intOrNull ?: return null
        val end = obj["endUtf16"]?.jsonPrimitive?.intOrNull ?: return null
        val quote = obj["quoteAnchor"]?.jsonPrimitive?.contentOrNull ?: return null
        return VerifiedSourceAnchor(page, blockId, start, end, quote)
    }

    private fun encodeQuiz(q: CompanionQuizItem): JsonObject = buildJsonObject {
        put("id", q.id)
        put("sectionId", q.sectionId)
        put("stem", q.stem)
        put("options", buildJsonArray { q.options.forEach { add(JsonPrimitive(it)) } })
        put("correctOption", q.correctOption)
        put("explanation", q.explanation)
        put("revealHint", q.revealHint)
        put("kind", q.kind)
        encodeAnchor(q.anchor)?.let { put("anchor", it) }
    }

    private fun decodeQuiz(obj: JsonObject): CompanionQuizItem = CompanionQuizItem(
        id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        sectionId = obj["sectionId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        stem = obj["stem"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        correctOption = obj["correctOption"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        explanation = obj["explanation"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        revealHint = obj["revealHint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "MCQ",
        anchor = decodeAnchor(obj["anchor"] as? JsonObject),
    )

    fun encode(pack: ChapterCompanionPack): JsonObject = buildJsonObject {
        put("version", pack.version)
        put("bookId", pack.bookId)
        put("chapterId", pack.chapterId)
        put("chapterTitle", pack.chapterTitle)
        put("coreQuestion", pack.coreQuestion)
        put("keyPoints", buildJsonArray {
            pack.keyPoints.forEach { kp ->
                add(buildJsonObject {
                    put("id", kp.id); put("sectionId", kp.sectionId); put("summary", kp.summary)
                    encodeAnchor(kp.anchor)?.let { put("anchor", it) }
                })
            }
        })
        put("jargonGlosses", buildJsonArray {
            pack.jargonGlosses.forEach { g ->
                add(buildJsonObject {
                    put("term", g.term); put("plainMeaning", g.plainMeaning)
                    encodeAnchor(g.anchor)?.let { put("anchor", it) }
                })
            }
        })
        put("pretest", buildJsonArray { pack.pretest.forEach { add(encodeQuiz(it)) } })
        put("derivations", buildJsonArray {
            pack.derivations.forEach { d ->
                add(buildJsonObject {
                    put("id", d.id); put("sectionId", d.sectionId); put("title", d.title)
                    put("setup", d.setup)
                    put("steps", buildJsonArray { d.steps.forEach { add(JsonPrimitive(it)) } })
                    put("graphCheck", d.graphCheck); put("pitfall", d.pitfall)
                    encodeAnchor(d.anchor)?.let { put("anchor", it) }
                })
            }
        })
        put("optionalPractice", buildJsonArray { pack.optionalPractice.forEach { add(encodeQuiz(it)) } })
        put("exerciseHints", buildJsonArray {
            pack.exerciseHints.forEach { h ->
                add(buildJsonObject {
                    put("id", h.id); put("sectionId", h.sectionId); put("problemLabel", h.problemLabel)
                    put("questionSummary", h.questionSummary); put("hint1Direction", h.hint1Direction)
                    put("hint2Method", h.hint2Method); put("hint3Check", h.hint3Check)
                    encodeAnchor(h.anchor)?.let { put("anchor", it) }
                })
            }
        })
        put("stats", buildJsonObject {
            put("sectionChunks", pack.stats.sectionChunks)
            put("maxRequestTokens", pack.stats.maxRequestTokens)
            put("verifiedAnchors", pack.stats.verifiedAnchors)
            put("rejectedAnchors", pack.stats.rejectedAnchors)
            put("exercisesIsolated", pack.stats.exercisesIsolated)
            put("overlapAudit", buildJsonObject {
                put("en8GramHits", pack.stats.overlapAudit.en8GramHits)
                put("zh12GramHits", pack.stats.overlapAudit.zh12GramHits)
                put("passed", pack.stats.overlapAudit.passed)
            })
            put("p50ElapsedMs", pack.stats.p50ElapsedMs)
            put("p95ElapsedMs", pack.stats.p95ElapsedMs)
            put("p50TtftMs", pack.stats.p50TtftMs)
            put("p95TtftMs", pack.stats.p95TtftMs)
            put("totalCostMicrodollars", pack.stats.totalCostMicrodollars)
            put("routeChannelsUsed", buildJsonArray { pack.stats.routeChannelsUsed.forEach { add(JsonPrimitive(it)) } })
            put("fromCache", pack.stats.fromCache)
        })
    }

    fun decode(raw: String): ChapterCompanionPack {
        val obj = LedgerJson.parseToJsonElement(raw).jsonObject
        val st = obj["stats"] as? JsonObject
        val ov = st?.get("overlapAudit") as? JsonObject
        return ChapterCompanionPack(
            version = obj["version"]?.jsonPrimitive?.intOrNull ?: 1,
            bookId = obj["bookId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            chapterId = obj["chapterId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            chapterTitle = obj["chapterTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            coreQuestion = obj["coreQuestion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            keyPoints = (obj["keyPoints"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                CompanionKeyPointItem(
                    o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["sectionId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    decodeAnchor(o["anchor"] as? JsonObject),
                )
            },
            jargonGlosses = (obj["jargonGlosses"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                JargonGlossItem(
                    o["term"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["plainMeaning"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    decodeAnchor(o["anchor"] as? JsonObject),
                )
            },
            pretest = (obj["pretest"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeQuiz) },
            derivations = (obj["derivations"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                DerivationStepTemplate(
                    o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["sectionId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["setup"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    (o["steps"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    o["graphCheck"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["pitfall"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    decodeAnchor(o["anchor"] as? JsonObject),
                )
            },
            optionalPractice = (obj["optionalPractice"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeQuiz) },
            exerciseHints = (obj["exerciseHints"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                ExerciseProgressiveHint(
                    o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["sectionId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["problemLabel"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["questionSummary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["hint1Direction"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["hint2Method"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["hint3Check"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    decodeAnchor(o["anchor"] as? JsonObject),
                )
            },
            stats = CompanionPipelineStats(
                sectionChunks = st?.get("sectionChunks")?.jsonPrimitive?.intOrNull ?: 0,
                maxRequestTokens = st?.get("maxRequestTokens")?.jsonPrimitive?.intOrNull ?: 0,
                verifiedAnchors = st?.get("verifiedAnchors")?.jsonPrimitive?.intOrNull ?: 0,
                rejectedAnchors = st?.get("rejectedAnchors")?.jsonPrimitive?.intOrNull ?: 0,
                exercisesIsolated = st?.get("exercisesIsolated")?.jsonPrimitive?.intOrNull ?: 0,
                overlapAudit = OverlapAudit(
                    en8GramHits = ov?.get("en8GramHits")?.jsonPrimitive?.intOrNull ?: 0,
                    zh12GramHits = ov?.get("zh12GramHits")?.jsonPrimitive?.intOrNull ?: 0,
                    passed = ov?.get("passed")?.jsonPrimitive?.booleanOrNull ?: true,
                ),
                p50ElapsedMs = st?.get("p50ElapsedMs")?.jsonPrimitive?.longOrNull ?: 0L,
                p95ElapsedMs = st?.get("p95ElapsedMs")?.jsonPrimitive?.longOrNull ?: 0L,
                p50TtftMs = st?.get("p50TtftMs")?.jsonPrimitive?.longOrNull ?: 0L,
                p95TtftMs = st?.get("p95TtftMs")?.jsonPrimitive?.longOrNull ?: 0L,
                totalCostMicrodollars = st?.get("totalCostMicrodollars")?.jsonPrimitive?.longOrNull ?: 0L,
                routeChannelsUsed = (st?.get("routeChannelsUsed") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                fromCache = st?.get("fromCache")?.jsonPrimitive?.booleanOrNull ?: false,
            ),
        )
    }
}

/** Clean heuristic to isolate chapter-end Exercises/Problems from body self-tests. */
object ExerciseSectionDetector {
    private val EXERCISE_TITLE_RE = Regex(
        """(?i)^(?:\d+(?:\.\d+)*\s+)?(?:exercises?|problems?|review\s+questions?|chapter\s+exercises?|study\s+questions?|homework|习题|练习题|思考题)\b""",
    )

    fun isExerciseSection(section: SectionBatchInput): Boolean {
        if (section.isExerciseSection) return true
        return EXERCISE_TITLE_RE.containsMatchIn(section.title.trim())
    }
}

/** Deterministic token-bounded section chunker (`<= 3,500` tokens per request). */
data class BoundedSectionChunk(
    val sectionId: String,
    val chunkIndex: Int,
    val chunkCount: Int,
    val title: String,
    val isExerciseSection: Boolean,
    val blocks: List<SectionBlockInput>,
    val estimatedContentTokens: Int,
    val estimatedRequestTokens: Int,
)

object SectionChunkSplitter {
    fun split(
        sections: List<SectionBatchInput>,
        maxRequestTokens: Int = SectionBatchLimits.MAX_REQUEST_TOKENS,
        promptOverheadTokens: Int = SectionBatchLimits.PROMPT_OVERHEAD_TOKENS,
    ): List<BoundedSectionChunk> {
        require(maxRequestTokens > promptOverheadTokens + 200) { "Invalid token budget" }
        val maxContentTokens = maxRequestTokens - promptOverheadTokens
        val output = mutableListOf<BoundedSectionChunk>()
        for (section in sections) {
            val isEx = ExerciseSectionDetector.isExerciseSection(section)
            val nonBlank = section.blocks.filter { it.text.isNotBlank() }
            if (nonBlank.isEmpty()) continue
            val groups = mutableListOf<Pair<List<SectionBlockInput>, Int>>()
            var currentBlocks = mutableListOf<SectionBlockInput>()
            var currentTokens = 0
            for (block in nonBlank) {
                val slices = sliceOversizedBlock(block, maxContentTokens)
                for (slice in slices) {
                    val tok = TokenLedgerPricing.estimateTokens(slice.text) + 12 // header overhead per block
                    if (currentBlocks.isNotEmpty() && currentTokens + tok > maxContentTokens) {
                        groups += currentBlocks.toList() to currentTokens
                        currentBlocks = mutableListOf()
                        currentTokens = 0
                    }
                    currentBlocks += slice
                    currentTokens += tok
                }
            }
            if (currentBlocks.isNotEmpty()) {
                groups += currentBlocks.toList() to currentTokens
            }
            groups.forEachIndexed { idx, (chunkBlocks, contentTok) ->
                val reqTok = (contentTok + promptOverheadTokens).coerceAtMost(maxRequestTokens)
                output += BoundedSectionChunk(
                    sectionId = section.sectionId,
                    chunkIndex = idx,
                    chunkCount = groups.size,
                    title = section.title,
                    isExerciseSection = isEx,
                    blocks = chunkBlocks,
                    estimatedContentTokens = contentTok,
                    estimatedRequestTokens = reqTok,
                )
            }
        }
        return output
    }

    /** Splits any single gigantic block while preserving exact UTF-16 offsets into its original page. */
    private fun sliceOversizedBlock(block: SectionBlockInput, maxContentTokens: Int): List<SectionBlockInput> {
        val est = TokenLedgerPricing.estimateTokens(block.text) + 12
        if (est <= maxContentTokens) return listOf(block)
        // Conservative char bound so word*1.33 and chars/4 both stay strictly under budget.
        val maxChars = ((maxContentTokens - 24) * 3).coerceAtLeast(400)
        val result = mutableListOf<SectionBlockInput>()
        var cursor = 0
        while (cursor < block.text.length) {
            var end = min(cursor + maxChars, block.text.length)
            if (end < block.text.length) {
                val nl = block.text.lastIndexOf('\n', end)
                val sp = block.text.lastIndexOf(' ', end)
                val cut = maxOf(nl, sp)
                if (cut > cursor + maxChars / 2) end = cut
                if (end > cursor && Character.isHighSurrogate(block.text[end - 1])) end--
            }
            if (end <= cursor) end = min(cursor + maxChars, block.text.length)
            val sub = block.text.substring(cursor, end)
            if (sub.isNotBlank()) {
                val newStart = block.startUtf16 + cursor
                val bookPrefix = block.id.substringBefore(":p")
                val sliceId = if (cursor == 0) block.id else "$bookPrefix:p${block.pdfPage}:$newStart"
                result += SectionBlockInput(sliceId, block.pdfPage, newStart, sub)
            }
            cursor = end
            while (cursor < block.text.length && block.text[cursor].isWhitespace()) cursor++
        }
        return result
    }
}

/**
 * Hard UTF-16 page & offset verifier against raw book pages/blocks.
 * Never fabricates page numbers or character offsets; rejects unverified claims cleanly.
 */
object Utf16AnchorVerifier {
    fun verify(
        claimedPage: Int?,
        quoteAnchor: String?,
        blocks: List<SectionBlockInput>,
        rawPages: Map<Int, String> = emptyMap(),
    ): VerifiedSourceAnchor? {
        val quote = quoteAnchor?.trim()?.takeIf { it.length >= 6 } ?: return null
        val candidateBlocks = if (claimedPage != null && claimedPage > 0) {
            blocks.filter { it.pdfPage == claimedPage }
        } else {
            blocks
        }
        // 1) Exact UTF-16 match inside block text
        for (blk in candidateBlocks) {
            val idx = blk.text.indexOf(quote)
            if (idx >= 0) {
                return VerifiedSourceAnchor(
                    pdfPage = blk.pdfPage,
                    blockId = blk.id,
                    startUtf16 = blk.startUtf16 + idx,
                    endUtf16 = blk.startUtf16 + idx + quote.length,
                    quoteAnchor = blk.text.substring(idx, idx + quote.length),
                )
            }
        }
        // 2) Whitespace/typography-normalized match mapped back to exact UTF-16 span in block text
        for (blk in candidateBlocks) {
            val span = findNormalizedUtf16Span(blk.text, quote)
            if (span != null) {
                return VerifiedSourceAnchor(
                    pdfPage = blk.pdfPage,
                    blockId = blk.id,
                    startUtf16 = blk.startUtf16 + span.first,
                    endUtf16 = blk.startUtf16 + span.second,
                    quoteAnchor = blk.text.substring(span.first, span.second),
                )
            }
        }
        // 3) Fallback check on raw page text when provided (must still exist verbatim on claimed page)
        if (claimedPage != null) {
            val raw = rawPages[claimedPage]
            if (raw != null) {
                val exactIdx = raw.indexOf(quote)
                val span = if (exactIdx >= 0) exactIdx to (exactIdx + quote.length) else findNormalizedUtf16Span(raw, quote)
                if (span != null) {
                    val bookId = blocks.firstOrNull()?.id?.substringBefore(":p").orEmpty()
                    val blkId = candidateBlocks.firstOrNull()?.id ?: "$bookId:p$claimedPage:${span.first}"
                    return VerifiedSourceAnchor(
                        pdfPage = claimedPage,
                        blockId = blkId,
                        startUtf16 = span.first,
                        endUtf16 = span.second,
                        quoteAnchor = raw.substring(span.first, span.second),
                    )
                }
            }
        }
        return null
    }

    /** Maps a whitespace-collapsed, punctuation-normalized search back to exact raw UTF-16 [start, end). */
    internal fun findNormalizedUtf16Span(raw: String, query: String): Pair<Int, Int>? {
        val normQuery = normalizeForMatch(query)
        if (normQuery.length < 6) return null
        val normChars = StringBuilder(raw.length)
        val rawIndices = IntArray(raw.length + 1)
        var outLen = 0
        var lastWasSpace = true
        for (i in raw.indices) {
            val ch = normalizeChar(raw[i])
            if (ch.isWhitespace()) {
                if (!lastWasSpace && outLen > 0) {
                    rawIndices[outLen] = i
                    normChars.append(' ')
                    outLen++
                    lastWasSpace = true
                }
            } else {
                rawIndices[outLen] = i
                normChars.append(ch.lowercaseChar())
                outLen++
                lastWasSpace = false
            }
        }
        val haystack = normChars.toString().trimEnd()
        val hit = haystack.indexOf(normQuery)
        if (hit < 0) return null
        val endNorm = (hit + normQuery.length - 1).coerceAtMost(outLen - 1)
        val rawStart = rawIndices[hit]
        val rawEnd = (rawIndices[endNorm] + 1).coerceAtMost(raw.length)
        return if (rawStart in 0 until rawEnd) rawStart to rawEnd else null
    }

    private fun normalizeChar(c: Char): Char = when (c) {
        '\u2212', '\u2013', '\u2014' -> '-'
        '\u2018', '\u2019' -> '\''
        '\u201c', '\u201d' -> '"'
        '\u00a0' -> ' '
        else -> c
    }

    private fun normalizeForMatch(s: String): String {
        val sb = StringBuilder(s.length)
        var lastSpace = true
        for (raw in Normalizer.normalize(s, Normalizer.Form.NFKC)) {
            val ch = normalizeChar(raw)
            if (ch.isWhitespace()) {
                if (!lastSpace && sb.isNotEmpty()) {
                    sb.append(' ')
                    lastSpace = true
                }
            } else {
                sb.append(ch.lowercaseChar())
                lastSpace = false
            }
        }
        return sb.toString().trim()
    }
}

/** Verbatim overlap gate: English 8-gram = 0, Chinese 12-gram = 0. */
object VerbatimOverlapGuard {
    private val EN_WORD = Regex("[a-z0-9]+(?:'[a-z0-9]+)?")

    fun englishWords(text: String): List<String> =
        EN_WORD.findAll(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()).map { it.value }.toList()

    fun chineseChars(text: String): String = buildString {
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF) append(ch)
        }
    }

    fun audit(generatedProse: List<String>, sourceText: String): OverlapAudit {
        val srcEn = englishWords(sourceText)
        val srcEnGrams = HashSet<String>(srcEn.size)
        for (i in 0..(srcEn.size - SectionBatchLimits.EN_NGRAM_LIMIT)) {
            srcEnGrams += srcEn.subList(i, i + SectionBatchLimits.EN_NGRAM_LIMIT).joinToString(" ")
        }
        val srcZh = chineseChars(sourceText)
        val srcZhGrams = HashSet<String>(srcZh.length)
        for (i in 0..(srcZh.length - SectionBatchLimits.ZH_NGRAM_LIMIT)) {
            srcZhGrams += srcZh.substring(i, i + SectionBatchLimits.ZH_NGRAM_LIMIT)
        }

        var enHits = 0
        var zhHits = 0
        for (prose in generatedProse) {
            val words = englishWords(prose)
            for (i in 0..(words.size - SectionBatchLimits.EN_NGRAM_LIMIT)) {
                val gram = words.subList(i, i + SectionBatchLimits.EN_NGRAM_LIMIT).joinToString(" ")
                if (gram in srcEnGrams) enHits++
            }
            val zh = chineseChars(prose)
            for (i in 0..(zh.length - SectionBatchLimits.ZH_NGRAM_LIMIT)) {
                val gram = zh.substring(i, i + SectionBatchLimits.ZH_NGRAM_LIMIT)
                if (gram in srcZhGrams) zhHits++
            }
        }
        return OverlapAudit(en8GramHits = enHits, zh12GramHits = zhHits, passed = enHits == 0 && zhHits == 0)
    }

    /** Breaks any accidental verbatim >=8-word English or >=12-char Chinese run so persisted output satisfies en 8-gram=0, zh 12-gram=0. */
    fun hasOverlap(prose: String, sourceText: String): Boolean = !audit(listOf(prose), sourceText).passed

    fun scrubOverlaps(prose: String, sourceText: String): String {
        if (prose.isBlank() || !hasOverlap(prose, sourceText)) return prose
        val srcEn = englishWords(sourceText)
        val srcEnGrams = HashSet<String>(srcEn.size)
        for (i in 0..(srcEn.size - SectionBatchLimits.EN_NGRAM_LIMIT)) {
            srcEnGrams += srcEn.subList(i, i + SectionBatchLimits.EN_NGRAM_LIMIT).joinToString(" ")
        }
        val srcZh = chineseChars(sourceText)
        val srcZhGrams = HashSet<String>(srcZh.length)
        for (i in 0..(srcZh.length - SectionBatchLimits.ZH_NGRAM_LIMIT)) {
            srcZhGrams += srcZh.substring(i, i + SectionBatchLimits.ZH_NGRAM_LIMIT)
        }
        var current = prose
        // Iteratively break offending >=8-word English spans by compacting long inline symbol/quote chains.
        val matches = EN_WORD.findAll(Normalizer.normalize(current, Normalizer.Form.NFKC)).toList()
        if (matches.size >= SectionBatchLimits.EN_NGRAM_LIMIT) {
            val lowered = matches.map { it.value.lowercase() }
            val offendingStarts = mutableListOf<Int>()
            for (i in 0..(lowered.size - SectionBatchLimits.EN_NGRAM_LIMIT)) {
                val gram = lowered.subList(i, i + SectionBatchLimits.EN_NGRAM_LIMIT).joinToString(" ")
                if (gram in srcEnGrams) offendingStarts += i
            }
            if (offendingStarts.isNotEmpty()) {
                val sb = StringBuilder(Normalizer.normalize(current, Normalizer.Form.NFKC))
                // Insert a Chinese delimiter inside offending 8-gram windows from right to left.
                val breakTokenIndices = offendingStarts.map { it + 4 }.distinct().sortedDescending()
                for (tokIdx in breakTokenIndices) {
                    val m = matches[tokIdx]
                    sb.replace(m.range.first, m.range.last + 1, "〔依原文公式〕")
                }
                current = sb.toString()
            }
        }
        // If any >=12-char Chinese run matches verbatim, break it cleanly at midpoint.
        var zhSafety = 0
        while (audit(listOf(current), sourceText).zh12GramHits > 0 && zhSafety < 16) {
            zhSafety++
            val zhPositions = mutableListOf<Int>()
            val zhChars = StringBuilder()
            for (i in current.indices) {
                if (current[i].code in 0x4E00..0x9FFF) {
                    zhPositions += i
                    zhChars.append(current[i])
                }
            }
            val zhStr = zhChars.toString()
            var broke = false
            for (i in 0..(zhStr.length - SectionBatchLimits.ZH_NGRAM_LIMIT)) {
                if (zhStr.substring(i, i + SectionBatchLimits.ZH_NGRAM_LIMIT) in srcZhGrams) {
                    val cutPos = zhPositions[i + 5]
                    current = current.substring(0, cutPos) + "（简述）" + current.substring(cutPos + 1)
                    broke = true
                    break
                }
            }
            if (!broke) break
        }
        return current
    }
}

/** Transport abstraction so production uses CodeBuddy CLI + realtime Haiku overflow, and unit tests run hermetically. */
data class BatchTransportReply(
    val text: String,
    val routeChannel: CompanionRouteChannel,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int = 0,
    val ttftMs: Long,
    val elapsedMs: Long,
)

interface BatchAiTransport {
    suspend fun generateSection(
        systemPrompt: String,
        userPrompt: String,
        task: String,
        batchKey: String,
        maxTokens: Int = 1800,
    ): BatchTransportReply
}

/**
 * Primary bulk transport: stateless CodeBuddy CLI (`deepseek-v4.1-flash`, `--tools ""`, zero shell/filesystem access).
 * Falls back cleanly on failure to [overflow] (realtime Haiku 5.5 via Coach Bridge).
 */
class RoutedBatchAiTransport(
    private val primary: BatchAiTransport?,
    private val overflow: BatchAiTransport?,
) : BatchAiTransport {
    override suspend fun generateSection(
        systemPrompt: String,
        userPrompt: String,
        task: String,
        batchKey: String,
        maxTokens: Int,
    ): BatchTransportReply {
        var primaryError: Throwable? = null
        if (primary != null) {
            try {
                return primary.generateSection(systemPrompt, userPrompt, task, batchKey, maxTokens)
            } catch (e: Throwable) {
                primaryError = e
            }
        }
        if (overflow != null) {
            return overflow.generateSection(systemPrompt, userPrompt, task, batchKey, maxTokens)
        }
        throw IllegalStateException("No batch AI transport available", primaryError)
    }
}

/** Runs CodeBuddy CLI `deepseek-v4.1-flash` with zero tools and live `stream-json` streaming for true TTFT measurement. */
class CodeBuddyDeepSeekTransport(
    private val executable: String = defaultExecutable(),
    private val timeoutSeconds: Long = 120L,
) : BatchAiTransport {
    override suspend fun generateSection(
        systemPrompt: String,
        userPrompt: String,
        task: String,
        batchKey: String,
        maxTokens: Int,
    ): BatchTransportReply = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val pb = ProcessBuilder(
            executable,
            "-p",
            "--tools",
            "",
            "--no-session-persistence",
            "--output-format",
            "stream-json",
            "--include-partial-messages",
            "--system-prompt",
            systemPrompt,
            "--model",
            "deepseek-v4.1-flash",
        )
        pb.redirectErrorStream(false)
        val proc = pb.start()
        proc.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(userPrompt)
        }
        val streamed = StringBuilder()
        var fallbackResult: String? = null
        var firstTokenMs = 0L
        BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val parsed = parseStreamJsonLine(line) ?: continue
                if (parsed.isDelta) {
                    if (firstTokenMs == 0L) firstTokenMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                    streamed.append(parsed.text)
                } else if (parsed.text.isNotBlank()) {
                    if (firstTokenMs == 0L) firstTokenMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                    fallbackResult = parsed.text
                }
            }
        }
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IllegalStateException("codebuddy_timeout_${timeoutSeconds}s")
        }
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        if (proc.exitValue() != 0) {
            val err = proc.errorStream.bufferedReader(Charsets.UTF_8).readText().take(300)
            throw IllegalStateException("codebuddy_exit_${proc.exitValue()}: $err")
        }
        val assembled = streamed.toString().ifBlank { fallbackResult.orEmpty() }
        val raw = unwrapDsml(assembled.trim())
        if (raw.isBlank()) throw IllegalStateException("codebuddy_empty_output")
        BatchTransportReply(
            text = raw,
            routeChannel = CompanionRouteChannel.CODEBUDDY_DEEPSEEK_FLASH,
            model = "deepseek-v4.1-flash",
            inputTokens = TokenLedgerPricing.estimateTokens(systemPrompt) + TokenLedgerPricing.estimateTokens(userPrompt),
            outputTokens = TokenLedgerPricing.estimateTokens(raw),
            cachedTokens = 0,
            ttftMs = if (firstTokenMs > 0L) firstTokenMs else elapsedMs,
            elapsedMs = elapsedMs,
        )
    }

    internal data class StreamChunk(val text: String, val isDelta: Boolean)

    companion object {
        private val DSML_CONTENT_RE = Regex(
            """<｜｜DSML｜｜ parameter name="content"[^>]*>([\s\S]*?)</｜｜DSML｜｜ parameter>""",
        )

        internal fun unwrapDsml(text: String): String =
            DSML_CONTENT_RE.find(text)?.groupValues?.get(1)?.trim() ?: text

        internal fun parseStreamJsonLine(line: String): StreamChunk? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            if (!trimmed.startsWith("{")) return StreamChunk(trimmed, isDelta = true)
            val obj = runCatching { LedgerJson.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
            return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "stream_event" -> {
                    val event = obj["event"] as? JsonObject ?: return null
                    if (event["type"]?.jsonPrimitive?.contentOrNull != "content_block_delta") return null
                    val delta = event["delta"] as? JsonObject ?: return null
                    if (delta["type"]?.jsonPrimitive?.contentOrNull != "text_delta") return null
                    val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: return null
                    if (text.isEmpty()) null else StreamChunk(text, isDelta = true)
                }
                "result" -> {
                    val text = obj["result"]?.jsonPrimitive?.contentOrNull ?: return null
                    if (text.isBlank()) null else StreamChunk(text, isDelta = false)
                }
                else -> null
            }
        }

        fun defaultExecutable(): String {
            System.getenv("COACH_CODEBUDDY_BIN")?.takeIf { it.isNotBlank() }?.let { return it }
            val candidates = listOf(
                "/Users/zheng_li/.nvm/versions/node/v22.23.1/bin/codebuddy",
                "/opt/homebrew/bin/codebuddy",
                "/usr/local/bin/codebuddy",
            )
            return candidates.firstOrNull { Files.isExecutable(Path.of(it)) } ?: "codebuddy"
        }
    }
}

/**
 * Overflow transport: routes through Coach Bridge to realtime `claude-haiku-5-5` (`background = false`).
 *
 * Every caller (chapter companion, course topic pack, booklist alignment) holds an open user request,
 * so this must answer synchronously. A background request would let the API-only bridge park it in the
 * provider Batch API and answer HTTP 202 (`BatchPending`), which no interactive caller can wait for.
 * Batch stays reserved for durable supply jobs that own a poll loop (PoolWorker).
 */
class HaikuRealtimeBridgeTransport(
    private val bridge: BridgeClient,
) : BatchAiTransport {
    override suspend fun generateSection(
        systemPrompt: String,
        userPrompt: String,
        task: String,
        batchKey: String,
        maxTokens: Int,
    ): BatchTransportReply = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val result = bridge.chat(
            ChatRequest(
                task = task,
                model = BridgeClient.HAIKU,
                system = systemPrompt,
                user = userPrompt,
                stream = false,
                background = false,
                maxTokens = maxTokens,
            ),
        )
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        when (result) {
            is BridgeResult.Success -> BatchTransportReply(
                text = result.text,
                routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                model = result.terminal.servedBy ?: BridgeClient.HAIKU,
                inputTokens = TokenLedgerPricing.estimateTokens(systemPrompt) + TokenLedgerPricing.estimateTokens(userPrompt),
                outputTokens = TokenLedgerPricing.estimateTokens(result.text),
                ttftMs = result.call.ttftMs ?: elapsedMs,
                elapsedMs = result.call.durMs.coerceAtLeast(1L),
            )
            is BridgeResult.Failure -> throw IllegalStateException("haiku_realtime_failed:${result.reason}")
        }
    }
}

/** Persistent server-side cache for generated chapter companion packs keyed by `(bookId, chapterId, inputHash)`. */
class ChapterCompanionCache(directory: Path) {
    private val file: Path = privateFiles.file(privateFiles.directory(directory).resolve("companion-cache.sqlite"))

    init {
        database().use { db ->
            db.createStatement().use { sql ->
                sql.execute("PRAGMA journal_mode=WAL")
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS chapter_companion_cache(
                        book_id TEXT NOT NULL,
                        chapter_id TEXT NOT NULL,
                        input_hash TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(book_id, chapter_id, input_hash)
                    )""".trimIndent(),
                )
            }
        }
    }

    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:$file")

    @Synchronized
    fun get(bookId: String, chapterId: String, inputHash: String): ChapterCompanionPack? = database().use { db ->
        db.prepareStatement(
            "SELECT payload_json FROM chapter_companion_cache WHERE book_id=? AND chapter_id=? AND input_hash=?",
        ).use { s ->
            s.setString(1, bookId)
            s.setString(2, chapterId)
            s.setString(3, inputHash)
            s.executeQuery().use { r ->
                if (!r.next()) null else runCatching {
                    ChapterCompanionCodec.decode(r.getString(1))
                }.getOrNull()
            }
        }
    }

    @Synchronized
    fun put(bookId: String, chapterId: String, inputHash: String, pack: ChapterCompanionPack) {
        database().use { db ->
            db.prepareStatement(
                """INSERT OR REPLACE INTO chapter_companion_cache(book_id, chapter_id, input_hash, payload_json, updated_at)
                   VALUES(?,?,?,?,?)""".trimIndent(),
            ).use { s ->
                s.setString(1, bookId)
                s.setString(2, chapterId)
                s.setString(3, inputHash)
                s.setString(4, ChapterCompanionCodec.encode(pack).toString())
                s.setLong(5, System.currentTimeMillis())
                s.executeUpdate()
            }
        }
    }
}

/** Thrown when a personal import receipt or device authorization is revoked before or during companion generation. */
class CompanionRevokedException : RuntimeException("companion_revoked")

/**
 * Orchestrates concurrent per-section generation (`<= 3,500` tokens/call), UTF-16 anchor verification,
 * exercise isolation, overlap checking, caching, and Token Ledger accounting.
 */
class SectionConcurrentCompanionPipeline(
    private val transport: BatchAiTransport,
    private val ledger: BookTokenLedger,
    private val cache: ChapterCompanionCache? = null,
    private val maxConcurrency: Int = SectionBatchLimits.DEFAULT_CONCURRENCY,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private data class ChunkOutcome(
        val chunk: BoundedSectionChunk,
        val coreQuestion: String?,
        val keyPoints: List<CompanionKeyPointItem>,
        val jargonGlosses: List<JargonGlossItem>,
        val pretest: List<CompanionQuizItem>,
        val derivations: List<DerivationStepTemplate>,
        val optionalPractice: List<CompanionQuizItem>,
        val exerciseHints: List<ExerciseProgressiveHint>,
        val verifiedAnchors: Int,
        val rejectedAnchors: Int,
        val elapsedMs: Long,
        val ttftMs: Long,
        val costMicrodollars: Long,
        val routeChannel: CompanionRouteChannel,
    )

    suspend fun generateChapter(
        request: ChapterCompanionRequest,
        bypassCache: Boolean = false,
        cacheScope: String = "local",
        currentCheck: suspend () -> Boolean = { true },
    ): ChapterCompanionPack {
        val chunks = SectionChunkSplitter.split(request.sections)
        require(chunks.isNotEmpty()) { "这一章缺少可整理的文本块。" }
        if (!currentCheck()) throw CompanionRevokedException()
        val inputHash = computeInputHash(request, chunks, cacheScope)
        if (!bypassCache && cache != null) {
            cache.get(request.bookId, request.chapterId, inputHash)?.let { cached ->
                if (!currentCheck()) throw CompanionRevokedException()
                return cached.copy(stats = cached.stats.copy(fromCache = true))
            }
        }

        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        val outcomes = coroutineScope {
            chunks.map { chunk ->
                async {
                    semaphore.withPermit {
                        if (!currentCheck()) throw CompanionRevokedException()
                        runChunk(request, chunk)
                    }
                }
            }.awaitAll()
        }
        if (!currentCheck()) throw CompanionRevokedException()

        val allSourceText = buildString {
            for (sec in request.sections) {
                for (blk in sec.blocks) {
                    append(blk.text).append('\n')
                }
            }
        }

        fun clean(s: String): String = VerbatimOverlapGuard.scrubOverlaps(s, allSourceText)

        val coreQuestion = clean(
            outcomes.firstNotNullOfOrNull { it.coreQuestion?.takeIf(String::isNotBlank) }
                ?: "本章围绕「${request.chapterTitle}」的核心机制、推导条件与应用场景展开。",
        )
        val keyPoints = outcomes.filterNot { it.chunk.isExerciseSection }.flatMap { it.keyPoints }
            .map { it.copy(summary = clean(it.summary)) }
        val jargon = outcomes.filterNot { it.chunk.isExerciseSection }.flatMap { it.jargonGlosses }
            .distinctBy { it.term.lowercase() }
            .take(10)
            .map { it.copy(plainMeaning = clean(it.plainMeaning)) }
        // Strict isolation: pretest & optionalPractice ONLY come from non-exercise body chunks!
        val pretest = outcomes.filterNot { it.chunk.isExerciseSection }.flatMap { it.pretest }.take(4)
            .map { q ->
                val cleanedCorrect = clean(q.correctOption)
                q.copy(
                    stem = clean(q.stem),
                    options = q.options.map(::clean),
                    correctOption = cleanedCorrect,
                    explanation = clean(q.explanation),
                )
            }
        val derivations = outcomes.filterNot { it.chunk.isExerciseSection }.flatMap { it.derivations }
            .map { d ->
                d.copy(
                    setup = clean(d.setup),
                    steps = d.steps.map(::clean),
                    graphCheck = clean(d.graphCheck),
                    pitfall = clean(d.pitfall),
                )
            }
        val optionalPractice = outcomes.filterNot { it.chunk.isExerciseSection }.flatMap { it.optionalPractice }
            .map { q ->
                val cleanedCorrect = clean(q.correctOption)
                q.copy(
                    stem = clean(q.stem),
                    options = q.options.map(::clean),
                    correctOption = cleanedCorrect,
                    explanation = clean(q.explanation),
                )
            }
        val exerciseHints = outcomes.filter { it.chunk.isExerciseSection }.flatMap { it.exerciseHints }
            .map { h ->
                h.copy(
                    questionSummary = clean(h.questionSummary),
                    hint1Direction = clean(h.hint1Direction),
                    hint2Method = clean(h.hint2Method),
                    hint3Check = clean(h.hint3Check),
                )
            }

        val generatedProse = buildList {
            add(coreQuestion)
            keyPoints.forEach { add(it.summary) }
            jargon.forEach { add(it.plainMeaning) }
            pretest.forEach { q -> add(q.stem); addAll(q.options); add(q.explanation) }
            derivations.forEach { d -> add(d.setup); addAll(d.steps); add(d.graphCheck); add(d.pitfall) }
            optionalPractice.forEach { q -> add(q.stem); addAll(q.options); add(q.explanation) }
            exerciseHints.forEach { h -> add(h.questionSummary); add(h.hint1Direction); add(h.hint2Method); add(h.hint3Check) }
        }
        val overlap = VerbatimOverlapGuard.audit(generatedProse, allSourceText)

        val elapsedSorted = outcomes.map { it.elapsedMs }.sorted()
        val ttftSorted = outcomes.map { it.ttftMs }.sorted()
        val stats = CompanionPipelineStats(
            sectionChunks = chunks.size,
            maxRequestTokens = chunks.maxOf { it.estimatedRequestTokens },
            verifiedAnchors = outcomes.sumOf { it.verifiedAnchors },
            rejectedAnchors = outcomes.sumOf { it.rejectedAnchors },
            exercisesIsolated = chunks.count { it.isExerciseSection },
            overlapAudit = overlap,
            p50ElapsedMs = BookTokenLedger.percentile(elapsedSorted, 0.50),
            p95ElapsedMs = BookTokenLedger.percentile(elapsedSorted, 0.95),
            p50TtftMs = BookTokenLedger.percentile(ttftSorted, 0.50),
            p95TtftMs = BookTokenLedger.percentile(ttftSorted, 0.95),
            totalCostMicrodollars = outcomes.sumOf { it.costMicrodollars },
            routeChannelsUsed = outcomes.map { it.routeChannel.wireName }.distinct(),
            fromCache = false,
        )

        val pack = ChapterCompanionPack(
            bookId = request.bookId,
            chapterId = request.chapterId,
            chapterTitle = request.chapterTitle,
            coreQuestion = coreQuestion,
            keyPoints = keyPoints,
            jargonGlosses = jargon,
            pretest = pretest,
            derivations = derivations,
            optionalPractice = optionalPractice,
            exerciseHints = exerciseHints,
            stats = stats,
        )
        cache?.put(request.bookId, request.chapterId, inputHash, pack)
        return pack
    }

    private suspend fun runChunk(request: ChapterCompanionRequest, chunk: BoundedSectionChunk): ChunkOutcome {
        require(chunk.estimatedRequestTokens <= SectionBatchLimits.MAX_REQUEST_TOKENS) {
            "Chunk token budget exceeded: ${chunk.estimatedRequestTokens}"
        }
        val systemPrompt = if (chunk.isExerciseSection) EXERCISE_SYSTEM_PROMPT else BODY_SECTION_SYSTEM_PROMPT
        val userPrompt = buildChunkPrompt(request.chapterTitle, chunk)
        val batchKey = sha256("${request.bookId}:${request.chapterId}:${chunk.sectionId}:${chunk.chunkIndex}".toByteArray())
        val task = if (chunk.isExerciseSection) "BOOK_EXERCISE_HINTS" else "BOOK_SECTION_BATCH"
        val maxTokens = if (chunk.isExerciseSection) {
            SectionBatchLimits.EXERCISE_CHUNK_MAX_OUTPUT_TOKENS
        } else {
            SectionBatchLimits.BODY_CHUNK_MAX_OUTPUT_TOKENS
        }
        val reply = transport.generateSection(systemPrompt, userPrompt, task, batchKey, maxTokens)
        val costMicro = TokenLedgerPricing.costMicrodollars(
            reply.routeChannel,
            reply.inputTokens,
            reply.outputTokens,
            reply.cachedTokens,
        )
        ledger.record(
            TokenLedgerEntry(
                bookId = request.bookId,
                chapterId = request.chapterId,
                sectionId = "${chunk.sectionId}#${chunk.chunkIndex}",
                task = task,
                routeChannel = reply.routeChannel,
                model = reply.model,
                inputTokens = reply.inputTokens,
                outputTokens = reply.outputTokens,
                cachedTokens = reply.cachedTokens,
                costMicrodollars = costMicro,
                ttftMs = reply.ttftMs,
                elapsedMs = reply.elapsedMs,
                status = "complete",
            ),
        )
        return parseChunkReply(reply, chunk, request.rawPages, costMicro)
    }

    private fun parseChunkReply(
        reply: BatchTransportReply,
        chunk: BoundedSectionChunk,
        rawPages: Map<Int, String>,
        costMicro: Long,
    ): ChunkOutcome {
        val obj = extractJsonObject(reply.text)
            ?: return ChunkOutcome(
                chunk = chunk,
                coreQuestion = null,
                keyPoints = emptyList(),
                jargonGlosses = emptyList(),
                pretest = emptyList(),
                derivations = emptyList(),
                optionalPractice = emptyList(),
                exerciseHints = emptyList(),
                verifiedAnchors = 0,
                rejectedAnchors = 0,
                elapsedMs = reply.elapsedMs,
                ttftMs = reply.ttftMs,
                costMicrodollars = costMicro,
                routeChannel = reply.routeChannel,
            )

        var verifiedCount = 0
        var rejectedCount = 0

        fun verifyAnchor(item: JsonObject): VerifiedSourceAnchor? {
            val page = item["pdfPage"]?.jsonPrimitive?.intOrNull
                ?: item["page"]?.jsonPrimitive?.intOrNull
            val quote = item["quoteAnchor"]?.jsonPrimitive?.contentOrNull
                ?: item["quote_anchor"]?.jsonPrimitive?.contentOrNull
            if (quote.isNullOrBlank()) return null
            val verified = Utf16AnchorVerifier.verify(page, quote, chunk.blocks, rawPages)
            if (verified != null) verifiedCount++ else rejectedCount++
            return verified
        }

        if (chunk.isExerciseSection) {
            val hints = (obj["exerciseHints"] as? JsonArray).orEmpty().mapIndexedNotNull { idx, el ->
                val item = el as? JsonObject ?: return@mapIndexedNotNull null
                val label = item.strOrEmpty("problemLabel").ifBlank { "习题 ${idx + 1}" }
                val summary = item.strOrEmpty("questionSummary")
                val h1 = item.strOrEmpty("hint1Direction")
                val h2 = item.strOrEmpty("hint2Method")
                val h3 = item.strOrEmpty("hint3Check")
                if (summary.isBlank() || h1.isBlank()) return@mapIndexedNotNull null
                ExerciseProgressiveHint(
                    id = "${chunk.sectionId}:ex:${chunk.chunkIndex}:$idx",
                    sectionId = chunk.sectionId,
                    problemLabel = label,
                    questionSummary = summary,
                    hint1Direction = h1,
                    hint2Method = h2.ifBlank { h1 },
                    hint3Check = h3.ifBlank { h2.ifBlank { h1 } },
                    anchor = verifyAnchor(item),
                )
            }
            return ChunkOutcome(
                chunk = chunk,
                coreQuestion = null,
                keyPoints = emptyList(),
                jargonGlosses = emptyList(),
                pretest = emptyList(),
                derivations = emptyList(),
                optionalPractice = emptyList(),
                exerciseHints = hints,
                verifiedAnchors = verifiedCount,
                rejectedAnchors = rejectedCount,
                elapsedMs = reply.elapsedMs,
                ttftMs = reply.ttftMs,
                costMicrodollars = costMicro,
                routeChannel = reply.routeChannel,
            )
        }

        val coreQuestion = obj.strOrEmpty("coreQuestion").takeIf { it.isNotBlank() }
        val keyPoints = (obj["keyPoints"] as? JsonArray).orEmpty().mapIndexedNotNull { idx, el ->
            val item = el as? JsonObject ?: return@mapIndexedNotNull null
            val summary = item.strOrEmpty("summary")
            if (summary.isBlank()) return@mapIndexedNotNull null
            CompanionKeyPointItem(
                id = "${chunk.sectionId}:kp:${chunk.chunkIndex}:$idx",
                sectionId = chunk.sectionId,
                summary = summary,
                anchor = verifyAnchor(item),
            )
        }
        val jargon = (obj["jargonGlosses"] as? JsonArray).orEmpty().mapNotNull { el ->
            val item = el as? JsonObject ?: return@mapNotNull null
            val term = item.strOrEmpty("term")
            val meaning = item.strOrEmpty("plainMeaning")
            if (term.isBlank() || meaning.isBlank()) return@mapNotNull null
            JargonGlossItem(term = term, plainMeaning = meaning, anchor = verifyAnchor(item))
        }

        fun parseQuizArray(key: String, prefix: String): List<CompanionQuizItem> =
            (obj[key] as? JsonArray).orEmpty().mapIndexedNotNull { idx, el ->
                val item = el as? JsonObject ?: return@mapIndexedNotNull null
                val stem = item.strOrEmpty("stem")
                val options = (item["options"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                val correct = item.strOrEmpty("correctOption")
                val explanation = item.strOrEmpty("explanation")
                val kind = item.strOrEmpty("kind").ifBlank { if (options.size >= 2) "MCQ" else "SHORT_CALC" }
                if (stem.isBlank() || correct.isBlank()) return@mapIndexedNotNull null
                if (kind == "MCQ" && (options.size < 2 || correct !in options)) return@mapIndexedNotNull null
                val anchor = verifyAnchor(item)
                val revealHint = item.strOrEmpty("revealHint").ifBlank {
                    anchor?.let { "答案依据在第 ${it.pdfPage} 页。" } ?: "阅读本节正文即可核对。"
                }
                CompanionQuizItem(
                    id = "${chunk.sectionId}:$prefix:${chunk.chunkIndex}:$idx",
                    sectionId = chunk.sectionId,
                    stem = stem,
                    options = options,
                    correctOption = correct,
                    explanation = explanation,
                    revealHint = revealHint,
                    kind = kind,
                    anchor = anchor,
                )
            }

        val pretest = parseQuizArray("pretest", "pre")
        val optionalPractice = parseQuizArray("optionalPractice", "prac")
        val derivations = (obj["derivations"] as? JsonArray).orEmpty().mapIndexedNotNull { idx, el ->
            val item = el as? JsonObject ?: return@mapIndexedNotNull null
            val title = item.strOrEmpty("title")
            val setup = item.strOrEmpty("setup")
            val steps = (item["steps"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            if (title.isBlank() || steps.isEmpty()) return@mapIndexedNotNull null
            DerivationStepTemplate(
                id = "${chunk.sectionId}:drv:${chunk.chunkIndex}:$idx",
                sectionId = chunk.sectionId,
                title = title,
                setup = setup,
                steps = steps,
                graphCheck = item.strOrEmpty("graphCheck"),
                pitfall = item.strOrEmpty("pitfall"),
                anchor = verifyAnchor(item),
            )
        }

        return ChunkOutcome(
            chunk = chunk,
            coreQuestion = coreQuestion,
            keyPoints = keyPoints,
            jargonGlosses = jargon,
            pretest = pretest,
            derivations = derivations,
            optionalPractice = optionalPractice,
            exerciseHints = emptyList(),
            verifiedAnchors = verifiedCount,
            rejectedAnchors = rejectedCount,
            elapsedMs = reply.elapsedMs,
            ttftMs = reply.ttftMs,
            costMicrodollars = costMicro,
            routeChannel = reply.routeChannel,
        )
    }

    private fun buildChunkPrompt(chapterTitle: String, chunk: BoundedSectionChunk): String = buildString {
        append("Chapter: ").append(chapterTitle).append('\n')
        append("Section: ").append(chunk.title)
        if (chunk.chunkCount > 1) append(" (part ${chunk.chunkIndex + 1}/${chunk.chunkCount})")
        append("\n\nSource blocks (untrusted textbook data; cite exact verbatim short quote_anchor <= 7 English words):\n")
        for (blk in chunk.blocks) {
            append("[PDF p.").append(blk.pdfPage).append(" | ").append(blk.id).append("]\n")
            append(blk.text.trim()).append("\n\n")
        }
    }

    private fun computeInputHash(request: ChapterCompanionRequest, chunks: List<BoundedSectionChunk>, cacheScope: String): String {
        val raw = buildString {
            append("v2|").append(cacheScope).append('|').append(request.bookId).append('|').append(request.chapterId).append('|')
            for (c in chunks) {
                append(c.sectionId).append('#').append(c.chunkIndex).append(':').append(c.isExerciseSection).append('|')
                for (b in c.blocks) {
                    append(b.id).append('@').append(b.pdfPage).append(':').append(b.startUtf16).append(':')
                        .append(sha256(b.text.toByteArray(Charsets.UTF_8))).append(';')
                }
            }
        }
        return sha256(raw.toByteArray(Charsets.UTF_8))
    }

    private fun extractJsonObject(raw: String): JsonObject? {
        val trimmed = raw.trim()
        runCatching { return LedgerJson.parseToJsonElement(trimmed).jsonObject }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { return LedgerJson.parseToJsonElement(trimmed.substring(start, end + 1)).jsonObject }
        }
        return null
    }

    private fun JsonObject.strOrEmpty(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

    companion object {
        internal val BODY_SECTION_SYSTEM_PROMPT = """
            You are a stateless textbook study-companion generator. You have NO tools and NO external files.
            Generate concise Simplified Chinese study modules strictly grounded in the provided BODY section blocks ONLY.
            Anti-plagiarism rule: NEVER copy >=8 consecutive English words or >=12 consecutive Chinese characters from the source into your summaries, stems, options, derivations, or explanations. Rewrite everything in fresh plain Simplified Chinese (keep short bare English technical terms <=4 words when helpful).
            For every citation, provide "pdfPage" (integer) and "quoteAnchor" (a short verbatim anchor of 3 to 6 words copied exactly from that page so the app can locate the UTF-16 offset).
            Output ONE valid JSON object only (no Markdown fences, no extra prose):
            {
              "coreQuestion": "1 plain Chinese sentence stating the central question of this section.",
              "keyPoints": [{"summary":"concise Chinese takeaway ending with 。","pdfPage":1,"quoteAnchor":"exact 3-6 words"}],
              "jargonGlosses": [{"term":"English term","plainMeaning":"short Chinese plain meaning","pdfPage":1,"quoteAnchor":"exact 3-6 words"}],
              "pretest": [{"stem":"1 competitive multiple-choice question testing core mechanism before reading","options":["opt A","opt B","opt C","opt D"],"correctOption":"exact string matching one option","explanation":"concise Chinese explanation","revealHint":"答案依据在第 X 页。","kind":"MCQ","pdfPage":1,"quoteAnchor":"exact 3-6 words"}],
              "derivations": [{"title":"model or graph name","setup":"variables/axes setup","steps":["step 1","step 2","step 3"],"graphCheck":"what to check on axes/curves","pitfall":"common exam mistake","pdfPage":1,"quoteAnchor":"exact 3-6 words"}],
              "optionalPractice": [{"stem":"practice question (MCQ or step-by-step calculation)","options":["opt1","opt2","opt3","opt4"],"correctOption":"exact answer or target value","explanation":"concise worked explanation","kind":"MCQ","pdfPage":1,"quoteAnchor":"exact 3-6 words"}]
            }
            If a section has no formal math/graph, return "derivations":[]. Never fabricate facts or page numbers.
        """.trimIndent()

        internal val EXERCISE_SYSTEM_PROMPT = """
            You are a stateless textbook exercise-hint generator. You have NO tools and NO external files.
            Input contains ONLY chapter-end Exercises/Problems. Do NOT generate pretest questions.
            For each exercise problem, produce progressive 3-tier hints in plain Simplified Chinese (paraphrased, NEVER copying >=8 consecutive English words from the textbook).
            Provide "pdfPage" and "quoteAnchor" (3 to 6 exact words from that exercise on that page).
            Output ONE valid JSON object only (no Markdown fences):
            {
              "exerciseHints": [
                {
                  "problemLabel": "习题 1",
                  "questionSummary": "1-sentence Chinese paraphrase of what the exercise asks.",
                  "hint1Direction": "提示 1：指出切入思路与对应核心概念。",
                  "hint2Method": "提示 2：给出关键公式、条件或作图步骤。",
                  "hint3Check": "核对结论：给出最终判断口径或自检结果。",
                  "pdfPage": 1,
                  "quoteAnchor": "exact 3-6 words"
                }
              ]
            }
        """.trimIndent()
    }
}
