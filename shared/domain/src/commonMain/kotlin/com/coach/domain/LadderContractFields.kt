package com.coach.domain

import kotlinx.serialization.json.*

/** A scheduling fallback is not a verified rating. Preserve the incoming unknown state. */
data class LadderStar(val value: Int?, val state: String) {
    companion object {
        fun read(priority: JsonObject?): LadderStar {
            val raw = priority?.get("star") ?: return LadderStar(null, "missing")
            if (raw == JsonNull) return LadderStar(null, "null")
            if (raw == JsonPrimitive("unknown")) return LadderStar(null, "unknown")
            val number = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            return if (number != null && number in 1..3 && priority.str("star_source").isNotBlank()) LadderStar(number, "verified")
            else LadderStar(null, "unverified")
        }
    }
}

/** A prerequisite question reference must never silently become a knowledge-point requirement. */
data class LadderPrerequisites(val ids: List<String>, val known: Boolean, val issues: List<String>) {
    companion object {
        fun read(raw: JsonElement?, kpIds: Set<String>?): LadderPrerequisites {
            if (raw == null || raw == JsonNull) return LadderPrerequisites(emptyList(), false, listOf("prerequisite_kp_ids_unknown"))
            if (raw !is JsonArray || raw.any { it !is JsonPrimitive || !it.isString || it.content.isBlank() })
                return LadderPrerequisites(emptyList(), false, listOf("invalid_prerequisite_kp_ids"))
            val ids = raw.map { it.jsonPrimitive.content }
            val issues = when {
                ids.isEmpty() -> emptyList()
                kpIds == null -> listOf("prerequisite_kp_catalog_unknown")
                ids.any { it !in kpIds } -> listOf("prerequisite_id_is_not_a_kp")
                else -> emptyList()
            }
            return LadderPrerequisites(ids, issues.isEmpty(), issues)
        }
    }
}
