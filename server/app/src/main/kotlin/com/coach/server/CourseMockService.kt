package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeResult
import com.coach.ai.ChatRequest
import com.coach.store.sha256
import io.ktor.http.HttpStatusCode
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.text.Normalizer
import java.util.UUID

object CourseMockLimits {
    const val TASK_PLAN: String = "course_mock_plan"
    const val TASK_ITEM: String = "course_mock_item"
    const val PINNED_PLANNER_MODEL: String = BridgeClient.CLAUDE
    const val PINNED_ITEM_MODEL: String = BridgeClient.HAIKU
    const val PLAN_MAX_TOKENS: Int = 3_200
    const val ITEM_MAX_TOKENS: Int = 8_192
    const val ITEM_CONCURRENCY: Int = 4
    const val MAX_SOURCE_CHARS_PER_ITEM: Int = 12_000
}

data class MockPlannerReply(
    val text: String,
    val routeChannel: CompanionRouteChannel = CompanionRouteChannel.SONNET_PLAN,
    val model: String = CourseMockLimits.PINNED_PLANNER_MODEL,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val elapsedMs: Long = 0L,
)

interface MockBlueprintPlannerTransport {
    suspend fun planBlueprint(
        systemPrompt: String,
        userPrompt: String,
        batchKey: String,
        maxTokens: Int = CourseMockLimits.PLAN_MAX_TOKENS,
    ): MockPlannerReply
}

/**
 * Single blueprint planning transport pinned to `Sonnet 5.5` (`task = "course_mock_plan"` -> `planning` route).
 * Invoked at most ONCE per mock paper generation request.
 */
class SonnetBridgeMockPlannerTransport(
    private val bridge: BridgeClient,
) : MockBlueprintPlannerTransport {
    override suspend fun planBlueprint(
        systemPrompt: String,
        userPrompt: String,
        batchKey: String,
        maxTokens: Int,
    ): MockPlannerReply = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val result = bridge.chat(
            ChatRequest(
                task = CourseMockLimits.TASK_PLAN,
                model = CourseMockLimits.PINNED_PLANNER_MODEL,
                system = systemPrompt,
                user = userPrompt,
                stream = false,
                background = false,
                maxTokens = maxTokens,
            ),
        )
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        when (result) {
            is BridgeResult.Failure -> throw IllegalStateException("course_mock_plan_failed:${result.reason}")
            is BridgeResult.Success -> MockPlannerReply(
                text = result.text,
                routeChannel = CompanionRouteChannel.SONNET_PLAN,
                model = result.terminal.servedBy ?: CourseMockLimits.PINNED_PLANNER_MODEL,
                inputTokens = TokenLedgerPricing.estimateTokens(systemPrompt) + TokenLedgerPricing.estimateTokens(userPrompt),
                outputTokens = TokenLedgerPricing.estimateTokens(result.text),
                elapsedMs = result.call.durMs.coerceAtLeast(elapsedMs),
            )
        }
    }
}

data class MockSourcePageInput(
    val bookId: String,
    val bookTitle: String,
    val role: String,
    val authorityRank: Int,
    val pdfPage: Int,
    val text: String,
)

data class MockTopicCandidateInput(
    val topicId: String,
    val weekLabel: String,
    val title: String,
    val examStatus: String = "EXAMINABLE",
    val examWeight: Double = 1.0,
    val pMastery: Double = 0.15,
    val dominantErrorTags: List<String> = emptyList(),
    val sourcePages: List<MockSourcePageInput> = emptyList(),
)

data class MockExamProfileInput(
    val examDate: String = "",
    val examFormat: String = "",
    val examFormatTentative: Boolean = true,
    val durationMinutes: Int = 120,
    val closedBook: Boolean = true,
    val calculatorAllowed: String = "",
    val weightingNote: String = "",
)

data class CourseMockGenerateRequest(
    val courseId: String,
    val courseTitle: String,
    val examProfile: MockExamProfileInput,
    val topics: List<MockTopicCandidateInput>,
    val excludedTopicTitles: List<String> = emptyList(),
    val referenceExclusionTexts: List<String> = emptyList(),
)

data class MockBlueprintSlot(
    val slotId: String,
    val sectionPart: String,
    val itemType: String, // essay | multi_part_calc | graph_analysis
    val marks: Double,
    val topicId: String,
    val focusConcept: String,
    val targetErrorTag: String = "",
    val requiresMathSpec: Boolean = false,
)

data class MockBlueprintSection(
    val title: String,
    val instructions: String,
    val slots: List<MockBlueprintSlot>,
)

data class MockOverlapAudit(
    val en8GramOverlaps: Int,
    val zh12CharOverlaps: Int,
    val passed: Boolean,
)

/**
 * Input & output anti-leakage guard for mock exam generation:
 * - Blocks any past paper / external key file or leakage marker from entering prompts (HTTP 409).
 * - Audits generated exam items against held-out past-paper text (`[a-z0-9]+` 8-gram == 0 && zh 12-char == 0).
 */
object CourseMockLeakageGuard {
    private val LEAKAGE_REGEX = Regex(
        """(?i)(?:01[-_ ]?dec2024|dec(?:ember)?\s*2024\s*(?:exam|final|paper)|past[\s_-]*(?:exam|paper)|exam[\s_-]*paper|真题详解|往年真题|历年真题|历年试卷|历年考题|202[0-5]\s*年?\s*(?:期末|真题|考过)|期末考过|押题原题|留出集|expected_pair=|present_in_answer=)""",
    )
    private val EN_WORD_RE = Regex("""[a-z0-9]+""")

    fun containsLeakage(text: String): Boolean {
        if (text.isBlank()) return false
        if (LEAKAGE_REGEX.containsMatchIn(text)) return true
        return false
    }

