package com.coach.domain.reviewqueue

import kotlinx.serialization.Serializable

/**
 * 题库给出的一个可练候选。它只描述题库里真实持有的一道题，队列不生成、不补题。
 * [keyVerified] 与 [availability] 都由题库侧如实报告；选择器仍会逐条复核，因为端口不可信。
 */
@Serializable
data class VariantCandidate(
    val courseId: String,
    val kpId: String,
    val question: QueueQuestion,
    val availability: QueueAvailability = QueueAvailability.VALID,
    val keyVerified: Boolean = false,
    val variantGroupId: String? = null,
    val agentRunId: String? = null,
)

/**
 * 题库候选选择端口（可注入）。与主线选题共用同一条「同课程、同考点、已核键、没出过题」的池子；
 * 实现方只报真实持有的题，没有就返回空列表，由队列保持「缺题」，不伪造一道可练题。
 */
fun interface VariantCandidatePort {
    fun candidates(courseId: String, agentRunId: String?, kpId: String): List<VariantCandidate>
}

/** 新变式练习的作答身份。默认由条目与题目身份确定，重试得同一条，不会重复建项。 */
fun interface VariantAttemptId {
    fun allocate(entryId: String, question: QueueQuestion): String
}

data class VariantSelection(val entryId: String, val variant: QueueVariant?)

data class VariantAllocation(
    val queue: ReviewQueueCandidate,
    /** 每条被尝试的缺口：拿到变式就是 [VariantSelection.variant]，没有就是 null（保持缺题）。 */
    val selections: List<VariantSelection> = emptyList(),
)

private val deterministicAttemptIds = VariantAttemptId { entryId, question ->
    queueKey("variant", entryId, question.identity())
}

/**
 * 把还停在 [QueueReadiness.NEEDS_VARIANT] 的条目接到题库端口上：只在真的选到
 * 同课程、同考点、同运行范围、有效版本、已核键、且不是原题的题时才分配，其余条目原样保留为缺题。
 * 已完成、待同步的条目不参与，避免覆盖已有作答身份。
 */
fun ReviewQueueCandidate.allocateVariants(
    port: VariantCandidatePort,
    attemptIds: VariantAttemptId = deterministicAttemptIds,
): VariantAllocation {
    var queue = this
    val selections = entries.filter { !it.completed && it.readiness == QueueReadiness.NEEDS_VARIANT }.map { entry ->
        val variant = selectVariant(entry, port.candidates(scope.courseId, scope.agentRunId, entry.kpId), attemptIds)
        if (variant != null) queue = queue.assignVariant(entry.id, variant)
        VariantSelection(entry.id, variant)
    }
    return VariantAllocation(queue, selections)
}

/** 固定顺序的逐个复核：同一份候选永远选出同一道题，端口返回顺序不影响结果。 */
private fun ReviewQueueCandidate.selectVariant(
    entry: QueueEntry,
    candidates: List<VariantCandidate>,
    attemptIds: VariantAttemptId,
): QueueVariant? {
    val ordered = candidates.sortedWith(
        compareBy({ it.variantGroupId.orEmpty() }, { it.question.identity() }))
    for (candidate in ordered) {
        if (candidate.courseId != scope.courseId || candidate.kpId != entry.kpId) continue
        if (candidate.agentRunId != scope.agentRunId) continue
        if (candidate.availability != QueueAvailability.VALID || !candidate.keyVerified) continue
        if (candidate.question.id.isBlank()) continue
        // The original is never replayed as its own variant, and one question is never allocated twice.
        if (candidate.question.id == entry.originalQuestion?.id) continue
        if (entry.variants.any { it.question == candidate.question }) continue
        val attemptId = attemptIds.allocate(entry.id, candidate.question)
        if (attemptId.isBlank()) continue
        return QueueVariant(attemptId, candidate.courseId, candidate.kpId, candidate.question,
            candidate.variantGroupId, candidate.availability, candidate.agentRunId)
    }
    return null
}
