package com.coach.ai

import com.coach.domain.grade.NumericValue
import com.coach.domain.grade.EvidenceCheck
import com.coach.domain.grade.EvidenceSpan
import com.coach.domain.grade.VerbatimEvidence

/** Claude generates text-only choices; Codex independently solves them before VERIFIED admission. */
class QuestionPool(private val client: BridgeClient) {

    data class Rejected(val mcq: Mcq, val reasons: List<String>, val reviewerAnswer: String?)

    data class GenBatch(
        val kpId: String,
        val questions: List<Mcq>,
        val call: AiCall,
        val parsed: Boolean,
        val wallMs: Long,
        val failureReason: String? = null,
    )

    data class ReviewEntry(
        val kpId: String,
        val excerpt: String,
        val questions: List<Mcq>,
        val firstId: Int,
    )

    data class ReviewBatch(
        val verdicts: Map<Int, ReviewVerdict>,
        val call: AiCall?,
        val wallMs: Long,
        val failureReason: String? = null,
    )

    data class PoolResult(
        val kpId: String,
        val accepted: List<Mcq>,
        val rejected: List<Rejected>,
        val calls: List<AiCall>,
        val wallMs: Long,
        val parsed: Boolean,
        val reviewWallMs: Long = 0,
        val failureReason: String? = null,
    ) {
        val physicalCalls: Int get() = calls.sumOf { it.physicalCalls }
    }

    fun generateFor(
        kpId: String,
        objective: String,
        excerpt: String,
        count: Int,
        generatorModel: String = BridgeClient.CLAUDE,
        avoid: List<String> = emptyList(),
        background: Boolean = false,
    ): GenBatch {
        val started = System.nanoTime()
        val generation = client.chat(
            ChatRequest(
                task = "gen_mcq",
                model = generatorModel,
                system = Prompts.GEN_SYSTEM,
                user = Prompts.genMcq(objective, excerpt, count, avoid),
                stream = false,
                background = background,
                maxTokens = 4000,
            ),
        )
        val questions = when (generation) {
            is BridgeResult.Failure -> emptyList()
            is BridgeResult.Success -> runCatching { TaskParsing.mcqs(generation.text) }.getOrDefault(emptyList())
        }
        return GenBatch(
            kpId = kpId,
            questions = questions,
            call = generation.call,
            parsed = generation is BridgeResult.Success && questions.isNotEmpty(),
            failureReason = when {
                generation is BridgeResult.Failure -> if (generation.reason.contains("timeout", true) || generation.terminal?.reason?.contains("timeout", true) == true) "TIMEOUT" else if (generation.reason == "empty_content") "EMPTY" else "MALFORMED"
                questions.isNotEmpty() -> null
                (generation as BridgeResult.Success).text.trim() == "[]" -> "EMPTY"
                !generation.text.contains("[") && !generation.text.contains("{") -> "TEXT_ONLY"
                else -> "MALFORMED"
            },
            wallMs = (System.nanoTime() - started) / 1_000_000,
        )
    }

    fun reviewGroup(
        entries: List<ReviewEntry>,
        reviewerModel: String = BridgeClient.CODEX,
        background: Boolean = false,
    ): ReviewBatch {
        if (entries.isEmpty()) return ReviewBatch(emptyMap(), null, 0)
        val started = System.nanoTime()
        val response = client.chat(
            ChatRequest(
                task = "blind_review",
                model = reviewerModel,
                system = Prompts.REVIEW_SYSTEM,
                user = Prompts.blindReviewGroup(entries.map { ReviewGroupInput(it.kpId, it.excerpt, it.firstId, it.questions) }),
                stream = false,
                background = background,
                maxTokens = 4000,
            ),
        )
        val verdicts = when (response) {
            is BridgeResult.Failure -> emptyMap()
            is BridgeResult.Success -> runCatching { TaskParsing.verdicts(response.text).also { verdicts ->
                require(verdicts.map { it.id }.distinct().size == verdicts.size) { "Duplicate review IDs" }
            }.map { verdict ->
                val question = entries.firstOrNull { verdict.id in it.firstId until it.firstId + it.questions.size }
                    ?.let { it.questions[verdict.id - it.firstId] }
                requireNotNull(question) { "Unknown review ID" }
                TaskParsing.resolveVerdict(verdict, question)
            }.associateBy { it.id } }
                .getOrDefault(emptyMap())
        }
        return ReviewBatch(verdicts, response.call, (System.nanoTime() - started) / 1_000_000,
            if (response is BridgeResult.Failure && (response.reason.contains("timeout",true) || response.terminal?.reason?.contains("timeout",true) == true)) "TIMEOUT"
            else if (verdicts.isEmpty()) "MALFORMED" else null)
    }

