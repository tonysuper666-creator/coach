package com.coach.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Source labels (including secondary anchors) are the course calendar; no week is guessed. */
fun titleWeeks(title: String): Set<Int> = Regex("【W([1-9][0-9]*)(?=[·・\\s】])").findAll(title).map { it.groupValues[1].toInt() }.toSet()

data class WeekPoint(val id: String, val title: String, val weeks: Set<Int>)

/** Same outline order and week interpretation for server commands and every client. */
fun weekPoints(events: List<Event>): List<WeekPoint> {
    val points = linkedMapOf<String, WeekPoint>()
    for (e in events.filter { it.type == "outline_revised" }) {
        for (raw in (e.payload["knowledgePoints"] as? JsonArray).orEmpty()) {
            val p = raw as? JsonObject ?: continue
            val id = p.str("id"); if (id.isBlank()) continue
            if (p.flag("retired")) { points.remove(id); continue }
            val title = p.str("title").ifBlank { p.str("name") }
            val week = (p["week"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
            points[id] = WeekPoint(id, title, week?.let { setOf(it) } ?: titleWeeks(title))
        }
        for (raw in (e.payload["ops"] as? JsonArray).orEmpty()) {
            val p = raw as? JsonObject ?: continue
            val id = p.str("kpId").ifBlank { p.str("id") }; if (id.isBlank()) continue
            if (p.str("op") == "retire") { points.remove(id); continue }
            val title = p.str("title").ifBlank { p.str("name") }.ifBlank { points[id]?.title.orEmpty() }
            val week = (p["week"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
            points[id] = WeekPoint(id, title, week?.let { setOf(it) } ?: if (p.str("title").isBlank() && p.str("name").isBlank()) points[id]?.weeks ?: titleWeeks(title) else titleWeeks(title))
            if (p.str("op") == "move") {
                val rows = points.values.toMutableList(); val row = rows.removeAt(rows.indexOfFirst { it.id == id })
                rows.add(p.num("index").toInt().coerceIn(0, rows.size), row)
                points.clear(); rows.forEach { points[it.id] = it }
            }
        }
    }
    return points.values.toList()
}

@Serializable data class StudyScope(val id: String, val week: Int, val kpIds: List<String>, val cursor: Int = 0) {
    /** Passing consumes a queue position; an explicit Next consumes it without claiming mastery. */
    fun target(mastery: Map<String, Mastery>, available: Set<String> = kpIds.toSet()): String? =
        kpIds.drop(cursor).firstOrNull { it in available && mastery[it]?.let { m -> m.passed && !m.needsRepair } != true }
}

/** Latest answered version of each choice; a successful retry removes it from the week's mistakes. */
fun weekMistakes(events: List<Event>, week: Int): List<Event> {
    val ids = weekPoints(events).filter { week in it.weeks }.map { it.id }.toSet()
    val answered = events.filter { it.type == "answer_submitted" }.map { it.payload.str("attemptId") }.toSet()
    val voided = events.filter { it.type == "attempt_voided" }.map { it.payload.str("attemptId") }.toSet()
    val grades = events.filter { it.type in setOf("attempt_graded", "grade_reviewed") }.associateBy { it.payload.str("attemptId") }
    return events.filter { it.type == "question_posed" && it.payload.str("format") == "SINGLE_CHOICE" &&
        it.payload.strings("kpIds").any { id -> id in ids } && it.payload.str("attemptId") in answered && it.payload.str("attemptId") !in voided }
        .groupBy { it.payload.str("questionId") }.values.mapNotNull { attempts ->
            val latest = attempts.last()
            val grade = grades[latest.payload.str("attemptId")]?.payload ?: return@mapNotNull null
            latest.takeIf { grade.num("max") > 0 && grade.num("score") < grade.num("max") }
        }
}
