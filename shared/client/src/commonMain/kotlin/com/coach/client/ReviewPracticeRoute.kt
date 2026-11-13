package com.coach.client

import com.coach.domain.*
import com.coach.domain.reviewqueue.QueueQuestion
import com.coach.domain.reviewqueue.QueueReadiness
import kotlinx.serialization.json.JsonObject

/** Exact practice-host input. A bare KP callback cannot represent this request. */
data class ReviewPracticeTarget(
    val courseId: String,
    val deviceId: String,
    val agentRunId: String?,
    val kpId: String,
    val entryId: String,
    val attemptId: String,
    val question: QueueQuestion,
)

/** Persist selection only. This does not claim the server has posed/reserved a question. */
fun prepareReviewPractice(sync: SyncEngine, courseId: String, run: String?, item: ReviewItem, day: Long): ReviewPracticeTarget? {
    val attempt = item.variantAttemptId?.takeIf { it.isNotBlank() } ?: return null
    val question = item.variantQuestion?.takeIf { it.id.isNotBlank() } ?: return null
    if (item.readiness == QueueReadiness.NEEDS_VARIANT || item.agentRunId != run) return null
    val target = ReviewPracticeTarget(courseId, sync.deviceId, run, item.kp.id, item.entryId, attempt, question)
    sync.saveMainlineState(courseId, json("courseId" to courseId, "deviceId" to sync.deviceId, "agentRunId" to run,
        "kpId" to target.kpId, "entryId" to target.entryId, "attemptId" to attempt,
        "question" to json("id" to question.id, "version" to question.version, "fingerprint" to question.fingerprint)), targetPage(run))
    saveReviewCursor(sync, courseId, item.entryId, day, attempt)
    return reviewPracticeTarget(sync, courseId, run)
}

/** Consume the actual cursor, not a newly selected candidate. Wrong scope/stale state is rejected. */
fun reviewPracticeTarget(sync: SyncEngine, courseId: String, run: String? = null): ReviewPracticeTarget? {
    val saved = sync.mainlineState(courseId, targetPage(run))
    val question = saved["question"] as? JsonObject ?: return null
    val target = ReviewPracticeTarget(saved.str("courseId"), saved.str("deviceId"), saved.str("agentRunId").ifBlank { null },
        saved.str("kpId"), saved.str("entryId"), saved.str("attemptId"),
        QueueQuestion(question.str("id"), question.str("version").ifBlank { null }, question.str("fingerprint").ifBlank { null }))
    return target.takeIf {
        it.courseId == courseId && it.deviceId == sync.deviceId && it.agentRunId == run &&
            it.kpId.isNotBlank() && it.entryId.isNotBlank() && it.attemptId.isNotBlank() && it.question.id.isNotBlank() &&
            it.entryId == reviewCursor(sync, courseId) && it.attemptId == reviewCursorAttempt(sync, courseId)
    }
}

private fun targetPage(run: String?) = "review-practice:${run?.let { "agent:${it.length}:$it" } ?: "learner"}"

enum class ReviewPracticeFailure { NO_TARGET, SNAPSHOT_REQUIRED, IDENTITY_MISMATCH, MISSING_ALLOCATION, UNVERIFIED, UNAVAILABLE, INCOMPLETE_CARD }

sealed interface ReviewPracticeSession {
    data class Ready(val target: ReviewPracticeTarget, val threadId: String, val group: QuizGroup) : ReviewPracticeSession
    data class Rejected(val reason: ReviewPracticeFailure) : ReviewPracticeSession
}

/**
 * Restore an already posed single-card session through the existing CourseView/QuizGroup parser.
 * /pool is metadata only. Until an exact allocation endpoint exists, a new target returns
 * MISSING_ALLOCATION; it must not call takePoolGroup/next or generate a synthetic posed fact.
 */
fun restoreReviewPracticeSession(sync: SyncEngine, courseId: String, kpId: String, run: String?, view: CourseView, nowMillis: Long): ReviewPracticeSession {
    fun reject(reason: ReviewPracticeFailure) = ReviewPracticeSession.Rejected(reason)
    val target = reviewPracticeTarget(sync, courseId, run) ?: return reject(ReviewPracticeFailure.NO_TARGET)
    if (target.question.version == null && target.question.fingerprint == null) return reject(ReviewPracticeFailure.IDENTITY_MISMATCH)
    if (target.kpId != kpId || view.events.any { it.courseId != courseId }) return reject(ReviewPracticeFailure.IDENTITY_MISMATCH)
    if (view.projection.requiresServerSnapshot) return reject(ReviewPracticeFailure.SNAPSHOT_REQUIRED)
    val poses = view.events.filter { it.type == "question_posed" && it.payload.str("attemptId") == target.attemptId }.distinct()
    if (poses.isEmpty()) return reject(ReviewPracticeFailure.MISSING_ALLOCATION)
    if (poses.size != 1) return reject(ReviewPracticeFailure.IDENTITY_MISMATCH)
    val pose = poses.single()
    val p = pose.payload
    val poseRun = agentRunId(pose.threadId) ?: p.str("agentRunId").ifBlank { null }
    if (poseRun != run || p.str("questionId") != target.question.id || p.strings("kpIds") != listOf(kpId) ||
        p.str("questionVersion").ifBlank { null } != target.question.version ||
        target.question.fingerprint?.let { it != p.str("fingerprint") } == true ||
        !p.flag("review") || p.str("format") != "SINGLE_CHOICE") return reject(ReviewPracticeFailure.IDENTITY_MISMATCH)
    val key = view.events.filter { it.type == "question_key_changed" && it.payload.str("questionId") == target.question.id }
        .maxByOrNull { it.seq }?.payload?.str("keyState") ?: p.str("keyState")
    if (p.str("keyState") != "VERIFIED" || key != "VERIFIED") return reject(ReviewPracticeFailure.UNVERIFIED)
    if (view.events.any { it.type == "attempt_voided" && it.payload.str("attemptId") == target.attemptId } ||
        p.str("availability") != "VALID" || p.num("expiresAt", Long.MAX_VALUE) <= nowMillis)
        return reject(ReviewPracticeFailure.UNAVAILABLE)
    val thread = pose.threadId?.takeIf { it.isNotBlank() } ?: return reject(ReviewPracticeFailure.INCOMPLETE_CARD)
    val group = view.quizGroups(thread).singleOrNull { g -> g.cards.any { it.attemptId == target.attemptId } }
        ?: return reject(ReviewPracticeFailure.INCOMPLETE_CARD)
    val card = group.cards.singleOrNull() ?: return reject(ReviewPracticeFailure.INCOMPLETE_CARD)
    if (group.groupId.isBlank() || card.stem.isBlank() || card.options.size < 2 || card.options.any { it.first.isBlank() || it.second.isBlank() })
        return reject(ReviewPracticeFailure.INCOMPLETE_CARD)
    return ReviewPracticeSession.Ready(target, thread, group)
}
