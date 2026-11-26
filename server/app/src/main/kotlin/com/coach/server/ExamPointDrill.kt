package com.coach.server

import com.coach.ai.BridgeClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Teaching questions for one exam point.
 *
 * Haiku 5.5 Batch writes the items. A GPT model on the bridge answers them
 * without seeing the key, the reasons, or the writer's citations. A mismatch
 * gets one more ask. Anything still inconsistent is not stored.
 * Past-paper text never enters the prompt. Textbook spans outrank slides.
 */
object ExamPointDrillLimits {
    const val GENERATOR_MODEL: String = BridgeClient.HAIKU
    const val CHECKER_MODEL: String = BridgeClient.GPT_62_ASTRA
    const val MIN_CHOICES: Int = 3
    const val MAX_CHOICES: Int = 5
    const val MAX_TOKENS: Int = 8_000
}

data class DrillOptionDraft(
    val letter: String,
    val text: String,
    val correct: Boolean,
    val reason: String,
    val citation: JsonObject?,
)

data class ChoiceDraft(
    val id: String,
    val stem: String,
    val options: List<DrillOptionDraft>,
)

data class EssayParagraphDraft(
    val claim: String,
    val citation: JsonObject?,
)

data class EssayDraft(
    val thesis: String,
    val paragraphs: List<EssayParagraphDraft>,
)

data class SupportVote(
    val letter: String? = null,
    val supported: Boolean? = null,
    val citation: JsonObject? = null,
)

data class ExamPointAdmission(
    val choices: JsonArray,
    val essay: JsonObject?,
    val missingChoices: Int,
    val essayMissing: Boolean,
    val discards: Map<String, Int>,
    val checked: Int,
    val agreed: Int,
    val emptyReason: String?,
    val generatorModel: String,
    val checkerModel: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("choices", choices)
        if (essay != null) put("essay", essay) else put("essay", kotlinx.serialization.json.JsonNull)
        put("missingChoices", missingChoices)
        put("essayMissing", essayMissing)
        put("discards", buildJsonObject { discards.forEach { (reason, count) -> put(reason, count) } })
        put("checked", checked)
        put("agreed", agreed)
        if (emptyReason != null) put("emptyReason", emptyReason) else put("emptyReason", kotlinx.serialization.json.JsonNull)
        put("generatorModel", generatorModel)
        put("checkerModel", checkerModel)
    }
}

object ExamPointDrillGate {
    private val letters = listOf("A", "B", "C", "D")
    private val essayTechniques = setOf("EXPLANATION", "COMPARISON", "APPLICATION")
    private val essayFormat = Regex("""论述|简答|essay|discuss|short\s+answer""", RegexOption.IGNORE_CASE)
    private val choiceOnlyFormat = Regex("""只考选择|选择题|multiple[\s-]*choice|\bmcq\b""", RegexOption.IGNORE_CASE)

    fun essayEligible(examFormat: String, techniques: List<String>): Boolean {
        val named = techniques.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (named.any { it in essayTechniques }) return true
        if (essayFormat.containsMatchIn(examFormat)) return true
        if (named.isNotEmpty()) return false
        return !choiceOnlyFormat.containsMatchIn(examFormat)
    }

    fun prepare(blocks: List<CourseMaterialTextBlock>): Pair<List<CourseMaterialTextBlock>, String?> {
        var droppedPastExam = false
        val kept = blocks.mapNotNull { block ->
            if (block.role.equals("PAST_EXAM", ignoreCase = true) || CourseQualityGates.isPastExamTitle(block.bookTitle)) {
                droppedPastExam = true
                null
            } else {
                block
            }
        }.sortedWith(compareBy({ it.authorityRank }, { it.page }, { it.bookId }))
        val reason = when {
            kept.isNotEmpty() -> null
            droppedPastExam -> "只有往年题，不能进题库"
            else -> "没有可引用的教材或讲义"
        }
        return kept to reason
    }

