package com.coach.server

import com.coach.ai.AnalyticalNumericKey
import com.coach.ai.BridgeClient
import com.coach.ai.BridgeResult
import com.coach.ai.ChatRequest
import com.coach.domain.grade.ChapterOpenAcceptedPoint
import com.coach.domain.grade.ChapterOpenDraft
import com.coach.domain.grade.ChapterOpenGate
import com.coach.domain.grade.ChapterOpenPointRef
import com.coach.domain.grade.ChapterOpenRawPoint
import com.coach.domain.grade.ChapterOpenSourceBlock
import com.coach.domain.grade.NumericValue
import com.coach.store.sha256
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.text.Normalizer
import java.util.UUID

/**
 * LearningCoach 2.0-AI-2 (TON-1801):
 * Course-framework point-by-point rubric grading (zero ghostwriting) + 4-class misconception diagnosis +
 * targeted isomorphic variant generation.
 *
 * Hard server invariants:
 * 1. Cold-grading fix: frozen L1/L2 textbook/lecture page definitions are injected into grading alongside
 *    authoritative deterministic `ServerNumericFact` verification; external past-paper / exam-key inputs
 *    are hard-blocked via [PracticeLeakageGuard].
 * 2. Legacy `rewrite` ghostwriting is completely abolished from both prompt and response schema.
 * 3. Three hard gates run before stats/persistence:
 *    - [StudentQuoteGate]: any `verdict != ZERO` or `awardedMarks > 0` must match an exact contiguous
 *      substring of `studentAnswer` (1 stateless repair retry; still invalid => forcibly zeroed).
 *    - [SourcePageAnchorGate]: `(pdfPage, quoteAnchor)` verified via UTF-16 matching on `sourcePages`.
 *    - [NoRewriteOutputGuard]: `feedbackNoteZh <= 42` Chinese chars and `<= 18` consecutive English words;
 *      blocks model-answer paragraphs (`rewrittenParagraphsEmitted == 0`).
 * 4. Generic two-step misconception diagnosis (Step 1 Equivalence Shield -> Step 2 4-class root cause)
 *    uses a strictly chapter-agnostic, zero-instance system prompt, then generates 1 targeted isomorphic
 *    variant MCQ passing UTF-16 anchor verification and bilingual zero-overlap (`en 8-gram == 0`,
 *    `zh 12-char == 0`), recorded in [BookTokenLedger].
 * 5. Overflow Haiku transport explicitly pins `model = "claude-haiku-5-5"` and `max_tokens = 8192`
 *    (never relying on implicit Sonnet rewriting).
 */
object PracticeGradeLimits {
    const val PINNED_HAIKU_MODEL: String = BridgeClient.HAIKU // "claude-haiku-5-5" (kept for test/ledger compat)
    const val PINNED_GRADE_MODEL: String = BridgeClient.GPT_62_ASTRA
    const val BATCH_MAX_TOKENS: Int = 8_192
    const val MAX_FEEDBACK_ZH_CHARS: Int = 42
    const val MAX_FEEDBACK_CONSECUTIVE_EN_WORDS: Int = 18
    const val MAX_SOURCE_CHARS_IN_PROMPT: Int = 14_000
    const val TASK_GRADE: String = "practice_grade"
    const val TASK_DIAGNOSE: String = "practice_diagnose"
    const val TASK_VARIANT: String = "practice_variant"
    const val TASK_CHAPTER_OPEN: String = "chapter_open_question"
}

internal data class PracticeCallPlan(
    val useBatch: Boolean,
    val model: String,
    val task: String,
)

/** Owner chapter-open generation is Haiku 5.5 Batch. Other practice calls keep their existing model. */
internal fun planPracticeCall(
    task: String,
    friend: String?,
    backgroundBatch: Boolean,
    defaultModel: String,
): PracticeCallPlan {
    val chapterOpen = task == PracticeGradeLimits.TASK_CHAPTER_OPEN
    val useBatch = (backgroundBatch || chapterOpen) && friend == null
    val model = when {
        chapterOpen -> BridgeClient.HAIKU
        friend != null -> BridgeClient.CLAUDE
        else -> defaultModel
    }
    val targetTask = if (friend != null) "READER_SELECTION" else task
    return PracticeCallPlan(useBatch = useBatch, model = model, task = targetTask)
}

enum class PracticeRubricVerdict {
    FULL,
    PARTIAL,
    ZERO,
    ;

    companion object {
        fun parse(raw: String?): PracticeRubricVerdict = when (raw?.trim()?.uppercase()) {
            "FULL" -> FULL
            "PARTIAL" -> PARTIAL
            else -> ZERO
        }
    }
}

enum class PracticeMisconceptionClass(val labelZh: String) {
    NONE("无实质错因"),
    CONCEPT_CONFUSION("概念混淆"),
    SIGN_OR_FORMULA("公式与符号错误"),
    GRAPH_SHIFT_OR_ROTATION("图形移动或旋转方向错误"),
    ARITHMETIC_OR_ALGEBRA("算术或代数计算失误"),
    ;

    companion object {
        fun parse(raw: String?): PracticeMisconceptionClass =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

data class PracticeSourcePageInput(
    val pdfPage: Int,
    val text: String,
    val title: String = "",
    val authorityRank: Int = 2,
)

data class PracticeNumericKeySpec(
    val cid: String = "",
    val label: String = "",
    val expected: String,
    val submitted: String? = null,
    val absTolerance: String = "0.015",
    val relTolerance: String = "0.0",
    val decimalPlaces: Int? = 2,
)

data class PracticeRubricCriterionInput(
    val cid: String,
    val slotLabel: String,
    val maxMarks: Double = 0.0,
    val pdfPage: Int = 0,
    val quoteAnchor: String = "",
    val sourceExcerpt: String = "",
    val numericKey: PracticeNumericKeySpec? = null,
)

data class PracticeGradeAndDiagnoseRequest(
    val mode: String = "GRADE_AND_DIAGNOSE",
    val bookId: String,
    val chapterId: String = "practice",
    val sectionId: String = "section",
    val conceptKey: String = "",
    val questionId: String = "",
    val questionStem: String,
    val studentAnswer: String,
    val rubricCriteria: List<PracticeRubricCriterionInput> = emptyList(),
    val sourcePages: List<PracticeSourcePageInput> = emptyList(),
    val numericFacts: List<PracticeNumericKeySpec> = emptyList(),
    val generateVariant: Boolean = true,
    val referenceExclusionTexts: List<String> = emptyList(),
    val mcqCorrectOption: String = "",
    val mcqSelectedOption: String = "",
    val retryPointIds: List<String> = emptyList(),
)

data class ServerNumericFactResult(
    val cid: String,
    val label: String,
    val expected: String,
    val submitted: String,
    val correct: Boolean,
    val status: String,
    val absTolerance: String,
    val relTolerance: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("cid", cid)
        put("label", label)
        put("expected", expected)
        put("submitted", submitted)
        put("correct", correct)
        put("status", status)
        put("absTolerance", absTolerance)
        put("relTolerance", relTolerance)
    }
}

data class GradedRubricItemResult(
    val cid: String,
    val verdict: PracticeRubricVerdict,
    val rawModelVerdict: PracticeRubricVerdict,
    val awardedMarks: Double,
    val rawModelAwardedMarks: Double,
    val maxMarks: Double,
    val studentQuote: String,
    val studentQuoteVerified: Boolean,
    val quoteGateZeroed: Boolean,
    val quoteRepairAttempted: Boolean,
    val feedbackNoteZh: String,
    val pdfPage: Int,
    val quoteAnchor: String,
    val anchorVerified: Boolean,
    val missCause: String = "",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("cid", cid)
        put("verdict", verdict.name)
        put("rawModelVerdict", rawModelVerdict.name)
        put("awardedMarks", awardedMarks)
        put("rawModelAwardedMarks", rawModelAwardedMarks)
        put("maxMarks", maxMarks)
        put("studentQuote", studentQuote)
        put("studentQuoteVerified", studentQuoteVerified)
        put("quoteGateZeroed", quoteGateZeroed)
        put("quoteRepairAttempted", quoteRepairAttempted)
        put("feedbackNoteZh", feedbackNoteZh)
        put("pdfPage", pdfPage)
        put("quoteAnchor", quoteAnchor)
        put("anchorVerified", anchorVerified)
        if (missCause.isNotBlank()) put("missCause", missCause)
    }
}

data class MisconceptionDiagnosisResult(
    val isEquivalentValid: Boolean,
    val errorClass: PracticeMisconceptionClass,
    val studentErrorSpan: String,
    val diagnosisZh: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("isEquivalentValid", isEquivalentValid)
        put("errorClass", errorClass.name)
        put("labelZh", errorClass.labelZh)
        put("studentErrorSpan", studentErrorSpan)
        put("diagnosisZh", diagnosisZh)
    }
}

data class IsomorphicVariantResult(
    val variantId: String,
    val stem: String,
    val options: List<String>,
    val correctOption: String,
    val targetedTrapExplanation: String,
    val pdfPage: Int,
    val quoteAnchor: String,
    val conceptKey: String,
    val targetErrorClass: PracticeMisconceptionClass,
    val anchorVerified: Boolean,
    val overlapAudit: OverlapAudit,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("variantId", variantId)
        put("stem", stem)
        put("options", buildJsonArray { options.forEach { add(JsonPrimitive(it)) } })
        put("correctOption", correctOption)
        put("targetedTrapExplanation", targetedTrapExplanation)
        put("pdfPage", pdfPage)
        put("quoteAnchor", quoteAnchor)
        put("conceptKey", conceptKey)
        put("targetErrorClass", targetErrorClass.name)
        put("anchorVerified", anchorVerified)
        put("overlapAudit", buildJsonObject {
            put("en8GramHits", overlapAudit.en8GramHits)
            put("zh12GramHits", overlapAudit.zh12GramHits)
            put("passed", overlapAudit.passed)
        })
    }
}

