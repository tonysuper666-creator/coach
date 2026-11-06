package com.coach.core

import com.coach.domain.*
import com.coach.store.LedgerStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import java.nio.file.Files
import kotlin.test.*

class TutorConceptRevisionTest {
    private suspend fun TutorFixture.revise(concepts: Set<String>, seeded: Boolean = false) = actor.execute {
        val point = json((if (seeded) "id" else "kpId") to "kp-1", "conceptIds" to concepts.sorted())
        append("course", "outline_revised", json(
            (if (seeded) "knowledgePoints" else "ops") to JsonArray(listOf(point))))
    }

    @Test fun revisedOutlineReopensWithoutChangingHistoryOrEvidence() = runBlocking {
        TutorFixture().use { f ->
            f.seed(); f.open(concepts = setOf("kp-1")); f.teach()
            val graded = f.machine.next("course", "thread").cards
            f.submit(graded)
            val pending = f.machine.next("course", "thread").cards
            val before = f.events()
            val mastery = f.state().mastery
            f.revise(setOf("concept:new-a", "concept:new-b"), seeded = true)
            assertEquals(TutorState.AWAIT_ANSWER, f.open(concepts = setOf("concept:new-a", "concept:new-b")).state)
            assertEquals(before, f.events().take(before.size))
            assertEquals(mastery, f.state().mastery)
            assertEquals(setOf("kp-1"), f.state().taughtConceptIds)
            assertTrue("kp-1" in f.state().explainedKpIds)
            assertEquals(pending, f.machine.next("course", "thread").cards)
            // Reopening with an old replica is idempotent and never rolls the mapping back.
            f.open(concepts = setOf("kp-1"))
            assertEquals(1, f.events().count { it.type == "tutor_concepts_changed" })
            assertTrue(f.state().unsupportedTypes.isEmpty())
            f.submit(pending, id = "after-revision")
            val plan = assertNotNull(f.machine.explain("course", "thread", TeachingMode.EXAMPLE).plan)
            assertEquals(setOf("concept:new-a", "concept:new-b"), plan.conceptIds)
            assertTrue(plan.recapConceptIds.isEmpty())
            assertFalse(plan.firstExplanation)
        }
    }

    @Test fun partialOutlineEditsDoNotHideLatestExplicitConcepts() = runBlocking {
        TutorFixture().use { f ->
            f.open(); f.revise(setOf("cost", "new"))
            f.actor.execute {
                append("course", "outline_revised", json("ops" to JsonArray(listOf(
                    json("op" to "move", "kpId" to "kp-1", "index" to 0)))))
            }
            f.open(concepts = setOf("stale"))
            val plan = assertNotNull(f.machine.next("course", "thread").plan)
            assertEquals(setOf("cost", "new"), plan.context.concepts)
            f.machine.finish(plan, "delivered", true)
            // Removed concepts stay in historical evidence, but are not sent as current concepts.
            f.revise(setOf("new", "added"))
            f.open(concepts = setOf("cost", "new"))
            val recap = assertNotNull(f.machine.explain("course", "thread", TeachingMode.EXAMPLE).plan)
            assertEquals(setOf("added"), recap.conceptIds)
            assertEquals(setOf("new"), recap.recapConceptIds)
            assertTrue("cost" in f.state().taughtConceptIds)
        }
    }

    @Test fun newThreadAlsoUsesTheOutlineInsteadOfStaleClientMetadata() = runBlocking {
        TutorFixture().use { f ->
            f.revise(setOf("canonical"), seeded = true)
            f.open(concepts = setOf("old"))
            val opened = f.events().single { it.type == "kp_opened" }
            assertEquals(listOf("canonical"), opened.payload.strings("conceptIds"))
            assertTrue(f.events().none { it.type == "tutor_concepts_changed" })
        }
    }

    @Test fun unrelatedConceptChangesAndIdentityChangesStillFailAtomically() = runBlocking {
        TutorFixture().use { f ->
            f.open()
            var before = f.events()
            assertFailsWith<IllegalArgumentException> { f.open(concepts = setOf("invented")) }
            assertEquals(before, f.events())
            f.revise(setOf("revised"))
            before = f.events()
            assertFailsWith<IllegalArgumentException> { f.open(kp = "another-kp") }
            assertFailsWith<IllegalArgumentException> {
                f.machine.open("course", "thread", "kp-1", setOf("revised"), "Review", review = true)
            }
            assertFailsWith<IllegalArgumentException> {
                f.machine.open("another-course", "thread", "kp-1", setOf("revised"), "Other course")
            }
            assertEquals(before, f.events())
        }
    }

    @Test fun activeReplyCanFinishWithItsOriginalEvidenceAfterReopen() = runBlocking {
        TutorFixture().use { f ->
            f.open()
            val plan = assertNotNull(f.machine.next("course", "thread").plan)
            f.revise(setOf("new"))
            assertEquals(TutorState.TEACH, f.open().state)
            f.machine.finish(plan, "old-plan", true)
            assertEquals(setOf("cost"), f.state().taughtConceptIds)
            val current = assertNotNull(f.machine.explain("course", "thread", TeachingMode.EXAMPLE).plan)
            assertEquals(setOf("new"), current.context.concepts)
            assertEquals(setOf("new"), current.conceptIds)
        }
    }

    @Test fun reviewThreadReconcilesWithoutReplacingLearningThread() = runBlocking {
        TutorFixture().use { f ->
            f.open()
            f.machine.open("course", "review", "kp-1", setOf("cost"), "Review", review = true)
            f.revise(setOf("current"))
            f.machine.open("course", "review", "kp-1", setOf("cost"), "Review", review = true)
            val plan = f.machine.followup("course", "review")
            assertTrue(plan.context.review)
            assertEquals(setOf("current"), plan.context.concepts)
            f.open()
            assertFalse(assertNotNull(f.machine.next("course", "thread").plan).context.review)
        }
    }

    @Test fun conceptRevisionSurvivesRestart() = runBlocking {
        val directory = Files.createTempDirectory("coach-concept-restart-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var actor = LedgerActor(scope, LedgerStore(directory))
            try {
                TutorMachine(actor).open("course", "thread", "kp", setOf("old"), "Synthetic")
                actor.execute { append("course", "outline_revised", json("ops" to JsonArray(listOf(
                    json("kpId" to "kp", "conceptIds" to listOf("new")))))) }
                TutorMachine(actor).open("course", "thread", "kp", setOf("new"), "Synthetic")
            } finally { actor.close() }
            actor = LedgerActor(scope, LedgerStore(directory))
            try {
                val machine = TutorMachine(actor)
                machine.open("course", "thread", "kp", setOf("old"), "Synthetic")
                assertEquals(setOf("new"), assertNotNull(machine.next("course", "thread").plan).context.concepts)
                assertEquals(1, actor.execute { events("course").count { it.type == "tutor_concepts_changed" } })
            } finally { actor.close() }
        } finally { scope.cancel(); directory.toFile().deleteRecursively() }
    }
}
