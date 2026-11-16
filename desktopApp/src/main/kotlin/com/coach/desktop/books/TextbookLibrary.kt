package com.coach.desktop.books

import com.coach.domain.LadderQuestion
import com.coach.domain.LadderSession
import com.coach.domain.LedgerJson
import com.coach.domain.QuestionLadder
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
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** All state belongs to this Windows library. No server, course ID, token or user corpus is opened. */
internal class TextbookLibrary(val directory: Path) {
    init {
        Files.createDirectories(directory)
        PdfWarmup.start()
        database().use { db -> db.createStatement().use { sql ->
            sql.execute("CREATE TABLE IF NOT EXISTS books(id TEXT PRIMARY KEY,title TEXT NOT NULL,format TEXT NOT NULL,reading_page INTEGER NOT NULL DEFAULT 1,lesson_index INTEGER NOT NULL DEFAULT 0,learning INTEGER NOT NULL DEFAULT 0,prompt_seen INTEGER NOT NULL DEFAULT 0,mode TEXT NOT NULL DEFAULT 'NONE',touched INTEGER NOT NULL)")
            sql.execute("CREATE TABLE IF NOT EXISTS pages(book TEXT NOT NULL,page INTEGER NOT NULL,text TEXT NOT NULL,PRIMARY KEY(book,page))")
            sql.execute("CREATE TABLE IF NOT EXISTS answers(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,PRIMARY KEY(book,lesson,mode))")
            sql.execute("""CREATE TABLE IF NOT EXISTS book_reviews(
                book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,book_title TEXT NOT NULL,lesson_title TEXT NOT NULL,
                prompt TEXT NOT NULL,reference TEXT NOT NULL,solution TEXT NOT NULL,options TEXT NOT NULL,wrong_text TEXT NOT NULL,
                answer_text TEXT NOT NULL DEFAULT '',revealed INTEGER NOT NULL DEFAULT 0,understood INTEGER,
                completed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))""")
            sql.execute("CREATE TABLE IF NOT EXISTS book_review_position(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL)")
            sql.execute("CREATE TABLE IF NOT EXISTS book_learning_paths(book TEXT PRIMARY KEY,path TEXT NOT NULL CHECK(path IN ('READ','READ_AND_LEARN','QUESTION_FIRST')))")
            sql.execute("CREATE TABLE IF NOT EXISTS book_ladders(book TEXT PRIMARY KEY,session TEXT NOT NULL)")
        } }
        backfillReviews()
    }
    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:${directory.resolve("library.sqlite")}").apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    @Synchronized fun catalog(): List<BookSummary> = database().use { db ->
        db.createStatement().use { sql -> sql.executeQuery("SELECT b.*, (SELECT count(*) FROM pages p WHERE p.book=b.id) AS page_count, (SELECT max(p.page) FROM pages p WHERE p.book=b.id) AS last_page FROM books b ORDER BY touched DESC,id").use { rows -> buildList {
            while (rows.next()) add(BookSummary(rows.getString("id"), rows.getString("title"), rows.getInt("page_count"), rows.getInt("last_page"), rows.getInt("reading_page"), rows.getInt("learning") == 1))
        } } }
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

    /** 教材入口和主线共用同一个题梯会话；一行 JSON 按书保存在本机。 */
    @Synchronized fun ladder(book: String): LadderSession = database().use { db ->
        db.prepareStatement("SELECT session FROM book_ladders WHERE book=?").use { sql ->
            sql.setString(1, book)
            sql.executeQuery().use { row ->
                if (!row.next()) LadderSession()
                else runCatching { LedgerJson.decodeFromString(LadderSession.serializer(), row.getString(1)) }.getOrNull() ?: LadderSession()
            }
        }
    }

    @Synchronized fun saveLadder(book: String, session: LadderSession) {
        database().use { db -> saveLadderRow(db, book, session) }
    }

