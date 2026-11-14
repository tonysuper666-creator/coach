package com.coach.domain.reviewqueue

import com.coach.domain.*
import kotlinx.serialization.json.JsonObject

/** Explicit correlation supplied by a future allocator/migration, never inferred from a shared KP. */
data class DueOriginalLink(val dueId: String, val question: QueueQuestion)

/** Original (pre-overlay) identity survives exam compression and repeated migration. */
fun legacyDueId(scope: QueueScope, kpId: String, schedule: Review): String = queueKey(
    scope.courseId, scope.agentRunId, kpId, (schedule.baseDueDay ?: schedule.dueDay).toString(),
    schedule.lastReviewDay?.toString(),
)

/**
 * Pure, learner-only snapshot adapter over main's actual Event/Projection/reviewsOn APIs.
 * It does not replace the formal scheduler. No QuestionLadder/PR150 type is assumed.
 * Main does not carry an original-question link on Review; absent an explicit link the
 * migrated due item remains unknown, even when there is a mistake on the same KP.
 */
fun ledgerQueueCandidate(
    courseId: String,
    input: List<Event>,
    day: Long,
    dueOrigins: List<DueOriginalLink> = emptyList(),
): ReviewQueueCandidate {
    require(input.all { it.courseId == courseId }) { "One course per candidate" }
    require(input.groupBy { it.id }.values.all { it.distinct().size == 1 }) { "Conflicting ledger event replay" }
    val ordered = input.distinctBy { it.id }.sortedWith(compareBy({ it.seq }, { it.id }))
    val events = learnerEvidence(ordered)
    val projection = project(events)
    require(!projection.requiresServerSnapshot) { "Unsupported ledger facts require an authoritative snapshot" }
    val scope = QueueScope(courseId)
    var queue = ReviewQueueCandidate(scope)
    val submitted = events.filter { it.type == "answer_submitted" }.groupBy { it.payload.str("attemptId") }
    val graded = events.filter { it.type in setOf("attempt_graded", "grade_reviewed") }.groupBy { it.payload.str("attemptId") }
    fun question(event: Event) = QueueQuestion(event.payload.str("questionId"),
        event.payload.str("questionVersion").ifBlank { null }, event.payload.str("fingerprint").ifBlank { null })
    fun attempt(event: Event) = QueueOriginalAttempt(event,
        submitted[event.payload.str("attemptId")]?.firstOrNull(), graded[event.payload.str("attemptId")].orEmpty())
    fun grade(attempt: QueueOriginalAttempt): JsonObject? = attempt.grades.fold(null as JsonObject?) { previous, fact ->
        if (fact.type == "attempt_graded") fact.payload
        else (fact.payload["revised"] as? JsonObject)?.let { JsonObject(previous.orEmpty() + it) } ?: previous
    }
    val voided = events.filter { it.type == "attempt_voided" }.map { it.payload.str("attemptId") }.toSet()
    val poses = events.filter { it.type == "question_posed" }
    val pending = poses.filter { it.payload.str("format") == "SINGLE_CHOICE" && it.payload.str("keyState") == "VERIFIED" }
        .map(::attempt).filter { it.submitted != null && it.id !in voided }
        .groupBy { question(it.posed) }.values.map { it.maxBy { a -> a.submitted!!.seq } }
        .filter { a -> grade(a)?.let { it.num("max") > 0 &&
            (it.num("score") < it.num("max") || a.submitted!!.payload.flag("guessed")) } == true }
    for (current in pending) {
        val posed = current.posed
        val original = question(posed)
        if (original.id.isBlank()) continue
        val allHistory = poses.filter { question(it) == original }.map(::attempt)
        val answer = submitted[posed.payload.str("attemptId")]?.firstOrNull() ?: continue
        for (kp in posed.payload.strings("kpIds")) {
            if (kp !in (projection.activeKpIds ?: projection.kpTitles.keys)) continue
            val history = allHistory.filter { kp in it.posed.payload.strings("kpIds") }
            val due = projection.review[kp]?.onDay(day, projection.examDay)?.dueDay ?: projection.revisit[kp]
                ?: (logicalDay(answer.occurredAt.coerceIn(
                    posed.payload.num("posedAt", posed.recordedAt).coerceAtMost(answer.recordedAt), answer.recordedAt),
                    projection.timeZone) + 1)
            for (old in history) {
                val oldAnswer = old.submitted ?: continue
                if (old.id in voided) continue
                val effectiveGrade = grade(old) ?: continue
                if (effectiveGrade.num("max") > 0 && effectiveGrade.num("score") < effectiveGrade.num("max")) {
                    queue = queue.observe(scope, kp, original, QueueSource(oldAnswer.id, QueueReason.WRONG,
                        old.id), due, history)
                }
                if (oldAnswer.payload.flag("guessed")) {
                    queue = queue.observe(scope, kp, original, QueueSource(oldAnswer.id, QueueReason.GUESSED,
                        old.id), due, history)
                }
            }
        }
    }
    for ((kp, effective) in projection.reviewsOn(day)) {
        val originalSchedule = projection.review[kp]?.onDay(day, null)
            ?: Review(projection.revisit.getValue(kp), lastResult = REVISIT)
        val dueId = legacyDueId(scope, kp, originalSchedule)
        val links = dueOrigins.filter { it.dueId == dueId }.map { it.question }.distinct()
        require(links.size <= 1) { "Ambiguous original for due record" }
        val original = links.singleOrNull()
        queue = queue.observe(scope, kp, original, QueueSource(dueId, QueueReason.DUE,
            legacySchedule = originalSchedule), effective.dueDay, unknownDueId = if (original == null) dueId else null)
    }
    return queue
}
