package com.coach.domain.fsrs

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * FSRS-5 scheduler (Ye, Su & Cao, KDD '22; FSRS-5 formulas), day granularity.
 *
 * This is the pure memory kernel: it answers "when is this card due next" for one card.
 * It knows nothing about books, courses, UI or storage, so all three learning routes
 * (exam course, single book, book list) share it.
 *
 * The default weights are the official FSRS-5 defaults, and every formula below is a
 * line-by-line port of py-fsrs 5.1.3's `Scheduler` with empty learning/relearning steps
 * and fuzzing off. `Fsrs5ReferenceParityTest` replays a review log produced by that
 * reference and fails on any difference, so the parity is a test, not a claim.
 *
 * Product choices layered on top of the kernel (not here): the daily 20/30 cap and the
 * deferral order live in [FsrsDailyQueue]; the 1/3/7 streak history import lives in
 * [LegacyStreakMigration].
 */
enum class FsrsRating(val value: Int) {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4);

    companion object {
        fun of(value: Int): FsrsRating = entries.first { it.value == value }
    }
}

/**
 * @param weights the 19 FSRS-5 weights; the default is the official parameter set.
 * @param desiredRetention target probability of recall at the next due date. 0.9 is the
 *   default; the pre-exam window may raise it to 0.94 to compress intervals.
 * @param maximumIntervalDays hard ceiling, same as the reference default.
 */
data class FsrsConfig(
    val weights: List<Double> = DEFAULT_FSRS5_WEIGHTS,
    val desiredRetention: Double = 0.9,
    val maximumIntervalDays: Int = 36_500,
) {
    init {
        require(weights.size == 19) { "FSRS-5 has 19 weights, got ${weights.size}" }
        require(desiredRetention > 0.0 && desiredRetention <= 1.0) { "desiredRetention must be in (0, 1]" }
        require(maximumIntervalDays >= 1)
    }

    /**
     * Pre-exam compression from the V2 plan (C-learning proposal §2, sample chapter §05):
     * inside the last [EXAM_WINDOW_DAYS] days the target retention rises to
     * [EXAM_DESIRED_RETENTION] and no interval may outlive a quarter of the days left, so every
     * card is seen roughly four times before the exam and at least once on the final day.
     *
     * Both values are product assumptions picked from Cepeda et al. (2006), not fitted
     * parameters; callers can pass their own config and this function only tightens it.
     */
    fun forExamWindow(daysToExam: Long): FsrsConfig {
        require(daysToExam >= 0) { "daysToExam must not be negative" }
        if (daysToExam > EXAM_WINDOW_DAYS) return this
        return copy(
            desiredRetention = max(desiredRetention, EXAM_DESIRED_RETENTION),
            maximumIntervalDays = min(maximumIntervalDays, examIntervalCeiling(daysToExam)),
        )
    }

    companion object {
        /** Days before the exam when compression starts (V2 plan: the last three weeks). */
        const val EXAM_WINDOW_DAYS: Long = 21

        /** Target retention inside the exam window (V2 plan: 0.94 against the 0.90 default). */
        const val EXAM_DESIRED_RETENTION: Double = 0.94

        /** Interval ceiling inside the window: a quarter of the days left, never below one day. */
        fun examIntervalCeiling(daysToExam: Long): Int {
            require(daysToExam >= 0) { "daysToExam must not be negative" }
            return max(1, (daysToExam / 4).toInt())
        }
    }
}

/**
 * One card's memory state. `stability == null` means the card has never been answered.
 *
 * [dueDay] is a logical day index (the same clock the rest of Coach uses), not a timestamp.
 * [lastReviewDay] is the day of the last answer; elapsed days for the next review come from
 * the difference, so a review later the same day takes the short-term branch.
 */
data class FsrsState(
    val dueDay: Long,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val lastReviewDay: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
) {
    val isNew: Boolean get() = stability == null
}

object Fsrs5 {
    /** FSRS fixes the shape of the forgetting curve; desired retention only scales intervals. */
    const val DECAY = -0.5
    val FACTOR: Double = 0.9.pow(1.0 / DECAY) - 1.0

    fun newCard(dueDay: Long): FsrsState = FsrsState(dueDay = dueDay)

    /** Probability of recall on [day]; 0 for a card that was never reviewed. */
    fun retrievability(card: FsrsState, day: Long): Double {
        val stability = card.stability ?: return 0.0
        val last = card.lastReviewDay ?: return 0.0
        val elapsed = max(0L, day - last)
        return (1.0 + FACTOR * elapsed / stability).pow(DECAY)
    }

