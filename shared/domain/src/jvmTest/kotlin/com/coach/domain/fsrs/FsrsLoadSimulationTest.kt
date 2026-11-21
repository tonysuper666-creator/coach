package com.coach.domain.fsrs

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Load model for P1-7 of the V2 red-team review: 19 chapters, 11 weeks of study (77 days), a
 * 21-day pre-exam cram, and the 20/30 daily cap. It answers the questions the product could not
 * answer before: how many cards fall due per day, how much backlog the cap defers, whether
 * retention survives the deferral, and what the pre-exam compression really costs.
 *
 * The cram is simulated exactly as the V2 plan specifies it: from day 77 on, every review uses
 * [FsrsConfig.forExamWindow] — target retention 0.94 instead of 0.90, and no interval longer than
 * a quarter of the days left. The first version of this test forgot that and under-reported the
 * backlog by an order of magnitude, which is why the numbers below are split into the two phases.
 *
 * Two volumes are simulated, both inside the proposal's own 6-12 cards per chapter range:
 *  - "lean": 6-8 cards per chapter, 132 cards — the volume named in this issue.
 *  - "dense": 8-12 cards per chapter, 228 cards — the upper end the red team feared.
 *
 * The run is deterministic (fixed seed, fixed chapter dates), so every number below is
 * reproducible with `./gradlew :shared:domain:jvmTest --tests '*FsrsLoadSimulationTest*'`.
 * The per-day curves are written to `shared/domain/build/reports/fsrs-load-sim-<name>.csv`.
 */
class FsrsLoadSimulationTest {
    private val chapters = 19
    private val examDay = 98L
    private val studyDays = 77L
    private val secondsPerCard = 25

    /** 6, 7, 8, 6, 7, 8 … — the issue's stated range, 132 cards across 19 chapters. */
    private val leanCards = (0 until chapters).map { 6 + it % 3 }

    /** 12 cards in every chapter — 228 cards, the red team's worst case. */
    private val denseCards = List(chapters) { 12 }

    @Test
    fun theLeanBookStaysUnderTheCapWhileStudying() {
        val result = run(cardsPerChapter = leanCards, seed = 20_261_121)
        emit(result, "lean")
        assertEquals(132, result.totalCards)
        assertEquals(0, result.studyDays.maxOf { it.deferred }, "studying should need no deferral at this volume")
        assertTrue(
            result.studyDays.maxOf { it.dueBeforeCap } <= 20,
            "studying peaked at ${result.studyDays.maxOf { it.dueBeforeCap }} due cards, the 20 cap is meant to be slack",
        )
        assertTrue(result.maxMinutes <= 12.5, "longest session was ${result.maxMinutes} minutes")
        assertEquals(1.0, result.windowCoverage, "the cram must touch every card at least once")
        assertTrue(result.examRetention >= 0.94, "exam morning retention was ${result.examRetention}")
        assertTrue(result.urgencyOrderHolds, "a less at-risk card was shown ahead of a more at-risk one")
        assertTrue(
            result.cramShownRetention < result.cramDeferredRetention,
            "the cram showed the safer batch: shown R ${result.cramShownRetention} vs deferred ${result.cramDeferredRetention}",
        )
    }

    @Test
    fun theDenseBookDefersButKeepsTimeAndCoverageBounded() {
        val result = run(cardsPerChapter = denseCards, seed = 20_261_121)
        emit(result, "dense")
        assertEquals(228, result.totalCards)
        assertTrue(
            result.studyDays.maxOf { it.dueBeforeCap } > 20,
            "this volume is supposed to hit the 20 cap while studying, peaked at " +
                "${result.studyDays.maxOf { it.dueBeforeCap }}",
        )
        assertTrue(result.peakDeferred > 0, "the cap never deferred anything, so this scenario proves nothing")
        assertTrue(result.maxMinutes <= 12.5, "longest session was ${result.maxMinutes} minutes")
        assertEquals(1.0, result.windowCoverage, "the cram must touch every card at least once")
        assertTrue(result.examRetention >= 0.94, "exam morning retention was ${result.examRetention}")
        assertTrue(result.weightedExamRetention >= 0.94, "weighted retention was ${result.weightedExamRetention}")
        assertTrue(result.urgencyOrderHolds, "a less at-risk card was shown ahead of a more at-risk one")
        assertTrue(
            result.cramShownRetention < result.cramDeferredRetention,
            "the cram showed the safer batch: shown R ${result.cramShownRetention} vs deferred ${result.cramDeferredRetention}",
        )
    }

    private data class DayRow(
        val day: Long,
        val dueBeforeCap: Int,
        val shown: Int,
        val deferred: Int,
        val newShown: Int,
        val minutes: Double,
        /** Mean R(t) of the review cards shown; new cards are first exposure, not forgetting. */
        val meanRetrievability: Double?,
        /** True while the pre-exam compression is in force. */
        val crammed: Boolean,
        /** Mean R(t) of the cards left for tomorrow; they should be the safer ones. */
        val meanDeferredRetrievability: Double?,
        /** Lowest urgency among the cards shown today. */
        val minShownUrgency: Double?,
        /** Highest urgency among the cards left for tomorrow. */
        val maxDeferredUrgency: Double?,
    )

