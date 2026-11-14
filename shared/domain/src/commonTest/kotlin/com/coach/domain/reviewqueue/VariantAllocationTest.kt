package com.coach.domain.reviewqueue

import com.coach.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic candidates only: no pool read, no ledger write, no formal content. */
class VariantAllocationTest {
    private val scope = QueueScope("synthetic-course")
    private val day = 20_000L

    private fun queue(vararg kps: String): ReviewQueueCandidate = kps.fold(ReviewQueueCandidate(scope)) { q, kp ->
        q.observe(scope, kp, QueueQuestion("q-$kp", "orig-v1", "fp-$kp"),
            QueueSource("wrong-$kp", QueueReason.WRONG), day)
    }

    private fun candidate(
        kp: String = "kp", id: String = "q-variant", course: String = "synthetic-course",
        availability: QueueAvailability = QueueAvailability.VALID, verified: Boolean = true,
        version: String? = "v2", run: String? = null,
    ) = VariantCandidate(course, kp, QueueQuestion(id, version, "fp-$id"), availability, verified, "group-$id", run)

    private fun port(vararg candidates: VariantCandidate) =
        VariantCandidatePort { _, _, kp -> candidates.filter { it.kpId == kp } }

    @Test fun selectionOnlyTakesALiveVerifiedQuestionOnTheSameKpThatIsNotTheOriginal() {
        val before = queue("kp")
        val allocated = before.allocateVariants(port(
            candidate(kp = "kp2"),
            candidate(course = "other-course"),
            candidate(id = "q-retired", availability = QueueAvailability.RETIRED),
            candidate(id = "q-replaced", availability = QueueAvailability.REPLACED),
            candidate(id = "q-unverified", verified = false),
            candidate(id = "q-kp"),
            candidate(id = "q-variant"),
        ))

        val entry = allocated.queue.entries.single()
        assertEquals("q-variant", entry.variants.single().question.id)
        assertEquals(QueueReadiness.READY, entry.readiness)
        assertEquals(entry.variants.single().attemptId, entry.currentAttemptId)
        assertTrue(entry.variants.single().attemptId.isNotBlank())
        // Original identity and history are untouched by the allocation.
        assertEquals(before.entries.single().originalQuestion, entry.originalQuestion)
        assertEquals(before.entries.single().originalAttempts, entry.originalAttempts)
        assertEquals(before.entries.single().sources, entry.sources)
        assertEquals("q-variant", allocated.selections.single().variant?.question?.id)
    }

    @Test fun withoutAUsableCandidateTheEntryStaysAMissingQuestion() {
        val before = queue("kp")
        for (candidates in listOf(
            emptyList(),
            listOf(candidate(id = "q-kp")),
            listOf(candidate(id = "q-retired", availability = QueueAvailability.RETIRED)),
            listOf(candidate(id = "q-unverified", verified = false)),
            listOf(candidate(kp = "kp2"), candidate(course = "other-course")),
        )) {
            val allocated = before.allocateVariants(port(*candidates.toTypedArray()))
            val entry = allocated.queue.entries.single()
            assertEquals(before.entries.single(), entry, "A gap must stay a gap: $candidates")
            assertEquals(QueueReadiness.NEEDS_VARIANT, entry.readiness)
            assertTrue(entry.variants.isEmpty())
            assertNull(allocated.selections.single().variant)
        }
    }

    @Test fun candidatesCannotCrossTheRunScope() {
        val learner = queue("kp")
        assertTrue(learner.allocateVariants(port(candidate(run = "run-b"))).queue.entries.single().variants.isEmpty())

        val agentScope = QueueScope("synthetic-course", "run-a")
        val agentQueue = ReviewQueueCandidate(agentScope).observe(agentScope, "kp",
            QueueQuestion("q-kp", "orig-v1", "fp-kp"), QueueSource("wrong-kp", QueueReason.WRONG), day)
        assertTrue(agentQueue.allocateVariants(port(candidate(run = "run-b"))).queue.entries.single().variants.isEmpty())
        val own = agentQueue.allocateVariants(port(candidate(run = "run-a"))).queue.entries.single()
        assertEquals(QueueReadiness.READY, own.readiness)
        assertEquals("run-a", own.variants.single().agentRunId)
    }

    @Test fun allocationIsIdempotentRestorableAndNeverTouchesAPendingCommand() {
        val once = queue("kp").allocateVariants(port(candidate(), candidate(id = "q-other"))).queue
        val twice = once.allocateVariants(port(candidate(), candidate(id = "q-other"))).queue
        assertEquals(once.bytes(), twice.bytes())
        assertEquals(1, once.entries.single().variants.size)
        assertEquals(once, LedgerJson.decodeFromString(ReviewQueueCandidate.serializer(), once.bytes()))

        val pending = once.pendingSync(once.entries.single().id, once.entries.single().currentAttemptId!!)
        val after = pending.allocateVariants(port(candidate(id = "q-late"))).queue
        assertEquals(pending.bytes(), after.bytes())
        assertEquals(QueueReadiness.PENDING_SYNC, after.entries.single().readiness)
    }

