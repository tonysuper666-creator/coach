package com.coach.migrate

import kotlinx.serialization.json.*

fun chapters(text: String): Set<Int> {
    val regex = Regex("(?i)(?:Ch(?:apter)?\\s*|第\\s*)(\\d+)(?:\\s*[–—−-]\\s*(\\d+))?")
    return regex.findAll(text).flatMap { m ->
        val lo=m.groupValues[1].toInt(); val hi=m.groupValues[2].toIntOrNull() ?: lo
        if(hi >= lo && hi-lo < 50) (lo..hi).asSequence() else emptySequence()
    }.toSet()
}
fun matchQuestion(q: JsonObject, kps: List<JsonObject>): JsonObject {
    val tags = q["tags"]?.toString().orEmpty()
    val ch=chapters(tags)
    var candidates = if(ch.isEmpty()) emptyList() else kps.filter { kp ->
        chapters(kp.str("name")).intersect(ch).isNotEmpty()
    }
    val trace=mutableListOf("chapter:${candidates.size}")
    val module=q.str("module_id")
    if(module.isNotBlank()) candidates = candidates.filter { it.str("module_id") == module }
    trace.add("module:${candidates.size}")
    // Page locators must be explicit. No page or module is guessed from question prose.
    val pages=pageLocators(q)
    if(pages.isNotEmpty()) candidates = candidates.filter { kp ->
        pageLocators(kp["anchors"] ?: JsonNull).intersect(pages).isNotEmpty()
    }
    trace.add("page_overlap:${candidates.size}")
    return buildJsonObject {
        put("question_id", q.str("id"))
        put("kp_ids", JsonArray(if(candidates.size==1) candidates.map { JsonPrimitive(it.str("id")) } else emptyList()))
        put("candidate_ids", JsonArray(candidates.map { JsonPrimitive(it.str("id")) }.sortedBy { it.content }))
        put("status", if(candidates.size==1) "deterministic" else "需模型/人工确认")
        put("trace", JsonArray(trace.map(::JsonPrimitive)))
        put("reason", if(ch.isEmpty()) "missing_chapter" else if(candidates.isEmpty()) "no_supported_candidate" else if(candidates.size>1) "ambiguous" else "unique_supported_candidate")
    }
}

// Page numbers are comparable only within the same document and coordinate system.
fun pageLocators(value: JsonElement): Set<String> = when(value) {
    is JsonArray -> value.flatMap { pageLocators(it) }.toSet()
    is JsonObject -> {
        val document=value.str("document_key")
        val index=value["page_index"]?.let { (it as? JsonPrimitive)?.intOrNull }
        val own=if(document.isNotBlank() && index!=null) setOf("$document:index:$index") else emptySet()
        own + value.values.flatMap { pageLocators(it) }
    }
    else -> emptySet()
}
