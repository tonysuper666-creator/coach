package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.desktop.books.AnswerDrafts
import com.coach.desktop.books.AnswerExplain
import com.coach.desktop.books.BookExplainer
import com.coach.desktop.books.BookFormats
import com.coach.desktop.books.BookImportToast
import com.coach.desktop.books.BookScreen
import com.coach.desktop.books.CoachConnectivityStatus
import com.coach.desktop.books.CollectionKind
import com.coach.desktop.books.CollectionSummary
import com.coach.desktop.books.ExplainConsent
import com.coach.desktop.books.HostOfflineBadge
import com.coach.desktop.books.LocalBookHinge
import com.coach.desktop.books.LocalCoachConnectivity
import com.coach.desktop.books.LocalConnectivityPollMs
import com.coach.desktop.books.LocalImportNoticeHandled
import com.coach.desktop.books.LocalReadingFont
import com.coach.desktop.books.ReadingSettings
import com.coach.desktop.books.ReadingSettingsPanel
import com.coach.desktop.books.ShellRetryState
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.TextbookWorkspace
import com.coach.desktop.books.answerExplainWith
import com.coach.desktop.books.bookTabletopSplit
import com.coach.desktop.books.bookWorkspaceThemeOverride
import com.coach.desktop.books.exam.LocalExamController
import com.coach.desktop.books.exam.rememberLocalExamController
import com.coach.desktop.books.originalPdfTouchEnabled
import com.coach.desktop.books.rememberAnswerDrafts
import com.coach.desktop.books.textbookFileDrop
import com.coach.ui.AppTab
import com.coach.ui.CoachTheme
import com.coach.ui.LocalV7TextScale
import com.coach.ui.adaptive.Hinge
import com.coach.ui.v7.DeskTypeControl
import com.coach.ui.v7.LocalDeskSelect
import com.coach.ui.v7.LocalDeskTypeControl
import com.coach.ui.v7.V7TextButton
import coach.design.tokens.CoachV7
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Answering in 做题学 uses the shared learning service & global switch, created once for the whole window.
 */
@Composable
internal fun rememberAnswerExplain(library: TextbookLibrary): AnswerExplain {
    val explainer = remember(library) { BookExplainer(library = library) }
    val consent = remember(library) { ExplainConsent(library.directory) }
    return remember(explainer, consent) { answerExplainWith(explainer, consent) }
}

/**
 * LearningCoach 2.0 单栏总枢纽宿主：
 * - 取消顶层「今天 / 课程 / 书架」三 Tab 切换栏，改为唯一居中单栏首页（<= 720dp）+ 顶栏「LearningCoach」与右上角「搜索 / ＋ / 设置」。
 * - 保留折叠屏铰链分栏与沉浸阅读器。
 */
