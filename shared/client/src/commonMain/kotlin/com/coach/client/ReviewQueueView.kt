package com.coach.client

import com.coach.domain.Event
import com.coach.domain.REVISIT
import com.coach.domain.json
import com.coach.domain.reviewqueue.QueueAvailability
import com.coach.domain.reviewqueue.QueueQuestion
import com.coach.domain.reviewqueue.QueueReadiness
import com.coach.domain.reviewqueue.QueueReason
import com.coach.domain.reviewqueue.VariantCandidatePort
import com.coach.domain.reviewqueue.allocateVariants
import com.coach.domain.reviewqueue.ledgerQueueCandidate
import com.coach.domain.reviewqueue.scheduleOrder
import com.coach.domain.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 复习入口的一行：原题身份、为什么排进来、现在能不能直接练。
 * [original] 为 null 表示账本里只有到期日程、没有可追的原题（[unknownDueId] 保留它的稳定身份），
 * 这时不假装它是某道错题，也不给它算分。
 */
data class ReviewItem(
    val entryId: String,
    val kp: KpRow,
    val reasons: List<QueueReason>,
    val original: QueueQuestion?,
    val unknownDueId: String?,
    val dueDay: Long,
    val overdueDays: Int,
    val revisit: Boolean,
    val readiness: QueueReadiness,
    val variantCount: Int,
    /** 已经选好的变式作答身份；null 表示这一行还没选到题，入口不能拿它当可练。 */
    val variantAttemptId: String?,
    val pendingSync: Int,
    /** 真实已知的先修知识点（大纲 prereqs），没有解析到名字时退回 id。 */
    val prereqTitles: List<String>,
    /** 真实已知的考纲权重（exam_weights_set），0 表示这门课没有给这个点定权重。 */
    val examWeight: Int,
    /** Identity of the allocated question, never inferred from its attempt ID. */
    val variantQuestion: QueueQuestion? = null,
    val agentRunId: String? = null,
) {
    val hasUnknownOriginal: Boolean get() = original == null

    /** 错题/猜对/到期的可读来源，固定顺序，不因账本事件的先后抖动。 */
    val reasonLabel: String
        get() = reasons.sortedBy { it.ordinal }.joinToString(" · ") {
            when (it) {
                QueueReason.WRONG -> "答错"
                QueueReason.GUESSED -> "猜对"
                QueueReason.DUE -> "到期"
            }
        }

    /**
     * 原题与变式身份。未知原题时不编一道题出来：说清楚它只有日程、练的是同知识点的变式。
     * 变式数量只数 pool 里仍然 VALID 的，撤回/换版的不算还能练。
     */
    val identityLabel: String
        get() = when {
            original == null && variantCount > 0 -> "原题未关联 · 已备 $variantCount 道变式"
            original == null -> "原题未关联 · 练同知识点变式"
            variantCount > 0 -> "原题 ${original.id.take(8)} · 已备 $variantCount 道变式"
            pendingSync > 0 || readiness == QueueReadiness.PENDING_SYNC -> "原题 ${original.id.take(8)} · 作答待同步"
            else -> "原题 ${original.id.take(8)} · 待生成变式"
        }
}

/**
 * 复习入口的唯一状态源：把账本里的答错、猜对和到期日程合成一队，按到期日、考纲权重排。
 * 未核答案（keyState 不是 VERIFIED）不进入队列、不计分，只报数量，避免把未核键当成掌握证据。
 */
data class ReviewBoard(
    val day: Long,
    val items: List<ReviewItem>,
    /** 已作答但答案键未核的题数：显示出来，但既不进队列也不计分。 */
    val unverifiedAnswered: Int,
    /** 队列空了以后最近的一条到期日；没有未完成项就是 null。 */
    val nextDueDay: Long?,
    /**
     * 账本里有这一版读不了的权威事实（[com.coach.domain.Projection.requiresServerSnapshot]）。
     * 这时队列、计数和下一次到期都不可判定：不能当成空队列或已完成，也不能拿旧投影硬猜，
     * 只能如实告诉用户要先取服务端快照。
     */
    val snapshotRequired: Boolean = false,
) {
    /** 全是间隔复习到期时沿用旧文案「N 节到期」；混入错题/猜对或「再看一次」时改说「N 项复习」。 */
    val dueOnly: Boolean get() = !snapshotRequired && items.all { it.reasons == listOf(QueueReason.DUE) && !it.revisit }
    val headline: String
        get() = when {
            snapshotRequired || items.isEmpty() -> ""
            dueOnly -> "${items.size} 节到期"
            else -> "${items.size} 项复习"
        }

}

/**
 * 本机还没有「题库 → 复习队列」的题级通道时的默认候选源：如实报没有候选。
 * 队列因此保持缺题（「待生成变式」），不拿账本里已经出过的题冒充新变式。
 */
val NoVariantCandidates: VariantCandidatePort = VariantCandidatePort { _, _, _ -> emptyList() }

/**
 * 纯函数：用真实账本（[CourseView.events]）构造复习入口要显示的队列。
 * 队列身份由已验的 [ledgerQueueCandidate] 决定；这里只做展示层的取舍，不改合同、不写账本。
 *
 * 缺口（[QueueReadiness.NEEDS_VARIANT]）先交给 [variants] 这条可注入的题库端口挑选：
 * 只有同课程、同考点、有效版本、已核键、不是原题的一道题才会被选上，且同一 scope 内一道题只花一次；
 * 端口没有候选（默认 [NoVariantCandidates]）或候选不合格时，这一行原样保持缺题，
 * 入口显示「待生成变式」，不会把没有的题说成可练。
 *
 * 到期日程本身不携带原题身份（主干的 `Review` 没有题号）；只有分配器/迁移显式给出的
 * `DueOriginalLink` 才能把它关联到某道原题。同知识点、同一天、恰好一道错题都不构成证据：
 * 那种情况下到期行保持「原题未关联」，宁可多一行，也不把别人的题认成原题。
 *
 * 账本要求服务端权威快照时不做任何猜测：返回 [ReviewBoard.snapshotRequired]，
 * 由入口显示「待同步/无法确认」，而不是抛异常或报「今天没有复习」。
 */