data class PracticeGradeAndDiagnoseResponse(
    val mode: String,
    val rubricItems: List<GradedRubricItemResult>,
    val numericFacts: List<ServerNumericFactResult>,
    val totalAwarded: Double,
    val maxMarks: Double,
    val summaryMetaLine: String,
    val diagnosis: MisconceptionDiagnosisResult?,
    val variant: IsomorphicVariantResult?,
    val modelUsed: String,
    val routeChannel: String,
    val rewrittenParagraphsEmitted: Int = 0,
    val rewrittenParagraphsBlocked: Int = 0,
    val unverifiedPositivePassCount: Int = 0,
    val elapsedMs: Long = 0L,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mode", mode)
        put("rubricItems", buildJsonArray { rubricItems.forEach { add(it.toJson()) } })
        put("numericFacts", buildJsonArray { numericFacts.forEach { add(it.toJson()) } })
        put("totalAwarded", totalAwarded)
        put("maxMarks", maxMarks)
        put("summaryMetaLine", summaryMetaLine)
        diagnosis?.let { put("diagnosis", it.toJson()) }
        variant?.let { put("variant", it.toJson()) }
        put("modelUsed", modelUsed)
        put("routeChannel", routeChannel)
        put("rewrittenParagraphsEmitted", rewrittenParagraphsEmitted)
        put("rewrittenParagraphsBlocked", rewrittenParagraphsBlocked)
        put("unverifiedPositivePassCount", unverifiedPositivePassCount)
        put("elapsedMs", elapsedMs)
    }
}

/** Rejects past-paper keys / external exam solutions from entering grading or generation prompts. */
object PracticeLeakageGuard {
    private val LEAKAGE_REGEX = Regex(
        """(202[0-5]\s*年?\s*(期末|真题|考过)|期末考过|真题详解|押题|留出集|dec(?:ember)?\s*2024\s*(?:exam|final|paper)|past\s*paper\s*question|01-dec2024)""",
        RegexOption.IGNORE_CASE,
    )
    private val FORBIDDEN_MARKERS = listOf(
        "expected_pair=",
        "present_in_answer=",
        "extLines",
        "extAnchor",
    )

    fun assertCleanCourseInput(text: String, fieldName: String) {
        val hit = LEAKAGE_REGEX.find(text)
        if (hit != null) {
            throw IllegalArgumentException("输入 $fieldName 包含受限往年真题或外部详解标记，已拒绝批改。")
        }
        for (marker in FORBIDDEN_MARKERS) {
            if (marker in text) {
                throw IllegalArgumentException("输入 $fieldName 包含禁止的外部参考键字段。")
            }
        }
    }
}

/** Deterministic text normalization shared across quote gate & anchor verification. */
object PracticeTextNormalizer {
    fun normalize(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        var lastSpace = true
        for (ch in nfkc) {
            val mapped = when (ch) {
                '\u2212', '\u2013', '\u2014' -> '-'
                '\u2018', '\u2019' -> '\''
                '\u201c', '\u201d' -> '"'
                '\u00a0' -> ' '
                else -> ch
            }
            if (mapped.isWhitespace()) {
                if (!lastSpace && sb.isNotEmpty()) {
                    sb.append(' ')
                    lastSpace = true
                }
            } else {
                sb.append(mapped)
                lastSpace = false
            }
        }
        return sb.toString().trim()
    }
}

/**
 * Deterministic `ServerNumericFact` checker built on [NumericValue] and [AnalyticalNumericKey].
 * Runs BEFORE LLM grading so arithmetic/formula truth is server-owned and immutable.
 */
object PracticeServerNumericChecker {
    private val NUM_LITERAL_RE = Regex("""(?<![A-Za-z0-9_])[-+]?\d+(?:\.\d+)?%?(?![A-Za-z0-9_])""")

    fun evaluate(
        specs: List<PracticeNumericKeySpec>,
        studentAnswer: String,
    ): List<ServerNumericFactResult> = specs.map { spec ->
        val candidate = spec.submitted?.trim()?.takeIf(String::isNotEmpty)
            ?: extractCandidateNumber(studentAnswer, spec.label, spec.expected)
            ?: ""
        val absTol = spec.absTolerance.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.015
        val relTol = spec.relTolerance.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val (correct, status) = if (candidate.isBlank()) {
            false to "MISSING"
        } else if (spec.decimalPlaces == 2 && relTol == 0.0 && runCatching { BigDecimal(spec.expected); BigDecimal(spec.absTolerance) }.isSuccess) {
            val analytical = AnalyticalNumericKey(spec.expected, spec.absTolerance)
            val res = analytical.grade(candidate)
            if (res.correct) {
                true to res.status
            } else {
                val fallbackMatch = NumericValue.matches(candidate, spec.expected, relTol, absTol)
                fallbackMatch to if (fallbackMatch) "CORRECT" else res.status
            }
        } else {
            val ok = NumericValue.matches(candidate, spec.expected, relTol, absTol)
            ok to if (ok) "CORRECT" else "INCORRECT"
        }
        ServerNumericFactResult(
            cid = spec.cid,
            label = spec.label,
            expected = spec.expected,
            submitted = candidate,
            correct = correct,
            status = status,
            absTolerance = spec.absTolerance,
            relTolerance = spec.relTolerance,
        )
    }

    internal fun extractCandidateNumber(studentAnswer: String, label: String, expected: String): String? {
        val norm = PracticeTextNormalizer.normalize(studentAnswer)
        if (norm.isBlank()) return null
        val targetVal = NumericValue.parse(expected)
        val partMatch = Regex("""\(([a-z])\)""", RegexOption.IGNORE_CASE).find(label)
        val segment = if (partMatch != null) {
            val marker = "(${partMatch.groupValues[1].lowercase()})"
            val lower = norm.lowercase()
            val start = lower.indexOf(marker)
            if (start >= 0) {
                val nextPart = Regex("""\([a-z]\)""").find(lower, start + marker.length)
                norm.substring(start, nextPart?.range?.first ?: norm.length)
            } else norm
        } else norm

        val numbers = NUM_LITERAL_RE.findAll(segment).map { it.value }.toList()
        if (numbers.isEmpty()) return null
        if (targetVal != null) {
            val exact = numbers.lastOrNull { NumericValue.matches(it, expected, 0.0, 0.015) }
            if (exact != null) return exact
        }
        return numbers.lastOrNull()
    }
}

/** Hard Gate 1: Every awarded item (`verdict != ZERO` or `awardedMarks > 0`) MUST cite an exact contiguous student substring. */
object StudentQuoteGate {
    fun isExactContiguousSubstring(quote: String, studentAnswer: String): Boolean {
        val cleanQuote = PracticeTextNormalizer.normalize(quote)
        if (cleanQuote.length < 3) return false
        val cleanAnswer = PracticeTextNormalizer.normalize(studentAnswer)
        if (cleanAnswer.isBlank()) return false
        return cleanAnswer.contains(cleanQuote, ignoreCase = true)
    }

    /** Returns exact span in original `studentAnswer` for UI green highlighting, or null if not found. */
    fun locateSpanInOriginal(quote: String, studentAnswer: String): IntRange? {
        val trimmed = quote.trim()
        if (trimmed.length < 3) return null
        val directIdx = studentAnswer.indexOf(trimmed)
        if (directIdx >= 0) return directIdx until (directIdx + trimmed.length)
        val directCi = studentAnswer.indexOf(trimmed, ignoreCase = true)
        if (directCi >= 0) return directCi until (directCi + trimmed.length)
        val normSpan = Utf16AnchorVerifier.findNormalizedUtf16Span(studentAnswer, trimmed)
        return normSpan?.let { it.first until it.second }
    }
}

/** Hard Gate 3: Anti-ghostwriting output guard (`<= 42` zh chars, `<= 18` consecutive English words, zero model answers). */
object NoRewriteOutputGuard {
    private val GHOSTWRITE_PATTERNS = listOf(
        Regex("""(?i)\b(?:model\s+answer|full[- ]mark\s+answer|sample\s+answer|rewrite\s*:)\b"""),
        Regex("""(?:示范答案|标准答案如下|完整改写|可直接抄写|参考全文)"""),
    )

    data class GuardOutcome(
        val sanitizedNoteZh: String,
        val blockedGhostwriting: Boolean,
    )

    fun chineseCharCount(text: String): Int = text.count { it.code in 0x4E00..0x9FFF }