@Composable
internal fun WindowsTextbookHost(
    controller: TextbookController,
    drafts: AnswerDrafts = rememberAnswerDrafts(controller),
    exams: LocalExamController = rememberLocalExamController(controller.library),
    answerExplain: AnswerExplain = rememberAnswerExplain(controller.library),
    learningSupport: com.coach.desktop.books.BookLearningSupport? = null,
    onPersonalCourse: (() -> Unit)? = null,
    hinge: Hinge? = null,
    onExportNotes: ((com.coach.desktop.books.Textbook, Int) -> Unit)? = null,
    releasePickedBook: (Path) -> Unit = {},
    chooseBooks: (suspend () -> List<Path>)? = null,
    bookExplainer: BookExplainer? = null,
    chooseReviewedBlocks: (suspend () -> List<Path>)? = null,
    firstOpenGuide: Boolean = false,
    chooseBook: suspend () -> Path?,
) {
    var settings by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var creatingKind by remember { mutableStateOf<CollectionKind?>(null) }
    var activeCollection by remember { mutableStateOf<CollectionSummary?>(null) }
    val ui by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val book = ui.book
    val hostExplainer = remember(controller.library, bookExplainer) { bookExplainer ?: BookExplainer(library = controller.library) }
    val pollMs = LocalConnectivityPollMs.current
    val connectivityClient = remember(controller, hostExplainer, hostExplainer.identity) {
        controller.unifiedUploadClient ?: hostExplainer.unifiedUploadClient()
    }
    val registryFlow = remember(connectivityClient, hostExplainer.identity) {
        connectivityClient?.connectivityFlow()
    }
    val registryStatus by (registryFlow?.collectAsState() ?: remember { mutableStateOf(CoachConnectivityStatus.UNKNOWN) })
    var probedConnectivity by remember(connectivityClient) { mutableStateOf(CoachConnectivityStatus.UNKNOWN) }
    val connectivity = if (registryStatus != CoachConnectivityStatus.UNKNOWN) registryStatus else probedConnectivity
    LaunchedEffect(controller, connectivityClient, pollMs) {
        val activeClient = connectivityClient ?: return@LaunchedEffect
        while (true) {
            val probed = runCatching { activeClient.probeReachability() }.getOrDefault(CoachConnectivityStatus.UNKNOWN)
            val wasOffline = probedConnectivity == CoachConnectivityStatus.OFFLINE || registryStatus == CoachConnectivityStatus.OFFLINE
            probedConnectivity = probed
            if (probed == CoachConnectivityStatus.ONLINE &&
                (wasOffline || controller.uploadCoordinator.hasPendingSyncWork())
            ) {
                runCatching { controller.syncBookUploads().join() }
            }
            delay(pollMs)
        }
    }

    val pickMultiple: suspend () -> List<Path> = remember(chooseBooks, chooseBook) {
        {
            if (chooseBooks != null) chooseBooks()
            else chooseBook()?.let(::listOf).orEmpty()
        }
    }

    LaunchedEffect(ui.screen, book?.id) {
        if (ui.screen == BookScreen.READER && book != null) {
            settings = false
            searchOpen = false
        }
    }

    LaunchedEffect(controller) {
        val current = controller.state.value
        if (current.catalog.isEmpty() && current.book == null && current.prompt == null) controller.load()
        while (true) {
            delay(60_000)
            controller.refreshReviews()
        }
    }

    val retryState = remember(controller, releasePickedBook) { ShellRetryState(releasePickedBook) }
    DisposableEffect(retryState) { onDispose { retryState.releasePending() } }

    fun importFromPicker() {
        scope.launch {
            val picked = pickMultiple()
            if (picked.size == 1) {
                retryState.importPicked(controller, picked.single())
            } else if (picked.size > 1) {
                retryState.releasePending()
                controller.launchDroppedBooks(picked) { picked.forEach(releasePickedBook) }
            }
        }
    }

    val isEmptyLibrary = ui.catalog.isEmpty() && ui.collections.none { !it.record.archived }
    var sawEmptyShelf by remember { mutableStateOf(false) }
    var tasteActive by remember { mutableStateOf(false) }
    var tasteDone by remember(controller.library.directory) {
        mutableStateOf(com.coach.desktop.books.FirstOpenStore.isDone(controller.library.directory))
    }
    LaunchedEffect(firstOpenGuide, ui.initialized, isEmptyLibrary, ui.book?.id, ui.screen, tasteDone) {
        if (!firstOpenGuide || tasteDone) {
            tasteActive = false
            return@LaunchedEffect
        }
        if (ui.initialized && isEmptyLibrary && ui.book == null) sawEmptyShelf = true
        tasteActive = sawEmptyShelf && ui.book != null && ui.screen == com.coach.desktop.books.BookScreen.READER
    }
    fun finishFirstTaste() {
        com.coach.desktop.books.FirstOpenStore.markDone(controller.library.directory)
        tasteDone = true
        tasteActive = false
    }
    val deskPage = (book != null && ui.screen != BookScreen.HOME) ||
        ui.screen == BookScreen.COURSE ||
        ui.screen == BookScreen.COURSE_MOCK ||
        ui.screen == BookScreen.BOOKLIST ||
        ui.screen == BookScreen.REVIEWS || ui.screen == BookScreen.REVIEWING
    val incidentPromptHost = LocalIncidentPromptHost.current
    DisposableEffect(incidentPromptHost) {
        incidentPromptHost?.onHostMounted(true)
        onDispose { incidentPromptHost?.onHostMounted(false) }
    }
    val homeIncidentBanner: (@Composable () -> Unit)? = if (!deskPage && incidentPromptHost != null) {
        {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                incidentPromptHost.prompt()
            }
        }
    } else null
    val systemDark = isSystemInDarkTheme()
    val dark = remember { bookWorkspaceThemeOverride() }
    val preferences = remember(controller.library) { ReadingSettings(controller.library.directory) }

    CoachTheme(dark ?: preferences.paper.dark ?: systemDark, initialTextScale = preferences.size.scale) {
        val textScale = LocalV7TextScale.current
        val preferredScale = preferences.size.scale
        SideEffect { if (textScale.value != preferredScale) textScale.value = preferredScale }
        val colors = com.coach.ui.theme.CoachTheme.colors
        CompositionLocalProvider(LocalCoachConnectivity provides connectivity) {
        Surface(
            Modifier.fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.K &&
                        (event.isCtrlPressed || event.isMetaPressed) &&
                        ui.screen != BookScreen.LEARNING &&
                        ui.screen != BookScreen.REVIEWING &&
                        !isEmptyLibrary
                    ) {
                        searchOpen = true
                        true
                    } else false
                }
                .textbookFileDrop { files ->
                    scope.launch {
                        if (drafts.flush() && exams.flush()) {
                            val currentCourseId = ui.activeCourseId
                            if (ui.screen == BookScreen.COURSE && currentCourseId != null && files.isNotEmpty()) {
                                retryState.releasePending()
                                controller.addMaterialsToCourse(currentCourseId, files) {
                                    files.forEach(releasePickedBook)
                                }
                            } else if (files.size == 1) {
                                retryState.importPicked(controller, files.single())
                            } else if (files.size > 1) {
                                retryState.releasePending()
                                controller.launchDroppedBooks(files) { files.forEach(releasePickedBook) }
                            }
                        } else {
                            files.forEach(releasePickedBook)
                        }
                    }
                }
                .testTag("windows-host"),
            color = colors.bgCanvas,
        ) {
            Box(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                var hostOriginDp by remember { mutableStateOf<Pair<Float, Float>?>(null) }
                BoxWithConstraints(
                    Modifier.fillMaxSize().onGloballyPositioned { coords ->
                        val pos = coords.positionInRoot()
                        hostOriginDp = (pos.x / density.density) to (pos.y / density.density)
                    },
                ) {
                    val hostTopDp = hostOriginDp?.second ?: 0f
                    val hostTabletop = if (!deskPage) bookTabletopSplit(maxWidth.value, maxHeight.value, hostTopDp, hinge) else null

                    val deskSelect = remember(controller, scope) {
                        { _: AppTab ->
                            scope.launch { if (controller.state.value.screen != BookScreen.HOME) controller.back() }
                            Unit
                        }
                    }
                    val typeControl: DeskTypeControl? = if (ui.screen == BookScreen.READER && book != null) {
                        val defaultTextView = remember(book.id) {
                            book.page(ui.page)?.ocr == true && !(book.format == "pdf" && originalPdfTouchEnabled)
                        }
                        val panel: DeskTypeControl = { extra ->
                            ReadingSettingsPanel(
                                preferences,
                                isOriginal = {
                                    (book.format == "pdf" || book.format in BookFormats.images) &&
                                        !(controller.library.preferredTextView(book.id) ?: defaultTextView)
                                },
                                extra = extra,
                            )
                        }
                        panel
                    } else null

                    val workspaceContent: @Composable () -> Unit = {
                        TextbookWorkspace(
                            controller = controller,
                            drafts = drafts,
                            exams = exams,
                            loadOnMount = false,
                            answerExplain = answerExplain,
                            support = learningSupport,
                            hinge = hinge,
                            onExportNotes = onExportNotes,
                            releasePickedBook = releasePickedBook,
                            retryState = retryState,
                            onNewCourse = { creatingKind = CollectionKind.COURSE },
                            onNewBooklist = { creatingKind = CollectionKind.BOOKLIST },
                            onOpenCollection = { col ->
                                when (col.record.kind) {
                                    CollectionKind.COURSE -> scope.launch { controller.openCourse(col.record.id) }
                                    CollectionKind.BOOKLIST -> scope.launch { controller.openBooklist(col.record.id) }
                                    else -> activeCollection = col
                                }
                            },
                            onPickCourseFiles = pickMultiple,
                            chooseReviewedBlocks = chooseReviewedBlocks,
                            onPickBooklistFiles = pickMultiple,
                            explainProvider = hostExplainer,
                            chooseBook = chooseBook,
                            firstOpenGuide = firstOpenGuide,
                        )
                    }

                    CompositionLocalProvider(
                        LocalDeskSelect provides deskSelect,
                        LocalDeskTypeControl provides typeControl,
                        LocalReadingFont provides preferences.font,
                        LocalImportNoticeHandled provides true,
                        com.coach.desktop.books.LocalReadingAutoGuide provides preferences.autoGuide,
                        com.coach.desktop.books.LocalReadingSectionSummary provides preferences.sectionSummary,
                        com.coach.desktop.books.LocalReadingAiExplain provides preferences.aiExplain,
                        LocalBookHinge provides hinge,
                        LocalCoachConnectivity provides connectivity,
                    ) {
                        if (hostTabletop != null) {
                            Column(Modifier.fillMaxSize()) {
                                Column(Modifier.height(hostTabletop.topDp.dp).fillMaxWidth().testTag("book-host-tabletop-content")) {
                                    HostTopBar(
                                        showSearch = !isEmptyLibrary,
                                        offline = connectivity == CoachConnectivityStatus.OFFLINE,
                                        onSearch = { searchOpen = true },
                                        onImport = ::importFromPicker,
                                        onNewCourse = { creatingKind = CollectionKind.COURSE },
                                        onNewBooklist = { creatingKind = CollectionKind.BOOKLIST },
                                        onSettings = { settings = true },
                                    )
                                    homeIncidentBanner?.invoke()
                                    Box(Modifier.weight(1f).fillMaxWidth()) { workspaceContent() }
                                }
                                Spacer(Modifier.height(hostTabletop.gapDp.dp).fillMaxWidth().testTag("book-host-tabletop-hinge"))
                                Row(
                                    Modifier.weight(1f).fillMaxWidth().background(colors.bgCanvas)
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                        .testTag("book-host-tabletop-controls"),
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    if (!isEmptyLibrary) {
                                        V7TextButton({ searchOpen = true }, ringInside = true) { Text("搜索") }
                                    }
                                    V7TextButton(::importFromPicker, ringInside = true) { Text("导入文件") }
                                    V7TextButton({ settings = true }, ringInside = true) { Text("设置") }
                                }
                            }
                        } else {
                            // Keep the workspace mounted across lesson and reader navigation. It owns
                            // the verified citation, reply, scroll position and return-focus requester.
                            Column(Modifier.fillMaxSize()) {
                                if (!deskPage) {
                                    HostTopBar(
                                        showSearch = !isEmptyLibrary,
                                        offline = connectivity == CoachConnectivityStatus.OFFLINE,
                                        onSearch = { searchOpen = true },
                                        onImport = ::importFromPicker,
                                        onNewCourse = { creatingKind = CollectionKind.COURSE },
                                        onNewBooklist = { creatingKind = CollectionKind.BOOKLIST },
                                        onSettings = { settings = true },
                                    )
                                    homeIncidentBanner?.invoke()
                                }
                                Box(Modifier.weight(1f).fillMaxWidth()) { workspaceContent() }
                            }
                        }
                    }
                }
                BookImportToast(ui.importNotice, controller::dismissImportNotice, Modifier.align(Alignment.TopCenter))
                val tasteBook = ui.book
                if (tasteActive && tasteBook != null) {
                    com.coach.desktop.books.FirstTastePanel(
                        book = tasteBook,
                        explainer = hostExplainer,
                        connectivity = connectivity,
                        onReadBook = ::finishFirstTaste,
                        onDone = ::finishFirstTaste,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (settings) {
                HostSettings(controller.library.directory, onPersonalCourse, preferences) { settings = false }
            }
            if (searchOpen) {
                HostSearchDialog(
                    library = controller.library,
                    catalog = ui.catalog,
                    collections = ui.collections.filterNot { it.record.archived },
                    onOpenBook = { bookId, page ->
                        searchOpen = false
                        scope.launch { controller.openBook(bookId, page) }
                    },
                    onOpenCollection = { col ->
                        searchOpen = false
                        when (col.record.kind) {
                            CollectionKind.COURSE -> scope.launch { controller.openCourse(col.record.id) }
                            CollectionKind.BOOKLIST -> scope.launch { controller.openBooklist(col.record.id) }
                            else -> activeCollection = col
                        }
                    },
                    onClose = { searchOpen = false },
                    explainer = hostExplainer,
                    preferredCollectionId = ui.activeCourseId ?: ui.activeBooklistId,
                    onJumpCollectionCitation = { cit ->
                        searchOpen = false
                        scope.launch { controller.jumpToCollectionCitation(cit) }
                    },
                )
            }
            creatingKind?.let { kind ->
                CreateCollectionDialog(
                    kind = kind,
                    existingBooks = ui.catalog,
                    onPickFiles = pickMultiple,
                    onConfirm = { title, examDate, existingIds, newFiles ->
                        creatingKind = null
                        controller.createCollection(
                            kind = kind,
                            title = title,
                            examDate = examDate,
                            existingBookIds = existingIds,
                            newFiles = newFiles,
                            onStaged = { newFiles.forEach(releasePickedBook) },
                        )
                    },
                    onClose = { creatingKind = null },
                )
            }
            activeCollection?.let { col ->
                val latest = ui.collections.firstOrNull { it.record.id == col.record.id } ?: col
                CollectionDetailDialog(
                    summary = latest,
                    catalog = ui.catalog,
                    onOpenBook = { bookId ->
                        activeCollection = null
                        scope.launch { controller.openBook(bookId) }
                    },
                    onClose = { activeCollection = null },
                )
            }
        }
        }
    }
}

