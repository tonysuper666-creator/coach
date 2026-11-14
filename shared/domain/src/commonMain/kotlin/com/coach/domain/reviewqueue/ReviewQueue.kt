package com.coach.domain.reviewqueue

import com.coach.domain.Event
import com.coach.domain.LedgerJson
import com.coach.domain.Review
import com.coach.domain.canonicalJson
import com.coach.domain.agentRunId
import com.coach.domain.learnerEvidence
import com.coach.domain.str
import com.coach.domain.strings
import kotlinx.serialization.Serializable

/** Isolated contract candidate. No scheduler, storage, transport or UI consumes this yet. */
@Serializable
data class QueueScope(val courseId: String, val agentRunId: String? = null)

/** Null version means unknown, including main's fingerprint-only posed facts. */
@Serializable
data class QueueQuestion(val id: String, val version: String? = null, val fingerprint: String? = null)

@Serializable enum class QueueReason { WRONG, GUESSED, DUE }
@Serializable enum class QueueReadiness { NEEDS_VARIANT, READY, PENDING_SYNC }
@Serializable enum class QueueAvailability { VALID, RETIRED, REPLACED, UNVERIFIED }

@Serializable
data class QueueSource(
    val id: String,
    val reason: QueueReason,
    val attemptId: String? = null,
    /** The pre-migration schedule is retained, not rewritten by an exam overlay. */
    val legacySchedule: Review? = null,
)

/** Exact original facts remain immutable even after a new variant is answered. */
@Serializable
data class QueueOriginalAttempt(val posed: Event, val submitted: Event?, val grades: List<Event> = emptyList()) {
    val id: String get() = posed.payload.str("attemptId")
}

@Serializable
data class QueueVariant(
    val attemptId: String,
    val courseId: String,
    val kpId: String,
    val question: QueueQuestion,
    val variantGroupId: String?,
    val availability: QueueAvailability,
    val agentRunId: String? = null,
)

@Serializable
data class QueueEntry(
    val id: String,
    val kpId: String,
    val originalQuestion: QueueQuestion?,
    /** Required when the original question is unknown; never replaced with an invented question. */
    val unknownDueId: String? = null,
    val sources: List<QueueSource>,
    val originalAttempts: List<QueueOriginalAttempt> = emptyList(),
    val dueDay: Long,
    val variants: List<QueueVariant> = emptyList(),
    val currentAttemptId: String? = null,
    val readiness: QueueReadiness = QueueReadiness.NEEDS_VARIANT,
    /** A withdrawal must not erase an outstanding offline command's attempt identity. */
    val pendingSyncAttemptIds: List<String> = emptyList(),
    val completed: Boolean = false,
)

/** Length-prefixed parts avoid delimiter collisions without platform-specific hashing. */
internal fun queueKey(vararg parts: String?): String = parts.joinToString("") {
    if (it == null) "-:" else "${it.length}:$it"
}

internal fun QueueQuestion.identity(): String = queueKey(id, version, fingerprint)

