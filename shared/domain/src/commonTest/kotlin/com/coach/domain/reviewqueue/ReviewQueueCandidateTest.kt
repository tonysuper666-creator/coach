package com.coach.domain.reviewqueue

import com.coach.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic ledger facts only; no corpus, formal pool, client, server or production writes. */
class ReviewQueueCandidateTest {
    private class Ledger {
        val events = mutableListOf<Event>()
        val now = 1_793_188_800_000L
        val day = logicalDay(now, "Europe/London")
        val scope = QueueScope("synthetic-course")

        init {
            add("outline_revised", json("ops" to JsonArray(listOf(
                json("kpId" to "kp", "title" to "【W1】 Synthetic point"),
                json("kpId" to "kp2", "title" to "【W2】 Other point")))))
            add("legacy_passed", json("kpId" to "kp"))
            add("legacy_schedule", json("kpId" to "kp", "dueDay" to day - 2, "interval" to 5, "ease" to 2.1))
        }

        fun add(type: String, payload: JsonObject, run: String? = null): Event {
            val seq = events.size + 1L
            return Event(seq, "event-$seq", scope.courseId, type, recordedAt = now, occurredAt = now,
                deviceId = "synthetic-device", actor = "LEARNER", commandId = "command-$seq",
                threadId = run?.let { agentThreadId(it, "synthetic-thread") },
                payload = JsonObject(payload + agentRunMetadata(run))).also(events::add)
        }

        fun answer(id: String, score: Int, guessed: Boolean = false, q: String = "q-original",
                   version: String? = null, run: String? = null): QueueOriginalAttempt {
            val posed = add("question_posed", json("attemptId" to id, "questionId" to q,
                "questionVersion" to version, "kpIds" to listOf("kp"), "fingerprint" to "fingerprint-$q-${version ?: "main"}",
                "keyState" to "VERIFIED", "format" to "SINGLE_CHOICE", "mainline" to true,
                "purpose" to "PRACTICE", "origin" to "COACH", "policyVersion" to 1, "review" to false), run)
            val submitted = add("answer_submitted", json("attemptId" to id, "answer" to "synthetic-B",
                "guessed" to guessed), run)
            // Late grading can omit provenance. The real public learnerEvidence closes the attempt.
            val graded = add("attempt_graded", json("attemptId" to id, "score" to score, "max" to 10, "status" to "EXACT"))
            return QueueOriginalAttempt(posed, submitted, listOf(graded))
        }

        fun question(attempt: QueueOriginalAttempt) = QueueQuestion(attempt.posed.payload.str("questionId"),
            attempt.posed.payload.str("questionVersion").ifBlank { null }, attempt.posed.payload.str("fingerprint"))

        fun dueLink(attempt: QueueOriginalAttempt): DueOriginalLink {
            val real = project(events)
            return DueOriginalLink(legacyDueId(scope, "kp", real.review.getValue("kp")), question(attempt))
        }

        fun queue(links: List<DueOriginalLink> = emptyList(), input: List<Event> = events) =
            ledgerQueueCandidate(scope.courseId, input, day, links)
    }

    private fun variant(entry: QueueEntry, id: String = "variant-attempt", version: String = "v1",
                        q: String = "q-variant") = QueueVariant(id, "synthetic-course", entry.kpId,
        QueueQuestion(q, version), "synthetic-variant-group", QueueAvailability.VALID)

    @Test fun wrongGuessAndExplicitlyLinkedDueAreOneEntryWithBothOriginalAnswers() {
        val l = Ledger()
        val wrong = l.answer("original-wrong", 0)
        val guessed = l.answer("original-guessed", 10, guessed = true)
        val queue = l.queue(listOf(l.dueLink(guessed)))
        val entry = queue.entries.single()
        assertEquals(setOf(QueueReason.WRONG, QueueReason.GUESSED, QueueReason.DUE), entry.sources.map { it.reason }.toSet())
        assertEquals(listOf(wrong, guessed), entry.originalAttempts)
        assertEquals("synthetic-B", entry.originalAttempts.first().submitted!!.payload.str("answer"))
        assertEquals(QueueReadiness.NEEDS_VARIANT, entry.readiness)
        assertEquals(queue.entries, queue.dueEntries(l.day))
        assertEquals(1, queue.dueEntries(l.day).size)
        assertEquals(l.question(wrong), entry.originalQuestion)
        assertNull(entry.originalQuestion!!.version, "main's missing version remains unknown")
    }

