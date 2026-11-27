package com.coach.desktop.books

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.CoachTheme
import com.coach.ui.adaptive.Hinge
import com.coach.desktop.books.exam.CourseMockHost
import com.coach.desktop.books.exam.CourseMockHubPane
import com.coach.desktop.books.exam.LocalCourseMockHost
import com.coach.desktop.books.exam.LocalExamController
import com.coach.desktop.books.exam.rememberLocalExamController
import com.coach.desktop.reader.ReaderSession
import com.coach.desktop.reader.toReaderInput
import com.coach.ui.v7.LocalDeskMetrics
import com.coach.ui.v7.LocalV7ReducedMotion
import com.coach.ui.v7.paperShadow
import com.coach.ui.v7.v7FocusRing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.file.Path

@Composable
internal fun TextbookWorkspace(controller: TextbookController, drafts: AnswerDrafts = rememberAnswerDrafts(controller), exams: LocalExamController = rememberLocalExamController(controller.library), loadOnMount: Boolean = true,
    support: BookLearningSupport? = null,
    answerExplain: AnswerExplain? = null,
    sourceResolver: BookSourceResolver = remember(controller.library) { BookSourceResolver(controller.library) },
    hinge: Hinge? = LocalBookHinge.current,
    onExportNotes: ((Textbook, Int) -> Unit)? = null,
    releasePickedBook: (Path) -> Unit = {}, retryState: ShellRetryState? = null,
    onNewCourse: () -> Unit = {},
    onNewBooklist: () -> Unit = {},
    onOpenCollection: (CollectionSummary) -> Unit = {},
    onPickCourseFiles: (suspend () -> List<Path>)? = null,
    onPickBooklistFiles: (suspend () -> List<Path>)? = null,
    chooseReviewedBlocks: (suspend () -> List<Path>)? = null,
    explainProvider: BookExplainer? = null,
    firstOpenGuide: Boolean = false,
    chooseBook: suspend () -> Path?,
) {
    val explainer = remember(controller.library, explainProvider) { explainProvider ?: BookExplainer(library = controller.library) }
    val collectionQaSessions = remember { mutableStateMapOf<String, CollectionQaSessionState>() }
    var guideCourse by remember { mutableStateOf<String?>(null) }
    var comparisonCourse by remember { mutableStateOf<String?>(null) }
    val aiEnabled = LocalReadingAiExplain.current
    val currentAiEnabled by rememberUpdatedState(aiEnabled)
    val booklistJudge = remember(explainer) {
        RemoteBooklistSemanticJudge(
            explainer = explainer,
            allowRemote = { currentAiEnabled },
        )
    }
    DisposableEffect(controller, booklistJudge, aiEnabled) {
        controller.allowRemoteBooklistAi = { currentAiEnabled }
        val desiredJudge: ConceptSemanticJudge = if (aiEnabled) booklistJudge else HeuristicConceptSemanticJudge
        val existing = controller.conceptSemanticJudge
        if (existing == null || existing === booklistJudge || existing === HeuristicConceptSemanticJudge) {
            controller.conceptSemanticJudge = desiredJudge
        }
        onDispose {
            controller.allowRemoteBooklistAi = { true }
            val installed = controller.conceptSemanticJudge
            if (installed === booklistJudge || installed === HeuristicConceptSemanticJudge) {
                controller.conceptSemanticJudge = null
            }
        }
    }
    val bookStructureProvider = remember(explainer, aiEnabled) {
        BookStructureProvider { _, candidates, bookPages ->
            if (!explainer.loaded) explainer.load()
            if (explainer.identity == null || !aiEnabled) {
                ModelSectionResult.Deferred
            } else {
                explainer.structureUnstructuredBook(candidates, bookPages)
            }
        }
    }
    DisposableEffect(controller, bookStructureProvider) {
        controller.structureProvider = bookStructureProvider
        onDispose {
            if (controller.structureProvider === bookStructureProvider) controller.structureProvider = null
        }
    }
    val uploadClient = remember(explainer) { explainer.unifiedUploadClient() }
    DisposableEffect(controller, uploadClient) {
        controller.unifiedUploadClient = uploadClient
        onDispose {
            if (controller.unifiedUploadClient === uploadClient) {
                controller.unifiedUploadClient = null
                controller.onUploadIdentityChanged(null)
            }
        }
    }
    LaunchedEffect(controller, uploadClient, explainer.identity) {
        val idKey = uploadClient?.activeAccountKey()
        controller.onUploadIdentityChanged(idKey)
    }
    val learningSupport = support ?: remember(controller.library, explainer) {
        PairedBookLearningSupport(controller.library,
            sendLesson = { blocks, pages -> explainer.run { load(); explainLesson(blocks, pages) } },
            send = { block, question -> explainer.run { load(); ask(block, question) } })
    }
    val ui by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var practiceSource by remember { mutableStateOf<PracticeSource?>(null) }
    var returnFocus by remember { mutableStateOf<SourceFocus?>(null) }
    var sourceOrigin by remember { mutableStateOf(SourceFocus.LESSON) }
    var openPairing by remember { mutableStateOf(false) }
    var citationIndex by remember { mutableIntStateOf(0) }
    var readerHasFocus by remember { mutableStateOf(false) }
    var noteReader by remember { mutableStateOf<ReaderSession?>(null) }
    // Both immediate and late restore requests target the page that handles
    // paging keys. Focusing this outer Surface would take those keys away.
    val readerFocus = remember { FocusRequester() }
    // 翻页后恢复阅读器焦点：续体可能在后台线程恢复（控制器动作在 IO 上完成），不能在那里直接动 Compose 焦点；
    // 只发一张"焦点票"，由下面的 LaunchedEffect 在组合所在的 UI 上下文里核对票上的书和出处仍是当前的，才真正请求焦点。
    var readerFocusTicket by remember { mutableStateOf<ReaderFocusTicket?>(null) }
    ReaderFocusTicketEffect(readerFocusTicket, ui) { readerFocus.requestFocus() }
    val lessonSourceFocus = remember { FocusRequester() }
    val practiceSourceFocus = remember { FocusRequester() }
    val lessonIndex = ui.book?.lessonIndex
    val explanationReply = remember(ui.book?.id, lessonIndex, learningSupport) { mutableStateOf<BookLearningReply?>(null) }
    // 答完就讲的话由另一条通道取，重开窗口会重新问一次；它不跟原文页的讲解共用缓存。
    val answerExplainReply = remember(ui.book?.id, lessonIndex, answerExplain) { mutableStateOf<BookLearningReply?>(null) }
    val paired = explainer.identity != null
    LaunchedEffect(paired) {
        if (paired) {
            if ((explanationReply.value as? BookLearningReply.Unavailable)?.trouble?.needsPairing == true)
                explanationReply.value = null
            if ((answerExplainReply.value as? BookLearningReply.Unavailable)?.trouble?.needsPairing == true)
                answerExplainReply.value = null
        }
    }
    val citationFocus = remember(ui.book?.id, lessonIndex) { mutableMapOf<Int, FocusRequester>() }
    val learningScroll = remember(ui.book?.id, ui.book?.lessonIndex) { ScrollState(0) }
    val ownedRetryState = if (retryState == null) remember(controller, releasePickedBook) { ShellRetryState(releasePickedBook) } else null
    if (ownedRetryState != null) {
        DisposableEffect(ownedRetryState) { onDispose { ownedRetryState.releasePending() } }
    }
    val activeRetry = retryState ?: checkNotNull(ownedRetryState)
    LaunchedEffect(ui.importing?.recognizing) {
        if (ui.importing?.recognizing == true) activeRetry.releasePending()
    }
    LaunchedEffect(controller) {
        val current = controller.state.value
        if (loadOnMount && current.catalog.isEmpty() && current.book == null && current.prompt == null) {
            val loadAction: suspend () -> Unit = { controller.load() }
            activeRetry.recordAction(loadAction)
            loadAction()
        }
    }
    LaunchedEffect(ui.screen, ui.source) {
        if (ui.screen == BookScreen.READER && ui.source != null) readerFocus.requestFocus()
    }
    fun run(action: suspend () -> Unit) {
        activeRetry.recordAction(action)
        scope.launch { action() }
    }
    fun returnFromReader() {
        openPairing = false
        if (ui.source != null) {
            // Only newly composed, ready rows may supply focus targets on return.
            if (sourceOrigin == SourceFocus.CITATION) citationFocus.clear()
            returnFocus = sourceOrigin
        }
        run { controller.back() }
    }
    BookWorkspaceBackHandler(ui.prompt != null || ui.screen != BookScreen.HOME) {
        when {
            ui.prompt != null -> run { controller.dismissPrompt() }
            ui.screen == BookScreen.READER -> returnFromReader()
            else -> run { controller.back() }
        }
    }
    fun openLessonSource(pairing: Boolean) {
        openPairing = pairing
        practiceSource = null
        sourceOrigin = SourceFocus.LESSON
        run { controller.source() }
    }
    fun openPracticeSource(practice: Practice) {
        val book = ui.book ?: return
        val lesson = ui.review?.let { review ->
            book.lessons.find { it.id == review.lesson || it.id == review.lesson.substringBefore("#q") }
        } ?: book.lessons.getOrNull(book.lessonIndex) ?: return
        // 本题依据取自整节正文（lesson.text 以 "\n\n" 拼接各正文块），
        // 所以整段查找后再回落到命中的那个块，才能定位到块所在页。
        val body = lesson.blocks.filterNot { it.heading }
        val spans = ArrayList<Triple<SourceBlock, Int, Int>>(body.size)
        var cursor = 0
        for (block in body) {
            if (spans.isNotEmpty()) cursor += 2 // lesson.text 用 "\n\n" 拼接正文块
            spans += Triple(block, cursor, cursor + block.text.length)
            cursor += block.text.length
        }
        val hits = if (practice.reference.isBlank()) emptyList() else Regex(Regex.escape(practice.reference)).findAll(lesson.text).toList()
        val anchor = when (hits.size) {
            1 -> {
                // 依据可能跨多个正文块；只有命中覆盖的块同属一页时，页锚点才是确定的。
                val hit = hits.single()
                val covered = spans.filter { it.third > hit.range.first && it.second <= hit.range.last }
                val pages = covered.map { it.first.page }.distinct()
                when {
                    covered.isEmpty() ->
                        PracticeSource(null, null, "无法确认原句：未找到本题依据所在的位置。")
                    pages.size > 1 ->
                        PracticeSource(null, null, "无法确认原句：本题依据跨越多页，无法定位到单一页面。")
                    else -> PracticeSource(pages.single(), practice.reference, null)
                }
            }
            0 -> PracticeSource(null, null, "无法确认原句：未找到本题依据所在的位置。")
            else -> PracticeSource(null, null, "无法确认原句：本题依据出现多次。")
        }
        val sourcePage = practice.sourcePage?.takeIf { book.page(it) != null }
        practiceSource = if (sourcePage != null && anchor.page != sourcePage) PracticeSource(sourcePage, null, null) else anchor
        sourceOrigin = SourceFocus.PRACTICE
        run { controller.source(); (sourcePage ?: anchor.page)?.let { controller.page(it) } }
    }
    // 外壳（WindowsTextbookHost）已经按系统或 coach.windows.theme 选好明暗；正文沿用同一个选择，
    // 不再让内层按系统重选，否则外壳浅、正文深。
    CoachTheme(com.coach.ui.theme.CoachTheme.colors.dark) {
        Surface(Modifier.fillMaxSize().testTag("windows-textbooks").onPreviewKeyEvent { event ->
            val editing = noteReader?.takeIf { ui.screen == BookScreen.READER && it.noting }
            val noteKey = event.toReaderInput()
            editing != null && noteKey != null && editing.onKey(noteKey.first, noteKey.second)
        }.onKeyEvent { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && ui.screen == BookScreen.READER &&
                ui.prompt == null && !ui.busy) {
                returnFromReader(); true
            } else false
        }.onFocusChanged { readerHasFocus = it.hasFocus }
            .focusable(enabled = ui.screen == BookScreen.READER && ui.source != null),
            color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
            Box(Modifier.fillMaxSize()) {
                Column {
                    ui.importing?.let { progress ->
                        val readingImport = ui.screen == BookScreen.READER && progress.recognizing &&
                            progress.bookId != null && progress.bookId == ui.book?.id
                        val handledElsewhere = (ui.screen == BookScreen.HOME && progress.recognizing) ||
                            (ui.screen == BookScreen.READER && !readingImport)
                        if (readingImport && ui.book?.page(ui.page)?.ocrPending == true) {
                            Text("这一页还在识别，可以先看原页。", Modifier.padding(horizontal = 16.dp).testTag("page-ocr-pending"))
                        }
                        if (!handledElsewhere) {
                            BookImportProgressBar(
                                progress = progress,
                                onCancel = controller::cancelImport,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                    .testTag("book-import-progress"),
                            )
                        }
                    }
                    if (ui.importing == null) {
                        ui.indexing?.let { indexing ->
                            BookImportProgressBar(
                                progress = BookImportProgress(indexing.page, indexing.total),
                                onCancel = controller::cancelImport,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                    .testTag("book-import-progress"),
                            )
                        }
                    }
                    val damagedPages = ui.book?.damagedPages.orEmpty()
                    if (ui.screen == BookScreen.READER && damagedPages.isNotEmpty()) {
                        Text(
                            BookImportMessages.damagedPages(damagedPages),
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("damaged-pages"),
                            style = MaterialTheme.typography.bodySmall,
                            color = v7Alert(),
                        )
                    }
                    ui.error?.let { error ->
                        ShellErrorRow(
                            message = error,
                            busy = ui.busy || ui.importing != null,
                            onRetry = {
                                scope.launch {
                                    if (drafts.flush() && controller.state.value.error != null) {
                                        (activeRetry.lastAction ?: { controller.load() })()
                                    }
                                }
                            },
                            textTag = "book-error",
                            retryTag = "book-error-retry",
                            surfaceCard = true,
                            modifier = Modifier
                                .padding(horizontal = CoachV7.Space.s6, vertical = CoachV7.Space.s2)
                                .testTag("book-error-banner"),
                        )
                    }
                    if (ui.passwordPromptPath != null) {
                        var pdfPassword by remember(ui.passwordPromptPath, ui.error) { mutableStateOf("") }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = pdfPassword,
                                onValueChange = { pdfPassword = it },
                                modifier = Modifier.weight(1f).testTag("pdf-password"),
                                label = { Text("打开密码") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                            Button(
                                onClick = { controller.submitPdfPassword(pdfPassword) },
                                enabled = pdfPassword.isNotEmpty() && ui.importing == null,
                                modifier = Modifier.testTag("pdf-password-submit"),
                            ) { Text("导入") }
                        }
                    }
                    val book = ui.book
                    val existingGradingHost = LocalPracticeGradingHost.current
                    val activeGradingHost = remember(existingGradingHost, explainer, controller.library) {
                        existingGradingHost ?: PracticeGradingHost(
                            library = controller.library,
                            gradeAndDiagnose = { req ->
                                if (!explainer.loaded) explainer.load()
                                explainer.gradeAndDiagnosePractice(req)
                            },
                        )
                    }
                    val existingCourseMockHost = LocalCourseMockHost.current
                    val activeCourseMockHost = remember(existingCourseMockHost, explainer, activeGradingHost) {
                        existingCourseMockHost ?: CourseMockHost(
                            generateCourseMock = { req ->
                                if (!explainer.loaded) explainer.load()
                                explainer.generateCourseMock(req)
                            },
                            gradeAndDiagnose = activeGradingHost.gradeAndDiagnose,
                        )
                    }
                    val examUi by exams.state.collectAsState()
                    CompositionLocalProvider(
                        LocalPracticeGradingHost provides activeGradingHost,
                        LocalCourseMockHost provides activeCourseMockHost,
                    ) {
                    when {
                        !ui.initialized && ui.screen == BookScreen.HOME && book == null && ui.catalog.isEmpty() -> {
                            // 首次从 SQLite 载入时只给一组安静的呼吸点，绝不闪空态（UX-3）。
                            Box(Modifier.fillMaxSize().testTag("library-loading"), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                com.coach.ui.v7.CoachLoadingDots(label = "正在打开书架…")
                            }
                        }
                        ui.screen == BookScreen.BOOKLIST && ui.activeBooklistId != null -> {
                            val activeBooklist = ui.collections.firstOrNull { it.record.id == ui.activeBooklistId }
                            if (activeBooklist == null) {
                                LaunchedEffect(ui.activeBooklistId) { controller.back() }
                            } else {
                                BooklistScreen(
                                    collection = activeBooklist,
                                    catalog = ui.catalog,
                                    library = controller.library,
                                    aligning = ui.booklistAligning,
                                    busy = ui.busy,
                                    revision = ui.booklistRevision,
                                    onBack = { run { controller.back() } },
                                    onOpenBook = { bookId, targetPage -> run { controller.openBook(bookId, targetPage) } },
                                    onAddBooks = { existingIds, newFiles ->
                                        controller.addBooksToBooklist(
                                            collectionId = activeBooklist.record.id,
                                            existingBookIds = existingIds,
                                            newFiles = newFiles,
                                            onStaged = { newFiles.forEach(releasePickedBook) },
                                        )
                                    },
                                    onPickFiles = {
                                        if (onPickBooklistFiles != null) onPickBooklistFiles()
                                        else chooseBook()?.let(::listOf).orEmpty()
                                    },
                                    onRealign = { controller.launchBooklistAlignment(activeBooklist.record.id) },
                                    onMuteConcept = { conceptId, muted -> controller.setConceptMuted(conceptId, muted) },
                                    onSaveSharedNote = { conceptId, note -> controller.saveConceptSharedNote(conceptId, note) },
                                )
                            }
                        }
                        ui.screen == BookScreen.HOME || (book == null && ui.screen != BookScreen.REVIEWS && ui.screen != BookScreen.REVIEWING && ui.screen != BookScreen.COURSE && ui.screen != BookScreen.COURSE_MOCK && ui.screen != BookScreen.BOOKLIST) -> LibraryHome(
                            ui = ui,
                            onImport = {
                                scope.launch {
                                    chooseBook()?.let { picked ->
                                        activeRetry.importPicked(controller, picked)
                                    }
                                }
                            },
                            onSelect = { run { controller.select(it) } },
                            onRead = { run { controller.read() } },
                            onLearn = { run { controller.read() } },
                            onPath = { path -> run { if (drafts.flush()) controller.learn(path) } },
                            onCancelImport = controller::cancelImport,
                            hinge = hinge,
                            onExportNotes = onExportNotes,
                            onOpenBook = { id -> run { controller.openBook(id) } },
                            onOpenAskDraft = { draft -> run { controller.openBook(draft.book, draft.page, askDraft = draft) } },
                            onOpenReviews = { run { if (drafts.flush()) controller.showReviews() } },
                            onNewCourse = onNewCourse,
                            onNewBooklist = onNewBooklist,
                            onOpenCollection = onOpenCollection,
                            onMultiDropAction = { action -> controller.resolveMultiDrop(action) },
                            onDismissMultiDrop = controller::dismissMultiDrop,
                            onRetryUpload = { bookId -> controller.retryBookUpload(bookId) },
                            library = controller.library,
                            firstOpenGuide = firstOpenGuide,
                        )
                        ui.screen == BookScreen.COURSE && ui.activeCourseDetail != null -> {
                            val detail = checkNotNull(ui.activeCourseDetail)
                            if (comparisonCourse == detail.courseId) {
                                LiteratureComparisonPane(detail, controller.library, explainer,
                                    onBack = { comparisonCourse = null }) { source ->
                                    run {
                                        controller.jumpToMockSourcePage(
                                            detail.courseId,
                                            source.book.id,
                                            source.anchor.pdfPage,
                                            source.anchor.quoteAnchor,
                                            source.anchor,
                                        )
                                    }
                                }
                            } else if (guideCourse == detail.courseId) {
                                LiteratureGuidePane(detail, controller.library, explainer,
                                    onBack = { guideCourse = null }) { source ->
                                    run { controller.jumpToLiteraturePage(detail.courseId, source.book.id, source.anchor.pdfPage) }
                                }
                            } else CourseScreen(
                                detail = detail,
                                busy = ui.busy || ui.courseBusy,
                                notice = ui.courseNotice,
                                onOpenLiteratureGuide = if (!originalPdfTouchEnabled) {
                                    { comparisonCourse = null; guideCourse = detail.courseId }
                                } else null,
                                onOpenLiteratureComparison = if (!originalPdfTouchEnabled) {
                                    { guideCourse = null; comparisonCourse = detail.courseId }
                                } else null,
                                onBack = { run { controller.back() } },
                                onOpenTopic = { topic -> run { controller.openCourseTopic(detail.courseId, topic.topicId, explainer) } },
                                onAddMaterials = {
                                    scope.launch {
                                        val picked = if (onPickCourseFiles != null) {
                                            onPickCourseFiles()
                                        } else {
                                            listOfNotNull(chooseBook())
                                        }
                                        if (picked.isNotEmpty()) {
                                            controller.addMaterialsToCourse(detail.courseId, picked) {
                                                picked.forEach(releasePickedBook)
                                            }
                                        }
                                    }
                                },
                                onRealign = { run { controller.realignCourse(detail.courseId, explainer) } },
                                onGenerateExamDrills = { run { controller.generateExamPointDrills(detail.courseId, explainer) } },
                                drillProgress = ui.examDrillProgress?.takeIf { it.courseId == detail.courseId },
                                onUpdateMaterialAssignment = { bookId, weekNum, role ->
                                    run { controller.updateCourseMaterialAssignment(detail.courseId, bookId, weekNum, role) }
                                },
                                onRemoveMaterial = { bookId ->
                                    run { controller.removeCourseMaterial(detail.courseId, bookId) }
                                },
                                onRunHandwrittenVision = { bookId ->
                                    run { controller.runCourseHandwrittenVision(detail.courseId, bookId, explainer) }
                                },
                                onRenameTopic = { topicId, newTitle ->
                                    run { controller.renameCourseTopic(detail.courseId, topicId, newTitle) }
                                },
                                onUnlinkTopicSource = { topicId, bookId ->
                                    run { controller.unlinkCourseTopicSource(detail.courseId, topicId, bookId) }
                                },
                                onSetTopicStatus = { topicId, status ->
                                    run { controller.setCourseTopicStatus(detail.courseId, topicId, status) }
                                },
                                onUpdateExamProfile = { profile ->
                                    run { controller.updateCourseExamProfile(detail.courseId, profile) }
                                },
                                onDismissNotice = controller::dismissCourseNotice,
                                intake = ui.courseIntake?.takeIf { it.courseId == detail.courseId },
                                onChangeIntakeType = { key, role -> run { controller.changeCourseIntakeType(detail.courseId, key, role) } },
                                onDismissIntake = controller::dismissCourseIntake,
                                hasActiveMockExam = remember(detail.courseId, examUi.active, examUi.attempt) {
                                    exams.hasActiveCourseMock(detail.courseId)
                                },
                                onOpenMockExam = { run { controller.openCourseMock(detail.courseId) } },
                            )
                        }
                        ui.screen == BookScreen.COURSE_MOCK && ui.activeCourseDetail != null -> {
                            val detail = checkNotNull(ui.activeCourseDetail)
                            CourseMockHubPane(
                                detail = detail,
                                controller = exams,
                                library = controller.library,
                                onBackToCourse = { run { controller.back() } },
                                onJumpToBookPage = { bookId, pg, quote ->
                                    run { controller.jumpToMockSourcePage(detail.courseId, bookId, pg, quote) }
                                },
                                onMasteryUpdated = { cid -> controller.refreshActiveCourse(cid) },
                                onAddMaterials = {
                                    scope.launch {
                                        val picked = if (onPickCourseFiles != null) {
                                            onPickCourseFiles()
                                        } else {
                                            listOfNotNull(chooseBook())
                                        }
                                        if (picked.isNotEmpty()) {
                                            controller.addMaterialsToCourse(detail.courseId, picked) {
                                                picked.forEach(releasePickedBook)
                                            }
                                        }
                                    }
                                },
                                onSkipSyllabusPrompt = {
                                    run {
                                        controller.updateCourseExamProfile(
                                            detail.courseId,
                                            detail.examProfile.copy(syllabusPromptState = CourseSyllabusPromptState.SKIPPED),
                                        )
                                    }
                                },
                            )
                        }
                        ui.screen == BookScreen.REVIEWS -> LocalBookReviews(ui,
                            onBack = { run { controller.back() } },
                            onOpen = { entry -> run { if (drafts.flush()) controller.openReview(entry) } },
                            onOpenMemory = { item -> run { if (drafts.flush()) controller.openMemoryReview(item) } })
                        ui.screen == BookScreen.REVIEWING && ui.memoryReview != null -> {
                            val item = checkNotNull(ui.memoryReview)
                            val at = ui.todayQueue.lineup.indexOfFirst {
                                it is ReviewLine.Memory && it.item.kind == item.kind && it.item.objectId == item.objectId && it.item.bookId == item.bookId
                            }
                            MemoryReviewCard(
                                item = item,
                                enabled = !ui.busy,
                                position = at.takeIf { it >= 0 }?.let { it + 1 to ui.todayQueue.lineup.size },
                                onBack = { run { controller.back() } },
                                onSource = { run { controller.showMemorySentence() } },
                                onRate = { remembered, note -> run { controller.rateMemoryReview(remembered, note) } },
                            )
                        }
                        ui.screen == BookScreen.REVIEWING && ui.review != null -> {
                            val storedReview = checkNotNull(ui.review)
                            val review = storedReview.copy(answer = drafts.currentReview(storedReview))
                            LocalBookReviewQuestion(
                                review = review,
                                enabled = !ui.busy,
                                sourceFocus = practiceSourceFocus,
                                returnFocus = returnFocus,
                                onFocusRestored = { returnFocus = null },
                                onBack = { run { controller.back() } },
                                onSource = { openPracticeSource(review.practice) },
                                onAnswer = { drafts.saveReview(review, it) },
                                position = ui.todayQueue.lineup.indexOfFirst {
                                    it is ReviewLine.Question && it.review.book == review.book && it.review.lesson == review.lesson && it.review.mode == review.mode
                                }.takeIf { it >= 0 }?.let { it + 1 to ui.todayQueue.lineup.size },
                                deferred = ui.todayQueue.deferred,
                                onNext = { run { if (drafts.flush()) controller.nextReview() } },
                                onRate = { rating, ans ->
                                    run {
                                        drafts.flush()
                                        controller.rateReview(rating, ans)
                                    }
                                },
                            )
                        }
                        ui.screen == BookScreen.READER -> {
                            val activeCourse = ui.activeCourseDetail
                            val activeTopic = activeCourse?.allTopics?.firstOrNull { it.topicId == ui.activeCourseTopicId }
                            val courseContext = if (activeCourse != null && activeTopic != null) {
                                CourseReaderContext(
                                    courseId = activeCourse.courseId,
                                    courseTitle = activeCourse.title,
                                    topicId = activeTopic.topicId,
                                    topicTitle = activeTopic.title,
                                    weekLabel = activeTopic.weekLabel,
                                    authorityNotice = activeTopic.pack?.authorityNotice.orEmpty(),
                                    examProfile = activeCourse.examProfile,
                                    orderedSources = activeTopic.sources,
                                    pack = activeTopic.pack,
                                    resumeDraft = activeTopic.practiceDraft,
                                )
                            } else null
                            TextbookReader(ui, controller.library, learningSupport,
                                practiceSource = practiceSource, pageFocus = readerFocus, explainProvider = explainer,
                                openPairing = openPairing,
                                resumeDraft = ui.activePracticeDraft?.takeIf { it.bookId == book?.id },
                                onPracticeDraftChange = { draft, pagesTotal ->
                                    controller.saveChapterPracticeDraft(draft, pagesTotal)
                                },
                                courseContext = courseContext,
                                pendingCourseJump = ui.pendingCourseJump,
                                onConsumePendingCourseJump = controller::consumePendingCourseJump,
                                onSwitchCourseSource = { targetBookId, targetPage ->
                                    run { controller.switchCourseSource(targetBookId, targetPage) }
                                },
                                onSaveCoursePracticeDraft = { draft ->
                                    activeCourse?.let { controller.saveCoursePracticeDraft(it.courseId, draft) }
                                },
                                onRefreshCoursePack = {
                                    if (activeCourse != null && activeTopic != null) {
                                        scope.launch { controller.refreshCourseTopicPack(activeCourse.courseId, activeTopic.topicId, explainer) }
                                    }
                                },
                                onJumpCourseCitation = { citation, origin ->
                                    run { controller.jumpToCourseCitation(citation, origin) }
                                },
                                onEditStructure = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
                                    controller::editStructure else null,
                                onCancelImport = controller::cancelImport,
                                onReorganizeSections = { run { controller.reorganizeSections() } },
                                onSaveSectionQuiz = { lesson, qIdx, practice, answer ->
                                    run { controller.recordSectionQuizAnswer(lesson, qIdx, practice, answer) }
                                },
                                onSaveSectionPretest = { lesson, qIdx, practice, answer ->
                                    run { controller.recordSectionQuizAnswer(lesson, qIdx, practice, answer, isPretest = true) }
                                },
                                onOpenSectionMainline = { secIdx ->
                                    run { if (drafts.flush()) controller.openSectionPractice(secIdx) }
                                },
                                onEnsureUnstructuredSectioned = {
                                    ui.book?.let { target -> run { controller.ensureUnstructuredBookSectioned(target.id, bookStructureProvider) } }
                                },
                                onRecordPageDwell = { bId, nodeId, p, total, dwellMs ->
                                    controller.recordPageDwell(bId, nodeId, p, total, dwellMs)
                                },
                                onToggleChapterRead = { bId, nodeId, total, read ->
                                    controller.setChapterManualReadState(bId, nodeId, total, read)
                                },
                                onMuteConcept = { conceptId ->
                                    controller.setConceptMuted(conceptId, true)
                                },
                                onOpenCrossBookPage = { targetBookId, targetPage, quote, offset ->
                                    run { controller.openBookAtQuote(targetBookId, targetPage, quote.orEmpty(), offset) }
                                },
                                collectionQaSessions = collectionQaSessions,
                                onUpdateCollectionQaSession = { colId, next -> collectionQaSessions[colId] = next },
                                onJumpCollectionCitation = { citation, origin ->
                                    run { controller.jumpToCollectionCitation(citation, origin) }
                                },
                                onConsumeKeepAskTabOpen = controller::consumeKeepAskTabOpen,
                                hinge = hinge,
                                onExportNotes = onExportNotes,
                                onNoteSession = { noteReader = it },
                                onLoadPdfVisuals = if (controller.library.lazyPdfExtras) controller::ensureVisualsForPages else null,
                                onImportReviewedBlocks = chooseReviewedBlocks?.let { choose ->
                                    { bookId -> run {
                                        val files = choose()
                                        if (files.isNotEmpty()) controller.importReviewedBlocks(bookId, files)
                                    } }
                                },
                                onBrowsePage = { page ->
                                    run { controller.page(page, commitProgress = false) }
                                },
                                onPage = { page ->
                                    val restoreFocus = ui.source != null && readerHasFocus
                                    val startBookId = ui.book?.id; val startSource = ui.source
                                    run {
                                        controller.page(page)
                                        if (restoreFocus) awaitReaderFocusRestore(startBookId, startSource, { readerHasFocus }, { controller.state.value }) {
                                            readerFocusTicket = it
                                        }
                                    }
                                }, onBack = { returnFromReader() },
                                onLearn = { run { if (drafts.flush()) controller.learn(LearningPath.QUESTION_FIRST) } })
                        }
                        else -> {
                            val activeBook = checkNotNull(book)
                            val lesson = activeBook.lessons.getOrNull(activeBook.lessonIndex)
                            val displayed = if (lesson == null) ui else ui.copy(
                                answer = drafts.current(activeBook.id, lesson.id, activeBook.questionMode, ui.answer))
                            TextbookLearning(displayed, learningSupport, learningScroll, lessonSourceFocus, practiceSourceFocus, returnFocus,
                            explanationReply, answerExplain, answerExplainReply, sourceResolver, citationFocus, citationIndex,
                            library = controller.library,
                            onFocusRestored = { returnFocus = null }, onBack = { run { controller.back() } },
                            onSource = { openLessonSource(pairing = false) }, onPair = { openLessonSource(pairing = true) },
                            onCitation = { index, target ->
                                val current = controller.state.value
                                if (current.screen == BookScreen.LEARNING && current.book?.id == target.book &&
                                    current.book.lessons.getOrNull(current.book.lessonIndex)?.id == lesson?.id && lesson != null) {
                                    citationIndex = index
                                    sourceOrigin = SourceFocus.CITATION
                                    practiceSource = PracticeSource(target.pdfPage, target.quote, null, target)
                                    run { controller.citationSource(target.book, lesson.id, target.pdfPage) }
                                }
                            },
                            onCitationRefused = {
                                val current = controller.state.value
                                if (current.screen == BookScreen.LEARNING && current.book?.id == activeBook.id &&
                                    current.book.lessons.getOrNull(current.book.lessonIndex)?.id == lesson?.id) {
                                    runCatching { lessonSourceFocus.requestFocus() }
                                }
                            },
                            onPracticeSource = { openPracticeSource(it) }, onLesson = { run { drafts.flush(); controller.lesson(it) } },
                            onQuestionMode = { selected -> run { controller.mode(selected) } }, onAnswer = { answer ->
                                ui.book?.let { current ->
                                    current.lessons.getOrNull(current.lessonIndex)?.let { lesson ->
                                        drafts.save(current.id, lesson.id, current.questionMode, answer)
                                    }
                                }
                            }, onReviews = { run { if (drafts.flush()) controller.showReviews() } },
                            onQuestions = { run { controller.beginQuestions() } },
                            onRecapContinue = { run { controller.recapContinue() } },
                            hinge = hinge)
                        }
                    }
                    }
                }
                // Saving a reading bookmark must not resize the page being paginated.
                // Overlay the V7 paper hairline so the desk's bounds stay intact.
                if (ui.busy) {
                    WorkspaceBusyBar(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .testTag("workspace-busy-bar"),
                    )
                }
                if (!LocalImportNoticeHandled.current) {
                    BookImportToast(ui.importNotice, controller::dismissImportNotice,
                        Modifier.align(androidx.compose.ui.Alignment.TopCenter))
                }
            }
        }
    }
}

