package com.coach.store

import com.coach.domain.*
import com.coach.domain.reviewqueue.*
import java.nio.file.Files
import kotlin.test.*

/** The real pool port: the store offers only what a learner can actually practise, and the queue keeps the rest as gaps. */
class ReviewVariantCandidatesTest {
    @Test fun onlyLiveVerifiedUnposedQuestionsOnTheSameKpAreOffered() {
        val root = Files.createTempDirectory("review-variants")
        try { LedgerStore(root).use { store ->
            val options = listOf(Option("o1", "synthetic one"), Option("o2", "synthetic two"))
            store.registerQuestion("q-posed", "c", "fp-posed", "SINGLE_CHOICE", "Synthetic already posed", "VERIFIED", "o1", options, listOf("kp"))
            store.registerQuestion("q-unverified", "c", "fp-unv", "SINGLE_CHOICE", "Synthetic unverified", "UNREVIEWED", "o1", options, listOf("kp"))
            store.registerQuestion("q-other-kp", "c", "fp-other", "SINGLE_CHOICE", "Synthetic other point", "VERIFIED", "o1", options, listOf("kp2"))
            store.registerQuestion("q-live", "c", "fp-live", "SINGLE_CHOICE", "Synthetic live", "VERIFIED", "o1", options, listOf("kp"))
            // The question route consumes the first pool row; it must never come back as a review variant.
            val taken = store.takePoolGroup("c", "kp", "synthetic-group", 1)
            assertEquals("q-posed", taken.events.single().payload.str("questionId"))

            val offered = store.reviewVariantCandidates("c", "kp")
            assertEquals(listOf("q-live"), offered.map { it.question.id })
            val candidate = offered.single()
            assertEquals("c", candidate.courseId)
            assertEquals("kp", candidate.kpId)
            assertTrue(candidate.keyVerified)
            assertEquals(QueueAvailability.VALID, candidate.availability)
            assertNotNull(candidate.question.fingerprint)
            assertNull(candidate.question.version, "An unknown version stays unknown")
            assertEquals(emptyList(), store.reviewVariantCandidates("c", "kp-missing"))

            // The real queue consumes that port: it becomes READY with this question, and no other entry is invented.
            val scope = QueueScope("c")
            val queue = ReviewQueueCandidate(scope).observe(scope, "kp",
                QueueQuestion("q-original", "v1", "fp-original"), QueueSource("wrong-1", QueueReason.WRONG), 20_000)
            val allocated = queue.allocateVariants(VariantCandidatePort { course, run, kp ->
                if (course == "c" && run == null && kp == "kp") offered else emptyList()
            })
            val entry = allocated.queue.entries.single()
            assertEquals(QueueReadiness.READY, entry.readiness)
            assertEquals("q-live", entry.variants.single().question.id)
            assertEquals("q-original", entry.originalQuestion?.id)
        } } finally { root.toFile().deleteRecursively() }
    }
}