@Serializable
data class ReviewQueueCandidate(
    val scope: QueueScope,
    val entries: List<QueueEntry> = emptyList(),
    val cursorEntryId: String? = null,
) {
    fun bytes(): String = canonicalJson(LedgerJson.encodeToJsonElement(serializer(), this))

    /** A future Today count must use this same list, including visible not-ready gaps. */
    fun dueEntries(day: Long): List<QueueEntry> = entries.filter { !it.completed && it.dueDay <= day }

    fun focus(entryId: String): ReviewQueueCandidate {
        require(entries.any { it.id == entryId }) { "Unknown queue entry" }
        return copy(cursorEntryId = entryId)
    }

    /** Idempotent union. A conflicting replay is rejected; it cannot overwrite an original answer. */
    fun observe(
        provenance: QueueScope,
        kpId: String,
        question: QueueQuestion?,
        source: QueueSource,
        dueDay: Long,
        attempts: List<QueueOriginalAttempt> = emptyList(),
        unknownDueId: String? = null,
    ): ReviewQueueCandidate {
        require(provenance == scope) { "Cannot merge learner and agent/course evidence" }
        require(kpId.isNotBlank() && source.id.isNotBlank())
        require(question?.id?.isNotBlank() ?: !unknownDueId.isNullOrBlank())
        require(question != null || attempts.isEmpty()) { "Unknown original cannot own invented attempts" }
        for (attempt in attempts) {
            val facts = listOfNotNull(attempt.posed, attempt.submitted) + attempt.grades
            require(attempt.id.isNotBlank() && facts.all {
                it.courseId == scope.courseId && it.payload.str("attemptId") == attempt.id
            })
            require(attempt.posed.payload.str("questionId") == question?.id && kpId in attempt.posed.payload.strings("kpIds"))
            require(attempt.posed.payload.str("questionVersion").ifBlank { null } == question?.version)
            require(attempt.posed.payload.str("fingerprint").ifBlank { null } == question.fingerprint)
            if (scope.agentRunId == null) require(learnerEvidence(facts).size == facts.size) {
                "Cannot import agent answers into a learner queue"
            } else {
                require((agentRunId(attempt.posed.threadId) ?: attempt.posed.payload.str("agentRunId")) == scope.agentRunId)
                require(learnerEvidence(facts, scope.agentRunId).size == facts.size)
            }
        }
        val id = queueKey(scope.courseId, scope.agentRunId, kpId,
            if (question == null) "legacy" else "question", question?.identity() ?: unknownDueId)
        val previous = entries.find { it.id == id }
        val mergedSources = ((previous?.sources ?: emptyList()) + source).groupBy { it.id to it.reason }.values.map {
            require(it.distinct().size == 1) { "Conflicting queue source replay" }
            it.first()
        }.sortedWith(compareBy({ it.id }, { it.reason.name }))
        val mergedAttempts = ((previous?.originalAttempts ?: emptyList()) + attempts).groupBy { it.id }.values.map {
            require(it.distinct().size == 1) { "Conflicting original attempt replay" }
            it.first()
        }.sortedWith(compareBy({ it.submitted?.seq ?: it.posed.seq }, { it.id }))
        val entry = previous?.copy(sources = mergedSources, originalAttempts = mergedAttempts,
            dueDay = minOf(previous.dueDay, dueDay))
            ?: QueueEntry(id, kpId, question, unknownDueId, mergedSources, mergedAttempts, dueDay)
        return copy(entries = (entries.filterNot { it.id == id } + entry)
            .sortedWith(compareBy({ it.dueDay }, { it.id })))
    }

    /** Called only after a selector actually allocated a valid same-KP variant. */
    fun assignVariant(entryId: String, variant: QueueVariant): ReviewQueueCandidate = change(entryId) { entry ->
        require(variant.courseId == scope.courseId && variant.kpId == entry.kpId && variant.agentRunId == scope.agentRunId)
        require(variant.attemptId.isNotBlank() && variant.question.id.isNotBlank())
        require(variant.availability == QueueAvailability.VALID) { "Variant is not ready" }
        require(variant.question.id != entry.originalQuestion?.id) { "Must allocate a different question" }
        require(entry.originalAttempts.none { it.id == variant.attemptId })
        val previous = entry.variants.find { it.attemptId == variant.attemptId }
        require(previous == null || previous == variant) { "Conflicting variant allocation replay" }
        if (previous != null) entry else entry.copy(variants = entry.variants + variant,
            currentAttemptId = variant.attemptId, readiness = QueueReadiness.READY)
    }

    /** Pending command retries keep the allocated attempt and original answers. No new item is created. */
    fun pendingSync(entryId: String, attemptId: String): ReviewQueueCandidate = change(entryId) { entry ->
        if (attemptId in entry.pendingSyncAttemptIds) entry else {
            require(entry.currentAttemptId == attemptId && entry.readiness != QueueReadiness.NEEDS_VARIANT)
            entry.copy(readiness = QueueReadiness.PENDING_SYNC,
                pendingSyncAttemptIds = (entry.pendingSyncAttemptIds + attemptId).sorted())
        }
    }

    /** Invalidate only the affected revision. History and cursor survive pool withdrawal/version changes. */
    fun invalidateQuestion(question: QueueQuestion, availability: QueueAvailability): ReviewQueueCandidate {
        require(availability != QueueAvailability.VALID)
        return copy(entries = entries.map { entry ->
            val variants = entry.variants.map { if (it.question == question) it.copy(availability = availability) else it }
            val currentInvalid = variants.any { it.attemptId == entry.currentAttemptId && it.availability != QueueAvailability.VALID }
            entry.copy(variants = variants, readiness = if (currentInvalid) QueueReadiness.NEEDS_VARIANT else entry.readiness)
        })
    }

    fun complete(entryId: String): ReviewQueueCandidate = change(entryId) { it.copy(completed = true) }

    private fun change(entryId: String, transform: (QueueEntry) -> QueueEntry): ReviewQueueCandidate {
        require(entries.any { it.id == entryId }) { "Unknown queue entry" }
        return copy(entries = entries.map { if (it.id == entryId) transform(it) else it })
    }
}
