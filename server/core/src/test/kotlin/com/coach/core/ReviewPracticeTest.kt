package com.coach.core

import com.coach.domain.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ReviewPracticeTest {
    @Test fun reviewStartsWithCardsAndGradingReschedulesTheLedgerWithoutMovingContinue() = runBlocking {
        TutorFixture().use { f ->
            f.seed()
            f.open(thread = "learning")
            f.actor.execute { append("course", "legacy_passed", json("kpId" to "kp-1")) }
            val before = f.state()
            val due = before.review.getValue("kp-1").dueDay
            f.machine.open("course", "review", "kp-1", setOf("cost"), "Synthetic review", review = true)
            assertEquals(before.position.current, f.state().position.current)
            assertFailsWith<IllegalArgumentException> {
                f.machine.open("course", "review", "kp-1", setOf("cost"), "Synthetic review", review = false)
            }
            val next = f.machine.next("course", "review")
            assertNull(next.plan)
            assertEquals(3, next.cards.size)
            assertTrue(next.cards.all { it.payload.flag("review") && it.payload.str("purpose") == "RECALL" })
            // Retrying/reopening the screen reuses the pending group, rather than consuming another.
            f.machine.open("course", "review", "kp-1", setOf("cost"), "Synthetic review", review = true)
            assertEquals(next.cards, f.machine.next("course", "review").cards)
            f.machine.submit("course", "review", next.cards.first().payload.str("groupId"), f.answers(next.cards), "desktop", "review-submit")
            val after = f.state()
            assertEquals("PASS", after.review.getValue("kp-1").lastResult)
            assertNotNull(after.review.getValue("kp-1").lastReviewDay)
            assertTrue(after.review.getValue("kp-1").dueDay > due)
            assertEquals(before.position.current, after.position.current)
            assertTrue(f.events().none { it.type == "explanation_delivered" })
            // The ordinary thread still retains its teach-before-practice behavior.
            assertNotNull(f.machine.next("course", "learning").plan)
            Unit
        }
    }

    @Test fun poolRefillRecoversReviewPurposeFromPersistedFacts() = runBlocking {
        TutorFixture().use { f ->
            f.machine.open("course", "review", "kp-1", setOf("cost"), "Synthetic review", review = true)
            assertTrue(f.machine.next("course", "review").waitingForPool)
            f.seed()
            val restarted = TutorMachine(f.actor)
            val resumed = assertNotNull(restarted.resumePool("course", "review"))
            assertEquals(3, resumed.cards.size)
            assertTrue(resumed.cards.all { it.payload.flag("review") && it.payload.str("purpose") == "RECALL" })
            assertNull(restarted.resumePool("course", "review"))
        }
    }
}