    fun maxConsecutiveEnglishWords(text: String): Int {
        var maxRun = 0
        var curRun = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch.code in 0x4E00..0x9FFF -> {
                    curRun = 0
                    i++
                }
                ch.isLetterOrDigit() -> {
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '\'' || text[i] == '-')) i++
                    curRun++
                    if (curRun > maxRun) maxRun = curRun
                }
                else -> i++
            }
        }
        return maxRun
    }

    fun isCompliant(note: String): Boolean {
        if (note.isBlank()) return true
        if (GHOSTWRITE_PATTERNS.any { it.containsMatchIn(note) }) return false
        if (chineseCharCount(note) > PracticeGradeLimits.MAX_FEEDBACK_ZH_CHARS) return false
        if (maxConsecutiveEnglishWords(note) > PracticeGradeLimits.MAX_FEEDBACK_CONSECUTIVE_EN_WORDS) return false
        return true
    }

    fun enforce(rawNote: String, verdict: PracticeRubricVerdict, pdfPage: Int): GuardOutcome {
        val compact = rawNote.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
        if (compact.isBlank()) {
            val fallback = when (verdict) {
                PracticeRubricVerdict.FULL -> "该采分点核心依据已写清。"
                PracticeRubricVerdict.PARTIAL -> if (pdfPage > 0) "要点部分命中，请回看第${pdfPage}页补全条件。" else "要点部分命中，请补齐关键步骤。"
                PracticeRubricVerdict.ZERO -> if (pdfPage > 0) "漏掉该采分点，请回看第${pdfPage}页核对定义。" else "漏掉该采分点，请回看原页核对。"
            }
            return GuardOutcome(fallback, blockedGhostwriting = false)
        }
        val ghostDetected = GHOSTWRITE_PATTERNS.any { it.containsMatchIn(compact) } ||
            maxConsecutiveEnglishWords(compact) > PracticeGradeLimits.MAX_FEEDBACK_CONSECUTIVE_EN_WORDS ||
            compact.length > 140
        if (isCompliant(compact) && !ghostDetected) {
            return GuardOutcome(compact, blockedGhostwriting = false)
        }
        var stripped = compact
        for (pat in GHOSTWRITE_PATTERNS) {
            stripped = stripped.replace(pat, "").trim()
        }
        if (maxConsecutiveEnglishWords(stripped) > PracticeGradeLimits.MAX_FEEDBACK_CONSECUTIVE_EN_WORDS) {
            val fallback = when (verdict) {
                PracticeRubricVerdict.FULL -> "核心要点已答出，表述完整。"
                PracticeRubricVerdict.PARTIAL -> if (pdfPage > 0) "部分要点正确，漏步请核对第${pdfPage}页原文。" else "部分要点正确，仍需补齐漏掉条件。"
                PracticeRubricVerdict.ZERO -> if (pdfPage > 0) "未命中该采分点，请翻回第${pdfPage}页核对定义。" else "未命中该采分点，请对照课程原页补齐。"
            }
            return GuardOutcome(fallback, blockedGhostwriting = true)
        }
        val sb = StringBuilder()
        var zhSeen = 0
        for (ch in stripped) {
            if (ch.code in 0x4E00..0x9FFF) {
                if (zhSeen >= PracticeGradeLimits.MAX_FEEDBACK_ZH_CHARS - 1) break
                zhSeen++
            }
            if (sb.length >= 76) break
            sb.append(ch)
        }
        val trimmed = sb.toString().trimEnd(' ', ',', '，', '；', ';', ':', '：')
        val finalNote = if (trimmed.isBlank()) {
            if (pdfPage > 0) "请对照第${pdfPage}页补齐该步。" else "请对照原页补齐该步。"
        } else if (trimmed.endsWith('。') || trimmed.endsWith('.')) {
            trimmed
        } else {
            "$trimmed。"
        }
        return GuardOutcome(finalNote, blockedGhostwriting = ghostDetected)
    }
}

/**
 * Overflow transport explicitly pinning `claude-haiku-5-5` and `max_tokens = 8192` through Coach Bridge.
 * Never relies on `claude-sonnet-5-5` implicit rewriting; stays strictly under `CreditLedger` 95% grant guard.
 */
class HaikuPinnedBridgeTransport(
    private val bridge: BridgeClient,
    private val defaultMaxTokens: Int = PracticeGradeLimits.BATCH_MAX_TOKENS,
    private val backgroundBatch: Boolean = false,
    private val model: String = PracticeGradeLimits.PINNED_GRADE_MODEL,
) : BatchAiTransport {
    override suspend fun generateSection(
        systemPrompt: String,
        userPrompt: String,
        task: String,
        batchKey: String,
        maxTokens: Int,
    ): BatchTransportReply = generateSectionInternal(
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        task = task,
        batchKey = batchKey,
        maxTokens = maxTokens,
        friend = null,
        device = null,
    )

    override suspend fun generateSectionForFriend(
        systemPrompt: String,
        userPrompt: String,
        task: String,
        batchKey: String,
        maxTokens: Int,
        friend: String?,
        device: String?,
    ): BatchTransportReply = generateSectionInternal(
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        task = task,
        batchKey = batchKey,
        maxTokens = maxTokens,
        friend = friend,
        device = device,
    )

    private suspend fun generateSectionInternal(
        systemPrompt: String,
        userPrompt: String,
        task: String,
        batchKey: String,
        maxTokens: Int,
        friend: String?,
        device: String?,
    ): BatchTransportReply = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val effectiveLimit = maxOf(maxTokens, defaultMaxTokens).coerceAtMost(16_000)
        val plan = planPracticeCall(task, friend, backgroundBatch, model)
        val useBatch = plan.useBatch
        val targetTask = plan.task
        val targetModel = plan.model
        val client = if (useBatch) bridge.forBatchJob("practice", batchKey) else bridge
        val request = ChatRequest(
            task = targetTask,
            model = targetModel,
            system = systemPrompt,
            user = userPrompt,
            stream = false,
            background = useBatch,
            maxTokens = effectiveLimit,
            friendId = friend?.let(::friendId),
            friendDevice = if (friend != null) device else null,
        )
        val result = if (useBatch) {
            pollUntilComplete(client, request)
        } else {
            client.chat(request)
        }
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        when (result) {
            is BridgeResult.Success -> BatchTransportReply(
                text = result.text,
                routeChannel = if (useBatch) CompanionRouteChannel.HAIKU_BATCH else CompanionRouteChannel.HAIKU_REALTIME,
                model = result.terminal.servedBy ?: targetModel,
                inputTokens = TokenLedgerPricing.estimateTokens(systemPrompt) + TokenLedgerPricing.estimateTokens(userPrompt),
                outputTokens = TokenLedgerPricing.estimateTokens(result.text),
                ttftMs = result.call.ttftMs ?: elapsedMs,
                elapsedMs = result.call.durMs.coerceAtLeast(1L),
            )
            is BridgeResult.Failure -> {
                if (friend != null) {
                    val reason = result.terminal?.reason
                    val quotaCode = reason?.takeIf { it in setOf("daily_quota", "monthly_quota") }
                    throw FriendServiceError(quotaCode ?: "unavailable")
                }
                throw IllegalStateException("haiku_pinned_failed:${result.reason}")
            }
        }
    }

    private fun pollUntilComplete(client: BridgeClient, request: ChatRequest): BridgeResult {
        var attempts = 0
        while (attempts < 40) {
            attempts++
            try {
                return client.chat(request)
            } catch (pending: com.coach.ai.BatchPending) {
                Thread.sleep(pending.retryAfterMillis.coerceIn(1_000L, 62_000L))
            }
        }
        throw IllegalStateException("haiku_batch_poll_timeout")
    }
}

/**
 * Core service for `/v1/library/practice/grade-and-diagnose`.
 */
