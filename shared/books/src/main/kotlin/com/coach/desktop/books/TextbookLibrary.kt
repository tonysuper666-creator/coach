package com.coach.desktop.books

import com.coach.domain.LadderQuestion
import com.coach.domain.LadderSession
import com.coach.domain.LedgerJson
import com.coach.domain.QuestionLadder
import com.coach.domain.reader.ReaderTextRange
import com.coach.domain.reader.ReaderTextUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** All state belongs to this Windows library. No server, course ID, token or user corpus is opened. */
internal class TextbookLibrary(
    val directory: Path, private val clock: Clock = Clock.systemDefaultZone(), private val ocr: BookOcr = BundledBookOcr,
    private val extractBook: (Path, String, String, BookOcr, BookImportControl) -> ExtractedBook = TextbookExtraction::extract,
) {
    private val reviewStore = TextbookReviewStore { LocalDate.now(clock).toEpochDay() }
    private class PendingTextView(val textView: Boolean)
    private val pendingTextViews = ConcurrentHashMap<String, PendingTextView>()
    private val textViewWrites = Mutex()
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
            sql.execute("CREATE TABLE IF NOT EXISTS book_reader_views(book TEXT PRIMARY KEY,text_view INTEGER NOT NULL)")
            sql.execute("CREATE TABLE IF NOT EXISTS book_pdf_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)")
            sql.execute("CREATE TABLE IF NOT EXISTS book_text_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)")
            sql.execute("CREATE TABLE IF NOT EXISTS book_read_spots(book TEXT PRIMARY KEY,page INTEGER NOT NULL,offset_utf16 INTEGER NOT NULL)")
            sql.execute("""CREATE TABLE IF NOT EXISTS book_highlights(
                book TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,
                PRIMARY KEY(book,page,start,end))""")
        } }
        database().use { db ->
            val pageColumns = db.createStatement().use { sql -> sql.executeQuery("PRAGMA table_info(pages)").use { rows ->
                buildSet { while (rows.next()) add(rows.getString("name")) }
            } }
            if ("ocr" !in pageColumns) db.createStatement().use { it.execute("ALTER TABLE pages ADD COLUMN ocr INTEGER NOT NULL DEFAULT 0") }
            if ("ocr_pending" !in pageColumns) db.createStatement().use { it.execute("ALTER TABLE pages ADD COLUMN ocr_pending INTEGER NOT NULL DEFAULT 0") }
            reviewStore.migrate(db)
            BookPageModelStore.migrate(db)
            BookNativeSourceStore.migrate(db)
        }
        backfillReviews()
    }
    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:${directory.resolve("library.sqlite")}").apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    @Synchronized fun catalog(): List<BookSummary> = database().use { db ->
        db.createStatement().use { sql -> sql.executeQuery("""SELECT b.*,
            (SELECT count(*) FROM pages p WHERE p.book=b.id) AS page_count,
            (SELECT max(p.page) FROM pages p WHERE p.book=b.id) AS last_page,
            (SELECT count(*) FROM pages p WHERE p.book=b.id AND p.ocr_pending=1) AS pending_ocr
            FROM books b ORDER BY touched DESC,id""").use { rows -> buildList {
            while (rows.next()) add(BookSummary(
                rows.getString("id"), rows.getString("title"),
                rows.getInt("page_count"), rows.getInt("last_page"),
                rows.getInt("reading_page"), rows.getInt("learning") == 1,
                rows.getInt("pending_ocr"),
            ))
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
                val extras = BookPageModelStore.loadExtrasByPage(db, id, includeTextLayouts = true)
                val pages = db.prepareStatement("SELECT page,text,ocr,ocr_pending FROM pages WHERE book=? ORDER BY page").use { query ->
                    query.setString(1, id)
                    query.executeQuery().use { p -> buildList { while (p.next()) {
                        val number = p.getInt(1)
                        val extra = extras[number]
                        val text = p.getString(2)
                        val illustrations = extra?.illustrations.orEmpty().map { item ->
                            if (item.table?.valid(item.height, text) == false) item.copy(table = null) else item
                        }
                        add(BookPage(number, text, p.getInt(3) == 1, illustrations, extra?.textLayout, ocrPending = p.getInt(4) == 1))
                    } } }
                }
                val storedPage = row.getInt("reading_page")
                Textbook(id, row.getString("title"), row.getString("format"), pages, if (pages.any { it.number == storedPage }) storedPage else pages.first().number,
                    row.getInt("lesson_index"), row.getInt("learning") == 1, row.getInt("prompt_seen") == 1,
                    QuestionMode.valueOf(row.getString("mode")))
            }
        }
    }

    @Synchronized fun pageTextLayout(bookId: String, page: Int): PageTextLayout? = database().use { db ->
        BookPageTextBoxStore.loadPage(db, bookId, page)
    }

    fun pdfTextLayer(book: Textbook, page: BookPage): ReaderTextUnit? {
        if (book.format != "pdf" || page.ocr || page.text.isBlank()) return null
        return BookPdfLoader.load(original(book.id, book.format).toFile()).use { pdf ->
            if (!pdf.currentAccessPermission.canExtractContent()) return@use null
            val saved = page.textLayout ?: pageTextLayout(book.id, page.number)
            if (saved != null) {
                val restored = try { PdfPageTextExtraction.restore(pdf, page, saved) }
                catch (_: IllegalArgumentException) { null }
                if (restored != null) return@use restored
            }
            val extracted = PdfPageTextExtraction.extract(pdf, page.number)
            // Older books may have a different text layer. Attach nothing unless all offsets still agree.
            if (extracted.unit.text != page.text) return@use null
            database().use { db ->
                BookPageTextBoxStore.saveInTransaction(db, book.id, mapOf(page.number to page.text), listOf(extracted.pageLayout()))
            }
            extracted.unit
        }
    }

    fun savedTextView(bookId: String): Boolean? = database().use { db ->
        db.prepareStatement("SELECT text_view FROM book_reader_views WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { if (it.next()) it.getBoolean(1) else null }
        }
    }

    fun preferredTextView(bookId: String): Boolean? = pendingTextViews[bookId]?.textView ?: savedTextView(bookId)

    /** Called in click order from the UI. Finish accepted writes even if the reader closes. */
    suspend fun persistTextView(bookId: String, textView: Boolean) {
        val pending = PendingTextView(textView)
        pendingTextViews[bookId] = pending
        try {
            withContext(NonCancellable) {
                textViewWrites.withLock {
                    withContext(Dispatchers.IO) {
                        try {
                            saveTextView(bookId, textView)
                        } catch (error: SQLException) {
                            // Retry a transient write lock once using a fresh connection.
                            if ((error.errorCode and 0xff) != 5 /* SQLITE_BUSY */) throw error
                            saveTextView(bookId, textView)
                        }
                    }
                }
            }
        } finally {
            pendingTextViews.remove(bookId, pending)
        }
    }

    fun saveTextView(bookId: String, textView: Boolean) = database().use { db ->
        db.prepareStatement("INSERT OR REPLACE INTO book_reader_views(book,text_view) VALUES(?,?)").use { sql ->
            sql.setString(1, bookId)
            sql.setBoolean(2, textView)
            sql.executeUpdate()
        }
    }

    fun pdfScrollMode(bookId: String): Boolean = database().use { db ->
        db.prepareStatement("SELECT scrolling FROM book_pdf_modes WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { !it.next() || it.getBoolean(1) }
        }
    }

    fun savePdfScrollMode(bookId: String, scrolling: Boolean) = database().use { db ->
        db.prepareStatement("INSERT OR REPLACE INTO book_pdf_modes(book,scrolling) VALUES(?,?)").use { sql ->
            sql.setString(1, bookId)
            sql.setBoolean(2, scrolling)
            sql.executeUpdate()
        }
    }

    /** 文字版阅读方式：默认翻页读，按书记住（TON-1724）。 */
    fun textScrollMode(bookId: String): Boolean = database().use { db ->
        db.prepareStatement("SELECT scrolling FROM book_text_modes WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { it.next() && it.getBoolean(1) }
        }
    }

    fun saveTextScrollMode(bookId: String, scrolling: Boolean) = database().use { db ->
        db.prepareStatement("INSERT OR REPLACE INTO book_text_modes(book,scrolling) VALUES(?,?)").use { sql ->
            sql.setString(1, bookId)
            sql.setBoolean(2, scrolling)
            sql.executeUpdate()
        }
    }

    /** 文字版章内读位：导入页与 UTF-16 偏移，重开回到同一句附近（TON-1724）。 */
    fun savedReadSpot(bookId: String): Pair<Int, Int>? = database().use { db ->
        db.prepareStatement("SELECT page,offset_utf16 FROM book_read_spots WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { if (it.next()) it.getInt(1) to it.getInt(2) else null }
        }
    }

    fun saveReadSpot(bookId: String, page: Int, offsetUtf16: Int) = database().use { db ->
        db.prepareStatement("INSERT OR REPLACE INTO book_read_spots(book,page,offset_utf16) VALUES(?,?,?)").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, page)
            sql.setInt(3, offsetUtf16)
            sql.executeUpdate()
        }
    }

    private val aspectCache = ConcurrentHashMap<String, Map<Int, Float>>()

    fun pdfPageAspects(book: Textbook): Map<Int, Float> {
        aspectCache[book.id]?.let { return it }
        val computed = BookPdfLoader.load(original(book.id, book.format).toFile()).use { pdf ->
            pdf.pages.withIndex().mapNotNull { (index, page) ->
                val crop = page.cropBox
                val aspect = if (page.rotation == 90 || page.rotation == 270) crop.height / crop.width else crop.width / crop.height
                aspect.takeIf { it.isFinite() && it > 0 }?.let { index + 1 to it }
            }.toMap()
        }
        if (aspectCache.size >= 16) aspectCache.clear()
        aspectCache[book.id] = computed
        return computed
    }

    fun highlights(bookId: String, page: BookPage): List<ReaderTextRange> = database().use { db ->
        db.prepareStatement("SELECT start,end,quote FROM book_highlights WHERE book=? AND page=? ORDER BY start,end").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, page.number)
            sql.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    val start = rows.getInt(1)
                    val end = rows.getInt(2)
                    if (start in 0 until end && end <= page.text.length &&
                        page.text.substring(start, end) == rows.getString(3)) add(ReaderTextRange(start, end))
                }
            } }
        }
    }

    fun setHighlight(passage: BookPassage, highlighted: Boolean) = database().use { db ->
        val statement = if (highlighted)
            "INSERT OR IGNORE INTO book_highlights(book,page,start,end,quote) VALUES(?,?,?,?,?)"
        else "DELETE FROM book_highlights WHERE book=? AND page=? AND start=? AND end=? AND quote=?"
        db.prepareStatement(statement).use { sql ->
            sql.setString(1, passage.book)
            sql.setInt(2, passage.page)
            sql.setInt(3, passage.start)
            sql.setInt(4, passage.end)
            sql.setString(5, passage.text)
            sql.executeUpdate()
        }
    }

    /** Persisted provenance only. Missing, stale, unsupported or damaged records return null. */
    @Synchronized fun nativeSources(bookId: String): BookNativeSources? {
        val book = book(bookId)
        return database().use { BookNativeSourceStore.load(it, bookId, book.format, book.pages) }
    }

    /** Optional, inert source structure. Never read a node against an unchecked member digest. */
    @Synchronized fun nativeDocument(
        bookId: String, member: String, control: BookImportControl = BookImportControl(),
    ): BookNativeDocument? {
        control.check()
        val index = nativeSources(bookId) ?: return null
        return BookNativeDocument.read(original(bookId, index.format), index, member, control)
    }

    /** Explicit backfill for old libraries; never relocate text or replace a valid saved index. */
    @Synchronized fun retainNativeSources(bookId: String, control: BookImportControl = BookImportControl()): BookNativeSources? {
        control.check()
        nativeSources(bookId)?.let { return it }
        val book = book(bookId)
        if (book.format !in setOf("epub", "docx")) return null
        val path = original(bookId, book.format)
        fun checkOriginal() {
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; control.check(); hash.update(buffer, 0, n) }
            }
            require(hash.digest().joinToString("") { "%02x".format(it) } == bookId) { "原文件已变化，请重新导入。" }
        }
        checkOriginal()
        val payload = BookNativeSourceStore.capture(path, book.format, bookId, book.pages, control) ?: return null
        checkOriginal()
        database().use { db ->
            db.autoCommit = false
            try { BookNativeSourceStore.saveInTransaction(db, bookId, payload); control.check(); db.commit() }
            catch (error: Exception) { db.rollback(); throw error }
        }
        return nativeSources(bookId)
    }

    /** Explicit local review only. A preview or an adjacent model box is not approval.
     * Add disjoint objects; never silently replace an existing manual/automatic visual.
     */
    @Synchronized fun addReviewedPdfVisuals(
        bookId: String, reviews: List<PdfVisualReview>, control: BookImportControl = BookImportControl(),
    ): Textbook {
        val book = book(bookId)
        require(book.format == "pdf") { "这本教材不是 PDF。" }
        val bytes = Files.readAllBytes(original(bookId, book.format))
        require(BookIllustrationAssets.sha256(bytes) == bookId) { "原文件已变化，请重新导入。" }
        val images = PdfReviewedVisuals.extract(bytes, book.pages, reviews, control)
        val merged = mutableListOf<ExtractedIllustration>()
        for ((number, incoming) in images.groupBy { it.page }) {
            val existing = checkNotNull(book.page(number)).illustrations
            for (item in incoming) for (old in existing) {
                val same = old.imageSha256 == item.contentSha256 && old.sourceRegion == item.sourceRegion &&
                    old.kind == item.kind && old.anchorOffset == item.anchorOffset && old.sourceStart == null && old.sourceEnd == null
                val a = requireNotNull(old.sourceRegion) { "本页已有无法核对位置的插图，请先人工整理。" }
                val b = checkNotNull(item.sourceRegion)
                require(same || !(a.x < b.x + b.width && b.x < a.x + a.width &&
                    a.y < b.y + b.height && b.y < a.y + a.height)) { "与本页已有插图重叠，请先人工整理。" }
            }
            for (old in existing) {
                val data = Files.readAllBytes(BookIllustrationAssets.imageFile(directory, bookId, old))
                check(BookIllustrationAssets.sha256(data) == old.imageSha256) { "已有插图已变化。" }
                merged += ExtractedIllustration(number, old.anchorOffset, data, old.extension, old.width, old.height,
                    old.caption, old.kind, old.sourceStart, old.sourceEnd, old.sourceRegion, old.table)
            }
            merged += incoming.filterNot { item -> existing.any { it.imageSha256 == item.contentSha256 && it.sourceRegion == item.sourceRegion } }
        }
        val newFiles = merged.map { BookIllustrationAssets.imageFile(directory, bookId, it.contentSha256, it.extension) }
            .distinct().filterNot(Files::exists)
        database().use { db ->
            db.autoCommit = false
            try {
                db.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS book_pdf_visual_reviews(book TEXT NOT NULL,page INTEGER NOT NULL,image_sha TEXT NOT NULL,review TEXT NOT NULL,PRIMARY KEY(book,page,image_sha))") }
                BookPageModelStore.saveInTransaction(db, directory, bookId, book.pages.associate { it.number to it.text },
                    merged.sortedWith(compareBy({ it.page }, { it.anchorOffset })))
                db.prepareStatement("INSERT OR REPLACE INTO book_pdf_visual_reviews(book,page,image_sha,review) VALUES(?,?,?,?)").use { sql ->
                    for (review in reviews) {
                        sql.setString(1, bookId); sql.setInt(2, review.selection.page); sql.setString(3, review.pngSha256)
                        sql.setString(4, PdfVisualReviewCodec.encode(review)); sql.addBatch()
                    }
                    sql.executeBatch()
                }
                control.check()
                db.commit()
            } catch (error: Exception) { db.rollback(); newFiles.forEach { Files.deleteIfExists(it) }; throw error }
        }
        return book(bookId)
    }

    @Synchronized fun pdfVisualReviews(bookId: String): List<PdfVisualReview> = database().use { db ->
        val exists = db.createStatement().use { sql -> sql.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='book_pdf_visual_reviews'").use { it.next() } }
        if (!exists) emptyList() else db.prepareStatement("SELECT review FROM book_pdf_visual_reviews WHERE book=? ORDER BY page,image_sha").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows -> buildList { while (rows.next()) add(PdfVisualReviewCodec.decode(rows.getString(1))) } }
        }
    }

    internal data class StagedBookImport(val book: Textbook, val alreadyExisted: Boolean)

    fun importBook(source: Path, control: BookImportControl = BookImportControl()): Textbook {
        val staged = stageBookImport(source, control)
        if (!staged.book.hasPendingOcr) return staged.book
        return try {
            resumeBookOcr(staged.book.id, control)
        } catch (failure: Throwable) {
            if (!staged.alreadyExisted) runCatching { deleteBook(staged.book.id) }
            throw failure
        }
    }

    fun stageBookImport(source: Path, control: BookImportControl = BookImportControl()): StagedBookImport {
        control.report(0, 0)
        require(Files.isRegularFile(source)) { "请选择一份教材文件，文件夹不能直接导入。" }
        require(Files.size(source) > 0) { "这份教材是空文件，请重新保存或下载后再导入。" }
        require(Files.size(source) <= 256L * 1024 * 1024) { "请选择不超过 256 MB 的教材。" }
        val stage = directory.resolve(".import-${UUID.randomUUID()}")
        try {
            Files.copy(source, stage)
            val format = BookFormats.detect(stage, source.fileName.toString())
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(stage).use { input ->
                val buffer = ByteArray(65536)
                while (true) { val size = input.read(buffer); if (size < 0) break; control.check(); digest.update(buffer, 0, size) }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            if (catalog().any { it.id == id }) return StagedBookImport(book(id), alreadyExisted = true)
            val fallback = source.fileName.toString().substringBeforeLast('.')
            val extracted = if (extractBook == TextbookExtraction::extract) {
                TextbookExtraction.extractForStaging(stage, format, fallback, control)
            } else {
                extractBook(stage, format, fallback, ocr, control)
            }.let { book ->
                when (format) {
                    "html" -> StructuredBookExtraction.htmlImages(
                        BookText.decode(Files.readAllBytes(stage)), book, source.toAbsolutePath().parent,
                    )
                    "md" -> MarkdownBookImages.attach(
                        BookText.decode(Files.readAllBytes(stage)), book, source.toAbsolutePath().parent, control,
                    )
                    else -> book
                }
            }
            require(extracted.pages.isNotEmpty()) { "教材中没有可打开的页面。" }
            control.check()
            // Optional second traversal, using the existing canonical-text equality gate. Failure
            // omits the index rather than breaking an otherwise readable EPUB/Word import.
            val nativeSources = BookNativeSourceStore.capture(stage, format, id, extracted.pages, control)
            return saveStagedBook(stage, id, format, extracted, nativeSources, control)
        } finally { Files.deleteIfExists(stage) }
    }

    @Synchronized
    private fun saveStagedBook(
        stage: Path, id: String, format: String, extracted: ExtractedBook,
        nativeSources: ByteArray?, control: BookImportControl,
    ): StagedBookImport {
        if (catalog().any { it.id == id }) return StagedBookImport(book(id), alreadyExisted = true)
        val target = original(id, format)
        // The immutable source is in place before the single transaction makes it visible.
        Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING)
        val newImages = extracted.illustrations.map {
            BookIllustrationAssets.imageFile(directory, id, it.contentSha256, it.extension)
        }.distinct().filterNot(Files::exists)
        database().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("INSERT INTO books(id,title,format,mode,touched,reading_page) VALUES(?,?,?,?,?,?)").use { sql ->
                    sql.setString(1, id); sql.setString(2, extracted.title); sql.setString(3, format)
                    sql.setString(4, QuestionMode.CHOICE.name)
                    sql.setLong(5, System.currentTimeMillis()); sql.setInt(6, extracted.pages.minOf { it.number }); sql.executeUpdate()
                }
                db.prepareStatement("INSERT INTO pages(book,page,text,ocr,ocr_pending) VALUES(?,?,?,?,?)").use { sql ->
                    for (page in extracted.pages) {
                        sql.setString(1, id); sql.setInt(2, page.number); sql.setString(3, page.text)
                        sql.setInt(4, if (page.ocr) 1 else 0); sql.setInt(5, if (page.ocrPending) 1 else 0)
                        sql.addBatch()
                    }
                    sql.executeBatch()
                }
                BookPageModelStore.saveInTransaction(
                    db, directory, id, extracted.pages.associate { it.number to it.text },
                    extracted.illustrations, extracted.pages.mapNotNull { it.textLayout },
                )
                nativeSources?.let { BookNativeSourceStore.saveInTransaction(db, id, it) }
                control.check()
                db.commit()
            } catch (error: Exception) {
                db.rollback()
                newImages.forEach { Files.deleteIfExists(it) }
                Files.deleteIfExists(target)
                throw error
            }
        }
        return StagedBookImport(book(id), alreadyExisted = false)
    }

    fun resumeBookOcr(
        bookId: String,
        control: BookImportControl = BookImportControl(),
        onPageUpdated: (Textbook, BookImportProgress) -> Unit = { _, _ -> },
    ): Textbook {
        val initial = book(bookId)
        val pending = initial.pages.filter { it.ocrPending }.map { it.number }
        if (pending.isEmpty()) return initial
        val totalPages = initial.pages.size
        val initialDone = totalPages - pending.size
        val sourceFile = original(initial.id, initial.format)
        require(Files.isRegularFile(sourceFile)) { "原文件已丢失或损坏，请重新导入《${initial.title}》。" }
        val estimator = ImportEtaEstimator()
        var completedInSession = 0
        if (initial.format in BookFormats.images) {
            control.report(0, 1, recognizing = true, bookId = initial.id, bookTitle = initial.title,
                completedOcr = 0, totalOcr = 1)
            val image = BookScanImages.read(sourceFile)
            val text = try { ocr.recognize(image, control) } finally { releaseBookBitmap(image) }
            control.check()
            updateRecognizedPage(initial.id, 1, text)
            val updated = book(initial.id)
            val progress = BookImportProgress(1, 1, recognizing = true, bookId = initial.id, bookTitle = initial.title,
                completedOcr = 1, totalOcr = 1)
            onPageUpdated(updated, progress)
            return updated
        }
        require(initial.format == "pdf") { "这份教材不支持后台文字识别。" }
        // Reopen the PDF in 25-page batches so PDFBox internal caches stay bounded on 250+ page books.
        for (batch in pending.chunked(25)) {
            control.check()
            try {
                BookPdfLoader.load(sourceFile.toFile()).use { pdf ->
                    val renderer = newBookPdfRenderer(pdf)
                    for (pageNumber in batch) {
                        control.check()
                        val etaBefore = estimator.estimateRemainingSeconds(pending.size - completedInSession)
                        control.report(
                            page = pageNumber,
                            total = totalPages,
                            recognizing = true,
                            bookId = initial.id,
                            bookTitle = initial.title,
                            completedOcr = completedInSession,
                            totalOcr = pending.size,
                            estimatedRemainingSeconds = etaBefore,
                        )
                        val started = System.nanoTime()
                        val bounds = pdf.getPage(pageNumber - 1).cropBox
                        require(bounds.width.isFinite() && bounds.height.isFinite() && bounds.width > 0 && bounds.height > 0) {
                            "这页 PDF 尺寸无效，请重新保存后导入。"
                        }
                        val scale = minOf(300f / 72f, 4000f / maxOf(bounds.width, bounds.height))
                        BookScanImages.checkSize(kotlin.math.ceil(bounds.width * scale).toInt(), kotlin.math.ceil(bounds.height * scale).toInt())
                        val image = renderer.renderImage(pageNumber - 1, scale)
                        val recognized = try { ocr.recognize(image, control) } finally { releaseBookBitmap(image) }
                        estimator.record(((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L))
                        control.check()
                        updateRecognizedPage(initial.id, pageNumber, recognized)
                        completedInSession++
                        val etaAfter = estimator.estimateRemainingSeconds(pending.size - completedInSession)
                        val progress = BookImportProgress(
                            page = initialDone + completedInSession,
                            total = totalPages,
                            recognizing = true,
                            bookId = initial.id,
                            bookTitle = initial.title,
                            completedOcr = completedInSession,
                            totalOcr = pending.size,
                            estimatedRemainingSeconds = etaAfter,
                        )
                        onPageUpdated(book(initial.id), progress)
                    }
                }
            } catch (e: BookImportCancelled) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("原文件已丢失或损坏，请重新导入《${initial.title}》。", e)
            }
        }
        return book(initial.id)
    }

    @Synchronized fun updateRecognizedPage(bookId: String, pageNumber: Int, text: String) {
        database().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("UPDATE pages SET text=?,ocr=1,ocr_pending=0 WHERE book=? AND page=?").use { sql ->
                    sql.setString(1, text); sql.setString(2, bookId); sql.setInt(3, pageNumber); sql.executeUpdate()
                }
                db.prepareStatement("UPDATE books SET touched=? WHERE id=?").use { sql ->
                    sql.setLong(1, System.currentTimeMillis()); sql.setString(2, bookId); sql.executeUpdate()
                }
                db.commit()
            } catch (error: Exception) { db.rollback(); throw error }
        }
    }

    @Synchronized fun clearPendingOcr(bookId: String) {
        database().use { db ->
            db.prepareStatement("UPDATE pages SET ocr_pending=0 WHERE book=? AND ocr_pending=1").use { sql ->
                sql.setString(1, bookId); sql.executeUpdate()
            }
        }
    }

    @Synchronized fun deleteBook(bookId: String) {
        aspectCache.remove(bookId)
        synchronized(renderLock) {
            renderCache.keys.removeIf { it.id == bookId }
        }
        val format = database().use { db ->
            val fmt = db.prepareStatement("SELECT format FROM books WHERE id=?").use { sql ->
                sql.setString(1, bookId)
                sql.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
            db.autoCommit = false
            try {
                for (table in listOf(
                    "pages", "book_illustrations", "book_page_text_boxes", "book_native_sources",
                    "book_learning_paths", "book_ladders", "book_reader_views", "book_pdf_modes",
                    "book_highlights", "answers", "book_reviews",
                )) {
                    val exists = db.createStatement().use { s -> s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table'").use { it.next() } }
                    if (exists) db.prepareStatement("DELETE FROM $table WHERE book=?").use { it.setString(1, bookId); it.executeUpdate() }
                }
                val hasVisualReviews = db.createStatement().use { s -> s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='book_pdf_visual_reviews'").use { it.next() } }
                if (hasVisualReviews) db.prepareStatement("DELETE FROM book_pdf_visual_reviews WHERE book=?").use { it.setString(1, bookId); it.executeUpdate() }
                db.prepareStatement("DELETE FROM book_review_position WHERE book=?").use { it.setString(1, bookId); it.executeUpdate() }
                db.prepareStatement("DELETE FROM books WHERE id=?").use { it.setString(1, bookId); it.executeUpdate() }
                db.commit()
            } catch (error: Exception) { db.rollback(); throw error }
            fmt
        }
        if (format != null && format in BookFormats.supported) {
            runCatching { Files.deleteIfExists(original(bookId, format)) }
        }
        runCatching { directory.resolve(bookId).toFile().deleteRecursively() }
    }

    private fun original(id: String, format: String): Path {
        require(id.matches(Regex("[a-f0-9]{64}")) && format in BookFormats.supported)
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
        // Every submitted local question gets one schedule; wrong/guessed answers are due in the same list now.
        val answered = answer.committed(mode)
        val previous = this.answer(book, lesson, mode)
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
                db.prepareStatement("INSERT OR REPLACE INTO answers(book,lesson,mode,text,revealed,understood,guessed) VALUES(?,?,?,?,?,?,?)").use { sql ->
                    sql.setString(1, book); sql.setString(2, lesson); sql.setString(3, mode.name); sql.setString(4, answer.text)
                    sql.setInt(5, if (answer.revealed) 1 else 0)
                    if (answer.understood == null) sql.setNull(6, java.sql.Types.INTEGER) else sql.setInt(6, if (answer.understood) 1 else 0)
                    sql.setInt(7, if (answer.guessed) 1 else 0)
                    sql.executeUpdate()
                }
                if (practice != null && previous != answer) {
                    reviewStore.recordLesson(db, checkNotNull(source), checkNotNull(section), mode, practice, answer)
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
        val correct = practice.independentlyCorrect(mode, answer)
        return QuestionLadder.record(session, question, correct, knowledgeLine(lesson, practice))
    }

    private fun knowledgeLine(lesson: BookLesson, practice: Practice): String {
        val sentence = practice.reference.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: lesson.title
        val capped = if (sentence.length > 90) sentence.take(88).trimEnd('，', ',', '；', ';', '、', ' ') + "…" else sentence
        return "${lesson.title}：$capped"
    }

    /** Upgrade legacy submitted answers once. Their missing answer dates remain explicitly unknown. */
    private fun backfillReviews() {
        val old = database().use { db -> db.createStatement().use { sql ->
            sql.executeQuery("""SELECT book,lesson,mode FROM answers a WHERE revealed=1
                AND (mode='CHOICE' OR (mode='SHORT_ANSWER' AND understood IS NOT NULL))
                AND NOT EXISTS(SELECT 1 FROM book_reviews r WHERE r.book=a.book AND r.lesson=a.lesson AND r.mode=a.mode)""").use { rows -> buildList {
                while (rows.next()) add(Triple(rows.getString(1), rows.getString(2), QuestionMode.valueOf(rows.getString(3))))
            } }
        } }
        for ((id, lessonId, mode) in old) {
            val book = book(id)
            val lesson = book.lessons.find { it.id == lessonId } ?: continue
            val practice = StudyCoachCourse.practice(book, lesson, mode) ?: continue
            val answer = answer(id, lessonId, mode)
            if (answer.committed(mode)) database().use { db ->
                db.autoCommit = false
                try {
                    reviewStore.recordLesson(db, book, lesson, mode, practice, answer, migrated = true)
                    db.commit()
                } catch (failure: Exception) { db.rollback(); throw failure }
            }
        }
    }

    @Synchronized fun reviews(): List<LocalBookReview> = database().use { db ->
        reviewStore.pending(db)
    }

    @Synchronized fun review(book: String, lesson: String, mode: QuestionMode): LocalBookReview = database().use { db ->
        checkNotNull(reviewStore.find(db, book, lesson, mode)) { "这道复习题已不在书架中。" }
    }

    @Synchronized fun saveReviewAnswer(review: LocalBookReview, answer: PracticeAnswer) {
        database().use { db ->
            db.autoCommit = false
            try { reviewStore.saveRetry(db, review, answer); db.commit() }
            catch (failure: Exception) { db.rollback(); throw failure }
        }
    }

    @Synchronized fun activeReview(): LocalBookReview? = database().use { db ->
        reviewStore.active(db)
    }

    @Synchronized fun openReview(review: LocalBookReview) {
        database().use { db ->
            db.autoCommit = false
            try { reviewStore.open(db, review); db.commit() }
            catch (failure: Exception) { db.rollback(); throw failure }
        }
    }

    @Synchronized fun closeReview() {
        database().use { db -> db.createStatement().use { it.executeUpdate("DELETE FROM book_review_position") } }
    }

    // ---- 同一页、同一显示宽度合并并发调用并缓存，每次调用留下分段计时 ----
    private data class RenderKey(val id: String, val page: Int, val stamp: Long, val pixelWidth: Int?)
    private val renderLock = Any()
    private val renderCache = LinkedHashMap<RenderKey, BookBitmap>(16, .75f, true)
    private val renderInflight = HashMap<RenderKey, java.util.concurrent.CompletableFuture<BookBitmap>>()
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
    fun renderOriginal(book: Textbook, page: Int, pixelWidth: Int? = null): BookBitmap {
        require(book.page(page) != null)
        return if (book.format == "pdf") renderPdf(book, page, pixelWidth)
        else {
            require(book.format in BookFormats.images)
            BookScanImages.read(original(book.id, book.format))
        }
    }

    fun renderPdf(book: Textbook, page: Int, pixelWidth: Int? = null): BookBitmap {
        require(book.format == "pdf" && page in 1..book.pages.size)
        require(pixelWidth == null || pixelWidth > 0)
        val file = original(book.id, book.format).toFile()
        val key = RenderKey(book.id, page, file.length() xor file.lastModified(), pixelWidth)
        val started = System.nanoTime()
        var owner = false
        val future: java.util.concurrent.CompletableFuture<BookBitmap>
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
            val image = BookPdfLoader.load(file).use { pdf ->
                val t1 = System.nanoTime()
                val sheet = pdf.getPage(page - 1)
                val bounds = sheet.cropBox
                val longEdge = maxOf(bounds.width, bounds.height)
                val scale = if (pixelWidth == null) minOf(1.5f, 1800f / longEdge).coerceAtLeast(.05f)
                else {
                    val displayedWidth = if (sheet.rotation == 90 || sheet.rotation == 270) bounds.height else bounds.width
                    // PDFBox floors pixel dimensions. Round the scale upward before applying the raster limit.
                    minOf(Math.nextUp(pixelWidth / displayedWidth), bookPdfRasterLimit / longEdge)
                }
                val t2 = System.nanoTime()
                val img = renderBookPdfPage(file, pdf, page - 1, scale)
                val t3 = System.nanoTime()
                record(RenderTrace(book.id, page, "render", (t1 - t0) / 1_000_000.0, (t2 - t1) / 1_000_000.0, (t3 - t2) / 1_000_000.0, (t3 - started) / 1_000_000.0, Thread.currentThread().name))
                img
            }
            synchronized(renderLock) {
                renderCache[key] = image
                val entries = renderCache.values.iterator()
                var bytes = renderCache.values.sumOf(::bookPdfBitmapBytes)
                while (renderCache.size > 6 || bytes > bookPdfCacheBytes) {
                    bytes -= bookPdfBitmapBytes(entries.next())
                    entries.remove()
                }
                renderInflight.remove(key)
            }
            future.complete(image)
            return image
        } catch (e: Throwable) {
            synchronized(renderLock) {
                renderInflight.remove(key)
                if (e is OutOfMemoryError) renderCache.clear()
            }
            future.completeExceptionally(e)
            throw e
        }
    }
}

/** 一次页图渲染的分段计时（毫秒）。 */
internal data class RenderTrace(val book: String, val page: Int, val outcome: String, val loadMs: Double, val pageBoxMs: Double, val renderMs: Double, val totalMs: Double, val thread: String)

internal data class ExtractedBook(
    val title: String, val pages: List<BookPage>, val illustrations: List<ExtractedIllustration> = emptyList(),
)

internal object TextbookExtraction {
    fun extract(path: Path, format: String, title: String, ocr: BookOcr = BundledBookOcr, control: BookImportControl = BookImportControl()): ExtractedBook = try {
        extractContent(path, format, title, ocr, control, stageOcrOnly = false)
    } catch (e: BookPdfPasswordException) {
        throw IllegalArgumentException("这本 PDF 有密码保护，请取消密码后再导入。", e)
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("这份教材不完整或已损坏，请重新保存或下载后再导入。", e)
    }

    fun extractForStaging(path: Path, format: String, title: String, control: BookImportControl = BookImportControl()): ExtractedBook = try {
        extractContent(path, format, title, BundledBookOcr, control, stageOcrOnly = true)
    } catch (e: BookPdfPasswordException) {
        throw IllegalArgumentException("这本 PDF 有密码保护，请取消密码后再导入。", e)
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("这份教材不完整或已损坏，请重新保存或下载后再导入。", e)
    }

    private fun extractContent(path: Path, format: String, title: String, ocr: BookOcr, control: BookImportControl, stageOcrOnly: Boolean): ExtractedBook = when (format) {
        "pdf" -> BookPdfLoader.load(path.toFile()).use { pdf ->
            require(pdf.numberOfPages in 1..5000) { "这本 PDF 的页数暂不支持。" }
            require(pdf.currentAccessPermission.canExtractContent()) { "这本 PDF 限制了文字提取，请使用有阅读和提取权限的教材。" }
            val pages = (1..pdf.numberOfPages).map { n ->
                control.report(n, pdf.numberOfPages)
                val extracted = PdfPageTextExtraction.extract(pdf, n)
                val text = checkNotNull(extracted.unit.text)
                BookPage(n, text, textLayout = extracted.pageLayout().takeIf { text.isNotBlank() }, ocrPending = stageOcrOnly && text.isBlank())
            }
            require(pages.count { it.text.isBlank() } <= BookScanImages.MAX_OCR_PAGES) {
                "扫描页超过 ${BookScanImages.MAX_OCR_PAGES} 页，请把 PDF 拆分后再导入。"
            }
            val recognized = if (stageOcrOnly) pages else {
                val renderer by lazy { newBookPdfRenderer(pdf) }
                pages.map { page ->
                    if (page.text.isNotBlank()) page else {
                        control.report(page.number, pdf.numberOfPages, recognizing = true)
                        val bounds = pdf.getPage(page.number - 1).cropBox
                        require(bounds.width.isFinite() && bounds.height.isFinite() && bounds.width > 0 && bounds.height > 0) { "这页 PDF 尺寸无效，请重新保存后导入。" }
                        // 300 dpi where possible; cap the decoded raster before allocating it.
                        val scale = minOf(300f / 72f, 4000f / maxOf(bounds.width, bounds.height))
                        BookScanImages.checkSize(kotlin.math.ceil(bounds.width * scale).toInt(), kotlin.math.ceil(bounds.height * scale).toInt())
                        val image = renderer.renderImage(page.number - 1, scale)
                        try { BookPage(page.number, ocr.recognize(image, control), ocr = true) }
                        finally { releaseBookBitmap(image) }
                    }
                }
            }
            val visuals = importedPdfVisuals(pdf, recognized, control)
            ExtractedBook(pdf.documentInformation.title?.trim()?.takeIf(String::isNotEmpty) ?: title, recognized, visuals)
        }
        "png", "jpg" -> {
            if (stageOcrOnly) {
                val image = BookScanImages.read(path)
                releaseBookBitmap(image)
                ExtractedBook(title, listOf(BookPage(1, "", ocr = false, ocrPending = true)))
            } else {
                control.report(1, 1, recognizing = true)
                val image = BookScanImages.read(path)
                try { ExtractedBook(title, listOf(BookPage(1, ocr.recognize(image, control), ocr = true))) }
                finally { releaseBookBitmap(image) }
            }
        }
        "epub" -> epub(path, title, control = control)
        "mobi", "azw3" -> com.coach.desktop.books.mobi.KindleText.read(Files.readAllBytes(path)).let { book ->
            val name = book.title().ifBlank { title }
            StructuredBookExtraction.kindle(book, name)
        }
        "docx", "pptx", "html", "rtf", "odt" -> StructuredBookExtraction.extract(path, format, title, control)
        else -> {
            require(Files.size(path) <= 32L * 1024 * 1024) { "文本教材请控制在 32 MB 以内。" }
            val text = BookText.decode(Files.readAllBytes(path))
            require(text.isNotBlank()) { "教材中没有文字。" }
            val markers = Regex("<!--\\s*PDF 第 (\\d+) 页 · 书第 (.*?) 页\\s*-->").findAll(text).toList()
            val marked = markers.mapIndexed { index, marker ->
                BookPage(marker.groupValues[1].toInt(), text.substring(marker.range.first, markers.getOrNull(index + 1)?.range?.first ?: text.length))
            }
            require(marked.all { it.number > 0 } && marked.map { it.number }.distinct().size == marked.size) { "教材里的 PDF 页标记不完整或重复，无法确认页码。" }
            ExtractedBook(title, marked.ifEmpty {
                val cleaned = clean(text)
                require(cleaned.isNotBlank()) { "教材中没有文字。" }
                BookText.chapters(cleaned)
            })
        }
    }

    internal fun clean(text: String) = text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
    internal fun paginate(text: String): List<BookPage> {
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

    /** On-demand extraction payload. It is not persisted or used by the reader yet. */
    fun epubSourceMapping(path: Path, canonicalPages: List<BookPage>): EpubSourceMapping {
        val elements = mutableListOf<EpubElementSource>()
        val extracted = epub(path, "", elements)
        // Existing books must match every page, including page numbers. Never relocate stale text.
        return if (extracted.pages.map { it.number to it.text } == canonicalPages.map { it.number to it.text })
            EpubSourceMapping(EpubSourceMapping.Status.MAPPED, elements)
        else EpubSourceMapping(EpubSourceMapping.Status.STALE_TEXT, emptyList())
    }

    private fun epub(path: Path, fallback: String, elements: MutableList<EpubElementSource>? = null,
        control: BookImportControl = BookImportControl()): ExtractedBook = ZipFile(path.toFile()).use { zip ->
        fun read(name: String): ByteArray {
            require(!name.startsWith('/') && name.split('/').none { it == ".." } && '\\' !in name) { "EPUB 内含无效的文件位置。" }
            val entry = requireNotNull(zip.getEntry(name)) { "EPUB 缺少阅读顺序中的正文。" }
            require(entry.size in 0..(16L * 1024 * 1024)) { "EPUB 的单个章节过大。" }
            return zip.getInputStream(entry).use { input ->
                val bytes = input.readNBytes(16 * 1024 * 1024 + 1)
                require(bytes.size <= 16 * 1024 * 1024) { "EPUB 的单个章节过大。" }; bytes
            }
        }
        fun xml(bytes: ByteArray): Element = EpubXml.parse(bytes)
        fun Element.all(name: String): List<Element> = getElementsByTagNameNS("*", name).let { list -> (0 until list.length).map { list.item(it) as Element } }
        if (zip.getEntry("META-INF/encryption.xml") != null) {
            val encryption = xml(read("META-INF/encryption.xml"))
            val fontObfuscation = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
            require(encryption.all("EncryptionMethod").all { it.getAttribute("Algorithm") in fontObfuscation }) {
                "这本 EPUB 有版权保护，打不开。请使用无 DRM、且有权阅读的版本。"
            }
        }
        val container = xml(read("META-INF/container.xml"))
        val packagePath = container.all("rootfile").firstOrNull()?.getAttribute("full-path") ?: error("EPUB 缺少内容目录。")
        val opf = xml(read(packagePath))
        val manifest = opf.all("item").associate { it.getAttribute("id") to it.getAttribute("href") }
        val spine = opf.all("itemref").withIndex().filter { it.value.getAttribute("linear") != "no" }
        require(spine.size in 1..5000) { "EPUB 没有可读正文，或章节太多。" }
        val root = java.net.URI("/$packagePath")
        var totalBytes = 0L
        var pageBase = 0
        val imageExtractor = if (elements == null) EpubBookImages(zip, control) else null
        val illustrations = mutableListOf<ExtractedIllustration>()
        val pages = spine.flatMap { (spineIndex, item) ->
            control.check()
            val href = requireNotNull(manifest[item.getAttribute("idref")]) { "EPUB 阅读顺序不完整。" }
            val resolved = root.resolve(href).normalize()
            require(resolved.scheme == null && resolved.host == null && resolved.fragment == null) { "EPUB 的正文必须包含在书内。" }
            val member = resolved.path.removePrefix("/")
            val bytes = read(member)
            totalBytes += bytes.size
            require(totalBytes <= 64L * 1024 * 1024) { "EPUB 的正文过大。" }
            val doc = xml(bytes)
            val output = StringBuilder()
            val trace = if (elements != null) EpubTextTrace() else null
            fun visit(node: Node, elementPath: List<Int>) {
                if (node.nodeType == Node.TEXT_NODE) { output.append(node.nodeValue); return }
                val name = node.localName?.lowercase().orEmpty()
                if (name in setOf("script", "style", "head")) return
                val block = name in setOf("p", "div", "li", "h1", "h2", "h3", "h4", "section", "br", "tr")
                if (block) output.append("\n\n")
                val start = output.length
                if (node is Element) imageExtractor?.record(node, start)
                if (name.matches(Regex("h[1-6]"))) output.append("# ")
                var childIndex = 0
                for (i in 0 until node.childNodes.length) {
                    val child = node.childNodes.item(i)
                    val childPath = if (trace != null && child is Element) elementPath + childIndex else emptyList()
                    if (child is Element) childIndex++
                    visit(child, childPath)
                }
                if (node is Element) trace?.record(elementPath, node.namespaceURI.orEmpty(), node.localName ?: node.tagName, start, output.length)
                if (block) output.append("\n\n")
            }
            visit(doc, emptyList())
            val localPages = paginate(clean(output.toString()).trim())
            imageExtractor?.let { illustrations += it.finish(output.toString(), localPages, member, pageBase) }
            trace?.let { elements?.addAll(it.project(output.toString(), localPages, spineIndex, member, bytes, pageBase)) }
            pageBase += localPages.size
            localPages
        }.mapIndexed { index, page -> page.copy(number = index + 1) }
        require(pages.any { it.text.isNotBlank() }) { "EPUB 中没有可读取的文字正文。" }
        ExtractedBook(opf.all("title").firstOrNull()?.textContent?.trim()?.takeIf(String::isNotEmpty) ?: fallback, pages, illustrations)
    }
}
