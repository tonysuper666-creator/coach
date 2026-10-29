package com.coach.migrate

import com.coach.domain.*
import kotlinx.serialization.json.*

/** Import-only boundary. Keep original letters in the source archive, never in the usable question. */
fun mapChoiceQuestion(q: JsonObject): JsonObject {
    if (q.str("question_type").ifBlank { q.str("type") }.lowercase() !in setOf("mcq","multiple_choice","single_choice","choice")) return q
    val raw = q["options"] as? JsonObject
    val key = q.str("correct_answer").ifBlank { q.str("key") }
    val options = raw?.entries?.map { entry ->
        Option("option:" + sha(entry.value.jsonPrimitive.content), entry.value.jsonPrimitive.content)
    }.orEmpty()
    val correct = raw?.keys?.indexOf(key)?.let { options.getOrNull(it)?.optionId }
    val stem = q.str("question").ifBlank { q.str("stem") }
    return buildJsonObject {
        for ((name,value) in q) if (name !in setOf("options","correct_answer","key","answer")) put(name,value)
        put("options", JsonArray(options.map { buildJsonObject { put("optionId",it.optionId); put("text",it.text) } }))
        put("keyState","UNREVIEWED")
        if (correct != null && runCatching { ChoiceKey(options,correct) }.isSuccess) {
            put("correctOptionId",correct)
            put("fingerprint",questionFingerprint(stem,options))
        } else put("importIssue","Missing or invalid legacy options/key; requires review")
    }
}