internal val LocalImportNoticeHandled = staticCompositionLocalOf { false }
internal enum class SourceFocus { LESSON, PRACTICE, CITATION }

/**
 * 记住可恢复外壳操作，使点击「再试一次」重放真实失败的操作；
 * 当所选教材因可恢复错误（如 SQLITE_BUSY）导入失败时保留原文件，待重试成功、进入后台识别、
 * 出现非可恢复错误/取消、切走其他动作或离开界面后再释放。
 */
internal class ShellRetryState(private val releasePickedBook: (Path) -> Unit) {
    var lastAction: (suspend () -> Unit)? by mutableStateOf(null)
        private set
    private var pendingImport: Path? = null
    private var importGeneration: Long = 0L

    fun releasePending(keep: Path? = null) {
        val previous = pendingImport
        if (previous != null && previous != keep) {
            pendingImport = null
            releasePickedBook(previous)
        }
    }

    fun recordAction(action: suspend () -> Unit) {
        releasePending()
        importGeneration++
        lastAction = action
    }

    suspend fun importPicked(controller: TextbookController, picked: Path) {
        releasePending(keep = picked)
        val generation = ++importGeneration
        pendingImport = picked
        val retryAction: suspend () -> Unit = { importPicked(controller, picked) }
        lastAction = retryAction
        val job = controller.launchImport(picked)
        try {
            job.join()
        } finally {
            val error = controller.state.value.error
            val keepForRetry = error != null && classifyShellError(error) == ShellErrorKind.RECOVERABLE
            if (importGeneration == generation) {
                if (!keepForRetry && pendingImport == picked) {
                    pendingImport = null
                    releasePickedBook(picked)
                }
                if (!keepForRetry && error == null && lastAction === retryAction) {
                    lastAction = null
                }
            }
        }
    }
}

