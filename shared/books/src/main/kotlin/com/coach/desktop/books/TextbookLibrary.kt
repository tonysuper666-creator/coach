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
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

internal data class CollectionSummary(
    val record: MaterialCollectionRecord,
    val materialIds: List<String>,
    val activeConceptCount: Int = 0,
)

internal data class StartupLoadSnapshot(
    val catalog: List<BookSummary>,
    val activeReview: LocalBookReview?,
    val openSession: Pair<String, String>?,
    val book: Textbook?,
    val learningPath: String?,
    val reviews: List<LocalBookReview>,
    val ladder: LadderSession,
    val todayQueue: LocalReviewQueue = LocalReviewQueue(emptyList(), 0),
    val collections: List<CollectionSummary> = emptyList(),
    val activePracticeDraft: ChapterPracticeDraft? = null,
    val focusRecommendation: HomeFocusRecommendation? = null,
    val lastBookQuestion: BookLastQuestion? = null,
)

/** All state belongs to this Windows library. No server, course ID, token or user corpus is opened. */
internal class TextbookLibrary(
    val directory: Path, internal val clock: Clock = Clock.systemDefaultZone(), private val ocr: BookOcr = BundledBookOcr,
    private val streamPdfExtras: Boolean = false,
    internal val lazyPdfExtras: Boolean = false,
    private val extractBook: ((Path, String, String, BookOcr, BookImportControl) -> ExtractedBook)? = null,
) {
    private val reviewStore = TextbookReviewStore(
        today = { LocalDate.now(clock).toEpochDay() },
        nowMs = { clock.millis() },
    )
    private class PendingTextView(val textView: Boolean)
    private class CachedValue<T>(val value: T)
    private val pendingTextViews = ConcurrentHashMap<String, PendingTextView>()
    private val savedTextViewCache = ConcurrentHashMap<String, CachedValue<Boolean?>>()
    private val pdfScrollModeCache = ConcurrentHashMap<String, Boolean>()
    private val textScrollModeCache = ConcurrentHashMap<String, Boolean>()
    private val readSpotCache = ConcurrentHashMap<String, CachedValue<Pair<Int, Int>?>>()
    private val pageHighlightRawCache = ConcurrentHashMap<Pair<String, Int>, List<Triple<Int, Int, String>>>()
    private val textViewWrites = Mutex()
    private val textLayerLock = Any()
    private val textLayerCache = LinkedHashMap<Pair<String, Int>, ReaderTextUnit>(16, .75f, true)
    init {
        Files.createDirectories(directory)
        PdfWarmup.start()
        BookSchemaMigration.upgradeAndMigrate(
            directory = directory,
            openConnection = ::database,
            onInTransaction = { db ->
                reviewStore.migrate(db, manageTransaction = false)
                BookPageModelStore.migrate(db)
                BookNativeSourceStore.migrate(db)
                BookStructureStore.migrate(db)
            },
            onPostCommit = { db ->
                if (db != null) backfillReviews(db) else database().use(::backfillReviews)
            },
        )
    }
    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:${directory.resolve("library.sqlite")}").apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    private fun catalog(db: Connection): List<BookSummary> =
        db.createStatement().use { sql ->
            sql.executeQuery(
                """SELECT b.id, b.title, COALESCE(ps.page_count, 0), COALESCE(ps.last_page, 0),
                          b.reading_page, b.learning, COALESCE(ps.pending_ocr, 0), b.index_state,
                          COALESCE(b.show_standalone, 1)
                   FROM books b
                   LEFT JOIN (
                       SELECT book, COUNT(*) AS page_count, MAX(page) AS last_page,
                              SUM(CASE WHEN ocr_pending=1 THEN 1 ELSE 0 END) AS pending_ocr
                       FROM pages GROUP BY book
                   ) ps ON ps.book = b.id
                   ORDER BY b.touched DESC, b.id""".trimIndent(),
            ).use { rows ->
                buildList {
                    while (rows.next()) {
                        val indexStateCol = rows.getString(8)?.takeIf { it.isNotEmpty() } ?: "READY"
                        add(
                            BookSummary(
                                rows.getString(1), rows.getString(2),
                                rows.getInt(3), rows.getInt(4),
                                rows.getInt(5), rows.getInt(6) == 1,
                                rows.getInt(7),
                                indexState = indexStateCol,
                                showStandalone = rows.getInt(9) != 0,
                            ),
                        )
                    }
                }
            }
        }

    @Synchronized fun catalog(): List<BookSummary> = database().use(::catalog)

    private fun learningPath(db: Connection, book: String): String? =
        db.prepareStatement("SELECT path FROM book_learning_paths WHERE book=?").use { sql ->
            sql.setString(1, book)
            sql.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
        }

    @Synchronized fun learningPath(book: String): String? = database().use { learningPath(it, book) }

    @Synchronized fun saveLearningPath(book: String, path: String) {
        require(path in setOf("READ", "READ_AND_LEARN", "QUESTION_FIRST")) { "未知的学习走法。" }
        database().use { db ->
            db.prepareStatement("INSERT INTO book_learning_paths(book,path) SELECT id,? FROM books WHERE id=? ON CONFLICT(book) DO UPDATE SET path=excluded.path").use { sql ->
                sql.setString(1, path); sql.setString(2, book)
                require(sql.executeUpdate() == 1) { "这本教材已不在书架中。" }
            }
        }
    }

    private fun ladder(db: Connection, book: String): LadderSession =
        db.prepareStatement("SELECT session FROM book_ladders WHERE book=?").use { sql ->
            sql.setString(1, book)
            sql.executeQuery().use { row ->
                if (!row.next()) LadderSession()
                else runCatching { LedgerJson.decodeFromString(LadderSession.serializer(), row.getString(1)) }.getOrNull() ?: LadderSession()
            }
        }

    /** 教材入口和主线共用同一个题梯会话；一行 JSON 按书保存在本机。 */
    @Synchronized fun ladder(book: String): LadderSession = database().use { ladder(it, book) }

    @Synchronized fun saveLadder(book: String, session: LadderSession) {
        database().use { db -> saveLadderRow(db, book, session) }
    }

    private fun saveLadderRow(db: Connection, book: String, session: LadderSession) {
        db.prepareStatement("INSERT INTO book_ladders(book,session) VALUES(?,?) ON CONFLICT(book) DO UPDATE SET session=excluded.session").use { sql ->
            sql.setString(1, book); sql.setString(2, LedgerJson.encodeToString(LadderSession.serializer(), session))
            sql.executeUpdate()
        }
    }

    private fun book(db: Connection, id: String): Textbook {
        val loaded = readBook(db, id)
        val structure = loaded.structure ?: return loaded
        if (structure.version >= BookStructure.CURRENT_VERSION || loaded.indexState != "READY") return loaded
        if (loaded.format !in setOf("epub", "pdf")) return loaded
        val path = original(id, loaded.format)
        if (!Files.isRegularFile(path)) return loaded
        val navigation = if (loaded.format == "epub") {
            TextbookExtraction.epubNavigation(path, loaded.pages) ?: return loaded
        } else null
        val outline = navigation?.outline ?: run {
            if (structure.source == HeadingSource.PDF_OUTLINE.name) return loaded
            runCatching { BookPdfLoader.read(path.toFile(), ::extractBookPdfOutline) }.getOrDefault(emptyList())
                .ifEmpty { return loaded }
        }
        val refreshed = BookStructurePlanner.plan(id, loaded.title, loaded.format, loaded.pages, outline,
            nativeBodyStart = navigation?.nativeBodyStart)
        // Keep the reader's saved page, bookmarks and notes when refreshing navigation.
        val autoCommit = db.autoCommit
        if (autoCommit) db.autoCommit = false
        try {
            BookStructureStore.saveStructure(db, id, refreshed)
            if (autoCommit) db.commit()
        } catch (error: Exception) {
            if (autoCommit) db.rollback()
            throw error
        } finally {
            if (autoCommit) db.autoCommit = true
        }
        return loaded.copy(structure = refreshed)
    }

    private fun readBook(db: Connection, id: String): Textbook =
        db.prepareStatement("SELECT title,format,reading_page,lesson_index,learning,prompt_seen,mode,index_state FROM books WHERE id=?").use { sql ->
            sql.setString(1, id)
            sql.executeQuery().use { row ->
                check(row.next()) { "这本教材已不在书架中。" }
                val extras = BookPageModelStore.loadExtrasByPage(db, id, includeTextLayouts = !streamPdfExtras && !lazyPdfExtras)
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
                val title = row.getString(1)
                val format = row.getString(2)
                val storedPage = row.getInt(3)
                val lessonIdx = row.getInt(4)
                val learning = row.getInt(5) == 1
                val promptSeen = row.getInt(6) == 1
                val questionMode = QuestionMode.valueOf(row.getString(7))
                val indexStateCol = row.getString(8)?.takeIf { it.isNotEmpty() } ?: "READY"
                var createdBook: Textbook? = null
                val structure = BookStructureStore.loadStructure(db, id, pages, sharedBlocks = {
                    createdBook?.sourceBlocks() ?: StudyCoachCourse.blocks(id, pages, snapSentences = true)
                })
                val correctedBodyPage = db.prepareStatement("SELECT page FROM book_body_start WHERE book=?").use { sql ->
                    sql.setString(1, id)
                    sql.executeQuery().use { if (it.next()) it.getInt(1) else null }
                }
                Textbook(
                    id, title, format, pages,
                    if (pages.any { it.number == storedPage }) storedPage else pages.first().number,
                    lessonIdx, learning, promptSeen, questionMode, structure,
                    indexState = indexStateCol, correctedBodyPage = correctedBodyPage,
                ).also { createdBook = it }
            }
        }

    @Synchronized fun book(id: String): Textbook = database().use { book(it, id) }

    @Synchronized fun saveBodyStart(bookId: String, page: Int?) {
        database().use { db ->
            if (page != null) {
                val valid = db.prepareStatement("SELECT 1 FROM pages WHERE book=? AND page=?").use { sql ->
                    sql.setString(1, bookId)
                    sql.setInt(2, page)
                    sql.executeQuery().use { it.next() }
                }
                require(valid) { "请输入书内页码。" }
            }
            if (page == null) {
                db.prepareStatement("DELETE FROM book_body_start WHERE book=?").use { sql ->
                    sql.setString(1, bookId)
                    sql.executeUpdate()
                }
            } else {
                db.prepareStatement("INSERT OR REPLACE INTO book_body_start(book,page) VALUES(?,?)").use { sql ->
                    sql.setString(1, bookId)
                    sql.setInt(2, page)
                    sql.executeUpdate()
                }
            }
        }
    }

    @Synchronized fun loadStartupSnapshot(): StartupLoadSnapshot = database().use { db ->
        val catalog = catalog(db)
        val active = reviewStore.active(db)
        val rawSession = openSession(db)
        val session = if (active == null) rawSession?.takeIf { (id, _) -> catalog.any { it.id == id } } else null
        var prefetchedPage: Int? = null
        if (session != null && session.second == "READER") {
            val earlyPage = db.prepareStatement("SELECT format, reading_page FROM books WHERE id=?").use { sql ->
                sql.setString(1, session.first)
                sql.executeQuery().use { row ->
                    if (row.next() && row.getString(1) == "pdf") row.getInt(2) else null
                }
            }
            if (earlyPage != null) {
                prefetchedPage = earlyPage
                startupFirstRenderCompletedAtNs = 0L
                startupReaderBookId = session.first
                val partner = if (earlyPage % 2 == 1) earlyPage + 1 else (earlyPage - 1).coerceAtLeast(1)
                startupNearWidthBookId = session.first
                startupNearWidthPrimaryPage = earlyPage
                startupNearWidthPartnerPage = partner
                startupNearWidthUntilNs = System.nanoTime() + 1_500_000_000L
                prefetchRenderDiskCacheAsync(session.first, earlyPage, partner)
            }
        }
        val targetId = active?.book ?: session?.first ?: catalog.firstOrNull()?.id
        val loadedBook = targetId?.let { book(db, it) }
        if (loadedBook != null) {
            if (loadedBook.format == "pdf" && session?.second == "READER") {
                startupWarmupExecutor.execute {
                    runCatching {
                        loadedBook.sourceBlocks()
                        loadedBook.lessons
                    }
                }
                startupReaderBookId = loadedBook.id
                val p = loadedBook.readingPage
                val partner = if (p % 2 == 1) p + 1 else (p - 1).coerceAtLeast(1)
                startupNearWidthBookId = loadedBook.id
                startupNearWidthPrimaryPage = p
                startupNearWidthPartnerPage = partner
                startupNearWidthUntilNs = System.nanoTime() + 1_500_000_000L
                if (prefetchedPage != p) {
                    startupFirstRenderCompletedAtNs = 0L
                    prefetchRenderDiskCacheAsync(loadedBook.id, p, partner)
                }
            } else {
                startupFirstRenderCompletedAtNs = 0L
                startupReaderBookId = null
                startupNearWidthBookId = null
                startupNearWidthUntilNs = 0L
            }
            if (loadedBook.format == "pdf" && !aspectCache.containsKey(loadedBook.id)) {
                val aspects = BookAspectStore.loadAspects(db, loadedBook.id)
                if (aspects.isNotEmpty()) {
                    if (aspectCache.size >= 16) aspectCache.clear()
                    aspectCache[loadedBook.id] = aspects
                }
            }
            savedTextViewCache[loadedBook.id] = CachedValue(savedTextViewFromDb(db, loadedBook.id))
            pdfScrollModeCache[loadedBook.id] = pdfScrollModeFromDb(db, loadedBook.id)
            textScrollModeCache[loadedBook.id] = textScrollModeFromDb(db, loadedBook.id)
            readSpotCache[loadedBook.id] = CachedValue(savedReadSpotFromDb(db, loadedBook.id))
            pageHighlightRawCache[loadedBook.id to loadedBook.readingPage] =
                rawHighlightsFromDb(db, loadedBook.id, loadedBook.readingPage)
        } else {
            startupFirstRenderCompletedAtNs = 0L
            startupReaderBookId = null
            startupNearWidthBookId = null
            startupNearWidthUntilNs = 0L
        }
        val learningPath = loadedBook?.let { learningPath(db, it.id) }
        val todayQueue = reviewStore.queue(db)
        val reviews = reviewStore.pending(db)
        val collections = collectionSummaries(db)
        val ladder = loadedBook?.let { ladder(db, it.id) } ?: LadderSession()
        val activeDraft = loadedBook?.let { ChapterProgressStore.latestPracticeDraft(db, it.id) }
        val focusRec = MasteryStore.topHomeRecommendation(db, loadedBook?.id, clock.millis())
        val lastQuestion = (loadedBook?.id ?: catalog.firstOrNull()?.id)?.let { bookId ->
            MasteryStore.lookupLastBookQuestion(db, bookId)?.let { BookLastQuestion(bookId, it) }
        }
        StartupLoadSnapshot(catalog, active, session, loadedBook, learningPath, reviews, ladder, todayQueue, collections, activeDraft, focusRec, lastQuestion)
    }

    @Synchronized fun pageTextLayout(bookId: String, page: Int): PageTextLayout? = database().use { db ->
        BookPageTextBoxStore.loadPage(db, bookId, page)
    }

    fun pdfTextLayer(book: Textbook, page: BookPage): ReaderTextUnit? {
        if (book.format != "pdf" || page.ocr || page.text.isBlank()) return null
        val cacheKey = book.id to page.number
        synchronized(textLayerLock) { textLayerCache[cacheKey] }?.let { return it }
        if (book.id == startupReaderBookId) {
            if (firstRenderGates[book.id]?.isDone != true) {
                awaitFirstRenderOrTimeout(book.id, 400L)
            }
            val completedAt = startupFirstRenderCompletedAtNs
            if (completedAt > 0L) {
                val remainingMs = 80L - (System.nanoTime() - completedAt) / 1_000_000L
                if (remainingMs > 0L) runCatching { Thread.sleep(remainingMs) }
            }
            startupReaderBookId = null
        }
        if (activeRenders.get() > 0) {
            var remainingNanos = 1_500_000_000L
            renderIdleLock.lock()
            try {
                while (activeRenders.get() > 0 && remainingNanos > 0L) {
                    remainingNanos = runCatching { renderIdleCondition.awaitNanos(remainingNanos) }.getOrDefault(0L)
                }
            } finally {
                renderIdleLock.unlock()
            }
        }
        val saved = page.textLayout ?: pageTextLayout(book.id, page.number)
        val unit = BookPdfLoader.readCached(original(book.id, book.format).toFile()) { pdf ->
            if (!pdf.currentAccessPermission.canExtractContent()) return@readCached null
            if (saved != null) {
                val restored = try { PdfPageTextExtraction.restore(pdf, page, saved) }
                catch (_: IllegalArgumentException) { null }
                if (restored != null) return@readCached restored
            }
            val extracted = PdfPageTextExtraction.extract(pdf, page.number)
            // Older books may have a different text layer. Attach nothing unless all offsets still agree.
            if (extracted.unit.text != page.text) return@readCached null
            database().use { db ->
                BookPageTextBoxStore.saveInTransaction(db, book.id, mapOf(page.number to page.text), listOf(extracted.pageLayout()))
            }
            extracted.unit
        }
        if (unit != null) {
            synchronized(textLayerLock) {
                textLayerCache[cacheKey] = unit
                while (textLayerCache.size > 16) {
                    val eldest = textLayerCache.keys.iterator().next()
                    textLayerCache.remove(eldest)
                }
            }
        }
        return unit
    }

    private fun savedTextViewFromDb(db: Connection, bookId: String): Boolean? =
        db.prepareStatement("SELECT text_view FROM book_reader_views WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { if (it.next()) it.getBoolean(1) else null }
        }

    fun savedTextView(bookId: String): Boolean? {
        savedTextViewCache[bookId]?.let { return it.value }
        return database().use { db -> savedTextViewFromDb(db, bookId) }.also {
            savedTextViewCache[bookId] = CachedValue(it)
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
        savedTextViewCache[bookId] = CachedValue(textView)
    }

    private fun pdfScrollModeFromDb(db: Connection, bookId: String): Boolean =
        db.prepareStatement("SELECT scrolling FROM book_pdf_modes WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { it.next() && it.getBoolean(1) }
        }

    fun pdfScrollMode(bookId: String): Boolean {
        pdfScrollModeCache[bookId]?.let { return it }
        return database().use { db -> pdfScrollModeFromDb(db, bookId) }.also {
            pdfScrollModeCache[bookId] = it
        }
    }

    fun savePdfScrollMode(bookId: String, scrolling: Boolean) = database().use { db ->
        db.prepareStatement("INSERT OR REPLACE INTO book_pdf_modes(book,scrolling) VALUES(?,?)").use { sql ->
            sql.setString(1, bookId)
            sql.setBoolean(2, scrolling)
            sql.executeUpdate()
        }
        pdfScrollModeCache[bookId] = scrolling
    }

    private fun textScrollModeFromDb(db: Connection, bookId: String): Boolean =
        db.prepareStatement("SELECT scrolling FROM book_text_modes WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { it.next() && it.getBoolean(1) }
        }

    /** 文字版阅读方式：默认翻页读，按书记住（TON-1724）。 */
    fun textScrollMode(bookId: String): Boolean {
        textScrollModeCache[bookId]?.let { return it }
        return database().use { db -> textScrollModeFromDb(db, bookId) }.also {
            textScrollModeCache[bookId] = it
        }
    }

    fun saveTextScrollMode(bookId: String, scrolling: Boolean) = database().use { db ->
        db.prepareStatement("INSERT OR REPLACE INTO book_text_modes(book,scrolling) VALUES(?,?)").use { sql ->
            sql.setString(1, bookId)
            sql.setBoolean(2, scrolling)
            sql.executeUpdate()
        }
        textScrollModeCache[bookId] = scrolling
    }

    private fun savedReadSpotFromDb(db: Connection, bookId: String): Pair<Int, Int>? =
        db.prepareStatement("SELECT page,offset_utf16 FROM book_read_spots WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { if (it.next()) it.getInt(1) to it.getInt(2) else null }
        }

    /** 文字版章内读位：导入页与 UTF-16 偏移，重开回到同一句附近（TON-1724）。 */
    fun savedReadSpot(bookId: String): Pair<Int, Int>? {
        readSpotCache[bookId]?.let { return it.value }
        return database().use { db -> savedReadSpotFromDb(db, bookId) }.also {
            readSpotCache[bookId] = CachedValue(it)
        }
    }

    fun saveReadSpot(bookId: String, page: Int, offsetUtf16: Int) = database().use { db ->
        db.prepareStatement("INSERT OR REPLACE INTO book_read_spots(book,page,offset_utf16) VALUES(?,?,?)").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, page)
            sql.setInt(3, offsetUtf16)
            sql.executeUpdate()
        }
        readSpotCache[bookId] = CachedValue(page to offsetUtf16)
    }

    private val aspectCache = ConcurrentHashMap<String, Map<Int, Float>>()

    fun pdfPageAspects(book: Textbook): Map<Int, Float> {
        aspectCache[book.id]?.let { return it }
        val fromDb = database().use { db -> BookAspectStore.loadAspects(db, book.id) }
        if (fromDb.isNotEmpty()) {
            if (aspectCache.size >= 16) aspectCache.clear()
            aspectCache[book.id] = fromDb
            return fromDb
        }
        val boxes = BookPdfLoader.read(original(book.id, book.format).toFile()) { pdf ->
            pdf.pages.withIndex().map { (index, page) ->
                val crop = page.cropBox
                Triple(index + 1, crop.width.toDouble() to crop.height.toDouble(), page.rotation)
            }
        }
        val computed = boxes.mapNotNull { (pageNum, dims, rot) ->
            val w = dims.first.toFloat()
            val h = dims.second.toFloat()
            val aspect = if (rot == 90 || rot == 270) h / w else w / h
            aspect.takeIf { it.isFinite() && it > 0 }?.let { pageNum to it }
        }.toMap()
        runCatching {
            database().use { db ->
                db.autoCommit = false
                try {
                    BookAspectStore.saveInTransaction(db, book.id, boxes)
                    db.commit()
                } catch (e: Exception) {
                    db.rollback()
                    throw e
                }
            }
        }
        if (aspectCache.size >= 16) aspectCache.clear()
        aspectCache[book.id] = computed
        return computed
    }

    private fun rawHighlightsFromDb(db: Connection, bookId: String, page: Int): List<Triple<Int, Int, String>> =
        db.prepareStatement("SELECT start,end,quote FROM book_highlights WHERE book=? AND page=? ORDER BY start,end").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, page)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(Triple(rows.getInt(1), rows.getInt(2), rows.getString(3)))
                }
            }
        }

    fun highlights(bookId: String, page: BookPage): List<ReaderTextRange> {
        val raw = pageHighlightRawCache[bookId to page.number]
            ?: database().use { db -> rawHighlightsFromDb(db, bookId, page.number) }.also {
                if (pageHighlightRawCache.size >= 64) pageHighlightRawCache.clear()
                pageHighlightRawCache[bookId to page.number] = it
            }
        if (raw.isEmpty()) return emptyList()
        return buildList {
            for ((start, end, quote) in raw) {
                if (start in 0 until end && end <= page.text.length &&
                    page.text.substring(start, end) == quote) add(ReaderTextRange(start, end))
            }
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
        if (highlighted) {
            val nodeId = "p${passage.page}"
            MasteryStore.recordEvidence(
                db = db,
                bookId = passage.book,
                tocNodeId = nodeId,
                page = passage.page,
                canonicalName = "第 ${passage.page} 页划线",
                signal = LearnerSignalType.HIGHLIGHT_NOTE,
                nowMs = clock.millis(),
            )
        }
        pageHighlightRawCache.remove(passage.book to passage.page)
    }

    @Synchronized fun allHighlights(bookId: String): List<BookHighlightRecord> = database().use { db ->
        db.prepareStatement("SELECT page,start,end,quote FROM book_highlights WHERE book=? ORDER BY page,start,end").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(BookHighlightRecord(rows.getInt(1), rows.getInt(2), rows.getInt(3), rows.getString(4)))
                }
            }
        }
    }

    @Synchronized fun allReviews(bookId: String): List<LocalBookReview> = database().use { db ->
        reviewStore.allForBook(db, bookId)
    }

    @Synchronized fun allAnswers(bookId: String): List<SavedBookAnswer> = database().use { db ->
        db.prepareStatement("SELECT lesson,mode,text,revealed,understood,guessed FROM answers WHERE book=? ORDER BY rowid").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val mode = runCatching { QuestionMode.valueOf(rows.getString(2)) }.getOrNull() ?: continue
                        val understoodRaw = rows.getObject(5)
                        val understood = if (understoodRaw == null) null else rows.getInt(5) != 0
                        add(
                            SavedBookAnswer(
                                lessonId = rows.getString(1),
                                mode = mode,
                                text = rows.getString(3),
                                revealed = rows.getInt(4) != 0,
                                understood = understood,
                                guessed = rows.getInt(6) != 0,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Load persisted reader notes for [bookId] in reading order. */
    @Synchronized fun allNotes(bookId: String): List<BookNoteRecord> = database().use { db ->
        db.prepareStatement(
            "SELECT id,version,page,start,end,quote,note,text_sha256,revision,deleted FROM book_notes WHERE book=? AND deleted=0 ORDER BY page,start,end,rowid",
        ).use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            BookNoteRecord(
                                id = rows.getString(1),
                                version = rows.getString(2),
                                pdfPage = rows.getInt(3),
                                start = rows.getInt(4),
                                end = rows.getInt(5),
                                quote = rows.getString(6),
                                note = rows.getString(7),
                                textSha256 = rows.getString(8),
                                revision = rows.getLong(9),
                                deleted = rows.getInt(10) != 0,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Persist a reader note atomically with CAS revision checks and mutationId idempotency. */
    @Synchronized fun saveNote(
        bookId: String,
        record: BookNoteRecord,
        baseRevision: Long,
        mutationId: String,
    ): BookNoteWriteResult = database().use { db ->
        db.autoCommit = false
        try {
            val existing = db.prepareStatement(
                "SELECT version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted FROM book_notes WHERE book=? AND id=?",
            ).use { sql ->
                sql.setString(1, bookId)
                sql.setString(2, record.id)
                sql.executeQuery().use { r ->
                    if (!r.next()) null else Triple(
                        BookNoteRecord(
                            id = record.id,
                            version = r.getString(1),
                            pdfPage = r.getInt(2),
                            start = r.getInt(3),
                            end = r.getInt(4),
                            quote = r.getString(5),
                            note = r.getString(6),
                            textSha256 = r.getString(7),
                            revision = r.getLong(8),
                            deleted = r.getInt(10) != 0,
                        ),
                        r.getLong(8),
                        r.getString(9),
                    )
                }
            }
            if (existing != null && existing.third == mutationId) {
                val stored = existing.first
                val matches = stored.version == record.version &&
                    stored.pdfPage == record.pdfPage &&
                    stored.start == record.start &&
                    stored.end == record.end &&
                    stored.quote == record.quote &&
                    stored.note == record.note &&
                    stored.textSha256 == record.textSha256 &&
                    stored.deleted == record.deleted
                db.rollback()
                return@use if (matches) BookNoteWriteResult.Saved(existing.second) else BookNoteWriteResult.Conflict
            }
            if (existing == null) {
                if (baseRevision != 0L) {
                    db.rollback()
                    return@use BookNoteWriteResult.Conflict
                }
                val rev = 1L
                db.prepareStatement(
                    """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                ).use { sql ->
                    sql.setString(1, bookId)
                    sql.setString(2, record.id)
                    sql.setString(3, record.version)
                    sql.setInt(4, record.pdfPage)
                    sql.setInt(5, record.start)
                    sql.setInt(6, record.end)
                    sql.setString(7, record.quote)
                    sql.setString(8, record.note)
                    sql.setString(9, record.textSha256)
                    sql.setLong(10, rev)
                    sql.setString(11, mutationId)
                    sql.setInt(12, if (record.deleted) 1 else 0)
                    sql.executeUpdate()
                }
                db.commit()
                return@use BookNoteWriteResult.Saved(rev)
            }
            if (baseRevision != existing.second) {
                db.rollback()
                return@use BookNoteWriteResult.Conflict
            }
            val nextRev = existing.second + 1L
            db.prepareStatement(
                """UPDATE book_notes SET version=?,page=?,start=?,end=?,quote=?,note=?,text_sha256=?,revision=?,last_mutation_id=?,deleted=?
                   WHERE book=? AND id=?""",
            ).use { sql ->
                sql.setString(1, record.version)
                sql.setInt(2, record.pdfPage)
                sql.setInt(3, record.start)
                sql.setInt(4, record.end)
                sql.setString(5, record.quote)
                sql.setString(6, record.note)
                sql.setString(7, record.textSha256)
                sql.setLong(8, nextRev)
                sql.setString(9, mutationId)
                sql.setInt(10, if (record.deleted) 1 else 0)
                sql.setString(11, bookId)
                sql.setString(12, record.id)
                sql.executeUpdate()
            }
            db.commit()
            BookNoteWriteResult.Saved(nextRev)
        } catch (error: Throwable) {
            runCatching { db.rollback() }
            throw error
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

    private fun stageCopyAndHash(source: Path, control: BookImportControl, stage: Path): Triple<String, String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesCopied = 0L
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(stage).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    control.check()
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                    output.write(buffer, 0, size)
                    bytesCopied += size
                }
            }
        }
        val format = BookFormats.detect(stage, source.fileName.toString())
        val id = digest.digest().joinToString("") { "%02x".format(it) }
        return Triple(id, format, bytesCopied)
    }

    private fun stagePreparedNonTier0(
        source: Path, stage: Path, id: String, format: String, fileBytes: Long, control: BookImportControl,
    ): StagedBookImport {
        val fallback = source.fileName.toString().substringBeforeLast('.')
        if (format == "pdf" && streamPdfExtras && extractBook == null) {
            return stageStreamedPdfBook(stage, id, fallback, control, source.fileName?.toString(), fileBytes)
        }
        val extracted = if (extractBook == null) {
            TextbookExtraction.extractForStaging(stage, format, fallback, control)
        } else {
            extractBook.invoke(stage, format, fallback, ocr, control)
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
        return saveStagedBook(stage, id, format, extracted, nativeSources, control, source.fileName?.toString(), fileBytes)
    }

    fun stageBookImport(source: Path, control: BookImportControl = BookImportControl()): StagedBookImport {
        control.report(0, 0)
        require(Files.isRegularFile(source)) { "请选择一份教材文件，文件夹不能直接导入。" }
        require(Files.size(source) > 0) { "这份教材是空文件，请重新保存或下载后再导入。" }
        require(Files.size(source) <= 256L * 1024 * 1024) { "请选择不超过 256 MB 的教材。" }
        val stage = directory.resolve(".import-${UUID.randomUUID()}")
        try {
            val (id, format, fileBytes) = stageCopyAndHash(source, control, stage)
            if (catalog().any { it.id == id }) return StagedBookImport(book(id), alreadyExisted = true)
            return stagePreparedNonTier0(source, stage, id, format, fileBytes, control)
        } finally { Files.deleteIfExists(stage) }
    }

    private data class Tier0PdfPayload(
        val title: String,
        val totalPages: Int,
        val outline: List<BookOutlineItem>,
        val aspects: List<Triple<Int, Pair<Double, Double>, Int>>,
        val tier0Pages: List<BookPage>,
        val initialReadingPage: Int,
    )

    internal fun initialBodyPage(pages: List<BookPage>, structure: BookStructure?): Int =
        BookBoundaries.bodyStart(pages, structure).importPage

    fun stageBookImportTier0(source: Path, control: BookImportControl = BookImportControl()): StagedBookImport {
        control.report(0, 0)
        require(Files.isRegularFile(source)) { "请选择一份教材文件，文件夹不能直接导入。" }
        require(Files.size(source) > 0) { "这份教材是空文件，请重新保存或下载后再导入。" }
        require(Files.size(source) <= 256L * 1024 * 1024) { "请选择不超过 256 MB 的教材。" }
        val stage = directory.resolve(".import-${UUID.randomUUID()}")
        try {
            val (id, format, fileBytes) = stageCopyAndHash(source, control, stage)
            if (catalog().any { it.id == id }) return StagedBookImport(book(id), alreadyExisted = true)
            if (format != "pdf" || extractBook != null) {
                return stagePreparedNonTier0(source, stage, id, format, fileBytes, control)
            }
            val fallback = source.fileName.toString().substringBeforeLast('.')
            var payloadReady = false
            return try {
                BookPdfLoader.readAndTransfer(
                    stageFile = stage.toFile(),
                    extract = { pdf ->
                        val totalPages = pdf.numberOfPages
                        require(totalPages in 1..5000) { "这本 PDF 的页数暂不支持。" }
                        require(pdf.currentAccessPermission.canExtractContent()) { "这本 PDF 限制了文字提取，请使用有阅读和提取权限的教材。" }
                        val bookTitle = pdf.documentInformation.title?.trim()?.takeIf(String::isNotEmpty) ?: fallback
                        val outline = extractBookPdfOutline(pdf)
                        val aspects = pdf.pages.withIndex().map { (index, sheet) ->
                            val crop = sheet.cropBox
                            Triple(index + 1, crop.width.toDouble() to crop.height.toDouble(), sheet.rotation)
                        }
                        val plainExtractor = ReusablePlainPdfTextExtractor()
                        val extractedMap = LinkedHashMap<Int, BookPage>()
                        fun extractPage(pageNum: Int): BookPage = extractedMap.getOrPut(pageNum) {
                            control.check()
                            BookPage(pageNum, plainExtractor.extractPage(pdf, pageNum))
                        }
                        val tier0End = minOf(3, totalPages)
                        for (pageNum in 1..tier0End) extractPage(pageNum)
                        val outlineStructure = BookTocWaterfall.buildTier0Structure(id, bookTitle, totalPages, outline)
                        val outlineChapterPage = BookBoundaries.firstChapter(outlineStructure.tocNodes)?.startPage
                        val probeStart = outlineChapterPage ?: 1
                        val nextChapterPage = outlineStructure.tocNodes.firstOrNull {
                            it.kind == TocNodeKind.CHAPTER && it.startPage > probeStart
                        }?.startPage
                        val probeEnd = minOf(probeStart + 31, totalPages, (nextChapterPage ?: totalPages + 1) - 1)
                        val initialPage = (probeStart..probeEnd).firstOrNull { p ->
                            !BookBoundaries.looksLikeFrontMatter(extractPage(p).text)
                        } ?: probeStart
                        for (p in initialPage..minOf(initialPage + 2, totalPages)) extractPage(p)
                        payloadReady = true
                        Tier0PdfPayload(bookTitle, totalPages, outline, aspects, extractedMap.values.sortedBy { it.number }, initialPage)
                    },
                    commitAndTarget = { payload ->
                        control.check()
                        val tier0ByPage = payload.tier0Pages.associateBy { it.number }
                        val allTier0Pages = (1..payload.totalPages).map { pageNum ->
                            tier0ByPage[pageNum] ?: BookPage(pageNum, "")
                        }
                        val tier0Structure = BookTocWaterfall.buildTier0Structure(id, payload.title, payload.totalPages, payload.outline)
                            .copy(nativeBodyStart = BookPageAnchor(payload.initialReadingPage)).also {
                            it.deferResolvedLessons { it.toLessons(id, allTier0Pages) }
                        }
                        val startPage = payload.initialReadingPage.coerceIn(1, payload.totalPages)
                        val target = original(id, "pdf")
                        var moved = false
                        database().use { db ->
                            db.autoCommit = false
                            try {
                                db.prepareStatement(
                                    "INSERT INTO books(id,title,format,mode,touched,reading_page,index_state,material_kind,source_filename,file_bytes) VALUES(?,?,?,?,?,?,?,?,?,?)",
                                ).use { sql ->
                                    sql.setString(1, id); sql.setString(2, payload.title); sql.setString(3, "pdf")
                                    sql.setString(4, QuestionMode.CHOICE.name); sql.setLong(5, System.currentTimeMillis())
                                    sql.setInt(6, startPage); sql.setString(7, "TIER0"); sql.setString(8, "BOOK")
                                    sql.setString(9, source.fileName?.toString()); sql.setLong(10, fileBytes)
                                    sql.executeUpdate()
                                }
                                db.prepareStatement("INSERT INTO pages(book,page,text,ocr,ocr_pending,text_pending) VALUES(?,?,?,?,?,?)").use { sql ->
                                    for (pageNum in 1..payload.totalPages) {
                                        val pageObj = tier0ByPage[pageNum]
                                        sql.setString(1, id)
                                        sql.setInt(2, pageNum)
                                        sql.setString(3, pageObj?.text ?: "")
                                        sql.setInt(4, 0)
                                        sql.setInt(5, 0)
                                        sql.setInt(6, if (pageObj == null) 1 else 0)
                                        sql.addBatch()
                                    }
                                    sql.executeBatch()
                                }
                                BookAspectStore.saveInTransaction(db, id, payload.aspects)
                                BookStructureStore.saveStructure(db, id, tier0Structure)
                                BookFtsStore.rebuildBook(db, id, payload.tier0Pages)
                                control.check()
                                Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING)
                                moved = true
                                control.check()
                                db.commit()
                            } catch (error: Throwable) {
                                db.rollback()
                                if (moved) runCatching { Files.deleteIfExists(target) }
                                throw error
                            }
                        }
                        cacheAspects(id, payload.aspects)
                        val inMemoryBook = Textbook(
                            id = id,
                            title = payload.title,
                            format = "pdf",
                            pages = allTier0Pages,
                            readingPage = startPage,
                            lessonIndex = 0,
                            learning = false,
                            promptSeen = false,
                            questionMode = QuestionMode.CHOICE,
                            structure = tier0Structure,
                            indexState = "TIER0",
                        )
                        target.toFile() to StagedBookImport(inMemoryBook, alreadyExisted = false)
                    },
                )
            } catch (e: BookPdfPasswordException) {
                throw IllegalArgumentException("这本 PDF 有密码保护，请取消密码后再导入。", e)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                if (!payloadReady) throw IllegalArgumentException("这份教材不完整或已损坏，请重新保存或下载后再导入。", e)
                else throw e
            }
        } finally { Files.deleteIfExists(stage) }
    }

    private fun cacheAspects(bookId: String, aspects: List<Triple<Int, Pair<Double, Double>, Int>>) {
        val aspectMap = aspects.mapNotNull { (pageNum, dims, rot) ->
            val w = dims.first.toFloat()
            val h = dims.second.toFloat()
            val aspect = if (rot == 90 || rot == 270) h / w else w / h
            aspect.takeIf { it.isFinite() && it > 0 }?.let { pageNum to it }
        }.toMap()
        if (aspectMap.isNotEmpty()) {
            if (aspectCache.size >= 16) aspectCache.clear()
            aspectCache[bookId] = aspectMap
        }
    }

    private fun rebuildFtsIfMissing(db: Connection, bookId: String, pages: List<BookPage>) {
        if (!BookSchemaMigration.tableExists(db, "pages_fts")) return
        if (pages.none { it.text.isNotEmpty() }) return
        val hasRows = runCatching {
            db.prepareStatement("SELECT 1 FROM pages_fts WHERE book=? LIMIT 1").use { sql ->
                sql.setString(1, bookId)
                sql.executeQuery().use { it.next() }
            }
        }.getOrDefault(true)
        if (!hasRows) {
            BookFtsStore.rebuildBook(db, bookId, pages)
        }
    }

    private fun extractTier1Page(
        pdf: BookPdfDocument,
        pageNum: Int,
        existing: BookPage?,
        preserveExisting: Boolean,
        plainExtractor: ReusablePlainPdfTextExtractor?,
    ): Pair<BookPage, List<PageLineTypography>?> {
        // Legacy v1 upgrades preserve existing pages.text verbatim so highlight/note UTF-16 anchors remain valid.
        if (existing != null && (existing.ocr || preserveExisting)) {
            val typoLines = if (!existing.ocr && plainExtractor == null) {
                PdfPageTextExtraction.extractPlainTextAndTypography(pdf, pageNum).lines.takeIf { it.isNotEmpty() }
            } else null
            return existing to typoLines
        }
        return if (plainExtractor != null) {
            val text = plainExtractor.extractPage(pdf, pageNum)
            BookPage(
                pageNum, text,
                ocr = false,
                illustrations = existing?.illustrations.orEmpty(),
                textLayout = existing?.textLayout,
                ocrPending = text.isBlank(),
            ) to null
        } else {
            val fast = PdfPageTextExtraction.extractPlainTextAndTypography(pdf, pageNum)
            BookPage(
                pageNum, fast.text,
                ocr = false,
                illustrations = existing?.illustrations.orEmpty(),
                textLayout = existing?.textLayout,
                ocrPending = fast.text.isBlank(),
            ) to fast.lines.takeIf { it.isNotEmpty() }
        }
    }

    /** Tier 1 background full-text, typography, Stage A/B/C TOC, and FTS5 indexing without holding `@Synchronized`. */
    fun indexBookTier1(
        bookId: String,
        control: BookImportControl = BookImportControl(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Textbook {
        val current = book(bookId)
        if (current.format != "pdf" || current.indexState == "READY") return current
        val isLegacyTocRebuild = current.indexState == "PENDING_TOC"
        val sourceFile = original(bookId, "pdf")
        if (isLegacyTocRebuild && !Files.isRegularFile(sourceFile)) {
            return completePendingTocFromPersistedPages(bookId, current, control, onProgress)
        }
        require(Files.isRegularFile(sourceFile)) { "原文件已丢失或损坏，请重新导入《${current.title}》。" }
        val existingByPage = current.pages.associateBy { it.number }
        val needAspects = isLegacyTocRebuild && database().use { BookAspectStore.loadAspects(it, bookId).isEmpty() }
        val file = sourceFile.toFile()
        val (initialCount, outline, extractedAspects) = BookPdfLoader.readCached(file) { pdf ->
            val aspects = if (needAspects) {
                pdf.pages.withIndex().map { (index, sheet) ->
                    val crop = sheet.cropBox
                    Triple(index + 1, crop.width.toDouble() to crop.height.toDouble(), sheet.rotation)
                }
            } else emptyList()
            Triple(pdf.numberOfPages, extractBookPdfOutline(pdf), aspects)
        }
        val needTypography = !BookTocWaterfall.willStageASucceed(outline, initialCount)
        val preserveExistingPages = isLegacyTocRebuild && (1..initialCount).all { p -> existingByPage[p] != null }
        val indexedPages = ArrayList<BookPage>(initialCount)
        val typographyLines = LinkedHashMap<Int, List<PageLineTypography>>()

        if (preserveExistingPages && !needTypography) {
            for (pageNum in 1..initialCount) {
                control.check()
                indexedPages += existingByPage.getValue(pageNum)
            }
            onProgress(initialCount, initialCount)
        } else {
            val workers = if (initialCount >= 40) bookPdfTier1Parallelism.coerceAtMost(initialCount) else 1
            if (workers > 1) {
                val pdfBytes = if (file.length() in 1L..(16L * 1024 * 1024)) Files.readAllBytes(sourceFile) else null
                val sliceSize = (initialCount + workers - 1) / workers
                val slices = (1..initialCount).chunked(sliceSize)
                val doneCount = AtomicInteger(0)
                val pool = Executors.newFixedThreadPool(slices.size) { r ->
                    Thread(r, "coach-tier1-worker").apply { isDaemon = true }
                }
                try {
                    val futures = slices.map { slice ->
                        pool.submit(Callable<Pair<List<BookPage>, List<Pair<Int, List<PageLineTypography>>>>> {
                            val localPages = ArrayList<BookPage>(slice.size)
                            val localTypo = ArrayList<Pair<Int, List<PageLineTypography>>>()
                            val doc = if (pdfBytes != null) BookPdfLoader.load(pdfBytes) else BookPdfLoader.load(file)
                            doc.use { pdf ->
                                val plainExtractor = if (!needTypography) ReusablePlainPdfTextExtractor() else null
                                for (pageNum in slice) {
                                    control.check()
                                    val existing = existingByPage[pageNum]
                                    val preserve = preserveExistingPages ||
                                        (isLegacyTocRebuild && existing != null && !existing.ocr && existing.text.isNotBlank())
                                    val (page, lines) = extractTier1Page(pdf, pageNum, existing, preserve, plainExtractor)
                                    localPages += page
                                    if (lines != null) localTypo += pageNum to lines
                                    val done = doneCount.incrementAndGet()
                                    if (done % 10 == 0 || done == initialCount) {
                                        onProgress(done, initialCount)
                                    }
                                }
                            }
                            localPages to localTypo
                        })
                    }
                    for (f in futures) {
                        val (pagesChunk, typoChunk) = try {
                            f.get()
                        } catch (e: java.util.concurrent.ExecutionException) {
                            throw (e.cause ?: e)
                        }
                        indexedPages.addAll(pagesChunk)
                        for ((p, lines) in typoChunk) typographyLines[p] = lines
                    }
                } finally {
                    pool.shutdownNow()
                }
            } else {
                val batches = (1..initialCount).chunked(200)
                for (batch in batches) {
                    control.check()
                    BookPdfLoader.read(file) { pdf ->
                        val plainExtractor = if (!needTypography) ReusablePlainPdfTextExtractor() else null
                        for (pageNum in batch) {
                            control.check()
                            val existing = existingByPage[pageNum]
                            val preserve = preserveExistingPages ||
                                (isLegacyTocRebuild && existing != null && !existing.ocr && existing.text.isNotBlank())
                            val (page, lines) = extractTier1Page(pdf, pageNum, existing, preserve, plainExtractor)
                            indexedPages += page
                            if (lines != null) typographyLines[pageNum] = lines
                            if (pageNum % 10 == 0 || pageNum == initialCount) {
                                onProgress(pageNum, initialCount)
                            }
                        }
                    }
                }
            }
        }
        if (!preserveExistingPages) {
            require(indexedPages.count { it.ocrPending } <= BookScanImages.MAX_OCR_PAGES) {
                "扫描页超过 ${BookScanImages.MAX_OCR_PAGES} 页，请把 PDF 拆分后再导入。"
            }
        }
        control.check()
        val bodyStart = current.structure?.nativeBodyStart?.takeIf { outline.isNotEmpty() }
        val structure = BookStructurePlanner.plan(bookId, current.title, "pdf", indexedPages, outline, typographyLines,
            nativeBodyStart = bodyStart)
        var readingPage = current.readingPage
        var lessonIndex = current.lessonIndex
        var learning = current.learning
        var promptSeen = current.promptSeen
        var questionMode = current.questionMode
        database().use { db ->
            db.autoCommit = false
            try {
                val meta = db.prepareStatement("SELECT reading_page,lesson_index,learning,prompt_seen,mode FROM books WHERE id=?").use { sql ->
                    sql.setString(1, bookId)
                    sql.executeQuery().use { row ->
                        if (!row.next()) null
                        else arrayOf<Any>(
                            row.getInt(1),
                            row.getInt(2),
                            row.getInt(3) == 1,
                            row.getInt(4) == 1,
                            QuestionMode.valueOf(row.getString(5)),
                        )
                    }
                } ?: throw BookImportCancelled()
                val rawReadingPage = meta[0] as Int
                readingPage = if (indexedPages.any { it.number == rawReadingPage }) rawReadingPage else indexedPages.first().number
                lessonIndex = meta[1] as Int
                learning = meta[2] as Boolean
                promptSeen = meta[3] as Boolean
                questionMode = meta[4] as QuestionMode
                if (!preserveExistingPages) {
                    db.prepareStatement(
                        """INSERT INTO pages(book,page,text,ocr,ocr_pending,text_pending) VALUES(?,?,?,?,?,0)
                           ON CONFLICT(book,page) DO UPDATE SET
                           text=CASE WHEN pages.ocr=1 THEN pages.text ELSE excluded.text END,
                           ocr_pending=CASE WHEN pages.ocr=1 THEN 0 ELSE excluded.ocr_pending END,
                           text_pending=0""".trimIndent(),
                    ).use { sql ->
                        for (page in indexedPages) {
                            sql.setString(1, bookId)
                            sql.setInt(2, page.number)
                            sql.setString(3, page.text)
                            sql.setInt(4, if (page.ocr) 1 else 0)
                            sql.setInt(5, if (page.ocrPending) 1 else 0)
                            sql.addBatch()
                        }
                        sql.executeBatch()
                    }
                }
                if (extractedAspects.isNotEmpty()) {
                    BookAspectStore.saveInTransaction(db, bookId, extractedAspects)
                }
                BookStructureStore.saveStructure(db, bookId, structure)
                if (!preserveExistingPages) {
                    BookFtsStore.rebuildBook(db, bookId, indexedPages)
                } else {
                    rebuildFtsIfMissing(db, bookId, indexedPages)
                }
                lessonIndex = lessonIndex.coerceIn(0, structure.resolvedLessons.size)
                if (isLegacyTocRebuild) {
                    db.prepareStatement("UPDATE books SET index_state='READY', lesson_index=? WHERE id=?").use { sql ->
                        sql.setInt(1, lessonIndex)
                        sql.setString(2, bookId)
                        sql.executeUpdate()
                    }
                } else {
                    db.prepareStatement("UPDATE books SET index_state='READY', touched=?, lesson_index=? WHERE id=?").use { sql ->
                        sql.setLong(1, System.currentTimeMillis())
                        sql.setInt(2, lessonIndex)
                        sql.setString(3, bookId)
                        sql.executeUpdate()
                    }
                }
                control.check()
                db.commit()
            } catch (error: Throwable) {
                db.rollback()
                throw error
            }
        }
        if (extractedAspects.isNotEmpty()) {
            cacheAspects(bookId, extractedAspects)
        }
        return Textbook(
            id = bookId,
            title = current.title,
            format = "pdf",
            pages = indexedPages,
            readingPage = readingPage,
            lessonIndex = lessonIndex,
            learning = learning,
            promptSeen = promptSeen,
            questionMode = questionMode,
            structure = structure,
            indexState = "READY",
            correctedBodyPage = current.correctedBodyPage,
        )
    }

    private fun completePendingTocFromPersistedPages(
        bookId: String,
        current: Textbook,
        control: BookImportControl,
        onProgress: (Int, Int) -> Unit,
    ): Textbook {
        if (current.pages.isEmpty()) {
            database().use { db ->
                db.prepareStatement("UPDATE books SET index_state='READY' WHERE id=?").use { sql ->
                    sql.setString(1, bookId)
                    sql.executeUpdate()
                }
            }
            return current.copy(indexState = "READY")
        }
        control.check()
        val structure = BookStructurePlanner.plan(bookId, current.title, "pdf", current.pages)
        var readingPage = current.readingPage
        var lessonIndex = current.lessonIndex
        var learning = current.learning
        var promptSeen = current.promptSeen
        var questionMode = current.questionMode
        database().use { db ->
            db.autoCommit = false
            try {
                val meta = db.prepareStatement("SELECT reading_page,lesson_index,learning,prompt_seen,mode FROM books WHERE id=?").use { sql ->
                    sql.setString(1, bookId)
                    sql.executeQuery().use { row ->
                        if (!row.next()) null
                        else arrayOf<Any>(
                            row.getInt(1),
                            row.getInt(2),
                            row.getInt(3) == 1,
                            row.getInt(4) == 1,
                            QuestionMode.valueOf(row.getString(5)),
                        )
                    }
                } ?: throw BookImportCancelled()
                val rawReadingPage = meta[0] as Int
                readingPage = if (current.pages.any { it.number == rawReadingPage }) rawReadingPage else current.pages.first().number
                lessonIndex = (meta[1] as Int).coerceIn(0, structure.resolvedLessons.size)
                learning = meta[2] as Boolean
                promptSeen = meta[3] as Boolean
                questionMode = meta[4] as QuestionMode
                BookStructureStore.saveStructure(db, bookId, structure)
                rebuildFtsIfMissing(db, bookId, current.pages)
                db.prepareStatement("UPDATE books SET index_state='READY', lesson_index=? WHERE id=?").use { sql ->
                    sql.setInt(1, lessonIndex)
                    sql.setString(2, bookId)
                    sql.executeUpdate()
                }
                control.check()
                db.commit()
            } catch (error: Throwable) {
                db.rollback()
                throw error
            }
        }
        onProgress(current.pages.size, current.pages.size)
        return Textbook(
            id = bookId,
            title = current.title,
            format = "pdf",
            pages = current.pages,
            readingPage = readingPage,
            lessonIndex = lessonIndex,
            learning = learning,
            promptSeen = promptSeen,
            questionMode = questionMode,
            structure = structure,
            indexState = "READY",
        )
    }

    /** Tier 2 lazy per-page visual extraction (idempotent via `book_visual_pages`). Never holds `@Synchronized`. */
    fun ensurePdfVisualsForPages(
        bookId: String,
        pageNumbers: Collection<Int>,
        control: BookImportControl = BookImportControl(),
    ): Boolean {
        val requested = pageNumbers.filter { it >= 1 }.distinct()
        if (requested.isEmpty()) return false
        val prep = database().use { db ->
            val fmt = db.prepareStatement("SELECT format FROM books WHERE id=?").use { sql ->
                sql.setString(1, bookId)
                sql.executeQuery().use { if (it.next()) it.getString(1) else null }
            } ?: return@use null
            if (fmt != "pdf") return@use null
            val extractedSet = BookVisualPageStore.extractedPages(db, bookId)
            val missing = requested.filter { it !in extractedSet }
            if (missing.isEmpty()) return@use null
            val readyPages = missing.mapNotNull { pageNum ->
                db.prepareStatement("SELECT text, ocr, ocr_pending, text_pending FROM pages WHERE book=? AND page=?").use { sql ->
                    sql.setString(1, bookId)
                    sql.setInt(2, pageNum)
                    sql.executeQuery().use { row ->
                        if (!row.next()) null
                        else {
                            val ocrPending = row.getInt("ocr_pending") == 1
                            val textPending = runCatching { row.getInt("text_pending") == 1 }.getOrDefault(false)
                            if (ocrPending || textPending) null
                            else BookPage(pageNum, row.getString("text"), ocr = row.getInt("ocr") == 1)
                        }
                    }
                }
            }
            if (readyPages.isEmpty()) return@use null
            val existingAssets = if (BookSchemaMigration.tableExists(db, "book_illustrations")) {
                db.prepareStatement("SELECT DISTINCT image_sha256, extension FROM book_illustrations WHERE book=?").use { sql ->
                    sql.setString(1, bookId)
                    sql.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
                    }
                }
            } else emptyList()
            val existingCount = if (BookSchemaMigration.tableExists(db, "book_illustrations")) {
                db.prepareStatement("SELECT COUNT(*) FROM book_illustrations WHERE book=?").use { sql ->
                    sql.setString(1, bookId)
                    sql.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
                }
            } else 0
            val existingBytes = existingAssets.sumOf { (sha, ext) ->
                runCatching { Files.size(BookIllustrationAssets.imageFile(directory, bookId, sha, ext)) }.getOrDefault(0L)
            }
            Triple(readyPages, existingCount, existingBytes)
        } ?: return false

        val (readyPages, existingCount, existingBytes) = prep
        val pdfFile = original(bookId, "pdf").toFile()
        if (!pdfFile.isFile) return false
        val accepted = BookPdfLoader.read(pdfFile) { pdf ->
            val raw = importedPdfVisuals(pdf, readyPages, control)
            var count = existingCount
            var bytes = existingBytes
            buildList {
                for (item in raw) {
                    if (count >= FormatBookImages.MAX_IMAGES || bytes + item.bytes.size > FormatBookImages.MAX_TOTAL) break
                    count++
                    bytes += item.bytes.size
                    add(item)
                }
            }
        }
        val newFiles = accepted.map { BookIllustrationAssets.imageFile(directory, bookId, it.contentSha256, it.extension) }
            .distinct().filterNot(Files::exists)
        database().use { db ->
            db.autoCommit = false
            try {
                val stillExists = db.prepareStatement("SELECT 1 FROM books WHERE id=?").use { sql ->
                    sql.setString(1, bookId)
                    sql.executeQuery().use { it.next() }
                }
                if (!stillExists) {
                    db.rollback()
                    return false
                }
                if (accepted.isNotEmpty()) {
                    BookIllustrationStore.saveInTransaction(
                        db, directory, bookId, readyPages.associate { it.number to it.text }, accepted, ensureSchema = false,
                    )
                }
                BookVisualPageStore.markExtracted(db, bookId, readyPages.map { it.number })
                db.commit()
            } catch (error: Throwable) {
                db.rollback()
                newFiles.forEach { Files.deleteIfExists(it) }
                throw error
            }
        }
        return true
    }

    @Synchronized
    private fun stageStreamedPdfBook(
        stage: Path, id: String, fallback: String, control: BookImportControl,
        sourceFilename: String? = null, fileBytes: Long = Files.size(stage),
    ): StagedBookImport {
        if (catalog().any { it.id == id }) return StagedBookImport(book(id), alreadyExisted = true)
        val target = original(id, "pdf")
        val newImages = mutableSetOf<Path>()
        database().use { db ->
            db.autoCommit = false
            var moved = false
            try {
                fun saveExtras(texts: Map<Int, String>, images: List<ExtractedIllustration>, layouts: List<PageTextLayout>) {
                    for (image in images) {
                        val path = BookIllustrationAssets.imageFile(directory, id, image.contentSha256, image.extension)
                        if (!Files.exists(path)) newImages.add(path)
                    }
                    BookPageModelStore.saveInTransaction(db, directory, id, texts, images, layouts, ensureSchema = false)
                }
                val extracted = TextbookExtraction.extractForStaging(stage, "pdf", fallback, control) { page, images ->
                    saveExtras(mapOf(page.number to page.text), images, listOfNotNull(page.textLayout))
                }
                require(extracted.pages.isNotEmpty()) { "教材中没有可打开的页面。" }
                val structure = BookStructurePlanner.plan(id, extracted.title, "pdf", extracted.pages, extracted.outline)
                val startPage = initialBodyPage(extracted.pages, structure)
                control.check()
                db.prepareStatement("INSERT INTO books(id,title,format,mode,touched,reading_page,index_state,material_kind,source_filename,file_bytes) VALUES(?,?,?,?,?,?,?,?,?,?)").use { sql ->
                    sql.setString(1, id); sql.setString(2, extracted.title); sql.setString(3, "pdf")
                    sql.setString(4, QuestionMode.CHOICE.name); sql.setLong(5, System.currentTimeMillis())
                    sql.setInt(6, startPage); sql.setString(7, "READY")
                    sql.setString(8, "BOOK"); sql.setString(9, sourceFilename); sql.setLong(10, fileBytes)
                    sql.executeUpdate()
                }
                db.prepareStatement("INSERT INTO pages(book,page,text,ocr,ocr_pending,text_pending) VALUES(?,?,?,?,?,0)").use { sql ->
                    for (page in extracted.pages) {
                        sql.setString(1, id); sql.setInt(2, page.number); sql.setString(3, page.text)
                        sql.setInt(4, if (page.ocr) 1 else 0); sql.setInt(5, if (page.ocrPending) 1 else 0)
                        sql.addBatch()
                    }
                    sql.executeBatch()
                }
                saveExtras(
                    extracted.pages.associate { it.number to it.text },
                    extracted.illustrations, extracted.pages.mapNotNull { it.textLayout },
                )
                BookStructureStore.saveStructure(db, id, structure)
                BookFtsStore.rebuildBook(db, id, extracted.pages)
                control.check()
                Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING)
                moved = true
                control.check()
                db.commit()
            } catch (error: Throwable) {
                db.rollback()
                newImages.forEach { Files.deleteIfExists(it) }
                if (moved) Files.deleteIfExists(target)
                throw error
            }
        }
        return StagedBookImport(book(id), alreadyExisted = false)
    }

    @Synchronized
    private fun saveStagedBook(
        stage: Path, id: String, format: String, extracted: ExtractedBook,
        nativeSources: ByteArray?, control: BookImportControl,
        sourceFilename: String? = null, fileBytes: Long = Files.size(stage),
    ): StagedBookImport {
        if (catalog().any { it.id == id }) return StagedBookImport(book(id), alreadyExisted = true)
        val structure = BookStructurePlanner.plan(id, extracted.title, format, extracted.pages, extracted.outline,
            nativeBodyStart = extracted.nativeBodyStart)
        val startPage = initialBodyPage(extracted.pages, structure)
        val target = original(id, format)
        // The immutable source is in place before the single transaction makes it visible.
        Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING)
        val newImages = extracted.illustrations.map {
            BookIllustrationAssets.imageFile(directory, id, it.contentSha256, it.extension)
        }.distinct().filterNot(Files::exists)
        database().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("INSERT INTO books(id,title,format,mode,touched,reading_page,index_state,material_kind,source_filename,file_bytes) VALUES(?,?,?,?,?,?,?,?,?,?)").use { sql ->
                    sql.setString(1, id); sql.setString(2, extracted.title); sql.setString(3, format)
                    sql.setString(4, QuestionMode.CHOICE.name); sql.setLong(5, System.currentTimeMillis())
                    sql.setInt(6, startPage); sql.setString(7, "READY")
                    sql.setString(8, "BOOK"); sql.setString(9, sourceFilename); sql.setLong(10, fileBytes)
                    sql.executeUpdate()
                }
                db.prepareStatement("INSERT INTO pages(book,page,text,ocr,ocr_pending,text_pending) VALUES(?,?,?,?,?,0)").use { sql ->
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
                BookStructureStore.saveStructure(db, id, structure)
                BookFtsStore.rebuildBook(db, id, extracted.pages)
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
        val file = sourceFile.toFile()
        for (batch in pending.chunked(25)) {
            control.check()
            try {
                BookPdfLoader.read(file) { pdf ->
                    for (pageNumber in batch) {
                        control.check()
                        val etaBefore = estimator.estimateRemainingSeconds(pending.size - completedInSession)
                        OcrTiming.measure("page$pageNumber.progress") {
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
                        }
                        val started = System.nanoTime()
                        val bounds = pdf.getPage(pageNumber - 1).cropBox
                        require(bounds.width.isFinite() && bounds.height.isFinite() && bounds.width > 0 && bounds.height > 0) {
                            "这页 PDF 尺寸无效，请重新保存后导入。"
                        }
                        val scale = minOf(bookOcrTargetDpi / 72f, bookOcrMaxSide / maxOf(bounds.width, bounds.height))
                        BookScanImages.checkSize(kotlin.math.ceil(bounds.width * scale).toInt(), kotlin.math.ceil(bounds.height * scale).toInt())
                        val image = OcrTiming.measure("page$pageNumber.render") {
                            renderBookPdfPage(file, pdf, pageNumber - 1, scale)
                        }
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
                db.prepareStatement("UPDATE pages SET text=?,ocr=1,ocr_pending=0,text_pending=0 WHERE book=? AND page=?").use { sql ->
                    sql.setString(1, text); sql.setString(2, bookId); sql.setInt(3, pageNumber); sql.executeUpdate()
                }
                db.prepareStatement("UPDATE books SET touched=? WHERE id=?").use { sql ->
                    sql.setLong(1, System.currentTimeMillis()); sql.setString(2, bookId); sql.executeUpdate()
                }
                BookFtsStore.upsertPage(db, bookId, pageNumber, text)
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

    @Synchronized fun deleteBook(bookId: String, initiatingAccountKey: String? = null) {
        firstRenderGates.remove(bookId)
        aspectCache.remove(bookId)
        savedTextViewCache.remove(bookId)
        pdfScrollModeCache.remove(bookId)
        textScrollModeCache.remove(bookId)
        readSpotCache.remove(bookId)
        pageHighlightRawCache.keys.removeIf { it.first == bookId }
        synchronized(textLayerLock) {
            textLayerCache.keys.removeIf { it.first == bookId }
        }
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
                BookFtsStore.deleteBook(db, bookId)
                for (table in listOf(
                    "pages", "book_illustrations", "book_page_text_boxes", "book_native_sources",
                    "book_learning_paths", "book_ladders", "book_reader_views", "book_pdf_modes",
                    "book_highlights", "book_notes", "answers", "book_reviews", "book_structures", "book_section_cache", "book_body_start",
                    "book_open_session", "book_toc_nodes", "book_study_chunks", "book_bookmarks",
                    "book_page_aspects", "book_visual_pages",
                )) {
                    val exists = db.createStatement().use { s -> s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table'").use { it.next() } }
                    if (exists) db.prepareStatement("DELETE FROM $table WHERE book=?").use { it.setString(1, bookId); it.executeUpdate() }
                }
                if (BookSchemaMigration.tableExists(db, "collection_materials")) {
                    db.prepareStatement("DELETE FROM collection_materials WHERE material_id=?").use { it.setString(1, bookId); it.executeUpdate() }
                }
                MasteryStore.deleteForBook(db, bookId)
                BookUploadQueueStore.deleteLocalUnsyncedForAccount(db, bookId, initiatingAccountKey)
                val hasVisualReviews = db.createStatement().use { s -> s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='book_pdf_visual_reviews'").use { it.next() } }
                if (hasVisualReviews) db.prepareStatement("DELETE FROM book_pdf_visual_reviews WHERE book=?").use { it.setString(1, bookId); it.executeUpdate() }
                db.prepareStatement("DELETE FROM book_review_position WHERE book=?").use { it.setString(1, bookId); it.executeUpdate() }
                db.prepareStatement("DELETE FROM books WHERE id=?").use { it.setString(1, bookId); it.executeUpdate() }
                db.commit()
            } catch (error: Exception) { db.rollback(); throw error }
            fmt
        }
        if (format != null && format in BookFormats.supported) {
            val filePath = original(bookId, format)
            if (format == "pdf") BookPdfLoader.evict(filePath.toFile())
            runCatching { Files.deleteIfExists(filePath) }
        }
        runCatching {
            val cacheDir = directory.resolve(".render-cache")
            if (Files.isDirectory(cacheDir)) {
                Files.list(cacheDir).use { stream ->
                    stream.filter { it.fileName.toString().startsWith("$bookId-") }.forEach { Files.deleteIfExists(it) }
                }
            }
        }
        runCatching { directory.resolve(bookId).toFile().deleteRecursively() }
    }

    @Synchronized fun reorganizeSections(bookId: String): Textbook {
        val current = book(bookId)
        val file = runCatching { original(bookId, current.format) }.getOrNull()?.takeIf(Files::isRegularFile)
        var typographyLines: Map<Int, List<PageLineTypography>> = emptyMap()
        val epubNavigation = if (file != null && current.format == "epub") TextbookExtraction.epubNavigation(file, current.pages) else null
        val outline = if (file == null) emptyList() else when (current.format) {
            "pdf" -> runCatching {
                BookPdfLoader.read(file.toFile()) { pdf ->
                    val items = extractBookPdfOutline(pdf)
                    if (items.isEmpty()) {
                        typographyLines = buildMap {
                            for (p in 1..minOf(pdf.numberOfPages, 5000)) {
                                if (current.page(p)?.ocr != true) {
                                    val fast = PdfPageTextExtraction.extractPlainTextAndTypography(pdf, p)
                                    if (fast.lines.isNotEmpty()) put(p, fast.lines)
                                }
                            }
                        }
                    }
                    items
                }
            }.getOrDefault(emptyList())
            "epub" -> epubNavigation?.outline.orEmpty()
            "docx" -> BookStructurePlanner.extractWordOutline(file, current.pages)
            else -> emptyList()
        }
        val structure = BookStructurePlanner.plan(current.id, current.title, current.format, current.pages, outline, typographyLines, epubNavigation?.nativeBodyStart)
        val clampedIndex = current.lessonIndex.coerceIn(0, structure.resolvedLessons.size)
        database().use { db ->
            db.autoCommit = false
            try {
                BookStructureStore.saveStructure(db, bookId, structure)
                BookStructureStore.clearGuides(db, bookId)
                db.prepareStatement("UPDATE books SET lesson_index=?, touched=? WHERE id=?").use { sql ->
                    sql.setInt(1, clampedIndex); sql.setLong(2, System.currentTimeMillis()); sql.setString(3, bookId)
                    sql.executeUpdate()
                }
                db.commit()
            } catch (e: Exception) { db.rollback(); throw e }
        }
        return book(bookId)
    }

    /** Fast full-book search backed by FTS5 (`pages_fts`) and exact UTF-16 offset refinement. */
    fun searchBook(bookId: String, rawQuery: String, limit: Int = DEFAULT_BOOK_SEARCH_LIMIT): BookSearchResult {
        val query = rawQuery.trim()
        if (query.isEmpty() || limit <= 0) return BookSearchResult(query, emptyList(), truncated = false)
        val pages = database().use { db ->
            val candidates = BookFtsStore.queryCandidatePages(db, bookId, query)
            if (candidates == null) {
                db.prepareStatement("SELECT page,text,ocr,ocr_pending FROM pages WHERE book=? ORDER BY page").use { sql ->
                    sql.setString(1, bookId)
                    sql.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(BookPage(rows.getInt(1), rows.getString(2), ocr = rows.getInt(3) == 1, ocrPending = rows.getInt(4) == 1)) }
                    }
                }
            } else if (candidates.isEmpty()) {
                emptyList()
            } else {
                buildList {
                    for (chunk in candidates.chunked(500)) {
                        val placeholders = chunk.joinToString(",") { "?" }
                        db.prepareStatement(
                            "SELECT page,text,ocr,ocr_pending FROM pages WHERE book=? AND page IN ($placeholders) ORDER BY page",
                        ).use { sql ->
                            sql.setString(1, bookId)
                            chunk.forEachIndexed { i, p -> sql.setInt(i + 2, p) }
                            sql.executeQuery().use { rows ->
                                while (rows.next()) {
                                    add(BookPage(rows.getInt(1), rows.getString(2), ocr = rows.getInt(3) == 1, ocrPending = rows.getInt(4) == 1))
                                }
                            }
                        }
                    }
                }
            }
        }
        return searchBookPages(pages, query, limit)
    }

    private fun collectionSummaries(db: Connection): List<CollectionSummary> {
        if (!BookSchemaMigration.tableExists(db, "collections")) return emptyList()
        return MaterialCollectionStore.collections(db).map { col ->
            val conceptCount = if (col.kind == CollectionKind.BOOKLIST) ConceptStore.countActiveConcepts(db, col.id) else 0
            CollectionSummary(col, MaterialCollectionStore.materials(db, col.id).map { it.materialId }, conceptCount)
        }
    }

    @Synchronized fun collectionSummaries(): List<CollectionSummary> = database().use(::collectionSummaries)

    @Synchronized fun collections(): List<MaterialCollectionRecord> = database().use { db ->
        MaterialCollectionStore.collections(db)
    }

    @Synchronized fun upsertCollection(record: MaterialCollectionRecord) = database().use { db ->
        MaterialCollectionStore.upsertCollection(db, record)
    }

    @Synchronized fun attachMaterial(link: CollectionMaterialRecord) = database().use { db ->
        MaterialCollectionStore.attachMaterial(db, link)
    }

    @Synchronized fun setBookShowStandalone(bookId: String, showStandalone: Boolean) = database().use { db ->
        db.prepareStatement("UPDATE books SET show_standalone=? WHERE id=?").use { sql ->
            sql.setInt(1, if (showStandalone) 1 else 0)
            sql.setString(2, bookId)
            sql.executeUpdate()
        }
    }

    @Synchronized fun collectionMaterials(collectionId: String): List<CollectionMaterialRecord> = database().use { db ->
        MaterialCollectionStore.materials(db, collectionId)
    }

    @Synchronized fun <T> withCourseDatabase(block: (Connection) -> T): T = database().use(block)

    @Synchronized fun originalFileIfExists(bookId: String, format: String): Path? =
        runCatching { original(bookId, format) }.getOrNull()?.takeIf(Files::isRegularFile)

    @Synchronized fun courseDetail(courseId: String): CourseDetailSnapshot = database().use { db ->
        CourseStore.loadCourseDetail(db, courseId, clock)
    }

    fun searchAllBooks(rawQuery: String, limitPerBook: Int = 5): List<Pair<BookSummary, BookSearchResult>> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return emptyList()
        return catalog().mapNotNull { summary ->
            val result = searchBook(summary.id, query, limitPerBook)
            if (result.hits.isNotEmpty() || summary.title.contains(query, ignoreCase = true)) summary to result else null
        }
    }

    @Synchronized fun saveBookStructure(bookId: String, structure: BookStructure): Textbook {
        val current = book(bookId)
        val clampedIndex = current.lessonIndex.coerceIn(0, structure.resolvedLessons.size)
        database().use { db ->
            db.autoCommit = false
            try {
                BookStructureStore.saveStructure(db, bookId, structure)
                BookStructureStore.clearGuides(db, bookId)
                db.prepareStatement("UPDATE books SET lesson_index=?, touched=? WHERE id=?").use { sql ->
                    sql.setInt(1, clampedIndex); sql.setLong(2, System.currentTimeMillis()); sql.setString(3, bookId)
                    sql.executeUpdate()
                }
                db.commit()
            } catch (e: Exception) { db.rollback(); throw e }
        }
        return book(bookId)
    }

    @Synchronized fun sectionGuide(bookId: String, sectionId: String): CachedSectionGuide? = database().use { db ->
        BookStructureStore.loadGuide(db, bookId, sectionId)
    }

    @Synchronized fun saveSectionGuide(bookId: String, sectionId: String, guide: CachedSectionGuide) = database().use { db ->
        BookStructureStore.saveGuide(db, bookId, sectionId, guide)
    }

    @Synchronized fun saveChapterPracticeDraft(bookId: String, tocNodeId: String, pagesTotal: Int, draft: ChapterPracticeDraft) = database().use { db ->
        ChapterProgressStore.savePracticeDraft(db, bookId, tocNodeId, pagesTotal, draft)
    }

    @Synchronized fun loadChapterPracticeDraft(bookId: String, tocNodeId: String): ChapterPracticeDraft? = database().use { db ->
        ChapterProgressStore.loadPracticeDraft(db, bookId, tocNodeId)
    }

    @Synchronized fun latestChapterPracticeDraft(bookId: String): ChapterPracticeDraft? = database().use { db ->
        ChapterProgressStore.latestPracticeDraft(db, bookId)
    }

    @Synchronized fun cachedChapterCompanion(bookId: String, chapterId: String): BookCompanionPack? = database().use { db ->
        ChapterProgressStore.loadCachedPack(db, bookId, chapterId)
    }

    @Synchronized fun saveChapterCompanion(bookId: String, chapterId: String, pack: BookCompanionPack) = database().use { db ->
        ChapterProgressStore.saveCachedPack(db, bookId, chapterId, pack)
    }

    @Synchronized fun recordPageDwell(
        bookId: String,
        tocNodeId: String,
        page: Int,
        pagesTotal: Int,
        dwellMs: Long = ChapterReadPolicy.MIN_DWELL_MS,
    ): ChapterProgressRecord? = database().use { db ->
        val now = clock.millis()
        val progress = BooklistProgressStore.recordPageDwell(db, bookId, tocNodeId, page, pagesTotal, dwellMs, now)
        if (dwellMs >= ChapterReadPolicy.MIN_DWELL_MS) {
            val conceptKey = MasteryEngine.conceptKeyFor(bookId, tocNodeId)
            MasteryStore.recordEvidence(
                db = db,
                bookId = bookId,
                tocNodeId = tocNodeId,
                page = page,
                canonicalName = "",
                signal = LearnerSignalType.PAGE_DWELL,
                durationMs = dwellMs,
                conceptKeyOverride = conceptKey,
                nowMs = now,
            )
            MasteryStore.recordLearningGainRead(
                db = db,
                bookId = bookId,
                chapterId = tocNodeId,
                conceptKey = conceptKey,
                addedDwellSec = (dwellMs / 1000L).toInt().coerceAtLeast(1),
                nowMs = now,
            )
        }
        progress
    }

    @Synchronized fun recordMasteryEvidence(
        bookId: String,
        tocNodeId: String,
        page: Int,
        canonicalName: String,
        signal: LearnerSignalType,
        scoreRatio: Double? = null,
        guessed: Boolean = false,
        errorClass: MisconceptionErrorClass? = null,
        durationMs: Long = 0L,
        detailJson: String = "{}",
        conceptKeyOverride: String? = null,
        collectionId: String? = null,
        syllabusWeight: Double? = null,
    ): ConceptMasteryRecord? = database().use { db ->
        MasteryStore.recordEvidence(
            db = db,
            bookId = bookId,
            tocNodeId = tocNodeId,
            page = page,
            canonicalName = canonicalName,
            signal = signal,
            scoreRatio = scoreRatio,
            guessed = guessed,
            errorClass = errorClass,
            durationMs = durationMs,
            detailJson = detailJson,
            conceptKeyOverride = conceptKeyOverride,
            collectionId = collectionId,
            syllabusWeight = syllabusWeight,
            nowMs = clock.millis(),
        )
    }

    @Synchronized fun recordLearningGainT0(
        bookId: String,
        chapterId: String,
        conceptKey: String,
        rawScore: Double,
        guessed: Boolean,
        collectionId: String? = null,
        variantId: String? = null,
    ): LearningGainCohort? = database().use { db ->
        MasteryStore.recordLearningGainT0(db, bookId, chapterId, conceptKey, rawScore, guessed, collectionId, variantId, clock.millis())
    }

    @Synchronized fun recordLearningGainRead(
        bookId: String,
        chapterId: String,
        conceptKey: String,
        addedDwellSec: Int,
    ): LearningGainCohort? = database().use { db ->
        MasteryStore.recordLearningGainRead(db, bookId, chapterId, conceptKey, addedDwellSec, clock.millis())
    }

    @Synchronized fun recordLearningGainT1(
        bookId: String,
        chapterId: String,
        conceptKey: String,
        rawScore: Double,
        guessed: Boolean,
    ): LearningGainCohort? = database().use { db ->
        MasteryStore.recordLearningGainT1(db, bookId, chapterId, conceptKey, rawScore, guessed, clock.millis())
    }

    @Synchronized fun recordLearningGainT2(
        cohortId: String,
        rawScore: Double,
        guessed: Boolean,
    ): LearningGainCohort? = database().use { db ->
        MasteryStore.recordLearningGainT2(db, cohortId, rawScore, guessed, clock.millis())
    }

    @Synchronized fun learningGainCohorts(bookId: String? = null): List<LearningGainCohort> = database().use { db ->
        MasteryStore.listCohorts(db, bookId)
    }

    @Synchronized fun conceptMasteryForBook(bookId: String): List<ConceptMasteryRecord> = database().use { db ->
        MasteryStore.loadConceptsForBook(db, bookId)
    }

    @Synchronized fun conceptMastery(conceptKey: String): ConceptMasteryRecord? = database().use { db ->
        MasteryStore.loadConcept(db, conceptKey)
    }

    @Synchronized fun learnerEvidenceEvents(conceptKey: String? = null, bookId: String? = null): List<LearnerEvidenceEvent> = database().use { db ->
        MasteryStore.listEvidenceEvents(db, conceptKey, bookId)
    }

    @Synchronized fun topHomeRecommendation(preferredBookId: String? = null): HomeFocusRecommendation? = database().use { db ->
        MasteryStore.topHomeRecommendation(db, preferredBookId, clock.millis())
    }

    @Synchronized fun saveLastQuestion(bookId: String, question: String) = database().use { db ->
        MasteryStore.saveLastBookQuestion(db, bookId, question)
    }

    @Synchronized fun lastQuestion(bookId: String): String? = database().use { db ->
        MasteryStore.lookupLastBookQuestion(db, bookId)
    }

    @Synchronized fun saveMarginWhisperAudit(candidate: MarginWhisperCandidate) = database().use { db ->
        MasteryStore.saveMarginWhisperAudit(db, candidate)
    }

    @Synchronized fun dismissMarginWhisperAudit(whisperId: String) = database().use { db ->
        MasteryStore.markMarginWhisperDismissed(db, whisperId)
    }

    @Synchronized fun marginWhisperAudits(bookId: String? = null): List<MarginWhisperCandidate> = database().use { db ->
        MasteryStore.listMarginWhisperAudits(db, bookId)
    }

    @Synchronized fun crossMaterialConflictForPage(bookId: String, page: Int, tocNodeId: String? = null): Pair<String, String>? = database().use { db ->
        MasteryStore.queryCrossMaterialConflictForPage(db, bookId, page, tocNodeId)
    }

    @Synchronized fun setChapterManualReadState(
        bookId: String,
        tocNodeId: String,
        pagesTotal: Int,
        read: Boolean,
    ): ChapterProgressRecord? = database().use { db ->
        BooklistProgressStore.setManualReadState(db, bookId, tocNodeId, pagesTotal, read)
    }

    @Synchronized fun chapterProgressMap(bookId: String): Map<String, ChapterProgressRecord> = database().use { db ->
        BooklistProgressStore.loadForBook(db, bookId)
    }

    @Synchronized fun ftsCandidatePages(bookId: String, query: String): List<Int> = database().use { db ->
        BookFtsStore.queryCandidatePages(db, bookId, query).orEmpty()
    }

    @Synchronized fun saveAlignedConcepts(
        collectionId: String,
        entities: List<ConceptEntityRecord>,
        mentions: List<ConceptMentionRecord>,
        links: List<ConceptLinkRecord>,
    ) = database().use { db ->
        ConceptStore.saveAlignedConcepts(db, collectionId, entities, mentions, links)
    }

    @Synchronized fun setConceptMuted(conceptId: String, muted: Boolean): Boolean = database().use { db ->
        ConceptStore.setConceptMuted(db, conceptId, muted)
    }

    @Synchronized fun saveConceptSharedNote(conceptId: String, sharedNote: String): Boolean = database().use { db ->
        ConceptStore.saveSharedNote(db, conceptId, sharedNote)
    }

    @Synchronized fun booklistConcepts(
        collectionId: String,
        bookTitles: Map<String, String>,
        chapterTitlesByBook: Map<String, Map<String, String>>,
    ): BooklistConceptsSnapshot = database().use { db ->
        ConceptStore.loadBooklistConcepts(db, collectionId, bookTitles, chapterTitlesByBook)
    }

    @Synchronized fun companionCrossBookCards(
        currentBookId: String,
        currentChapterId: String?,
        currentPageRange: IntRange,
        activeBooklistId: String? = null,
    ): List<CrossBookCompanionCard> = database().use { db ->
        ConceptStore.queryCompanionCardsForSection(db, currentBookId, currentChapterId, currentPageRange, activeBooklistId)
    }

    /** 读取全局「伴读讲解与提问」开关持久化状态；关闭时所有书单对齐均严禁发送正文到远端。 */
    @Synchronized fun isReadingAiExplainEnabled(): Boolean = database().use { db ->
        if (!BookSchemaMigration.tableExists(db, "reading_settings")) return@use true
        db.prepareStatement("SELECT value FROM reading_settings WHERE name='aiExplain'").use { q ->
            q.executeQuery().use { r -> if (r.next()) r.getString(1) != "false" else true }
        }
    }

    /** 节末可选选择题作答：写入本地答案、错题/猜的复习队列与知识小结梯。 */
    @Synchronized fun saveSectionCheckAnswer(
        bookId: String,
        lesson: BookLesson,
        questionIndex: Int,
        practice: Practice,
        answer: PracticeAnswer,
        isPretest: Boolean = false,
    ) {
        val source = book(bookId)
        val lessonKey = if (questionIndex == 0) lesson.id else "${lesson.id}#q${questionIndex + 1}"
        val previous = this.answer(bookId, lessonKey, QuestionMode.CHOICE)
        val storedLesson = if (questionIndex == 0) lesson else lesson.copy(id = lessonKey, title = "${lesson.title}（${questionIndex + 1}）")
        val ladder = nextLadderSession(source, storedLesson, QuestionMode.CHOICE, practice, answer, forceRecord = true)
        database().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("INSERT OR REPLACE INTO answers(book,lesson,mode,text,revealed,understood,guessed) VALUES(?,?,?,?,?,?,?)").use { sql ->
                    sql.setString(1, bookId); sql.setString(2, lessonKey); sql.setString(3, QuestionMode.CHOICE.name)
                    sql.setString(4, answer.text); sql.setInt(5, if (answer.revealed) 1 else 0)
                    if (answer.understood == null) sql.setNull(6, java.sql.Types.INTEGER) else sql.setInt(6, if (answer.understood) 1 else 0)
                    sql.setInt(7, if (answer.guessed) 1 else 0)
                    sql.executeUpdate()
                }
                if (previous != answer && answer.committed(QuestionMode.CHOICE)) {
                    reviewStore.recordLesson(db, source, storedLesson, QuestionMode.CHOICE, practice, answer)
                    val now = clock.millis()
                    val correct = practice.independentlyCorrect(QuestionMode.CHOICE, answer)
                    val score = if (correct && !answer.guessed) 1.0 else 0.0
                    val conceptKey = MasteryEngine.conceptKeyFor(bookId, lesson.id)
                    val fsrs = reviewStore.fsrsState(db, bookId, lessonKey, QuestionMode.CHOICE)
                    MasteryStore.recordEvidence(
                        db = db,
                        bookId = bookId,
                        tocNodeId = lesson.id,
                        page = lesson.firstPage,
                        canonicalName = lesson.title,
                        signal = if (isPretest) LearnerSignalType.PRETEST_T0 else LearnerSignalType.POST_QUIZ_T1,
                        scoreRatio = score,
                        guessed = answer.guessed,
                        fsrsStability = fsrs?.stability,
                        conceptKeyOverride = conceptKey,
                        nowMs = now,
                    )
                    if (isPretest) {
                        MasteryStore.recordLearningGainT0(
                            db = db,
                            bookId = bookId,
                            chapterId = lesson.id,
                            conceptKey = conceptKey,
                            rawScore = score,
                            guessed = answer.guessed,
                            nowMs = now,
                        )
                    } else {
                        MasteryStore.recordLearningGainT1(
                            db = db,
                            bookId = bookId,
                            chapterId = lesson.id,
                            conceptKey = conceptKey,
                            rawScore = score,
                            guessed = answer.guessed,
                            nowMs = now,
                        )
                    }
                }
                if (ladder != null) saveLadderRow(db, bookId, ladder)
                db.commit()
            } catch (failure: Exception) { db.rollback(); throw failure }
        }
    }

    private fun original(id: String, format: String): Path {
        require(id.matches(Regex("[a-f0-9]{64}")) && format in BookFormats.supported)
        return directory.resolve("$id.$format")
    }

    internal fun originalFileIfPresent(id: String, format: String): Path? {
        if (!id.matches(Regex("[a-f0-9]{64}")) || format !in BookFormats.supported) return null
        val path = original(id, format)
        return path.takeIf { Files.isRegularFile(it) }
    }

    @Synchronized internal fun enqueueBookUpload(
        accountKey: String,
        localBookId: String,
        fileSha256: String,
        format: String,
        title: String,
    ) = database().use { db ->
        BookUploadQueueStore.enqueue(db, accountKey, localBookId, fileSha256, format, title, clock.millis())
    }

    @Synchronized internal fun switchUploadAccount(activeAccountKey: String) = database().use { db ->
        BookUploadQueueStore.switchAccount(db, activeAccountKey, clock.millis())
    }

    @Synchronized internal fun rekeyUploadAccount(fromAccountKey: String, toAccountKey: String) {
        if (fromAccountKey == toAccountKey) return
        database().use { db ->
            if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return
            db.autoCommit = false
            try {
                val fromRows = BookUploadQueueStore.listForAccount(db, fromAccountKey)
                for (row in fromRows.values) {
                    val targetExists = BookUploadQueueStore.read(db, toAccountKey, row.localBookId) != null
                    if (targetExists) {
                        db.prepareStatement("DELETE FROM book_upload_queue WHERE account_key=? AND local_book_id=?").use { sql ->
                            sql.setString(1, fromAccountKey)
                            sql.setString(2, row.localBookId)
                            sql.executeUpdate()
                        }
                    } else {
                        db.prepareStatement(
                            "UPDATE book_upload_queue SET account_key=?, updated_at=? WHERE account_key=? AND local_book_id=?",
                        ).use { sql ->
                            sql.setString(1, toAccountKey)
                            sql.setLong(2, clock.millis())
                            sql.setString(3, fromAccountKey)
                            sql.setString(4, row.localBookId)
                            sql.executeUpdate()
                        }
                    }
                }
                db.commit()
            } catch (e: Exception) {
                db.rollback()
                throw e
            }
        }
    }

    @Synchronized internal fun saveUploadAccountBinding(sessionKey: String, accountKey: String) = database().use { db ->
        BookUploadQueueStore.saveAccountBinding(db, sessionKey, accountKey, clock.millis())
    }

    @Synchronized internal fun boundUploadAccountKey(sessionKey: String): String? = database().use { db ->
        BookUploadQueueStore.boundAccountKey(db, sessionKey)
    }

    @Synchronized internal fun recoverInterruptedBookUploads() = database().use { db ->
        BookUploadQueueStore.recoverInterruptedUploads(db, clock.millis())
    }

    @Synchronized internal fun updateBookUploadState(
        accountKey: String,
        localBookId: String,
        state: BookUploadState,
        progressPct: Int = 0,
        stageLabel: String = "",
        errorMessage: String? = null,
        serverShelfBookId: String? = null,
        clearServerShelfBookId: Boolean = false,
    ) = database().use { db ->
        BookUploadQueueStore.updateState(
            db, accountKey, localBookId, state, progressPct, stageLabel, errorMessage,
            serverShelfBookId, clearServerShelfBookId, clock.millis(),
        )
    }

    @Synchronized internal fun retryBookUpload(accountKey: String, localBookId: String) = database().use { db ->
        BookUploadQueueStore.retry(db, accountKey, localBookId, clock.millis())
    }

    @Synchronized internal fun uploadEntriesForAccount(accountKey: String): Map<String, BookUploadEntry> =
        database().use { db -> BookUploadQueueStore.listForAccount(db, accountKey) }

    @Synchronized internal fun allUploadEntries(): List<BookUploadEntry> =
        database().use { db -> BookUploadQueueStore.listAll(db) }

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
        val practice = section?.takeIf { mode == QuestionMode.SHORT_ANSWER && it.text.isNotBlank() }?.let {
            Practice("合上原文，用自己的话说明「${it.title}」的主要内容，并举一个例子。", it.text, "", emptyList())
        }
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
                    val now = clock.millis()
                    val correct = practice.independentlyCorrect(mode, answer)
                    val score = if (correct && !answer.guessed) 1.0 else 0.0
                    val fsrs = reviewStore.fsrsState(db, book, lesson, mode)
                    MasteryStore.recordEvidence(
                        db = db,
                        bookId = book,
                        tocNodeId = section.id,
                        page = section.firstPage,
                        canonicalName = section.title,
                        signal = LearnerSignalType.POST_QUIZ_T1,
                        scoreRatio = score,
                        guessed = answer.guessed,
                        fsrsStability = fsrs?.stability,
                        nowMs = now,
                    )
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
        practice: Practice, answer: PracticeAnswer, forceRecord: Boolean = false): LadderSession? {
        if (!forceRecord && learningPath(book.id) != LearningPath.QUESTION_FIRST.name) return null
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
    private fun backfillReviews(db: Connection) {
        val old = db.createStatement().use { sql ->
            sql.executeQuery("""SELECT book,lesson,mode FROM answers a WHERE revealed=1
                AND (mode='CHOICE' OR (mode='SHORT_ANSWER' AND understood IS NOT NULL))
                AND NOT EXISTS(SELECT 1 FROM book_reviews r WHERE r.book=a.book AND r.lesson=a.lesson AND r.mode=a.mode)""").use { rows -> buildList {
                while (rows.next()) add(Triple(rows.getString(1), rows.getString(2), QuestionMode.valueOf(rows.getString(3))))
            } }
        }
        if (old.isEmpty()) return
        for ((id, lessonId, mode) in old) {
            val book = book(id)
            val lesson = book.lessons.find { it.id == lessonId } ?: continue
            val practice = if (mode == QuestionMode.SHORT_ANSWER && lesson.text.isNotBlank()) {
                Practice("合上原文，用自己的话说明「${lesson.title}」的主要内容，并举一个例子。", lesson.text, "", emptyList())
            } else continue
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

    /** Today's FSRS-ranked single-card queue: at most 20 shown, the rest stay due and roll to tomorrow. */
    @Synchronized fun reviewQueue(): LocalReviewQueue = database().use { db -> reviewStore.queue(db) }

    @Synchronized fun fsrsState(book: String, lesson: String, mode: QuestionMode) = database().use { db ->
        reviewStore.fsrsState(db, book, lesson, mode)
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

    @Synchronized fun saveReviewRating(review: LocalBookReview, rating: com.coach.domain.fsrs.FsrsRating, answer: PracticeAnswer) {
        database().use { db ->
            db.autoCommit = false
            try { reviewStore.saveRating(db, review, rating, answer); db.commit() }
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

    private fun openSession(db: Connection): Pair<String, String>? =
        db.createStatement().use { sql ->
            sql.executeQuery("SELECT book,screen FROM book_open_session WHERE slot=1").use { row ->
                if (row.next()) row.getString(1) to row.getString(2) else null
            }
        }

    /** Last open reading or learning screen so reopening returns directly to the book and spot with zero clicks. */
    @Synchronized fun openSession(): Pair<String, String>? = database().use(::openSession)

    @Synchronized fun saveOpenSession(bookId: String, screen: String) {
        require(screen in setOf("READER", "LEARNING")) { "未知的打开界面。" }
        database().use { db ->
            db.prepareStatement("INSERT OR REPLACE INTO book_open_session(slot,book,screen) VALUES(1,?,?)").use { sql ->
                sql.setString(1, bookId)
                sql.setString(2, screen)
                sql.executeUpdate()
            }
        }
    }

    @Synchronized fun clearOpenSession() {
        database().use { db -> db.createStatement().use { it.executeUpdate("DELETE FROM book_open_session") } }
    }

    // ---- 同一页、同一显示宽度合并并发调用并缓存，每次调用留下分段计时 ----
    private data class RenderKey(val id: String, val page: Int, val stamp: Long, val pixelWidth: Int?)
    private val renderLock = Any()
    private val renderCache = LinkedHashMap<RenderKey, BookBitmap>(16, .75f, true)
    private val renderInflight = HashMap<RenderKey, CompletableFuture<BookBitmap>>()
    private val traces = java.util.concurrent.ConcurrentLinkedQueue<RenderTrace>()
    private val activeRenders = AtomicInteger(0)
    private val renderIdleLock = java.util.concurrent.locks.ReentrantLock()
    private val renderIdleCondition = renderIdleLock.newCondition()
    private val firstRenderGates = ConcurrentHashMap<String, CompletableFuture<Unit>>()
    @Volatile private var startupReaderBookId: String? = null
    @Volatile private var startupFirstRenderCompletedAtNs: Long = 0L
    @Volatile private var startupNearWidthBookId: String? = null
    @Volatile private var startupNearWidthPrimaryPage: Int = 0
    @Volatile private var startupNearWidthPartnerPage: Int = 0
    @Volatile private var startupNearWidthUntilNs: Long = 0L

    private fun allowStartupNearWidth(bookId: String, page: Int): Boolean =
        bookId == startupNearWidthBookId &&
            (page == startupNearWidthPrimaryPage || page == startupNearWidthPartnerPage) &&
            System.nanoTime() <= startupNearWidthUntilNs

    private fun markFirstRenderCompleted(bookId: String) {
        val gate = firstRenderGates.computeIfAbsent(bookId) { CompletableFuture() }
        if (gate.complete(Unit) && bookId == startupReaderBookId) {
            startupFirstRenderCompletedAtNs = System.nanoTime()
        }
    }
    private val diskCacheExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "coach-render-disk-cache").apply { isDaemon = true }
    }
    private val startupWarmupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "coach-startup-warmup").apply { isDaemon = true }
    }

    fun awaitFirstRenderOrTimeout(bookId: String, timeoutMs: Long) {
        val gate = firstRenderGates.computeIfAbsent(bookId) { CompletableFuture() }
        runCatching { gate.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun renderDiskCacheFile(key: RenderKey): Path = directory.resolve(".render-cache")
        .resolve("${key.id}-p${key.page}-w${key.pixelWidth ?: "auto"}-s${java.lang.Long.toUnsignedString(key.stamp, 36)}.cbm")

    private fun putRenderCacheLocked(key: RenderKey, bitmap: BookBitmap) {
        renderCache[key] = bitmap
        val entries = renderCache.values.iterator()
        var bytes = renderCache.values.sumOf(::bookPdfBitmapBytes)
        while (renderCache.size > 12 || bytes > bookPdfCacheBytes) {
            bytes -= bookPdfBitmapBytes(entries.next())
            entries.remove()
        }
    }

    private fun prefetchRenderDiskCacheAsync(bookId: String, vararg pages: Int) {
        val pageList = pages.distinct().filter { it > 0 }
        if (pageList.isEmpty()) return
        diskCacheExecutor.execute {
            runCatching {
                val sourceFile = original(bookId, "pdf").toFile()
                if (!sourceFile.isFile) return@execute
                val stamp = sourceFile.length() xor sourceFile.lastModified()
                val cacheDir = directory.resolve(".render-cache")
                if (!Files.isDirectory(cacheDir)) return@execute
                val prefixes = pageList.map { it to "$bookId-p$it-w" }
                val suffix = "-s${java.lang.Long.toUnsignedString(stamp, 36)}.cbm"
                val candidates = Files.list(cacheDir).use { stream ->
                    val matches = mutableListOf<Triple<Int, String, Path>>()
                    val it = stream.iterator()
                    while (it.hasNext()) {
                        val path = it.next()
                        val name = path.fileName.toString()
                        if (!name.endsWith(suffix)) continue
                        val match = prefixes.firstOrNull { (_, prefix) -> name.startsWith(prefix) } ?: continue
                        matches += Triple(match.first, match.second, path)
                    }
                    matches.sortBy { if (it.first == pageList.first()) 0 else 1 }
                    matches
                }
                for ((page, prefix, path) in candidates) {
                    val name = path.fileName.toString()
                    val widthToken = name.removePrefix(prefix).removeSuffix(suffix)
                    val pixelWidth = if (widthToken == "auto") null else widthToken.toIntOrNull() ?: continue
                    val key = RenderKey(bookId, page, stamp, pixelWidth)
                    val alreadyLoaded = synchronized(renderLock) {
                        renderCache.containsKey(key) || renderInflight.containsKey(key)
                    }
                    if (alreadyLoaded) continue
                    val bitmap = readCachedPageBitmap(path) ?: continue
                    synchronized(renderLock) {
                        if (!renderCache.containsKey(key) && !renderInflight.containsKey(key)) {
                            putRenderCacheLocked(key, bitmap)
                        }
                    }
                }
            }
        }
    }

    private fun persistRenderBitmapAsync(key: RenderKey, bitmap: BookBitmap) {
        diskCacheExecutor.execute {
            runCatching {
                val target = renderDiskCacheFile(key)
                writeCachedPageBitmap(target, bitmap)
                val cacheDir = target.parent ?: return@execute
                if (Files.isDirectory(cacheDir)) {
                    val files = Files.list(cacheDir).use { stream ->
                        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".cbm") }
                            .sorted(Comparator.comparingLong<Path> { Files.getLastModifiedTime(it).toMillis() }.reversed())
                            .toList()
                    }
                    files.drop(16).forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }
    }

    /** 最近的渲染记录（最多 64 条）：outcome = render（真正渲染）/ joined（并发时等同一次渲染）/ hit（缓存命中）/ disk-hit（磁盘位图命中）。 */
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

    private fun findNearWidthCachedLocked(bookId: String, page: Int, stamp: Long, pixelWidth: Int): BookBitmap? {
        val minW = (pixelWidth * 0.75).toInt()
        val maxW = (pixelWidth * 1.35).toInt()
        var best: BookBitmap? = null
        var bestDist = Int.MAX_VALUE
        var bestW = -1
        for ((k, bmp) in renderCache) {
            if (k.id != bookId || k.page != page || k.stamp != stamp) continue
            val w = k.pixelWidth ?: continue
            if (w !in minW..maxW) continue
            val dist = kotlin.math.abs(w - pixelWidth)
            if (dist < bestDist || (dist == bestDist && w >= pixelWidth && bestW < pixelWidth)) {
                best = bmp
                bestDist = dist
                bestW = w
            }
        }
        return best
    }

    private fun findNearWidthDiskCached(bookId: String, page: Int, stamp: Long, pixelWidth: Int): Pair<Int, BookBitmap>? {
        val cacheDir = directory.resolve(".render-cache")
        if (!Files.isDirectory(cacheDir)) return null
        val prefix = "$bookId-p$page-w"
        val suffix = "-s${java.lang.Long.toUnsignedString(stamp, 36)}.cbm"
        val minW = (pixelWidth * 0.75).toInt()
        val maxW = (pixelWidth * 1.35).toInt()
        val candidates = runCatching {
            Files.list(cacheDir).use { stream ->
                val matches = mutableListOf<Pair<Int, Path>>()
                val it = stream.iterator()
                while (it.hasNext()) {
                    val path = it.next()
                    val name = path.fileName.toString()
                    if (!name.startsWith(prefix) || !name.endsWith(suffix)) continue
                    val w = name.removePrefix(prefix).removeSuffix(suffix).toIntOrNull() ?: continue
                    if (w in minW..maxW) matches += w to path
                }
                matches
            }
        }.getOrNull().orEmpty()
        if (candidates.isEmpty()) return null
        val ordered = candidates.sortedWith(
            compareBy<Pair<Int, Path>>({ kotlin.math.abs(it.first - pixelWidth) }, { if (it.first >= pixelWidth) 0 else 1 }),
        )
        for ((w, path) in ordered) {
            val bmp = readCachedPageBitmap(path) ?: continue
            return w to bmp
        }
        return null
    }

    fun renderPdf(book: Textbook, page: Int, pixelWidth: Int? = null): BookBitmap {
        require(book.format == "pdf" && page in 1..book.pages.size)
        require(pixelWidth == null || pixelWidth > 0)
        val file = original(book.id, book.format).toFile()
        val key = RenderKey(book.id, page, file.length() xor file.lastModified(), pixelWidth)
        val started = System.nanoTime()
        var owner = false
        val future: CompletableFuture<BookBitmap>
        synchronized(renderLock) {
            renderCache[key]?.let {
                record(RenderTrace(book.id, page, "hit", 0.0, 0.0, 0.0, (System.nanoTime() - started) / 1_000_000.0, Thread.currentThread().name))
                markFirstRenderCompleted(book.id)
                return it
            }
            if (pixelWidth != null && allowStartupNearWidth(book.id, page)) {
                findNearWidthCachedLocked(book.id, page, key.stamp, pixelWidth)?.let { nearBmp ->
                    record(RenderTrace(book.id, page, "hit", 0.0, 0.0, 0.0, (System.nanoTime() - started) / 1_000_000.0, Thread.currentThread().name))
                    markFirstRenderCompleted(book.id)
                    return nearBmp
                }
            }
            val running = renderInflight[key]
            if (running != null) future = running else { future = CompletableFuture(); renderInflight[key] = future; owner = true }
        }
        if (!owner) {
            val img = try { future.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
            record(RenderTrace(book.id, page, "joined", 0.0, 0.0, 0.0, (System.nanoTime() - started) / 1_000_000.0, Thread.currentThread().name))
            return img
        }
        activeRenders.incrementAndGet()
        try {
            val exactDisk = readCachedPageBitmap(renderDiskCacheFile(key))
            val nearDisk = if (exactDisk == null && pixelWidth != null && allowStartupNearWidth(book.id, page)) {
                findNearWidthDiskCached(book.id, page, key.stamp, pixelWidth)
            } else null
            val diskCached = exactDisk ?: nearDisk?.second
            if (diskCached != null) {
                val tEnd = System.nanoTime()
                record(RenderTrace(book.id, page, "disk-hit", 0.0, 0.0, (tEnd - started) / 1_000_000.0, (tEnd - started) / 1_000_000.0, Thread.currentThread().name))
                synchronized(renderLock) {
                    putRenderCacheLocked(key, diskCached)
                    if (nearDisk != null) {
                        putRenderCacheLocked(RenderKey(book.id, page, key.stamp, nearDisk.first), diskCached)
                    }
                    renderInflight.remove(key)
                }
                markFirstRenderCompleted(book.id)
                future.complete(diskCached)
                return diskCached
            }
            val t0 = System.nanoTime()
            val image = BookPdfLoader.readCached(file) { pdf ->
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
                putRenderCacheLocked(key, image)
                renderInflight.remove(key)
            }
            markFirstRenderCompleted(book.id)
            persistRenderBitmapAsync(key, image)
            future.complete(image)
            return image
        } catch (e: Throwable) {
            synchronized(renderLock) {
                renderInflight.remove(key)
                if (e is OutOfMemoryError) renderCache.clear()
            }
            future.completeExceptionally(e)
            throw e
        } finally {
            if (activeRenders.decrementAndGet() == 0) {
                renderIdleLock.lock()
                try {
                    renderIdleCondition.signalAll()
                } finally {
                    renderIdleLock.unlock()
                }
            }
        }
    }
}

/** 一次页图渲染的分段计时（毫秒）。 */
internal data class RenderTrace(val book: String, val page: Int, val outcome: String, val loadMs: Double, val pageBoxMs: Double, val renderMs: Double, val totalMs: Double, val thread: String)

internal data class ExtractedBook(
    val title: String,
    val pages: List<BookPage>,
    val illustrations: List<ExtractedIllustration> = emptyList(),
    val outline: List<BookOutlineItem> = emptyList(),
    val nativeBodyStart: BookPageAnchor? = null,
)

internal object TextbookExtraction {
    fun extract(path: Path, format: String, title: String, ocr: BookOcr = BundledBookOcr,
        control: BookImportControl = BookImportControl(),
        storePdfExtras: ((BookPage, List<ExtractedIllustration>) -> Unit)? = null,
    ): ExtractedBook = try {
        if (format == "pdf") pdf(path, title, ocr, control, stageOcrOnly = false, store = storePdfExtras)
        else extractContent(path, format, title, ocr, control, stageOcrOnly = false)
    } catch (e: BookPdfPasswordException) {
        throw IllegalArgumentException("这本 PDF 有密码保护，请取消密码后再导入。", e)
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("这份教材不完整或已损坏，请重新保存或下载后再导入。", e)
    }

    fun extractForStaging(path: Path, format: String, title: String,
        control: BookImportControl = BookImportControl(),
        storePdfExtras: ((BookPage, List<ExtractedIllustration>) -> Unit)? = null,
    ): ExtractedBook = try {
        if (format == "pdf") pdf(path, title, BundledBookOcr, control, stageOcrOnly = true, store = storePdfExtras)
        else extractContent(path, format, title, BundledBookOcr, control, stageOcrOnly = true)
    } catch (e: BookPdfPasswordException) {
        throw IllegalArgumentException("这本 PDF 有密码保护，请取消密码后再导入。", e)
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("这份教材不完整或已损坏，请重新保存或下载后再导入。", e)
    }

    private fun pdf(path: Path, title: String, ocr: BookOcr, control: BookImportControl,
        stageOcrOnly: Boolean = false,
        store: ((BookPage, List<ExtractedIllustration>) -> Unit)? = null,
    ): ExtractedBook = BookPdfLoader.read(path.toFile()) { pdf ->
        require(pdf.numberOfPages in 1..5000) { "这本 PDF 的页数暂不支持。" }
        require(pdf.currentAccessPermission.canExtractContent()) { "这本 PDF 限制了文字提取，请使用有阅读和提取权限的教材。" }
        val pages = (1..pdf.numberOfPages).map { n ->
            control.report(n, pdf.numberOfPages)
            val extracted = PdfPageTextExtraction.extract(pdf, n)
            val text = checkNotNull(extracted.unit.text)
            val page = BookPage(n, text, textLayout = extracted.pageLayout().takeIf { text.isNotBlank() }, ocrPending = stageOcrOnly && text.isBlank())
            if (store != null && page.textLayout != null) {
                store(page, emptyList())
                page.copy(textLayout = null)
            } else page
        }
        require(pages.count { it.text.isBlank() } <= BookScanImages.MAX_OCR_PAGES) {
            "扫描页超过 ${BookScanImages.MAX_OCR_PAGES} 页，请把 PDF 拆分后再导入。"
        }
        val file = path.toFile()
        val recognized = if (stageOcrOnly) pages else pages.map { page ->
            if (page.text.isNotBlank()) page else {
                OcrTiming.measure("page${page.number}.progress") {
                    control.report(page.number, pdf.numberOfPages, recognizing = true)
                }
                val bounds = pdf.getPage(page.number - 1).cropBox
                require(bounds.width.isFinite() && bounds.height.isFinite() && bounds.width > 0 && bounds.height > 0) { "这页 PDF 尺寸无效，请重新保存后导入。" }
                // Cap the OCR raster before allocating it (Android 200 dpi/2000 px, Desktop 300 dpi/4000 px).
                val scale = minOf(bookOcrTargetDpi / 72f, bookOcrMaxSide / maxOf(bounds.width, bounds.height))
                BookScanImages.checkSize(kotlin.math.ceil(bounds.width * scale).toInt(), kotlin.math.ceil(bounds.height * scale).toInt())
                val image = OcrTiming.measure("page${page.number}.render") {
                    renderBookPdfPage(file, pdf, page.number - 1, scale)
                }
                try { BookPage(page.number, ocr.recognize(image, control), ocr = true) }
                finally { releaseBookBitmap(image) }
            }
        }
        val visuals = if (store == null) importedPdfVisuals(pdf, recognized, control) else {
            var count = 0
            var bytes = 0L
            for (page in recognized) {
                if (count >= FormatBookImages.MAX_IMAGES || bytes >= FormatBookImages.MAX_TOTAL) break
                val images = importedPdfVisuals(pdf, listOf(page), control)
                val accepted = mutableListOf<ExtractedIllustration>()
                for (image in images) {
                    if (count >= FormatBookImages.MAX_IMAGES || bytes + image.bytes.size > FormatBookImages.MAX_TOTAL) break
                    count++
                    bytes += image.bytes.size
                    accepted += image
                }
                if (accepted.isNotEmpty()) store(page, accepted)
                if (accepted.size < images.size) break
            }
            emptyList()
        }
        val outline = extractBookPdfOutline(pdf)
        ExtractedBook(pdf.documentInformation.title?.trim()?.takeIf(String::isNotEmpty) ?: title, recognized, visuals, outline)
    }

    private fun extractContent(path: Path, format: String, title: String, ocr: BookOcr, control: BookImportControl, stageOcrOnly: Boolean): ExtractedBook = when (format) {
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
        "epub" -> epub(path, title, control = control, withNavigation = true)
        "mobi", "azw3" -> com.coach.desktop.books.mobi.KindleText.read(Files.readAllBytes(path)).let { book ->
            val name = book.title().ifBlank { title }
            StructuredBookExtraction.kindle(book, name)
        }
        "docx" -> StructuredBookExtraction.extract(path, format, title, control).let { extracted ->
            extracted.copy(outline = BookStructurePlanner.extractWordOutline(path, extracted.pages))
        }
        "pptx", "html", "rtf", "odt" -> StructuredBookExtraction.extract(path, format, title, control)
        "srt", "vtt" -> {
            require(Files.size(path) <= 32L * 1024 * 1024) { "文本教材请控制在 32 MB 以内。" }
            val text = BookText.decode(Files.readAllBytes(path))
            require(text.isNotBlank()) { "转写或字幕文件中没有文字。" }
            ExtractedBook(title, CourseTranscriptParser.extractToBookPages(text, format))
        }
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
                if (CourseTranscriptParser.looksLikeTranscript(cleaned, format)) {
                    CourseTranscriptParser.extractToBookPages(cleaned, format)
                } else {
                    BookText.chapters(cleaned)
                }
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

    fun epubNavigation(path: Path, canonicalPages: List<BookPage>): ExtractedBook? = runCatching {
        epub(path, "", withNavigation = true, navigationOnly = true)
            .takeIf { parsed -> parsed.pages.map { it.text } == canonicalPages.map { it.text } }
    }.getOrNull()

    private fun epub(path: Path, fallback: String, elements: MutableList<EpubElementSource>? = null,
        control: BookImportControl = BookImportControl(), withNavigation: Boolean = false,
        navigationOnly: Boolean = false): ExtractedBook = ZipFile(path.toFile()).use { zip ->
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
        val root = java.net.URI(null, null, "/$packagePath", null)
        var totalBytes = 0L
        var pageBase = 0
        val imageExtractor = if (elements == null && !navigationOnly) EpubBookImages(zip, control) else null
        val navigation = if (withNavigation) EpubNavigation(packagePath, opf) { member ->
            if (zip.getEntry(member) != null) read(member) else null
        } else null
        val illustrations = mutableListOf<ExtractedIllustration>()
        val pages = spine.flatMap { (spineIndex, item) ->
            control.check()
            val href = requireNotNull(manifest[item.getAttribute("idref")]) { "EPUB 阅读顺序不完整。" }
            val resolved = root.resolve(href.replace(" ", "%20")).normalize()
            require(resolved.scheme == null && resolved.host == null && resolved.fragment == null) { "EPUB 的正文必须包含在书内。" }
            val member = resolved.path.removePrefix("/")
            val bytes = read(member)
            totalBytes += bytes.size
            require(totalBytes <= 64L * 1024 * 1024) { "EPUB 的正文过大。" }
            val doc = xml(bytes)
            val output = StringBuilder()
            val requestedFragments = navigation?.fragments(member).orEmpty()
            val targetOffsets = mutableMapOf<String?, Int>()
            var boilerplateEnd: Int? = null
            if (null in requestedFragments) targetOffsets[null] = 0
            val trace = if (elements != null) EpubTextTrace() else null
            fun visit(node: Node, elementPath: List<Int>) {
                if (node.nodeType == Node.TEXT_NODE) { output.append(node.nodeValue); return }
                val name = node.localName?.lowercase().orEmpty()
                if (name in setOf("script", "style", "head")) return
                val block = name in setOf("p", "div", "li", "h1", "h2", "h3", "h4", "section", "br", "tr")
                if (block) output.append("\n\n")
                val start = output.length
                if (node is Element) {
                    imageExtractor?.record(node, start)
                    if (requestedFragments.isNotEmpty()) {
                        for (attribute in listOf("id", "xml:id", "name")) {
                            val fragment = node.getAttribute(attribute)
                            if (fragment.isNotEmpty() && fragment in requestedFragments) {
                                targetOffsets.putIfAbsent(fragment, start)
                                if (name == "p" || name.length == 2 && name[0] == 'h' && name[1] in '1'..'6') {
                                    navigation?.recordTargetText(member, fragment, name, node.textContent)
                                }
                            }
                        }
                    }
                }
                if (name.matches(Regex("h[1-6]"))) output.append("# ")
                var childIndex = 0
                for (i in 0 until node.childNodes.length) {
                    val child = node.childNodes.item(i)
                    val childPath = if (trace != null && child is Element) elementPath + childIndex else emptyList()
                    if (child is Element) childIndex++
                    visit(child, childPath)
                }
                if (node is Element) {
                    trace?.record(elementPath, node.namespaceURI.orEmpty(), node.localName ?: node.tagName, start, output.length)
                    if (name == "header" && node.getAttribute("id") == "pg-header") boilerplateEnd = output.length
                }
                if (block) output.append("\n\n")
            }
            visit(doc, emptyList())
            val raw = output.toString()
            val cleaned = clean(raw)
            val localPages = paginate(cleaned.trim())
            if (navigation != null && targetOffsets.isNotEmpty()) {
                navigation.recordMember(member, raw, cleaned, localPages, pageBase, targetOffsets, boilerplateEnd)
            }
            imageExtractor?.let { illustrations += it.finish(output.toString(), localPages, member, pageBase) }
            trace?.let { elements?.addAll(it.project(output.toString(), localPages, spineIndex, member, bytes, pageBase)) }
            pageBase += localPages.size
            localPages
        }.mapIndexed { index, page -> page.copy(number = index + 1) }
        require(pages.any { it.text.isNotBlank() }) { "EPUB 中没有可读取的文字正文。" }
        ExtractedBook(opf.all("title").firstOrNull()?.textContent?.trim()?.takeIf(String::isNotEmpty) ?: fallback,
            pages, illustrations, navigation?.outline().orEmpty(), navigation?.bodyStart())
    }
}