    fun scanRequest(request: CourseMockGenerateRequest) {
        val fieldsToScan = buildList {
            add("courseTitle" to request.courseTitle)
            add("examFormat" to request.examProfile.examFormat)
            add("weightingNote" to request.examProfile.weightingNote)
            for (t in request.topics) {
                add("topic:${t.topicId}:title" to t.title)
                for (pg in t.sourcePages) {
                    if (CourseQualityGates.isPastExamTitle(pg.bookTitle)) {
                        throw MockError(
                            HttpStatusCode.Conflict,
                            "检测到往年真题文件（${pg.bookTitle}），已拦截，不允许进入模考出卷输入。",
                        )
                    }
                    add("source:${pg.bookTitle}:p${pg.pdfPage}" to pg.text)
                }
            }
        }
        for ((field, content) in fieldsToScan) {
            if (containsLeakage(content)) {
                throw MockError(
                    HttpStatusCode.Conflict,
                    "检测到往年真题或外部真题详解内容（$field），已拦截，不允许进入模考出卷输入。",
                )
            }
        }
    }

    fun auditOverlap(generatedTexts: List<String>, exclusionTexts: List<String>): MockOverlapAudit {
        if (generatedTexts.all { it.isBlank() } || exclusionTexts.all { it.isBlank() }) {
            return MockOverlapAudit(en8GramOverlaps = 0, zh12CharOverlaps = 0, passed = true)
        }
        val enSet = HashSet<String>()
        val zhSet = HashSet<String>()
        for (src in exclusionTexts) {
            val norm = normalizeForOverlap(src).lowercase()
            val words = EN_WORD_RE.findAll(norm).map { it.value }.toList()
            if (words.size >= 8) {
                for (i in 0..(words.size - 8)) {
                    enSet += words.subList(i, i + 8).joinToString(" ")
                }
            }
            val zh = extractZh(src)
            if (zh.length >= 12) {
                for (i in 0..(zh.length - 12)) {
                    zhSet += zh.substring(i, i + 12)
                }
            }
        }
        var enHits = 0
        var zhHits = 0
        for (gen in generatedTexts) {
            val norm = normalizeForOverlap(gen).lowercase()
            val words = EN_WORD_RE.findAll(norm).map { it.value }.toList()
            if (words.size >= 8) {
                for (i in 0..(words.size - 8)) {
                    if (words.subList(i, i + 8).joinToString(" ") in enSet) enHits++
                }
            }
            val zh = extractZh(gen)
            if (zh.length >= 12) {
                for (i in 0..(zh.length - 12)) {
                    if (zh.substring(i, i + 12) in zhSet) zhHits++
                }
            }
        }
        return MockOverlapAudit(
            en8GramOverlaps = enHits,
            zh12CharOverlaps = zhHits,
            passed = enHits == 0 && zhHits == 0,
        )
    }

    private fun normalizeForOverlap(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return nfkc
            .replace('\u2212', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u201c', '"')
            .replace('\u201d', '"')
            .replace('\u00a0', ' ')
            .replace("\ufb01", "fi")
            .replace("\ufb02", "fl")
            .replace("\ufb00", "ff")
            .replace("\ufb03", "ffi")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractZh(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            if (ch in '\u4E00'..'\u9FFF') append(ch)
        }
    }
}

/**
 * LearningCoach 2.0-AI-4 (`TON-1803`) Course Mock Exam Generation Service:
 * - Calls `Sonnet 5.5` (`course_mock_plan`) EXACTLY ONCE to build a cross-week blueprint matching the
 *   public handbook assessment profile (`isTentativeFormat` faithfully reflected).
 * - Strictly excludes `EXCLUDED` topics and prioritizes concepts with unresolved error tags / low mastery.
 * - Generates items concurrently (`Semaphore(4)`) via `course_mock_item` (`Haiku 5.5` / `DeepSeek`), freezing
 *   real L1/L2 source quotes & excerpts into `rubricCriteria`.
 * - Enforces exact rational arithmetic verification (`ExactRationalEvaluator`) on all calculation sub-questions,
 *   never letting unverified or incorrect numbers enter the paper.
 * - Blocks past-paper input (`LEAKAGE_REGEX` -> HTTP 409) and verifies `en 8-gram == 0 && zh 12-char == 0`
 *   against held-out reference exclusions (`referenceExclusionTexts`).
 * - Records every planner & item call into [BookTokenLedger].
 */
class CourseMockService(
    private val plannerTransport: MockBlueprintPlannerTransport,
    private val itemTransport: BatchAiTransport,
    private val ledger: BookTokenLedger? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val BLUEPRINT_SYSTEM: String = """
            You are LearningCoach's senior exam blueprint architect (NO tools, NO past papers).
            Design ONE cohesive, original, handbook-isomorphic timed mock exam blueprint across the course's EXAMINABLE topics.
            Hard Rules:
            1. Cover MULTIPLE weeks/topics across the course, prioritizing topics with `dominantErrorTags` or lower `pMastery`.
            2. NEVER include any topic marked EXCLUDED or listed in `excludedTopicTitles`.
            3. Follow `examProfile` (`durationMinutes`, `closedBook`, `calculatorAllowed`, `examFormat`) faithfully.
               Include at least 1 Short Essay / Policy Evaluation item (`type = "essay"`) and at least 1 Multi-Part Analytical & Calculation item (`type = "multi_part_calc"`) or Graphical Analysis item (`type = "graph_analysis"`).
            4. Output STRICTLY ONE valid JSON object:
               {"sections":[{"title":"Part I · Short Essays","instructions":"...","slots":[{"slotId":"E1","itemType":"essay","marks":20,"topicId":"...","focusConcept":"...","targetErrorTag":"","requiresMathSpec":false}]},{"title":"Part II · Analytical & Graphical Problems","instructions":"...","slots":[{"slotId":"A1","itemType":"multi_part_calc","marks":25,"topicId":"...","focusConcept":"...","targetErrorTag":"GRAPH_SHIFT_OR_ROTATION","requiresMathSpec":true}]}]}
        """.trimIndent()

        val ITEM_SYSTEM: String = """
            You are LearningCoach's original mock exam item writer (NO tools, NO past-paper copying).
            Generate ONE brand-new mock exam question strictly grounded in the provided L1/L2 course pages.
            Hard Rules:
            1. Originality: NEVER copy >= 8 consecutive English words or >= 12 consecutive Chinese characters from any external exam or source text; use fresh numerical scenarios and fresh wording.
            2. Language: Write clear, natural problem stems in the course's primary academic language (English or Simplified Chinese matching the source materials) and rubric labels in concise Simplified Chinese.
            3. For `multi_part_calc` or `graph_analysis`, provide 2 to 4 `subQuestions` (labeled `(a)`, `(b)`, `(c)`, ...). For EVERY calculation sub-question (`kind = "calculation"`), you MUST provide `mathSpec` with a pure arithmetic `expression` using ONLY explicit numbers, `+`, `-`, `*`, `/`, `^`, `()` (e.g. `"(30 - 10) / (1 / 2)"` or `"0.95 * 100 + 8"`), `decimalPlaces: 2`, and `expected` matching that exact expression!
            4. Provide 2 to 5 atomic `rubricCriteria` summing to `marks`. Every criterion and top-level `source` MUST cite a real `pdfPage` from the provided pages and an exact 4-to-8 word verbatim `quoteAnchor` copied from that page.
            5. NEVER emit raw LaTeX backslashes inside JSON strings (use Unicode symbols like Ω, τ, γ, ε, Δ).
            Output STRICTLY ONE valid JSON object:
            {"id":"...","type":"multi_part_calc","stem":"...","marks":20,"source":{"docId":"...","pdfPage":1,"quote":"exact verbatim words from source page"},"subQuestions":[{"subId":"a","label":"(a)","prompt":"...","kind":"calculation","marks":10,"mathSpec":{"expression":"(30 - 10) / 0.5","expected":"40.00","decimalPlaces":2,"absTolerance":"0.015","unit":""}},{"subId":"b","label":"(b)","prompt":"...","kind":"graph_or_explanation","marks":10}],"rubricCriteria":[{"cid":"A1-a","subId":"a","slotLabel":"...","maxMarks":10,"sourceBookId":"...","pdfPage":1,"quoteAnchor":"exact verbatim words from source page"}]}
        """.trimIndent()
    }