/**
 * V7 纸面顶部的加载细条：2dp 线槽（borderSubtle）配松绿（v7Pine()）滑动条；
 * 减弱动态开启时（LocalV7ReducedMotion）只画静止的居中一段，不起无限动画。
 */
@Composable
internal fun WorkspaceBusyBar(modifier: Modifier = Modifier) {
    val colors = com.coach.ui.theme.CoachTheme.colors
    val pine = v7Pine()
    val track = colors.borderSubtle
    val reduced = LocalV7ReducedMotion.current
    val baseModifier = modifier
        .fillMaxWidth()
        .height(2.dp)
        .semantics {
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            stateDescription = if (reduced) "static" else "animated"
        }
    if (reduced) {
        Canvas(baseModifier) {
            drawRect(color = track)
            val width = size.width * 0.40f
            drawRect(color = pine, topLeft = Offset(size.width * 0.30f, 0f), size = Size(width, size.height))
        }
    } else {
        val transition = rememberInfiniteTransition(label = "workspace-busy")
        val head by transition.animateFloat(
            initialValue = -0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "workspace-busy-head",
        )
        Canvas(baseModifier) {
            clipRect {
                drawRect(color = track)
                val segWidth = size.width * 0.35f
                drawRect(color = pine, topLeft = Offset(head * size.width, 0f), size = Size(segWidth, size.height))
            }
        }
    }
}

