package com.coach.ui

import com.coach.client.*
import com.coach.domain.*
import kotlinx.serialization.json.*

/** One decision for Today, the course browser and the exam panel. Local session state is not a ledger write. */
internal data class LearningTarget(val kp: KpRow, val week: Int?, val resume: Boolean, val legacy: Boolean = false, val reading: Boolean = false)

/** Resuming a question must preserve its filter, choice and feedback; a fresh quiz entry may align them. */
internal val LearningTarget.entry: String
    get() = if (resume) (if (reading) "resume" else "resume-quiz") else (if (reading) "notes" else "quiz")

internal fun CoachController.learningTarget(view: CourseView, browseWeek: Int? = null): LearningTarget? {
    val points = browseWeek?.let(view::weekKps) ?: view.kps
    val unfinished = points.filter { it.status !in setOf(KpStatus.PASSED, KpStatus.SOLID) }
    // Pending tutor questions from earlier versions must keep their original thread and answers.
    val pending = unfinished.firstOrNull { it.id == view.projection.position.continueTarget && view.pendingGroup(view.tutorThreadId(it.id)) != null }
        ?: unfinished.lastOrNull { view.pendingGroup(view.tutorThreadId(it.id)) != null }
    if (pending != null) return LearningTarget(pending, browseWeek ?: view.weeks.firstOrNull { n -> view.weekKps(n).any { it.id == pending.id } }, true, legacy = true)
    val session = sync.mainlineState(courseId, "learning")
    val tutorKp = session.str("kpId").takeIf { session.str("kind") == "tutor" }?.let(view::kp)?.takeIf { it in unfinished && view.isResume(it) }
    if (tutorKp != null) return LearningTarget(tutorKp, browseWeek ?: view.weeks.firstOrNull { n -> view.weekKps(n).any { it.id == tutorKp.id } }, true, legacy = true)
    val readingKp = session.str("kpId").takeIf { session.str("kind") == "reading" }?.let(view::kp)?.takeIf { it in unfinished }
    if (readingKp != null) return LearningTarget(readingKp, browseWeek ?: view.weeks.firstOrNull { n -> view.weekKps(n).any { it.id == readingKp.id } }, true, legacy = true, reading = true)
    val previous = browseWeek ?: (if (session.str("kind") == "mainline") session else sync.mainlineState(courseId)).num("week").toInt().takeIf { it in view.weeks }
    if (previous != null) {
        val saved = sync.mainlineState(courseId, "week:$previous")
        val body = saved["body"] as? JsonObject
        val items = (body?.get("items") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val active = saved.str("item").isNotBlank() && items.any { it.str("status") != "mastered" }
        // Persist the visible view independently. Today prefers an unfinished quiz even after optional reading,
        // while reopening the reader itself restores the notes the student was looking at.
        val quizStarted = saved.flag("quizStarted") || saved.str("mode") == "quiz" ||
            saved["choice"] is JsonPrimitive && saved["choice"] != JsonNull ||
            saved["result"] is JsonObject || saved["pending"] is JsonObject
        val reading = saved.str("mode") == "notes" && !(quizStarted && active)
        val readingActive = reading && (body?.get("notes") as? JsonArray).orEmpty().isNotEmpty()
        val section = (body?.get("notes") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.str("section_id") == saved.str("section") }
        val question = items.firstOrNull { it.str("id") == saved.str("item") }
        val facts = if (reading) section?.strings("fact_ids").orEmpty() else question?.strings("factIds").orEmpty()
        val rows = view.weekKps(previous)
        val linkedId = if (reading) section?.str("section_id") else question?.str("kpId")
        val kp = rows.firstOrNull { it.id == linkedId || it.conceptIds.any { f -> f in facts } }
            ?: rows.firstOrNull { it in unfinished } ?: rows.firstOrNull()
        if (kp != null && (active || readingActive)) return LearningTarget(kp, previous, true, reading = reading)
    }
    val kp = if (browseWeek == null) view.continueTarget else
        unfinished.firstOrNull { view.isResume(it) } ?: unfinished.firstOrNull { it.status == KpStatus.NEEDS_REPAIR } ?: unfinished.firstOrNull()
    kp ?: return null
    val week = browseWeek ?: view.weeks.firstOrNull { n -> view.weekKps(n).any { it.id == kp.id } }
    return LearningTarget(kp, week, false, legacy = week == null)
}

/** A course can answer its directory before the ledger outline has arrived. Keep that availability fallback
 * here beside the common decision, and never replace an existing outline's recommendation with server order. */
internal fun CoachController.mainlineContinuationWeek(view: CourseView, overview: JsonObject?): Int? {
    learningTarget(view)?.let { return it.week }
    if (view.kps.isNotEmpty()) return null
    val weeks = (overview?.get("weeks") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .filter { it.num("week") > 0 }
    val session = sync.mainlineState(courseId, "learning")
    val previous = (if (session.str("kind") == "mainline") session else sync.mainlineState(courseId)).num("week").toInt()
    val target = weeks.firstOrNull { it.num("week").toInt() == previous }
        ?: weeks.firstOrNull { val coverage = it["coverage"] as? JsonObject
            coverage != null && coverage.num("factsMastered") < coverage.num("facts") }
        ?: weeks.firstOrNull()
    return target?.num("week")?.toInt()
}