    /** 一道题是否 VERIFIED：结构 + 原文引句 + 盲复核一致且无缺陷。返回拒绝原因（空 = 通过）。 */
    fun score(mcq: Mcq, verdict: ReviewVerdict?, excerpt: String): List<String> {
        val reasons = ArrayList<String>()
        reasons.addAll(QuestionGate.structural(mcq))
        if (!QuestionGate.quotedVerbatim(mcq, excerpt)) reasons.add("source_quote_not_verbatim")
        if (verdict == null) {
            reasons.add("blind_review_missing")
        } else {
            if (verdict.answer != mcq.correctOptionId) reasons.add("blind_review_disagrees:${verdict.answer}")
            if (verdict.defects.isNotEmpty()) reasons.addAll(verdict.defects.map { "defect:$it" })
        }
        return reasons
    }

    /** 单个 KP 的完整一批（生成 + 复核），给 `liveTest` 与构答题用。 */
    fun fill(
        kpId: String,
        objective: String,
        excerpt: String,
        count: Int,
        generatorModel: String = BridgeClient.CLAUDE,
        reviewerModel: String = BridgeClient.CODEX,
        avoid: List<String> = emptyList(),
        background: Boolean = false,
    ): PoolResult {
        require(modelVendor(generatorModel) != modelVendor(reviewerModel)) { "Review must use a different vendor" }
        val started = System.nanoTime()
        val generated = generateFor(kpId, objective, excerpt, count, generatorModel, avoid, background)
        if (generated.questions.isEmpty()) {
            return PoolResult(kpId, emptyList(), emptyList(), listOf(generated.call), generated.wallMs, generated.parsed, failureReason = generated.failureReason)
        }
        val review = reviewGroup(listOf(ReviewEntry(kpId, excerpt, generated.questions, 0)), reviewerModel, background)
        val accepted = ArrayList<Mcq>()
        val rejected = ArrayList<Rejected>()
        generated.questions.forEachIndexed { index, mcq ->
            val verdict = review.verdicts[index]
            val reasons = score(mcq, verdict, excerpt)
            if (reasons.isEmpty()) accepted.add(mcq) else rejected.add(Rejected(mcq, reasons, verdict?.answer))
        }
        val calls = listOfNotNull(generated.call, review.call)
        return PoolResult(
            kpId = kpId,
            accepted = accepted,
            rejected = rejected,
            calls = calls,
            wallMs = (System.nanoTime() - started) / 1_000_000,
            parsed = generated.parsed,
            reviewWallMs = review.wallMs,
            failureReason = if (accepted.isEmpty()) "MALFORMED" else null,
        )
    }
}

/**
 * 批改：先得到草稿，再让不同厂商复核；复核 CONFIRM 之后才算数。
 * 草稿与复核可分开执行；只有通过确定性检查和异厂复核的分数才计入。
 */
class GradePipeline(private val client: BridgeClient) {

    data class Outcome(
        val written: Boolean,
        val awarded: Double?,
        val defects: List<String>,
        val calls: List<AiCall>,
        val crossReview: CrossReview?,
        val evidence: List<String>,
        val rubricFingerprint: String,
        /** 草稿逐条（含模型给的引文）——不为通过，只为失败时能看清是模型引错了还是校验器误拒。 */
        val draft: DraftGrade? = null,
        val evidenceSpans: List<GradedEvidence> = emptyList(),
    )

    fun gradeConstructed(
        question: ConstructedQuestion,
        answer: String,
        expectedFingerprint: String = GradeGate.rubricFingerprint(question.rubric),
        graderModel: String = BridgeClient.CLAUDE,
        reviewerModel: String = BridgeClient.CODEX,
        background: Boolean = false,
        draftOnly: Boolean = false,
    ): Outcome {
        require(modelVendor(graderModel) != modelVendor(reviewerModel)) { "Review must use a different vendor" }
        val calls = ArrayList<AiCall>()
        val fingerprint = GradeGate.rubricFingerprint(question.rubric)
        if (fingerprint != expectedFingerprint) {
            return Outcome(false, null, listOf("rubric_changed"), calls, null, emptyList(), fingerprint)
        }
        val graded = client.chat(
            ChatRequest(
                task = "grade_constructed",
                model = graderModel,
                system = Prompts.GRADE_SYSTEM,
                user = Prompts.gradeConstructed(question, answer),
                stream = false,
                background = background,
                maxTokens = 2000,
            ),
        )
        calls.add(graded.call)
        if (graded is BridgeResult.Failure) {
            return Outcome(false, null, listOf("grade_call_${graded.reason}"), calls, null, emptyList(), fingerprint)
        }
        val draft = try {
            TaskParsing.grade((graded as BridgeResult.Success).text)
        } catch (error: Exception) {
            return Outcome(false, null, listOf("grade_unparsable"), calls, null, emptyList(), fingerprint)
        }
        fun checks(candidate: DraftGrade): List<String> =
            GradeGate.arithmetic(candidate, question.rubric) + GradeGate.evidence(answer, candidate).first

        var current = draft
        var spans = GradeGate.evidence(answer, current).second
        var defects = checks(current)
        if (defects.isNotEmpty()) {
            // ADR-0003 决定 6：失败带原因修复一次，仍失败才算批改不可用（这次修复也计入 ai_calls 与物理调用数）
            val repaired = client.chat(
                ChatRequest(
                    task = "repair_grade",
                    model = graderModel,
                    system = Prompts.GRADE_SYSTEM,
                    user = Prompts.repairGrade(question, answer, current, defects),
                    stream = false,
                    background = background,
                    maxTokens = 2000,
                ),
            )
            calls.add(repaired.call)
            if (repaired is BridgeResult.Success) {
                runCatching { TaskParsing.grade(repaired.text) }.getOrNull()?.let { fixed ->
                    current = fixed
                    spans = GradeGate.evidence(answer, fixed).second
                    defects = checks(fixed)
                }
            }
        }
        if (defects.isNotEmpty()) {
            return Outcome(false, null, defects, calls, null, spans, fingerprint, current)
        }
        val located = locate(answer, current)
        val prepared = Outcome(false, current.awarded, emptyList(), calls, null, spans, fingerprint, current, located)
        if (draftOnly) return prepared
        return reviewDraft(question, answer, prepared, reviewerModel, background)
    }