    private data class CardRow(
        val cardId: String,
        val weight: Double,
        val dueDay: Long,
        val lastShownDay: Long?,
        val cramReviews: Int,
    )

    private data class Result(
        val days: List<DayRow>,
        val cards: List<CardRow>,
        val totalCards: Int,
        val reviewed: Int,
        /** How many reviews each card got inside the 21-day cram window, mean. */
        val meanCramRounds: Double,
        /** Share of all cards the cram touched at least once. */
        val windowCoverage: Double,
        /** Mean chance of recall on exam morning, over every card. */
        val examRetention: Double,
        /** The same, weighted by syllabus weight. */
        val weightedExamRetention: Double,
    ) {
        val studyDays get() = days.filter { !it.crammed }
        val cramDays get() = days.filter { it.crammed }

        /** No day may show a card that is less at risk than one it left for tomorrow. */
        val urgencyOrderHolds: Boolean
            get() = days.all { day ->
                val shown = day.minShownUrgency ?: return@all true
                val deferred = day.maxDeferredUrgency ?: return@all true
                shown >= deferred - 1e-12
            }

        /** Mean R(t) of the review cards shown during the cram. */
        val cramShownRetention: Double
            get() = cramDays.mapNotNull { it.meanRetrievability }.average()

        /** Mean R(t) of the cards the cram left for tomorrow — must be the safer batch. */
        val cramDeferredRetention: Double
            get() = cramDays.mapNotNull { it.meanDeferredRetrievability }.average()

        val peakDue: Int get() = days.maxOf { it.dueBeforeCap }
        val peakDeferred: Int get() = days.maxOf { it.deferred }
        val maxMinutes: Double get() = days.maxOf { it.minutes }
        val meanRetention: Double get() = days.mapNotNull { it.meanRetrievability }.average()
    }

    private fun run(cardsPerChapter: List<Int>, seed: Int): Result {
        val introductionDay = (0 until chapters).associateWith { it * 4L }
        val weights = (0 until chapters).map { 0.5 + 0.5 * ((it * 7) % chapters) / (chapters - 1.0) }
        val random = Random(seed)
        val queue = FsrsDailyQueue()
        val live = mutableListOf<FsrsQueueCard>()
        val days = mutableListOf<DayRow>()
        val reviewsInWindow = mutableMapOf<String, Int>()
        val lastShownDay = mutableMapOf<String, Long>()
        var reviewed = 0

        for (chapter in 0 until chapters) {
            repeat(cardsPerChapter[chapter]) { index ->
                live += FsrsQueueCard(
                    cardId = "c%02d-%02d".format(chapter + 1, index + 1),
                    materialId = "book/ch%02d".format(chapter + 1),
                    kpId = "ch%02d-kp%02d".format(chapter + 1, index % 4 + 1),
                    syllabusWeight = weights[chapter],
                    state = Fsrs5.newCard(dueDay = introductionDay.getValue(chapter)),
                )
            }
        }
        val cramStart = studyDays

        // Reviews run up to the eve (day 97); nothing is scheduled on the exam day itself, so the
        // retention below is what the student walks in with, not a number flattered by a fresh
        // review that morning.
        for (day in 0L until examDay) {
            val daysToExam = examDay - day
            // Outside the window this is the default config; inside it is the plan's cram mode.
            val dayConfig = FsrsConfig().forExamWindow(daysToExam)
            val plan = queue.plan(live, day, examDay)
            val reviewShown = mutableListOf<Double>()
            var newShown = 0
            for (ranked in plan.priority) {
                val card = ranked.card
                val correct = if (card.state.isNew) {
                    newShown++
                    random.nextDouble() < 0.8
                } else {
                    reviewShown += ranked.retrievability
                    random.nextDouble() < ranked.retrievability
                }
                val rating = if (correct) FsrsRating.GOOD else FsrsRating.AGAIN
                val next = Fsrs5.review(card.state, rating, day, dayConfig)
                val slot = live.indexOfFirst { it.cardId == card.cardId }
                live[slot] = card.copy(state = next)
                // Only the last 21 days count as "cram": earlier reviews say nothing about whether
                // the compression touched every card.
                if (day >= cramStart) {
                    reviewsInWindow[card.cardId] = (reviewsInWindow[card.cardId] ?: 0) + 1
                }
                lastShownDay[card.cardId] = day
                reviewed++
            }
            days += DayRow(
                day = day,
                dueBeforeCap = plan.priorityCount + plan.deferredCount,
                shown = plan.priorityCount,
                deferred = plan.deferredCount,
                newShown = newShown,
                minutes = plan.priorityCount * secondsPerCard / 60.0,
                meanRetrievability = reviewShown.takeIf { it.isNotEmpty() }?.average(),
                crammed = day >= cramStart,
                meanDeferredRetrievability = plan.deferred.takeIf { it.isNotEmpty() }
                    ?.map { it.retrievability }?.average(),
                minShownUrgency = plan.priority.minOfOrNull { it.urgency },
                maxDeferredUrgency = plan.deferred.maxOfOrNull { it.urgency },
            )
        }

        val cramRounds = reviewsInWindow.values.sum().toDouble() / live.size
        val touched = cardsTouchedInWindow(reviewsInWindow)
        val retention = live.map { Fsrs5.retrievability(it.state, examDay) }
        val weightSum = live.sumOf { it.syllabusWeight }
        val cardRows = live.map {
            CardRow(
                cardId = it.cardId,
                weight = it.syllabusWeight,
                dueDay = it.state.dueDay,
                lastShownDay = lastShownDay[it.cardId],
                cramReviews = reviewsInWindow[it.cardId] ?: 0,
            )
        }
        return Result(
            days = days,
            cards = cardRows,
            totalCards = live.size,
            reviewed = reviewed,
            meanCramRounds = cramRounds,
            windowCoverage = touched.toDouble() / live.size,
            examRetention = retention.average(),
            weightedExamRetention = live.indices.sumOf { live[it].syllabusWeight * retention[it] } / weightSum,
        )
    }

