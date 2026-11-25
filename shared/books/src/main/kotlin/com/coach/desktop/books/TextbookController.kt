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

internal enum class BookScreen { HOME, COURSE, COURSE_MOCK, BOOKLIST, READER, LEARNING, REVIEWS, REVIEWING }
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
    /** 当前书最近落盘的伴读练习草稿（`book_chapter_progress.practice_draft_json`），供首页「继续」精确续接。 */
    val activePracticeDraft: ChapterPracticeDraft? = null,
    /** 2.0-AI-1：无未完草稿时按最高 NeedScore 推荐的薄弱概念（供首页「继续」单行依据与直达）。 */
    val focusRecommendation: HomeFocusRecommendation? = null,
    /** 2.0-FIX-E (#4A)：当前焦点书最近一次提问正文（绑定 bookId 防止切书串台），供书架「继续」卡展示「上次问：…」。 */
    val lastBookQuestion: BookLastQuestion? = null,
    val activeCourseId: String? = null,
    val activeCourseTopicId: String? = null,
    val activeCourseDetail: CourseDetailSnapshot? = null,
    val pendingCourseJump: BookPassage? = null,
    val courseNotice: String? = null,
    val courseBusy: Boolean = false,
    /** 当前打开的书单 ID（从书单进入阅读器时保留上下文，返回时回到该书单页）。 */
    val activeBooklistId: String? = null,
    /** 书单后台正在执行两阶段共同主题对齐时为 true。 */
    val booklistAligning: Boolean = false,
    /** 章节已读、主题静音或共享笔记变更递增版本号，驱动书单页与伴读抽屉即时刷新。 */
    val booklistRevision: Int = 0,
    /** 2.0-AI-3：跨材料问答点击异书角标跳页后保持伴读「提问」抽屉开启。 */
    val keepAskTabOpen: Boolean = false,
    /** TON-1823 PR A：单行真实上传与服务端整理状态（按当前账号隔离，绝不阻塞本地阅读）。 */
    val uploadStatuses: Map<String, BookUploadEntry> = emptyMap(),
)

internal class TextbookController(val library: TextbookLibrary, var structureProvider: BookStructureProvider? = null) {
    var conceptSemanticJudge: ConceptSemanticJudge? = null
    @Volatile var allowRemoteBooklistAi: () -> Boolean = { true }
    @Volatile var unifiedUploadClient: UnifiedBookRemoteClient? = null
    internal val uploadCoordinator = UnifiedBookUploadCoordinator(library) { unifiedUploadClient }
    @Volatile private var activeBooklistAlignJob: Job? = null
    suspend fun awaitBooklistAlignment() { activeBooklistAlignJob?.join() }
    private val mutable = MutableStateFlow(LibraryUi(initialized = false))
    val state = mutable.asStateFlow()
    private val actions = Mutex()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeImport: BookImportControl? = null
    @Volatile private var activeImportJob: Job? = null
    @Volatile private var activeUploadSyncJob: Job? = null
    @Volatile private var pendingPageSaveJob: Job? = null
    private val pageSaveLock = Mutex()
    private val indexingControls = ConcurrentHashMap<String, BookImportControl>()
    private val indexingJobs = ConcurrentHashMap<String, Job>()

    init {
        backgroundScope.launch {
            uploadCoordinator.statuses.collect { statuses ->
                mutable.update { current ->
                    if (current.uploadStatuses == statuses) current else current.copy(uploadStatuses = statuses)
                }
            }
        }
    }

    fun onUploadIdentityChanged(newSessionId: String?): Job? {
        activeUploadSyncJob?.cancel()
        activeUploadSyncJob = null
        uploadCoordinator.onSessionChanged(newSessionId)
        return if (newSessionId != null && unifiedUploadClient != null) syncBookUploads() else null
    }

    fun syncBookUploads(): Job {
        val job = backgroundScope.launch {
            runCatching { uploadCoordinator.syncOnce() }
        }
        activeUploadSyncJob = job
        return job
    }

    fun retryBookUpload(localBookId: String): Job = backgroundScope.launch {
        runCatching { uploadCoordinator.retryBook(localBookId) }
    }

    private fun persistImportOwnerIfNew(staged: TextbookLibrary.StagedBookImport, initiatingAccountKey: String?) {
        if (!staged.alreadyExisted && initiatingAccountKey != null) {
            val effectiveAccountKey = runCatching { library.boundUploadAccountKey(initiatingAccountKey) }.getOrNull()
                ?: initiatingAccountKey
            runCatching { library.saveImportAccountKey(staged.book.id, effectiveAccountKey) }
        }
    }

    private fun enqueueBookForUnifiedUpload(book: Textbook, initiatingAccountKey: String? = null) {
        if (unifiedUploadClient == null) return
        backgroundScope.launch {
            runCatching {
                val rawAccountKey = initiatingAccountKey ?: uploadCoordinator.resolveInitiatingAccountKey()
                val accountKey = rawAccountKey?.let { key -> library.boundUploadAccountKey(key) ?: key }
                if (accountKey != null) {
                    uploadCoordinator.enqueueBookForAccount(
                        accountKey = accountKey,
                        localBookId = book.id,
                        fileSha256 = book.id,
                        format = book.format,
                        title = book.title,
                    )
                    if (accountKey == uploadCoordinator.currentAccountKey()) {
                        uploadCoordinator.syncOnce()
                    }
                }
            }
        }
    }
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
                val restoredCoursePair = resolveRestoredCourse(snap.collections, restoredScreen, book?.id)
                val restoredCourseId = restoredCoursePair?.first
                val restoredCourseDetail = restoredCoursePair?.second
                val restoredCourseTopicId = restoredCourseDetail?.activeAnchor?.topicId
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
                        activePracticeDraft = snap.activePracticeDraft,
                        focusRecommendation = snap.focusRecommendation,
                        lastBookQuestion = snap.lastBookQuestion,
                        activeCourseId = restoredCourseId,
                        activeCourseTopicId = restoredCourseTopicId,
                        activeCourseDetail = restoredCourseDetail,
                        uploadStatuses = current.uploadStatuses,
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

