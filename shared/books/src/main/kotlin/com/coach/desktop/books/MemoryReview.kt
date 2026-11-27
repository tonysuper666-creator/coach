package com.coach.desktop.books

import com.coach.domain.fsrs.Fsrs5
import com.coach.domain.fsrs.FsrsConfig
import com.coach.domain.fsrs.FsrsRating
import com.coach.domain.fsrs.FsrsState
import com.coach.domain.mastery.LearnerSignalType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.Types
import java.util.UUID
import kotlin.random.Random

internal enum class MemoryReviewKind { CONCEPT, NOTE }

/** A concept cloze built from the source sentence, or a prompt that does not need a model. */
internal sealed class MemoryPrompt {
    data class Cloze(val stem: String, val answer: String, val options: List<String>) : MemoryPrompt()
    data object Retell : MemoryPrompt()
    data object StillTrue : MemoryPrompt()
}

/**
 * One concept or one of the reader's own notes, scheduled on the same FSRS cards table as questions.
 * The lesson key is `concept:<id>` or `note:<id>` and the mode is [MODE_CONCEPT] or [MODE_NOTE],
 * so question rows (`CHOICE` / `SHORT_ANSWER`) stay untouched.
 */
internal data class MemoryReviewItem(
    val kind: MemoryReviewKind,
    val objectId: String,
    val bookId: String,
    val bookTitle: String,
    val title: String,
    val quote: String,
    val body: String,
    val page: Int,
    val offsetUtf16: Int,
    val tocNodeId: String,
    val dueDay: Long,
    val prompt: MemoryPrompt,
) {
    val lessonKey: String get() = when (kind) {
        MemoryReviewKind.CONCEPT -> MemoryReview.CONCEPT_PREFIX + objectId
        MemoryReviewKind.NOTE -> MemoryReview.NOTE_PREFIX + objectId
    }
    val modeName: String get() = when (kind) {
        MemoryReviewKind.CONCEPT -> MemoryReview.MODE_CONCEPT
        MemoryReviewKind.NOTE -> MemoryReview.MODE_NOTE
    }
}

internal sealed class ReviewLine {
    data class Question(val review: LocalBookReview) : ReviewLine()
    data class Memory(val item: MemoryReviewItem) : ReviewLine()
}

internal data class RankedMemory(val score: Double, val waitingDays: Long, val cardId: String, val item: MemoryReviewItem)

/**
 * Concepts and the reader's notes share the question queue's daily cap.
 * New cards are written once, with a stable `updated_at` of 0, so two machines enroll the same row.
 * A later grade rewrites the FSRS state. Deleting a note or muting a concept deletes the card;
 * learning sync already turns that delete into a tombstone.
 */
internal object MemoryReview {
    const val MODE_CONCEPT = "CONCEPT"
    const val MODE_NOTE = "NOTE"
    const val CONCEPT_PREFIX = "concept:"
    const val NOTE_PREFIX = "note:"

    fun enroll(db: Connection, today: Long) {
        if (!BookSchemaMigration.tableExists(db, "book_fsrs_cards")) return
        if (BookSchemaMigration.tableExists(db, "concept_entities") &&
            BookSchemaMigration.tableExists(db, "concept_mentions")
        ) {
            db.createStatement().use { sql ->
                sql.executeUpdate(
                    """DELETE FROM book_fsrs_cards
                       WHERE mode='$MODE_CONCEPT' AND NOT EXISTS (
                         SELECT 1 FROM concept_entities e
                         JOIN concept_mentions m ON m.concept_id=e.concept_id
                         WHERE e.muted=0 AND length(trim(m.quote_anchor))>0
                           AND book_fsrs_cards.book=m.book_id
                           AND book_fsrs_cards.lesson='${CONCEPT_PREFIX}'||e.concept_id
                       )""",
                )
            }
            db.createStatement().use { sql ->
                sql.executeQuery(
                    """SELECT e.concept_id, m.book_id
                       FROM concept_entities e
                       JOIN concept_mentions m ON m.concept_id=e.concept_id
                       WHERE e.muted=0 AND length(trim(m.quote_anchor))>0
                       ORDER BY e.concept_id, m.page, m.offset_utf16, m.mention_id""",
                ).use { rows ->
                    val seen = HashSet<String>()
                    while (rows.next()) {
                        val conceptId = rows.getString(1)
                        if (!seen.add(conceptId)) continue
                        insertNew(db, rows.getString(2), CONCEPT_PREFIX + conceptId, MODE_CONCEPT, today)
                    }
                }
            }
        }
        if (BookSchemaMigration.tableExists(db, "book_notes")) {
            db.createStatement().use { sql ->
                sql.executeUpdate(
                    """DELETE FROM book_fsrs_cards
                       WHERE mode='$MODE_NOTE' AND NOT EXISTS (
                         SELECT 1 FROM book_notes n
                         WHERE n.deleted=0 AND length(trim(n.note))>0 AND length(trim(n.quote))>0
                           AND book_fsrs_cards.book=n.book
                           AND book_fsrs_cards.lesson='${NOTE_PREFIX}'||n.id
                       )""",
                )
            }
            db.createStatement().use { sql ->
                sql.executeQuery(
                    """SELECT book, id FROM book_notes
                       WHERE deleted=0 AND length(trim(note))>0 AND length(trim(quote))>0
                       ORDER BY book, id""",
                ).use { rows ->
                    while (rows.next()) {
                        insertNew(db, rows.getString(1), NOTE_PREFIX + rows.getString(2), MODE_NOTE, today)
                    }
                }
            }
        }
    }

