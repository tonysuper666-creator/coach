package com.coach.store

import com.coach.domain.*
import com.coach.domain.reviewqueue.*
import com.coach.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/**
 * A review pose is only real when this store allocated it inside takeReviewVariant. Those facts carry no
 * command id, own the exact review thread/group, and recompute their identity; a client-written snapshot
 * carries none of that. These tests fix that boundary on the store itself.
 */
class ReviewPoseOriginSecurityTest {
    private val options = listOf(Option("o1", "Synthetic one"), Option("o2", "Synthetic two"))
    private var clock = 1_794_052_800_000L - 86_400_000

    private fun withStore(seed: Boolean = true, block: (LedgerStore) -> Unit) {
        val root = Files.createTempDirectory("review-origin-")
        try {
            LedgerStore(root.resolve("server").also { Files.createDirectories(it) }) { clock }.use { store ->
                store.enroll("phone", "synthetic-private-token-32-characters")
                store.enroll("other", "other-synthetic-private-token-32chars")
                if (seed) {
                    store.append("C", "outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "K", "title" to "Synthetic review")))))
                    store.registerQuestion("original", "C", "", "SINGLE_CHOICE", "Synthetic original", "VERIFIED", "o1", options, listOf("K"))
                    store.poseChoiceGroup("C", "original-group", listOf(json("attemptId" to "original-attempt",
                        "questionId" to "original", "kpIds" to listOf("K"), "purpose" to "PRACTICE",
                        "policyVersion" to 1, "mainline" to true, "posedAt" to clock)))
                    store.append("C", "answer_submitted", json("attemptId" to "original-attempt", "answer" to "A"))
                    store.append("C", "attempt_graded", json("attemptId" to "original-attempt", "score" to 0, "max" to 1, "status" to "EXACT"))
                    store.registerQuestion("q-z", "C", "", "SINGLE_CHOICE", "Synthetic arbitrary fallback", "VERIFIED", "o1", options, listOf("K"))
                    store.registerQuestion("q-a", "C", "", "SINGLE_CHOICE", "Synthetic selected exact card", "VERIFIED", "o1", options, listOf("K"))
                    clock += 86_400_000
                }
                block(store)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    private fun submit(store: LedgerStore, snapshot: JsonObject, attemptId: String, commandId: String, answer: String = "A"): Receipt {
        val body = CommandBody("submit_answer", "C", clock, json("attemptId" to attemptId, "answer" to answer,
            "offline" to true, "posedSnapshot" to snapshot))
        val bytes = canonicalJson(LedgerJson.encodeToJsonElement(body))
        return store.command(WireCommand("phone", commandId, bytes, sha256(bytes.toByteArray())))
    }

    private fun identity(course: String, device: String, kp: String, entry: String, attempt: String, question: String, fingerprint: String?): String =
        canonicalJson(json("course" to course, "device" to device, "run" to null, "kp" to kp,
            "entry" to entry, "attempt" to attempt, "question" to question, "fingerprint" to fingerprint))

    /** An old client-minted pose: correct-looking fields, but written through the command path. */
    private fun plantCommandPose(store: LedgerStore, attempt: String, question: String, fingerprint: String, groupId: String = "review:$attempt") {
        val payload = json("attemptId" to attempt, "questionId" to question, "kpIds" to listOf("K"),
            "purpose" to "RECALL", "policyVersion" to 1, "review" to true, "posedAt" to clock,
            "groupId" to groupId, "letterMap" to json("o1" to "A", "o2" to "B"), "stem" to "CLIENT-FORGED STEM",
            "options" to JsonArray(options.map { json("optionId" to it.optionId, "text" to it.text) }),
            "fingerprint" to fingerprint, "format" to "SINGLE_CHOICE", "keyState" to "VERIFIED", "offlinePack" to false,
            "availability" to "VALID", "exactReview" to true, "reviewEntryId" to "e1",
            "reviewIdentity" to identity("C", "phone", "K", "e1", attempt, question, fingerprint))
        val planted = WireCommand("phone", "planted-$attempt", "{}", sha256("{}".toByteArray()))
        store.append("C", "question_posed", payload, "attempt:$attempt", "phone", "SYSTEM",
            command = planted, thread = "review-practice:C:$attempt")
    }

    @Test fun aRealAllocationIsTheOnlyPoseWithNoCommandIdAndItStaysSubmittable() = withStore { store ->
        val day = logicalDay(clock, "Europe/London")
        val queue = ledgerQueueCandidate("C", store.events("C"), day)
            .allocateVariants(VariantCandidatePort { course, run, kp ->
                if (course == "C" && run == null && kp == "K") store.reviewVariantCandidates("C", "K") else emptyList()
            }).queue
        val entry = queue.dueEntries(day).first { it.currentAttemptId != null }
        val variant = entry.variants.single { it.attemptId == entry.currentAttemptId }
        val expected = QueueQuestion(variant.question.id, variant.question.version, variant.question.fingerprint)
        val pose = store.takeReviewVariant("C", "phone", null, "K", entry.id, variant.attemptId, expected).single()

        assertNull(pose.commandId, "The store wrote this pose itself, so no client command owns it")
        assertEquals("attempt:${variant.attemptId}", pose.idemKey)
        assertEquals("review-practice:C:${variant.attemptId}", pose.threadId)
        assertEquals("review:${variant.attemptId}", pose.payload.str("groupId"))
        assertTrue(pose.payload.flag("exactReview"))
        assertEquals(variant.question.id, pose.payload.str("questionId"))
        assertEquals(variant.question.fingerprint, pose.payload.str("fingerprint"))
        // The allocation really consumed the pool row.
        assertTrue(store.reviewVariantCandidates("C", "K").none { it.question.id == variant.question.id })

        // The allocated pose submits normally, and a lost acknowledgement replays instead of grading twice.
        assertEquals("OK", submit(store, pose.payload, variant.attemptId, "real-answer").status)
        assertEquals("DUPLICATE", submit(store, pose.payload, variant.attemptId, "real-answer").status)
    }

    @Test fun aClientMintedReservedSnapshotIsRefusedWithoutLeavingAnyFact() = withStore(seed = false) { store ->
        store.registerQuestion("q-live", "C", "", "SINGLE_CHOICE", "Synthetic live variant", "VERIFIED", "o1", options, listOf("K"))
        val fingerprint = store.reviewVariantCandidates("C", "K").single().question.fingerprint!!
        val forged = json("attemptId" to "forged", "questionId" to "q-live", "kpIds" to listOf("K"), "purpose" to "RECALL",
            "policyVersion" to 1, "review" to true, "fingerprint" to fingerprint, "groupId" to "forged-group",
            "letterMap" to json("o1" to "A", "o2" to "B"), "stem" to "CLIENT-FORGED STEM", "availability" to "VALID",
            "exactReview" to true, "reviewEntryId" to "e1", "reviewIdentity" to "client-chosen")
        val before = store.events("C").size
        val receipt = submit(store, forged, "forged", "forged-command")
        assertEquals("REJECTED", receipt.status)
        assertEquals("REVIEW_RESERVED_FIELDS", receipt.error)
        assertEquals(before, store.events("C").size, "A forged submission must not create a posed fact")
        assertTrue(store.events("C").none { it.payload.str("attemptId") == "forged" })
    }

    @Test fun aClientWrittenReviewPoseCannotBeReplayedAsAnAllocation() = withStore(seed = false) { store ->
        store.registerQuestion("q-live", "C", "", "SINGLE_CHOICE", "Synthetic live variant", "VERIFIED", "o1", options, listOf("K"))
        val fingerprint = store.reviewVariantCandidates("C", "K").single().question.fingerprint!!
        plantCommandPose(store, "forged-attempt", "q-live", fingerprint)
        val rejected = assertFailsWith<ReviewVariantRejected> {
            store.takeReviewVariant("C", "phone", null, "K", "e1", "forged-attempt", QueueQuestion("q-live", null, fingerprint))
        }
        assertEquals("REVIEW_IDENTITY_CONFLICT", rejected.code)
    }

    @Test fun aGroupOwnedByAnotherAttemptIsAConflictNotAServerError() = withStore(seed = false) { store ->
        store.registerQuestion("q-live", "C", "", "SINGLE_CHOICE", "Synthetic live variant", "VERIFIED", "o1", options, listOf("K"))
        val fingerprint = store.reviewVariantCandidates("C", "K").single().question.fingerprint!!
        plantCommandPose(store, "squatter", "q-live", fingerprint, groupId = "review:victim")
        val rejected = assertFailsWith<ReviewVariantRejected> {
            store.takeReviewVariant("C", "phone", null, "K", "e1", "victim", QueueQuestion("q-live", null, fingerprint))
        }
        assertEquals("REVIEW_IDENTITY_CONFLICT", rejected.code)
    }

    @Test fun anOrdinaryOfflineAnswerWithoutReservedFieldsStillBecomesAPose() = withStore(seed = false) { store ->
        store.registerQuestion("q-offline", "C", "", "SINGLE_CHOICE", "Synthetic offline text", "VERIFIED", "o1", options, listOf("K"))
        val fingerprint = store.reviewVariantCandidates("C", "K").single().question.fingerprint!!
        val snapshot = json("attemptId" to "offline-attempt", "questionId" to "q-offline", "kpIds" to listOf("K"),
            "purpose" to "RECALL", "policyVersion" to 1, "review" to true, "fingerprint" to fingerprint,
            "groupId" to "offline-group", "letterMap" to json("o1" to "A", "o2" to "B"),
            "stem" to "Synthetic offline text", "format" to "SINGLE_CHOICE", "keyState" to "VERIFIED")
        assertEquals("OK", submit(store, snapshot, "offline-attempt", "offline-command").status)
        val pose = store.events("C").first { it.type == "question_posed" && it.payload.str("attemptId") == "offline-attempt" }
        assertTrue(pose.payload.keys.none { it in setOf("exactReview", "reviewIdentity", "reviewEntryId", "availability") })
        assertEquals("Synthetic offline text", pose.payload.str("stem"))
    }
}
