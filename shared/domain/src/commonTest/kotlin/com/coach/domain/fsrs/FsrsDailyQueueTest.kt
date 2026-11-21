package com.coach.domain.fsrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FsrsDailyQueueTest {
    private val queue = FsrsDailyQueue()

    private fun card(
        id: String,
        weight: Double,
        dueDay: Long = 10,
        stability: Double = 10.0,
        lastReviewDay: Long = 0,
        material: String = "book-1",
    ) = FsrsQueueCard(
        cardId = id,
        materialId = material,
        kpId = "kp-$id",
        syllabusWeight = weight,
        state = FsrsState(dueDay = dueDay, stability = stability, difficulty = 5.0, lastReviewDay = lastReviewDay),
    )

    @Test
    fun twentyPerDayAndThirtyInTheLastThreeWeeks() {
        assertEquals(20, queue.capFor(day = 0, examDay = null))
        assertEquals(20, queue.capFor(day = 0, examDay = 22))
        assertEquals(30, queue.capFor(day = 0, examDay = 21))
        assertEquals(30, queue.capFor(day = 0, examDay = 7))
        assertEquals(30, queue.capFor(day = 0, examDay = 0))
        assertEquals(20, queue.capFor(day = 0, examDay = -1))
    }

    @Test
    fun overflowIsDeferredAndCounted() {
        val cards = (1..25).map { card("c$it", weight = 0.5) }
        val plan = queue.plan(cards, day = 10)
        assertEquals(20, plan.priorityCount)
        assertEquals(5, plan.deferredCount)
        assertEquals("今日优先 20 题 · 另有 5 题顺延", plan.summary())
        assertEquals(cards.map { it.cardId }.toSet(), (plan.priority + plan.deferred).map { it.card.cardId }.toSet())
    }

    @Test
    fun orderIsWeightTimesForgetting() {
        val cards = listOf(
            card("low-weight", weight = 0.2),
            card("high-weight", weight = 1.0),
            card("forgotten", weight = 0.6, stability = 2.0),
            card("fresh", weight = 0.6, stability = 400.0),
        )
        val plan = queue.plan(cards, day = 10)
        val urgencies = plan.priority.map { it.urgency }
        assertEquals(urgencies.sortedDescending(), urgencies)
        assertEquals("forgotten", plan.priority.first().card.cardId)
        assertEquals("fresh", plan.priority.last().card.cardId)
        for (ranked in plan.priority) {
            val expected = ranked.card.syllabusWeight * (1.0 - ranked.retrievability)
            assertTrue(kotlin.math.abs(expected - ranked.urgency) < 1e-12)
        }
    }

    @Test
    fun newCardsCompeteOnWeightAlone() {
        val plan = queue.plan(
            listOf(
                card("light", weight = 0.1, dueDay = 5).copy(state = FsrsState(dueDay = 5)),
                card("heavy", weight = 0.9, dueDay = 5).copy(state = FsrsState(dueDay = 5)),
            ),
            day = 5,
        )
        assertEquals(listOf("heavy", "light"), plan.priority.map { it.card.cardId })
        assertEquals(0.0, plan.priority.first().retrievability)
        assertEquals(0.9, plan.priority.first().urgency)
    }

    @Test
    fun aCardDueTodayWithMoreAtStakeOutranksOneThatWaitedADayLonger() {
        // Two days versus zero days of waiting: the fresher card still wins because more is at stake.
        val waited = card("low", weight = 0.05, dueDay = 8, stability = 1_000.0)
        val fresh = card("high", weight = 1.0, dueDay = 10, stability = 2.0)
        val plan = queue.plan(listOf(waited, fresh), day = 10)
        assertEquals(2, plan.priority.single { it.card.cardId == "low" }.waitingDays)
        assertEquals(0, plan.priority.single { it.card.cardId == "high" }.waitingDays)
        assertEquals(listOf("high", "low"), plan.priority.map { it.card.cardId })
        assertTrue(plan.priority.first().urgency > plan.priority.last().urgency)
    }

    @Test
    fun waitingDaysNeverOutrankExamValueEvenAfterAWeek() {
        // The red-team repro: a low-value card that waited a week must still lose to the card
        // whose weight times forgetting says today matters more.
        val old = card("old", weight = 0.05, dueDay = 3, stability = 5_000.0)
        val heavy = card("heavy", weight = 1.0, dueDay = 10, stability = 3.0)
        val plan = queue.plan(listOf(old, heavy), day = 10)
        assertEquals(7, plan.priority.single { it.card.cardId == "old" }.waitingDays)
        assertEquals(listOf("heavy", "old"), plan.priority.map { it.card.cardId })
    }

    @Test
    fun cardsFromSeveralMaterialsShareOneQueue() {
        val plan = queue.plan(
            listOf(
                card("textbook", weight = 0.4, material = "book/ch08"),
                card("handout", weight = 0.4, material = "lecture/week-05"),
                card("past-paper", weight = 0.4, material = "breakdown/2023"),
            ),
            day = 10,
        )
        assertEquals(3, plan.priorityCount)
        assertEquals(
            setOf("book/ch08", "lecture/week-05", "breakdown/2023"),
            plan.priority.map { it.card.materialId }.toSet(),
        )
    }

    @Test
    fun futureCardsAndEmptyDays() {
        val plan = queue.plan(listOf(card("later", weight = 1.0, dueDay = 11)), day = 10)
        assertEquals(0, plan.priorityCount)
        assertEquals(0, plan.deferredCount)
        assertEquals("今日优先 0 题 · 另有 0 题顺延", plan.summary())
        assertNull(queue.meanRetrievability(plan.priority))
        assertNull(plan.longestDeferralDays())
    }

    @Test
    fun theQueueRescuesTheCardsAlreadyFallingAway() {
        val cards = (1..25).map { card("c$it", weight = 0.5, stability = 10.0 + it) }
        val plan = queue.plan(cards, day = 20)
        val shown = assertNotNull(queue.meanRetrievability(plan.priority))
        val slipped = assertNotNull(queue.meanRetrievability(plan.deferred))
        // Urgency prefers low R(t), so the shown batch is the one at risk, not the one that is safe.
        assertTrue(shown < slipped, "shown $shown should be more at risk than deferred $slipped")
        assertEquals(10, plan.longestDeferralDays())
    }
}