    fun parseDrafts(raw: JsonObject): List<ChoiceDraft> {
        val rows = raw["choices"] as? JsonArray ?: return emptyList()
        return rows.mapIndexedNotNull { index, el ->
            val obj = el as? JsonObject ?: return@mapIndexedNotNull null
            val options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { optEl ->
                val opt = optEl as? JsonObject ?: return@mapNotNull null
                DrillOptionDraft(
                    letter = opt.str("letter").trim().uppercase(),
                    text = opt.str("text").trim(),
                    correct = (opt["correct"] as? JsonPrimitive)?.booleanOrNull == true,
                    reason = opt.str("reason").trim(),
                    citation = opt["citation"] as? JsonObject,
                )
            }
            ChoiceDraft(
                id = obj.str("id").ifBlank { "c${index + 1}" },
                stem = obj.str("stem").trim(),
                options = options,
            )
        }.take(ExamPointDrillLimits.MAX_CHOICES)
    }

    fun parseEssay(raw: JsonObject): EssayDraft? {
        val obj = raw["essay"] as? JsonObject ?: return null
        val paragraphs = (obj["paragraphs"] as? JsonArray).orEmpty().mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            EssayParagraphDraft(claim = row.str("claim").trim(), citation = row["citation"] as? JsonObject)
        }
        val thesis = obj.str("thesis").trim()
        if (thesis.isBlank() && paragraphs.isEmpty()) return null
        return EssayDraft(thesis, paragraphs)
    }

    fun parseVotes(raw: JsonObject?): Pair<Map<String, SupportVote>, List<SupportVote>> {
        if (raw == null) return emptyMap<String, SupportVote>() to emptyList()
        val answers = (raw["answers"] as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id")
            if (id.isBlank()) return@mapNotNull null
            id to SupportVote(
                letter = obj.str("letter").trim().uppercase().ifBlank { null },
                citation = obj["support"] as? JsonObject ?: obj["citation"] as? JsonObject,
            )
        }.toMap()
        val essay = (raw["essay"] as? JsonArray).orEmpty().map { el ->
            val obj = el as? JsonObject
            if (obj == null) {
                SupportVote(supported = false)
            } else {
                SupportVote(
                    supported = (obj["supported"] as? JsonPrimitive)?.booleanOrNull == true,
                    citation = obj["support"] as? JsonObject ?: obj["citation"] as? JsonObject,
                )
            }
        }
        return answers to essay
    }

    /** Stem and option text only. The checker must not see the key, the reasons, or the writer's citations. */
    fun blindPayload(choices: List<ChoiceDraft>, essay: EssayDraft?): JsonObject = buildJsonObject {
        put("choices", buildJsonArray {
            for (choice in choices) {
                add(buildJsonObject {
                    put("id", choice.id)
                    put("stem", choice.stem)
                    put("options", buildJsonArray {
                        for (opt in choice.options) {
                            add(buildJsonObject {
                                put("letter", opt.letter)
                                put("text", opt.text)
                            })
                        }
                    })
                })
            }
        })
        if (essay != null) {
            put("essay", buildJsonObject {
                put("thesis", essay.thesis)
                put("paragraphs", buildJsonArray {
                    essay.paragraphs.forEach { add(buildJsonObject { put("claim", it.claim) }) }
                })
            })
        }
    }

    fun admit(
        blocks: List<CourseMaterialTextBlock>,
        essayWanted: Boolean,
        drafts: List<ChoiceDraft>,
        votes: Map<String, List<SupportVote>>,
        essay: EssayDraft?,
        essayVotes: List<List<SupportVote>>,
        generatorModel: String,
        checkerModel: String,
    ): ExamPointAdmission {
        val discards = linkedMapOf<String, Int>()
        fun drop(reason: String) { discards[reason] = (discards[reason] ?: 0) + 1 }
        if (!generatorModel.startsWith("claude-haiku")) {
            drop("wrong_vendor")
            return emptyAdmission(3, essayWanted, discards, 0, 0, "出题模型不是 Haiku，整批未入库", generatorModel, checkerModel)
        }
        val checkerOk = checkerModel.startsWith("gpt-")
        val byPage = blocks.groupBy { it.bookId to it.page }
        val byBook = blocks.groupBy { it.bookId }
        val textbookIds = blocks.filter { it.role == "CORE_TEXTBOOK" }.map { it.bookId }.toSet()
        val sourceTexts = blocks.map { it.text }
        val stored = mutableListOf<JsonObject>()
        var checked = 0
        var agreed = 0
        for (draft in drafts) {
            val local = localChoiceFailure(draft, byPage, byBook, textbookIds, sourceTexts)
            if (local != null) {
                drop(local)
                continue
            }
            val history = votes[draft.id].orEmpty()
            if (!checkerOk || history.isEmpty()) {
                drop(if (!checkerOk) "wrong_vendor" else "answer_mismatch")
                continue
            }
            val verdicts = history.map { vote ->
                checked += 1
                val reason = choiceVerdict(draft, vote, byPage, byBook, textbookIds)
                if (reason == null) agreed += 1
                reason
            }
            val finalReason = verdicts.last()
            if (finalReason == null) stored += storedChoice(draft, byPage, byBook)
            else drop(finalReason)
        }
        val kept = stored.take(ExamPointDrillLimits.MAX_CHOICES)
        val missing = (ExamPointDrillLimits.MIN_CHOICES - kept.size).coerceAtLeast(0)
        val essayResult = if (!essayWanted) {
            null to false
        } else {
            judgeEssay(essay, essayVotes, checkerOk, byPage, byBook, textbookIds, sourceTexts, ::drop) { ok ->
                checked += 1
                if (ok) agreed += 1
            }
        }
        val emptyReason = when {
            kept.isNotEmpty() -> null
            missing > 0 && discards.isEmpty() -> "模型没有写出可用的题"
            else -> null
        }
        return ExamPointAdmission(
            choices = JsonArray(kept),
            essay = essayResult.first,
            missingChoices = missing,
            essayMissing = essayResult.second,
            discards = discards,
            checked = checked,
            agreed = agreed,
            emptyReason = emptyReason,
            generatorModel = generatorModel,
            checkerModel = checkerModel,
        )
    }

    private fun judgeEssay(
        essay: EssayDraft?,
        votes: List<List<SupportVote>>,
        checkerOk: Boolean,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
        sourceTexts: List<String>,
        drop: (String) -> Unit,
        count: (Boolean) -> Unit,
    ): Pair<JsonObject?, Boolean> {
        val local = localEssayFailure(essay, byPage, byBook, textbookIds, sourceTexts)
        if (local != null || essay == null) {
            drop(local ?: "malformed")
            return null to true
        }
        if (!checkerOk || votes.isEmpty()) {
            drop(if (!checkerOk) "wrong_vendor" else "essay_unsupported")
            return null to true
        }
        val finals = votes.map { round ->
            val aligned = essay.paragraphs.size == round.size
            var ok = aligned
            for (index in essay.paragraphs.indices) {
                val vote = if (aligned) round[index] else null
                val verdict = vote?.supported == true &&
                    verifiedSupport(vote.citation, byPage, byBook, textbookIds) != null
                count(verdict)
                if (!verdict) ok = false
            }
            ok
        }
        if (finals.last()) return storedEssay(essay, byPage, byBook) to false
        drop("essay_unsupported")
        return null to true
    }

    private fun localChoiceFailure(
        draft: ChoiceDraft,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
        sourceTexts: List<String>,
    ): String? {
        if (draft.stem.isBlank() || draft.options.size != 4) return "malformed"
        if (draft.options.map { it.letter } != letters) return "malformed"
        if (draft.options.count { it.correct } != 1) return "malformed"
        if (draft.options.any { it.text.isBlank() || it.reason.isBlank() }) return "malformed"
        if (!CourseQualityGates.auditZeroOverlap(listOf(draft.stem), sourceTexts).passed) return "recall_stem"
        for (opt in draft.options) {
            if (verifiedSupport(opt.citation, byPage, byBook, textbookIds) == null) return "source_mismatch"
        }
        return null
    }

    private fun localEssayFailure(
        essay: EssayDraft?,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
        sourceTexts: List<String>,
    ): String? {
        if (essay == null) return "malformed"
        if (essay.thesis.isBlank() || essay.paragraphs.size !in 2..6) return "malformed"
        if (essay.paragraphs.any { it.claim.isBlank() }) return "malformed"
        val prose = listOf(essay.thesis) + essay.paragraphs.map { it.claim }
        if (!CourseQualityGates.auditZeroOverlap(prose, sourceTexts).passed) return "recall_stem"
        for (paragraph in essay.paragraphs) {
            if (verifiedSupport(paragraph.citation, byPage, byBook, textbookIds) == null) return "source_mismatch"
        }
        return null
    }

    private fun choiceVerdict(
        draft: ChoiceDraft,
        vote: SupportVote,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
    ): String? {
        val key = draft.options.single { it.correct }.letter
        if (!vote.letter.equals(key, ignoreCase = true)) return "answer_mismatch"
        if (verifiedSupport(vote.citation, byPage, byBook, textbookIds) == null) return "source_mismatch"
        return null
    }

    private fun verifiedSupport(
        raw: JsonObject?,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
    ): CourseVerifiedCitation? {
        if (raw == null) return null
        val verified = CourseQualityGates.verifyCitation(raw, byPage, byBook) ?: return null
        if (textbookIds.isNotEmpty() && verified.bookId !in textbookIds) return null
        return verified
    }

    private fun storedChoice(
        draft: ChoiceDraft,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
    ): JsonObject = buildJsonObject {
        put("id", draft.id)
        put("stem", draft.stem)
        put("options", buildJsonArray {
            for (opt in draft.options) {
                val verified = CourseQualityGates.verifyCitation(opt.citation!!, byPage, byBook)!!
                add(buildJsonObject {
                    put("letter", opt.letter)
                    put("text", opt.text)
                    put("correct", opt.correct)
                    put("reason", opt.reason)
                    put("citation", verified.toJson())
                })
            }
        })
    }

    private fun storedEssay(
        essay: EssayDraft,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
    ): JsonObject = buildJsonObject {
        put("thesis", essay.thesis)
        put("paragraphs", buildJsonArray {
            for (paragraph in essay.paragraphs) {
                val verified = CourseQualityGates.verifyCitation(paragraph.citation!!, byPage, byBook)!!
                add(buildJsonObject {
                    put("claim", paragraph.claim)
                    put("citation", verified.toJson())
                })
            }
        })
    }

    fun emptyAdmission(
        missing: Int,
        essayMissing: Boolean,
        discards: Map<String, Int>,
        checked: Int,
        agreed: Int,
        emptyReason: String?,
        generatorModel: String,
        checkerModel: String,
    ) = ExamPointAdmission(
        choices = buildJsonArray {},
        essay = null,
        missingChoices = missing,
        essayMissing = essayMissing,
        discards = discards,
        checked = checked,
        agreed = agreed,
        emptyReason = emptyReason,
        generatorModel = generatorModel,
        checkerModel = checkerModel,
    )
}

