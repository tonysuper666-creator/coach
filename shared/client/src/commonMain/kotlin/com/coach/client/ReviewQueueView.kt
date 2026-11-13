package com.coach.client

import com.coach.domain.Event
import com.coach.domain.REVISIT
import com.coach.domain.Review
import com.coach.domain.json
import com.coach.domain.reviewqueue.DueOriginalLink
import com.coach.domain.reviewqueue.QueueAvailability
import com.coach.domain.reviewqueue.QueueQuestion
import com.coach.domain.reviewqueue.QueueReadiness
import com.coach.domain.reviewqueue.QueueReason
import com.coach.domain.reviewqueue.QueueScope
import com.coach.domain.reviewqueue.ReviewQueueCandidate
import com.coach.domain.reviewqueue.ledgerQueueCandidate
import com.coach.domain.reviewqueue.legacyDueId
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
    val pendingSync: Int,
    /** 真实已知的先修知识点（大纲 prereqs），没有解析到名字时退回 id。 */
    val prereqTitles: List<String>,
    /** 真实已知的考纲权重（exam_weights_set），0 表示这门课没有给这个点定权重。 */
    val examWeight: Int,
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
) {
    /** 全是间隔复习到期时沿用旧文案「N 节到期」；混入错题/猜对或「再看一次」时改说「N 项复习」。 */
    val dueOnly: Boolean get() = items.all { it.reasons == listOf(QueueReason.DUE) && !it.revisit }
    val headline: String
        get() = when {
            items.isEmpty() -> ""
            dueOnly -> "${items.size} 节到期"
            else -> "${items.size} 项复习"
        }

}

/**
 * 纯函数：用真实账本（[CourseView.events]）构造复习入口要显示的队列。
 * 队列身份由已验的 [ledgerQueueCandidate] 决定；这里只做展示层的取舍，不改合同、不写账本。
 *
 * 到期日程本身不带原题链接。唯一的例外是「再看一次」：`Projection.revisit` 是某道答错/猜对题
 * 排出来的重练日，同一天同一知识点只有一道这样的原题时，把这道题的真实身份交给队列去重，
 * 两行并成一行；有多道时无法确认是哪一道，就不合并、不猜。
 */
fun reviewBoard(courseId: String, view: CourseView, day: Long): ReviewBoard {
    val base = ledgerQueueCandidate(courseId, view.events, day)
    val links = revisitLinks(courseId, view, day, base)
    val candidate = if (links.isEmpty()) base else ledgerQueueCandidate(courseId, view.events, day, links)
    val weights = examWeights(view.events)
    val items = candidate.dueEntries(day).map { entry ->
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
            pendingSync = entry.pendingSyncAttemptIds.size,
            prereqTitles = kp.prereqs.map { id -> view.kp(id)?.title?.takeIf { it.isNotBlank() } ?: id },
            examWeight = weights[entry.kpId] ?: 0,
        )
    }.sortedWith(compareBy({ it.dueDay }, { -it.examWeight }, { it.kp.id }, { it.entryId }))
    return ReviewBoard(day, items, unverifiedAnswered(view), nextDueDay(view, day))
}

/** 下一次到期（间隔复习或重练），含还没到期的；没有就是 null。 */
private fun nextDueDay(view: CourseView, day: Long): Long? {
    val active = view.projection.activeKpIds ?: view.projection.kpTitles.keys
    return (view.projection.review.filterKeys { it in active }.values.map { it.dueDay } +
        view.projection.revisit.filterKeys { it in active }.values).filter { it > day }.minOrNull()
}

/** 「再看一次」的原题关联：只有当天同知识点恰好一道错题/猜对题时才给出，避免把共享知识点当成同一道题。 */
private fun revisitLinks(
    courseId: String, view: CourseView, day: Long, queue: ReviewQueueCandidate,
): List<DueOriginalLink> {
    val scope = QueueScope(courseId)
    val mistakes = queue.entries.filter { it.originalQuestion != null }.groupBy { it.kpId }
    return view.projection.revisit.flatMap { (kpId, revisitDay) ->
        // 有正式复习日程时适配器按日程取身份，这里不抢它的来源。
        if (revisitDay > day || view.projection.review[kpId] != null) return@flatMap emptyList()
        val questions = mistakes[kpId].orEmpty().filter { it.dueDay == revisitDay }
            .mapNotNull { it.originalQuestion }.distinct()
        if (questions.size != 1) return@flatMap emptyList()
        listOf(DueOriginalLink(legacyDueId(scope, kpId, Review(revisitDay, lastResult = REVISIT)), questions.single()))
    }
}

/** 上次从这个入口点开的条目；退出重进后仍然指得回来。 */
fun reviewCursor(sync: SyncEngine, courseId: String): String? =
    (sync.mainlineState(courseId, REVIEW_PAGE)["entryId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

fun saveReviewCursor(sync: SyncEngine, courseId: String, entryId: String, day: Long) {
    sync.saveMainlineState(courseId, json("entryId" to entryId, "day" to day), REVIEW_PAGE)
}

private const val REVIEW_PAGE = "review"

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