    /**
     * QA's first counterexample: one live variant, two NEEDS_VARIANT entries on the same KP. Both used to go
     * READY on the same question, producing two attempt ids for one question. A question is spent once.
     */
    @Test fun oneCandidateIsNeverSpentOnTwoEntriesOfWorkInTheSameScope() {
        val before = ReviewQueueCandidate(scope)
            .observe(scope, "kp", QueueQuestion("q-kp", "orig-v1", "fp-kp"),
                QueueSource("wrong-kp", QueueReason.WRONG), day)
            .observe(scope, "kp", question = null, source = QueueSource("due-kp", QueueReason.DUE),
                dueDay = day, unknownDueId = "legacy-due-kp")
        assertEquals(2, before.entries.size)

        val allocated = before.allocateVariants(port(candidate()))
        val ready = allocated.queue.entries.filter { it.readiness == QueueReadiness.READY }
        val missing = allocated.queue.entries.filter { it.readiness == QueueReadiness.NEEDS_VARIANT }
        assertEquals(1, ready.size)
        assertEquals("q-variant", ready.single().variants.single().question.id)
        assertEquals(1, missing.size, "the entry that could not get a question stays a gap")
        assertTrue(missing.single().variants.isEmpty())
        assertTrue(missing.single().currentAttemptId == null)
        assertEquals(1, allocated.selections.count { it.variant != null })
        assertEquals(1, allocated.selections.count { it.variant == null })
        val questions = allocated.queue.entries.flatMap { entry -> entry.variants.map { it.question.id } }
        assertEquals(questions.distinct(), questions, "one question cannot own two variant attempts")
    }

    @Test fun aQuestionAlreadyAllocatedInTheQueueIsNotSpentAgain() {
        // The first pass spends the only candidate on the mistake row.
        val firstPass = queue("kp").allocateVariants(port(candidate())).queue
        // A later due record for the same KP arrives; the pool still only holds the spent question.
        val secondPass = firstPass
            .observe(scope, "kp", question = null, source = QueueSource("due-kp", QueueReason.DUE),
                dueDay = day, unknownDueId = "legacy-due-kp")
            .allocateVariants(port(candidate()))
        assertEquals(1, secondPass.queue.entries.count { it.readiness == QueueReadiness.READY })
        val dueRow = secondPass.queue.entries.single { it.originalQuestion == null }
        assertTrue(dueRow.variants.isEmpty())
        assertEquals(QueueReadiness.NEEDS_VARIANT, dueRow.readiness)
    }

    /**
     * A sibling due row has no original link, so nothing but the spend-set stops it from taking the very
     * question the learner already answered on the same KP. That would be a replay, not a variant.
     */
    @Test fun anAnsweredOriginalIsNeverHandedToASiblingRowAsItsVariant() {
        val before = ReviewQueueCandidate(scope)
            .observe(scope, "kp", QueueQuestion("q-kp", "orig-v1", "fp-kp"),
                QueueSource("wrong-kp", QueueReason.WRONG), day)
            .observe(scope, "kp", question = null, source = QueueSource("due-kp", QueueReason.DUE),
                dueDay = day, unknownDueId = "legacy-due-kp")
        // The pool still offers that same question (even in another version) and nothing else.
        val allocated = before.allocateVariants(port(candidate(id = "q-kp")))
        assertTrue(allocated.queue.entries.all { it.variants.isEmpty() })
        assertTrue(allocated.selections.all { it.variant == null })
        assertTrue(allocated.queue.entries.all { it.readiness == QueueReadiness.NEEDS_VARIANT })
    }

    @Test fun aRealLedgerEntryKeepsItsOriginalIdentityWhenAVariantIsAllocated() {
        val events = mutableListOf<Event>()
        val now = 1_793_188_800_000L
        fun add(type: String, payload: JsonObject) {
            val seq = events.size + 1L
            events += Event(seq, "event-$seq", scope.courseId, type, recordedAt = now, occurredAt = now,
                deviceId = "synthetic-device", actor = "LEARNER", commandId = "command-$seq",
                payload = JsonObject(payload + agentRunMetadata(null)))
        }
        add("outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "kp", "title" to "【W1】 Synthetic point")))))
        add("legacy_passed", json("kpId" to "kp"))
        add("legacy_schedule", json("kpId" to "kp", "dueDay" to day - 2, "interval" to 5))
        add("question_posed", json("attemptId" to "original", "questionId" to "q-kp", "kpIds" to listOf("kp"),
            "keyState" to "VERIFIED", "format" to "SINGLE_CHOICE", "fingerprint" to "fp-kp"))
        add("answer_submitted", json("attemptId" to "original", "answer" to "synthetic-B"))
        add("attempt_graded", json("attemptId" to "original", "score" to 0, "max" to 10))

        val fromLedger = ledgerQueueCandidate(scope.courseId, events, day)
        val known = fromLedger.entries.single { it.originalQuestion != null }
        val unknownDue = fromLedger.entries.single { it.originalQuestion == null }
        assertEquals("q-kp", known.originalQuestion?.id)
        // Two real candidates so both rows can be served; each question is still spent only once.
        val allocated = fromLedger.allocateVariants(port(candidate(), candidate(id = "q-variant-2"))).queue
        val after = allocated.entries.single { it.id == known.id }
        assertEquals(known.id, after.id)
        assertEquals(known.originalQuestion, after.originalQuestion)
        assertEquals(known.originalAttempts, after.originalAttempts)
        assertEquals(QueueReadiness.READY, after.readiness)
        assertEquals(2, allocated.entries.flatMap { it.variants }.map { it.question.id }.distinct().size)
        // The unlinked due row keeps its own identity; the variant never rewrites it into the mistake.
        val stillUnknown = allocated.entries.single { it.id == unknownDue.id }
        assertNull(stillUnknown.originalQuestion)
        assertEquals(unknownDue.unknownDueId, stillUnknown.unknownDueId)
    }
}
