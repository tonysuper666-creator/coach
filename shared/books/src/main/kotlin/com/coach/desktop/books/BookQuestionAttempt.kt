package com.coach.desktop.books

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.UUID

/** The reader installs this sink only for a question with a committed local turn. */
internal class BookAnswerProgress(val received: suspend (String) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BookAnswerProgress>
}

internal data class BookQuestionAttempt(val rowId: Long, val writeId: String, val question: String)

internal fun TextbookLibrary.beginBookQuestion(
    book: String, thread: String, selection: SourceBlock, location: String, question: String,
    sent: List<SourceBlock>, retry: BookQuestionAttempt? = null,
): BookQuestionAttempt = withCourseDatabase { db ->
    val writeId = UUID.randomUUID().toString()
    db.autoCommit = false
    try {
        val rowId = if (retry != null) {
            // A reopened window may retry an interrupted request while its old writer is still alive.
            db.prepareStatement("""UPDATE book_ask_turns SET write_id=?,answer_status='pending'
                WHERE id=? AND write_id=? AND book=? AND thread_id=? AND question_text=? AND answer_status!='complete'""").use {
                it.setString(1, writeId); it.setLong(2, retry.rowId); it.setString(3, retry.writeId)
                it.setString(4, book); it.setString(5, thread); it.setString(6, question)
                check(it.executeUpdate() == 1)
            }
            retry.rowId
        } else {
            val blocks = buildJsonArray {
                for (b in sent) add(buildJsonObject {
                    put("id", b.id); put("page", b.page); put("start", b.start); put("end", b.end)
                    put("heading", b.heading); put("text", b.text)
                })
            }
            db.prepareStatement("""INSERT INTO book_ask_turns(book,thread_id,selection_id,selection_page,
                selection_start,selection_end,selection_text,section_location,action_kind,label,question_text,
                answer_text,sent_blocks_json,created_at,answer_status,write_id)
                SELECT id,?,?,?,?,?,?,?,'ask',?,?,'',?,?,'pending',? FROM books WHERE id=?""").use {
                it.setString(1, thread); it.setString(2, selection.id); it.setInt(3, selection.page)
                it.setInt(4, selection.start); it.setInt(5, selection.end); it.setString(6, selection.text)
                it.setString(7, location); it.setString(8, "问：$question"); it.setString(9, question)
                it.setString(10, blocks.toString()); it.setLong(11, clock.millis()); it.setString(12, writeId)
                it.setString(13, book); check(it.executeUpdate() == 1)
            }
            db.createStatement().use { it.executeQuery("SELECT last_insert_rowid()").use { r -> check(r.next()); r.getLong(1) } }
        }
        MasteryStore.saveLastBookQuestion(db, book, question)
        db.commit()
        BookQuestionAttempt(rowId, writeId, question)
    } catch (e: Throwable) {
        db.rollback()
        throw e
    }
}

internal fun TextbookLibrary.saveBookQuestionProgress(turn: BookQuestionAttempt, text: String) =
    withCourseDatabase { db ->
        db.prepareStatement("UPDATE book_ask_turns SET answer_text=? WHERE id=? AND write_id=? AND answer_status='pending'").use {
            it.setString(1, text); it.setLong(2, turn.rowId); it.setString(3, turn.writeId)
            check(it.executeUpdate() == 1)
        }
    }

internal fun TextbookLibrary.finishBookQuestion(turn: BookQuestionAttempt, answer: Explanation?) =
    withCourseDatabase { db ->
        if (answer == null) {
            db.prepareStatement("UPDATE book_ask_turns SET answer_status='failed' WHERE id=? AND write_id=? AND answer_status='pending'").use {
                it.setLong(1, turn.rowId); it.setString(2, turn.writeId); it.executeUpdate()
            }
        } else {
            val citationsJson = StanceAnswerCodec.encodeCitationsColumn(answer)
            db.prepareStatement("""UPDATE book_ask_turns SET answer_status='complete',answer_text=?,citations_json=?,elapsed_ms=?
                WHERE id=? AND write_id=? AND answer_status='pending'""").use {
                it.setString(1, answer.text); it.setString(2, citationsJson); it.setLong(3, answer.elapsedMs)
                it.setLong(4, turn.rowId); it.setString(5, turn.writeId); check(it.executeUpdate() == 1)
            }
        }
        Unit
    }

internal fun TextbookLibrary.lastUnfinishedQuestion(book: String, thread: String): BookQuestionAttempt? =
    withCourseDatabase { db ->
        db.prepareStatement("""SELECT id,write_id,question_text,answer_status FROM book_ask_turns
            WHERE book=? AND thread_id=? ORDER BY id DESC LIMIT 1""").use {
            it.setString(1, book); it.setString(2, thread)
            it.executeQuery().use { r ->
                if (r.next() && r.getString(4) != "complete") BookQuestionAttempt(r.getLong(1), r.getString(2), r.getString(3))
                else null
            }
        }
    }
