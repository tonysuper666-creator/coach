package com.coach.desktop.books

import com.coach.domain.Review
import com.coach.domain.initialReview
import com.coach.domain.logicalDay
import com.coach.domain.nextReview
import com.coach.domain.textbookPagesFromMarkers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** All state belongs to this Windows library. No server, token or external user corpus is opened. */
internal class TextbookLibrary(val directory: Path,
    variantPort: BookReviewVariantPort? = null,
    private val sourceAuthority: BookReviewSourceAuthority? = null,
    private val reviewDay: () -> Long = { logicalDay(System.currentTimeMillis(), ZoneId.systemDefault().id) }) {
    private val sources = BookReviewSources(sourceAuthority, ::database)
    private val variants = BookReviewVariants(variantPort ?: sources) { review ->
        book(review.book).lessons.single { it.id == review.lesson }.text
    }
    init {
        Files.createDirectories(directory)
        database().use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE IF NOT EXISTS books(id TEXT PRIMARY KEY,title TEXT NOT NULL,format TEXT NOT NULL,reading_page INTEGER NOT NULL DEFAULT 1,lesson_index INTEGER NOT NULL DEFAULT 0,learning INTEGER NOT NULL DEFAULT 0,prompt_seen INTEGER NOT NULL DEFAULT 0,mode TEXT NOT NULL DEFAULT 'NONE',touched INTEGER NOT NULL)")
                sql.execute("CREATE TABLE IF NOT EXISTS pages(book TEXT NOT NULL,page INTEGER NOT NULL,text TEXT NOT NULL,PRIMARY KEY(book,page))")
                sql.execute("CREATE TABLE IF NOT EXISTS answers(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,guessed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))")
                sql.execute("""CREATE TABLE IF NOT EXISTS book_reviews(
                    book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,book_title TEXT NOT NULL,lesson_title TEXT NOT NULL,
                    prompt TEXT NOT NULL,reference TEXT NOT NULL,solution TEXT NOT NULL,options TEXT NOT NULL,wrong_text TEXT NOT NULL,
                    wrong_guessed INTEGER NOT NULL DEFAULT 0,
                    answer_text TEXT NOT NULL DEFAULT '',revealed INTEGER NOT NULL DEFAULT 0,understood INTEGER,
                    answer_guessed INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))""")
                sql.execute("CREATE TABLE IF NOT EXISTS book_review_position(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL)")
                sql.execute("CREATE TABLE IF NOT EXISTS book_learning_paths(book TEXT PRIMARY KEY,path TEXT NOT NULL CHECK(path IN ('READ','READ_AND_LEARN','QUESTION_FIRST')))")
            }
            variants.initialize(db)
            sources.initialize(db)
            // Older libraries already have these tables; CREATE IF NOT EXISTS never adds a column to them.
            ensureColumn(db, "answers", "guessed", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, "book_reviews", "wrong_guessed", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, "book_reviews", "answer_guessed", "INTEGER NOT NULL DEFAULT 0")
            // No historical answer timestamps exist: never invent a deadline while migrating.
            ensureColumn(db, "book_reviews", "due_day", "INTEGER")
            ensureColumn(db, "book_reviews", "interval", "INTEGER")
            ensureColumn(db, "book_reviews", "ease", "REAL")
            ensureColumn(db, "book_reviews", "anchor", "INTEGER")
            ensureColumn(db, "book_reviews", "source_available", "INTEGER NOT NULL DEFAULT 1")
        }
        backfillReviews()
    }
    /** Adds a column to a library created before it existed; absent facts stay false, never guessed at. */
    private fun ensureColumn(db: Connection, table: String, column: String, definition: String) {
        val present = db.createStatement().use { sql ->
            sql.executeQuery("PRAGMA table_info($table)").use { rows ->
                buildSet { while (rows.next()) add(rows.getString("name")) }
            }
        }
        if (column !in present) db.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $column $definition") }
    }
    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:${directory.resolve("library.sqlite")}").apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    @Synchronized fun catalog(): List<BookSummary> = database().use { db ->
        db.createStatement().use { sql -> sql.executeQuery("SELECT b.*, (SELECT count(*) FROM pages p WHERE p.book=b.id) AS page_count, (SELECT max(p.page) FROM pages p WHERE p.book=b.id) AS last_page FROM books b ORDER BY touched DESC,id").use { rows -> buildList {
            while (rows.next()) add(BookSummary(rows.getString("id"), rows.getString("title"), rows.getInt("page_count"), rows.getInt("last_page"), rows.getInt("reading_page"), rows.getInt("learning") == 1))
        } } }
    }.filterNot { Files.exists(removedMarker(it.id)) }

    private fun removedMarker(id: String): Path {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return directory.resolve("removed-books").resolve("$id.removed")
    }

    @Synchronized fun learningPath(book: String): String? = database().use { db ->
        db.prepareStatement("SELECT path FROM book_learning_paths WHERE book=?").use { sql ->
            sql.setString(1, book)
            sql.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
        }
    }

    @Synchronized fun saveLearningPath(book: String, path: String) {
        require(path in setOf("READ", "READ_AND_LEARN", "QUESTION_FIRST")) { "未知的学习走法。" }
        database().use { db ->
            db.prepareStatement("INSERT INTO book_learning_paths(book,path) SELECT id,? FROM books WHERE id=? ON CONFLICT(book) DO UPDATE SET path=excluded.path").use { sql ->
                sql.setString(1, path); sql.setString(2, book)
                require(sql.executeUpdate() == 1) { "这本教材已不在书架中。" }
            }
        }
    }

    @Synchronized fun book(id: String): Textbook = database().use { db ->
        check(!Files.exists(removedMarker(id))) { "这本教材已不在书架中。" }
        db.prepareStatement("SELECT * FROM books WHERE id=?").use { sql ->
            sql.setString(1, id)
            sql.executeQuery().use { row ->
                check(row.next()) { "这本教材已不在书架中。" }
                val pages = db.prepareStatement("SELECT page,text FROM pages WHERE book=? ORDER BY page").use { query ->
                    query.setString(1, id)
                    query.executeQuery().use { p -> buildList { while (p.next()) add(BookPage(p.getInt(1), p.getString(2))) } }
                }
                // Reading progress is a physical PDF page; a stored number this book no longer has
                // (a page-marked export that starts at page 5) resumes at its first real page.
                val stored = row.getInt("reading_page")
                Textbook(id, row.getString("title"), row.getString("format"), pages,
                    if (pages.any { it.number == stored }) stored else pages.first().number,
                    row.getInt("lesson_index"), row.getInt("learning") == 1, row.getInt("prompt_seen") == 1,
                    QuestionMode.valueOf(row.getString("mode")))
            }
        }
    }

    @Synchronized fun importBook(source: Path): Textbook {
        val format = source.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(format in setOf("pdf", "epub", "md", "txt")) { "请选择 PDF、EPUB、Markdown 或 UTF-8 文本教材。" }
        require(Files.size(source) in 1..(256L * 1024 * 1024)) { "请选择不超过 256 MB 的教材。" }
        val stage = directory.resolve(".import-${UUID.randomUUID()}")
        try {
            Files.copy(source, stage)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(stage).use { input ->
                val buffer = ByteArray(65536)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            if (catalog().any { it.id == id }) return book(id)
            if (Files.exists(removedMarker(id))) {
                val savedFormat = database().use { db -> db.prepareStatement("SELECT format FROM books WHERE id=?").use { sql ->
                    sql.setString(1, id); sql.executeQuery().use { row -> check(row.next()); row.getString(1) }
                } }
                Files.move(stage, original(id, savedFormat), StandardCopyOption.REPLACE_EXISTING)
                Files.delete(removedMarker(id))
                return book(id) // Restore the same material's progress, never its former consent.
            }
            val fallback = source.fileName.toString().substringBeforeLast('.')
            val extracted = TextbookExtraction.extract(stage, format, fallback)
            require(extracted.pages.isNotEmpty()) { "教材中没有可打开的页面。" }
            val target = original(id, format)
            // The immutable source is in place before the single transaction makes it visible.
            Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING)
            database().use { db ->
                db.autoCommit = false
                try {
                    db.prepareStatement("INSERT INTO books(id,title,format,mode,touched,reading_page) VALUES(?,?,?,?,?,?)").use { sql ->
                        sql.setString(1, id); sql.setString(2, extracted.title); sql.setString(3, format)
                        sql.setString(4, QuestionMode.CHOICE.name)
                        sql.setLong(5, System.currentTimeMillis()); sql.setInt(6, extracted.pages.minOf { it.number }); sql.executeUpdate()
                    }
                    db.prepareStatement("INSERT INTO pages(book,page,text) VALUES(?,?,?)").use { sql ->
                        for (page in extracted.pages) {
                            sql.setString(1, id); sql.setInt(2, page.number); sql.setString(3, page.text); sql.addBatch()
                        }
                        sql.executeBatch()
                    }
                    db.commit()
                } catch (error: Exception) { db.rollback(); throw error }
            }
            return book(id)
        } finally { Files.deleteIfExists(stage) }
    }

    private fun original(id: String, format: String): Path {
        require(id.matches(Regex("[a-f0-9]{64}")) && format in setOf("pdf", "epub", "md", "txt"))
        return directory.resolve("$id.$format")
    }

    /** Reversible bookshelf removal. Call only after revoking and awaiting this book's requests. */
    @Synchronized fun removeBook(id: String) {
        val book = book(id)
        val source = original(id, book.format)
        val archive = directory.resolve("removed-books").resolve("$id-${UUID.randomUUID()}")
        Files.createDirectories(archive)
        val target = archive.resolve(source.fileName)
        if (Files.exists(source)) Files.move(source, target)
        try {
            Files.writeString(removedMarker(id), archive.fileName.toString())
        } catch (failure: Exception) {
            if (Files.exists(target)) Files.move(target, source)
            throw failure
        }
    }

    @Synchronized fun save(book: Textbook) {
        require(book.page(book.readingPage) != null && book.lessonIndex in 0..book.lessons.size)
        database().use { db -> db.prepareStatement("UPDATE books SET reading_page=?,lesson_index=?,learning=?,prompt_seen=?,mode=?,touched=? WHERE id=?").use { sql ->
            sql.setInt(1, book.readingPage); sql.setInt(2, book.lessonIndex); sql.setInt(3, if (book.learning) 1 else 0)
            sql.setInt(4, if (book.promptSeen) 1 else 0); sql.setString(5, book.questionMode.name)
            sql.setLong(6, System.currentTimeMillis()); sql.setString(7, book.id)
            check(sql.executeUpdate() == 1) { "无法保存这本书的进度。" }
        } }
    }

    @Synchronized fun answer(book: String, lesson: String, mode: QuestionMode): PracticeAnswer = database().use { db ->
        db.prepareStatement("SELECT text,revealed,understood,guessed FROM answers WHERE book=? AND lesson=? AND mode=?").use { sql ->
            sql.setString(1, book); sql.setString(2, lesson); sql.setString(3, mode.name)
            sql.executeQuery().use { row ->
                if (!row.next()) PracticeAnswer() else {
                    val understood = row.getInt(3).let { if (row.wasNull()) null else it == 1 }
                    PracticeAnswer(row.getString(1), row.getInt(2) == 1, understood, row.getInt(4) == 1)
                }
            }
        }
    }

    @Synchronized fun saveAnswer(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer) {
        val candidate = answer.revealed && (mode == QuestionMode.CHOICE && answer.text.isNotBlank() ||
            mode == QuestionMode.SHORT_ANSWER && answer.understood != null)
        val source = if (candidate) book(book) else null
        val section = source?.lessons?.find { it.id == lesson }
        val practice = section?.let { StudyCoachCourse.practice(checkNotNull(source), it, mode) }
        database().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("INSERT OR REPLACE INTO answers(book,lesson,mode,text,revealed,understood,guessed) VALUES(?,?,?,?,?,?,?)").use { sql ->
                    sql.setString(1, book); sql.setString(2, lesson); sql.setString(3, mode.name); sql.setString(4, answer.text)
                    sql.setInt(5, if (answer.revealed) 1 else 0)
                    if (answer.understood == null) sql.setNull(6, java.sql.Types.INTEGER) else sql.setInt(6, if (answer.understood) 1 else 0)
                    sql.setInt(7, if (answer.guessed) 1 else 0)
                    sql.executeUpdate()
                }
                if (practice != null) {
                    val queue = practice.needsReview(mode, answer)
                    recordReview(db, checkNotNull(source), checkNotNull(section), mode, practice, answer, reopen = queue, queue = queue)
                    val initial = initialReview(reviewDay())
                    // Ordinary practice initializes the ledger schedule; a miss brings it forward.
                    // Only the explicit review path advances its interval/ease.
                    db.prepareStatement("""UPDATE book_reviews SET
                        due_day=CASE WHEN ?=1 THEN min(COALESCE(due_day,?),?) ELSE COALESCE(due_day,?) END,
                        interval=COALESCE(interval,?),ease=COALESCE(ease,?) WHERE book=? AND lesson=? AND mode=?""").use { sql ->
                        sql.setInt(1, if (queue) 1 else 0)
                        sql.setLong(2, initial.dueDay); sql.setLong(3, initial.dueDay); sql.setLong(4, initial.dueDay)
                        sql.setInt(5, initial.intervalDays); sql.setDouble(6, initial.ease)
                        sql.setString(7, book); sql.setString(8, lesson); sql.setString(9, mode.name); sql.executeUpdate()
                    }
                }
                db.commit()
            } catch (failure: Exception) { db.rollback(); throw failure }
        }
    }

    /** Upgrade existing wrong answers too; completed review rows remain completed on every reopen. */
    private fun backfillReviews() {
        val old = database().use { db -> db.createStatement().use { sql ->
            sql.executeQuery("""SELECT book,lesson,mode FROM answers a WHERE revealed=1
                AND (mode='CHOICE' OR (mode='SHORT_ANSWER' AND understood=0))
                AND NOT EXISTS(SELECT 1 FROM book_reviews r WHERE r.book=a.book AND r.lesson=a.lesson AND r.mode=a.mode)""").use { rows -> buildList {
                while (rows.next()) add(Triple(rows.getString(1), rows.getString(2), QuestionMode.valueOf(rows.getString(3))))
            } }
        } }
        for ((id, lessonId, mode) in old.filterNot { Files.exists(removedMarker(it.first)) }) {
            val book = book(id)
            val lesson = book.lessons.find { it.id == lessonId } ?: continue
            val practice = StudyCoachCourse.practice(book, lesson, mode) ?: continue
            val answer = answer(id, lessonId, mode)
            if (practice.needsReview(mode, answer)) database().use { db ->
                recordReview(db, book, lesson, mode, practice, answer, reopen = false)
            }
        }
    }

    private fun recordReview(db: Connection, book: Textbook, lesson: BookLesson, mode: QuestionMode,
        practice: Practice, answer: PracticeAnswer, reopen: Boolean, queue: Boolean = true) {
        val conflict = if (reopen) """DO UPDATE SET wrong_text=excluded.wrong_text,wrong_guessed=excluded.wrong_guessed,
            prompt=excluded.prompt,reference=excluded.reference,solution=excluded.solution,options=excluded.options,
            answer_text='',revealed=0,understood=NULL,answer_guessed=0,completed=0 WHERE book_reviews.completed=1""" else "DO NOTHING"
        db.prepareStatement("""INSERT INTO book_reviews(book,lesson,mode,book_title,lesson_title,prompt,reference,solution,options,wrong_text,wrong_guessed,completed)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(book,lesson,mode) $conflict""").use { sql ->
            sql.setString(1, book.id); sql.setString(2, lesson.id); sql.setString(3, mode.name)
            sql.setString(4, book.title); sql.setString(5, lesson.title); sql.setString(6, practice.prompt)
            sql.setString(7, practice.reference); sql.setString(8, practice.answer)
            sql.setString(9, Json.encodeToString(practice.options)); sql.setString(10, answer.text)
            sql.setInt(11, if (answer.guessed) 1 else 0)
            sql.setInt(12, if (queue) 0 else 1)
            sql.executeUpdate()
        }
    }

    private fun java.sql.ResultSet.asReview(day: Long = reviewDay()): LocalBookReview {
        val stored = LocalBookReview(
        getString("book"), getString("lesson"), QuestionMode.valueOf(getString("mode")),
        getString("book_title"), getString("lesson_title"),
        Practice(getString("prompt"), getString("reference"), getString("solution"), Json.decodeFromString(getString("options"))),
        getString("wrong_text"), getInt("wrong_guessed") == 1,
        PracticeAnswer(getString("answer_text"), getInt("revealed") == 1,
            getInt("understood").let { if (wasNull()) null else it == 1 }, getInt("answer_guessed") == 1), getInt("completed") == 1,
        )
        val due = getLong("due_day").let { if (wasNull()) null else it }
        val projected = stored.copy(dueDay = due)
        // Keep the prior response on disk until a new response is saved. Read-only projection
        // reopens a completed original only when due, with a fresh response rather than its old key.
        return if (stored.completed && due != null && due <= day)
            projected.copy(answer = PracticeAnswer(), completed = false) else projected
    }

    @Synchronized fun reviews(): List<LocalBookReview> = reviewRows(availableOnly = true)

    /** Includes unavailable rows for revalidation; original responses and due dates stay untouched. */
    @Synchronized fun reviewCandidates(): List<LocalBookReview> = reviewRows(availableOnly = false)

    private fun reviewRows(availableOnly: Boolean): List<LocalBookReview> = database().use { db ->
        val day = reviewDay()
        db.prepareStatement("SELECT * FROM book_reviews WHERE (completed=0 OR due_day<=?) AND (?=0 OR source_available=1) ORDER BY rowid").use { sql ->
            sql.setLong(1, day)
            sql.setInt(2, if (availableOnly) 1 else 0)
            sql.executeQuery().use { rows ->
                buildList { while (rows.next()) add(variants.project(db, rows.asReview(day))) }
            }
        }
    }.filterNot { Files.exists(removedMarker(it.book)) }

    @Synchronized fun isReviewSourceAvailable(review: LocalBookReview): Boolean = database().use { db ->
        db.prepareStatement("SELECT source_available FROM book_reviews WHERE book=? AND lesson=? AND mode=?").use { sql ->
            sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
            sql.executeQuery().use { rows -> rows.next() && rows.getInt(1) == 1 }
        }
    }

    @Synchronized fun reviewSourceAvailable(review: LocalBookReview, available: Boolean) = database().use { db ->
        db.autoCommit = false
        try {
            db.prepareStatement("UPDATE book_reviews SET source_available=? WHERE book=? AND lesson=? AND mode=?").use { sql ->
                sql.setInt(1, if (available) 1 else 0); sql.setString(2, review.book)
                sql.setString(3, review.lesson); sql.setString(4, review.mode.name); sql.executeUpdate()
            }
            if (!available) db.prepareStatement("DELETE FROM book_review_position WHERE book=? AND lesson=? AND mode=?").use { sql ->
                sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name); sql.executeUpdate()
            }
            db.commit()
        } catch (failure: Exception) { db.rollback(); throw failure }
    }

    @Synchronized fun review(book: String, lesson: String, mode: QuestionMode): LocalBookReview = database().use { db ->
        db.prepareStatement("SELECT * FROM book_reviews WHERE book=? AND lesson=? AND mode=?").use { sql ->
            sql.setString(1, book); sql.setString(2, lesson); sql.setString(3, mode.name)
            sql.executeQuery().use { rows -> check(rows.next()) { "这道复习题已不在书架中。" }; variants.project(db, rows.asReview()) }
        }
    }

    /** Null means no known deadline, including migrated historical rows. UI authors may consume this fact. */
    @Synchronized fun reviewDueDay(review: LocalBookReview): Long? = database().use { db ->
        schedule(db, review)?.dueDay
    }

    private fun schedule(db: Connection, review: LocalBookReview): Review? =
        db.prepareStatement("SELECT due_day,interval,ease,anchor FROM book_reviews WHERE book=? AND lesson=? AND mode=?").use { sql ->
            sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
            sql.executeQuery().use { row ->
                check(row.next()) { "这道复习题已不在书架中。" }
                val due = row.getLong(1).let { if (row.wasNull()) null else it }
                val interval = row.getInt(2).let { if (row.wasNull()) null else it }
                val ease = row.getDouble(3).let { if (row.wasNull()) null else it }
                val anchor = row.getLong(4).let { if (row.wasNull()) null else it }
                if (due == null || interval == null || ease == null) null else Review(due, interval, ease, lastReviewDay = anchor)
            }
        }

    @Synchronized fun saveReviewAnswer(review: LocalBookReview, answer: PracticeAnswer) {
        val completed = answer.revealed && when (review.mode) {
            // A guessed correct choice is not mastery: the row stays queued instead of completing.
            QuestionMode.CHOICE -> !answer.guessed && answer.text == review.practice.answer
            QuestionMode.SHORT_ANSWER -> answer.understood == true
            QuestionMode.NONE -> false
        }
        database().use { db ->
            db.autoCommit = false
            try {
                variants.save(db, review, answer)
                val day = reviewDay()
                val current = db.prepareStatement("SELECT * FROM book_reviews WHERE book=? AND lesson=? AND mode=?").use { sql ->
                    sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
                    sql.executeQuery().use { row -> check(row.next()); row.asReview(day) }
                }
                val assessed = answer.revealed && when (review.mode) {
                    QuestionMode.CHOICE -> answer.text.isNotBlank()
                    QuestionMode.SHORT_ANSWER -> answer.understood != null
                    QuestionMode.NONE -> false
                }
                // Draft/reveal writes and an identical saved response are not additional assessments.
                if (assessed && (!current.answer.revealed || current.answer != answer)) {
                    val result = if (completed) "PASS" else if (review.mode == QuestionMode.CHOICE && answer.text == review.practice.answer) "NEEDS_WORK" else "FAIL"
                    val next = nextReview(schedule(db, review) ?: initialReview(day), day, result)
                    db.prepareStatement("UPDATE book_reviews SET due_day=?,interval=?,ease=?,anchor=? WHERE book=? AND lesson=? AND mode=?").use { sql ->
                        sql.setLong(1, next.dueDay); sql.setInt(2, next.intervalDays); sql.setDouble(3, next.ease)
                        if (next.lastReviewDay == null) sql.setNull(4, java.sql.Types.INTEGER) else sql.setLong(4, next.lastReviewDay!!)
                        sql.setString(5, review.book); sql.setString(6, review.lesson); sql.setString(7, review.mode.name); sql.executeUpdate()
                    }
                }
                db.prepareStatement("""UPDATE book_reviews SET answer_text=?,revealed=?,understood=?,answer_guessed=?,completed=?
                    WHERE book=? AND lesson=? AND mode=?""").use { sql ->
                    sql.setString(1, answer.text); sql.setInt(2, if (answer.revealed) 1 else 0)
                    if (answer.understood == null) sql.setNull(3, java.sql.Types.INTEGER) else sql.setInt(3, if (answer.understood) 1 else 0)
                    sql.setInt(4, if (answer.guessed) 1 else 0)
                    sql.setInt(5, if (completed) 1 else 0)
                    sql.setString(6, review.book); sql.setString(7, review.lesson); sql.setString(8, review.mode.name)
                    check(sql.executeUpdate() == 1) { "无法保存复习作答。" }
                }
                db.commit()
            } catch (failure: Exception) { db.rollback(); throw failure }
        }
    }

    @Synchronized fun startReviewVariant(review: LocalBookReview): LocalBookReview = database().use { db ->
        db.autoCommit = false
        try {
            val current = db.prepareStatement("SELECT * FROM book_reviews WHERE book=? AND lesson=? AND mode=?").use { sql ->
                sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
                sql.executeQuery().use { row -> check(row.next()); row.asReview() }
            }
            require(!current.completed) { "这题已复习。" }
            val result = variants.start(db, current)
            db.commit()
            result
        } catch (failure: Exception) { db.rollback(); throw failure }
    }

    /** Explicit approved input from a source owner; never manufactures IDs for generated Practice. */
    @Synchronized fun updateReviewVariantSource(review: LocalBookReview, update: BookReviewSourceUpdate) {
        database().use { db ->
            db.autoCommit = false
            try {
                val current = db.prepareStatement("SELECT * FROM book_reviews WHERE book=? AND lesson=? AND mode=?").use { sql ->
                    sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
                    sql.executeQuery().use { row -> check(row.next()); row.asReview() }
                }
                update.input?.let {
                    requireNotNull(sourceAuthority) { "来源身份未核验，新变式未备好。" }.verify(it)
                    variants.validateSource(db, current, it)
                }
                sources.update(db, current, update)
                db.commit()
            } catch (failure: Exception) { db.rollback(); throw failure }
        }
    }

    @Synchronized fun activeReview(): LocalBookReview? = database().use { db ->
        db.createStatement().use { sql -> sql.executeQuery("""SELECT r.* FROM book_reviews r JOIN book_review_position p
            ON r.book=p.book AND r.lesson=p.lesson AND r.mode=p.mode WHERE p.slot=1 AND r.source_available=1""").use { rows ->
            if (rows.next()) variants.project(db, rows.asReview()) else null
        } }
    }?.takeUnless { Files.exists(removedMarker(it.book)) }

    @Synchronized fun openReview(review: LocalBookReview) {
        database().use { db -> db.prepareStatement("INSERT OR REPLACE INTO book_review_position(slot,book,lesson,mode) VALUES(1,?,?,?)").use { sql ->
            sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
            sql.executeUpdate()
        } }
    }

    @Synchronized fun closeReview() {
        database().use { db -> db.createStatement().use { it.executeUpdate("DELETE FROM book_review_position") } }
    }

    fun renderPdf(book: Textbook, page: Int): BufferedImage {
        require(book.format == "pdf" && page in 1..book.pages.size)
        return Loader.loadPDF(original(book.id, book.format).toFile()).use { pdf ->
            val bounds = pdf.getPage(page - 1).cropBox
            val scale = minOf(1.5f, 1800f / maxOf(bounds.width, bounds.height)).coerceAtLeast(.05f)
            PDFRenderer(pdf).renderImage(page - 1, scale)
        }
    }
}

