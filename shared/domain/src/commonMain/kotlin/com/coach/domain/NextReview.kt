package com.coach.domain

import kotlin.math.floor
import kotlin.math.round

/** The ledger's newly-passed initialization. Local books do not infer solid mastery. */
fun initialReview(day: Long, solid: Boolean = false): Review {
    val n = if (solid) 3 else 1
    return Review(day + n, n)
}

/** The ledger's calculation only; its owner retains journal/mastery updates and daily guard. */
fun nextReview(old: Review, day: Long, result: String): Review {
    if (old.lastReviewDay == day) return old
    val interval = when(result) { "PASS" -> floor(old.intervalDays * old.ease).toInt().coerceAtLeast(1); "NEEDS_WORK" -> (old.intervalDays / 2).coerceAtLeast(1); else -> 1 }
    val ease = round((when(result) { "PASS" -> (old.ease + .1).coerceAtMost(3.0); "NEEDS_WORK" -> (old.ease - .15).coerceAtLeast(1.3); else -> (old.ease - .2).coerceAtLeast(1.3) }) * 1000) / 1000
    return old.copy(dueDay = day + interval, intervalDays = interval, ease = ease,
        lapses = old.lapses + if (result == "FAIL") 1 else 0, lastResult = result, lastReviewDay = day, snoozedUntilDay = null)
}
