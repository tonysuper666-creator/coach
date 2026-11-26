package com.coach.server

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Project only user-authored text and its locator. UI state and model-generated answers are not drafts. */
internal object LearningDraftRecovery {
    private data class Passage(val text: String, val book: String?, val page: Int?, val location: String)

    fun field(kind: String) = when (kind) {
        "ask_draft" -> "question"
        "answer" -> "text"
        "chapter_progress", "course_topic" -> "practice_draft_json"
        else -> null
    }

    fun copies(kind: String, parent: String, round: Long, writer: String, edited: JsonObject,
               before: JsonObject?, visible: JsonObject?): List<JsonObject> {
        val passages = passages(kind, edited)
        if (passages.isEmpty()) return emptyList()
        val base = passages(kind, before)
        val active = passages(kind, visible)
        return passages.mapNotNull { (slot, p) ->
            if (p.text == base[slot]?.text || p.text == active[slot]?.text) return@mapNotNull null
            buildJsonObject {
                put("parent", parent); put("generation", round); put("writer", writer); put("slot", slot)
                put("source", kind); put("book", p.book); put("page", p.page); put("location", p.location); put("text", p.text)
            }
        }
    }

    private fun passages(kind: String, row: JsonObject?): Map<String, Passage> {
        if (row == null) return emptyMap()
        fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        val book = row.string("book")
        fun one(text: String?, bookId: String?, page: String?, location: String): Map<String, Passage> =
            if (text.isNullOrBlank()) emptyMap() else mapOf("" to Passage(text, bookId, page?.toIntOrNull(), location))
        return when (kind) {
            "ask_draft" -> one(row.string("question"), book, row.string("page"),
                JsonObject(row.filterKeys { it in setOf("thread_id","block_id","block_start","block_end","collection_id") }).toString())
            "answer" -> if (row.string("mode") == "SHORT_ANSWER")
                one(row.string("text"), book, null, row.string("lesson").orEmpty()) else emptyMap()
            "chapter_progress", "course_topic" -> {
                val draft = try {
                    Json.parseToJsonElement(row.string("practice_draft_json") ?: return emptyMap()) as? JsonObject
                } catch (_: SerializationException) { null } ?: return emptyMap()
                if (kind == "course_topic") {
                    one(draft.string("text"), draft.string("activeSourceBookId"), draft.string("activePage"), row.string("topic_id").orEmpty())
                } else {
                    val answers = draft["practiceTextDrafts"] as? JsonObject ?: return emptyMap()
                    buildMap {
                        for (key in answers.keys) {
                            val text = answers.string(key)
                            if (!text.isNullOrBlank()) put(key, Passage(text, row.string("book_id"),
                                draft.string("page")?.toIntOrNull(), row.string("toc_node_id").orEmpty()))
                        }
                    }
                }
            }
            else -> emptyMap()
        }
    }
}