internal enum class ShellErrorKind {
    /** 能自己好的（断连、超时、暂时读不到/存不上等）：给「再试一次」和一句灰字。 */
    RECOVERABLE,
    /** 需要用户处理或找人的（额度用完、连接停用/撤销、权限/文件/格式问题等）：用朱色写清下一步或找谁，不给误导性重试。 */
    USER_ACTION,
    /** 用户主动取消一类的轻提示：灰字，不给重试。 */
    NOTICE,
}

private val userActionKeywords = listOf(
    "额度", "停用", "撤销", "配对", "重新登录", "权限", "未授权",
    "另一个窗口", "计时已结束", "无法确认这次作答",
    "限制了文字提取", "请使用", "以内", "超过", "过多", "换用", "损坏",
    "密码保护", "不是 PDF",
    "请一次拖入", "空文件", "不能直接导入", "超出", "没有这一节", "还没有可用练习",
    "没有可提取", "已不在书架", "先恢复或交卷", "还在准备中", "不能覆盖", "可以计时",
    "快照校验失败", "找不到这本教材",
)

internal fun classifyShellError(message: String): ShellErrorKind {
    val trimmed = message.trim()
    if ("已取消" in trimmed) return ShellErrorKind.NOTICE
    if (userActionKeywords.any { it in trimmed }) return ShellErrorKind.USER_ACTION
    return ShellErrorKind.RECOVERABLE
}

