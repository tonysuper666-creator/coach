package com.coach.desktop.books

import com.coach.domain.LadderSession
import com.coach.domain.QuestionLadder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal enum class BookScreen { HOME, READER, LEARNING, REVIEWS, REVIEWING }
internal enum class BookPrompt { IMPORTED, START, AFTER_READING, QUESTIONS }
internal enum class MultiDropAction { COURSE, BOOKLIST, SEPARATE }
internal data class PendingMultiDrop(val paths: List<Path>, val onStaged: () -> Unit = {})
internal data class BookIndexProgress(val bookId: String, val page: Int, val total: Int)
internal data class LibraryUi(
    val catalog: List<BookSummary> = emptyList(), val book: Textbook? = null,
    val collections: List<CollectionSummary> = emptyList(),
    val screen: BookScreen = BookScreen.HOME, val prompt: BookPrompt? = null,
    val page: Int = 1, val source: BookLesson? = null,
    val answer: PracticeAnswer = PracticeAnswer(), val busy: Boolean = false, val error: String? = null,
    val reviews: List<LocalBookReview> = emptyList(), val review: LocalBookReview? = null,
    /** 今日复习的顺序：开列表时定下，复习途中不重排，答完才刷新。 */
    val todayQueue: LocalReviewQueue = LocalReviewQueue(emptyList(), 0),
    val learningPath: LearningPath = LearningPath.READ, val questionsOpen: Boolean = false,
    /** Whether this book already has a chosen walk in the library, as opposed to the READ preview. */
    val learningPathSaved: Boolean = false,
    /** 这本书「做题学」的累计会话：六题一次小结，继续后接着累计。 */
    val ladder: LadderSession = LadderSession(),
    val importing: BookImportProgress? = null,
    val importNotice: BookImportNotice? = null,
    /** 首次从 SQLite 载入目录和上次会话完成前为 false，避免重开时先闪现「书架还是空的」。 */
    val initialized: Boolean = false,
    val indexing: BookIndexProgress? = null,
    /** 一次拖入多份文件时，在底部展示单行询问条（建课程 / 建书单 / 分开单放）。 */
    val pendingMultiDrop: PendingMultiDrop? = null,
    /** 最近一次单文件导入直达正文首页的耗时（毫秒），供性能回归核验（<3000ms）。 */
    val lastImportDurationMs: Long? = null,
)

internal class TextbookController(val library: TextbookLibrary, var structureProvider: BookStructureProvider? = null) {
    private val mutable = MutableStateFlow(LibraryUi(initialized = false))
    val state = mutable.asStateFlow()
    private val actions = Mutex()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeImport: BookImportControl? = null
    @Volatile private var activeImportJob: Job? = null
    @Volatile private var pendingPageSaveJob: Job? = null
    private val pageSaveLock = Mutex()
    private val indexingControls = ConcurrentHashMap<String, BookImportControl>()
    private val indexingJobs = ConcurrentHashMap<String, Job>()
    fun cancelImport() { activeImport?.cancel() }
    fun dismissImportNotice(id: Long) {
        mutable.update { if (it.importNotice?.id == id) it.copy(importNotice = null) else it }
    }

    /** 窗口关闭前等待在途页码写入落盘，避免快速退出丢失最后翻到的页码。 */
    suspend fun flushPendingSaves() {
        pendingPageSaveJob?.join()
    }