    private fun loadLastQuestion(bookId: String?): BookLastQuestion? =
        bookId?.let { id -> library.lastQuestion(id)?.let { BookLastQuestion(id, it) } }

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
            val initiatingAccountKey = uploadCoordinator.currentAccountKey()
                ?: runCatching { uploadCoordinator.resolveInitiatingAccountKey() }.getOrNull()
            action {
                var lastBook: Textbook? = null
                for (path in paths) {
                    val useTier0 = library.lazyPdfExtras && path.fileName?.toString().orEmpty().endsWith(".pdf", ignoreCase = true)
                    val staged = if (useTier0) library.stageBookImportTier0(path) else library.stageBookImport(path)
                    persistImportOwnerIfNew(staged, initiatingAccountKey)
                    enqueueBookForUnifiedUpload(staged.book, initiatingAccountKey)
                    lastBook = staged.book
                }
                val catalog = library.catalog()
                val collections = library.collectionSummaries()
                val reviews = library.reviews()
                val todayQueue = library.reviewQueue()
                val opened = lastBook ?: catalog.firstOrNull()?.let { library.book(it.id) }
                val draft = opened?.let { library.latestChapterPracticeDraft(it.id) }
                val focusRec = library.topHomeRecommendation(opened?.id)
                val lastQ = loadLastQuestion(opened?.id)
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
                        activePracticeDraft = draft,
                        focusRecommendation = focusRec,
                        lastBookQuestion = lastQ,
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
            val initiatingAccountKey = uploadCoordinator.currentAccountKey()
                ?: runCatching { uploadCoordinator.resolveInitiatingAccountKey() }.getOrNull()
            action {
                val cleanTitle = title.trim()
                require(cleanTitle.isNotEmpty()) { if (kind == CollectionKind.COURSE) "请填写课程名。" else "请填写书单名。" }
                val importedBooks = mutableListOf<Textbook>()
                val preRejectedPastExams = mutableListOf<String>()
                for (path in newFiles) {
                    val fileName = path.fileName?.toString().orEmpty()
                    if (kind == CollectionKind.COURSE && CourseClientGates.isPastExamMaterial(fileName, fileName)) {
                        preRejectedPastExams += fileName
                        continue
                    }
                    val useTier0 = library.lazyPdfExtras && fileName.endsWith(".pdf", ignoreCase = true)
                    val staged = if (useTier0) library.stageBookImportTier0(path) else library.stageBookImport(path)
                    if (kind == CollectionKind.COURSE && !staged.alreadyExisted) {
                        library.setBookShowStandalone(staged.book.id, false)
                    }
                    persistImportOwnerIfNew(staged, initiatingAccountKey)
                    enqueueBookForUnifiedUpload(staged.book, initiatingAccountKey)
                    importedBooks += staged.book
                }
                val allIds = (existingBookIds + importedBooks.map { it.id }).distinct()
                val now = System.currentTimeMillis()
                val id = "${kind.name.lowercase()}-${java.util.UUID.randomUUID().toString().take(8)}"
                val cleanExamDate = examDate?.trim()?.takeIf(String::isNotEmpty)
                var alignedCourseDetail: CourseDetailSnapshot? = null
                var rejectedPastExams: List<String> = emptyList()
                if (kind == CollectionKind.COURSE) {
                    val (_, rejected) = library.withCourseDatabase { db ->
                        val (cid, skipped) = CourseStore.createCourse(
                            db = db,
                            title = cleanTitle,
                            examProfile = CourseExamProfile(
                                examDate = cleanExamDate.orEmpty(),
                                examEnabled = !cleanExamDate.isNullOrBlank(),
                            ),
                            materialBookIds = allIds,
                            existingId = id,
                        )
                        val detail = CourseAutoAligner.alignLocally(db, cid)
                        Triple(cid, skipped, detail)
                    }.let { (cid, skipped, detail) ->
                        alignedCourseDetail = detail
                        cid to skipped
                    }
                    rejectedPastExams = preRejectedPastExams + rejected
                } else {
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
                        library.attachMaterial(
                            CollectionMaterialRecord(
                                collectionId = id,
                                materialId = bookId,
                                role = "LIST_BOOK",
                                ordinal = idx,
                                addedAt = now,
                            ),
                        )
                    }
                }
                val catalog = library.catalog()
                val collections = library.collectionSummaries()
                val reviews = library.reviews()
                val todayQueue = library.reviewQueue()
                val focusBook = importedBooks.firstOrNull()
                    ?: allIds.firstOrNull()?.let { runCatching { library.book(it) }.getOrNull() }
                    ?: mutable.value.book
                    ?: catalog.firstOrNull()?.let { library.book(it.id) }
                val draft = focusBook?.let { library.latestChapterPracticeDraft(it.id) }
                val focusRec = library.topHomeRecommendation(focusBook?.id)
                val lastQ = loadLastQuestion(focusBook?.id)
                val noticeLabel = buildString {
                    append(if (kind == CollectionKind.COURSE) "已创建课程《$cleanTitle》" else "已创建书单《$cleanTitle》")
                    if (rejectedPastExams.isNotEmpty()) {
                        append("（已拦截 ${rejectedPastExams.size} 份历年真题文件）")
                    }
                }
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
                        activePracticeDraft = draft,
                        focusRecommendation = focusRec,
                        lastBookQuestion = lastQ,
                        importNotice = BookImportNotice(System.nanoTime(), noticeLabel),
                        activeCourseId = if (kind == CollectionKind.COURSE) id else current.activeCourseId,
                        activeCourseTopicId = null,
                        activeCourseDetail = if (kind == CollectionKind.COURSE) alignedCourseDetail else current.activeCourseDetail,
                        courseNotice = if (rejectedPastExams.isNotEmpty()) {
                            "历年真题文件已由门禁拦截，未进入备考题库。"
                        } else null,
                    )
                }
                if (kind == CollectionKind.BOOKLIST && allIds.size >= 2) {
                    launchBooklistAlignment(id)
                }
            }
        } finally {
            onStaged()
        }
    }

    suspend fun openBooklist(collectionId: String) = action {
        val collections = library.collectionSummaries()
        require(collections.any { it.record.id == collectionId && it.record.kind == CollectionKind.BOOKLIST }) { "这份书单已不在列表中。" }
        library.closeReview()
        library.clearOpenSession()
        mutable.update { current ->
            current.copy(
                collections = collections,
                screen = BookScreen.BOOKLIST,
                activeBooklistId = collectionId,
                prompt = null,
                source = null,
                review = null,
                initialized = true,
            )
        }
    }

    fun addBooksToBooklist(
        collectionId: String,
        existingBookIds: List<String>,
        newFiles: List<Path>,
        onStaged: () -> Unit = {},
    ): Job = backgroundScope.launch {
        try {
            val initiatingAccountKey = uploadCoordinator.currentAccountKey()
                ?: runCatching { uploadCoordinator.resolveInitiatingAccountKey() }.getOrNull()
            action {
                val now = System.currentTimeMillis()
                val importedIds = mutableListOf<String>()
                for (path in newFiles) {
                    val useTier0 = library.lazyPdfExtras && path.fileName?.toString().orEmpty().endsWith(".pdf", ignoreCase = true)
                    val staged = if (useTier0) library.stageBookImportTier0(path) else library.stageBookImport(path)
                    persistImportOwnerIfNew(staged, initiatingAccountKey)
                    enqueueBookForUnifiedUpload(staged.book, initiatingAccountKey)
                    importedIds += staged.book.id
                }
                val currentMembers = library.collectionMaterials(collectionId)
                val existingSet = currentMembers.map { it.materialId }.toSet()
                val toAdd = (existingBookIds + importedIds).distinct().filterNot { it in existingSet }
                var nextOrd = (currentMembers.maxOfOrNull { it.ordinal } ?: -1) + 1
                for (bookId in toAdd) {
                    library.attachMaterial(
                        CollectionMaterialRecord(
                            collectionId = collectionId,
                            materialId = bookId,
                            role = "LIST_BOOK",
                            ordinal = nextOrd++,
                            addedAt = now,
                        ),
                    )
                }
                val catalog = library.catalog()
                val collections = library.collectionSummaries()
                mutable.update { current ->
                    current.copy(
                        catalog = catalog,
                        collections = collections,
                        booklistRevision = current.booklistRevision + 1,
                        importNotice = if (toAdd.isNotEmpty()) BookImportNotice(System.nanoTime(), "已向书单加入 ${toAdd.size} 本书。") else current.importNotice,
                    )
                }
                val totalAfter = collections.firstOrNull { it.record.id == collectionId }?.materialIds?.size ?: 0
                if (totalAfter >= 2) {
                    launchBooklistAlignment(collectionId)
                }
            }
        } finally {
            onStaged()
        }
    }

    fun launchBooklistAlignment(collectionId: String, judgeOverride: ConceptSemanticJudge? = null): Job {
        val job = backgroundScope.launch {
            mutable.update { it.copy(booklistAligning = true) }
            try {
                val remoteAllowed = allowRemoteBooklistAi() && library.isReadingAiExplainEnabled()
                val requestedJudge = judgeOverride ?: conceptSemanticJudge ?: HeuristicConceptSemanticJudge
                val judge = if (!remoteAllowed && requestedJudge is RemoteBooklistSemanticJudge) {
                    HeuristicConceptSemanticJudge
                } else {
                    requestedJudge
                }
                BooklistAlignmentEngine.alignBooklist(library, collectionId, judge)
                val collections = library.collectionSummaries()
                mutable.update { current ->
                    current.copy(
                        collections = collections,
                        booklistRevision = current.booklistRevision + 1,
                    )
                }
            } finally {
                mutable.update { it.copy(booklistAligning = false) }
            }
        }
        activeBooklistAlignJob = job
        return job
    }

    fun recordPageDwell(bookId: String, tocNodeId: String, page: Int, pagesTotal: Int, dwellMs: Long = ChapterReadPolicy.MIN_DWELL_MS) {
        backgroundScope.launch {
            val updated = runCatching {
                library.recordPageDwell(bookId, tocNodeId, page, pagesTotal, dwellMs)
            }.getOrNull()
            if (updated != null) {
                val rec = runCatching { library.topHomeRecommendation(bookId) }.getOrNull()
                mutable.update { it.copy(booklistRevision = it.booklistRevision + 1, focusRecommendation = rec ?: it.focusRecommendation) }
            }
        }
    }

    fun recordRereadBacktrack(bookId: String, tocNodeId: String, page: Int, canonicalName: String, dwellMs: Long) {
        backgroundScope.launch {
            runCatching {
                library.recordMasteryEvidence(
                    bookId = bookId,
                    tocNodeId = tocNodeId,
                    page = page,
                    canonicalName = canonicalName,
                    signal = LearnerSignalType.REREAD_BACKTRACK,
                    durationMs = dwellMs,
                )
            }
            val rec = runCatching { library.topHomeRecommendation(bookId) }.getOrNull()
            if (rec != null) mutable.update { it.copy(focusRecommendation = rec) }
        }
    }

    fun recordSelectionAsk(bookId: String, tocNodeId: String, page: Int, canonicalName: String, questionText: String? = null) {
        backgroundScope.launch {
            if (!questionText.isNullOrBlank()) {
                runCatching { library.saveLastQuestion(bookId, questionText) }
            }
            runCatching {
                library.recordMasteryEvidence(
                    bookId = bookId,
                    tocNodeId = tocNodeId,
                    page = page,
                    canonicalName = canonicalName,
                    signal = LearnerSignalType.SELECTION_ASK,
                )
            }
            val rec = runCatching { library.topHomeRecommendation(bookId) }.getOrNull()
            val lastQ = runCatching { loadLastQuestion(bookId) }.getOrNull()
            mutable.update { current ->
                current.copy(
                    focusRecommendation = rec ?: current.focusRecommendation,
                    lastBookQuestion = if (current.book?.id == bookId) (lastQ ?: current.lastBookQuestion) else current.lastBookQuestion,
                )
            }
        }
    }

    fun setChapterManualReadState(bookId: String, tocNodeId: String, pagesTotal: Int, read: Boolean) {
        backgroundScope.launch {
            runCatching { library.setChapterManualReadState(bookId, tocNodeId, pagesTotal, read) }
            mutable.update { it.copy(booklistRevision = it.booklistRevision + 1) }
        }
    }

    fun setConceptMuted(conceptId: String, muted: Boolean) {
        backgroundScope.launch {
            runCatching { library.setConceptMuted(conceptId, muted) }
            val collections = runCatching { library.collectionSummaries() }.getOrNull()
            mutable.update { current ->
                current.copy(
                    collections = collections ?: current.collections,
                    booklistRevision = current.booklistRevision + 1,
                )
            }
        }
    }

    fun saveConceptSharedNote(conceptId: String, note: String) {
        backgroundScope.launch {
            runCatching { library.saveConceptSharedNote(conceptId, note) }
            mutable.update { it.copy(booklistRevision = it.booklistRevision + 1) }
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
        val capturedAccountAtLaunch = uploadCoordinator.currentAccountKey()
        val control = BookImportControl(
            progress = { progress -> mutable.update { it.copy(importing = progress) } },
        )
        activeImport = control
        val job = backgroundScope.launch {
            var staged: TextbookLibrary.StagedBookImport? = null
            var stagedNotified = false
            var initiatingAccountKey: String? = capturedAccountAtLaunch
            var createdAccountUploadRow = false
            fun releaseOnce() {
                if (!stagedNotified) {
                    stagedNotified = true
                    onStaged()
                }
            }
            try {
                if (initiatingAccountKey == null) {
                    initiatingAccountKey = runCatching { uploadCoordinator.resolveInitiatingAccountKey() }.getOrNull()
                }
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
                persistImportOwnerIfNew(result, initiatingAccountKey)
                control.check()
                val effectiveAccountKey = initiatingAccountKey?.let { key ->
                    runCatching { library.boundUploadAccountKey(key) }.getOrNull() ?: key
                }
                if (effectiveAccountKey != null && unifiedUploadClient != null) {
                    val accountHadUploadRecord = runCatching {
                        val existing = library.readUploadEntry(effectiveAccountKey, book.id)
                        existing != null && existing.state != BookUploadState.PENDING_DELETE
                    }.getOrDefault(false)
                    val enqueued = runCatching {
                        uploadCoordinator.enqueueBookForAccount(
                            accountKey = effectiveAccountKey,
                            localBookId = book.id,
                            fileSha256 = book.id,
                            format = book.format,
                            title = book.title,
                        )
                    }.isSuccess
                    if (enqueued && !accountHadUploadRecord) {
                        createdAccountUploadRow = true
                    }
                    if (!control.isCancelled() && effectiveAccountKey == uploadCoordinator.currentAccountKey()) {
                        syncBookUploads()
                    }
                }
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
                        val draft = library.latestChapterPracticeDraft(book.id)
                        val focusRec = library.topHomeRecommendation(book.id)
                        val lastQ = loadLastQuestion(book.id)
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
                                activePracticeDraft = draft,
                                focusRecommendation = focusRec,
                                lastBookQuestion = lastQ,
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
                        val draft = library.latestChapterPracticeDraft(book.id)
                        val focusRec = library.topHomeRecommendation(book.id)
                        val lastQ = loadLastQuestion(book.id)
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
                                activePracticeDraft = draft,
                                focusRecommendation = focusRec,
                                lastBookQuestion = lastQ,
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
                    val draft = if (stayElsewhere) null else library.latestChapterPracticeDraft(book.id)
                    val focusRec = if (stayElsewhere) null else library.topHomeRecommendation(book.id)
                    val lastQ = if (stayElsewhere) null else loadLastQuestion(book.id)
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
                            activePracticeDraft = if (stayElsewhere) current.activePracticeDraft else draft,
                            focusRecommendation = if (stayElsewhere) current.focusRecommendation else focusRec,
                            lastBookQuestion = if (stayElsewhere) current.lastBookQuestion else lastQ,
                            lastImportDurationMs = elapsedMs,
                        )
                    }
                }
                runOcrLoop(book.id, control, deleteOnEmptyFailure = !result.alreadyExisted)
            } catch (_: BookImportCancelled) {
                releaseOnce()
                handleCancelledImport(staged?.book?.id, staged?.alreadyExisted == false, initiatingAccountKey, cancelAccountUploadRow = createdAccountUploadRow)
            } catch (cancel: CancellationException) {
                releaseOnce()
                handleCancelledImport(staged?.book?.id, staged?.alreadyExisted == false, initiatingAccountKey, cancelAccountUploadRow = createdAccountUploadRow)
                throw cancel
            } catch (failure: Exception) {
                releaseOnce()
                handleFailedImport(staged?.book?.id, staged?.alreadyExisted == false, failure, initiatingAccountKey)
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
            val initiatingAccountKey = runCatching { library.importAccountKey(bookId) }.getOrNull()
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
                if (initiatingAccountKey == null) {
                    runCatching { library.clearPendingOcr(bookId) }
                }
                handleCancelledImport(bookId, deleteLocalBook = initiatingAccountKey != null, initiatingAccountKey = initiatingAccountKey)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                runCatching { library.clearPendingOcr(bookId) }
                handleFailedImport(bookId, deleteIfNoOcrYet = false, failure = failure, initiatingAccountKey = initiatingAccountKey)
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

    private suspend fun handleCancelledImport(
        bookId: String?,
        deleteLocalBook: Boolean,
        initiatingAccountKey: String?,
        cancelAccountUploadRow: Boolean = false,
    ) = withContext(NonCancellable) {
        if (bookId != null && (deleteLocalBook || cancelAccountUploadRow)) {
            if (deleteLocalBook) {
                runCatching { library.deleteBook(bookId, initiatingAccountKey) }
            } else {
                runCatching { library.cancelAccountUploadIntent(bookId, initiatingAccountKey) }
            }
            runCatching { uploadCoordinator.refreshCurrentAccountStatuses() }
            val effectiveAccountKey = initiatingAccountKey?.let { key ->
                runCatching { library.boundUploadAccountKey(key) }.getOrNull() ?: key
            }
            if (effectiveAccountKey != null && effectiveAccountKey == uploadCoordinator.currentAccountKey() && unifiedUploadClient != null) {
                syncBookUploads()
            }
        }
        val catalog = runCatching { library.catalog() }.getOrElse { emptyList() }
        val fallbackBook = catalog.firstOrNull()?.let { runCatching { library.book(it.id) }.getOrNull() }
        val fallbackDraft = fallbackBook?.let { runCatching { library.latestChapterPracticeDraft(it.id) }.getOrNull() }
        val fallbackRec = runCatching { library.topHomeRecommendation(fallbackBook?.id) }.getOrNull()
        val fallbackLastQ = runCatching { loadLastQuestion(fallbackBook?.id) }.getOrNull()
        mutable.update { current ->
            val removedOpen = deleteLocalBook && bookId != null && current.book?.id == bookId
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
                activePracticeDraft = if (removedOpen) fallbackDraft else current.activePracticeDraft,
                focusRecommendation = if (removedOpen) fallbackRec else current.focusRecommendation,
                lastBookQuestion = if (removedOpen) fallbackLastQ else current.lastBookQuestion,
                error = "已取消导入，书架没有新增教材。",
            )
        }
    }

    private suspend fun handleFailedImport(
        bookId: String?,
        deleteIfNoOcrYet: Boolean,
        failure: Exception,
        initiatingAccountKey: String?,
    ) = withContext(NonCancellable) {
        val message = failure.message.takeIf { failure is IllegalArgumentException && ReadableText.readsAsChinese(it) }
            ?: "暂时无法读取或保存教材，请确认文件完整、未加密，然后重试。"
        val shouldDelete = bookId != null && deleteIfNoOcrYet &&
            runCatching { library.book(bookId).pages.none { it.ocr } }.getOrDefault(false)
        if (shouldDelete) {
            runCatching { library.deleteBook(checkNotNull(bookId), initiatingAccountKey) }
            runCatching { uploadCoordinator.refreshCurrentAccountStatuses() }
            val effectiveAccountKey = initiatingAccountKey?.let { key ->
                runCatching { library.boundUploadAccountKey(key) }.getOrNull() ?: key
            }
            if (effectiveAccountKey != null && effectiveAccountKey == uploadCoordinator.currentAccountKey() && unifiedUploadClient != null) {
                syncBookUploads()
            }
        }
        val catalog = runCatching { library.catalog() }.getOrElse { emptyList() }
        val fallbackBook = catalog.firstOrNull()?.let { runCatching { library.book(it.id) }.getOrNull() }
        val fallbackDraft = fallbackBook?.let { runCatching { library.latestChapterPracticeDraft(it.id) }.getOrNull() }
        val fallbackRec = runCatching { library.topHomeRecommendation(fallbackBook?.id) }.getOrNull()
        val fallbackLastQ = runCatching { loadLastQuestion(fallbackBook?.id) }.getOrNull()
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
                activePracticeDraft = if (removedOpen) fallbackDraft else current.activePracticeDraft,
                focusRecommendation = if (removedOpen) fallbackRec else current.focusRecommendation,
                lastBookQuestion = if (removedOpen) fallbackLastQ else current.lastBookQuestion,
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
            val draft = library.latestChapterPracticeDraft(book.id)
            val focusRec = library.topHomeRecommendation(book.id)
            val lastQ = loadLastQuestion(book.id)
            mutable.update { current ->
                LibraryUi(
                    catalog = catalog, book = book, collections = collections, page = book.readingPage, busy = true,
                    reviews = reviews, todayQueue = todayQueue,
                    learningPath = restoredPath(book), learningPathSaved = pathSaved,
                    ladder = ladder, importing = current.importing, importNotice = current.importNotice,
                    initialized = true, indexing = current.indexing,
                    pendingMultiDrop = current.pendingMultiDrop,
                    lastImportDurationMs = current.lastImportDurationMs,
                    activePracticeDraft = draft,
                    focusRecommendation = focusRec,
                    lastBookQuestion = lastQ,
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
            val draft = library.latestChapterPracticeDraft(book.id)
            val resumePage = if (targetPage == null && draft != null && draft.hasMeaningfulDraft() && book.page(draft.page) != null) {
                draft.page
            } else book.readingPage
            val activeBook = if (resumePage != book.readingPage) book.copy(readingPage = resumePage) else book
            val lastQ = loadLastQuestion(activeBook.id)
            mutable.update { current ->
                current.copy(
                    catalog = catalog,
                    collections = collections,
                    book = activeBook,
                    screen = BookScreen.READER,
                    prompt = null,
                    source = null,
                    page = activeBook.readingPage,
                    review = null,
                    reviews = reviews,
                    todayQueue = todayQueue,
                    learningPath = restoredPath(activeBook),
                    learningPathSaved = pathSaved,
                    questionsOpen = false,
                    ladder = ladder,
                    initialized = true,
                    activePracticeDraft = draft,
                    lastBookQuestion = lastQ,
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
        val draft = library.latestChapterPracticeDraft(book.id)
        val focusRec = library.topHomeRecommendation(book.id)
        val resumePage = when {
            draft != null && draft.hasMeaningfulDraft() && book.page(draft.page) != null -> draft.page
            focusRec != null && focusRec.bookId == book.id && book.page(focusRec.targetPage) != null -> focusRec.targetPage
            else -> book.readingPage
        }
        val activeBook = if (resumePage != book.readingPage) {
            book.copy(readingPage = resumePage).also { library.save(it) }
        } else book
        library.saveOpenSession(activeBook.id, "READER")
        mutable.update { it.copy(book = activeBook, review = null, questionsOpen = false,
            screen = BookScreen.READER, page = activeBook.readingPage, source = null,
            prompt = null, initialized = true, activePracticeDraft = draft,
            focusRecommendation = focusRec,
            lastBookQuestion = loadLastQuestion(activeBook.id)) }
    }

    /** 实时持久化单书伴读与练习草稿到 `book_chapter_progress.practice_draft_json`，退出后点「继续」精确恢复。 */
    fun saveChapterPracticeDraft(draft: ChapterPracticeDraft, pagesTotal: Int) {
        val nodeId = draft.tocNodeId.ifBlank { "p${draft.page}" }
        val normalized = draft.copy(tocNodeId = nodeId, updatedAt = System.currentTimeMillis())
        mutable.update { current ->
            if (current.book?.id == normalized.bookId) current.copy(activePracticeDraft = normalized) else current
        }
        val saveJob = backgroundScope.launch {
            pageSaveLock.withLock {
                runCatching {
                    library.saveChapterPracticeDraft(normalized.bookId, normalized.tocNodeId, pagesTotal, normalized)
                }
            }
        }
        pendingPageSaveJob = saveJob
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
        val cur = mutable.value
        if (cur.source != null) {
            mutable.update {
                it.copy(screen = if (it.review == null) BookScreen.LEARNING else BookScreen.REVIEWING, source = null)
            }
        } else if (cur.activeCourseId != null && cur.screen != BookScreen.COURSE && cur.screen != BookScreen.HOME) {
            val refreshed = library.withCourseDatabase { db ->
                CourseStore.loadCourseDetail(db, cur.activeCourseId, library.clock)
            }
            library.clearOpenSession()
            mutable.update {
                it.copy(
                    screen = BookScreen.COURSE,
                    activeCourseTopicId = null,
                    activeCourseDetail = refreshed,
                    pendingCourseJump = null,
                )
            }
        } else if (cur.screen == BookScreen.BOOKLIST) {
            library.clearOpenSession()
            val focusBookId = cur.book?.id ?: cur.catalog.firstOrNull()?.id
            mutable.update {
                it.copy(
                    screen = BookScreen.HOME,
                    activeBooklistId = null,
                    collections = library.collectionSummaries(),
                    review = null,
                    prompt = null,
                    focusRecommendation = library.topHomeRecommendation(focusBookId),
                    lastBookQuestion = loadLastQuestion(focusBookId),
                )
            }
        } else if (cur.screen == BookScreen.READER && cur.activeBooklistId != null) {
            library.clearOpenSession()
            mutable.update {
                it.copy(
                    screen = BookScreen.BOOKLIST,
                    collections = library.collectionSummaries(),
                    review = null,
                    prompt = null,
                )
            }
        } else {
            val fromReview = cur.review != null
            library.closeReview()
            if (!fromReview) library.clearOpenSession()
            mutable.update {
                it.copy(
                    screen = if (fromReview) BookScreen.REVIEWS else BookScreen.HOME,
                    review = null,
                    prompt = null,
                    activeCourseId = null,
                    activeCourseTopicId = null,
                    activeCourseDetail = null,
                    pendingCourseJump = null,
                    courseNotice = null,
                    activeBooklistId = if (fromReview) it.activeBooklistId else null,
                    reviews = library.reviews(),
                    todayQueue = library.reviewQueue(),
                    focusRecommendation = library.topHomeRecommendation(it.book?.id),
                    lastBookQuestion = loadLastQuestion(it.book?.id ?: it.catalog.firstOrNull()?.id),
                )
            }
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
        val nextQueue = if (current == null) library.reviewQueue() else mutable.value.todayQueue
        mutable.update { it.copy(reviews = library.reviews(), review = updated,
            answer = updated?.answer ?: it.answer, todayQueue = nextQueue) }
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
        isPretest: Boolean = false,
    ) = action(showBusy = false) {
        val book = mutable.value.book ?: return@action
        library.saveSectionCheckAnswer(book.id, lesson, questionIndex, practice, answer, isPretest = isPretest)
        val nextQueue = library.reviewQueue()
        mutable.update {
            it.copy(
                reviews = library.reviews(),
                todayQueue = nextQueue,
                ladder = library.ladder(book.id),
                focusRecommendation = library.topHomeRecommendation(book.id),
            )
        }
    }

    suspend fun openSectionPractice(sectionIndex: Int) = action {
        val book = mutable.value.book ?: return@action
        val idx = sectionIndex.coerceIn(0, (book.lessons.size - 1).coerceAtLeast(0))
        val next = book.copy(learning = true, promptSeen = true, lessonIndex = idx, questionMode = QuestionMode.CHOICE)
        library.saveLearningPath(book.id, LearningPath.QUESTION_FIRST.name)
        persist(next)
        openLearning(next, LearningPath.QUESTION_FIRST)
    }

    private fun resolveRestoredCourse(
        collections: List<CollectionSummary>,
        screen: BookScreen,
        openBookId: String?,
    ): Pair<String, CourseDetailSnapshot>? {
        if (screen == BookScreen.READER && openBookId != null) {
            for (col in collections) {
                if (col.record.kind != CollectionKind.COURSE) continue
                val anchor = CourseJson.decodeActiveAnchor(col.record.lastActiveAnchorJson) ?: continue
                if (anchor.topicId != null && (anchor.bookId == openBookId || openBookId in col.materialIds)) {
                    val detail = runCatching { library.courseDetail(col.record.id) }.getOrNull()
                    if (detail != null) return col.record.id to detail
                }
            }
        }
        return null
    }

    suspend fun openCourse(courseId: String) = action {
        val detail = library.withCourseDatabase { db ->
            val current = CourseStore.loadCourseDetail(db, courseId, library.clock)
            if (current.examinableTopics.isEmpty() && current.pendingTopics.isEmpty() && current.materials.isNotEmpty()) {
                CourseAutoAligner.alignLocally(db, courseId)
            } else {
                current
            }
        }
        library.clearOpenSession()
        mutable.update {
            it.copy(
                screen = BookScreen.COURSE,
                activeCourseId = courseId,
                activeCourseTopicId = null,
                activeCourseDetail = detail,
                pendingCourseJump = null,
                courseNotice = null,
                collections = library.collectionSummaries(),
            )
        }
    }

    suspend fun openCourseTopic(courseId: String, topicId: String, explainer: BookExplainer? = null) = action {
        var detail = library.withCourseDatabase { db -> CourseStore.loadCourseDetail(db, courseId, library.clock) }
        val topic = detail.allTopics.firstOrNull { it.topicId == topicId }
            ?: throw IllegalArgumentException("找不到该课程专题。")
        val resumeDraft = topic.practiceDraft
        val targetSource = resumeDraft?.activeSourceBookId?.let { bid ->
            topic.sources.firstOrNull { it.bookId == bid }
        } ?: topic.sources.firstOrNull()
            ?: throw IllegalStateException("该专题下还没有绑定任何材料。")

        val targetPage = (resumeDraft?.takeIf { it.activeSourceBookId == targetSource.bookId }?.activePage
            ?: targetSource.startPage).coerceAtLeast(1)

        if (topic.pack == null && topic.sources.isNotEmpty()) {
            val pagesByBook = library.withCourseDatabase { db ->
                topic.sources.associate { s -> s.bookId to CourseStore.loadPagesForBook(db, s.bookId) }
            }
            val pack = CourseAutoAligner.buildOfflineTopicPack(detail, topic, pagesByBook)
            val hash = topic.packSourcesHash.orEmpty()
            library.withCourseDatabase { db ->
                CourseStore.saveTopicPack(db, topic.topicId, pack, hash)
                CourseStore.saveActiveAnchor(
                    db,
                    CourseActiveAnchor(
                        courseId = courseId,
                        topicId = topic.topicId,
                        bookId = targetSource.bookId,
                        page = targetPage,
                        companionOpen = true,
                    ),
                )
            }
            detail = library.withCourseDatabase { db -> CourseStore.loadCourseDetail(db, courseId, library.clock) }
        } else {
            library.withCourseDatabase { db ->
                CourseStore.saveActiveAnchor(
                    db,
                    CourseActiveAnchor(
                        courseId = courseId,
                        topicId = topic.topicId,
                        bookId = targetSource.bookId,
                        page = targetPage,
                        companionOpen = true,
                    ),
                )
            }
        }

        val loaded = library.book(targetSource.bookId)
        val clampedPage = if (loaded.page(targetPage) != null) targetPage else 1
        val activeBook = loaded.copy(readingPage = clampedPage)
        library.save(activeBook)
        library.saveOpenSession(activeBook.id, "READER")
        mutable.update { current ->
            current.copy(
                book = activeBook,
                page = clampedPage,
                screen = BookScreen.READER,
                source = null,
                prompt = null,
                activeCourseId = courseId,
                activeCourseTopicId = topic.topicId,
                activeCourseDetail = detail,
                pendingCourseJump = null,
                catalog = library.catalog(),
                collections = library.collectionSummaries(),
            )
        }
        if (explainer != null && topic.pack?.serverBacked != true) {
            backgroundScope.launch {
                runCatching { refreshCourseTopicPack(courseId, topicId, explainer) }
            }
        }
    }

    suspend fun switchCourseSource(bookId: String, targetPage: Int = 1) = action {
        val courseId = mutable.value.activeCourseId ?: return@action
        val topicId = mutable.value.activeCourseTopicId
        val loaded = library.book(bookId)
        val clampedPage = if (loaded.page(targetPage) != null) targetPage else 1
        val updatedBook = loaded.copy(readingPage = clampedPage)
        library.save(updatedBook)
        library.saveOpenSession(updatedBook.id, "READER")
        library.withCourseDatabase { db ->
            CourseStore.saveActiveAnchor(
                db,
                CourseActiveAnchor(
                    courseId = courseId,
                    topicId = topicId,
                    bookId = updatedBook.id,
                    page = clampedPage,
                    companionOpen = true,
                ),
            )
            if (topicId != null) {
                val curDraft = CourseStore.loadCourseDetail(db, courseId, library.clock).allTopics
                    .firstOrNull { it.topicId == topicId }?.practiceDraft
                    ?: CourseTopicPracticeDraft(topicId = topicId)
                CourseStore.savePracticeDraft(
                    db,
                    courseId,
                    curDraft.copy(
                        activeSourceBookId = updatedBook.id,
                        activePage = clampedPage,
                        companionOpen = true,
                    ),
                )
            }
        }
        val detail = library.courseDetail(courseId)
        mutable.update {
            it.copy(
                book = updatedBook,
                page = clampedPage,
                activeCourseDetail = detail,
            )
        }
    }

    suspend fun jumpToCourseCitation(ref: CourseCitationRef) = action {
        val loaded = library.book(ref.bookId)
        val verified = CourseClientGates.verifySingleCitation(ref, loaded.pages)
        val targetPage = verified?.page ?: ref.page.coerceIn(1, loaded.pages.size.coerceAtLeast(1))
        val pageObj = loaded.page(targetPage)
        val passage = if (verified != null && pageObj != null &&
            verified.startUtf16 in 0 until verified.endUtf16 &&
            verified.endUtf16 <= pageObj.text.length &&
            pageObj.text.substring(verified.startUtf16, verified.endUtf16) == verified.quote
        ) {
            BookPassage(loaded.id, targetPage, verified.startUtf16, verified.endUtf16, verified.quote)
        } else {
            val anchor = ref.timestampLabel?.let { CourseTranscriptParser.findTimestampAnchor(loaded.pages, it) }
            if (anchor != null) {
                BookPassage(loaded.id, anchor.pageNumber, anchor.startUtf16, anchor.endUtf16, anchor.excerpt)
            } else {
                null
            }
        }
        val finalPage = passage?.page ?: targetPage
        val updatedBook = loaded.copy(readingPage = finalPage)
        library.save(updatedBook)
        library.saveOpenSession(updatedBook.id, "READER")
        mutable.value.activeCourseId?.let { cid ->
            library.withCourseDatabase { db ->
                CourseStore.saveActiveAnchor(
                    db,
                    CourseActiveAnchor(
                        courseId = cid,
                        topicId = mutable.value.activeCourseTopicId,
                        bookId = updatedBook.id,
                        page = finalPage,
                        companionOpen = true,
                    ),
                )
            }
        }
        mutable.update {
            it.copy(
                book = updatedBook,
                page = finalPage,
                pendingCourseJump = passage,
                activeCourseDetail = it.activeCourseId?.let { cid -> library.courseDetail(cid) },
            )
        }
    }

    fun consumePendingCourseJump() {
        mutable.update { it.copy(pendingCourseJump = null) }
    }

    fun consumeKeepAskTabOpen() {
        mutable.update { it.copy(keepAskTabOpen = false) }
    }

    /** 2.0-AI-3：从「整门课 / 已读书目」问答或全局搜索点击跨材料页码胶囊，1 击切书跳页并保留「提问」抽屉。 */
    suspend fun jumpToCollectionCitation(citation: CollectionQaCitation) = action {
        val loaded = library.book(citation.bookId)
        val clampedPage = citation.page.coerceIn(1, loaded.pages.size.coerceAtLeast(1))
        val pageObj = loaded.page(clampedPage)
        val passage = if (pageObj != null) {
            SentenceCitationGate.locatePassageInPage(loaded.id, pageObj, citation.quoteAnchor, citation.timestampLabel)
        } else {
            citation.timestampLabel?.let { ts ->
                CourseTranscriptParser.findTimestampAnchor(loaded.pages, ts)?.let { anchor ->
                    BookPassage(loaded.id, anchor.pageNumber, anchor.startUtf16, anchor.endUtf16, anchor.excerpt)
                }
            }
        }
        val finalPage = passage?.page ?: clampedPage
        val updatedBook = loaded.copy(readingPage = finalPage)
        library.save(updatedBook)
        library.saveOpenSession(updatedBook.id, "READER")
        val collections = library.collectionSummaries()
        val targetCol = collections.firstOrNull { it.record.id == citation.collectionId }
        val nextCourseId = when {
            citation.collectionId == CollectionQaVirtualCollections.READ_LIBRARY_ID -> null
            targetCol?.record?.kind == CollectionKind.COURSE -> targetCol.record.id
            targetCol?.record?.kind == CollectionKind.BOOKLIST -> null
            else -> mutable.value.activeCourseId
        }
        val nextBooklistId = when {
            citation.collectionId == CollectionQaVirtualCollections.READ_LIBRARY_ID -> null
            targetCol?.record?.kind == CollectionKind.BOOKLIST -> targetCol.record.id
            targetCol?.record?.kind == CollectionKind.COURSE -> null
            else -> mutable.value.activeBooklistId
        }
        val nextDetail = nextCourseId?.let { cid -> runCatching { library.courseDetail(cid) }.getOrNull() }
        if (nextCourseId != null) {
            library.withCourseDatabase { db ->
                CourseStore.saveActiveAnchor(
                    db,
                    CourseActiveAnchor(
                        courseId = nextCourseId,
                        topicId = mutable.value.activeCourseTopicId,
                        bookId = updatedBook.id,
                        page = finalPage,
                        companionOpen = true,
                    ),
                )
            }
        }
        mutable.update { current ->
            current.copy(
                book = updatedBook,
                page = finalPage,
                screen = BookScreen.READER,
                source = null,
                prompt = null,
                review = null,
                pendingCourseJump = passage,
                keepAskTabOpen = true,
                activeCourseId = nextCourseId,
                activeCourseDetail = nextDetail,
                activeBooklistId = nextBooklistId,
                collections = collections,
                catalog = library.catalog(),
            )
        }
    }

    fun dismissCourseNotice() {
        mutable.update { it.copy(courseNotice = null) }
    }

    suspend fun openCourseMock(courseId: String) = action {
        val detail = library.withCourseDatabase { db ->
            CourseStore.loadCourseDetail(db, courseId, library.clock)
        }
        library.clearOpenSession()
        mutable.update {
            it.copy(
                screen = BookScreen.COURSE_MOCK,
                activeCourseId = courseId,
                activeCourseTopicId = null,
                activeCourseDetail = detail,
                pendingCourseJump = null,
                courseNotice = null,
            )
        }
    }

    suspend fun refreshActiveCourse(courseId: String) = action {
        val detail = library.withCourseDatabase { db ->
            CourseStore.loadCourseDetail(db, courseId, library.clock)
        }
        val collections = library.collectionSummaries()
        mutable.update { cur ->
            if (cur.activeCourseId == courseId) {
                cur.copy(activeCourseDetail = detail, collections = collections)
            } else {
                cur.copy(collections = collections)
            }
        }
    }

    suspend fun jumpToMockSourcePage(courseId: String, bookId: String, page: Int, quote: String) = action {
        val loaded = library.book(bookId)
        val clampedPage = page.coerceIn(1, loaded.pages.size.coerceAtLeast(1))
        val pageObj = loaded.page(clampedPage)
        val passage = if (pageObj != null) {
            SentenceCitationGate.locatePassageInPage(loaded.id, pageObj, quote, null)
        } else null
        val finalPage = passage?.page ?: clampedPage
        val updatedBook = loaded.copy(readingPage = finalPage)
        library.save(updatedBook)
        library.saveOpenSession(updatedBook.id, "READER")
        val collections = library.collectionSummaries()
        val detail = library.withCourseDatabase { db ->
            CourseStore.saveActiveAnchor(
                db,
                CourseActiveAnchor(
                    courseId = courseId,
                    topicId = null,
                    bookId = updatedBook.id,
                    page = finalPage,
                    companionOpen = false,
                ),
            )
            CourseStore.loadCourseDetail(db, courseId, library.clock)
        }
        mutable.update { cur ->
            cur.copy(
                book = updatedBook,
                page = finalPage,
                screen = BookScreen.READER,
                source = null,
                prompt = null,
                review = null,
                pendingCourseJump = passage,
                keepAskTabOpen = false,
                activeCourseId = courseId,
                activeCourseTopicId = null,
                activeCourseDetail = detail,
                collections = collections,
                catalog = library.catalog(),
            )
        }
    }

    fun addMaterialsToCourse(courseId: String, files: List<Path>, onStaged: () -> Unit = {}): Job = backgroundScope.launch {
        try {
            val initiatingAccountKey = uploadCoordinator.currentAccountKey()
                ?: runCatching { uploadCoordinator.resolveInitiatingAccountKey() }.getOrNull()
            action {
                val importedIds = mutableListOf<String>()
                val preRejected = mutableListOf<String>()
                for (path in files) {
                    val fileName = path.fileName?.toString().orEmpty()
                    if (CourseClientGates.isPastExamMaterial(fileName, fileName)) {
                        preRejected += fileName
                        continue
                    }
                    val useTier0 = library.lazyPdfExtras && fileName.endsWith(".pdf", ignoreCase = true)
                    val staged = if (useTier0) library.stageBookImportTier0(path) else library.stageBookImport(path)
                    if (!staged.alreadyExisted) library.setBookShowStandalone(staged.book.id, false)
                    persistImportOwnerIfNew(staged, initiatingAccountKey)
                    enqueueBookForUnifiedUpload(staged.book, initiatingAccountKey)
                    importedIds += staged.book.id
                }
                val (detail, rejected) = library.withCourseDatabase { db ->
                    val addRes = CourseStore.addMaterials(db, courseId, importedIds)
                    val refreshed = CourseAutoAligner.alignLocally(db, courseId)
                    refreshed to (preRejected + addRes.rejectedPastExamTitles)
                }
                val notice = when {
                    rejected.isNotEmpty() -> "已拦截 ${rejected.size} 份历年真题文件；其余 ${detail.materials.size} 份材料已整理。"
                    importedIds.isNotEmpty() && detail.isExam -> "已补充 ${importedIds.size} 份材料并更新本周对齐。"
                    importedIds.isNotEmpty() -> "已添加 ${importedIds.size} 份材料。"
                    else -> null
                }
                mutable.update {
                    it.copy(
                        catalog = library.catalog(),
                        collections = library.collectionSummaries(),
                        activeCourseDetail = detail,
                        courseNotice = notice,
                    )
                }
            }
        } finally {
            onStaged()
        }
    }

    suspend fun updateCourseMaterialAssignment(
        courseId: String,
        bookId: String,
        weekNum: Int?,
        role: CourseMaterialRole,
    ) = action {
        val detail = library.withCourseDatabase { db ->
            CourseStore.updateMaterialAssignment(db, courseId, bookId, weekNum, role)
            CourseStore.loadCourseDetail(db, courseId, library.clock)
        }
        mutable.update {
            it.copy(
                activeCourseDetail = detail,
                collections = library.collectionSummaries(),
                courseNotice = "已锁定材料归属（${weekNum?.let { w -> "第 $w 周" } ?: "未分周"} · ${role.labelZh}），后续重排不会覆盖。",
            )
        }
    }

    suspend fun removeCourseMaterial(courseId: String, bookId: String) = action {
        val detail = library.withCourseDatabase { db ->
            CourseStore.removeMaterialFromCourse(db, courseId, bookId)
            CourseStore.loadCourseDetail(db, courseId, library.clock)
        }
        mutable.update {
            it.copy(
                activeCourseDetail = detail,
                collections = library.collectionSummaries(),
                courseNotice = "已从本课移除该材料（书架中的原文件仍保留）。",
            )
        }
    }

    suspend fun unlinkCourseTopicSource(courseId: String, topicId: String, bookId: String) = action {
        val detail = library.withCourseDatabase { db ->
            CourseStore.unlinkTopicSource(db, courseId, topicId, bookId)
            CourseStore.loadCourseDetail(db, courseId, library.clock)
        }
        mutable.update {
            it.copy(
                activeCourseDetail = detail,
                courseNotice = "已移除该专题下的误挂关联，重新对齐时不会再次挂回。",
            )
        }
    }

    suspend fun renameCourseTopic(courseId: String, topicId: String, newTitle: String) = action {
        val detail = library.withCourseDatabase { db ->
            CourseStore.renameTopic(db, topicId, newTitle)
            CourseStore.loadCourseDetail(db, courseId, library.clock)
        }
        mutable.update { it.copy(activeCourseDetail = detail) }
    }

    suspend fun setCourseTopicStatus(courseId: String, topicId: String, status: CourseTopicStatus) = action {
        val detail = library.withCourseDatabase { db ->
            CourseStore.setTopicExamStatus(db, topicId, status)
            CourseStore.loadCourseDetail(db, courseId, library.clock)
        }
        val msg = if (status == CourseTopicStatus.EXAMINABLE) "已恢复为必考专题并锁定。" else "已移入本课不考内容并锁定。"
        mutable.update { it.copy(activeCourseDetail = detail, courseNotice = msg) }
    }

    suspend fun updateCourseExamProfile(courseId: String, profile: CourseExamProfile) = action {
        val (prevProfile, detail) = library.withCourseDatabase { db ->
            val before = CourseStore.loadCourseDetail(db, courseId, library.clock).examProfile
            CourseStore.updateExamProfile(db, courseId, profile)
            val refreshed = if (before.isExam != profile.isExam) {
                CourseAutoAligner.alignLocally(db, courseId)
                CourseStore.loadCourseDetail(db, courseId, library.clock)
            } else {
                CourseStore.loadCourseDetail(db, courseId, library.clock)
            }
            before to refreshed
        }
        val onlyPromptChanged = prevProfile.isExam == profile.isExam &&
            prevProfile.examDate == profile.examDate &&
            prevProfile.examFormat == profile.examFormat &&
            prevProfile.syllabusPromptState != profile.syllabusPromptState
        val notice = when {
            onlyPromptChanged -> null
            !profile.isExam -> "已切换为普通学习。"
            profile.examDate.isNotBlank() -> "已保存考试日期（${profile.examDate}）。"
            else -> "已设为要考，可随时补考试日或考纲。"
        }
        mutable.update {
            it.copy(
                activeCourseDetail = detail,
                collections = library.collectionSummaries(),
                courseNotice = notice,
            )
        }
    }

    suspend fun realignCourse(courseId: String, explainer: BookExplainer? = null) = action {
        mutable.update { it.copy(courseNotice = null) }
        val existing = library.courseDetail(courseId)
        if (explainer != null) {
            runCatching { explainer.load() }
            if (explainer.identity != null) {
                val req = library.withCourseDatabase { db -> CourseAutoAligner.buildAlignRequest(db, existing) }
                val res = explainer.courseAlign(req)
                if (res.body != null) {
                    val remotePlan = CourseAutoAligner.parseAlignResponse(res.body, existing)
                    if (remotePlan.topics.isNotEmpty()) {
                        val updated = library.withCourseDatabase { db ->
                            CourseStore.applyAlignmentRespectingLocks(db, courseId, remotePlan)
                            CourseStore.loadCourseDetail(db, courseId, library.clock)
                        }
                        mutable.update {
                            it.copy(
                                activeCourseDetail = updated,
                                courseNotice = "已完成课程对齐，所有手动锁定条目已保留。",
                            )
                        }
                        return@action
                    }
                }
            }
        }
        val updated = library.withCourseDatabase { db ->
            CourseAutoAligner.alignLocally(db, courseId)
        }
        mutable.update {
            it.copy(
                activeCourseDetail = updated,
                courseNotice = "已按本地规则重新对齐，所有手动锁定条目已保留。",
            )
        }
    }

    suspend fun refreshCourseTopicPack(courseId: String, topicId: String, explainer: BookExplainer? = null) = action(showBusy = false) {
        val detail = library.courseDetail(courseId)
        val topic = detail.allTopics.firstOrNull { it.topicId == topicId } ?: return@action
        val pagesByBook = library.withCourseDatabase { db ->
            topic.sources.associate { s -> s.bookId to CourseStore.loadPagesForBook(db, s.bookId) }
        }
        var pack: CourseTopicPack? = null
        if (explainer != null) {
            runCatching { explainer.load() }
            if (explainer.identity != null) {
                val req = CourseAutoAligner.buildTopicPackRequest(detail, topic, pagesByBook)
                val res = explainer.courseTopicPack(req)
                if (res.body != null) {
                    pack = CourseAutoAligner.decodeVerifiedTopicPack(res.body, topic, pagesByBook)
                }
            }
        }
        val resolved = pack ?: CourseAutoAligner.buildOfflineTopicPack(detail, topic, pagesByBook)
        library.withCourseDatabase { db ->
            CourseStore.saveTopicPack(db, topicId, resolved, topic.packSourcesHash.orEmpty())
        }
        val refreshed = library.courseDetail(courseId)
        mutable.update { it.copy(activeCourseDetail = refreshed) }
    }

    suspend fun runCourseHandwrittenVision(courseId: String, bookId: String, explainer: BookExplainer?) = action {
        val prepared = CourseVisionCacheStore.prepareHandwrittenPages(library, bookId)
        if (prepared.pendingPages.isEmpty()) {
            val refreshed = library.courseDetail(courseId)
            mutable.update {
                it.copy(
                    activeCourseDetail = refreshed,
                    courseNotice = "已全部命中本地缓存（${prepared.cachedHits.size} 页），无需重复计费。",
                )
            }
            return@action
        }
        if (explainer == null) {
            mutable.update { it.copy(courseNotice = "请先配对学习服务后再识别手写板书页。") }
            return@action
        }
        runCatching { explainer.load() }
        if (explainer.identity == null) {
            mutable.update { it.copy(courseNotice = "请先配对学习服务后再识别手写板书页。") }
            return@action
        }
        val requestJson = CourseVisionCacheStore.buildVisionRequest(prepared)
        val reply = explainer.courseVision(requestJson)
        val body = reply.body
        if (body == null) {
            mutable.update { it.copy(courseNotice = reply.error ?: "视觉识别暂未成功，请稍后重试。") }
            return@action
        }
        val outputs = CourseVisionCacheStore.parseVisionOutputs(body)
        library.withCourseDatabase { db ->
            CourseVisionCacheStore.saveRecognizedPages(db, bookId, outputs, visionTag = "vision-remote")
        }
        val refreshed = library.courseDetail(courseId)
        mutable.update {
            it.copy(
                activeCourseDetail = refreshed,
                courseNotice = "手写页识别完成并已缓存（新识别 ${outputs.size} 页，命中缓存 ${prepared.cachedHits.size} 页）。",
            )
        }
    }

    fun saveCoursePracticeDraft(courseId: String, draft: CourseTopicPracticeDraft) {
        library.withCourseDatabase { db ->
            CourseStore.savePracticeDraft(db, courseId, draft)
        }
        mutable.update { cur ->
            val detail = cur.activeCourseDetail
            if (detail == null || detail.courseId != courseId) cur
            else {
                val updateList: (List<CourseTopicEntry>) -> List<CourseTopicEntry> = { list ->
                    list.map { t -> if (t.topicId == draft.topicId) t.copy(practiceDraft = draft) else t }
                }
                cur.copy(
                    activeCourseDetail = detail.copy(
                        examinableTopics = updateList(detail.examinableTopics),
                        pendingTopics = updateList(detail.pendingTopics),
                        excludedTopics = updateList(detail.excludedTopics),
                    ),
                )
            }
        }
    }
}