    private fun cardsTouchedInWindow(reviewsInWindow: Map<String, Int>): Int =
        reviewsInWindow.count { (_, count) -> count > 0 }

    private fun emit(result: Result, name: String) {
        val file = File("build/reports/fsrs-load-sim-$name.csv")
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("day,due_before_cap,shown,deferred,new_shown,minutes,mean_retrievability,crammed")
                for (row in result.days) {
                    appendLine(
                        "${row.day},${row.dueBeforeCap},${row.shown},${row.deferred},${row.newShown}," +
                            "%.1f,%s,%s".format(
                                row.minutes,
                                row.meanRetrievability?.let { "%.3f".format(it) } ?: "",
                                if (row.crammed) 1 else 0,
                            )
                    )
                }
            }
        )
        File("build/reports/fsrs-load-sim-$name-cards.csv").writeText(
            buildString {
                appendLine("card_id,weight,due_day,last_shown_day,cram_reviews")
                for (row in result.cards) {
                    appendLine(
                        "${row.cardId},${"%.3f".format(row.weight)},${row.dueDay}," +
                            "${row.lastShownDay ?: ""},${row.cramReviews}"
                    )
                }
            }
        )
        println("[$name] cards=${result.totalCards} reviewed=${result.reviewed} csv=${file.absolutePath}")
        println(
            "[$name] peak_due=${result.peakDue} peak_deferred=${result.peakDeferred} " +
                "max_minutes=${"%.1f".format(result.maxMinutes)} mean_retention=${"%.3f".format(result.meanRetention)} " +
                "cram_rounds=${"%.2f".format(result.meanCramRounds)} " +
                "window_coverage=${"%.3f".format(result.windowCoverage)} " +
                "exam_retention=${"%.4f".format(result.examRetention)} " +
                "weighted_exam_retention=${"%.4f".format(result.weightedExamRetention)} " +
                "cram_shown_R=${"%.4f".format(result.cramShownRetention)} " +
                "cram_deferred_R=${"%.4f".format(result.cramDeferredRetention)}"
        )
        File("build/reports/fsrs-load-sim-$name-summary.txt").writeText(
            buildString {
                appendLine("cards=${result.totalCards}")
                appendLine("reviewed=${result.reviewed}")
                appendLine("peak_due=${result.peakDue}")
                appendLine("peak_deferred=${result.peakDeferred}")
                appendLine("max_minutes=${"%.3f".format(result.maxMinutes)}")
                appendLine("mean_retention=${"%.4f".format(result.meanRetention)}")
                appendLine("cram_rounds=${"%.3f".format(result.meanCramRounds)}")
                appendLine("window_coverage=${"%.4f".format(result.windowCoverage)}")
                appendLine("exam_retention=${"%.4f".format(result.examRetention)}")
                appendLine("weighted_exam_retention=${"%.4f".format(result.weightedExamRetention)}")
                appendLine("cram_shown_R=${"%.4f".format(result.cramShownRetention)}")
                appendLine("cram_deferred_R=${"%.4f".format(result.cramDeferredRetention)}")
            }
        )
        println("[$name] week,day_range,due_avg,shown_avg,deferred_max,min_per_day,retention,cram")
        for (week in 0..14) {
            val slice = result.days.filter { it.day in (week * 7L)..(week * 7L + 6L) }
            if (slice.isEmpty()) continue
            println(
                "[$name] %2d,%3d-%3d,%5.1f,%5.1f,%3d,%5.1f,%s,%s".format(
                    week + 1,
                    slice.first().day,
                    slice.last().day,
                    slice.map { it.dueBeforeCap }.average(),
                    slice.map { it.shown }.average(),
                    slice.maxOf { it.deferred },
                    slice.map { it.minutes }.average(),
                    slice.mapNotNull { it.meanRetrievability }.takeIf { it.isNotEmpty() }
                        ?.average()?.let { "%.3f".format(it) } ?: "-",
                    if (slice.any { it.crammed }) "yes" else "",
                )
            )
        }
    }
}