    private suspend fun action(showBusy: Boolean = true, block: suspend () -> Unit) = actions.withLock {
        mutable.update { it.copy(busy = showBusy, error = null) }
        try { withContext(Dispatchers.IO) { block() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            mutable.update { it.copy(error = failure.message.takeIf { failure is IllegalArgumentException && ReadableText.readsAsChinese(it) }
                ?: "暂时无法读取或保存教材，请确认文件完整、未加密，然后重试。") }
        } finally { mutable.update { it.copy(busy = false) } }
    }

    suspend fun load() {
        var loadedCatalog: List<BookSummary> = emptyList()
        var loadedScreen = BookScreen.HOME
        var loadedBookId: String? = null
        try {
            action {
                val snap = library.loadStartupSnapshot()
                val catalog = snap.catalog
                loadedCatalog = catalog
                val active = snap.activeReview
                val session = snap.openSession
                val book = snap.book
                val savedPath = snap.learningPath
                val restoredPath = book?.let { LearningPath.restore(savedPath, it.learning) } ?: LearningPath.READ
                val restoredScreen = when {
                    active != null -> BookScreen.REVIEWING
                    book != null && session?.second == "READER" -> BookScreen.READER
                    book != null && session?.second == "LEARNING" && book.learning -> BookScreen.LEARNING
                    else -> BookScreen.HOME
                }
                loadedScreen = restoredScreen
                loadedBookId = book?.id
                val restoredLesson = if (restoredScreen == BookScreen.LEARNING && book != null) book.lessons.getOrNull(book.lessonIndex) else null
                val restoredAnswer = active?.answer
                    ?: restoredLesson?.let { library.answer(book!!.id, it.id, book.questionMode) }
                    ?: PracticeAnswer()
                val restoredQueue = ensureQueueIncludes(snap.todayQueue, active)
                mutable.update { current ->
                    LibraryUi(
                        catalog = catalog, book = book, collections = snap.collections,
                        page = book?.readingPage ?: 1, busy = true, reviews = snap.reviews,
                        review = active, todayQueue = restoredQueue, answer = restoredAnswer,
                        learningPath = restoredPath,
                        learningPathSaved = savedPath != null,
                        questionsOpen = restoredScreen == BookScreen.LEARNING &&
                            (restoredPath == LearningPath.QUESTION_FIRST || restoredAnswer.text.isNotBlank() || restoredAnswer.revealed),
                        ladder = snap.ladder,
                        screen = restoredScreen,
                        importing = current.importing,
                        importNotice = current.importNotice,
                        initialized = true,
                        indexing = current.indexing,
                        pendingMultiDrop = current.pendingMultiDrop,
                        lastImportDurationMs = current.lastImportDurationMs,
                    )
                }
            }
            val needIndex = loadedCatalog.firstOrNull { it.id == loadedBookId && it.indexState != "READY" }
                ?: loadedCatalog.firstOrNull { it.indexState != "READY" }
            if (needIndex != null && !indexingJobs.containsKey(needIndex.id)) {
                val waitRender = loadedScreen == BookScreen.READER && loadedBookId == needIndex.id
                launchTier1Indexing(needIndex.id, waitForFirstRender = waitRender)
            } else {
                val pending = loadedCatalog.firstOrNull { it.pendingOcrPages > 0 }
                if (pending != null && activeImport == null) {
                    resumePendingOcrInBackground(pending.id)
                }
            }
        } finally {
            mutable.update { if (it.initialized) it else it.copy(initialized = true) }
        }
    }

    private fun ensureQueueIncludes(queue: LocalReviewQueue, active: LocalBookReview?): LocalReviewQueue {
        if (active == null) return queue
        val present = queue.cards.any { it.book == active.book && it.lesson == active.lesson && it.mode == active.mode }
        return if (present) queue else queue.copy(cards = listOf(active) + queue.cards)
    }

    fun launchDroppedBooks(paths: List<Path>, onStaged: () -> Unit = {}): Job? {
        if (paths.isEmpty()) {
            onStaged()
            return null
        }
        if (paths.size == 1) {
            return launchImport(paths.single(), onStaged)
        }
        val previous = mutable.value.pendingMultiDrop
        mutable.update { it.copy(error = null, pendingMultiDrop = PendingMultiDrop(paths.toList(), onStaged)) }
        previous?.onStaged?.invoke()
        return null
    }

    fun dismissMultiDrop() {
        val pending = mutable.value.pendingMultiDrop ?: return
        mutable.update { it.copy(pendingMultiDrop = null) }
        pending.onStaged()
    }

    fun resolveMultiDrop(action: MultiDropAction, customTitle: String? = null, examDate: String? = null): Job {
        val pending = mutable.value.pendingMultiDrop ?: return Job().apply { complete() }
        mutable.update { it.copy(pendingMultiDrop = null) }
        val defaultTitle = customTitle?.trim()?.takeIf(String::isNotEmpty)
            ?: pending.paths.first().fileName?.toString()?.substringBeforeLast('.')?.takeIf(String::isNotBlank)
            ?: when (action) {
                MultiDropAction.COURSE -> "新课程"
                MultiDropAction.BOOKLIST -> "新书单"
                MultiDropAction.SEPARATE -> ""
            }
        return when (action) {
            MultiDropAction.COURSE -> createCollection(
                kind = CollectionKind.COURSE,
                title = defaultTitle,
                examDate = examDate,
                existingBookIds = emptyList(),
                newFiles = pending.paths,
                onStaged = pending.onStaged,
            )
            MultiDropAction.BOOKLIST -> createCollection(
                kind = CollectionKind.BOOKLIST,
                title = defaultTitle,
                examDate = null,
                existingBookIds = emptyList(),
                newFiles = pending.paths,
                onStaged = pending.onStaged,
            )
            MultiDropAction.SEPARATE -> importMultipleStandalone(pending.paths, pending.onStaged)
        }
    }

    private fun importMultipleStandalone(paths: List<Path>, onStaged: () -> Unit = {}): Job = backgroundScope.launch {
        try {
            action {
                var lastBook: Textbook? = null
                for (path in paths) {
                    val useTier0 = library.lazyPdfExtras && path.fileName?.toString().orEmpty().endsWith(".pdf", ignoreCase = true)
                    val staged = if (useTier0) library.stageBookImportTier0(path) else library.stageBookImport(path)
                    lastBook = staged.book
                }
                val catalog = library.catalog()
                val collections = library.collectionSummaries()
                val reviews = library.reviews()
                val todayQueue = library.reviewQueue()
                val opened = lastBook ?: catalog.firstOrNull()?.let { library.book(it.id) }
                mutable.update { current ->
                    current.copy(
                        catalog = catalog,
                        collections = collections,
                        book = opened,
                        screen = BookScreen.HOME,
                        prompt = null,
                        page = opened?.readingPage ?: 1,
                        reviews = reviews,
                        todayQueue = todayQueue,
                        initialized = true,
                        importNotice = BookImportNotice(System.nanoTime(), "已分开导入 ${paths.size} 份文件。"),
                    )
                }
            }
        } finally {
            onStaged()
        }
    }

    fun createCollection(
        kind: CollectionKind,
        title: String,
        examDate: String? = null,
        existingBookIds: List<String> = emptyList(),
        newFiles: List<Path> = emptyList(),
        onStaged: () -> Unit = {},
    ): Job = backgroundScope.launch {
        try {
            action {
                val cleanTitle = title.trim()
                require(cleanTitle.isNotEmpty()) { if (kind == CollectionKind.COURSE) "请填写课程名。" else "请填写书单名。" }
                val importedBooks = mutableListOf<Textbook>()
                for (path in newFiles) {
                    val useTier0 = library.lazyPdfExtras && path.fileName?.toString().orEmpty().endsWith(".pdf", ignoreCase = true)
                    val staged = if (useTier0) library.stageBookImportTier0(path) else library.stageBookImport(path)
                    if (kind == CollectionKind.COURSE && !staged.alreadyExisted) {
                        library.setBookShowStandalone(staged.book.id, false)
                    }
                    importedBooks += staged.book
                }
                val allIds = (existingBookIds + importedBooks.map { it.id }).distinct()
                val now = System.currentTimeMillis()
                val id = "${kind.name.lowercase()}-${java.util.UUID.randomUUID().toString().take(8)}"
                val cleanExamDate = examDate?.trim()?.takeIf(String::isNotEmpty)
                library.upsertCollection(
                    MaterialCollectionRecord(
                        id = id,
                        kind = kind,
                        title = cleanTitle,
                        createdAt = now,
                        updatedAt = now,
                        examDate = cleanExamDate,
                    ),
                )
                allIds.forEachIndexed { idx, bookId ->
                    val role = if (kind == CollectionKind.COURSE) {
                        if (idx == 0) "CORE_TEXTBOOK" else "LECTURE_SLIDES"
                    } else "LIST_BOOK"
                    library.attachMaterial(
                        CollectionMaterialRecord(
                            collectionId = id,
                            materialId = bookId,
                            role = role,
                            ordinal = idx,
                            addedAt = now,
                        ),
                    )
                }
                val catalog = library.catalog()
                val collections = library.collectionSummaries()
                val reviews = library.reviews()
                val todayQueue = library.reviewQueue()
                val focusBook = importedBooks.firstOrNull()
                    ?: allIds.firstOrNull()?.let { runCatching { library.book(it) }.getOrNull() }
                    ?: mutable.value.book
                    ?: catalog.firstOrNull()?.let { library.book(it.id) }
                val noticeLabel = if (kind == CollectionKind.COURSE) "已创建课程《$cleanTitle》" else "已创建书单《$cleanTitle》"
                mutable.update { current ->
                    current.copy(
                        catalog = catalog,
                        collections = collections,
                        book = focusBook,
                        screen = BookScreen.HOME,
                        prompt = null,
                        page = focusBook?.readingPage ?: 1,
                        reviews = reviews,
                        todayQueue = todayQueue,
                        initialized = true,
                        importNotice = BookImportNotice(System.nanoTime(), noticeLabel),
                    )
                }
            }
        } finally {
            onStaged()
        }
    }

    suspend fun importDroppedBooks(paths: List<Path>) {
        if (paths.size != 1) action { throw IllegalArgumentException("请一次拖入一份教材。") }
        else importBook(paths.single())
    }

    fun launchImport(path: Path, onStaged: () -> Unit = {}): Job {
        if (activeImport != null) {
            onStaged()
            mutable.update { it.copy(error = "上一本教材还在准备中，请稍候或先取消后再导入。") }
            return Job().apply { complete() }
        }
        val startedAtNs = System.nanoTime()
        val control = BookImportControl(
            progress = { progress -> mutable.update { it.copy(importing = progress) } },
        )
        activeImport = control
        val job = backgroundScope.launch {
            var staged: TextbookLibrary.StagedBookImport? = null
            var stagedNotified = false
            fun releaseOnce() {
                if (!stagedNotified) {
                    stagedNotified = true
                    onStaged()
                }
            }
            try {
                mutable.update { it.copy(error = null, importing = BookImportProgress(0, 0)) }
                val useTier0 = library.lazyPdfExtras && path.fileName?.toString().orEmpty().endsWith(".pdf", ignoreCase = true)
                val result = if (useTier0) {
                    library.stageBookImportTier0(path, control)
                } else {
                    library.stageBookImport(path, control)
                }
                staged = result
                releaseOnce()
                val book = result.book
                if (book.indexState != "READY") {
                    actions.withLock {
                        library.closeReview()
                        library.saveOpenSession(book.id, "READER")
                        val catalog = library.catalog()
                        val collections = library.collectionSummaries()
                        val reviews = library.reviews()
                        val todayQueue = library.reviewQueue()
                        val pathSaved = library.learningPath(book.id)
                        val ladder = library.ladder(book.id)
                        val elapsedMs = ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
                        mutable.update { current ->
                            current.copy(
                                catalog = catalog,
                                collections = collections,
                                book = book,
                                screen = BookScreen.READER,
                                prompt = null,
                                source = null,
                                page = book.readingPage,
                                busy = false,
                                error = null,
                                reviews = reviews,
                                todayQueue = todayQueue,
                                review = null,
                                learningPath = pathSaved?.let { LearningPath.restore(it, book.learning) } ?: LearningPath.READ,
                                learningPathSaved = pathSaved != null,
                                ladder = ladder,
                                importing = null,
                                initialized = true,
                                lastImportDurationMs = elapsedMs,
                            )
                        }
                    }
                    launchTier1Indexing(book.id)
                    return@launch
                }
                if (!book.hasPendingOcr) {
                    actions.withLock {
                        library.closeReview()
                        library.saveOpenSession(book.id, "READER")
                        val catalog = library.catalog()
                        val collections = library.collectionSummaries()
                        val reviews = library.reviews()
                        val todayQueue = library.reviewQueue()
                        val pathSaved = library.learningPath(book.id)
                        val ladder = library.ladder(book.id)
                        val elapsedMs = ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
                        mutable.update { current ->
                            current.copy(
                                catalog = catalog,
                                collections = collections,
                                book = book,
                                screen = BookScreen.READER,
                                prompt = null,
                                source = null,
                                page = book.readingPage,
                                busy = false,
                                error = null,
                                reviews = reviews,
                                todayQueue = todayQueue,
                                review = null,
                                learningPath = pathSaved?.let { LearningPath.restore(it, book.learning) } ?: LearningPath.READ,
                                learningPathSaved = pathSaved != null,
                                ladder = ladder,
                                importing = null,
                                initialized = true,
                                lastImportDurationMs = elapsedMs,
                            )
                        }
                    }
                    ensureUnstructuredBookSectioned(book.id)
                    return@launch
                }
                val totalPages = book.pages.size
                val donePages = book.pages.count { !it.ocrPending }
                val totalOcr = book.pages.count { it.ocrPending }
                val initialProgress = BookImportProgress(
                    page = donePages,
                    total = totalPages,
                    recognizing = true,
                    bookId = book.id,
                    bookTitle = book.title,
                    completedOcr = 0,
                    totalOcr = totalOcr,
                )
                actions.withLock {
                    library.closeReview()
                    val catalog = library.catalog()
                    val collections = library.collectionSummaries()
                    val reviews = library.reviews()
                    val todayQueue = library.reviewQueue()
                    val pathSaved = library.learningPath(book.id)
                    val ladder = library.ladder(book.id)
                    val stayElsewhere = mutable.value.let { it.screen != BookScreen.HOME && it.book != null && it.book.id != book.id }
                    if (!stayElsewhere) library.saveOpenSession(book.id, "READER")
                    val elapsedMs = ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
                    mutable.update { current ->
                        current.copy(
                            catalog = catalog,
                            collections = collections,
                            book = if (stayElsewhere) current.book else book,
                            screen = if (stayElsewhere) current.screen else BookScreen.READER,
                            prompt = null,
                            source = if (stayElsewhere) current.source else null,
                            page = if (stayElsewhere) current.page else book.readingPage,
                            busy = false,
                            error = null,
                            reviews = reviews,
                            todayQueue = todayQueue,
                            review = if (stayElsewhere) current.review else null,
                            learningPath = if (stayElsewhere) current.learningPath else (pathSaved?.let { LearningPath.restore(it, book.learning) } ?: LearningPath.READ),
                            learningPathSaved = if (stayElsewhere) current.learningPathSaved else (pathSaved != null),
                            ladder = if (stayElsewhere) current.ladder else ladder,
                            importing = initialProgress,
                            initialized = true,
                            lastImportDurationMs = elapsedMs,
                        )
                    }
                }
                runOcrLoop(book.id, control, deleteOnEmptyFailure = !result.alreadyExisted)
            } catch (_: BookImportCancelled) {
                releaseOnce()
                handleCancelledImport(staged?.book?.id)
            } catch (cancel: CancellationException) {
                releaseOnce()
                handleCancelledImport(staged?.book?.id)
                throw cancel
            } catch (failure: Exception) {
                releaseOnce()
                handleFailedImport(staged?.book?.id, staged?.alreadyExisted == false, failure)
            } finally {
                releaseOnce()
                if (activeImport === control) {
                    activeImport = null
                    activeImportJob = null
                }
                mutable.update { it.copy(importing = null) }
            }
        }
        activeImportJob = job
        return job
    }

    private fun resumePendingOcrInBackground(bookId: String): Job {
        val control = BookImportControl(
            progress = { progress -> mutable.update { it.copy(importing = progress) } },
        )
        activeImport = control
        val job = backgroundScope.launch {
            try {
                val book = library.book(bookId)
                val totalPages = book.pages.size
                val donePages = book.pages.count { !it.ocrPending }
                val totalOcr = book.pages.count { it.ocrPending }
                if (totalOcr > 0) {
                    mutable.update {
                        it.copy(
                            importing = BookImportProgress(
                                page = donePages,
                                total = totalPages,
                                recognizing = true,
                                bookId = book.id,
                                bookTitle = book.title,
                                completedOcr = 0,
                                totalOcr = totalOcr,
                            ),
                        )
                    }
                    runOcrLoop(bookId, control, deleteOnEmptyFailure = false)
                }
            } catch (_: BookImportCancelled) {
                handleCancelledImport(bookId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                runCatching { library.clearPendingOcr(bookId) }
                handleFailedImport(bookId, deleteIfNoOcrYet = false, failure = failure)
            } finally {
                if (activeImport === control) {
                    activeImport = null
                    activeImportJob = null
                }
                mutable.update { it.copy(importing = null) }
            }
        }
        activeImportJob = job
        return job
    }

    private suspend fun runOcrLoop(bookId: String, control: BookImportControl, deleteOnEmptyFailure: Boolean) {
        var finalBook = library.resumeBookOcr(bookId, control) { updatedBook, progress ->
            val remainingPending = updatedBook.pages.count { it.ocrPending }
            mutable.update { current ->
                current.copy(
                    catalog = current.catalog.map { summary ->
                        if (summary.id == updatedBook.id) summary.copy(pendingOcrPages = remainingPending) else summary
                    },
                    book = if (current.book?.id == updatedBook.id) updatedBook else current.book,
                    importing = progress,
                )
            }
        }
        if (!finalBook.hasPendingOcr && finalBook.pages.any { it.ocr }) {
            finalBook = runCatching { library.reorganizeSections(bookId) }.getOrDefault(finalBook)
            ensureUnstructuredBookSectioned(bookId)?.let { finalBook = it }
        }
        val catalog = library.catalog()
        mutable.update { current ->
            current.copy(
                catalog = catalog,
                book = if (current.book?.id == finalBook.id) finalBook else current.book,
                importing = null,
                importNotice = BookImportNotice(System.nanoTime(), "《${finalBook.title}》识别完了"),
            )
        }
    }

    private suspend fun handleCancelledImport(bookId: String?) = withContext(NonCancellable) {
        if (bookId != null) runCatching { library.deleteBook(bookId) }
        val catalog = runCatching { library.catalog() }.getOrElse { emptyList() }
        val fallbackBook = catalog.firstOrNull()?.let { runCatching { library.book(it.id) }.getOrNull() }
        mutable.update { current ->
            val removedOpen = bookId != null && current.book?.id == bookId
            current.copy(
                catalog = catalog,
                book = if (removedOpen) fallbackBook else current.book,
                screen = if (removedOpen) BookScreen.HOME else current.screen,
                prompt = if (removedOpen) null else current.prompt,
                source = if (removedOpen) null else current.source,
                page = if (removedOpen) (fallbackBook?.readingPage ?: 1) else current.page,
                busy = false,
                importing = null,
                initialized = true,
                error = "已取消导入，书架没有新增教材。",
            )
        }
    }

    private suspend fun handleFailedImport(bookId: String?, deleteIfNoOcrYet: Boolean, failure: Exception) = withContext(NonCancellable) {
        val message = failure.message.takeIf { failure is IllegalArgumentException && ReadableText.readsAsChinese(it) }
            ?: "暂时无法读取或保存教材，请确认文件完整、未加密，然后重试。"
        val shouldDelete = bookId != null && deleteIfNoOcrYet &&
            runCatching { library.book(bookId).pages.none { it.ocr } }.getOrDefault(false)
        if (shouldDelete) {
            runCatching { library.deleteBook(checkNotNull(bookId)) }
        }
        val catalog = runCatching { library.catalog() }.getOrElse { emptyList() }
        val fallbackBook = catalog.firstOrNull()?.let { runCatching { library.book(it.id) }.getOrNull() }
        mutable.update { current ->
            val removedOpen = shouldDelete && current.book?.id == bookId
            current.copy(
                catalog = catalog,
                book = if (removedOpen) fallbackBook else current.book,
                screen = if (removedOpen) BookScreen.HOME else current.screen,
                prompt = if (removedOpen) null else current.prompt,
                source = if (removedOpen) null else current.source,
                page = if (removedOpen) (fallbackBook?.readingPage ?: 1) else current.page,
                busy = false,
                importing = null,
                initialized = true,
                error = message,
            )
        }
    }

    suspend fun importBook(path: Path) {
        launchImport(path).join()
    }

    private fun launchTier1Indexing(
        bookId: String,
        waitForFirstRender: Boolean = true,
        skipOnContinue: Set<String> = emptySet(),
    ): Job? {
        val indexControl = BookImportControl()
        if (indexingControls.putIfAbsent(bookId, indexControl) != null) {
            return indexingJobs[bookId]
        }
        val job = backgroundScope.launch {
            var shouldContinueQueue = false
            var cancelled = false
            try {
                if (waitForFirstRender) {
                    library.awaitFirstRenderOrTimeout(bookId, 1200L)
                }
                val indexed = library.indexBookTier1(bookId, indexControl) { page, total ->
                    mutable.update { it.copy(indexing = BookIndexProgress(bookId, page, total)) }
                }
                val catalog = library.catalog()
                mutable.update { current ->
                    val openBook = current.book
                    val refreshed = if (openBook?.id == indexed.id) indexed.copy(readingPage = current.page) else openBook
                    current.copy(
                        catalog = catalog,
                        book = refreshed,
                        indexing = if (current.indexing?.bookId == bookId) null else current.indexing,
                    )
                }
                if (indexed.hasPendingOcr && activeImport == null) {
                    resumePendingOcrInBackground(indexed.id)
                } else {
                    ensureUnstructuredBookSectioned(indexed.id)
                }
                shouldContinueQueue = true
            } catch (_: BookImportCancelled) {
                // Book cancelled/deleted while indexing.
                shouldContinueQueue = true
            } catch (cancel: CancellationException) {
                cancelled = true
                throw cancel
            } catch (_: Exception) {
                // Keep Tier 0 readable state; next load retries background indexing cleanly.
                shouldContinueQueue = true
            } finally {
                indexingControls.remove(bookId, indexControl)
                indexingJobs.remove(bookId)
                mutable.update { if (it.indexing?.bookId == bookId) it.copy(indexing = null) else it }
                if (shouldContinueQueue && !cancelled) {
                    val nextSkip = skipOnContinue + bookId
                    val nextPending = runCatching { library.catalog() }.getOrNull()
                        ?.firstOrNull { it.indexState != "READY" && it.id !in nextSkip && !indexingJobs.containsKey(it.id) }
                    if (nextPending != null) {
                        launchTier1Indexing(nextPending.id, waitForFirstRender = false, skipOnContinue = nextSkip)
                    }
                }
            }
        }
        indexingJobs[bookId] = job
        return job
    }

    suspend fun ensureVisualsForPages(bookId: String, pageNumbers: Collection<Int>) {
        val updated = withContext(Dispatchers.IO) {
            if (library.ensurePdfVisualsForPages(bookId, pageNumbers)) {
                runCatching { library.book(bookId) }.getOrNull()
            } else null
        } ?: return
        mutable.update { current ->
            if (current.book?.id == updated.id) {
                current.copy(book = updated.copy(readingPage = current.page))
            } else current
        }
    }

    suspend fun select(id: String) {
        var selectedPendingId: String? = null
        action {
            val book = library.book(id)
            if (book.indexState != "READY") selectedPendingId = book.id
            library.save(book)
            library.closeReview()
            library.clearOpenSession()
            val catalog = library.catalog()
            val collections = library.collectionSummaries()
            val reviews = library.reviews()
            val todayQueue = library.reviewQueue()
            val pathSaved = library.learningPath(book.id) != null
            val ladder = library.ladder(book.id)
            mutable.update { current ->
                LibraryUi(
                    catalog = catalog, book = book, collections = collections, page = book.readingPage, busy = true,
                    reviews = reviews, todayQueue = todayQueue,
                    learningPath = restoredPath(book), learningPathSaved = pathSaved,
                    ladder = ladder, importing = current.importing, importNotice = current.importNotice,
                    initialized = true, indexing = current.indexing,
                    pendingMultiDrop = current.pendingMultiDrop,
                    lastImportDurationMs = current.lastImportDurationMs,
                )
            }
        }
        val pendingId = selectedPendingId
        if (pendingId != null && !indexingJobs.containsKey(pendingId)) {
            launchTier1Indexing(pendingId, waitForFirstRender = false)
        }
    }

    suspend fun openBook(id: String, targetPage: Int? = null) {
        var selectedPendingId: String? = null
        action {
            val loaded = library.book(id)
            val book = if (targetPage != null && loaded.page(targetPage) != null) {
                loaded.copy(readingPage = targetPage)
            } else loaded
            if (book.indexState != "READY") selectedPendingId = book.id
            library.save(book)
            library.closeReview()
            library.saveOpenSession(book.id, "READER")
            val catalog = library.catalog()
            val collections = library.collectionSummaries()
            val reviews = library.reviews()
            val todayQueue = library.reviewQueue()
            val pathSaved = library.learningPath(book.id) != null
            val ladder = library.ladder(book.id)
            mutable.update { current ->
                current.copy(
                    catalog = catalog,
                    collections = collections,
                    book = book,
                    screen = BookScreen.READER,
                    prompt = null,
                    source = null,
                    page = book.readingPage,
                    review = null,
                    reviews = reviews,
                    todayQueue = todayQueue,
                    learningPath = restoredPath(book),
                    learningPathSaved = pathSaved,
                    questionsOpen = false,
                    ladder = ladder,
                    initialized = true,
                )
            }
        }
        val pendingId = selectedPendingId
        if (pendingId != null && !indexingJobs.containsKey(pendingId)) {
            launchTier1Indexing(pendingId, waitForFirstRender = true)
        }
    }

    private fun persist(book: Textbook) {
        library.save(book)
        val latest = library.book(book.id)
        mutable.update { it.copy(book = latest, catalog = library.catalog(), initialized = true) }
    }

    private fun restoredPath(book: Textbook) = LearningPath.restore(library.learningPath(book.id), book.learning)

    private fun openReading(book: Textbook) {
        library.closeReview()
        library.saveOpenSession(book.id, "READER")
        mutable.update { it.copy(review = null, questionsOpen = false,
            screen = BookScreen.READER, page = book.readingPage, source = null,
            prompt = null, initialized = true) }
    }

    suspend fun read() = action { mutable.value.book?.let { openReading(it) } }

    /** An explicit entry changes the walk; the shared continue action restores the saved walk. */
    suspend fun learn(path: LearningPath? = null) = action {
        val book = mutable.value.book ?: return@action
        val target = path ?: restoredPath(book)
        if (target == LearningPath.READ) {
            if (path != null) library.saveLearningPath(book.id, target.name)
            mutable.update { it.copy(learningPath = target, learningPathSaved = it.learningPathSaved || path != null) }
            openReading(book); return@action
        }
        library.closeReview()
        mutable.update { it.copy(learningPath = target) }
        require(book.lessons.isNotEmpty()) {
            if (book.hasPendingOcr) "这本书还在后台识别文字，请等识别出部分内容后再开始学，或先翻看 PDF 原页。"
            else "这本书没有可提取或识别出的学习文字。仍可翻看 PDF 原页或图片原件；要开始学，请换用有文字或更清晰的版本后重试。"
        }
        val readyBook = if (!book.learning) {
            val enabled = book.copy(learning = true, promptSeen = true)
            library.save(enabled)
            enabled
        } else book
        library.saveLearningPath(readyBook.id, target.name)
        mutable.update { it.copy(book = readyBook, review = null, prompt = null, learningPath = target, learningPathSaved = true) }
        openLearning(readyBook, target)
    }

    private fun openLearning(book: Textbook, path: LearningPath = mutable.value.learningPath) {
        library.saveOpenSession(book.id, "LEARNING")
        val lesson = book.lessons.getOrNull(book.lessonIndex)
        val answer = lesson?.let { library.answer(book.id, it.id, book.questionMode) } ?: PracticeAnswer()
        mutable.update { it.copy(screen = BookScreen.LEARNING, prompt = null, source = null, answer = answer, review = null,
            learningPath = path, ladder = library.ladder(book.id),
            questionsOpen = path == LearningPath.QUESTION_FIRST || answer.text.isNotBlank() || answer.revealed) }
    }

    suspend fun chooseLearningPath(path: LearningPath) = action(showBusy = false) {
        mutable.update { it.copy(learningPath = path) }
    }

    suspend fun beginQuestions() = action(showBusy = false) {
        if (mutable.value.screen == BookScreen.LEARNING) mutable.update { it.copy(questionsOpen = true) }
    }

    suspend fun enableLearning() = action {
        val book = mutable.value.book ?: return@action
        val path = mutable.value.learningPath
        if (path == LearningPath.READ) {
            library.saveLearningPath(book.id, path.name)
            mutable.update { it.copy(learningPathSaved = true) }
            openReading(book); return@action
        }
        require(book.lessons.isNotEmpty()) {
            if (book.hasPendingOcr) "这本书还在后台识别文字，请等识别出部分内容后再开始学，或先翻看 PDF 原页。"
            else "这本书没有可提取或识别出的学习文字。仍可翻看 PDF 原页或图片原件；要开始学，请换用有文字或更清晰的版本后重试。"
        }
        val enabled = book.copy(learning = true, promptSeen = true)
        library.saveLearningPath(book.id, path.name)
        mutable.update { it.copy(learningPathSaved = true) }
        persist(enabled); openLearning(enabled)
    }

    suspend fun dismissPrompt() = action {
        val book = mutable.value.book
        if (mutable.value.prompt == BookPrompt.AFTER_READING && book != null) persist(book.copy(promptSeen = true))
        mutable.update { it.copy(prompt = null) }
    }

    suspend fun questionSettings() = action { mutable.update { it.copy(prompt = BookPrompt.QUESTIONS) } }
    suspend fun mode(mode: QuestionMode) = action {
        val book = mutable.value.book ?: return@action
        val changed = book.copy(questionMode = mode)
        persist(changed)
        val answer = changed.lessons.getOrNull(changed.lessonIndex)?.let { library.answer(changed.id, it.id, mode) } ?: PracticeAnswer()
        mutable.update { it.copy(answer = answer) }
    }

    suspend fun page(number: Int) {
        val current = mutable.value
        val book = current.book ?: return
        if (book.page(number) == null) {
            mutable.update { it.copy(error = "页码超出了这本教材的范围。") }
            return
        }
        val updatingBook = current.source == null
        val updatedBook = if (updatingBook) book.copy(readingPage = number) else book
        // 立刻同步更新 UI 页码与内存中的 book.readingPage，绝不重读 620 页 SQLite 导致标题/页码滞后 2–5 秒。
        mutable.update { state ->
            if (state.book?.id != book.id) state
            else state.copy(
                page = number,
                book = if (state.source == null) state.book.copy(readingPage = number) else state.book,
                catalog = if (state.source == null) state.catalog.map { s ->
                    if (s.id == book.id) s.copy(readingPage = number) else s
                } else state.catalog,
                error = null,
            )
        }
        if (updatingBook) {
            val saveJob = backgroundScope.launch {
                pageSaveLock.withLock {
                    runCatching { library.save(updatedBook) }
                }
            }
            pendingPageSaveJob = saveJob
            saveJob.join()
        }
    }

    suspend fun source() = action {
        val book = mutable.value.book ?: return@action
        val lesson = mutable.value.review?.let { review ->
            book.lessons.find { it.id == review.lesson || it.id == review.lesson.substringBefore("#q") }
        } ?: book.lessons.getOrNull(book.lessonIndex) ?: return@action
        mutable.update { it.copy(screen = BookScreen.READER, source = lesson, page = lesson.firstPage, prompt = null) }
    }

    /** A verified citation can arrive after the reader has changed books or lessons. */
    suspend fun citationSource(bookId: String, lessonId: String, page: Int) = action {
        val current = mutable.value
        val book = current.book ?: return@action
        val lesson = book.lessons.getOrNull(book.lessonIndex) ?: return@action
        if (current.screen != BookScreen.LEARNING || book.id != bookId || lesson.id != lessonId) return@action
        require(book.page(page) != null) { "页码超出了这本教材的范围。" }
        mutable.update { it.copy(screen = BookScreen.READER, source = lesson, page = page, prompt = null) }
    }

    suspend fun back() = action {
        if (mutable.value.source != null) mutable.update {
            it.copy(screen = if (it.review == null) BookScreen.LEARNING else BookScreen.REVIEWING, source = null)
        } else {
            val fromReview = mutable.value.review != null
            library.closeReview()
            if (!fromReview) library.clearOpenSession()
            mutable.update { it.copy(screen = if (fromReview) BookScreen.REVIEWS else BookScreen.HOME,
                review = null, prompt = null, reviews = library.reviews(), todayQueue = library.reviewQueue()) }
        }
    }

    suspend fun lesson(index: Int) = action {
        val book = mutable.value.book ?: return@action
        require(index in 0..book.lessons.size) { "没有这一节学习内容。" }
        val next = book.copy(lessonIndex = index)
        persist(next); openLearning(next)
    }

    suspend fun showReviews() = action {
        library.closeReview()
        library.clearOpenSession()
        mutable.update { it.copy(screen = BookScreen.REVIEWS, review = null, source = null,
            prompt = null, reviews = library.reviews(), todayQueue = library.reviewQueue()) }
    }

    /** 单卡流：打开今日队列里的下一题；没有下一题就回到复习页，显示刷新后的队列或「今日复习已完成」。 */
    suspend fun nextReview() = action {
        val ui = mutable.value
        val cards = ui.todayQueue.cards
        val at = ui.review?.let { open -> cards.indexOfFirst { it.book == open.book && it.lesson == open.lesson && it.mode == open.mode } } ?: -1
        val next = cards.getOrNull(at + 1)
        if (next == null) {
            library.closeReview()
            mutable.update { it.copy(screen = BookScreen.REVIEWS, review = null, source = null, prompt = null,
                reviews = library.reviews(), todayQueue = library.reviewQueue()) }
        } else {
            val review = library.review(next.book, next.lesson, next.mode)
            library.openReview(review)
            mutable.update { it.copy(book = library.book(review.book), screen = BookScreen.REVIEWING, source = null,
                prompt = null, review = review, answer = review.answer, reviews = library.reviews()) }
        }
    }

    /** The mounted host refreshes this projection across local midnight, including on 今天. */
    suspend fun refreshReviews() = action(showBusy = false) {
        val current = mutable.value.review
        val updated = current?.let { library.review(it.book, it.lesson, it.mode) }
        mutable.update { it.copy(reviews = library.reviews(), review = updated,
            answer = updated?.answer ?: it.answer, todayQueue = if (current == null) library.reviewQueue() else it.todayQueue) }
    }

    suspend fun openReview(entry: LocalBookReview) = action {
        val review = library.review(entry.book, entry.lesson, entry.mode)
        val book = library.book(review.book)
        library.openReview(review)
        val existingQueue = mutable.value.todayQueue
        val hydratedQueue = ensureQueueIncludes(
            if (existingQueue.cards.isEmpty()) library.reviewQueue() else existingQueue,
            review,
        )
        mutable.update { it.copy(book = book, screen = BookScreen.REVIEWING, source = null,
            prompt = null, review = review, answer = review.answer, reviews = library.reviews(),
            todayQueue = hydratedQueue) }
    }

    /** 单卡自评：点「没记住」(AGAIN) 或「记住了」(GOOD) 后写入 FSRS 并推进到今日队列下一题。 */
    suspend fun rateReview(rating: com.coach.domain.fsrs.FsrsRating, currentAnswer: PracticeAnswer) = action {
        val ui = mutable.value
        val open = ui.review ?: return@action
        library.saveReviewRating(open, rating, currentAnswer)
        val cards = ui.todayQueue.cards
        val at = cards.indexOfFirst { it.book == open.book && it.lesson == open.lesson && it.mode == open.mode }
        val next = if (at >= 0) cards.getOrNull(at + 1) else null
        if (next == null) {
            library.closeReview()
            mutable.update { it.copy(screen = BookScreen.REVIEWS, review = null, source = null, prompt = null,
                reviews = library.reviews(), todayQueue = library.reviewQueue()) }
        } else {
            val review = library.review(next.book, next.lesson, next.mode)
            library.openReview(review)
            mutable.update { it.copy(book = library.book(review.book), screen = BookScreen.REVIEWING, source = null,
                prompt = null, review = review, answer = review.answer, reviews = library.reviews()) }
        }
    }

    suspend fun saveReviewDraft(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer): Boolean {
        var saved = false
        action(showBusy = false) {
            val review = library.review(book, lesson, mode)
            library.saveReviewAnswer(review, answer)
            val updated = library.review(book, lesson, mode)
            val open = mutable.value.review
            mutable.update { it.copy(reviews = library.reviews()) }
            if (open?.book == book && open.lesson == lesson && open.mode == mode) {
                mutable.update { it.copy(review = updated, answer = answer) }
            }
            saved = true
        }
        return saved
    }

    suspend fun answer(answer: PracticeAnswer) = action(showBusy = false) {
        val book = mutable.value.book ?: return@action
        val lesson = book.lessons.getOrNull(book.lessonIndex) ?: return@action
        writeAnswer(book.id, lesson.id, book.questionMode, answer)
    }

    /**
     * Stores an answer under the book, lesson and mode it was typed in, not the one that happens to be open.
     *
     * The identity check and the write share this action's lock: a lesson switch that was already waiting on
     * SQLite updates the open lesson first, so checking before the lock would let a keystroke typed in the
     * still-visible lesson land in the one the switch finally opens.
     */
    suspend fun saveDraft(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer): Boolean {
        var saved = false
        action(showBusy = false) {
            writeAnswer(book, lesson, mode, answer)
            saved = true
        }
        return saved
    }

    private fun writeAnswer(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer) {
        library.saveAnswer(book, lesson, mode, answer)
        mutable.update { it.copy(reviews = library.reviews(), todayQueue = if (it.review == null) library.reviewQueue() else it.todayQueue) }
        val open = mutable.value.book
        if (mutable.value.review == null && open?.id == book && open.lessons.getOrNull(open.lessonIndex)?.id == lesson && open.questionMode == mode) {
            mutable.update { it.copy(answer = answer, ladder = library.ladder(book)) }
        }
    }

    /** 「接着做题」只推进小结游标：答过的题、选过的选项和走到哪一节都不动。 */
    suspend fun recapContinue() = action(showBusy = false) {
        val book = mutable.value.book ?: return@action
        val next = QuestionLadder.closeRecap(mutable.value.ladder)
        library.saveLadder(book.id, next)
        mutable.update { it.copy(ladder = next) }
    }

    /** 显式「重新整理章节」：按新版结构识别重算并缓存本书的章节划分。 */
    suspend fun reorganizeSections() = action {
        val book = mutable.value.book ?: return@action
        val updated = library.reorganizeSections(book.id)
        mutable.update { it.copy(book = updated, catalog = library.catalog()) }
        applyModelSectioningIfNeeded(book.id, structureProvider)
    }

    /** 若当前书无任何标题/书签/目录页且未尝试过模型分节，调用一次 Haiku 分节并按书缓存；失败退回段落边界切分。 */
    suspend fun ensureUnstructuredBookSectioned(
        bookId: String,
        provider: BookStructureProvider? = structureProvider,
    ): Textbook? = actions.withLock {
        applyModelSectioningIfNeeded(bookId, provider)
    }

    private suspend fun applyModelSectioningIfNeeded(
        bookId: String,
        provider: BookStructureProvider?,
    ): Textbook? {
        val activeProvider = provider ?: return null
        val currentBook = withContext(Dispatchers.IO) { library.book(bookId) }
        val currentStructure = currentBook.structure ?: return null
        if (!currentStructure.needsModelSectioning || currentBook.hasPendingOcr) return currentBook
        val candidates = BookStructurePlanner.extractUnstructuredCandidates(
            currentBook.id,
            currentBook.pages,
            currentStructure.pageRoles,
        )
        if (candidates.isEmpty()) return currentBook
        val bookPages = maxOf(currentBook.pages.size, currentBook.pages.maxOfOrNull { it.number } ?: 1)
        val result = runCatching { activeProvider.sectionUnstructuredBook(currentBook, candidates, bookPages) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                ModelSectionResult.Failed("分节请求未完成，已退回段落边界切分。")
            }
        if (result is ModelSectionResult.Deferred) return currentBook
        val boundaries = (result as? ModelSectionResult.Done)?.boundaries.orEmpty()
        val updatedStructure = BookStructurePlanner.applyModelBoundaries(
            bookId = currentBook.id,
            title = currentBook.title,
            format = currentBook.format,
            pages = currentBook.pages,
            boundaries = boundaries,
            fallback = currentStructure,
        )
        val savedBook = withContext(Dispatchers.IO) { library.saveBookStructure(currentBook.id, updatedStructure) }
        val catalog = withContext(Dispatchers.IO) { library.catalog() }
        mutable.update { state ->
            state.copy(
                book = if (state.book?.id == savedBook.id) savedBook.copy(readingPage = state.page) else state.book,
                catalog = catalog,
            )
        }
        return savedBook
    }

    suspend fun recordSectionQuizAnswer(
        lesson: BookLesson,
        questionIndex: Int,
        practice: Practice,
        answer: PracticeAnswer,
    ) = action(showBusy = false) {
        val book = mutable.value.book ?: return@action
        library.saveSectionCheckAnswer(book.id, lesson, questionIndex, practice, answer)
        mutable.update { it.copy(reviews = library.reviews(), todayQueue = library.reviewQueue(), ladder = library.ladder(book.id)) }
    }

    suspend fun openSectionPractice(sectionIndex: Int) = action {
        val book = mutable.value.book ?: return@action
        val idx = sectionIndex.coerceIn(0, (book.lessons.size - 1).coerceAtLeast(0))
        val next = book.copy(learning = true, promptSeen = true, lessonIndex = idx, questionMode = QuestionMode.CHOICE)
        library.saveLearningPath(book.id, LearningPath.QUESTION_FIRST.name)
        persist(next)
        openLearning(next, LearningPath.QUESTION_FIRST)
    }
}