    @Test fun kpDueWithoutOriginalIdentityRemainsUnknownAndRetainsScheduleAfterVariantAllocation() {
        val l = Ledger()
        val schedule = project(l.events).review.getValue("kp")
        val dueId = legacyDueId(l.scope, "kp", schedule)
        val queue = l.queue()
        val unknown = queue.entries.single()
        assertNull(unknown.originalQuestion)
        assertEquals(dueId, unknown.unknownDueId)
        assertEquals(schedule, unknown.sources.single().legacySchedule)
        assertTrue(unknown.originalAttempts.isEmpty())
        val assigned = queue.assignVariant(unknown.id, variant(unknown)).entries.single()
        assertEquals(unknown.id, assigned.id)
        assertNull(assigned.originalQuestion)
        assertEquals(dueId, assigned.unknownDueId)
        assertEquals(QueueReadiness.READY, assigned.readiness)
    }

    @Test fun sharingAKpDoesNotInventAnOriginalQuestionForAnUnlinkedDue() {
        val l = Ledger()
        l.answer("wrong", 0)
        val entries = l.queue().entries
        assertEquals(2, entries.size)
        assertEquals(1, entries.count { it.originalQuestion == null })
        assertTrue(entries.single { it.originalQuestion != null }.sources.none { it.reason == QueueReason.DUE })
    }

    @Test fun replayOrderDuplicateEventsAndJsonRestoreKeepOrderCursorAndOriginals() {
        val l = Ledger()
        val a = l.answer("a", 0)
        l.answer("b", 0, q = "other-original")
        val links = listOf(l.dueLink(a))
        val before = l.queue(links)
        val focused = before.focus(before.entries.last().id)
        val restored = LedgerJson.decodeFromString(ReviewQueueCandidate.serializer(), focused.bytes())
        assertEquals(focused, restored)
        assertEquals(before.bytes(), l.queue(links, (l.events + l.events).reversed()).bytes())
        assertEquals(before.entries.map { it.id }, restored.entries.map { it.id })
        assertEquals(focused.cursorEntryId, restored.cursorEntryId)
    }

    @Test fun actualMainRevisitAndExamOverlayFlowThroughThePublicProjection() {
        val l = Ledger()
        // A second point is not yet passed, so its missed mainline answer creates a real revisit.
        val a = l.answer("wrong", 0)
        val kp2Events = l.events.take(1) + l.events.drop(3).map {
            if (it.type == "question_posed") it.copy(payload = JsonObject(it.payload +
                json("kpIds" to listOf("kp2")))) else it
        }
        val projection = project(kp2Events)
        assertEquals(l.day + 1, projection.revisit["kp2"])
        val realDue = projection.reviewsOn(l.day + 1).getValue("kp2")
        val candidate = ledgerQueueCandidate(l.scope.courseId, kp2Events, l.day + 1)
        assertEquals(realDue.dueDay, candidate.entries.single { it.originalQuestion == null }.dueDay)
        assertTrue(candidate.entries.any { it.originalAttempts.singleOrNull()?.id == a.id })

        // main's exam overlay compresses a future schedule without changing the legacy identity.
        l.add("legacy_schedule", json("kpId" to "kp", "dueDay" to l.day + 10, "interval" to 20))
        val oldSchedule = project(l.events).review.getValue("kp")
        l.add("exam_set", json("examDay" to l.day + 7))
        val real = project(l.events).reviewsOn(l.day).getValue("kp")
        val unknown = l.queue().entries.single { it.originalQuestion == null }
        assertEquals(real.dueDay, unknown.dueDay)
        assertEquals(legacyDueId(l.scope, "kp", oldSchedule), unknown.unknownDueId)
        assertEquals(oldSchedule, unknown.sources.single().legacySchedule)
    }