internal fun shellErrorMessage(raw: String, kind: ShellErrorKind): String {
    val text = raw.trim()
    if (text.isBlank() || !ReadableText.readsAsChinese(text)) {
        return when (kind) {
            ShellErrorKind.RECOVERABLE -> "暂时打不开，请稍后再试一次。"
            ShellErrorKind.USER_ACTION -> "需要处理后才能继续，请检查当前设置或联系给你配对码的人。"
            ShellErrorKind.NOTICE -> "操作已取消。"
        }
    }
    if (kind == ShellErrorKind.USER_ACTION) {
        if ("额度" in text && "找" !in text && "联系" !in text && "配对码" !in text) {
            return "$text 如需继续，请联系给你配对码的人。"
        }
        if (("停用" in text || "撤销" in text) && "找" !in text && "联系" !in text && "登录" !in text && "配对" !in text) {
            return "$text 请重新登录，或找给你配对码的人生成新码。"
        }
    }
    return text
}

/**
 * 窗口外壳里的报错行（对齐 BookExplain.kt E3 约定）：
 * - 能自己好的：中性灰字（fgSecondary）+ 中性纸面按钮「再试一次」；
 * - 需要用户动手或找人的：朱色（v7Alert()）写清下一步或找谁，不给误导性重试；
 * - 取消类轻提示：灰字，不给重试。
 */