class ExamPointDrillPipeline(
    private val generator: BatchAiTransport,
    private val checker: BatchAiTransport,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(input: JsonObject): JsonObject {
        val topicId = input.str("topicId").ifBlank { "point" }
        val topicTitle = input.str("topicTitle").ifBlank { "这个考点" }
        val techniques = (input["examTechniques"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val essayWanted = ExamPointDrillGate.essayEligible(input.str("examFormat"), techniques)
        val blocks = readBlocks(input)
        val (kept, emptyReason) = ExamPointDrillGate.prepare(blocks)
        if (kept.isEmpty()) {
            return ExamPointDrillGate.emptyAdmission(
                ExamPointDrillLimits.MIN_CHOICES, essayWanted, emptyMap(), 0, 0, emptyReason, "", "",
            ).toJson()
        }
        val scope = input.str("sourcesHash").ifBlank {
            kept.joinToString("|") { "${it.bookId}:${it.page}:${it.startUtf16}:${it.text.hashCode()}" }
        }.take(80)
        val first = generator.generateSection(
            systemPrompt = generatorSystem(essayWanted),
            userPrompt = sourcePrompt(topicTitle, kept, essayWanted),
            task = "exam_point_drill",
            batchKey = "drill-gen-$topicId-$scope",
            maxTokens = ExamPointDrillLimits.MAX_TOKENS,
        )
        if (!first.model.startsWith("claude-haiku")) {
            return ExamPointDrillGate.emptyAdmission(
                ExamPointDrillLimits.MIN_CHOICES, essayWanted, mapOf("wrong_vendor" to 1), 0, 0,
                "出题模型不是 Haiku，整批未入库", first.model, "",
            ).toJson()
        }
        val firstJson = extractJsonObject(first.text)
        var drafts = ExamPointDrillGate.parseDrafts(firstJson)
        var essay = if (essayWanted) ExamPointDrillGate.parseEssay(firstJson) else null
        val indexes = citationIndex(kept)
        val sourceTexts = kept.map { it.text }
        val textbookIds = kept.filter { it.role == "CORE_TEXTBOOK" }.map { it.bookId }.toSet()
        val failedIds = drafts.filter { localChoiceNeedsRewrite(it, indexes, textbookIds, sourceTexts) }.map { it.id }
        val essayNeedsRewrite = essayWanted && essayLocalNeedsRewrite(essay, indexes, textbookIds, sourceTexts)
        if (failedIds.isNotEmpty() || essayNeedsRewrite) {
            val repair = generator.generateSection(
                systemPrompt = generatorSystem(essayWanted),
                userPrompt = repairPrompt(topicTitle, kept, essayWanted, failedIds, essayNeedsRewrite),
                task = "exam_point_drill",
                batchKey = "drill-repair-$topicId-$scope",
                maxTokens = ExamPointDrillLimits.MAX_TOKENS,
            )
            if (repair.model.startsWith("claude-haiku")) {
                val repaired = extractJsonObject(repair.text)
                val rewritten = ExamPointDrillGate.parseDrafts(repaired).associateBy { it.id }
                drafts = drafts.map { draft -> if (draft.id in failedIds) rewritten[draft.id] ?: draft else draft }
                val extras = rewritten.filterKeys { id -> drafts.none { it.id == id } }.values
                drafts = (drafts + extras).take(ExamPointDrillLimits.MAX_CHOICES)
                if (essayNeedsRewrite) essay = ExamPointDrillGate.parseEssay(repaired) ?: essay
            }
        }
        val ready = drafts.filterNot { localChoiceNeedsRewrite(it, indexes, textbookIds, sourceTexts) }
        val essayReady = essay?.takeUnless { essayLocalNeedsRewrite(it, indexes, textbookIds, sourceTexts) }
        val votes = linkedMapOf<String, MutableList<SupportVote>>()
        val essayVotes = mutableListOf<List<SupportVote>>()
        var checkerModel = ""
        if (ready.isNotEmpty() || essayReady != null) {
            val checked = askChecker(topicTitle, kept, ready, essayReady, "drill-check-$topicId-$scope")
            checkerModel = checked.model
            if (checked.model.startsWith("gpt-")) {
                val (answers, essayRound) = ExamPointDrillGate.parseVotes(extractJsonObject(checked.text))
                ready.forEach { draft -> votes.getOrPut(draft.id) { mutableListOf() }.add(answers[draft.id] ?: SupportVote()) }
                if (essayReady != null) essayVotes += essayRound
                val again = ready.filter { draft ->
                    val vote = answers[draft.id]
                    choiceNeedsRecheck(draft, vote, indexes, textbookIds)
                }
                val essayAgain = essayReady != null && !essayRoundAgrees(essayReady, essayRound, indexes, textbookIds)
                if (again.isNotEmpty() || essayAgain) {
                    val second = askChecker(
                        topicTitle, kept, again, if (essayAgain) essayReady else null, "drill-recheck-$topicId-$scope",
                    )
                    if (second.model.startsWith("gpt-")) {
                        checkerModel = second.model
                        val (answers2, essayRound2) = ExamPointDrillGate.parseVotes(extractJsonObject(second.text))
                        again.forEach { draft -> votes.getOrPut(draft.id) { mutableListOf() }.add(answers2[draft.id] ?: SupportVote()) }
                        if (essayAgain) essayVotes += essayRound2
                    }
                }
            }
        }
        return ExamPointDrillGate.admit(
            blocks = kept,
            essayWanted = essayWanted,
            drafts = drafts,
            votes = votes,
            essay = essay,
            essayVotes = essayVotes,
            generatorModel = first.model,
            checkerModel = checkerModel,
        ).toJson()
    }

    private suspend fun askChecker(
        topicTitle: String,
        blocks: List<CourseMaterialTextBlock>,
        choices: List<ChoiceDraft>,
        essay: EssayDraft?,
        batchKey: String,
    ): BatchTransportReply = checker.generateSection(
        systemPrompt = """
            你看不到标准答案，也不要猜测出题人的意图。只根据材料独立作答。
            每道单选给出 letter，并在 support 里抄一处逐字支持该选项的原句（bookId、page、quote）。
            有教材时 support 必须来自 CORE_TEXTBOOK。
            论述提纲的每个要点按顺序给出：有原句支撑则 supported 为 true 并附 support，否则 supported 为 false。
            只输出 JSON：{"answers":[{"id":"...","letter":"A","support":{"bookId":"...","page":1,"quote":"..."}}],"essay":[{"supported":true,"support":{"bookId":"...","page":1,"quote":"..."}}]}
        """.trimIndent(),
        userPrompt = buildJsonObject {
            put("topicTitle", topicTitle)
            put("materials", materialsJson(blocks))
            put("paper", ExamPointDrillGate.blindPayload(choices, essay))
        }.toString(),
        task = "exam_point_check",
        batchKey = batchKey,
        maxTokens = ExamPointDrillLimits.MAX_TOKENS,
    )

    private fun localChoiceNeedsRewrite(
        draft: ChoiceDraft,
        indexes: CitationIndex,
        textbookIds: Set<String>,
        sourceTexts: List<String>,
    ): Boolean =
        draft.stem.isBlank() || draft.options.size != 4 || draft.options.map { it.letter } != listOf("A", "B", "C", "D") ||
            draft.options.count { it.correct } != 1 || draft.options.any { it.text.isBlank() || it.reason.isBlank() } ||
            !CourseQualityGates.auditZeroOverlap(listOf(draft.stem), sourceTexts).passed ||
            draft.options.any { verifiedSupport(it.citation, indexes.byPage, indexes.byBook, textbookIds) == null }

    private fun essayLocalNeedsRewrite(
        essay: EssayDraft?,
        indexes: CitationIndex,
        textbookIds: Set<String>,
        sourceTexts: List<String>,
    ): Boolean {
        if (essay == null || essay.thesis.isBlank() || essay.paragraphs.size !in 2..6) return true
        if (essay.paragraphs.any { it.claim.isBlank() }) return true
        val prose = listOf(essay.thesis) + essay.paragraphs.map { it.claim }
        if (!CourseQualityGates.auditZeroOverlap(prose, sourceTexts).passed) return true
        return essay.paragraphs.any { verifiedSupport(it.citation, indexes.byPage, indexes.byBook, textbookIds) == null }
    }

    private fun choiceNeedsRecheck(
        draft: ChoiceDraft,
        vote: SupportVote?,
        indexes: CitationIndex,
        textbookIds: Set<String>,
    ): Boolean {
        if (vote?.letter == null) return true
        val key = draft.options.single { it.correct }.letter
        if (!vote.letter.equals(key, ignoreCase = true)) return true
        return verifiedSupport(vote.citation, indexes.byPage, indexes.byBook, textbookIds) == null
    }

    private fun essayRoundAgrees(
        essay: EssayDraft,
        round: List<SupportVote>,
        indexes: CitationIndex,
        textbookIds: Set<String>,
    ): Boolean = essay.paragraphs.size == round.size && essay.paragraphs.indices.all { index ->
        val vote = round[index]
        vote.supported == true && verifiedSupport(vote.citation, indexes.byPage, indexes.byBook, textbookIds) != null
    }

    private fun verifiedSupport(
        raw: JsonObject?,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
    ): CourseVerifiedCitation? {
        if (raw == null) return null
        val verified = CourseQualityGates.verifyCitation(raw, byPage, byBook) ?: return null
        if (textbookIds.isNotEmpty() && verified.bookId !in textbookIds) return null
        return verified
    }

    private fun generatorSystem(essayWanted: Boolean) = """
        你在为备考写教学题。题干考理解，不考原句复述，不得连续摘抄材料里 8 个英文词或 12 个汉字。
        写 5 道单选题。每题恰好 4 个选项，字母固定为 A、B、C、D，恰好一个 correct 为 true。
        每个选项写一句对或错的理由，并给 citation：bookId、page、startUtf16、endUtf16、quote。quote 必须是材料里的连续原文。
        材料里有 CORE_TEXTBOOK 时，引用只能来自教材，不能引用课件或讲义。
        不要使用往年题，不要输出材料里没有的页码。
        ${if (essayWanted) "另外写一份论述提纲：thesis，2 到 6 个 paragraphs，每段 claim 同样挂一处教材原句。论点用自己的话，不要照抄原文。" else "不要写论述提纲，essay 设为 null。"}
        只输出 JSON：{"choices":[{"id":"c1","stem":"...","options":[{"letter":"A","text":"...","correct":false,"reason":"...","citation":{"bookId":"...","page":1,"startUtf16":0,"endUtf16":1,"quote":"..."}}]}],"essay":null}
    """.trimIndent()

    private fun sourcePrompt(topicTitle: String, blocks: List<CourseMaterialTextBlock>, essayWanted: Boolean) = buildJsonObject {
        put("topicTitle", topicTitle)
        put("essay", essayWanted)
        put("materials", materialsJson(blocks))
    }.toString()

    private fun repairPrompt(
        topicTitle: String,
        blocks: List<CourseMaterialTextBlock>,
        essayWanted: Boolean,
        ids: List<String>,
        essay: Boolean,
    ) = buildJsonObject {
        put("topicTitle", topicTitle)
        put("essay", essayWanted && essay)
        put("rewriteChoiceIds", JsonArray(ids.map { JsonPrimitive(it) }))
        put("note", "这些题的出处对不上或题干不像理解题。请只重写点名的题。不要假设哪个选项该对。")
        put("materials", materialsJson(blocks))
    }.toString()

    private fun materialsJson(blocks: List<CourseMaterialTextBlock>) = buildJsonArray {
        for (block in blocks) {
            add(buildJsonObject {
                put("blockId", block.blockId)
                put("bookId", block.bookId)
                put("bookTitle", block.bookTitle)
                put("role", block.role)
                put("page", block.page)
                put("startUtf16", block.startUtf16)
                put("text", block.text)
            })
        }
    }

    private fun readBlocks(input: JsonObject): List<CourseMaterialTextBlock> =
        (input["blocks"] as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val text = obj.str("text")
            if (text.isBlank()) return@mapNotNull null
            val role = obj.str("role").ifBlank { "LECTURE_SLIDES" }
            CourseMaterialTextBlock(
                blockId = obj.str("blockId").ifBlank { "${obj.str("bookId")}:p${obj.int("page")}" },
                bookId = obj.str("bookId"),
                bookTitle = obj.str("bookTitle"),
                role = role,
                authorityRank = obj.int("authorityRank").takeIf { it > 0 } ?: if (role == "CORE_TEXTBOOK") 1 else 2,
                page = obj.int("page").coerceAtLeast(1),
                startUtf16 = obj.int("startUtf16"),
                text = text,
            )
        }

    private fun citationIndex(blocks: List<CourseMaterialTextBlock>) = CitationIndex(
        blocks.groupBy { it.bookId to it.page },
        blocks.groupBy { it.bookId },
    )

    private fun extractJsonObject(raw: String): JsonObject {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return buildJsonObject {}
        return runCatching { json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject }.getOrDefault(buildJsonObject {})
    }

    private data class CitationIndex(
        val byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        val byBook: Map<String, List<CourseMaterialTextBlock>>,
    )
}

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
