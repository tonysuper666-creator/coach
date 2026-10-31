package com.coach.migrate

import com.coach.domain.*
import kotlinx.serialization.json.*

/** Import-only boundary. Keep original letters in the source archive, never in the usable question. */
fun mapChoiceQuestion(q: JsonObject): JsonObject {
    if (!isChoice(q)) return q
    val entries = choiceEntries(q)
    val key = q.str("correct_answer").ifBlank { q.str("key") }.ifBlank { q.str("expected_answer") }
    val options = entries.map { (_,text) -> Option("option:" + sha(text),text) }
    val correct = entries.indexOfFirst { it.first == key }.let { options.getOrNull(it)?.optionId }
    val stem = q.str("question").ifBlank { q.str("stem") }.ifBlank { q.str("prompt") }
    return buildJsonObject {
        for ((name,value) in q) if (name !in setOf("options","correct_answer","key","answer","expected_answer")) put(name,value)
        put("options", JsonArray(options.map { buildJsonObject { put("optionId",it.optionId); put("text",it.text) } }))
        put("keyState","UNREVIEWED")
        if (correct != null && runCatching { ChoiceKey(options,correct) }.isSuccess) {
            put("correctOptionId",correct)
            put("fingerprint",questionFingerprint(stem,options))
        } else put("importIssue","Missing or invalid legacy options/key; requires review")
    }
}

fun choiceEntries(q: JsonObject): List<Pair<String,String>> = when(val raw=q["options"]) {
    is JsonObject -> raw.entries.map { it.key to it.value.jsonPrimitive.content }
    is JsonArray -> raw.map { it.jsonObject.let { o -> o.str("label") to o.str("body").ifBlank { o.str("text") } } }
    else -> emptyList()
}

fun isChoice(q:JsonObject) = q.str("question_type").ifBlank { q.str("type") }.lowercase() in
    setOf("mcq","multiple_choice","single_choice","choice")

fun choiceAnswer(q:JsonObject,answer:String): JsonElement {
    val entries=choiceEntries(q)
    val selected=entries.filter { it.first==answer.trim() }
    return if(selected.size==1 && selected.single().second.isNotBlank())
        JsonPrimitive("option:"+sha(selected.single().second)) else JsonNull
}
