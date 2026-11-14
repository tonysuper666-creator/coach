package com.coach.server

import com.coach.domain.*
import com.coach.store.sha256
import kotlinx.serialization.json.*

internal fun MainlineItem.eligibleForLearning(names: Set<String>, week: Int, kpIds: Set<String>? = null): Boolean =
    ladder(kpIds)?.eligible == true && unit.week == week && learning?.num("week") == week.toLong() &&
        names.any { it.equals(learning?.str("course_id"), ignoreCase = true) }

/** The immutable candidate is attached to its existing pack item; it is not another question pool. */
internal fun MainlineItem.ladder(kpIds: Set<String>? = null): LadderQuestion? {
    val data = learning ?: return null
    val approvals = data["runtime_approval"] as? JsonObject ?: return null
    val candidate = JsonObject(data - "runtime_approval")
    val utility = data["utility"] as? JsonObject
    val priority = data["priority"] as? JsonObject
    val explanation = data["explanation"] as? JsonObject
    val wrong = (explanation?.get("wrong") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val sources = (data["sources"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val prerequisites = LadderPrerequisites.read(data["prerequisite_kp_ids"], kpIds)
    val eligible = prerequisites.known && verified && data.str("key_state") == "VERIFIED" &&
        data.str("contract_version") == "1.0.0" && data.str("id") == unit.itemId && data.str("kp_id") == unit.kp &&
        data.str("stem") == unit.stem && data.strings("options") == unit.options && data.num("answer", -1).toInt() == unit.answer &&
        data.str("section_id").isNotBlank() && data.str("variant_group_id").isNotBlank() && data.str("remember").isNotBlank() &&
        utility?.str("rationale").orEmpty().isNotBlank() && (utility?.get("evidence") as? JsonArray).orEmpty().isNotEmpty() &&
        explanation?.str("correct").orEmpty().isNotBlank() && explanation?.str("background").orEmpty().isNotBlank() &&
        wrong.map { it.num("option", -1).toInt() }.sorted() == unit.options.indices.filter { it != unit.answer } && wrong.all { it.str("reason").isNotBlank() } &&
        sources.isNotEmpty() && sources.all { it.str("kind") in setOf("textbook", "slides", "handbook") && it.str("document_id").isNotBlank() && it.str("locator").isNotBlank() && it.str("anchor").isNotBlank() } &&
        approvals.str("screening_decision") == "keep" && approvals.str("isolation_state") == "CLEARED" && approvals.str("release_state") == "APPROVED" &&
        listOf("independent_key_evidence", "utility_review_evidence", "originality_review_evidence").all { approvals.str(it).isNotBlank() } &&
        approvals.str("candidate_sha256") == sha256(canonicalJson(candidate).encodeToByteArray())
    val star = LadderStar.read(priority)
    val weight = (star.value ?: 1) + (if (priority?.str("lecture_emphasis") == "verified" && priority.str("lecture_emphasis_source").isNotBlank()) 1 else 0) +
        (if (priority?.str("weakness_signal") == "verified" && priority.str("weakness_source").isNotBlank()) 1 else 0)
    return LadderQuestion(unit.itemId, data.str("kp_id"), data.str("section_id"), data.str("kind"), data.num("difficulty").toInt(),
        data.str("variant_group_id"), fingerprint, prerequisites.ids, weight, eligible,
        (data["calculation"] as? JsonObject)?.str("chain_id").orEmpty(),
        (data["calculation"] as? JsonObject)?.num("step")?.toInt() ?: 0,
        (data["calculation"] as? JsonObject)?.str("complete_example_id").orEmpty(),
        capabilityId = data.str("capability_id").takeIf { it.isNotBlank() },
        prerequisitesKnown = prerequisites.known, priorityStar = star.value, priorityStarState = star.state,
        contractIssues = prerequisites.issues)
}

internal fun MainlineItem.ladderExplanation(): JsonObject {
    val data = learning ?: return explanationParts(unit.explanation)
    val explanation = data["explanation"] as? JsonObject ?: return explanationParts(unit.explanation)
    val wrong = (explanation["wrong"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    return json("correct" to explanation.str("correct"), "background" to explanation.str("background"),
        "source" to explanation.str("source").ifBlank { source }, "options" to JsonObject(wrong.associate { it.num("option").toString() to JsonPrimitive(it.str("reason")) }))
}