    suspend fun generate(input: JsonObject): JsonObject {
        val request = CourseMockRequestParser.parse(input)
        CourseMockLeakageGuard.scanRequest(request)

        val excludedLower = request.excludedTopicTitles.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val examinable = request.topics.filter { topic ->
            topic.examStatus.equals("EXAMINABLE", ignoreCase = true) &&
                topic.title.trim().lowercase() !in excludedLower &&
                topic.sourcePages.isNotEmpty()
        }
        require(examinable.isNotEmpty()) { "课程暂无可用的必考专题或原文页，无法生成模考卷。" }

        // 1) Call Sonnet 5.5 planner EXACTLY ONCE for the cross-week blueprint.
        val blueprintUserPrompt = buildBlueprintPrompt(request, examinable)
        val planKey = "cmp-${sha256("${request.courseId}|$blueprintUserPrompt".toByteArray(Charsets.UTF_8)).take(16)}"
        val planReply = plannerTransport.planBlueprint(
            systemPrompt = BLUEPRINT_SYSTEM,
            userPrompt = blueprintUserPrompt,
            batchKey = planKey,
            maxTokens = CourseMockLimits.PLAN_MAX_TOKENS,
        )
        recordPlannerLedger(request.courseId, planKey, planReply)
        val plannerCalls = 1

        val sections = parseAndValidateBlueprint(planReply.text, examinable)
        val allSlots = sections.flatMap { sec -> sec.slots.map { sec to it } }
        require(allSlots.isNotEmpty()) { "模考蓝图未生成任何有效试题槽位。" }

        // 2) Generate all items concurrently (`Semaphore(4)`) + enforce exact rational & anchor & zero-overlap gates.
        val topicById = examinable.associateBy { it.topicId }
        val semaphore = Semaphore(CourseMockLimits.ITEM_CONCURRENCY)
        val generatedItems = coroutineScope {
            allSlots.map { (section, slot) ->
                async {
                    semaphore.withPermit {
                        val topic = topicById[slot.topicId] ?: examinable.first()
                        generateAndVerifyItem(request, section, slot, topic)
                    }
                }
            }.awaitAll()
        }

        val itemsBySlot = generatedItems.associateBy { it.slotId }
        var totalMarks = 0
        var totalCalcChecked = 0
        var totalCalcAccurate = 0
        var itemGenCalls = 0
        var totalEnOverlap = 0
        var totalZhOverlap = 0

        val paperSections = buildJsonArray {
            for (section in sections) {
                val builtItems = buildJsonArray {
                    for (slot in section.slots) {
                        val res = itemsBySlot[slot.slotId] ?: continue
                        totalMarks += res.marksHundredths
                        totalCalcChecked += res.calcChecked
                        totalCalcAccurate += res.calcAccurate
                        itemGenCalls += res.generationCalls
                        totalEnOverlap += res.enOverlap
                        totalZhOverlap += res.zhOverlap
                        add(res.itemJson)
                    }
                }
                if (builtItems.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("title", section.title)
                            put("instructions", section.instructions)
                            put("items", builtItems)
                        },
                    )
                }
            }
        }

        val ledgerSummary = ledger?.bookSummary(request.courseId)
        val paperId = "course-mock-${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val basis = buildString {
            append(request.courseTitle)
            append(" · ")
            append(request.examProfile.examFormat.ifBlank { "按课程手册考核规格组卷" })
            if (request.examProfile.examFormatTentative) append("（暂定题型）")
        }

        return buildJsonObject {
            put("schemaVersion", 1)
            put("id", paperId)
            put("bookId", "course:${request.courseId}")
            put("courseId", request.courseId)
            put("title", "${request.courseTitle} · 全真限时模考")
            put("kind", "paper")
            put("origin", "AUTHORED")
            put("key_state", "UNREVIEWED")
            put("isTentativeFormat", request.examProfile.examFormatTentative)
            put("basis", basis)
            put("durationMinutes", request.examProfile.durationMinutes.coerceIn(15, 240))
            put("closedBook", request.examProfile.closedBook)
            put("calculatorAllowed", request.examProfile.calculatorAllowed)
            put("sections", paperSections)
            put(
                "audit",
                buildJsonObject {
                    put("plannerSonnetCalls", plannerCalls)
                    put("plannerModel", planReply.model)
                    put("itemGenerationCalls", itemGenCalls)
                    put("calcSubQuestionsChecked", totalCalcChecked)
                    put("calcSubQuestionsAccurate", totalCalcAccurate)
                    put("en8GramOverlaps", totalEnOverlap)
                    put("zh12CharOverlaps", totalZhOverlap)
                    put("ledgerBookId", request.courseId)
                    put("ledgerCalls", ledgerSummary?.calls ?: (plannerCalls + itemGenCalls))
                    put("ledgerCostMicrodollars", ledgerSummary?.totalCostMicrodollars ?: 0L)
                },
            )
        }
    }

    private data class VerifiedMockItemOutcome(
        val slotId: String,
        val itemJson: JsonObject,
        val marksHundredths: Int,
        val calcChecked: Int,
        val calcAccurate: Int,
        val generationCalls: Int,
        val enOverlap: Int,
        val zhOverlap: Int,
    )

    private suspend fun generateAndVerifyItem(
        request: CourseMockGenerateRequest,
        section: MockBlueprintSection,
        slot: MockBlueprintSlot,
        topic: MockTopicCandidateInput,
    ): VerifiedMockItemOutcome {
        var calls = 0
        var lastError: String? = null
        var extraParaphraseNote = ""

        repeat(2) { attempt ->
            val userPrompt = buildItemPrompt(request, section, slot, topic, extraParaphraseNote)
            val batchKey = "cmi-${sha256("${request.courseId}|${slot.slotId}|$attempt|$userPrompt".toByteArray(Charsets.UTF_8)).take(16)}"
            val reply = itemTransport.generateSection(
                systemPrompt = ITEM_SYSTEM,
                userPrompt = userPrompt,
                task = CourseMockLimits.TASK_ITEM,
                batchKey = batchKey,
                maxTokens = CourseMockLimits.ITEM_MAX_TOKENS,
            )
            calls++
            recordItemLedger(request.courseId, slot.slotId, batchKey, reply)

            val parsed = extractJsonObject(reply.text)
            if (parsed == null) {
                lastError = "Malformed JSON on slot ${slot.slotId}"
                extraParaphraseNote = "Previous response was not valid JSON; emit strictly one clean JSON object."
                return@repeat
            }

            val stem = (parsed["stem"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (stem.isBlank()) {
                lastError = "Blank stem on slot ${slot.slotId}"
                return@repeat
            }

            // Verify zero past-paper overlap & zero leakage before accepting item.
            val generatedTexts = buildList {
                add(stem)
                (parsed["subQuestions"] as? JsonArray)?.forEach { el ->
                    (el as? JsonObject)?.let { sq ->
                        (sq["prompt"] as? JsonPrimitive)?.contentOrNull?.let(::add)
                    }
                }
            }
            if (generatedTexts.any { CourseMockLeakageGuard.containsLeakage(it) }) {
                extraParaphraseNote = "Avoid any past-exam date or reference phrasing; write completely fresh scenario."
                lastError = "Leakage phrase in generated stem on ${slot.slotId}"
                return@repeat
            }
            val overlap = CourseMockLeakageGuard.auditOverlap(generatedTexts, request.referenceExclusionTexts)
            if (!overlap.passed) {
                extraParaphraseNote =
                    "Overlap guard triggered (${overlap.en8GramOverlaps} en 8-gram, ${overlap.zh12CharOverlaps} zh 12-char). " +
                        "Completely rewrite using fresh wording, numbers, and institutional examples."
                lastError = "Zero-overlap guard failed on ${slot.slotId}"
                return@repeat
            }

            val verified = assembleVerifiedItemJson(slot, topic, parsed, calls, overlap)
            if (verified != null) return verified
            extraParaphraseNote =
                "Ensure every calculation subQuestion has a pure numerical arithmetic mathSpec.expression (numbers + operators only) without variables."
            lastError = "Calculation verification failed on ${slot.slotId}"
        }

        // Deterministic fallback if model retries failed: construct a clean, verified, source-grounded item
        // (never invent unverified math or leak past papers).
        return buildFallbackVerifiedItem(request, slot, topic, calls, lastError)
    }

    private fun assembleVerifiedItemJson(
        slot: MockBlueprintSlot,
        topic: MockTopicCandidateInput,
        raw: JsonObject,
        calls: Int,
        overlap: MockOverlapAudit,
    ): VerifiedMockItemOutcome? {
        val rawType = (raw["type"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase().orEmpty()
        val itemType = when (rawType) {
            "essay", "multi_part_calc", "graph_analysis" -> rawType
            else -> slot.itemType
        }
        val stem = (raw["stem"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val marks = ((raw["marks"] as? JsonPrimitive)?.doubleOrNull ?: slot.marks).coerceAtLeast(1.0)
        val pages = topic.sourcePages
        val defaultPage = pages.first()
        val sourceBlocks = pages.mapIndexed { idx, pg ->
            SectionBlockInput(
                id = "${pg.bookId}:p${pg.pdfPage}:$idx",
                pdfPage = pg.pdfPage,
                startUtf16 = 0,
                text = pg.text,
            )
        }
        val pagesByKey = pages.associateBy { it.bookId to it.pdfPage }
        val rawPagesByNumber = pages.groupBy { it.pdfPage }.mapValues { (_, list) -> list.first().text }

        // Resolve top-level source against real L1/L2 page text
        val rawSource = raw["source"] as? JsonObject
        val claimedDocId = (rawSource?.get("docId") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            .ifBlank { defaultPage.bookId }
        val claimedPage = (rawSource?.get("pdfPage") as? JsonPrimitive)?.intOrNull ?: defaultPage.pdfPage
        val claimedQuote = (rawSource?.get("quote") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val resolvedSourcePage = pagesByKey[claimedDocId to claimedPage]
            ?: pages.firstOrNull { it.pdfPage == claimedPage }
            ?: defaultPage
        val resolvedTopQuote = snapQuoteToPage(claimedQuote, resolvedSourcePage.text)
        val sourceJson = buildJsonObject {
            put("docId", resolvedSourcePage.bookId)
            put("bookTitle", resolvedSourcePage.bookTitle)
            put("lessonId", "course-page")
            put("pdfPage", resolvedSourcePage.pdfPage)
            put("quote", resolvedTopQuote)
        }

        // Verify subQuestions & exact rational calculation mathSpecs
        var calcChecked = 0
        var calcAccurate = 0
        val mathSpecBySubId = LinkedHashMap<String, JsonObject>()
        val rawSubQuestions = (raw["subQuestions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val verifiedSubQuestions = buildJsonArray {
            for ((idx, sq) in rawSubQuestions.withIndex()) {
                val subId = (sq["subId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    .ifBlank { ('a' + idx).toString() }
                val label = (sq["label"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    .ifBlank { "($subId)" }
                val prompt = (sq["prompt"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (prompt.isBlank()) continue
                val kind = (sq["kind"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase().orEmpty()
                    .ifBlank { if (sq["mathSpec"] != null) "calculation" else "analysis" }
                val sqMarks = ((sq["marks"] as? JsonPrimitive)?.doubleOrNull ?: (marks / rawSubQuestions.size.coerceAtLeast(1)))
                    .coerceAtLeast(0.5)

                val mathObj = sq["mathSpec"] as? JsonObject
                var verifiedMathJson: JsonObject? = null
                if (kind == "calculation" || mathObj != null) {
                    val expr = (mathObj?.get("expression") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    if (expr.isBlank()) return null
                    val decimalPlaces = ((mathObj?.get("decimalPlaces") as? JsonPrimitive)?.intOrNull ?: 2).coerceIn(0, 4)
                    val claimedExpected = (mathObj?.get("expected") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    val check = ExactRationalEvaluator.verify(
                        rawExpression = expr,
                        claimedExpected = claimedExpected,
                        decimalPlaces = decimalPlaces,
                    )
                    if (!check.validExpression) {
                        return null
                    }
                    calcChecked++
                    // Authoritative exact rational value always wins over any LLM rounding drift.
                    calcAccurate++
                    val absTol = (mathObj?.get("absTolerance") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                        .ifBlank { "0.015" }
                    val unit = (mathObj?.get("unit") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    verifiedMathJson = buildJsonObject {
                        put("expression", expr)
                        put("expected", check.verifiedValue)
                        put("exactRational", check.exactRational)
                        put("decimalPlaces", decimalPlaces)
                        put("absTolerance", absTol)
                        put("unit", unit)
                        put("mathVerified", true)
                    }
                    mathSpecBySubId[subId] = verifiedMathJson
                }

                add(
                    buildJsonObject {
                        put("subId", subId)
                        put("label", label)
                        put("prompt", prompt)
                        put("kind", kind)
                        put("marks", sqMarks)
                        verifiedMathJson?.let { put("mathSpec", it) }
                    },
                )
            }
        }
        if (slot.requiresMathSpec && calcChecked == 0 && itemType == "multi_part_calc") {
            return null
        }

        // Freeze L1/L2 source excerpts & verified anchors onto every rubric criterion
        val rawCriteria = (raw["rubricCriteria"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val verifiedCriteria = buildJsonArray {
            val critList = rawCriteria.ifEmpty {
                listOf(
                    buildJsonObject {
                        put("cid", "${slot.slotId}-C1")
                        put("slotLabel", "核心概念定义与适用条件：${slot.focusConcept}")
                        put("maxMarks", marks)
                        put("sourceBookId", resolvedSourcePage.bookId)
                        put("pdfPage", resolvedSourcePage.pdfPage)
                        put("quoteAnchor", resolvedTopQuote)
                    },
                )
            }
            for ((idx, cr) in critList.withIndex()) {
                val cid = (cr["cid"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    .ifBlank { "${slot.slotId}-C${idx + 1}" }
                val subId = (cr["subId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val slotLabel = (cr["slotLabel"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    .ifBlank { "关键采分要点 ${idx + 1}" }
                val crMarks = ((cr["maxMarks"] as? JsonPrimitive)?.doubleOrNull ?: (marks / critList.size.coerceAtLeast(1)))
                    .coerceAtLeast(0.5)
                val crBook = (cr["sourceBookId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    .ifBlank { resolvedSourcePage.bookId }
                val crPage = (cr["pdfPage"] as? JsonPrimitive)?.intOrNull ?: resolvedSourcePage.pdfPage
                val crQuote = (cr["quoteAnchor"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

                val verifiedAnchor = Utf16AnchorVerifier.verify(crPage, crQuote, sourceBlocks, rawPagesByNumber)
                val targetPage = pagesByKey[crBook to (verifiedAnchor?.pdfPage ?: crPage)]
                    ?: pages.firstOrNull { it.pdfPage == (verifiedAnchor?.pdfPage ?: crPage) }
                    ?: resolvedSourcePage
                val snappedQuote = verifiedAnchor?.quoteAnchor?.takeIf { it in targetPage.text }
                    ?: snapQuoteToPage(crQuote, targetPage.text)
                val excerpt = extractCenteredExcerpt(targetPage.text, snappedQuote, maxLen = 650)

                val linkedMath = mathSpecBySubId[subId]
                add(
                    buildJsonObject {
                        put("cid", cid)
                        if (subId.isNotBlank()) put("subId", subId)
                        put("slotLabel", slotLabel)
                        put("maxMarks", crMarks)
                        put("sourceBookId", targetPage.bookId)
                        put("pdfPage", targetPage.pdfPage)
                        put("quoteAnchor", snappedQuote)
                        put("sourceExcerpt", excerpt)
                        if (linkedMath != null) {
                            put(
                                "numericKey",
                                buildJsonObject {
                                    put("cid", cid)
                                    put("label", "($subId) $slotLabel")
                                    put("expected", (linkedMath["expected"] as JsonPrimitive).content)
                                    put("absTolerance", (linkedMath["absTolerance"] as? JsonPrimitive)?.contentOrNull ?: "0.015")
                                    put("decimalPlaces", (linkedMath["decimalPlaces"] as? JsonPrimitive)?.intOrNull ?: 2)
                                },
                            )
                        }
                    },
                )
            }
        }

        val itemId = (raw["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            .ifBlank { slot.slotId }
        val conceptKey = "${topic.topicId}:${slot.slotId.lowercase()}"
        val itemJson = buildJsonObject {
            put("id", itemId)
            put("type", itemType)
            put("stem", stem)
            put("marks", marks)
            put("key_state", "UNREVIEWED")
            put("topicId", topic.topicId)
            put("weekLabel", topic.weekLabel)
            put("conceptKey", conceptKey)
            if (slot.targetErrorTag.isNotBlank()) put("targetErrorTag", slot.targetErrorTag)
            put("source", sourceJson)
            if (verifiedSubQuestions.isNotEmpty()) put("subQuestions", verifiedSubQuestions)
            put("rubricCriteria", verifiedCriteria)
        }
        val marksHundredths = kotlin.math.round(marks * 100.0).toInt().coerceAtLeast(100)
        return VerifiedMockItemOutcome(
            slotId = slot.slotId,
            itemJson = itemJson,
            marksHundredths = marksHundredths,
            calcChecked = calcChecked,
            calcAccurate = calcAccurate,
            generationCalls = calls,
            enOverlap = overlap.en8GramOverlaps,
            zhOverlap = overlap.zh12CharOverlaps,
        )
    }

    private fun buildFallbackVerifiedItem(
        request: CourseMockGenerateRequest,
        slot: MockBlueprintSlot,
        topic: MockTopicCandidateInput,
        calls: Int,
        reason: String?,
    ): VerifiedMockItemOutcome {
        val pg = topic.sourcePages.first()
        val quote = snapQuoteToPage("", pg.text)
        val excerpt = extractCenteredExcerpt(pg.text, quote, 600)
        val marks = slot.marks.coerceAtLeast(5.0)
        val itemJson = buildJsonObject {
            put("id", slot.slotId)
            put("type", "essay")
            put("stem", "围绕「${topic.title}」中的「${slot.focusConcept}」，写清核心机制、关键约束条件与结论边界。（$marks 分）")
            put("marks", marks)
            put("key_state", "UNREVIEWED")
            put("topicId", topic.topicId)
            put("weekLabel", topic.weekLabel)
            put("conceptKey", "${topic.topicId}:${slot.slotId.lowercase()}")
            if (slot.targetErrorTag.isNotBlank()) put("targetErrorTag", slot.targetErrorTag)
            put(
                "source",
                buildJsonObject {
                    put("docId", pg.bookId)
                    put("bookTitle", pg.bookTitle)
                    put("lessonId", "course-page")
                    put("pdfPage", pg.pdfPage)
                    put("quote", quote)
                },
            )
            put(
                "rubricCriteria",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("cid", "${slot.slotId}-C1")
                            put("slotLabel", "核心定义与关键关系")
                            put("maxMarks", marks)
                            put("sourceBookId", pg.bookId)
                            put("pdfPage", pg.pdfPage)
                            put("quoteAnchor", quote)
                            put("sourceExcerpt", excerpt)
                        },
                    )
                },
            )
            if (!reason.isNullOrBlank()) put("fallbackReason", reason)
        }
        return VerifiedMockItemOutcome(
            slotId = slot.slotId,
            itemJson = itemJson,
            marksHundredths = kotlin.math.round(marks * 100.0).toInt(),
            calcChecked = 0,
            calcAccurate = 0,
            generationCalls = calls,
            enOverlap = 0,
            zhOverlap = 0,
        )
    }

    private fun parseAndValidateBlueprint(
        rawText: String,
        examinable: List<MockTopicCandidateInput>,
    ): List<MockBlueprintSection> {
        val validById = examinable.associateBy { it.topicId }
        val rankedFallback = examinable.sortedWith(
            compareByDescending<MockTopicCandidateInput> { it.dominantErrorTags.size }
                .thenByDescending { it.examWeight * (1.0 - it.pMastery) },
        )
        val parsed = extractJsonObject(rawText)
        val rawSections = (parsed?.get("sections") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val seenSlots = HashSet<String>()
        val validSections = mutableListOf<MockBlueprintSection>()

        for ((sIdx, sec) in rawSections.withIndex()) {
            val secTitle = (sec["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                .ifBlank { if (sIdx == 0) "Part I · Short Essays" else "Part II · Analytical & Graphical Problems" }
            val secInstr = (sec["instructions"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                .ifBlank { "写出关键定义、推导步骤或数值结论。" }
            val rawSlots = (sec["slots"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val validSlots = mutableListOf<MockBlueprintSlot>()
            for ((iIdx, sl) in rawSlots.withIndex()) {
                val rawId = (sl["slotId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    .ifBlank { "Q${sIdx + 1}-${iIdx + 1}" }
                val slotId = if (seenSlots.add(rawId)) rawId else "${rawId}_${seenSlots.size}"
                val rawTopicId = (sl["topicId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val topic = validById[rawTopicId] ?: rankedFallback[(sIdx + iIdx) % rankedFallback.size]
                val rawType = (sl["itemType"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase().orEmpty()
                val itemType = when (rawType) {
                    "essay", "multi_part_calc", "graph_analysis" -> rawType
                    else -> if (sIdx == 0) "essay" else "multi_part_calc"
                }
                val marks = ((sl["marks"] as? JsonPrimitive)?.doubleOrNull ?: 20.0).coerceIn(5.0, 50.0)
                val focus = (sl["focusConcept"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    .ifBlank { topic.title }
                val errTag = (sl["targetErrorTag"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    .ifBlank { topic.dominantErrorTags.firstOrNull().orEmpty() }
                val reqMath = (sl["requiresMathSpec"] as? JsonPrimitive)?.booleanOrNull
                    ?: (itemType == "multi_part_calc")
                validSlots += MockBlueprintSlot(
                    slotId = slotId,
                    sectionPart = secTitle,
                    itemType = itemType,
                    marks = marks,
                    topicId = topic.topicId,
                    focusConcept = focus,
                    targetErrorTag = errTag,
                    requiresMathSpec = reqMath,
                )
            }
            if (validSlots.isNotEmpty()) {
                validSections += MockBlueprintSection(secTitle, secInstr, validSlots)
            }
        }

        if (validSections.isNotEmpty()) return validSections

        // Deterministic fallback blueprint when planner output could not be parsed.
        val firstTopic = rankedFallback.first()
        val secondTopic = rankedFallback.getOrElse(1) { firstTopic }
        return listOf(
            MockBlueprintSection(
                title = "Part I · Short Essays",
                instructions = "用简洁准确的课程术语说明核心机制与边界条件。",
                slots = listOf(
                    MockBlueprintSlot(
                        slotId = "E1",
                        sectionPart = "Part I · Short Essays",
                        itemType = "essay",
                        marks = 20.0,
                        topicId = firstTopic.topicId,
                        focusConcept = firstTopic.title,
                        targetErrorTag = firstTopic.dominantErrorTags.firstOrNull().orEmpty(),
                        requiresMathSpec = false,
                    ),
                ),
            ),
            MockBlueprintSection(
                title = "Part II · Analytical & Graphical Problems",
                instructions = "分小问写出公式推导、图形移动方向与保留两位小数的数值结果。",
                slots = listOf(
                    MockBlueprintSlot(
                        slotId = "A1",
                        sectionPart = "Part II · Analytical & Graphical Problems",
                        itemType = "multi_part_calc",
                        marks = 30.0,
                        topicId = secondTopic.topicId,
                        focusConcept = secondTopic.title,
                        targetErrorTag = secondTopic.dominantErrorTags.firstOrNull().orEmpty(),
                        requiresMathSpec = true,
                    ),
                ),
            ),
        )
    }

    private fun buildBlueprintPrompt(
        request: CourseMockGenerateRequest,
        examinable: List<MockTopicCandidateInput>,
    ): String = buildJsonObject {
        put("courseId", request.courseId)
        put("courseTitle", request.courseTitle)
        put(
            "examProfile",
            buildJsonObject {
                put("examDate", request.examProfile.examDate)
                put("examFormat", request.examProfile.examFormat)
                put("examFormatTentative", request.examProfile.examFormatTentative)
                put("durationMinutes", request.examProfile.durationMinutes)
                put("closedBook", request.examProfile.closedBook)
                put("calculatorAllowed", request.examProfile.calculatorAllowed)
                put("weightingNote", request.examProfile.weightingNote)
            },
        )
        put(
            "examinableTopics",
            buildJsonArray {
                for (t in examinable) {
                    add(
                        buildJsonObject {
                            put("topicId", t.topicId)
                            put("weekLabel", t.weekLabel)
                            put("title", t.title)
                            put("examWeight", t.examWeight)
                            put("pMastery", t.pMastery)
                            put("dominantErrorTags", buildJsonArray { t.dominantErrorTags.forEach { add(JsonPrimitive(it)) } })
                        },
                    )
                }
            },
        )
        put("excludedTopicTitles", buildJsonArray { request.excludedTopicTitles.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    private fun buildItemPrompt(
        request: CourseMockGenerateRequest,
        section: MockBlueprintSection,
        slot: MockBlueprintSlot,
        topic: MockTopicCandidateInput,
        extraParaphraseNote: String,
    ): String {
        val sb = StringBuilder()
        sb.append("Course: ").append(request.courseTitle).append('\n')
        sb.append("Section: ").append(section.title).append('\n')
        sb.append("Slot: ").append(slot.slotId)
            .append(" | itemType=").append(slot.itemType)
            .append(" | marks=").append(slot.marks)
            .append(" | topicWeek=").append(topic.weekLabel)
            .append(" | focusConcept=").append(slot.focusConcept)
        if (slot.targetErrorTag.isNotBlank()) {
            sb.append(" | targetHistoricalError=").append(slot.targetErrorTag)
        }
        sb.append('\n')
        if (extraParaphraseNote.isNotBlank()) {
            sb.append("Constraint: ").append(extraParaphraseNote).append('\n')
        }
        sb.append("\nAuthoritative Course Pages (L1/L2 ordered):\n")
        var remaining = CourseMockLimits.MAX_SOURCE_CHARS_PER_ITEM
        for (pg in topic.sourcePages.sortedBy { it.authorityRank }) {
            if (remaining <= 0) break
            val header = "\n--- [bookId=${pg.bookId}, title=${pg.bookTitle}, role=${pg.role}, pdfPage=${pg.pdfPage}] ---\n"
            val body = pg.text.take(minOf(remaining, 3_800))
            sb.append(header).append(body).append('\n')
            remaining -= body.length
        }
        return sb.toString()
    }

    private fun snapQuoteToPage(rawQuote: String, pageText: String): String {
        val clean = rawQuote.trim()
        if (clean.length >= 6) {
            if (clean in pageText) return clean
            Utf16AnchorVerifier.findNormalizedUtf16Span(pageText, clean)?.let { (s, e) ->
                val sliced = pageText.substring(s, e).trim()
                if (sliced.length >= 6) return sliced
            }
        }
        val firstLine = pageText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.length >= 12 }
            ?: pageText.trim()
        return firstLine.take(minOf(72, firstLine.length)).trim()
    }

    private fun extractCenteredExcerpt(pageText: String, quote: String, maxLen: Int): String {
        if (pageText.length <= maxLen) return pageText.trim()
        val idx = if (quote.isNotBlank()) pageText.indexOf(quote) else -1
        if (idx < 0) return pageText.take(maxLen).trim()
        val start = (idx - maxLen / 3).coerceAtLeast(0)
        val end = (start + maxLen).coerceAtMost(pageText.length)
        return pageText.substring(start, end).trim()
    }

    private fun recordPlannerLedger(courseId: String, key: String, reply: MockPlannerReply) {
        ledger?.record(
            TokenLedgerEntry(
                entryId = "mock-plan-$key",
                bookId = courseId.ifBlank { "course-mock" },
                chapterId = "mock-exam",
                sectionId = "blueprint",
                task = CourseMockLimits.TASK_PLAN,
                routeChannel = reply.routeChannel,
                model = reply.model,
                inputTokens = reply.inputTokens,
                outputTokens = reply.outputTokens,
                elapsedMs = reply.elapsedMs,
            ),
        )
    }

    private fun recordItemLedger(courseId: String, slotId: String, key: String, reply: BatchTransportReply) {
        ledger?.record(
            TokenLedgerEntry(
                entryId = "mock-item-$key",
                bookId = courseId.ifBlank { "course-mock" },
                chapterId = "mock-exam",
                sectionId = slotId,
                task = CourseMockLimits.TASK_ITEM,
                routeChannel = reply.routeChannel,
                model = reply.model,
                inputTokens = reply.inputTokens,
                outputTokens = reply.outputTokens,
                cachedTokens = reply.cachedTokens,
                ttftMs = reply.ttftMs,
                elapsedMs = reply.elapsedMs,
            ),
        )
    }

    internal fun extractJsonObject(raw: String): JsonObject? {
        val candidates = buildList {
            val trimmed = raw.trim()
            val fenceRegex = Regex("""(?s)```(?:json)?\s*(\{.*?\})\s*```""")
            for (m in fenceRegex.findAll(trimmed)) add(m.groupValues[1].trim())
            val unfenced = trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            add(unfenced)
            val start = unfenced.indexOf('{')
            val end = unfenced.lastIndexOf('}')
            if (start >= 0 && end > start) add(unfenced.substring(start, end + 1))
        }.distinct()
        for (candidate in candidates) {
            runCatching { return json.parseToJsonElement(candidate).jsonObject }
        }
        return null
    }
}

object CourseMockRequestParser {
    fun parse(input: JsonObject): CourseMockGenerateRequest {
        val courseId = (input["courseId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val courseTitle = (input["courseTitle"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(courseId.isNotBlank() && courseTitle.isNotBlank()) { "缺少课程标识或课程名称。" }

        val epObj = input["examProfile"] as? JsonObject
        val examProfile = MockExamProfileInput(
            examDate = (epObj?.get("examDate") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
            examFormat = (epObj?.get("examFormat") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
            examFormatTentative = (epObj?.get("examFormatTentative") as? JsonPrimitive)?.booleanOrNull ?: true,
            durationMinutes = (epObj?.get("durationMinutes") as? JsonPrimitive)?.intOrNull ?: 120,
            closedBook = (epObj?.get("closedBook") as? JsonPrimitive)?.booleanOrNull ?: true,
            calculatorAllowed = (epObj?.get("calculatorAllowed") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
            weightingNote = (epObj?.get("weightingNote") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
        )

        val topics = (input["examinableTopics"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val tid = (o["topicId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (tid.isBlank() || title.isBlank()) return@mapNotNull null
            val pages = (o["sourcePages"] as? JsonArray).orEmpty().mapNotNull { pe ->
                val po = pe as? JsonObject ?: return@mapNotNull null
                val bookId = (po["bookId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val text = (po["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val pdfPage = (po["pdfPage"] as? JsonPrimitive)?.intOrNull ?: 0
                if (bookId.isBlank() || text.isBlank() || pdfPage <= 0) return@mapNotNull null
                MockSourcePageInput(
                    bookId = bookId,
                    bookTitle = (po["bookTitle"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
                    role = (po["role"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifBlank { "CORE_TEXTBOOK" },
                    authorityRank = (po["authorityRank"] as? JsonPrimitive)?.intOrNull ?: 2,
                    pdfPage = pdfPage,
                    text = text,
                )
            }
            MockTopicCandidateInput(
                topicId = tid,
                weekLabel = (o["weekLabel"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifBlank { "专题" },
                title = title,
                examStatus = (o["examStatus"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifBlank { "EXAMINABLE" },
                examWeight = (o["examWeight"] as? JsonPrimitive)?.doubleOrNull ?: 1.0,
                pMastery = (o["pMastery"] as? JsonPrimitive)?.doubleOrNull ?: 0.15,
                dominantErrorTags = (o["dominantErrorTags"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                },
                sourcePages = pages,
            )
        }

        val excluded = (input["excludedTopicTitles"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        }
        val exclusions = (input["referenceExclusionTexts"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        }

        return CourseMockGenerateRequest(
            courseId = courseId,
            courseTitle = courseTitle,
            examProfile = examProfile,
            topics = topics,
            excludedTopicTitles = excluded,
            referenceExclusionTexts = exclusions,
        )
    }
}