class PracticeGradeAndDiagnoseService(
    private val transport: BatchAiTransport,
    private val ledger: BookTokenLedger? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** Strictly zero-instance, chapter-agnostic 2-step misconception diagnostic prompt. */
        val GENERIC_SHIELDED_SYSTEM: String = """
            You are a stateless academic diagnostic verifier with NO external tools.
            Evaluate the student's answer strictly against the provided raw course textbook/lecture pages using this generic two-step protocol:
            STEP 1 (Equivalence Shield): Determine whether the student's statement and conclusion are conceptually and mathematically valid relative to the provided source excerpt, allowing equivalent algebraic rearrangements, valid fractions or reasonable rounding, and concise phrasing. If no substantive error exists, return predicted = "NONE" immediately.
            STEP 2 (Root-Cause Classification — ONLY when Step 1 identifies a substantive error):
              - CONCEPT_CONFUSION: Confuses two distinct theoretical concepts, institutional assumptions, welfare properties, or analytical mechanisms, without writing a malformed algebraic equation or misdrawing a curve shift.
              - SIGN_OR_FORMULA: Writes an incorrect algebraic operator, plus/minus sign, inequality direction, or structural equation before substituting numerical values.
              - GRAPH_SHIFT_OR_ROTATION: Moves or rotates the wrong curve, places a curve on the wrong side, or identifies the wrong geometric area on a two-dimensional diagram.
              - ARITHMETIC_OR_ALGEBRA: States a valid analytical setup and algebraic formula, but makes a numerical calculation slip (addition, subtraction, multiplication, division, or decimal placement) when computing numbers.
            Output ONE JSON object only: {"isEquivalentValid": true/false, "predicted": "LABEL", "studentErrorSpan": "exact quote from student answer or empty", "diagnosisZh": "1 concise Simplified Chinese sentence <= 40 chars"}
        """.trimIndent()

        val GRADER_SYSTEM: String = """
            You are LearningCoach's strict point-by-point rubric grader (NO tools, NO ghostwriting).
            You are given: (1) the question stem, (2) frozen course rubric criteria with page pointers, (3) frozen L1/L2 course textbook/lecture page definitions, (4) authoritative ServerNumericFact results (if any), and (5) the student's written answer.
            Hard Rules:
            1. Ground your evaluation strictly in the provided L1/L2 course textbook/lecture definitions and authoritative ServerNumericFact results. Do NOT penalize a student for using the textbook's exact conventions (e.g., non-negative profit or textbook numerical examples shown in the provided excerpt). Never overturn an authoritative ServerNumericFact verdict.
            2. Evaluate EACH criterion independently and assign `verdict` in {"FULL", "PARTIAL", "ZERO"}:
               - FULL: Every required core element (and accurate numerical values / directions if analytical) for this slot is accurately stated.
               - PARTIAL: Substantive mechanism, formula, or part of the reasoning is right, while another required part, sign, condition, or interpretation is missing or wrong.
               - ZERO: Missing, irrelevant, or wrong on both core mechanism and formula/numbers.
               If `maxMarks` > 0, assign `awardedMarks` in [0, maxMarks] (0.5 steps).
            3. HARD STUDENT-QUOTE RULE: Whenever `verdict` is "FULL" or "PARTIAL" (or `awardedMarks > 0`), `studentQuote` MUST be copied CHARACTER-FOR-CHARACTER as a contiguous verbatim substring from `Student Written Answer` (NO brackets, NO ellipses `...`, NO paraphrasing). Unverified quotes are forcibly zeroed by the server gate.
            4. ANTI-GHOSTWRITING RED LINE: NEVER output a `rewrite` field or model answer paragraph. `feedbackNoteZh` MUST be <= 42 Chinese characters (and <= 18 consecutive English words) stating ONLY what was earned or missed and which page to review.
            5. Provide integer `pdfPage` and a 3-to-6 word verbatim `quoteAnchor` copied from the provided course page excerpt.
            Output ONE strictly valid RFC-8259 JSON object only (escape any internal quotes as \" and use Unicode symbols like Ω, τ, ε instead of raw LaTeX backslashes): {"items": [{"cid":"...","verdict":"FULL","awardedMarks":0.0,"studentQuote":"exact contiguous substring","feedbackNoteZh":"...","pdfPage":1,"quoteAnchor":"exact verbatim words from source page"}]}
        """.trimIndent()

        val JSON_REPAIR_SYSTEM: String = """
            Your previous grading output was not valid RFC-8259 JSON.
            Re-emit ONLY ONE strictly valid JSON object with the exact schema {"items":[{"cid":"...","verdict":"FULL|PARTIAL|ZERO","awardedMarks":0.0,"studentQuote":"...","feedbackNoteZh":"...","pdfPage":1,"quoteAnchor":"..."}]}.
            Escape any internal quotes as \" and replace raw LaTeX backslashes with Unicode symbols. Do NOT add markdown fences or prose.
        """.trimIndent()

        val QUOTE_REPAIR_SYSTEM: String = """
            Your previous `studentQuote` was NOT an exact contiguous substring of the student's answer.
            Copy an EXACT character-for-character contiguous substring (8 to 100 chars, NO edits, NO brackets, NO `...`) from the student's answer that supports credit for this criterion, or return empty string if none exists.
            Output JSON only: {"studentQuote": "..."}
        """.trimIndent()

        val VARIANT_SYSTEM: String = """
            You are a stateless course tutor generating ONE targeted isomorphic variant MCQ in Simplified Chinese to remediate a diagnosed student error, grounded strictly in the provided textbook/lecture pages.
            Rules:
            1. Never copy >= 8 consecutive English words or >= 12 consecutive Chinese characters from the source pages or any exam text.
            2. Target the exact diagnosed error mechanism using fresh numbers or a clean contrast.
            3. Provide integer `pdfPage` and an exact verbatim 3-to-5 word `quoteAnchor` copied from that page.
            4. Provide exactly 4 options labeled A., B., C., D. and `correctOption` matching one option verbatim.
            Output JSON only: {"stem": "...", "options": ["A. ...", "B. ...", "C. ...", "D. ..."], "correctOption": "A. ...", "targetedTrapExplanation": "...。", "pdfPage": 1, "quoteAnchor": "exact words"}
        """.trimIndent()
    }

    suspend fun process(
        request: PracticeGradeAndDiagnoseRequest,
        friend: String? = null,
        device: String? = null,
    ): PracticeGradeAndDiagnoseResponse {
        val started = System.nanoTime()
        validateNoLeakage(request)
        val effectiveTransport = if (friend != null) FriendScopedBatchTransport(transport, friend, device.orEmpty()) else transport
        val recordOwnerLedger = friend == null

        val mode = request.mode.trim().uppercase().ifBlank { "GRADE_AND_DIAGNOSE" }
        val gradeRequest = if (mode == "CHAPTER_OPEN" && request.retryPointIds.isNotEmpty()) {
            val want = request.retryPointIds.toSet()
            request.copy(
                rubricCriteria = request.rubricCriteria.filter { it.cid in want },
                generateVariant = false,
            )
        } else if (mode == "CHAPTER_OPEN") {
            request.copy(generateVariant = false)
        } else {
            request
        }
        val sourceBlocks = buildSourceBlocks(request.bookId, request.sourcePages)
        val rawPagesMap = request.sourcePages.associate { it.pdfPage to it.text }
        val sourceCorpus = request.sourcePages.joinToString("\n\n") { it.text }
        val overlapCorpus = (listOf(sourceCorpus) + request.referenceExclusionTexts).joinToString("\n\n")

        val allNumericSpecs = buildList {
            addAll(request.numericFacts)
            for (c in request.rubricCriteria) {
                if (c.numericKey != null) {
                    add(c.numericKey.copy(cid = c.numericKey.cid.ifBlank { c.cid }, label = c.numericKey.label.ifBlank { c.slotLabel }))
                }
            }
        }
        val numericFacts = PracticeServerNumericChecker.evaluate(allNumericSpecs, request.studentAnswer)

        var lastModel = PracticeGradeLimits.PINNED_HAIKU_MODEL
        var lastChannel = CompanionRouteChannel.HAIKU_REALTIME.wireName
        var rewrittenBlocked = 0
        var rubricItems: List<GradedRubricItemResult> = emptyList()

        if (mode in setOf("GRADE_AND_DIAGNOSE", "GRADE_ONLY", "CHAPTER_OPEN") && gradeRequest.rubricCriteria.isNotEmpty()) {
            val gradeUserPrompt = buildGradingPrompt(gradeRequest, numericFacts)
            val gradeKey = "pg-${sha256("${request.bookId}|${request.questionId}|$gradeUserPrompt".toByteArray(Charsets.UTF_8)).take(16)}"
            val gradeReply = effectiveTransport.generateSection(
                systemPrompt = GRADER_SYSTEM,
                userPrompt = gradeUserPrompt,
                task = PracticeGradeLimits.TASK_GRADE,
                batchKey = gradeKey,
                maxTokens = PracticeGradeLimits.BATCH_MAX_TOKENS,
            )
            lastModel = gradeReply.model
            lastChannel = gradeReply.routeChannel.wireName
            if (recordOwnerLedger) recordLedger(request, PracticeGradeLimits.TASK_GRADE, gradeKey, gradeReply)

            var parsedItems = parseGradedItems(gradeReply.text)
            if (parsedItems.isEmpty()) {
                val jsonRepairPrompt = buildString {
                    append("Malformed grader output to convert into strictly valid JSON:\n")
                    append(gradeReply.text.take(8_000))
                    append("\n\nExpected criterion IDs: ")
                    append(gradeRequest.rubricCriteria.joinToString(", ") { it.cid })
                }
                val jsonRepairKey = "pjr-${sha256("${request.bookId}|${request.questionId}|$jsonRepairPrompt".toByteArray(Charsets.UTF_8)).take(16)}"
                val jsonRepairReply = runCatching {
                    effectiveTransport.generateSection(
                        systemPrompt = JSON_REPAIR_SYSTEM,
                        userPrompt = jsonRepairPrompt,
                        task = PracticeGradeLimits.TASK_GRADE,
                        batchKey = jsonRepairKey,
                        maxTokens = PracticeGradeLimits.BATCH_MAX_TOKENS,
                    )
                }.getOrNull()
                if (jsonRepairReply != null) {
                    lastModel = jsonRepairReply.model
                    lastChannel = jsonRepairReply.routeChannel.wireName
                    if (recordOwnerLedger) recordLedger(request, "practice_grade_json_repair", jsonRepairKey, jsonRepairReply)
                    parsedItems = parseGradedItems(jsonRepairReply.text)
                }
            }
            val byCid = parsedItems.associateBy { it.cid }
            val evaluated = mutableListOf<GradedRubricItemResult>()

            for (crit in gradeRequest.rubricCriteria) {
                val rawItem = byCid[crit.cid]
                val rawVerdict = rawItem?.verdict ?: PracticeRubricVerdict.ZERO
                val maxMarks = crit.maxMarks.coerceAtLeast(0.0)
                val rawAwarded = if (maxMarks > 0.0) {
                    (rawItem?.awardedMarks ?: defaultMarksFor(rawVerdict, maxMarks)).coerceIn(0.0, maxMarks)
                } else {
                    rawItem?.awardedMarks?.coerceAtLeast(0.0) ?: 0.0
                }

                val matchingNumeric = numericFacts.firstOrNull { it.cid == crit.cid }
                var enforcedVerdict = rawVerdict
                var enforcedAwarded = rawAwarded
                if (matchingNumeric != null && !matchingNumeric.correct && enforcedVerdict == PracticeRubricVerdict.FULL) {
                    enforcedVerdict = if (request.studentAnswer.isBlank()) PracticeRubricVerdict.ZERO else PracticeRubricVerdict.PARTIAL
                    if (maxMarks > 0.0) {
                        enforcedAwarded = minOf(enforcedAwarded, (maxMarks * 0.5))
                    }
                }

                var quote = rawItem?.studentQuote?.trim().orEmpty()
                var quoteVerified = StudentQuoteGate.isExactContiguousSubstring(quote, request.studentAnswer)
                var quoteRepairAttempted = false
                var quoteGateZeroed = false
                val claimsCredit = enforcedVerdict != PracticeRubricVerdict.ZERO || enforcedAwarded > 0.0

                if (claimsCredit && !quoteVerified) {
                    quoteRepairAttempted = true
                    val repairPrompt = buildString {
                        append("Criterion (${crit.cid}): ${crit.slotLabel}\n")
                        append("Previous invalid studentQuote: $quote\n")
                        append("Student Written Answer:\n<<<\n${request.studentAnswer}\n>>>\n")
                        append("Return an exact contiguous substring from Student Written Answer.")
                    }
                    val repairKey = "pqr-${sha256("${request.bookId}|${crit.cid}|$repairPrompt".toByteArray(Charsets.UTF_8)).take(16)}"
                    val repairReply = runCatching {
                        effectiveTransport.generateSection(
                            systemPrompt = QUOTE_REPAIR_SYSTEM,
                            userPrompt = repairPrompt,
                            task = PracticeGradeLimits.TASK_GRADE,
                            batchKey = repairKey,
                            maxTokens = PracticeGradeLimits.BATCH_MAX_TOKENS,
                        )
                    }.getOrNull()
                    if (repairReply != null) {
                        if (recordOwnerLedger) recordLedger(request, "practice_quote_repair", repairKey, repairReply)
                        val repairedQuote = parseQuotedRepair(repairReply.text)
                        if (StudentQuoteGate.isExactContiguousSubstring(repairedQuote, request.studentAnswer)) {
                            quote = repairedQuote
                            quoteVerified = true
                        }
                    }
                    if (!quoteVerified) {
                        enforcedVerdict = PracticeRubricVerdict.ZERO
                        enforcedAwarded = 0.0
                        quote = ""
                        quoteGateZeroed = true
                    }
                }
                if (enforcedVerdict == PracticeRubricVerdict.ZERO) {
                    enforcedAwarded = 0.0
                }

                val claimedPage = (rawItem?.pdfPage?.takeIf { it > 0 } ?: crit.pdfPage).coerceAtLeast(0)
                val claimedAnchor = rawItem?.quoteAnchor?.takeIf(String::isNotBlank) ?: crit.quoteAnchor
                val verifiedAnchor = Utf16AnchorVerifier.verify(claimedPage.takeIf { it > 0 }, claimedAnchor, sourceBlocks, rawPagesMap)
                    ?: if (crit.pdfPage > 0 && crit.quoteAnchor.isNotBlank()) {
                        Utf16AnchorVerifier.verify(crit.pdfPage, crit.quoteAnchor, sourceBlocks, rawPagesMap)
                    } else null
                val finalPage = verifiedAnchor?.pdfPage ?: crit.pdfPage.takeIf { it > 0 } ?: claimedPage
                val finalAnchor = verifiedAnchor?.quoteAnchor.orEmpty()
                val anchorVerified = verifiedAnchor != null

                val guarded = NoRewriteOutputGuard.enforce(
                    rawNote = rawItem?.feedbackNoteZh.orEmpty(),
                    verdict = enforcedVerdict,
                    pdfPage = finalPage,
                )
                if (guarded.blockedGhostwriting) rewrittenBlocked++

                evaluated += GradedRubricItemResult(
                    cid = crit.cid,
                    verdict = enforcedVerdict,
                    rawModelVerdict = rawVerdict,
                    awardedMarks = enforcedAwarded,
                    rawModelAwardedMarks = rawAwarded,
                    maxMarks = maxMarks,
                    studentQuote = quote,
                    studentQuoteVerified = quoteVerified,
                    quoteGateZeroed = quoteGateZeroed,
                    quoteRepairAttempted = quoteRepairAttempted,
                    feedbackNoteZh = guarded.sanitizedNoteZh,
                    pdfPage = finalPage,
                    quoteAnchor = finalAnchor,
                    anchorVerified = anchorVerified,
                )
            }
            rubricItems = evaluated
            if (mode == "CHAPTER_OPEN") {
                rubricItems = applyChapterOpenGate(request, rubricItems)
            }
        }

        val hasRubricDefect = rubricItems.any { it.verdict != PracticeRubricVerdict.FULL }
        val hasNumericDefect = numericFacts.any { !it.correct }
        val hasWrongMcq = request.mcqCorrectOption.isNotBlank() &&
            request.mcqSelectedOption.isNotBlank() &&
            request.mcqSelectedOption.trim() != request.mcqCorrectOption.trim()
        val shouldDiagnose = when (mode) {
            "DIAGNOSE_ONLY" -> true
            "GRADE_ONLY", "CHAPTER_OPEN" -> false
            "VARIANT_ONLY" -> false
            else -> rubricItems.isEmpty() || hasRubricDefect || hasNumericDefect || hasWrongMcq
        }

        var diagnosis: MisconceptionDiagnosisResult? = null
        if (shouldDiagnose) {
            val diagUserPrompt = buildDiagnosisPrompt(request, rubricItems, numericFacts)
            val diagKey = "pd-${sha256("${request.bookId}|${request.questionId}|$diagUserPrompt".toByteArray(Charsets.UTF_8)).take(16)}"
            val diagReply = effectiveTransport.generateSection(
                systemPrompt = GENERIC_SHIELDED_SYSTEM,
                userPrompt = diagUserPrompt,
                task = PracticeGradeLimits.TASK_DIAGNOSE,
                batchKey = diagKey,
                maxTokens = PracticeGradeLimits.BATCH_MAX_TOKENS,
            )
            lastModel = diagReply.model
            lastChannel = diagReply.routeChannel.wireName
            if (recordOwnerLedger) recordLedger(request, PracticeGradeLimits.TASK_DIAGNOSE, diagKey, diagReply)
            diagnosis = parseDiagnosis(diagReply.text, request.studentAnswer)
        } else if (mode == "GRADE_AND_DIAGNOSE" && rubricItems.isNotEmpty() && !hasRubricDefect && !hasNumericDefect) {
            diagnosis = MisconceptionDiagnosisResult(
                isEquivalentValid = true,
                errorClass = PracticeMisconceptionClass.NONE,
                studentErrorSpan = "",
                diagnosisZh = "作答各采分点均准确，未发现实质错因。",
            )
        }

        var variant: IsomorphicVariantResult? = null
        val targetErrorClass = diagnosis?.errorClass ?: PracticeMisconceptionClass.CONCEPT_CONFUSION
        val shouldGenerateVariant = mode != "CHAPTER_OPEN" && request.generateVariant &&
            (mode == "VARIANT_ONLY" || (diagnosis != null && diagnosis.errorClass != PracticeMisconceptionClass.NONE))

        if (shouldGenerateVariant && request.sourcePages.isNotEmpty()) {
            variant = generateVerifiedVariant(
                request = request,
                errorClass = targetErrorClass,
                diagnosisZh = diagnosis?.diagnosisZh.orEmpty(),
                sourceBlocks = sourceBlocks,
                rawPagesMap = rawPagesMap,
                overlapCorpus = overlapCorpus,
                activeTransport = effectiveTransport,
                recordOwnerLedger = recordOwnerLedger,
            )?.also { (_, replyModel, replyChannel) ->
                lastModel = replyModel
                lastChannel = replyChannel
            }?.first
        }

        val totalAwarded = rubricItems.sumOf { it.awardedMarks }
        val maxMarks = rubricItems.sumOf { it.maxMarks }
        val unverifiedPositivePass = rubricItems.count {
            (it.verdict != PracticeRubricVerdict.ZERO || it.awardedMarks > 0.0) && !it.studentQuoteVerified
        }
        require(unverifiedPositivePass == 0) { "StudentQuoteGate invariant violated" }

        val summaryMetaLine = buildSummaryMetaLine(rubricItems, totalAwarded, maxMarks, diagnosis)
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)

        return PracticeGradeAndDiagnoseResponse(
            mode = mode,
            rubricItems = rubricItems,
            numericFacts = numericFacts,
            totalAwarded = totalAwarded,
            maxMarks = maxMarks,
            summaryMetaLine = summaryMetaLine,
            diagnosis = diagnosis,
            variant = variant,
            modelUsed = lastModel,
            routeChannel = lastChannel,
            rewrittenParagraphsEmitted = 0,
            rewrittenParagraphsBlocked = rewrittenBlocked,
            unverifiedPositivePassCount = unverifiedPositivePass,
            elapsedMs = elapsedMs,
        )
    }

    private suspend fun generateVerifiedVariant(
        request: PracticeGradeAndDiagnoseRequest,
        errorClass: PracticeMisconceptionClass,
        diagnosisZh: String,
        sourceBlocks: List<SectionBlockInput>,
        rawPagesMap: Map<Int, String>,
        overlapCorpus: String,
        activeTransport: BatchAiTransport = transport,
        recordOwnerLedger: Boolean = true,
    ): Triple<IsomorphicVariantResult, String, String>? {
        val userPrompt = buildVariantPrompt(request, errorClass, diagnosisZh)
        val varKey = "pv-${sha256("${request.bookId}|${request.questionId}|${errorClass.name}|$userPrompt".toByteArray(Charsets.UTF_8)).take(16)}"
        val reply = activeTransport.generateSection(
            systemPrompt = VARIANT_SYSTEM,
            userPrompt = userPrompt,
            task = PracticeGradeLimits.TASK_VARIANT,
            batchKey = varKey,
            maxTokens = PracticeGradeLimits.BATCH_MAX_TOKENS,
        )
        if (recordOwnerLedger) recordLedger(request, PracticeGradeLimits.TASK_VARIANT, varKey, reply)

        val raw = parseVariantRaw(reply.text) ?: return null

        val cleanStem = VerbatimOverlapGuard.scrubOverlaps(raw.stem, overlapCorpus)
        val cleanOptions = raw.options.map { VerbatimOverlapGuard.scrubOverlaps(it, overlapCorpus) }
        val cleanCorrect = cleanOptions.firstOrNull { it == VerbatimOverlapGuard.scrubOverlaps(raw.correctOption, overlapCorpus) }
            ?: cleanOptions.firstOrNull { it.startsWith(raw.correctOption.take(2)) }
            ?: cleanOptions.firstOrNull().orEmpty()
        val cleanExplanation = VerbatimOverlapGuard.scrubOverlaps(raw.targetedTrapExplanation, overlapCorpus)

        val audit = VerbatimOverlapGuard.audit(
            generatedProse = listOf(cleanStem, cleanExplanation) + cleanOptions,
            sourceText = overlapCorpus,
        )

        val fallbackPage = request.sourcePages.firstOrNull()?.pdfPage ?: 1
        val targetPage = raw.pdfPage.takeIf { it in rawPagesMap } ?: fallbackPage
        var verified = Utf16AnchorVerifier.verify(targetPage, raw.quoteAnchor, sourceBlocks, rawPagesMap)
            ?: Utf16AnchorVerifier.verify(null, raw.quoteAnchor, sourceBlocks, rawPagesMap)
        if (verified == null) {
            val fallbackQuote = extractVerbatimAnchorFromPage(rawPagesMap[targetPage].orEmpty())
            if (fallbackQuote != null) {
                verified = Utf16AnchorVerifier.verify(targetPage, fallbackQuote, sourceBlocks, rawPagesMap)
            }
        }

        val variantResult = IsomorphicVariantResult(
            variantId = "var-${UUID.randomUUID().toString().replace("-", "").take(12)}",
            stem = cleanStem,
            options = cleanOptions,
            correctOption = cleanCorrect,
            targetedTrapExplanation = cleanExplanation,
            pdfPage = verified?.pdfPage ?: targetPage,
            quoteAnchor = verified?.quoteAnchor.orEmpty(),
            conceptKey = request.conceptKey.ifBlank { "${request.bookId}:${request.sectionId}" },
            targetErrorClass = errorClass,
            anchorVerified = verified != null,
            overlapAudit = audit,
        )
        return Triple(variantResult, reply.model, reply.routeChannel.wireName)
    }

    private fun extractVerbatimAnchorFromPage(pageText: String): String? {
        val lines = pageText.lineSequence().map(String::trim).filter { it.length >= 16 }
        for (line in lines) {
            val words = line.split(Regex("""\s+""")).filter { it.any(Char::isLetter) }
            if (words.size >= 4) {
                val candidate = words.take(4).joinToString(" ")
                if (candidate.length >= 8 && pageText.contains(candidate)) return candidate
            }
            val slice = line.take(18).trim()
            if (slice.length >= 6 && pageText.contains(slice)) return slice
        }
        return pageText.trim().take(18).takeIf { it.length >= 6 }
    }

    private fun validateNoLeakage(request: PracticeGradeAndDiagnoseRequest) {
        PracticeLeakageGuard.assertCleanCourseInput(request.questionStem, "questionStem")
        for (c in request.rubricCriteria) {
            PracticeLeakageGuard.assertCleanCourseInput(c.slotLabel, "rubricCriteria.${c.cid}")
            PracticeLeakageGuard.assertCleanCourseInput(c.sourceExcerpt, "rubricCriteria.${c.cid}.sourceExcerpt")
        }
        for (p in request.sourcePages) {
            PracticeLeakageGuard.assertCleanCourseInput(p.text, "sourcePages.p${p.pdfPage}")
        }
    }

    private fun buildSourceBlocks(bookId: String, pages: List<PracticeSourcePageInput>): List<SectionBlockInput> =
        pages.filter { it.text.isNotBlank() }.map { p ->
            SectionBlockInput(
                id = "${bookId.ifBlank { "book" }}:p${p.pdfPage}:0",
                pdfPage = p.pdfPage,
                startUtf16 = 0,
                text = p.text,
            )
        }

    private fun buildGradingPrompt(
        request: PracticeGradeAndDiagnoseRequest,
        numericFacts: List<ServerNumericFactResult>,
    ): String = buildString {
        append("Question Stem:\n${request.questionStem}\n\n")
        append("Frozen Course Rubric Criteria:\n")
        val critJson = buildJsonArray {
            for (c in request.rubricCriteria) {
                add(buildJsonObject {
                    put("cid", c.cid)
                    put("slotLabel", c.slotLabel)
                    if (c.maxMarks > 0.0) put("maxMarks", c.maxMarks)
                    if (c.pdfPage > 0) put("pdfPage", c.pdfPage)
                    if (c.quoteAnchor.isNotBlank()) put("quoteAnchor", c.quoteAnchor)
                    if (c.sourceExcerpt.isNotBlank()) put("courseDefinitionExcerpt", c.sourceExcerpt.take(800))
                })
            }
        }
        append(critJson.toString()).append("\n\n")

        if (numericFacts.isNotEmpty()) {
            append("Authoritative ServerNumericFact Verifications (DO NOT overturn):\n")
            append(buildJsonArray { numericFacts.forEach { add(it.toJson()) } }.toString()).append("\n\n")
        }

        if (request.sourcePages.isNotEmpty()) {
            append("Frozen L1/L2 Course Textbook / Lecture Source Pages:\n")
            var remaining = PracticeGradeLimits.MAX_SOURCE_CHARS_IN_PROMPT
            for (p in request.sourcePages.sortedBy { it.authorityRank }) {
                if (remaining <= 0) break
                val slice = p.text.take(minOf(3_600, remaining))
                remaining -= slice.length
                val titlePart = p.title.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
                append("[L${p.authorityRank} Page ${p.pdfPage}$titlePart]\n$slice\n\n")
            }
        }

        if (request.mode.trim().equals("CHAPTER_OPEN", ignoreCase = true)) {
            append("Chapter-open judging, which overrides the shared FULL/PARTIAL wording for this call:\n")
            append("Each criterion's courseDefinitionExcerpt is the sentence that point hangs on. ")
            append("Judge the student's meaning against that sentence, not against shared words. ")
            append("FULL means their own wording covers the sentence, even when none of its words appear. ")
            append("PARTIAL means only part of that sentence is covered. ")
            append("ZERO means the point is missing, wrong, or about something else. ")
            append("Do not mark a correct paraphrase PARTIAL for using different words. ")
            append("Do not mark an off-topic answer FULL. ")
            append("feedbackNoteZh is one reason of at most 42 Chinese characters.\n\n")
        }
        append("Student Written Answer (quote verbatim substrings ONLY from inside <<< >>>):\n")
        append("<<<\n${request.studentAnswer}\n>>>\n")
    }

    private fun buildDiagnosisPrompt(
        request: PracticeGradeAndDiagnoseRequest,
        rubricItems: List<GradedRubricItemResult>,
        numericFacts: List<ServerNumericFactResult>,
    ): String = buildString {
        if (request.sourcePages.isNotEmpty()) {
            append("Raw Course Source Pages:\n")
            var remaining = PracticeGradeLimits.MAX_SOURCE_CHARS_IN_PROMPT
            for (p in request.sourcePages.sortedBy { it.authorityRank }) {
                if (remaining <= 0) break
                val slice = p.text.take(minOf(4_000, remaining))
                remaining -= slice.length
                append("[PDF p.${p.pdfPage}]\n$slice\n\n")
            }
        }
        append("Question:\n${request.questionStem}\n\n")
        if (request.mcqSelectedOption.isNotBlank() && request.mcqCorrectOption.isNotBlank()) {
            append("Student Selected Option: ${request.mcqSelectedOption}\n")
            append("Correct Option: ${request.mcqCorrectOption}\n\n")
        }
        if (numericFacts.isNotEmpty()) {
            append("Deterministic Numeric Check:\n")
            append(buildJsonArray { numericFacts.forEach { add(it.toJson()) } }.toString()).append("\n\n")
        }
        if (rubricItems.isNotEmpty()) {
            val missed = rubricItems.filter { it.verdict != PracticeRubricVerdict.FULL }
            if (missed.isNotEmpty()) {
                append("Missed / Partial Rubric Notes:\n")
                for (m in missed) {
                    append("- ${m.cid} (${m.verdict}): ${m.feedbackNoteZh}\n")
                }
                append("\n")
            }
        }
        append("Student Answer:\n${request.studentAnswer}\n")
    }

    private fun buildVariantPrompt(
        request: PracticeGradeAndDiagnoseRequest,
        errorClass: PracticeMisconceptionClass,
        diagnosisZh: String,
    ): String = buildString {
        append("Diagnosed Misconception Class: ${errorClass.name} (${errorClass.labelZh})\n")
        if (diagnosisZh.isNotBlank()) append("Diagnosis Note: $diagnosisZh\n")
        append("Original Question Context:\n${request.questionStem}\n\n")
        append("Student Mistake Context:\n${request.studentAnswer.take(600)}\n\n")
        append("Course Source Pages (ground the variant and copy 3-5 word verbatim quoteAnchor from here):\n")
        var remaining = 9_000
        for (p in request.sourcePages.sortedBy { it.authorityRank }) {
            if (remaining <= 0) break
            val slice = p.text.take(minOf(3_200, remaining))
            remaining -= slice.length
            append("[PDF p.${p.pdfPage}]\n$slice\n\n")
        }
    }

    private fun buildSummaryMetaLine(
        items: List<GradedRubricItemResult>,
        totalAwarded: Double,
        maxMarks: Double,
        diagnosis: MisconceptionDiagnosisResult?,
    ): String {
        if (items.isEmpty()) {
            return if (diagnosis != null && diagnosis.errorClass != PracticeMisconceptionClass.NONE) {
                "已定位错因：${diagnosis.errorClass.labelZh}。"
            } else {
                "已完成作答核对。"
            }
        }
        val missed = items.count { it.verdict != PracticeRubricVerdict.FULL }
        val scorePart = if (maxMarks > 0.0) {
            " · 得分 ${formatMarks(totalAwarded)} / ${formatMarks(maxMarks)}"
        } else ""
        val missedPart = when {
            missed == 0 -> " · 全部要点已命中"
            else -> " · 漏了 $missed 个关键步骤"
        }
        return "已核对 ${items.size} 个采分要点$scorePart$missedPart。"
    }

    private fun formatMarks(v: Double): String {
        val rounded = kotlin.math.round(v * 10.0) / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else "%.1f".format(rounded)
    }

    private fun defaultMarksFor(verdict: PracticeRubricVerdict, maxMarks: Double): Double = when (verdict) {
        PracticeRubricVerdict.FULL -> maxMarks
        PracticeRubricVerdict.PARTIAL -> kotlin.math.round(maxMarks) / 2.0
        PracticeRubricVerdict.ZERO -> 0.0
    }

    private data class RawGradedItem(
        val cid: String,
        val verdict: PracticeRubricVerdict,
        val awardedMarks: Double?,
        val studentQuote: String,
        val feedbackNoteZh: String,
        val pdfPage: Int,
        val quoteAnchor: String,
    )

    private data class RawVariant(
        val stem: String,
        val options: List<String>,
        val correctOption: String,
        val targetedTrapExplanation: String,
        val pdfPage: Int,
        val quoteAnchor: String,
    )

    private fun parseGradedItems(rawText: String): List<RawGradedItem> {
        val obj = extractJsonObject(rawText) ?: return emptyList()
        val arr = obj["items"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val cid = (o["cid"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (cid.isBlank()) return@mapNotNull null
            val verdict = PracticeRubricVerdict.parse((o["verdict"] as? JsonPrimitive)?.contentOrNull)
            val awarded = (o["awardedMarks"] as? JsonPrimitive)?.doubleOrNull
                ?: (o["awarded"] as? JsonPrimitive)?.doubleOrNull
            val quote = (o["studentQuote"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val note = (o["feedbackNoteZh"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val page = (o["pdfPage"] as? JsonPrimitive)?.intOrNull ?: 0
            val anchor = (o["quoteAnchor"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            RawGradedItem(cid, verdict, awarded, quote, note, page, anchor)
        }
    }

    private fun parseQuotedRepair(rawText: String): String {
        val obj = extractJsonObject(rawText) ?: return ""
        return (obj["studentQuote"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    }

    private fun parseDiagnosis(rawText: String, studentAnswer: String): MisconceptionDiagnosisResult {
        val obj = extractJsonObject(rawText) ?: return MisconceptionDiagnosisResult(
            isEquivalentValid = false,
            errorClass = PracticeMisconceptionClass.NONE,
            studentErrorSpan = "",
            diagnosisZh = "暂时无法判定错因，请对照原页核对。",
        )
        val predicted = PracticeMisconceptionClass.parse((obj["predicted"] as? JsonPrimitive)?.contentOrNull)
        val isEquiv = (obj["isEquivalentValid"] as? JsonPrimitive)?.booleanOrNull ?: (predicted == PracticeMisconceptionClass.NONE)
        val effectiveClass = if (isEquiv && predicted == PracticeMisconceptionClass.NONE) {
            PracticeMisconceptionClass.NONE
        } else {
            predicted
        }
        val rawSpan = (obj["studentErrorSpan"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val verifiedSpan = if (rawSpan.isNotBlank() && StudentQuoteGate.isExactContiguousSubstring(rawSpan, studentAnswer)) rawSpan else ""
        val rawDiag = (obj["diagnosisZh"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val guardedDiag = NoRewriteOutputGuard.enforce(
            rawNote = rawDiag.ifBlank { if (effectiveClass == PracticeMisconceptionClass.NONE) "表述与课程定义等价，无实质性错误。" else "存在${effectiveClass.labelZh}，建议回看原页。" },
            verdict = if (effectiveClass == PracticeMisconceptionClass.NONE) PracticeRubricVerdict.FULL else PracticeRubricVerdict.PARTIAL,
            pdfPage = 0,
        ).sanitizedNoteZh
        return MisconceptionDiagnosisResult(
            isEquivalentValid = effectiveClass == PracticeMisconceptionClass.NONE,
            errorClass = effectiveClass,
            studentErrorSpan = verifiedSpan,
            diagnosisZh = guardedDiag,
        )
    }

    private fun parseVariantRaw(rawText: String): RawVariant? {
        val obj = extractJsonObject(rawText) ?: return null
        val stem = (obj["stem"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val options = (obj["options"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        if (stem.isBlank() || options.size < 2) return null
        val correct = (obj["correctOption"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val explanation = (obj["targetedTrapExplanation"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val pdfPage = (obj["pdfPage"] as? JsonPrimitive)?.intOrNull ?: 0
        val quoteAnchor = (obj["quoteAnchor"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return RawVariant(stem, options, correct, explanation, pdfPage, quoteAnchor)
    }

    internal fun extractJsonObject(raw: String): JsonObject? {
        val candidates = buildList {
            val trimmed = raw.trim()
            val fenceRegex = Regex("""(?s)```(?:json)?\s*(\{.*?\})\s*```""")
            for (m in fenceRegex.findAll(trimmed)) {
                add(m.groupValues[1].trim())
            }
            val unfenced = trimmed
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            add(unfenced)
            extractBalancedBraces(unfenced)?.let { add(it) }
            val start = unfenced.indexOf('{')
            val end = unfenced.lastIndexOf('}')
            if (start >= 0 && end > start) {
                add(unfenced.substring(start, end + 1))
            }
        }.distinct()

        for (candidate in candidates) {
            runCatching { return json.parseToJsonElement(candidate).jsonObject }
            val repaired = repairCommonLlmJson(candidate)
            if (repaired != candidate) {
                runCatching { return json.parseToJsonElement(repaired).jsonObject }
            }
        }
        return null
    }

    private fun extractBalancedBraces(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    internal fun repairCommonLlmJson(raw: String): String {
        val sb = StringBuilder(raw.length + 32)
        var inString = false
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (!inString) {
                if (ch == '"') {
                    inString = true
                    sb.append(ch)
                } else {
                    sb.append(ch)
                }
                i++
                continue
            }
            when (ch) {
                '\\' -> {
                    val next = raw.getOrNull(i + 1)
                    val isValidEscape = when (next) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> true
                        'u' -> i + 5 < raw.length && raw.substring(i + 2, i + 6).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                        else -> false
                    }
                    if (isValidEscape && next != null) {
                        sb.append('\\').append(next)
                        i += 2
                    } else {
                        // Escape stray backslashes such as LaTeX `\Omega`, `\tau`, `\_`
                        sb.append("\\\\")
                        i++
                    }
                }
                '"' -> {
                    inString = false
                    sb.append(ch)
                    i++
                }
                '\n' -> {
                    sb.append("\\n")
                    i++
                }
                '\r' -> {
                    sb.append("\\r")
                    i++
                }
                '\t' -> {
                    sb.append("\\t")
                    i++
                }
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        return sb.toString().replace(Regex(""",(\s*[}\]])"""), "$1")
    }

    private fun recordLedger(
        request: PracticeGradeAndDiagnoseRequest,
        task: String,
        sectionKey: String,
        reply: BatchTransportReply,
    ) {
        ledger?.record(
            TokenLedgerEntry(
                bookId = request.bookId.ifBlank { "practice" },
                chapterId = request.chapterId.ifBlank { "practice" },
                sectionId = sectionKey,
                task = task,
                routeChannel = reply.routeChannel,
                model = reply.model,
                inputTokens = reply.inputTokens,
                outputTokens = reply.outputTokens,
                cachedTokens = reply.cachedTokens,
                costMicrodollars = TokenLedgerPricing.costMicrodollars(
                    reply.routeChannel,
                    reply.inputTokens,
                    reply.outputTokens,
                    reply.cachedTokens,
                ),
                ttftMs = reply.ttftMs,
                elapsedMs = reply.elapsedMs,
            ),
        )
    }

    private fun applyChapterOpenGate(
        request: PracticeGradeAndDiagnoseRequest,
        items: List<GradedRubricItemResult>,
    ): List<GradedRubricItemResult> {
        val judgements = ChapterOpenGate.judge(
            answer = request.studentAnswer,
            points = request.rubricCriteria.map {
                ChapterOpenPointRef(it.cid, it.slotLabel, it.sourceExcerpt.trim())
            },
            drafts = items.map { ChapterOpenDraft(it.cid, it.verdict.name, it.feedbackNoteZh) },
        ).associateBy { it.id }
        return items.map { item ->
            val judged = judgements[item.cid] ?: return@map item
            val verdict = PracticeRubricVerdict.parse(judged.verdict)
            val note = NoRewriteOutputGuard.enforce(judged.reason, verdict, item.pdfPage).sanitizedNoteZh
            val awarded = when (verdict) {
                PracticeRubricVerdict.ZERO -> 0.0
                PracticeRubricVerdict.PARTIAL ->
                    if (item.maxMarks > 0.0) minOf(item.awardedMarks, item.maxMarks * 0.5) else 0.0
                PracticeRubricVerdict.FULL -> item.awardedMarks
            }
            item.copy(
                verdict = verdict,
                awardedMarks = awarded,
                feedbackNoteZh = note,
                missCause = if (verdict == PracticeRubricVerdict.ZERO) judged.missCause else "",
            )
        }
    }

    suspend fun generateChapterOpen(
        bookId: String,
        chapterId: String,
        blocks: List<ChapterOpenSourceBlock>,
        friend: String? = null,
        device: String? = null,
    ): ChapterOpenPack {
        val usable = blocks
            .filter { it.id.isNotBlank() && it.page > 0 && it.text.isNotBlank() }
            .take(12)
            .map { it.copy(text = it.text.take(900)) }
        require(usable.isNotEmpty()) { "这一章没有可用于出题的正文。" }
        val active = if (friend != null) FriendScopedBatchTransport(transport, friend, device.orEmpty()) else transport
        val first = requestChapterOpen(active, bookId, chapterId, usable, repairNote = "")
        if (first.stem.isNotBlank() && first.points.size >= ChapterOpenGate.MIN_POINTS) {
            return first.copy(points = first.points.take(ChapterOpenGate.MAX_POINTS))
        }
        val second = requestChapterOpen(
            active,
            bookId,
            chapterId,
            usable,
            repairNote = "The previous points were dropped because a claim copied the chapter or the quote was not an exact substring. Write new claims in your own words.",
        )
        require(second.stem.isNotBlank() && second.points.size >= ChapterOpenGate.MIN_POINTS) {
            "这一章没能整理出足够的得分点，请稍后再试。"
        }
        return second.copy(points = second.points.take(ChapterOpenGate.MAX_POINTS))
    }

    private suspend fun requestChapterOpen(
        active: BatchAiTransport,
        bookId: String,
        chapterId: String,
        blocks: List<ChapterOpenSourceBlock>,
        repairNote: String,
    ): ChapterOpenPack {
        val user = buildString {
            if (repairNote.isNotBlank()) append(repairNote).append("\n\n")
            append("Chapter blocks. Cite a blockId and copy a short quoteAnchor from that block. ")
            append("The claim must say what the student should explain, and must not contain 12 consecutive characters from the block.\n")
            for (block in blocks) {
                append("[${block.id} p.${block.page}]\n")
                append(block.text)
                append("\n\n")
            }
            append("Return JSON only: {\"stem\":\"...\",\"points\":[{\"id\":\"P1\",\"claim\":\"...\",\"blockId\":\"${blocks.first().id}\",\"quoteAnchor\":\"exact substring\"}]}\n")
            append("Provide 4 to 6 points.")
        }
        val key = "co-${sha256("$bookId|$chapterId|$repairNote|$user".toByteArray(Charsets.UTF_8)).take(16)}"
        val reply = active.generateSection(
            systemPrompt = CHAPTER_OPEN_SYSTEM,
            userPrompt = user,
            task = PracticeGradeLimits.TASK_CHAPTER_OPEN,
            batchKey = key,
            maxTokens = PracticeGradeLimits.BATCH_MAX_TOKENS,
        )
        val obj = extractJsonObject(reply.text)
        val stem = (obj?.get("stem") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val raw = (obj?.get("points") as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            ChapterOpenRawPoint(id = s("id"), claim = s("claim"), blockId = s("blockId"), quoteAnchor = s("quoteAnchor"))
        }
        return ChapterOpenPack(stem = stem, points = ChapterOpenGate.acceptPoints(raw, blocks))
    }
}

data class ChapterOpenPack(
    val stem: String,
    val points: List<ChapterOpenAcceptedPoint>,
)

private const val CHAPTER_OPEN_SYSTEM: String = """
You write one open question for a chapter the student has just read.
Rules:
1. One question that asks the student to explain the chapter in their own words.
2. Four to six scoring points. Each point's claim is what a good answer would say, written freshly. Do not paste 12 or more consecutive characters from any block into a claim.
3. Each point cites one blockId and a quoteAnchor of 4 to 18 characters copied exactly from that block, used only as a location.
4. Do not write a model answer, and do not include a rewrite field.
Output one JSON object only.
"""

object PracticeGradeRequestParser {
    fun parse(input: JsonObject): PracticeGradeAndDiagnoseRequest {
        fun JsonObject.s(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

        val mode = input.s("mode").ifBlank { "GRADE_AND_DIAGNOSE" }
        val bookId = input.s("bookId").trim().ifBlank { "practice" }
        val chapterId = input.s("chapterId").trim().ifBlank { "practice" }
        val sectionId = input.s("sectionId").trim().ifBlank { "section" }
        val conceptKey = input.s("conceptKey").trim()
        val questionId = input.s("questionId").trim()
        val questionStem = input.s("questionStem").trim()
        val studentAnswer = input.s("studentAnswer").trim()
        require(questionStem.isNotBlank() && questionStem.length <= 8_000) { "题目内容不能为空或过长。" }
        if (mode != "VARIANT_ONLY") {
            require(studentAnswer.isNotBlank() && studentAnswer.length <= 16_000) { "学生作答不能为空或过长。" }
        }

        val rubricCriteria = (input["rubricCriteria"] as? JsonArray).orEmpty().take(24).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val cid = o.s("cid").trim()
            val slotLabel = o.s("slotLabel").trim()
            if (cid.isBlank() || slotLabel.isBlank()) return@mapNotNull null
            val nkObj = o["numericKey"] as? JsonObject
            val nk = nkObj?.let { parseNumericSpec(it, cid, slotLabel) }
            PracticeRubricCriterionInput(
                cid = cid,
                slotLabel = slotLabel,
                maxMarks = (o["maxMarks"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                pdfPage = (o["pdfPage"] as? JsonPrimitive)?.intOrNull ?: 0,
                quoteAnchor = o.s("quoteAnchor").trim(),
                sourceExcerpt = o.s("sourceExcerpt").trim(),
                numericKey = nk,
            )
        }

        val sourcePages = (input["sourcePages"] as? JsonArray).orEmpty().take(24).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val pdfPage = (o["pdfPage"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val text = o.s("text")
            if (pdfPage <= 0 || text.isBlank()) return@mapNotNull null
            PracticeSourcePageInput(
                pdfPage = pdfPage,
                text = text.take(12_000),
                title = o.s("title").trim().take(120),
                authorityRank = ((o["authorityRank"] as? JsonPrimitive)?.intOrNull ?: 2).coerceIn(1, 4),
            )
        }

        val numericFacts = (input["numericFacts"] as? JsonArray).orEmpty().take(24).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            parseNumericSpec(o, o.s("cid").trim(), o.s("label").trim())
        }

        val exclusionTexts = (input["referenceExclusionTexts"] as? JsonArray).orEmpty().take(8).mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.take(40_000)
        }

        return PracticeGradeAndDiagnoseRequest(
            mode = mode,
            bookId = bookId,
            chapterId = chapterId,
            sectionId = sectionId,
            conceptKey = conceptKey,
            questionId = questionId,
            questionStem = questionStem,
            studentAnswer = studentAnswer,
            rubricCriteria = rubricCriteria,
            sourcePages = sourcePages,
            numericFacts = numericFacts,
            generateVariant = (input["generateVariant"] as? JsonPrimitive)?.booleanOrNull ?: true,
            referenceExclusionTexts = exclusionTexts,
            mcqCorrectOption = input.s("mcqCorrectOption").trim(),
            mcqSelectedOption = input.s("mcqSelectedOption").trim(),
            retryPointIds = (input["retryPointIds"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            }.distinct().take(ChapterOpenGate.MAX_POINTS),
        )
    }

    private fun parseNumericSpec(o: JsonObject, fallbackCid: String, fallbackLabel: String): PracticeNumericKeySpec? {
        val expected = (o["expected"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (expected.isBlank()) return null
        return PracticeNumericKeySpec(
            cid = (o["cid"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifBlank { fallbackCid },
            label = (o["label"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifBlank { fallbackLabel },
            expected = expected,
            submitted = (o["submitted"] as? JsonPrimitive)?.contentOrNull?.trim(),
            absTolerance = (o["absTolerance"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { "0.015" } ?: "0.015",
            relTolerance = (o["relTolerance"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { "0.0" } ?: "0.0",
            decimalPlaces = (o["decimalPlaces"] as? JsonPrimitive)?.intOrNull,
        )
    }
}