    @Test fun duplicatePendingSyncAndAllocationDoNotCreateItemsOrOverwriteAnswers() {
        val l = Ledger()
        val original = l.answer("a", 0)
        val initial = l.queue(listOf(l.dueLink(original)))
        val entry = initial.entries.single()
        val allocated = variant(entry)
        val pending = initial.assignVariant(entry.id, allocated).pendingSync(entry.id, allocated.attemptId)
        val retry = pending.pendingSync(entry.id, allocated.attemptId).assignVariant(entry.id, allocated)
        assertEquals(pending, retry)
        assertEquals(1, retry.entries.size)
        assertEquals(1, retry.entries.single().variants.size)
        assertEquals(listOf(original), retry.entries.single().originalAttempts)
        assertEquals(QueueReadiness.PENDING_SYNC, retry.entries.single().readiness)
        assertEquals(pending, LedgerJson.decodeFromString(ReviewQueueCandidate.serializer(), pending.bytes()))
        val withdrawn = pending.invalidateQuestion(allocated.question, QueueAvailability.RETIRED)
        val restored = LedgerJson.decodeFromString(ReviewQueueCandidate.serializer(), withdrawn.bytes())
        assertEquals(listOf(allocated.attemptId), restored.entries.single().pendingSyncAttemptIds)
        assertEquals(QueueReadiness.NEEDS_VARIANT, restored.entries.single().readiness)
        assertEquals(restored, restored.pendingSync(entry.id, allocated.attemptId))
    }

    @Test fun conflictingReplayCannotReplaceOriginalSubmission() {
        val l = Ledger()
        val a = l.answer("a", 0)
        val queue = l.queue(listOf(l.dueLink(a)))
        val source = queue.entries.single().sources.first { it.reason == QueueReason.WRONG }
        val before = queue.bytes()
        val changed = a.copy(submitted = a.submitted!!.copy(payload = json("attemptId" to a.id, "answer" to "changed")))
        assertFailsWith<IllegalArgumentException> {
            queue.observe(l.scope, "kp", l.question(a), source, l.day, listOf(changed))
        }
        assertEquals(before, queue.bytes())
    }

    @Test fun retirementAndReplacementKeepHistoryButRequireAValidNewVariant() {
        val l = Ledger()
        val a = l.answer("a", 0, version = "original-v1")
        val queue = l.queue(listOf(l.dueLink(a)))
        val entry = queue.entries.single()
        val first = variant(entry)
        val allocated = queue.assignVariant(entry.id, first).focus(entry.id)
        for (state in listOf(QueueAvailability.RETIRED, QueueAvailability.REPLACED, QueueAvailability.UNVERIFIED)) {
            val invalid = allocated.invalidateQuestion(first.question, state)
            val old = invalid.entries.single()
            assertEquals(QueueReadiness.NEEDS_VARIANT, old.readiness)
            assertEquals(first.question, old.variants.single().question)
            assertEquals(listOf(a), old.originalAttempts)
            assertEquals(entry.id, invalid.cursorEntryId)
            assertFailsWith<IllegalArgumentException> { invalid.assignVariant(entry.id, first) }
            val second = variant(entry, id = "new-variant-attempt", version = "v2")
            val ready = invalid.assignVariant(entry.id, second).entries.single()
            assertEquals(QueueReadiness.READY, ready.readiness)
            assertEquals(listOf(first.question, second.question), ready.variants.map { it.question })
            assertEquals(l.question(a), ready.originalQuestion)
        }
        // Retiring the original does not discard an already allocated valid alternative.
        assertEquals(QueueReadiness.READY, allocated.invalidateQuestion(l.question(a), QueueAvailability.RETIRED).entries.single().readiness)
    }

    @Test fun versionsCoursesAndKpsHaveDistinctStableIdentities() {
        val scope = QueueScope("C")
        val source = QueueSource("wrong", QueueReason.WRONG)
        val v1 = QueueQuestion("q:/x", "v1")
        val v2 = v1.copy(version = "v2")
        val queue = ReviewQueueCandidate(scope).observe(scope, "kp", v1, source, 1)
            .observe(scope, "kp", v2, source, 1).observe(scope, "kp2", v1, source, 1)
        assertEquals(3, queue.entries.map { it.id }.distinct().size)
        val otherScope = QueueScope("other-C")
        val other = ReviewQueueCandidate(otherScope).observe(otherScope, "kp", v1, source, 1)
        assertTrue(other.entries.single().id !in queue.entries.map { it.id })
        assertFailsWith<IllegalArgumentException> { queue.observe(otherScope, "kp", v1, source, 1) }
    }

