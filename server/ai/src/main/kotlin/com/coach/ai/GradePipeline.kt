package com.coach.ai

import com.coach.domain.grade.NumericValue

/**
 * 题池：生成（默认 Codex 一次 3 道）+ 异厂盲复核（默认 Claude，不看键自己作答再比对）。
 * 只有「结构合格 + 原文引句逐字 + 盲复核一致且无缺陷」才算 VERIFIED（ADR-0003 决定 4）。
 *
 * 期中前（d2 裁决 d）批量出题一律「Codex 出、Claude 复核」，且 Claude 每日硬上限 60 次，
 * 所以复核支持一次看多个 KP 的题（每批 4 个 KP），生成仍按 KP 各一次。
 */
class QuestionPool(private val client: BridgeClient) {

    data class Rejected(val mcq: Mcq, val reasons: List<String>, val reviewerAnswer: String?)

    data class GenBatch(
        val kpId: String,
        val questions: List<Mcq>,
        val call: AiCall,
        val parsed: Boolean,
        val wallMs: Long,
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
    )

    data class PoolResult(
        val kpId: String,
        val accepted: List<Mcq>,
        val rejected: List<Rejected>,
        val calls: List<AiCall>,
        val wallMs: Long,
        val parsed: Boolean,
        val reviewWallMs: Long = 0,
    ) {
        val physicalCalls: Int get() = calls.sumOf { it.physicalCalls }
    }

    fun generateFor(
        kpId: String,
        objective: String,
        excerpt: String,
        count: Int,
        generatorModel: String = BridgeClient.CODEX,
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
            parsed = generation is BridgeResult.Success,
            wallMs = (System.nanoTime() - started) / 1_000_000,
        )
    }

    fun reviewGroup(
        entries: List<ReviewEntry>,
        reviewerModel: String = BridgeClient.CLAUDE,
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
            is BridgeResult.Success -> runCatching { TaskParsing.verdicts(response.text).associateBy { it.id } }
                .getOrDefault(emptyMap())
        }
        return ReviewBatch(verdicts, response.call, (System.nanoTime() - started) / 1_000_000)
    }

    /** 一道题是否 VERIFIED：结构 + 原文引句 + 盲复核一致且无缺陷。返回拒绝原因（空 = 通过）。 */
    fun score(mcq: Mcq, verdict: ReviewVerdict?, excerpt: String): List<String> {
        val reasons = ArrayList<String>()
        reasons.addAll(QuestionGate.structural(mcq))
        if (!QuestionGate.quotedVerbatim(mcq, excerpt)) reasons.add("source_quote_not_verbatim")
        if (verdict == null) {
            reasons.add("blind_review_missing")
        } else {
            if (verdict.answer != mcq.key) reasons.add("blind_review_disagrees:${verdict.answer}")
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
        generatorModel: String = BridgeClient.CODEX,
        reviewerModel: String = BridgeClient.CLAUDE,
        avoid: List<String> = emptyList(),
        background: Boolean = false,
    ): PoolResult {
        val started = System.nanoTime()
        val generated = generateFor(kpId, objective, excerpt, count, generatorModel, avoid, background)
        if (generated.questions.isEmpty()) {
            return PoolResult(kpId, emptyList(), emptyList(), listOf(generated.call), generated.wallMs, generated.parsed)
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
        )
    }
}

/**
 * 批改：先得到草稿，再让不同厂商复核；复核 CONFIRM 之后才算数。
 * 任何一步不成立都不写任何结果（fail closed，ADR-0003 决定 8）。
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
    )

    fun gradeConstructed(
        question: ConstructedQuestion,
        answer: String,
        expectedFingerprint: String = GradeGate.rubricFingerprint(question.rubric),
        graderModel: String = BridgeClient.CLAUDE,
        reviewerModel: String = BridgeClient.CODEX,
        background: Boolean = false,
    ): Outcome {
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
        val (evidenceDefects, spans) = GradeGate.evidence(answer, draft)
        val defects = ArrayList<String>()
        defects.addAll(GradeGate.arithmetic(draft, question.rubric))
        defects.addAll(evidenceDefects)
        if (defects.isNotEmpty()) {
            return Outcome(false, null, defects, calls, null, spans, fingerprint, draft)
        }
        val reviewed = client.chat(
            ChatRequest(
                task = "cross_review_grade",
                model = reviewerModel,
                system = Prompts.CROSS_REVIEW_SYSTEM,
                user = Prompts.crossReviewGrade(question, answer, draft, spans),
                stream = false,
                background = background,
                maxTokens = 1500,
            ),
        )
        calls.add(reviewed.call)
        if (reviewed is BridgeResult.Failure) {
            return Outcome(false, null, listOf("review_call_${reviewed.reason}"), calls, null, spans, fingerprint)
        }
        val verdict = try {
            TaskParsing.crossReview((reviewed as BridgeResult.Success).text)
        } catch (error: Exception) {
            return Outcome(false, null, listOf("review_unparsable"), calls, null, spans, fingerprint)
        }
        if (verdict.verdict != "CONFIRM") {
            return Outcome(false, null, listOf("review_${verdict.verdict.lowercase()}"), calls, verdict, spans, fingerprint, draft)
        }
        return Outcome(true, draft.awarded, emptyList(), calls, verdict, spans, fingerprint, draft)
    }

    companion object {
        /** 选择题判分：无模型，立即 EXACT。 */
        fun gradeChoice(key: String, chosen: String): Boolean = key.trim().uppercase() == chosen.trim().uppercase()

        /** 计算题终值：认得就由服务端比，认不出（不是单个数值）返回 null 交回 AI 批过程。 */
        fun gradeNumeric(expected: String, learner: String): Boolean? {
            val expectedValue = NumericValue.parse(expected) ?: return null
            val learnerValue = NumericValue.parse(learner) ?: return null
            return NumericValue.close(learnerValue, expectedValue)
        }
    }
}
