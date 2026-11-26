package com.coach.protocol.learning

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Only fields belonging to learning records may enter a migration or a wire payload. */
class LearningTable internal constructor(
    val name: String,
    val kind: String,
    key: String,
    fields: String,
) {
    val keyFields = key.split(' ')
    val columns = fields.split(' ')
    val event = kind == "ask_turn" || kind == "review_event"
    val exam = name.startsWith("local_exam_")

    fun legacyId(row: JsonObject): String {
        fun field(name: String): JsonElement {
            val value = row[name] ?: JsonNull
            return if (value == JsonNull) value else JsonPrimitive(value.jsonPrimitive.content)
        }
        fun digest(names: String, prefix: String? = null): JsonPrimitive {
            val values = buildJsonArray {
                prefix?.let { add(it) }
                names.split(' ').forEach { add(field(it)) }
            }
            return JsonPrimitive(hex(MessageDigest.getInstance("SHA-256").digest(values.toString().encodeToByteArray())))
        }
        val key = buildJsonArray {
            add(kind)
            for (column in keyFields) add(when (column) {
                "version" -> if (kind == "highlight") field("book") else field(column)
                "historyDigest" -> digest("lesson mode kind text revealed understood guessed answered_day")
                "turnDigest" -> if (row["write_id"]?.jsonPrimitive?.contentOrNull != null) {
                    digest("thread_id selection_id question_text created_at", "question-v9")
                } else {
                    digest("thread_id selection_id selection_page selection_start selection_end selection_text section_location action_kind label question_text answer_text citations_json sent_blocks_json elapsed_ms created_at")
                }
                else -> field(column)
            })
        }
        return stableId(key.toString())
    }

    companion object {
        private val namespace = UUID.fromString("8de10572-23e3-43ea-bdb0-b45b39375efa")
        private val namespaceBytes = ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits)
            .putLong(namespace.leastSignificantBits).array()

        private fun stableId(name: String): String {
            val sha = MessageDigest.getInstance("SHA-1")
            sha.update(namespaceBytes)
            val bytes = sha.digest(name.encodeToByteArray())
            bytes[6] = ((bytes[6].toInt() and 15) or 0x50).toByte()
            bytes[8] = ((bytes[8].toInt() and 63) or 0x80).toByte()
            val words = ByteBuffer.wrap(bytes)
            return UUID(words.long, words.long).toString()
        }

        private fun hex(bytes: ByteArray) = java.util.HexFormat.of().formatHex(bytes)
    }
}

// Recovery rows have no pre-sync source. Their IDs identify one author's edit of one text slot.
val draftVersion = LearningTable("learning_draft_versions", "draft_version", "parent generation writer slot",
    "parent generation writer slot source book page location text")

val learningTables = listOf(
    LearningTable("books", "book_progress", "id",
        "id title format reading_page lesson_index learning prompt_seen mode touched material_kind show_standalone"),
    LearningTable("book_read_spots", "read_spot", "book",
        "book page offset_utf16"),
    LearningTable("book_chapter_progress", "chapter_progress", "book_id toc_node_id",
        "book_id toc_node_id pages_total pages_dwelled dwelled_pages_json read_state manual_override practice_draft_json updated_at"),
    LearningTable("book_learning_paths", "learning_path", "book",
        "book path"),
    LearningTable("book_ladders", "ladder", "book",
        "book session"),
    LearningTable("book_study_preferences", "study_preference", "book_id",
        "book_id study_mode user_locked default_reason detail_level explain_lang daily_minutes target_date inquiry_question updated_at"),
    LearningTable("book_notes", "note", "book id",
        "book id version page start end quote note text_sha256 revision last_mutation_id deleted created_at"),
    LearningTable("book_highlights", "highlight", "book version page start end",
        "book page start end quote created_at"),
    LearningTable("book_bookmarks", "bookmark", "book page",
        "book page note created_at"),
    LearningTable("book_ask_turns", "ask_turn", "book id turnDigest",
        "id book thread_id selection_id selection_page selection_start selection_end selection_text section_location action_kind label question_text answer_text citations_json sent_blocks_json elapsed_ms created_at answer_status"),
    LearningTable("book_ask_drafts", "ask_draft", "book",
        "book page question thread_id block_id block_start block_end collection_id updated_at"),
    LearningTable("answers", "answer", "book lesson mode",
        "book lesson mode text revealed understood guessed"),
    LearningTable("book_reviews", "mistake", "book lesson mode",
        "book lesson mode book_title lesson_title prompt reference solution options wrong_text answer_text revealed understood completed guessed sources due_day streak source_page"),
    LearningTable("book_review_history", "review_event", "book id historyDigest",
        "id book lesson mode kind text revealed understood guessed answered_day"),
    LearningTable("concept_entities", "concept", "concept_id",
        "concept_id collection_id canonical_name aliases_json muted muted_at shared_note"),
    LearningTable("concept_mentions", "concept_mention", "mention_id",
        "mention_id concept_id book_id toc_node_id chunk_id page offset_utf16 quote_anchor mention_role claim_summary"),
    LearningTable("concept_links", "concept_link", "link_id",
        "link_id collection_id concept_id from_mention_id to_mention_id relation_type diff_summary confidence dismissed"),
    LearningTable("concept_mastery_ledger", "mastery", "concept_key",
        "concept_key collection_id book_id toc_node_id canonical_name syllabus_weight p_mastery has_active_test fsrs_stability dominant_error_tag reread_count dwell_ms_total ask_count highlight_count updated_at"),
    LearningTable("learner_evidence_events", "evidence", "event_id",
        "event_id concept_key book_id page signal_type score_ratio guessed error_class duration_ms detail_json created_at"),
    LearningTable("learning_gain_cohorts", "gain_cohort", "cohort_id",
        "cohort_id collection_id book_id chapter_id concept_key t0_at t0_score t0_guessed read_completed_at read_dwell_sec t1_at t1_score t2_eligible_after t2_at t2_variant_id t2_score"),
    LearningTable("margin_whisper_audit", "whisper", "whisper_id",
        "whisper_id book_id toc_node_id page rule collapsed_text expanded_text pdf_page_anchor quote_anchor trigger_evidence_json dismissed created_at"),
    LearningTable("book_fsrs_cards", "card", "book lesson mode",
        "book lesson mode stability difficulty last_review_day due_day reps lapses origin updated_at"),
    LearningTable("collections", "collection", "id",
        "id kind title metadata_json created_at updated_at sort_order exam_date last_active_anchor_json source_hash user_locked archived"),
    LearningTable("collection_materials", "collection_material", "collection_id material_id",
        "collection_id material_id role ordinal added_at metadata_json week_num user_locked authority_rank source_hash"),
    LearningTable("course_topics", "course_topic", "topic_id",
        "topic_id collection_id week_label ord title exam_status user_locked exam_weight pack_payload_json pack_sources_hash practice_draft_json mastery_state updated_at"),
    LearningTable("course_topic_sources", "topic_source", "topic_id book_id start_page end_page",
        "topic_id book_id toc_node_id start_page end_page source_role user_locked scope_note"),
    LearningTable("local_exam_papers", "exam_paper", "book paper_id",
        "book paper_id paper_json paper_hash"),
    LearningTable("local_exam_attempts", "exam_attempt", "id",
        "id owner book paper_id paper_json paper_hash started_at answers_json answers_seq submitted_at result_json timing duration_minutes deadline auto_submitted"),
    LearningTable("exam_daily_state", "exam_daily", "course_id",
        "course_id daily_minutes pins_json updated_at"),
)