internal data class ExtractedBook(val title: String, val pages: List<BookPage>)

internal object TextbookExtraction {
    /**
     * A textbook export already carries its true PDF pages. Those marker numbers are the only page
     * identity a ledger source can name, so the page text and its UTF-16 offsets come from the one
     * contract the server seeds with: `com.coach.domain.textbookPagesFromMarkers`. A length-based
     * re-chunk of the same file would invent page numbers the server does not have.
     */
    fun extract(path: Path, format: String, title: String): ExtractedBook = when (format) {
        "pdf" -> Loader.loadPDF(path.toFile()).use { pdf ->
            require(pdf.numberOfPages in 1..5000) { "这本 PDF 的页数暂不支持。" }
            require(pdf.currentAccessPermission.canExtractContent()) { "这本 PDF 限制了文字提取，请使用有阅读和提取权限的教材。" }
            val stripper = PDFTextStripper().apply { sortByPosition = true; paragraphEnd = "\n\n" }
            val pages = (1..pdf.numberOfPages).map { n ->
                stripper.startPage = n; stripper.endPage = n
                BookPage(n, clean(stripper.getText(pdf)))
            }
            ExtractedBook(pdf.documentInformation.title?.trim()?.takeIf(String::isNotEmpty) ?: title, pages)
        }
        "epub" -> epub(path, title)
        else -> {
            require(Files.size(path) <= 32L * 1024 * 1024) { "文本教材请控制在 32 MB 以内。" }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            val text = decoder.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path))).toString().removePrefix("\uFEFF")
            require(text.isNotBlank()) { "教材中没有文字。" }
            // Pages come from the export's own markers, byte-for-byte, so the page number and
            // UTF-16 offsets a question cites are the server's, not a local chunk's.
            val marked = textbookPagesFromMarkers(text)
            ExtractedBook(title, marked.map { BookPage(it.pdfPage, it.text) }.ifEmpty { paginate(clean(text)) })
        }
    }

    private fun clean(text: String) = text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
    private fun paginate(text: String): List<BookPage> {
        val chunks = mutableListOf<String>()
        for (section in text.split('\u000c')) {
            var rest = section
            if (rest.isEmpty()) { chunks += ""; continue }
            while (rest.isNotEmpty()) {
                var end = minOf(rest.length, 4000)
                if (end < rest.length) {
                    val boundary = rest.lastIndexOf('\n', end)
                    if (boundary >= 2000) end = boundary + 1
                    if (Character.isHighSurrogate(rest[end - 1])) end--
                }
                chunks += rest.take(end); rest = rest.drop(end)
            }
        }
        return chunks.mapIndexed { index, content -> BookPage(index + 1, content) }
    }

    private fun epub(path: Path, fallback: String): ExtractedBook = ZipFile(path.toFile()).use { zip ->
        fun read(name: String): ByteArray {
            require(!name.startsWith('/') && name.split('/').none { it == ".." } && '\\' !in name) { "EPUB 内含无效的文件位置。" }
            val entry = requireNotNull(zip.getEntry(name)) { "EPUB 缺少阅读顺序中的正文。" }
            require(entry.size in 0..(16L * 1024 * 1024)) { "EPUB 的单个章节过大。" }
            return zip.getInputStream(entry).use { input ->
                val bytes = input.readNBytes(16 * 1024 * 1024 + 1)
                require(bytes.size <= 16 * 1024 * 1024) { "EPUB 的单个章节过大。" }; bytes
            }
        }
        fun xml(bytes: ByteArray): Element {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false; isExpandEntityReferences = false
            }
            return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        }
        fun Element.all(name: String): List<Element> = getElementsByTagNameNS("*", name).let { list -> (0 until list.length).map { list.item(it) as Element } }
        val container = xml(read("META-INF/container.xml"))
        val packagePath = container.all("rootfile").firstOrNull()?.getAttribute("full-path") ?: error("EPUB 缺少内容目录。")
        val opf = xml(read(packagePath))
        val manifest = opf.all("item").associate { it.getAttribute("id") to it.getAttribute("href") }
        val spine = opf.all("itemref").filter { it.getAttribute("linear") != "no" }
        require(spine.size in 1..5000) { "EPUB 没有可读正文，或章节太多。" }
        val root = java.net.URI("/$packagePath")
        var totalBytes = 0L
        val pages = spine.flatMap { item ->
            val href = requireNotNull(manifest[item.getAttribute("idref")]) { "EPUB 阅读顺序不完整。" }
            val resolved = root.resolve(href).normalize()
            require(resolved.scheme == null && resolved.host == null && resolved.fragment == null) { "EPUB 的正文必须包含在书内。" }
            val bytes = read(resolved.path.removePrefix("/"))
            totalBytes += bytes.size
            require(totalBytes <= 64L * 1024 * 1024) { "EPUB 的正文过大。" }
            val doc = xml(bytes)
            val output = StringBuilder()
            fun visit(node: Node) {
                if (node.nodeType == Node.TEXT_NODE) { output.append(node.nodeValue); return }
                val name = node.localName?.lowercase().orEmpty()
                if (name in setOf("script", "style", "head")) return
                val block = name in setOf("p", "div", "li", "h1", "h2", "h3", "h4", "section", "br", "tr")
                if (block) output.append("\n\n")
                if (name.matches(Regex("h[1-6]"))) output.append("# ")
                for (i in 0 until node.childNodes.length) visit(node.childNodes.item(i))
                if (block) output.append("\n\n")
            }
            visit(doc)
            paginate(clean(output.toString()).trim())
        }.mapIndexed { index, page -> page.copy(number = index + 1) }
        require(pages.any { it.text.isNotBlank() }) { "EPUB 中没有可读取的文字正文。" }
        ExtractedBook(opf.all("title").firstOrNull()?.textContent?.trim()?.takeIf(String::isNotEmpty) ?: fallback, pages)
    }
}