    /** Durable workers restore this input from the ledger; no second grading call is needed. */
    fun reviewDraft(question: ConstructedQuestion, answer: String, prepared: Outcome,
        reviewerModel: String = BridgeClient.CODEX, background: Boolean = true): Outcome {
        val current = requireNotNull(prepared.draft)
        if (prepared.rubricFingerprint != GradeGate.rubricFingerprint(question.rubric))
            return prepared.copy(defects=listOf("rubric_changed"))
        val defects = GradeGate.arithmetic(current, question.rubric) + GradeGate.evidence(answer,current).first
        if (defects.isNotEmpty()) return prepared.copy(defects=defects)
        val reviewed = client.chat(ChatRequest("cross_review_grade", reviewerModel, Prompts.CROSS_REVIEW_SYSTEM,
            Prompts.crossReviewGrade(question, answer, current, prepared.evidence), false,
            background=background, maxTokens=2500))
        val calls=prepared.calls + reviewed.call
        if (reviewed is BridgeResult.Failure) return prepared.copy(calls=calls,defects=listOf("review_call_${reviewed.reason}"))
        val verdict=runCatching { TaskParsing.crossReview((reviewed as BridgeResult.Success).text) }.getOrNull()
            ?: return prepared.copy(calls=calls,defects=listOf("review_unparsable"))
        val final = when(verdict.verdict) {
            "CONFIRM" -> current
            "REVISE" -> verdict.revisedGrade ?: return prepared.copy(calls=calls,crossReview=verdict,defects=listOf("review_revise"))
            else -> return prepared.copy(calls=calls,defects=listOf("review_unknown_verdict"))
        }
        val invalid=GradeGate.arithmetic(final,question.rubric) + GradeGate.evidence(answer,final).first
        if(invalid.isNotEmpty()) return prepared.copy(calls=calls,crossReview=verdict,defects=invalid)
        return prepared.copy(written=true,awarded=final.awarded,calls=calls,crossReview=verdict,draft=final,
            evidence=GradeGate.evidence(answer,final).second,evidenceSpans=locate(answer,final),defects=emptyList())
    }

    private fun locate(answer: String, grade: DraftGrade) = grade.items.mapNotNull { item ->
        (VerbatimEvidence.verify(answer,item.evidence) as? EvidenceCheck.Accepted)?.let { GradedEvidence(item.id,it.span) }
    }

    companion object {
        /** 选择题判分：无模型，立即 EXACT。 */
        fun gradeChoice(correctOptionId: String, chosenLetter: String, letterMap: Map<String, String>): Boolean =
            letterMap[correctOptionId] == chosenLetter.trim().uppercase()

        /** 计算题终值：认得就由服务端比，认不出（不是单个数值）返回 null 交回 AI 批过程。 */
        fun gradeNumeric(expected: String, learner: String): Boolean? {
            val expectedValue = NumericValue.parse(expected) ?: return null
            val learnerValue = NumericValue.parse(learner) ?: return null
            return NumericValue.close(learnerValue, expectedValue)
        }
    }
}

/** Vendor identity is checked both before dispatch and against bridge served_by. */
internal fun modelVendor(model: String): String = when {
    model.startsWith("claude") -> "anthropic"
    model.startsWith("gpt") -> "openai"
    else -> model.substringBefore("-")
}

data class GradedEvidence(val rubricItemId: String, val span: EvidenceSpan)