private val HostBarHeight = 48.dp

/** 单栏总枢纽顶栏：左侧 LearningCoach，右侧仅「搜索（非空时）/ ＋（导入文件…/新建课程/新建书单）/ 设置」。 */
@Composable
private fun HostTopBar(
    showSearch: Boolean,
    offline: Boolean = false,
    onSearch: () -> Unit,
    onImport: () -> Unit,
    onNewCourse: () -> Unit,
    onNewBooklist: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = com.coach.ui.theme.CoachTheme.colors
    var addMenuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(HostBarHeight).padding(horizontal = 20.dp).testTag("host-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "LearningCoach",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = CoachV7.Weight.uiStrong,
            color = colors.fgPrimary,
            modifier = Modifier.testTag("host-brand"),
        )
        if (offline) {
            Spacer(Modifier.width(10.dp))
            HostOfflineBadge()
        }
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showSearch) {
                V7TextButton(
                    onClick = onSearch,
                    modifier = Modifier.testTag("host-search"),
                    ringInside = true,
                ) {
                    Text("搜索", style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
                }
            }
            Box {
                V7TextButton(
                    onClick = { addMenuOpen = true },
                    modifier = Modifier.testTag("host-add"),
                    ringInside = true,
                ) {
                    Text("＋", style = MaterialTheme.typography.titleMedium, color = colors.fgPrimary)
                }
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false },
                    modifier = Modifier.background(colors.surface2).testTag("host-add-menu"),
                ) {
                    DropdownMenuItem(
                        text = { Text("导入文件…", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary) },
                        onClick = { addMenuOpen = false; onImport() },
                        modifier = Modifier.testTag("host-add-import"),
                    )
                    DropdownMenuItem(
                        text = { Text("新建课程", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary) },
                        onClick = { addMenuOpen = false; onNewCourse() },
                        modifier = Modifier.testTag("host-add-course"),
                    )
                    DropdownMenuItem(
                        text = { Text("新建书单", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary) },
                        onClick = { addMenuOpen = false; onNewBooklist() },
                        modifier = Modifier.testTag("host-add-booklist"),
                    )
                }
            }
            V7TextButton(
                onClick = onSettings,
                modifier = Modifier.testTag("open-settings"),
                ringInside = true,
            ) {
                Text("设置", style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
            }
        }
    }
}
