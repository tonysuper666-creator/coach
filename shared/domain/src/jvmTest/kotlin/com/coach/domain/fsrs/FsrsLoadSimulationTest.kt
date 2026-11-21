package com.coach.domain.fsrs

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Load model for P1-7 of the V2 red-team review: 19 chapters, 11 weeks of study (77 days), a
 * 21-day pre-exam cram, and the 20/30 daily cap. It answers the three questions the product could
 * not answer before: how many cards fall due per day, how much backlog the cap defers, and whether
 * retention survives the deferral.
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
    private val secondsPerCard = 25

    /** 6, 7, 8, 6, 7, 8 … — the issue's stated range, 132 cards across 19 chapters. */
    private val leanCards = (0 until chapters).map { 6 + it % 3 }

    /** 12 cards in every chapter — 228 cards, the red team's worst case. */
    private val denseCards = List(chapters) { 12 }

    @Test
    fun theLeanBookNeverReachesTheCap() {
        val result = run(cardsPerChapter = leanCards, seed = 20_261_121)
        assertEquals(132, result.totalCards)
        assertTrue(result.peakDue <= 20, "peak due was ${result.peakDue}, the 20 cap is meant to stay slack here")
        assertEquals(0, result.peakDeferred, "no card should be deferred at this volume")
        assertTrue(result.maxMinutes <= 15.0, "longest session was ${result.maxMinutes} minutes")
        assertTrue(result.meanRetention >= 0.85, "mean retention was ${result.meanRetention}")
        emit(result, "lean")
    }

    @Test
    fun theDenseBookDefersButDoesNotAvalanche() {
        val result = run(cardsPerChapter = denseCards, seed = 20_261_121)
        assertEquals(228, result.totalCards)
        assertTrue(result.peakDue > 20, "peak due was ${result.peakDue}; this volume is supposed to hit the cap")
        assertTrue(result.peakDeferred > 0, "the cap never deferred anything, so this scenario proves nothing")
        assertTrue(
            result.peakDeferred <= 2 * 30,
            "backlog peaked at ${result.peakDeferred} cards, more than two cram days",
        )
        assertEquals(0, result.days.last().deferred, "backlog had not drained on exam day")
        assertTrue(result.maxMinutes <= 15.0, "longest session was ${result.maxMinutes} minutes")
        assertTrue(result.meanRetention >= 0.85, "mean retention was ${result.meanRetention}")
        emit(result, "dense")
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
    )

    private data class Result(val days: List<DayRow>, val totalCards: Int, val reviewed: Int) {
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

        for (day in 0L..examDay) {
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
                val next = Fsrs5.review(card.state, rating, day)
                val slot = live.indexOfFirst { it.cardId == card.cardId }
                live[slot] = card.copy(state = next)
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
            )
        }
        return Result(days, live.size, reviewed)
    }

    private fun emit(result: Result, name: String) {
        val file = File("build/reports/fsrs-load-sim-$name.csv")
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("day,due_before_cap,shown,deferred,new_shown,minutes,mean_retrievability")
                for (row in result.days) {
                    appendLine(
                        "${row.day},${row.dueBeforeCap},${row.shown},${row.deferred},${row.newShown}," +
                            "%.1f,%s".format(
                                row.minutes,
                                row.meanRetrievability?.let { "%.3f".format(it) } ?: "",
                            )
                    )
                }
            }
        )
        println("[$name] cards=${result.totalCards} reviewed=${result.reviewed} csv=${file.absolutePath}")
        println(
            "[$name] peak_due=${result.peakDue} peak_deferred=${result.peakDeferred} " +
                "max_minutes=${"%.1f".format(result.maxMinutes)} mean_retention=${"%.3f".format(result.meanRetention)}"
        )
        println("[$name] week,day_range,due_avg,shown_avg,deferred_max,min_per_day,retention")
        for (week in 0..13) {
            val slice = result.days.filter { it.day in (week * 7L)..(week * 7L + 6L) }
            println(
                "[$name] %2d,%3d-%3d,%5.1f,%5.1f,%3d,%5.1f,%s".format(
                    week + 1,
                    slice.first().day,
                    slice.last().day,
                    slice.map { it.dueBeforeCap }.average(),
                    slice.map { it.shown }.average(),
                    slice.maxOf { it.deferred },
                    slice.map { it.minutes }.average(),
                    slice.mapNotNull { it.meanRetrievability }.takeIf { it.isNotEmpty() }
                        ?.average()?.let { "%.3f".format(it) } ?: "-",
                )
            )
        }
    }
}