    @Test fun realLedgerVersionsKeepSeparateHistoriesAndConflictingEventReplayIsRejected() {
        val l = Ledger()
        val v1 = l.answer("v1-attempt", 0, version = "v1")
        val v2 = l.answer("v2-attempt", 0, version = "v2")
        val queue = l.queue(listOf(l.dueLink(v2)))
        val known = queue.entries.filter { it.originalQuestion != null }
        assertEquals(setOf("v1", "v2"), known.map { it.originalQuestion!!.version }.toSet())
        assertEquals(listOf(v1), known.single { it.originalQuestion!!.version == "v1" }.originalAttempts)
        assertEquals(listOf(v2), known.single { it.originalQuestion!!.version == "v2" }.originalAttempts)
        assertTrue(known.all { it.readiness == QueueReadiness.NEEDS_VARIANT })
        val duplicate = v1.submitted!!.copy(payload = json("attemptId" to v1.id, "answer" to "changed"))
        assertFailsWith<IllegalArgumentException> { l.queue(input = l.events + duplicate) }
        assertFailsWith<IllegalArgumentException> { l.queue(input = l.events.map { it.copy(courseId = "different") }) }
        val future = l.events.first().copy(schema = 99)
        assertFailsWith<IllegalArgumentException> { l.queue(input = listOf(future) + l.events.drop(1)) }
    }

    @Test fun agentFactsAndUnmarkedLateGradesNeverEnterLearnerQueue() {
        val l = Ledger()
        val a = l.answer("human", 0)
        val before = l.queue(listOf(l.dueLink(a)))
        val agent = l.answer("agent", 0, q = "agent-question", run = "synthetic-run")
        assertEquals(before, l.queue(listOf(l.dueLink(a))))
        assertEquals(before, l.queue(listOf(l.dueLink(a)), l.events.map(::agentEvidenceForWire)))
        assertFailsWith<IllegalArgumentException> {
            before.observe(l.scope, "kp", l.question(agent), QueueSource("agent-source", QueueReason.WRONG), l.day, listOf(agent))
        }
        assertFailsWith<IllegalArgumentException> {
            before.observe(l.scope.copy(agentRunId = "synthetic-run"), "kp", l.question(a),
                QueueSource("agent-source", QueueReason.WRONG), l.day)
        }
    }

    @Test fun wrongKpUnavailableVariantAndOriginalQuestionCannotBeAllocated() {
        val l = Ledger()
        val a = l.answer("a", 0)
        val queue = l.queue(listOf(l.dueLink(a)))
        val entry = queue.entries.single()
        for (bad in listOf(variant(entry).copy(kpId = "kp2"), variant(entry).copy(courseId = "other"),
            variant(entry).copy(availability = QueueAvailability.UNVERIFIED),
            variant(entry).copy(question = l.question(a)), variant(entry).copy(agentRunId = "agent"))) {
            assertFailsWith<IllegalArgumentException> { queue.assignVariant(entry.id, bad) }
        }
        assertFailsWith<IllegalArgumentException> { queue.pendingSync(entry.id, "not-allocated") }
    }

    @Test fun gradeConfirmationKeepsFrozenAnswerAndFullGradeHistoryAndCompletionOnlyFiltersTheQueue() {
        val l = Ledger()
        val a = l.answer("a", 0)
        val confirmation = l.add("grade_reviewed", json("attemptId" to a.id, "decision" to "CONFIRM"))
        val queue = l.queue(listOf(l.dueLink(a)))
        val entry = queue.entries.single()
        assertEquals(a.grades + confirmation, entry.originalAttempts.single().grades)
        assertEquals(a.submitted, entry.originalAttempts.single().submitted)
        val completed = queue.complete(entry.id)
        assertTrue(completed.dueEntries(l.day).isEmpty())
        assertEquals(queue.entries.single().originalAttempts, completed.entries.single().originalAttempts)
        assertEquals(entry.sources, completed.entries.single().sources)
    }

    @Test fun successfulLatestOriginalAnswerDoesNotCreateAMistakeButVoidedHistoryRemainsAuditable() {
        val l = Ledger()
        val old = l.answer("old-wrong", 0)
        l.add("attempt_voided", json("attemptId" to old.id, "reason" to "synthetic-invalid"))
        val guessed = l.answer("guessed", 10, guessed = true)
        val queue = l.queue(listOf(l.dueLink(guessed)))
        assertEquals(listOf(old, guessed), queue.entries.single().originalAttempts)
        assertTrue(queue.entries.single().sources.none { it.attemptId == old.id })
        l.answer("correct", 10)
        val after = l.queue()
        assertTrue(after.entries.none { it.originalQuestion != null })
        assertTrue(after.entries.single().sources.all { it.reason == QueueReason.DUE })
    }
}
