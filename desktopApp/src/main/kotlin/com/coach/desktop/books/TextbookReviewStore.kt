package com.coach.desktop.books

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types

/** Local calendar-day policy: 1, 3, then 7 days after consecutive independent correct answers.
 * Wrong/guessed answers stay in today's same queue. No server identity or exam policy is inferred.
 */
internal class TextbookReviewStore(private val today: () -> Long) {
    fun migrate(db: Connection) {
        db.autoCommit = false
        try {
            fun add(table: String, name: String, declaration: String): Boolean {
                val exists = db.createStatement().use { s -> s.executeQuery("PRAGMA table_info($table)").use { r ->
                    var found = false
                    while (r.next()) if (r.getString("name") == name) found = true
                    found
                } }
                if (!exists) db.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $name $declaration") }
                return !exists
            }
            add("answers", "guessed", "INTEGER NOT NULL DEFAULT 0")
            add("book_reviews", "guessed", "INTEGER NOT NULL DEFAULT 0")
            if (add("book_reviews", "sources", "INTEGER NOT NULL DEFAULT 0")) db.createStatement().use {
                it.executeUpdate("UPDATE book_reviews SET sources=CASE mode WHEN 'CHOICE' THEN 1 WHEN 'SHORT_ANSWER' THEN 4 ELSE 0 END")
            }
            add("book_reviews", "due_day", "INTEGER")
            add("book_reviews", "streak", "INTEGER NOT NULL DEFAULT 0")
            db.prepareStatement("UPDATE book_reviews SET due_day=? WHERE due_day IS NULL").use {
                it.setLong(1, today() + 1); it.executeUpdate()
            }
            val hadHistory = db.createStatement().use { s -> s.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='book_review_history'").use { it.next() } }
            db.createStatement().use { it.execute("""CREATE TABLE IF NOT EXISTS book_review_history(
                id INTEGER PRIMARY KEY AUTOINCREMENT,book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,
                kind TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,
                guessed INTEGER NOT NULL,answered_day INTEGER)""") }
            if (!hadHistory) db.createStatement().use {
                // The old schema has no timestamps. Preserve facts without inventing an answer date.
                it.executeUpdate("""INSERT INTO book_review_history(book,lesson,mode,kind,text,revealed,understood,guessed)
                    SELECT book,lesson,mode,'MIGRATED',wrong_text,1,CASE WHEN mode='SHORT_ANSWER' THEN 0 ELSE NULL END,0
                    FROM book_reviews ORDER BY rowid""")
                it.executeUpdate("""INSERT INTO book_review_history(book,lesson,mode,kind,text,revealed,understood,guessed)
                    SELECT book,lesson,mode,'MIGRATED_REVIEW',answer_text,revealed,understood,guessed FROM book_reviews
                    WHERE revealed=1 AND (mode='CHOICE' OR understood IS NOT NULL) ORDER BY rowid""")
            }
            db.commit()
        } catch (failure: Exception) { db.rollback(); throw failure }
        finally { db.autoCommit = true }
    }

    private fun interval(streak: Int) = when (streak) { 0, 1 -> 1L; 2 -> 3L; else -> 7L }

    private fun sources(mode: QuestionMode, practice: Practice, answer: PracticeAnswer): Int {
        var bits = if (answer.guessed) LocalReviewSource.GUESSED.bit else 0
        if (mode == QuestionMode.CHOICE && answer.text != practice.answer) bits = bits or LocalReviewSource.WRONG.bit
        if (mode == QuestionMode.SHORT_ANSWER && answer.understood == false) bits = bits or LocalReviewSource.SELF_CHECK.bit
        return bits
    }

    fun recordLesson(db: Connection, book: Textbook, lesson: BookLesson, mode: QuestionMode,
        practice: Practice, answer: PracticeAnswer, migrated: Boolean = false) {
        val old = find(db, book.id, lesson.id, mode)
        val correct = (old?.practice ?: practice).independentlyCorrect(mode, answer)
        val streak = if (correct) (old?.confidentStreak ?: 0) + 1 else 0
        db.prepareStatement("""INSERT INTO book_reviews(book,lesson,mode,book_title,lesson_title,prompt,reference,
            solution,options,wrong_text,completed,sources,due_day,streak) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(book,lesson,mode) DO UPDATE SET
            answer_text=CASE WHEN book_reviews.completed=1 AND excluded.completed=0 THEN '' ELSE book_reviews.answer_text END,
            revealed=CASE WHEN book_reviews.completed=1 AND excluded.completed=0 THEN 0 ELSE book_reviews.revealed END,
            understood=CASE WHEN book_reviews.completed=1 AND excluded.completed=0 THEN NULL ELSE book_reviews.understood END,
            guessed=CASE WHEN book_reviews.completed=1 AND excluded.completed=0 THEN 0 ELSE book_reviews.guessed END,
            completed=excluded.completed,sources=book_reviews.sources | excluded.sources,
            due_day=excluded.due_day,streak=excluded.streak""").use { s ->
            s.setString(1, book.id); s.setString(2, lesson.id); s.setString(3, mode.name)
            s.setString(4, book.title); s.setString(5, lesson.title); s.setString(6, practice.prompt)
            s.setString(7, practice.reference); s.setString(8, practice.answer)
            s.setString(9, Json.encodeToString(practice.options)); s.setString(10, answer.text)
            s.setInt(11, if (correct) 1 else 0); s.setInt(12, sources(mode, old?.practice ?: practice, answer))
            s.setLong(13, today() + interval(streak)); s.setInt(14, streak); s.executeUpdate()
        }
        append(db, book.id, lesson.id, mode, answer, if (migrated) "MIGRATED" else "LESSON", if (migrated) null else today())
    }

    private fun append(db: Connection, book: String, lesson: String, mode: QuestionMode,
        answer: PracticeAnswer, kind: String, day: Long?) {
        db.prepareStatement("""INSERT INTO book_review_history(book,lesson,mode,kind,text,revealed,understood,guessed,answered_day)
            VALUES(?,?,?,?,?,?,?,?,?)""").use { s ->
            s.setString(1, book); s.setString(2, lesson); s.setString(3, mode.name); s.setString(4, kind)
            s.setString(5, answer.text); s.setInt(6, if (answer.revealed) 1 else 0)
            if (answer.understood == null) s.setNull(7, Types.INTEGER) else s.setInt(7, if (answer.understood) 1 else 0)
            s.setInt(8, if (answer.guessed) 1 else 0)
            if (day == null) s.setNull(9, Types.BIGINT) else s.setLong(9, day)
            s.executeUpdate()
        }
    }

    private fun history(db: Connection, book: String, lesson: String, mode: QuestionMode): List<LocalReviewAttempt> =
        db.prepareStatement("SELECT * FROM book_review_history WHERE book=? AND lesson=? AND mode=? ORDER BY id").use { s ->
            s.setString(1, book); s.setString(2, lesson); s.setString(3, mode.name)
            s.executeQuery().use { r -> buildList {
                while (r.next()) add(LocalReviewAttempt(size + 1, r.getString("kind"), r.answer("text"),
                    r.getLong("answered_day").let { if (r.wasNull()) null else it }))
            } }
        }

    private fun ResultSet.answer(text: String) = PracticeAnswer(getString(text), getInt("revealed") == 1,
        getInt("understood").let { if (wasNull()) null else it == 1 }, getInt("guessed") == 1)

    private fun read(db: Connection, r: ResultSet): LocalBookReview {
        val book = r.getString("book"); val lesson = r.getString("lesson"); val mode = QuestionMode.valueOf(r.getString("mode"))
        val due = r.getLong("due_day")
        val completed = r.getInt("completed") == 1
        val overdue = due <= today()
        val bits = r.getInt("sources") or if (overdue) LocalReviewSource.DUE.bit else 0
        return LocalBookReview(book, lesson, mode, r.getString("book_title"), r.getString("lesson_title"),
            Practice(r.getString("prompt"), r.getString("reference"), r.getString("solution"), Json.decodeFromString(r.getString("options"))),
            r.getString("wrong_text"), if (completed && overdue) PracticeAnswer() else r.answer("answer_text"),
            completed && !overdue, LocalReviewSource.entries.filter { bits and it.bit != 0 }.toSet(), due,
            history(db, book, lesson, mode), r.getInt("streak"))
    }

    fun find(db: Connection, book: String, lesson: String, mode: QuestionMode): LocalBookReview? =
        db.prepareStatement("SELECT * FROM book_reviews WHERE book=? AND lesson=? AND mode=?").use { s ->
            s.setString(1, book); s.setString(2, lesson); s.setString(3, mode.name)
            s.executeQuery().use { r -> if (r.next()) read(db, r) else null }
        }

    fun pending(db: Connection): List<LocalBookReview> = db.prepareStatement(
        "SELECT * FROM book_reviews WHERE completed=0 OR due_day<=? ORDER BY due_day,rowid").use { s ->
        s.setLong(1, today())
        s.executeQuery().use { r -> buildList { while (r.next()) add(read(db, r)) } }
    }

    fun saveRetry(db: Connection, entry: LocalBookReview, answer: PracticeAnswer) {
        val old = checkNotNull(find(db, entry.book, entry.lesson, entry.mode)) { "这道复习题已不在书架中。" }
        val committed = answer.committed(old.mode)
        val newAttempt = committed && answer != old.answer
        val correct = old.practice.independentlyCorrect(old.mode, answer)
        val streak = if (newAttempt) { if (correct) old.confidentStreak + 1 else 0 } else old.confidentStreak
        val due = if (newAttempt) today() + interval(streak) else old.dueDay
        db.prepareStatement("""UPDATE book_reviews SET answer_text=?,revealed=?,understood=?,guessed=?,completed=?,
            sources=sources | ?,due_day=?,streak=? WHERE book=? AND lesson=? AND mode=?""").use { s ->
            s.setString(1, answer.text); s.setInt(2, if (answer.revealed) 1 else 0)
            if (answer.understood == null) s.setNull(3, Types.INTEGER) else s.setInt(3, if (answer.understood) 1 else 0)
            s.setInt(4, if (answer.guessed) 1 else 0); s.setInt(5, if (correct) 1 else 0)
            s.setInt(6, old.sources.fold(if (committed) sources(old.mode, old.practice, answer) else 0) { bits, source -> bits or source.bit })
            s.setLong(7, due); s.setInt(8, streak)
            s.setString(9, old.book); s.setString(10, old.lesson); s.setString(11, old.mode.name)
            check(s.executeUpdate() == 1) { "无法保存复习作答。" }
        }
        if (newAttempt) append(db, old.book, old.lesson, old.mode, answer, "REVIEW", today())
    }

    fun active(db: Connection): LocalBookReview? = db.createStatement().use { s -> s.executeQuery("""SELECT r.*
        FROM book_reviews r JOIN book_review_position p ON r.book=p.book AND r.lesson=p.lesson AND r.mode=p.mode
        WHERE p.slot=1""").use { r -> if (r.next()) read(db, r) else null } }

    fun open(db: Connection, entry: LocalBookReview) {
        db.prepareStatement("UPDATE book_reviews SET sources=sources | 8 WHERE book=? AND lesson=? AND mode=? AND due_day<=?").use { s ->
            s.setString(1, entry.book); s.setString(2, entry.lesson); s.setString(3, entry.mode.name)
            s.setLong(4, today()); s.executeUpdate()
        }
        db.prepareStatement("""UPDATE book_reviews SET answer_text='',revealed=0,understood=NULL,guessed=0,completed=0
            WHERE book=? AND lesson=? AND mode=? AND completed=1 AND due_day<=?""").use { s ->
            s.setString(1, entry.book); s.setString(2, entry.lesson); s.setString(3, entry.mode.name)
            s.setLong(4, today()); s.executeUpdate()
        }
        db.prepareStatement("INSERT OR REPLACE INTO book_review_position(slot,book,lesson,mode) VALUES(1,?,?,?)").use { s ->
            s.setString(1, entry.book); s.setString(2, entry.lesson); s.setString(3, entry.mode.name); s.executeUpdate()
        }
    }
}
