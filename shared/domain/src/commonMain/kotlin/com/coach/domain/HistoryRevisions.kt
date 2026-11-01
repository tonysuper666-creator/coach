package com.coach.domain

import kotlinx.serialization.json.*

/** A source correction replaces its original historical facts at their original ledger position.
 * The immutable revision remains in the ledger. Replaying it must not count a second answer/note
 * or override learner facts that arrived after the original import. Only migration-owned facts
 * can be replaced; a revision cannot manufacture an ordinary assessment event.
 */
fun effectiveHistoryEvents(events: List<Event>): List<Event> {
    val originals = events.filter { it.deviceId == "deeptutor-migration" && it.actor == "SYSTEM" && it.type != "legacy_source_revised" }
        .groupBy { it.courseId to it.payload.str("sourceId") }
    val replacements = mutableMapOf<Pair<String,String>, List<Event>>()
    for (revision in events.filter { it.type == "legacy_source_revised" }) {
        require(revision.deviceId == "deeptutor-migration" && revision.actor == "SYSTEM" && revision.schema == 1) { "Invalid history revision producer" }
        val key = revision.courseId to revision.payload.str("sourceId")
        val base = originals[key].orEmpty()
        require(base.isNotEmpty() && base.all { it.seq < revision.seq }) { "History revision lacks an earlier source" }
        val template = base.first()
        val facts = (revision.payload["facts"] as? JsonArray) ?: error("Missing revised historical facts")
        val allowed = when (template.type) {
            "legacy_attempt" -> setOf("legacy_attempt", "answer_submitted", "attempt_voided")
            "legacy_imported" -> if (template.payload.containsKey("reviewNotAppliedReason")) setOf("legacy_imported", "legacy_schedule") else setOf("legacy_imported")
            "legacy_schedule" -> setOf("legacy_imported", "legacy_schedule")
            else -> setOf(template.type).intersect(setOf("legacy_passed", "note_saved", "legacy_journal_imported"))
        }
        val types = facts.map { it.jsonObject.str("type") }
        require(types.isNotEmpty() && types.distinct().size == types.size && types.all { it in allowed }) { "Unsafe historical revision types" }
        if (template.type == "legacy_attempt") require(types.take(2) == listOf("legacy_attempt", "answer_submitted")) { "Incomplete legacy attempt revision" }
        replacements[key] = facts.mapIndexed { index, value ->
            val fact = value.jsonObject
            val payload = fact.getValue("payload").jsonObject
            require(payload.str("sourceId") == key.second) { "History revision changed source identity" }
            if (fact.str("type") == "legacy_attempt") require(payload.str("origin") == "LEGACY" && payload.strings("kpIds").isEmpty() && payload.str("keyState") == "UNREVIEWED") { "History cannot become assessment evidence" }
            val occurred = fact.num("occurredAt")
            require(occurred > 0 && occurred <= revision.payload.num("snapshot_at") && occurred <= revision.recordedAt) { "Invalid historical revision time" }
            // An archived schedule may become eligible only when a later snapshot adds
            // its passed fact. Keep ordinary corrections at their original position,
            // but place this newly eligible schedule after that prerequisite.
            val passedSeq = if (fact.str("type") == "legacy_schedule") originals.values.flatten()
                .filter { it.courseId == revision.courseId && it.type == "legacy_passed" && it.payload.str("kpId") == payload.str("kpId") && it.seq < revision.seq }
                .minOfOrNull { it.seq } ?: template.seq else template.seq
            template.copy(seq = maxOf(template.seq, passedSeq), id = "${revision.id}:$index", idemKey = "${revision.id}:$index", type = fact.str("type"),
                recordedAt = revision.recordedAt, occurredAt = occurred, payload = payload)
        }
    }
    if (replacements.isEmpty()) return events
    return events.flatMap { event ->
        val key = event.courseId to event.payload.str("sourceId")
        val replacement = replacements[key]
        when {
            event.type == "legacy_source_revised" -> listOf(event) // audit only, keeps lastSeq authoritative
            replacement == null || event.deviceId != "deeptutor-migration" || event.actor != "SYSTEM" -> listOf(event)
            event.id == originals.getValue(key).first().id -> replacement
            else -> emptyList()
        }
    }.sortedWith(compareBy<Event> { it.seq }.thenBy { if (it.type == "legacy_passed") 0 else 1 })
}
