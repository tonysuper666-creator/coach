package com.coach.domain

import kotlinx.serialization.json.*
import kotlin.math.floor
import kotlin.math.ln

/** Policy targets, not measured learner accuracy. The legacy ease interval is calibrated at 90%.
 * Raising the target to 95% halves the tolerated forgetting risk for a checked three-star topic.
 * A reference exponential curve converts that target into an earlier actual due date; this is
 * not a fitted memory model. Existing one-day granularity and mastery evidence stay unchanged. */
object ReviewRetentionPolicy {
    const val BASELINE = 0.90
    const val THREE_STAR = 0.95

    fun target(stars: Int?): Double = if (stars == 3) THREE_STAR else BASELINE

    fun interval(referenceDays: Int, target: Double): Int {
        require(target.isFinite() && target >= BASELINE && target < 1.0)
        return floor(referenceDays.coerceAtLeast(1) * ln(target) / ln(BASELINE))
            .toInt().coerceIn(1, referenceDays.coerceAtLeast(1))
    }
}

/** Optional course_configured input, distinct from exam point weights. No text/material is needed.
 * Unchecked entries are valid configuration but cannot raise scheduling priority or retention. */
fun validReviewPriorities(value: JsonElement?): Boolean {
    if (value == null) return true
    val priorities = value as? JsonObject ?: return false
    return priorities.all { (kp, raw) ->
        val p = raw as? JsonObject
        val stars = p?.get("stars") as? JsonPrimitive
        val reviewed = p?.get("reviewed") as? JsonPrimitive
        kp.isNotBlank() && stars != null && !stars.isString && stars.intOrNull?.let { it in 1..3 } == true &&
            reviewed != null && !reviewed.isString && reviewed.booleanOrNull != null
    }
}

/** The latest explicit priority map replaces the previous map; timezone-only updates retain it.
 * Call with the already scope-filtered ledger, as project() does for learner/agent isolation. */
internal fun courseReviewStars(events: List<Event>): Map<String, Int> {
    val config = events.lastOrNull { it.type == "course_configured" && it.schema == 1 && "reviewPriorities" in it.payload }
        ?.payload?.get("reviewPriorities") ?: return emptyMap()
    if (!validReviewPriorities(config)) return emptyMap()
    return (config as JsonObject).mapNotNull { (kp, raw) ->
        val entry = raw.jsonObject
        if (entry.flag("reviewed")) kp to entry.num("stars").toInt() else null
    }.toMap()
}