@Composable
internal fun ShellErrorRow(
    message: String,
    busy: Boolean,
    onRetry: (() -> Unit)?,
    textTag: String,
    retryTag: String,
    modifier: Modifier = Modifier,
    surfaceCard: Boolean = false,
) {
    val colors = com.coach.ui.theme.CoachTheme.colors
    val kind = classifyShellError(message)
    val shown = shellErrorMessage(message, kind)
    val textColor = if (kind == ShellErrorKind.USER_ACTION) v7Alert() else colors.fgSecondary
    val row: @Composable (Modifier) -> Unit = { rowModifier ->
        Row(
            rowModifier.fillMaxWidth().heightIn(min = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (kind == ShellErrorKind.RECOVERABLE && onRetry != null) {
                ShellQuietButton(
                    label = "再试一次",
                    enabled = !busy,
                    modifier = Modifier.testTag(retryTag),
                    onClick = onRetry,
                )
            }
            Text(
                shown,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                modifier = Modifier.weight(1f).testTag(textTag),
            )
        }
    }
    if (surfaceCard) {
        val shape = RoundedCornerShape(CoachV7.Radius.btn)
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            row(
                Modifier
                    .widthIn(max = 1000.dp)
                    .clip(shape)
                    .background(colors.surface1)
                    .border(1.dp, colors.borderSubtle, shape)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    } else {
        row(modifier)
    }
}

/** 中性悬停底的纸面按钮（「再试一次」用中性灰悬停，不套朱色淡底，对齐 BookExplain.QuietButton）。 */
@Composable
private fun ShellQuietButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = com.coach.ui.theme.CoachTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    val base = if (colors.dark) CoachV7.Dark.btnBg else CoachV7.Light.btnBg
    Row(
        modifier
            .v7FocusRing(shape)
            .paperShadow("button", colors.dark)
            .clip(shape)
            .background(if (enabled && hovered) colors.borderSubtle.copy(alpha = .16f).compositeOver(base) else base)
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .defaultMinSize(minHeight = if (LocalDeskMetrics.current.inner) 36.dp else 44.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.fgPrimary.copy(alpha = if (enabled) 1f else .4f),
        )
    }
}