    private fun saveLadderRow(db: Connection, book: String, session: LadderSession) {
        db.prepareStatement("INSERT INTO book_ladders(book,session) VALUES(?,?) ON CONFLICT(book) DO UPDATE SET session=excluded.session").use { sql ->
            sql.setString(1, book); sql.setString(2, LedgerJson.encodeToString(LadderSession.serializer(), session))
            sql.executeUpdate()
        }
    }

    @Synchronized fun book(id: String): Textbook = database().use { db ->
        db.prepareStatement("SELECT * FROM books WHERE id=?").use { sql ->
            sql.setString(1, id)
            sql.executeQuery().use { row ->
                check(row.next()) { "这本教材已不在书架中。" }
                val pages = db.prepareStatement("SELECT page,text FROM pages WHERE book=? ORDER BY page").use { query ->
                    query.setString(1, id)
                    query.executeQuery().use { p -> buildList { while (p.next()) add(BookPage(p.getInt(1), p.getString(2))) } }
                }
                val storedPage = row.getInt("reading_page")
                Textbook(id, row.getString("title"), row.getString("format"), pages, if (pages.any { it.number == storedPage }) storedPage else pages.first().number,
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
        db.prepareStatement("SELECT text,revealed,understood FROM answers WHERE book=? AND lesson=? AND mode=?").use { sql ->
            sql.setString(1, book); sql.setString(2, lesson); sql.setString(3, mode.name)
            sql.executeQuery().use { row ->
                if (!row.next()) PracticeAnswer() else {
                    val understood = row.getInt(3).let { if (row.wasNull()) null else it == 1 }
                    PracticeAnswer(row.getString(1), row.getInt(2) == 1, understood)
                }
            }
        }
    }

    @Synchronized fun saveAnswer(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer) {
        // 「这一节做过一题」和「这一题要加入复习」是两道判断：选择题选了选项、简答题明确自检过，
        // 都算做过一次、进题梯；简答点「我已理解」还是「还需复习」都算。复习只收答错的，规则见 needsReview。
        val answered = answer.revealed && (mode == QuestionMode.CHOICE && answer.text.isNotBlank() ||
            mode == QuestionMode.SHORT_ANSWER && answer.understood != null)
        val source = if (answered) book(book) else null
        val section = source?.lessons?.find { it.id == lesson }
        val practice = section?.let { StudyCoachCourse.practice(checkNotNull(source), it, mode) }
        // 题梯会话和作答写在同一个事务里：读到一半的会话不会和刚保存的答案对不上。
        // 会话按「这本书 + 当前走法」累计，只在「做题学」里记，别的走法不改这张表。
        val ladder = if (source != null && section != null && practice != null)
            nextLadderSession(checkNotNull(source), section, mode, practice, answer) else null
        database().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("INSERT OR REPLACE INTO answers(book,lesson,mode,text,revealed,understood) VALUES(?,?,?,?,?,?)").use { sql ->
                    sql.setString(1, book); sql.setString(2, lesson); sql.setString(3, mode.name); sql.setString(4, answer.text)
                    sql.setInt(5, if (answer.revealed) 1 else 0)
                    if (answer.understood == null) sql.setNull(6, java.sql.Types.INTEGER) else sql.setInt(6, if (answer.understood) 1 else 0)
                    sql.executeUpdate()
                }
                if (practice != null && practice.needsReview(mode, answer)) {
                    recordReview(db, checkNotNull(source), checkNotNull(section), mode, practice, answer, reopen = true)
                }
                if (ladder != null) saveLadderRow(db, book, ladder)
                db.commit()
            } catch (failure: Exception) { db.rollback(); throw failure }
        }
    }

    /**
     * 教材这一节就是一道题：题的编号带上书、节和出题方式，重答同一节不会重复累计。
     * 小结的内容取本节原文里出题用的那一句，所以「记住的」是知识，不是你选了什么。
     */
    private fun nextLadderSession(book: Textbook, lesson: BookLesson, mode: QuestionMode,
        practice: Practice, answer: PracticeAnswer): LadderSession? {
        if (learningPath(book.id) != LearningPath.QUESTION_FIRST.name) return null
        val session = ladder(book.id)
        val question = LadderQuestion(
            id = "${book.id}:${lesson.id}:${mode.name}", kpId = lesson.id, sectionId = lesson.id,
            kind = "concept", difficulty = 1, variantGroupId = lesson.id,
            fingerprint = "${book.id}:${lesson.id}:${mode.name}", eligible = true,
        )
        if (session.responses.any { it.id == question.id }) return null
        val correct = if (mode == QuestionMode.SHORT_ANSWER) answer.understood == true else answer.text.trim() == practice.answer
        return QuestionLadder.record(session, question, correct, knowledgeLine(lesson, practice))
    }

    private fun knowledgeLine(lesson: BookLesson, practice: Practice): String {
        val sentence = practice.reference.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: lesson.title
        val capped = if (sentence.length > 90) sentence.take(88).trimEnd('，', ',', '；', ';', '、', ' ') + "…" else sentence
        return "${lesson.title}：$capped"
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
        for ((id, lessonId, mode) in old) {
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
        practice: Practice, answer: PracticeAnswer, reopen: Boolean) {
        val conflict = if (reopen) """DO UPDATE SET wrong_text=excluded.wrong_text,
            prompt=excluded.prompt,reference=excluded.reference,solution=excluded.solution,options=excluded.options,
            answer_text='',revealed=0,understood=NULL,completed=0 WHERE book_reviews.completed=1""" else "DO NOTHING"
        db.prepareStatement("""INSERT INTO book_reviews(book,lesson,mode,book_title,lesson_title,prompt,reference,solution,options,wrong_text)
            VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(book,lesson,mode) $conflict""").use { sql ->
            sql.setString(1, book.id); sql.setString(2, lesson.id); sql.setString(3, mode.name)
            sql.setString(4, book.title); sql.setString(5, lesson.title); sql.setString(6, practice.prompt)
            sql.setString(7, practice.reference); sql.setString(8, practice.answer)
            sql.setString(9, Json.encodeToString(practice.options)); sql.setString(10, answer.text)
            sql.executeUpdate()
        }
    }

    private fun java.sql.ResultSet.asReview(): LocalBookReview = LocalBookReview(
        getString("book"), getString("lesson"), QuestionMode.valueOf(getString("mode")),
        getString("book_title"), getString("lesson_title"),
        Practice(getString("prompt"), getString("reference"), getString("solution"), Json.decodeFromString(getString("options"))),
        getString("wrong_text"),
        PracticeAnswer(getString("answer_text"), getInt("revealed") == 1,
            getInt("understood").let { if (wasNull()) null else it == 1 }), getInt("completed") == 1,
    )

    @Synchronized fun reviews(): List<LocalBookReview> = database().use { db ->
        db.createStatement().use { sql ->
            sql.executeQuery("SELECT * FROM book_reviews WHERE completed=0 ORDER BY rowid").use { rows ->
                buildList { while (rows.next()) add(rows.asReview()) }
            }
        }
    }

    @Synchronized fun review(book: String, lesson: String, mode: QuestionMode): LocalBookReview = database().use { db ->
        db.prepareStatement("SELECT * FROM book_reviews WHERE book=? AND lesson=? AND mode=?").use { sql ->
            sql.setString(1, book); sql.setString(2, lesson); sql.setString(3, mode.name)
            sql.executeQuery().use { rows -> check(rows.next()) { "这道复习题已不在书架中。" }; rows.asReview() }
        }
    }

    @Synchronized fun saveReviewAnswer(review: LocalBookReview, answer: PracticeAnswer) {
        val completed = answer.revealed && when (review.mode) {
            QuestionMode.CHOICE -> answer.text == review.practice.answer
            QuestionMode.SHORT_ANSWER -> answer.understood == true
            QuestionMode.NONE -> false
        }
        database().use { db ->
            db.prepareStatement("""UPDATE book_reviews SET answer_text=?,revealed=?,understood=?,completed=?
                WHERE book=? AND lesson=? AND mode=?""").use { sql ->
                sql.setString(1, answer.text); sql.setInt(2, if (answer.revealed) 1 else 0)
                if (answer.understood == null) sql.setNull(3, java.sql.Types.INTEGER) else sql.setInt(3, if (answer.understood) 1 else 0)
                sql.setInt(4, if (completed) 1 else 0)
                sql.setString(5, review.book); sql.setString(6, review.lesson); sql.setString(7, review.mode.name)
                check(sql.executeUpdate() == 1) { "无法保存复习作答。" }
            }
        }
    }

    @Synchronized fun activeReview(): LocalBookReview? = database().use { db ->
        db.createStatement().use { sql -> sql.executeQuery("""SELECT r.* FROM book_reviews r JOIN book_review_position p
            ON r.book=p.book AND r.lesson=p.lesson AND r.mode=p.mode WHERE p.slot=1""").use { rows ->
            if (rows.next()) rows.asReview() else null
        } }
    }

    @Synchronized fun openReview(review: LocalBookReview) {
        database().use { db -> db.prepareStatement("INSERT OR REPLACE INTO book_review_position(slot,book,lesson,mode) VALUES(1,?,?,?)").use { sql ->
            sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
            sql.executeUpdate()
        } }
    }

    @Synchronized fun closeReview() {
        database().use { db -> db.createStatement().use { it.executeUpdate("DELETE FROM book_review_position") } }
    }

    // ---- 页图渲染：同一页只渲染一次（并发调用合并、结果缓存），每次调用留下分段计时 ----
    private data class RenderKey(val id: String, val page: Int, val stamp: Long)
    private val renderLock = Any()
    private val renderCache = object : LinkedHashMap<RenderKey, BufferedImage>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RenderKey, BufferedImage>) = size > 6
    }
    private val renderInflight = HashMap<RenderKey, java.util.concurrent.CompletableFuture<BufferedImage>>()
    private val traces = java.util.concurrent.ConcurrentLinkedQueue<RenderTrace>()

