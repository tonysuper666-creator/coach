package com.coach.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class QuestionLadderTest {
    private fun q(id: String, kp: String = "fruit", kind: String = "concept", difficulty: Int = 1, priority: Int = 1,
                  prerequisites: List<String> = emptyList(), eligible: Boolean = true, fingerprint: String = id) =
        LadderQuestion(id, kp, "orchard", kind, difficulty, "fruit-variants", fingerprint, prerequisites, priority, eligible)

    @Test fun wrongOrGuessedOrDirectExplanationImmediatelySelectsAnUnseenEasierQuestion() {
        val hard = q("application", kind = "application", difficulty = 3)
        val easy = q("basic", kind = "introduction", difficulty = 0)
        val other = q("other", kp = "other", priority = 5)
        val pool = listOf(hard, other, easy)
        for (remember in listOf("wrong", "guessed", "did not know")) {
            val session = QuestionLadder.record(LadderSession(), hard, false, remember)
            assertEquals("basic", QuestionLadder.next(pool, session).question?.id)
            val exhausted = QuestionLadder.record(session, easy, false, "basic")
            val step = QuestionLadder.next(pool, exhausted)
            assertNull(step.question)
            assertEquals(setOf("fruit"), step.blockedKps)
        }
    }

    @Test fun guessedAndDuplicateQuestionsCannotProduceMastery() {
        var session = LadderSession()
        session = QuestionLadder.record(session, q("one"), true, "one")
        session = QuestionLadder.record(session, q("two"), false, "two")
        session = QuestionLadder.record(session, q("three"), true, "three")
        assertFalse(QuestionLadder.learned(session, "fruit"))
        session = QuestionLadder.record(session, q("four"), true, "four")
        session = QuestionLadder.record(session, q("five"), true, "five")
        assertTrue(QuestionLadder.learned(session, "fruit"))
        val duplicate = session.copy(responses = session.responses.takeLast(3).map { it.copy(fingerprint = "same") })
        assertFalse(QuestionLadder.learned(duplicate, "fruit"))
    }

    @Test fun firstTryIsSharedWithTheWeekButDoesNotTeachOrMasterAnyPoint() {
        val primer = (1..4).map { q("try$it", kind = "try_first") }
        val teaching = q("teach", kind = "introduction")
        var session = LadderSession()
        for (question in primer) {
            assertEquals(question.id, QuestionLadder.next(primer + teaching, session).question?.id)
            session = QuestionLadder.record(session, question, true, "not a recap fact")
        }
        assertFalse(QuestionLadder.learned(session, "fruit"))
        assertTrue(QuestionLadder.recap(session).isEmpty())
        assertEquals("teach", QuestionLadder.next(primer + teaching, session).question?.id)
    }

    @Test fun recapAfterSixSubmissionsSurvivesRestartAndIdempotentReplay() {
        var session = LadderSession()
        for (i in 1..6) session = QuestionLadder.record(session, q("q$i"), true, "remember ${i % 4}")
        assertTrue(session.recapOpen)
        assertEquals(4, QuestionLadder.recap(session).size)
        val restored = LedgerJson.decodeFromString(LadderSession.serializer(), LedgerJson.encodeToString(LadderSession.serializer(), session))
        assertEquals(session, restored)
        assertEquals(restored, QuestionLadder.record(restored, q("q6"), true, "changed replay"))
        assertFalse(QuestionLadder.closeRecap(restored).recapOpen)
        assertTrue(QuestionLadder.recap(QuestionLadder.closeRecap(restored)).isEmpty())
    }

    @Test fun weightedTrajectoryPreservesIntroductionsAndPrerequisites() {
        val important = (1..5).map { q("high$it", "high", if (it == 1) "introduction" else "concept", priority = 3) }
        val ordinary = (1..5).map { q("low$it", "low", if (it == 1) "introduction" else "concept") }
        val dependent = q("dependent", "next", prerequisites = listOf("high"), priority = 5)
        val pool = ordinary + important + dependent
        var session = LadderSession()
        val trajectory = mutableListOf<String>()
        repeat(5) {
            val chosen = assertNotNull(QuestionLadder.next(pool, session).question)
            trajectory += chosen.id
            session = QuestionLadder.record(session, chosen, true, chosen.id)
        }
        assertEquals(listOf("high1", "low1", "high2", "high3", "dependent"), trajectory)
    }

    @Test fun deletedAndUnverifiedQuestionsAndReshufflesAreNotSupply() {
        val original = q("original")
        val session = QuestionLadder.record(LadderSession(), original, true, "one")
        assertNull(QuestionLadder.next(listOf(q("reshuffle", fingerprint = "original"), q("removed", eligible = false)), session).question)
    }

    @Test fun twoIndependentCorrectAnswersSkipOnlyRemainingIntroductionQuestions() {
        var session = QuestionLadder.record(LadderSession(), q("first", kind = "introduction"), true, "one")
        session = QuestionLadder.record(session, q("second"), true, "two")
        val pool = listOf(q("redundant", kind = "introduction"), q("detail", kind = "detail"))
        assertEquals("detail", QuestionLadder.next(pool, session).question?.id)
    }

    @Test fun calculationStepsPrecedeTheCompleteExampleEvenWhenItHasHigherPriority() {
        val first = q("step1").copy(calculationChain = "sum", calculationStep = 1, completeExampleId = "complete")
        val second = q("step2").copy(calculationChain = "sum", calculationStep = 2, completeExampleId = "complete")
        val whole = q("complete", priority = 5).copy(calculationChain = "sum")
        val pool = listOf(whole, second, first)
        var session = LadderSession()
        assertEquals(first.id, QuestionLadder.next(pool, session).question?.id)
        session = QuestionLadder.record(session, first, true, "step1")
        assertEquals(second.id, QuestionLadder.next(pool, session).question?.id)
        session = QuestionLadder.record(session, second, true, "step2")
        assertEquals(whole.id, QuestionLadder.next(pool, session).question?.id)
        assertNull(QuestionLadder.next(listOf(second, whole), LadderSession()).question)
    }

    @Test fun firstTryCannotAlterExistingMasteryOrReviewAndIsNotACorrectJournalAnswer() {
        val events = mutableListOf<Event>()
        fun e(type: String, payload: JsonObject) {
            val n = events.size.toLong() + 1
            events += Event(n, "e$n", "C", type, recordedAt = 1793188800000L, occurredAt = 1793188800000L,
                deviceId = "synthetic", actor = "SYSTEM", idemKey = "i$n", payload = payload)
        }
        repeat(4) { index ->
            val id = "primer$index"
            e("question_posed", json("attemptId" to id, "questionId" to id, "fingerprint" to id, "kpIds" to listOf("fruit"),
                "mainline" to true, "tryFirst" to true, "keyState" to "VERIFIED", "format" to "SINGLE_CHOICE", "purpose" to "RECALL", "origin" to "COACH", "policyVersion" to 1))
            e("answer_submitted", json("attemptId" to id, "answer" to "A"))
            e("attempt_graded", json("attemptId" to id, "score" to 1, "max" to 1, "status" to "EXACT"))
        }
        val projection = project(events)
        assertTrue(projection.mastery.values.none { it.passed })
        assertTrue(projection.review.isEmpty())
        assertTrue(projection.revisit.isEmpty())
        assertTrue(pendingChoiceMistakes(events, setOf("fruit")).isEmpty())
    }
}