    fun due(db: Connection, today: Long): List<RankedMemory> {
        if (!BookSchemaMigration.tableExists(db, "book_fsrs_cards")) return emptyList()
        val out = ArrayList<RankedMemory>()
        out += dueConcepts(db, today)
        out += dueNotes(db, today)
        return out
    }

    fun rate(
        db: Connection,
        item: MemoryReviewItem,
        remembered: Boolean,
        editedNote: String?,
        today: Long,
        nowMs: Long,
        config: FsrsConfig = FsrsConfig(),
    ) {
        val prior = readState(db, item.bookId, item.lessonKey, item.modeName) ?: Fsrs5.newCard(today)
        val rating = if (remembered) FsrsRating.GOOD else FsrsRating.AGAIN
        val next = Fsrs5.review(prior, rating, today, config)
        withLearningFact(db, "card", {
            buildJsonObject {
                put("algorithm", "fsrs5-default-v1")
                put("day", today)
                put("rating", rating.value)
            }
        }) { writeState(db, item.bookId, item.lessonKey, item.modeName, next, nowMs) }
        if (item.kind == MemoryReviewKind.NOTE) {
            val replacement = editedNote?.trim().orEmpty()
            if (replacement.isNotEmpty() && replacement != item.body) {
                db.prepareStatement(
                    """UPDATE book_notes SET note=?, revision=revision+1, last_mutation_id=?
                       WHERE book=? AND id=? AND deleted=0""",
                ).use { sql ->
                    sql.setString(1, replacement)
                    sql.setString(2, UUID.randomUUID().toString())
                    sql.setString(3, item.bookId)
                    sql.setString(4, item.objectId)
                    sql.executeUpdate()
                }
            }
        } else {
            val collection = db.prepareStatement(
                "SELECT collection_id FROM concept_entities WHERE concept_id=?",
            ).use { sql ->
                sql.setString(1, item.objectId)
                sql.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            MasteryStore.recordEvidence(
                db = db,
                bookId = item.bookId,
                tocNodeId = item.tocNodeId.ifBlank { "p${item.page.coerceAtLeast(1)}" },
                page = item.page.coerceAtLeast(1),
                canonicalName = item.title,
                signal = LearnerSignalType.RUBRIC_GRADE,
                scoreRatio = if (remembered) 1.0 else 0.0,
                conceptKeyOverride = item.objectId,
                collectionId = collection,
                fsrsStability = next.stability,
                detailJson = """{"source":"concept_review"}""",
                nowMs = nowMs,
            )
        }
    }

    private fun dueConcepts(db: Connection, today: Long): List<RankedMemory> {
        if (!BookSchemaMigration.tableExists(db, "concept_entities") ||
            !BookSchemaMigration.tableExists(db, "concept_mentions")
        ) {
            return emptyList()
        }
        val namesByBook = HashMap<String, MutableList<String>>()
        db.createStatement().use { sql ->
            sql.executeQuery(
                """SELECT m.book_id, e.canonical_name
                   FROM concept_entities e JOIN concept_mentions m ON m.concept_id=e.concept_id
                   WHERE e.muted=0 AND length(trim(e.canonical_name))>0
                   ORDER BY e.canonical_name""",
            ).use { rows ->
                while (rows.next()) {
                    namesByBook.getOrPut(rows.getString(1)) { ArrayList() }.add(rows.getString(2))
                }
            }
        }
        return db.prepareStatement(
            """SELECT e.concept_id, e.canonical_name, m.book_id, COALESCE(b.title, ''),
                      m.page, m.offset_utf16, m.quote_anchor, COALESCE(m.toc_node_id, ''), m.claim_summary,
                      f.due_day, f.stability, f.difficulty, f.last_review_day, f.reps, f.lapses
               FROM book_fsrs_cards f
               JOIN concept_entities e ON f.lesson='${CONCEPT_PREFIX}'||e.concept_id
               JOIN concept_mentions m ON m.concept_id=e.concept_id AND m.book_id=f.book
               LEFT JOIN books b ON b.id=f.book
               WHERE f.mode='$MODE_CONCEPT' AND e.muted=0 AND f.due_day<=?
               ORDER BY e.concept_id, m.page, m.offset_utf16, m.mention_id""",
        ).use { sql ->
            sql.setLong(1, today)
            sql.executeQuery().use { rows ->
                val seen = HashSet<String>()
                buildList {
                    while (rows.next()) {
                        val conceptId = rows.getString(1)
                        if (!seen.add(conceptId)) continue
                        val bookId = rows.getString(3)
                        val name = rows.getString(2)
                        val quote = rows.getString(7)
                        val state = stateOf(rows, 10)
                        val retrievability = Fsrs5.retrievability(state.copy(dueDay = minOf(state.dueDay, today)), today)
                        val item = MemoryReviewItem(
                            kind = MemoryReviewKind.CONCEPT,
                            objectId = conceptId,
                            bookId = bookId,
                            bookTitle = rows.getString(4).ifBlank { "这本书" },
                            title = name,
                            quote = quote,
                            body = rows.getString(9).orEmpty(),
                            page = rows.getInt(5),
                            offsetUtf16 = rows.getInt(6),
                            tocNodeId = rows.getString(8).orEmpty(),
                            dueDay = state.dueDay,
                            prompt = clozeOrRetell(conceptId, name, quote, namesByBook[bookId].orEmpty()),
                        )
                        add(RankedMemory(conceptScore(db, conceptId, bookId, item.tocNodeId, retrievability),
                            (today - state.dueDay).coerceAtLeast(0), "${bookId}/${item.lessonKey}/$MODE_CONCEPT", item))
                    }
                }
            }
        }
    }

    private fun dueNotes(db: Connection, today: Long): List<RankedMemory> {
        if (!BookSchemaMigration.tableExists(db, "book_notes")) return emptyList()
        return db.prepareStatement(
            """SELECT n.id, n.book, COALESCE(b.title, ''), n.page, n.start, n.quote, n.note,
                      f.due_day, f.stability, f.difficulty, f.last_review_day, f.reps, f.lapses
               FROM book_fsrs_cards f
               JOIN book_notes n ON n.book=f.book AND f.lesson='${NOTE_PREFIX}'||n.id
               LEFT JOIN books b ON b.id=n.book
               WHERE f.mode='$MODE_NOTE' AND n.deleted=0 AND f.due_day<=?
                 AND length(trim(n.note))>0 AND length(trim(n.quote))>0
               ORDER BY n.book, n.id""",
        ).use { sql ->
            sql.setLong(1, today)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val state = stateOf(rows, 8)
                        val retrievability = Fsrs5.retrievability(state.copy(dueDay = minOf(state.dueDay, today)), today)
                        val bookId = rows.getString(2)
                        val noteId = rows.getString(1)
                        val item = MemoryReviewItem(
                            kind = MemoryReviewKind.NOTE,
                            objectId = noteId,
                            bookId = bookId,
                            bookTitle = rows.getString(3).ifBlank { "这本书" },
                            title = "我的批注",
                            quote = rows.getString(6),
                            body = rows.getString(7),
                            page = rows.getInt(4),
                            offsetUtf16 = rows.getInt(5),
                            tocNodeId = "",
                            dueDay = state.dueDay,
                            prompt = MemoryPrompt.StillTrue,
                        )
                        val score = MasteryEngine.needScore(0.85, 1.0, retrievability, null, 0, 0)
                        add(RankedMemory(score, (today - state.dueDay).coerceAtLeast(0),
                            "$bookId/${item.lessonKey}/$MODE_NOTE", item))
                    }
                }
            }
        }
    }

    private fun conceptScore(db: Connection, conceptId: String, bookId: String, tocNodeId: String, retrievability: Double): Double {
        val keys = listOfNotNull(conceptId, tocNodeId.takeIf { it.isNotBlank() }?.let { MasteryEngine.conceptKeyFor(bookId, it) })
        if (BookSchemaMigration.tableExists(db, "concept_mastery_ledger")) {
            for (key in keys) {
                val found = db.prepareStatement(
                    """SELECT syllabus_weight, p_mastery, dominant_error_tag, reread_count, ask_count
                       FROM concept_mastery_ledger WHERE concept_key=?""",
                ).use { sql ->
                    sql.setString(1, key)
                    sql.executeQuery().use { rows ->
                        if (!rows.next()) null else MasteryEngine.needScore(
                            syllabusWeight = rows.getDouble(1),
                            pMastery = rows.getDouble(2),
                            retrievability = retrievability,
                            dominantErrorTag = rows.getString(3),
                            rereadCount = rows.getInt(4),
                            askCount = rows.getInt(5),
                        )
                    }
                }
                if (found != null) return found
            }
        }
        return MasteryEngine.needScore(1.0, MasteryEngine.DEFAULT_P_MASTERY, retrievability, null, 0, 0)
    }

    internal fun clozeOrRetell(conceptId: String, name: String, quote: String, namesInBook: List<String>): MemoryPrompt {
        val answer = name.trim()
        val at = if (answer.length < 2) -1 else quote.indexOf(answer)
        if (at < 0) return MemoryPrompt.Retell
        val distractors = namesInBook.map { it.trim() }.filter { it.isNotEmpty() && it != answer }.distinct().take(12)
        if (distractors.isEmpty()) return MemoryPrompt.Retell
        val picked = distractors.shuffled(Random(conceptId.hashCode())).take(3)
        val options = (picked + answer).shuffled(Random(conceptId.hashCode() xor 0x5f3759df))
        val stem = quote.replaceRange(at, at + answer.length, "____")
        return MemoryPrompt.Cloze(stem, answer, options)
    }

    private fun insertNew(db: Connection, book: String, lesson: String, mode: String, today: Long) {
        db.prepareStatement(
            """INSERT OR IGNORE INTO book_fsrs_cards(
                 book, lesson, mode, stability, difficulty, last_review_day, due_day, reps, lapses, origin, updated_at
               ) VALUES(?,?,?,NULL,NULL,NULL,?,0,0,'NEW',0)""",
        ).use { sql ->
            sql.setString(1, book)
            sql.setString(2, lesson)
            sql.setString(3, mode)
            sql.setLong(4, today)
            sql.executeUpdate()
        }
    }

    private fun stateOf(rows: java.sql.ResultSet, dueIndex: Int): FsrsState = FsrsState(
        dueDay = rows.getLong(dueIndex),
        stability = rows.getDouble(dueIndex + 1).let { if (rows.wasNull()) null else it },
        difficulty = rows.getDouble(dueIndex + 2).let { if (rows.wasNull()) null else it },
        lastReviewDay = rows.getLong(dueIndex + 3).let { if (rows.wasNull()) null else it },
        reps = rows.getInt(dueIndex + 4),
        lapses = rows.getInt(dueIndex + 5),
    )

    private fun readState(db: Connection, book: String, lesson: String, mode: String): FsrsState? =
        db.prepareStatement(
            "SELECT due_day, stability, difficulty, last_review_day, reps, lapses FROM book_fsrs_cards WHERE book=? AND lesson=? AND mode=?",
        ).use { sql ->
            sql.setString(1, book)
            sql.setString(2, lesson)
            sql.setString(3, mode)
            sql.executeQuery().use { rows -> if (rows.next()) stateOf(rows, 1) else null }
        }

    private fun writeState(db: Connection, book: String, lesson: String, mode: String, state: FsrsState, nowMs: Long) {
        db.prepareStatement(
            """INSERT INTO book_fsrs_cards(book,lesson,mode,stability,difficulty,last_review_day,due_day,reps,lapses,origin,updated_at)
               VALUES(?,?,?,?,?,?,?,?,?,'NEW',?)
               ON CONFLICT(book,lesson,mode) DO UPDATE SET stability=excluded.stability,difficulty=excluded.difficulty,
               last_review_day=excluded.last_review_day,due_day=excluded.due_day,reps=excluded.reps,lapses=excluded.lapses,
               updated_at=excluded.updated_at""",
        ).use { sql ->
            sql.setString(1, book)
            sql.setString(2, lesson)
            sql.setString(3, mode)
            val stability = state.stability
            val difficulty = state.difficulty
            val last = state.lastReviewDay
            if (stability == null) sql.setNull(4, Types.REAL) else sql.setDouble(4, stability)
            if (difficulty == null) sql.setNull(5, Types.REAL) else sql.setDouble(5, difficulty)
            if (last == null) sql.setNull(6, Types.BIGINT) else sql.setLong(6, last)
            sql.setLong(7, state.dueDay)
            sql.setInt(8, state.reps)
            sql.setInt(9, state.lapses)
            sql.setLong(10, nowMs)
            sql.executeUpdate()
        }
    }
}