    /** Interval in whole days for a stability; rounds half to even exactly like CPython's `round`. */
    fun intervalDays(stability: Double, config: FsrsConfig = FsrsConfig()): Int {
        val raw = stability / FACTOR * (config.desiredRetention.pow(1.0 / DECAY) - 1.0)
        return min(config.maximumIntervalDays, max(1, roundHalfEven(raw)))
    }

    /** Applies one answer. Pure: same state, rating, day and config always give the same result. */
    fun review(
        card: FsrsState,
        rating: FsrsRating,
        day: Long,
        config: FsrsConfig = FsrsConfig(),
    ): FsrsState {
        require(day >= 0) { "day must be a non-negative logical day" }
        val stability = card.stability
        val difficulty = card.difficulty
        val nextStability: Double
        val nextDifficulty: Double
        if (stability == null || difficulty == null) {
            nextStability = initialStability(rating, config)
            nextDifficulty = initialDifficulty(rating, config)
        } else if (day - (card.lastReviewDay ?: day) < 1) {
            nextStability = shortTermStability(stability, rating, config)
            nextDifficulty = nextDifficulty(difficulty, rating, config)
        } else {
            val r = retrievability(card, day)
            nextStability = if (rating == FsrsRating.AGAIN) {
                forgetStability(difficulty, stability, r, config)
            } else {
                recallStability(difficulty, stability, r, rating, config)
            }
            nextDifficulty = nextDifficulty(difficulty, rating, config)
        }
        val interval = intervalDays(nextStability, config)
        return card.copy(
            stability = nextStability,
            difficulty = nextDifficulty,
            lastReviewDay = day,
            dueDay = day + interval,
            reps = card.reps + 1,
            lapses = card.lapses + if (rating == FsrsRating.AGAIN && !card.isNew) 1 else 0,
        )
    }

    fun initialStability(rating: FsrsRating, config: FsrsConfig = FsrsConfig()): Double =
        max(config.weights[rating.value - 1], 0.1)

    fun initialDifficulty(rating: FsrsRating, config: FsrsConfig = FsrsConfig()): Double {
        val w = config.weights
        return clampDifficulty(w[4] - exp(w[5] * (rating.value - 1)) + 1.0)
    }

    fun nextDifficulty(difficulty: Double, rating: FsrsRating, config: FsrsConfig = FsrsConfig()): Double {
        val w = config.weights
        val delta = -(w[6] * (rating.value - 3))
        val damped = difficulty + (10.0 - difficulty) * delta / 9.0
        val meanReversion = w[7] * initialDifficulty(FsrsRating.EASY, config) + (1.0 - w[7]) * damped
        return clampDifficulty(meanReversion)
    }

    fun shortTermStability(stability: Double, rating: FsrsRating, config: FsrsConfig = FsrsConfig()): Double {
        val w = config.weights
        return stability * exp(w[17] * (rating.value - 3 + w[18]))
    }

    fun recallStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: FsrsRating,
        config: FsrsConfig = FsrsConfig(),
    ): Double {
        val w = config.weights
        val hardPenalty = if (rating == FsrsRating.HARD) w[15] else 1.0
        val easyBonus = if (rating == FsrsRating.EASY) w[16] else 1.0
        return stability * (
            1.0 + exp(w[8]) * (11.0 - difficulty) * stability.pow(-w[9]) *
                (exp((1.0 - retrievability) * w[10]) - 1.0) * hardPenalty * easyBonus
            )
    }

    fun forgetStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        config: FsrsConfig = FsrsConfig(),
    ): Double {
        val w = config.weights
        val longTerm = w[11] * difficulty.pow(-w[12]) * ((stability + 1.0).pow(w[13]) - 1.0) *
            exp((1.0 - retrievability) * w[14])
        val shortTerm = stability / exp(w[17] * w[18])
        return min(longTerm, shortTerm)
    }

    private fun clampDifficulty(d: Double): Double = min(10.0, max(1.0, d))

    /** CPython `round(float)` semantics: ties go to the even neighbour. */
    internal fun roundHalfEven(x: Double): Int {
        val lower = floor(x)
        val diff = x - lower
        val result = when {
            diff > 0.5 -> lower + 1.0
            diff < 0.5 -> lower
            else -> if (lower % 2.0 == 0.0) lower else lower + 1.0
        }
        return result.roundToLong().toInt()
    }
}

/** Official FSRS-5 default parameters (19 weights), identical to py-fsrs 5.1.3 / fsrs-rs. */
val DEFAULT_FSRS5_WEIGHTS: List<Double> = listOf(
    0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575,
    0.1192, 1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
)