    /** 最近的渲染记录（最多 64 条）：outcome = render（真正渲染）/ joined（并发时等同一次渲染）/ hit（缓存命中）。 */
    fun renderTraces(): List<RenderTrace> = traces.toList()

    private fun record(t: RenderTrace) {
        traces.add(t); while (traces.size > 64) traces.poll()
        if (java.lang.Boolean.getBoolean("coach.render.trace")) println("COACH_RENDER_TRACE " + t)
    }

    /**
     * 返回的图片是只读共享的（调用方只转换/编码，不修改）。渲染分三段计时：loadPDF / 取页框 / renderImage，另记总耗时。
     * 同一本书同一页（文件大小+修改时间一致）并发调用只会真正渲染一次，其余等同一结果；失败不缓存。
     */
    fun renderPdf(book: Textbook, page: Int): BufferedImage {
        require(book.format == "pdf" && page in 1..book.pages.size)
        val file = original(book.id, book.format).toFile()
        val key = RenderKey(book.id, page, file.length() xor file.lastModified())
        val started = System.nanoTime()
        var owner = false
        val future: java.util.concurrent.CompletableFuture<BufferedImage>
        synchronized(renderLock) {
            renderCache[key]?.let { record(RenderTrace(book.id, page, "hit", 0.0, 0.0, 0.0, (System.nanoTime() - started) / 1_000_000.0, Thread.currentThread().name)); return it }
            val running = renderInflight[key]
            if (running != null) future = running else { future = java.util.concurrent.CompletableFuture(); renderInflight[key] = future; owner = true }
        }
        if (!owner) {
            val img = try { future.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
            record(RenderTrace(book.id, page, "joined", 0.0, 0.0, 0.0, (System.nanoTime() - started) / 1_000_000.0, Thread.currentThread().name))
            return img
        }
        try {
            val t0 = System.nanoTime()
            val image = Loader.loadPDF(file).use { pdf ->
                val t1 = System.nanoTime()
                val bounds = pdf.getPage(page - 1).cropBox
                val scale = minOf(1.5f, 1800f / maxOf(bounds.width, bounds.height)).coerceAtLeast(.05f)
                val t2 = System.nanoTime()
                val img = PDFRenderer(pdf).renderImage(page - 1, scale)
                val t3 = System.nanoTime()
                record(RenderTrace(book.id, page, "render", (t1 - t0) / 1_000_000.0, (t2 - t1) / 1_000_000.0, (t3 - t2) / 1_000_000.0, (t3 - started) / 1_000_000.0, Thread.currentThread().name))
                img
            }
            synchronized(renderLock) { renderCache[key] = image; renderInflight.remove(key) }
            future.complete(image)
            return image
        } catch (e: Throwable) {
            synchronized(renderLock) { renderInflight.remove(key) }
            future.completeExceptionally(e)
            throw e
        }
    }
}

/**
 * 进程内只做一次的 PDF 渲染预热：用内存里生成的一页带字 PDF 走一遍 loadPDF → renderImage，
 * 把 PDFBox 类加载、字体缓存和 JIT 的冷启动代价挪到后台（打开书本/阅读器时就开始），不占目标页的首次绘制。
 * 只在内存里造 PDF，不读用户文件；失败静默（预热不是功能）。[done] 返回预热耗时毫秒，失败为 -1。
 */
internal object PdfWarmup {
    val done = java.util.concurrent.CompletableFuture<Long>()
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            val t0 = System.nanoTime()
            val ms = try {
                val out = java.io.ByteArrayOutputStream()
                org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
                    val page = org.apache.pdfbox.pdmodel.PDPage(); doc.addPage(page)
                    org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                        cs.beginText()
                        cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 14f)
                        cs.newLineAtOffset(72f, 700f); cs.showText("Warm up 0123456789"); cs.endText()
                    }
                    doc.save(out)
                }
                Loader.loadPDF(out.toByteArray()).use { pdf -> PDFRenderer(pdf).renderImage(0, 1.5f) }
                (System.nanoTime() - t0) / 1_000_000
            } catch (_: Throwable) { -1L }
            if (java.lang.Boolean.getBoolean("coach.render.trace")) println("COACH_RENDER_WARMUP ms=$ms")
            done.complete(ms)
        }, "pdf-warmup").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
    }
}

/** 一次页图渲染的分段计时（毫秒）。 */
internal data class RenderTrace(val book: String, val page: Int, val outcome: String, val loadMs: Double, val pageBoxMs: Double, val renderMs: Double, val totalMs: Double, val thread: String)

internal data class ExtractedBook(val title: String, val pages: List<BookPage>)

internal object TextbookExtraction {
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
            val markers = Regex("<!--\\s*PDF 第 (\\d+) 页 · 书第 (.*?) 页\\s*-->").findAll(text).toList()
            val marked = markers.mapIndexed { index, marker ->
                BookPage(marker.groupValues[1].toInt(), text.substring(marker.range.first, markers.getOrNull(index + 1)?.range?.first ?: text.length))
            }
            require(marked.all { it.number > 0 } && marked.map { it.number }.distinct().size == marked.size) { "教材里的 PDF 页标记不完整或重复，无法确认页码。" }
            ExtractedBook(title, marked.ifEmpty {
                val cleaned = clean(text)
                require(cleaned.isNotBlank()) { "教材中没有文字。" }
                paginate(cleaned)
            })
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