fun reviewBoard(
    courseId: String,
    view: CourseView,
    day: Long,
    variants: VariantCandidatePort = NoVariantCandidates,
): ReviewBoard {
    // view.projection 就是这块屏幕正在渲染的同一次回放；它读不懂的账本事实，队列也读不懂。
    if (view.projection.requiresServerSnapshot) {
        return ReviewBoard(day, emptyList(), 0, null, snapshotRequired = true)
    }
    val candidate = ledgerQueueCandidate(courseId, view.events, day)
    // 先补缺口，再排程：排的是真的能练的队列，不是「本来能练但没接上」的列表。
    val allocated = candidate.allocateVariants(variants).queue
    val weights = examWeights(view.events)
    // 权重与考前窗口都来自账本/课程配置：排序是真实排程，不是只把权重写在行里。
    val due = scheduleOrder(allocated.dueEntries(day), day, weights, view.projection.examDay,
        reviewStars = view.projection.reviewStars)
    val items = due.map { entry ->
        val kp = view.kp(entry.kpId) ?: KpRow(
            entry.kpId, view.projection.kpTitles[entry.kpId].orEmpty(), KpStatus.NEW, false, listOf(entry.kpId))
        ReviewItem(
            entryId = entry.id,
            kp = kp,
            reasons = entry.sources.map { it.reason }.distinct(),
            original = entry.originalQuestion,
            unknownDueId = entry.unknownDueId,
            dueDay = entry.dueDay,
            overdueDays = (day - entry.dueDay).toInt(),
            revisit = entry.sources.any { it.reason == QueueReason.DUE && it.legacySchedule?.lastResult == REVISIT },
            readiness = entry.readiness,
            variantCount = entry.variants.count { it.availability == QueueAvailability.VALID },
            variantAttemptId = entry.currentAttemptId?.takeIf { entry.readiness != QueueReadiness.NEEDS_VARIANT },
            pendingSync = entry.pendingSyncAttemptIds.size,
            prereqTitles = kp.prereqs.map { id -> view.kp(id)?.title?.takeIf { it.isNotBlank() } ?: id },
            examWeight = weights[entry.kpId] ?: 0,
            variantQuestion = entry.variants.singleOrNull { it.attemptId == entry.currentAttemptId && it.availability == QueueAvailability.VALID }?.question,
            agentRunId = allocated.scope.agentRunId,
        )
    }
    return ReviewBoard(day, items, unverifiedAnswered(view), nextDueDay(view, day))
}

/** 下一次到期（间隔复习或重练），含还没到期的；没有就是 null。 */
private fun nextDueDay(view: CourseView, day: Long): Long? {
    val active = view.projection.activeKpIds ?: view.projection.kpTitles.keys
    return (view.projection.review.filterKeys { it in active }.values.map { it.dueDay } +
        view.projection.revisit.filterKeys { it in active }.values).filter { it > day }.minOrNull()
}

/** 上次从这个入口点开的条目；退出重进后仍然指得回来。 */
fun reviewCursor(sync: SyncEngine, courseId: String, agentRunId: String? = null): String? =
    (sync.mainlineState(courseId, reviewPage(agentRunId))["entryId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * 上一次点开的那一项落到的具体作答身份：已经选好变式时是那道变式的 attemptId，
 * 还没有候选时是 null。
 *
 * 类型化复习组件通过 reviewPracticeTarget 消费此光标，重试与恢复沿用同一题。
 * 共享/Windows 默认宿主的挂接仍受单独文件租约约束。
 */
fun reviewCursorAttempt(sync: SyncEngine, courseId: String, agentRunId: String? = null): String? =
    (sync.mainlineState(courseId, reviewPage(agentRunId))["attemptId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

fun saveReviewCursor(
    sync: SyncEngine,
    courseId: String,
    entryId: String,
    day: Long,
    variantAttemptId: String? = null,
    agentRunId: String? = null,
) {
    sync.saveMainlineState(
        courseId,
        json("entryId" to entryId, "day" to day, "attemptId" to variantAttemptId),
        reviewPage(agentRunId),
    )
}

/** Preserve the legacy learner key; agent runs each own an independent cursor. */
private fun reviewPage(run: String?) = run?.let { "review:agent:${it.length}:$it" } ?: "review"

/** 最近一次 exam_weights_set 的每点权重；没有任何权重事件就是空。 */
private fun examWeights(events: List<Event>): Map<String, Int> =
    events.filter { it.type == "exam_weights_set" }.maxByOrNull { it.seq }
        ?.let { (it.payload["weights"] as? JsonObject) }
        ?.mapNotNull { (kp, value) -> (value as? JsonPrimitive)?.intOrNull?.let { kp to it } }
        ?.toMap().orEmpty()

/** 已作答但答案键未核的题（按题目去重）：报给入口看，永远不进队列、不计分。 */
private fun unverifiedAnswered(view: CourseView): Int {
    val answered = view.events.filter { it.type == "answer_submitted" }
        .map { it.payload.str("attemptId") }.toSet()
    return view.events.filter {
        it.type == "question_posed" && it.payload.str("attemptId") in answered &&
            it.payload.str("keyState", "UNREVIEWED") != "VERIFIED"
    }.map { it.payload.str("questionId").ifBlank { it.payload.str("attemptId") } }.distinct().size
}
