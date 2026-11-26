package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.adaptive.Hinge
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.CoachEmptyState
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.paperShadow
import com.coach.ui.v7.v7FocusRing

internal const val HOME_REVIEW_EMPTY_MESSAGE = "还没有要复习的。做错的题会在这里出现。"

@Composable
internal fun BookImportProgressBar(
    progress: BookImportProgress,
    onCancel: (() -> Unit)? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val pine = colors.actionPrimary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                formatImportProgressLabel(progress),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
                fontFamily = LocalV7Fonts.current.numbers,
            )
            if (onCancel != null) {
                V7TextButton(onCancel, modifier = Modifier.testTag("book-import-cancel"), ringInside = true) {
                    Text(if (compact) "取消" else "取消导入")
                }
            }
        }
        val barModifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).testTag("book-import-progress-bar")
        if (!progress.recognizing && progress.total <= 0) {
            LinearProgressIndicator(modifier = barModifier, color = pine, trackColor = colors.borderSubtle)
        } else {
            val fraction = if (progress.total <= 0) 0f else (progress.page.toFloat() / progress.total).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = barModifier,
                color = pine,
                trackColor = colors.borderSubtle,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
internal fun BookImportToast(notice: BookImportNotice?, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (notice == null) return
    LaunchedEffect(notice.id) {
        kotlinx.coroutines.delay(4_000)
        onDismiss(notice.id)
    }
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    Box(
        modifier
            .padding(top = 56.dp, start = 16.dp, end = 16.dp)
            .paperShadow("card", colors.dark)
            .clip(shape)
            .background(colors.fgPrimary)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("book-import-toast"),
    ) {
        Text(notice.message, style = MaterialTheme.typography.bodySmall, color = colors.surface1)
    }
}

/**
 * LearningCoach 2.0 居中单栏首页（最大宽度 <= 720dp，保留折叠屏铰链分栏）：
 * - 空态：零重复文案、全屏共 6 个零死路可点控件（顶栏 ＋ / 设置 + 唯一主钮「导入文件」+ 3 张可点引导卡）。
 * - 日常首页：全屏仅顶部焦点卡保留唯一实底 PrimaryButton（继续），下方依次为条件今日复习卡、课程、阅读（书单/单书）。
 */
@Composable
internal fun LibraryHome(
    ui: LibraryUi,
    onImport: () -> Unit,
    onSelect: (String) -> Unit,
    onRead: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onLearn: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onPath: (LearningPath) -> Unit = {},
    onCancelImport: () -> Unit = {},
    hinge: Hinge? = LocalBookHinge.current,
    @Suppress("UNUSED_PARAMETER") onExportNotes: ((Textbook, Int) -> Unit)? = null,
    onOpenBook: (String) -> Unit = onSelect,
    onOpenReviews: () -> Unit = {},
    onOpenAskDraft: (BookAskDraft) -> Unit = {},
    onNewCourse: () -> Unit = {},
    onNewBooklist: () -> Unit = {},
    onOpenCollection: (CollectionSummary) -> Unit = {},
    onMultiDropAction: (MultiDropAction) -> Unit = {},
    onDismissMultiDrop: () -> Unit = {},
    onRetryUpload: (String) -> Unit = {},
    library: TextbookLibrary? = null,
) {
    val colors = CoachTheme.colors
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val showToday = library?.todayCardEnabled ?: windowsToday
    val rightScroll = rememberScrollState()
    val density = LocalDensity.current
    var sheetOriginLeftDp by remember { mutableStateOf<Float?>(null) }
    val cloudCoordinator = remember(library) { library?.let(UnifiedBookCloudBridge::forLibrary) }
    val remoteBooks by (cloudCoordinator?.remoteShelfBooks?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val restoringShelfBookId by (cloudCoordinator?.restoringShelfBookId?.collectAsState() ?: remember { mutableStateOf<String?>(null) })
    val restoreProgressPct by (cloudCoordinator?.restoreProgressPct?.collectAsState() ?: remember { mutableStateOf(0) })
    val restoreError by (cloudCoordinator?.restoreError?.collectAsState() ?: remember { mutableStateOf<String?>(null) })
    val hasActiveServerJobs = remember(ui.uploadStatuses) {
        ui.uploadStatuses.values.any {
            it.state in setOf(BookUploadState.RECEIVED, BookUploadState.QUEUED, BookUploadState.PROCESSING)
        }
    }
    LaunchedEffect(cloudCoordinator, hasActiveServerJobs) {
        if (cloudCoordinator != null && hasActiveServerJobs) {
            while (true) {
                kotlinx.coroutines.delay(2_000L)
                runCatching { cloudCoordinator.syncOnce() }
            }
        }
    }
    val activeCollections = remember(ui.collections) { ui.collections.filterNot { it.record.archived } }
    val courses = remember(activeCollections) { activeCollections.filter { it.record.kind == CollectionKind.COURSE } }
    val booklists = remember(activeCollections) { activeCollections.filter { it.record.kind == CollectionKind.BOOKLIST } }
    val standaloneBooks = remember(ui.catalog) { ui.catalog.filter { it.showStandalone } }
    val isEmpty = ui.catalog.isEmpty() && activeCollections.isEmpty() && remoteBooks.isEmpty()
    var libraryOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier.fillMaxSize().padding(
            horizontal = if (hinge != null) CoachV7.Space.s3 else CoachV7.Space.s5,
            vertical = CoachV7.Space.s3,
        ),
        contentAlignment = Alignment.TopCenter,
    ) {
        val wideEmptyGuides = maxWidth >= 560.dp
        val sectionSpacing = if (maxHeight < 560.dp) CoachV7.Space.s3 else CoachV7.Space.s5
        Box(
            Modifier.widthIn(max = if (hinge != null) 1000.dp else 720.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .testTag("shelf-sheet"),
        ) {
            if (libraryOpen && library != null) {
                MyLibraryScreen(
                    library = library,
                    onBack = { libraryOpen = false },
                    onOpenBook = { id ->
                        libraryOpen = false
                        onOpenBook(id)
                    },
                )
            } else if (isEmpty) {
                // 空态首页：居中单栏，唯一主钮「导入文件」+ 3 张可点引导卡（加顶栏 ＋ / 设置，共 6 个零死路入口）。
                Box(Modifier.fillMaxSize(), contentAlignment = if (showToday) Alignment.TopCenter else Alignment.Center) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(scroll)
                            .padding(horizontal = CoachV7.Space.s4, vertical = CoachV7.Space.s5)
                            .testTag("home-empty-hub"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(CoachV7.Space.s5),
                    ) {
                        if (showToday) TodayCard(ui, library, onOpenBook, onOpenReviews, onOpenAskDraft)
                        CoachEmptyState(
                            title = "把要读的书或课程材料放进来。",
                            hint = "直接把文件拖进窗口，或选择下面一种方式开始。",
                            modifier = Modifier.padding(bottom = 0.dp),
                        )
                        PrimaryButton(
                            onClick = onImport,
                            enabled = !ui.busy,
                            modifier = Modifier.testTag("upload-textbook"),
                        ) { Text("导入文件") }
                        if (wideEmptyGuides) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(CoachV7.Space.s3),
                            ) {
                                EmptyRouteCard(
                                    title = "为一门课备考",
                                    meta = "放入课本与讲义，按周备考",
                                    tag = "empty-route-course",
                                    enabled = !ui.busy,
                                    onClick = onNewCourse,
                                    modifier = Modifier.weight(1f),
                                )
                                EmptyRouteCard(
                                    title = "单读一本书",
                                    meta = "选书直接开读，随时提问",
                                    tag = "empty-route-single",
                                    enabled = !ui.busy,
                                    onClick = onImport,
                                    modifier = Modifier.weight(1f),
                                )
                                EmptyRouteCard(
                                    title = "读一组书",
                                    meta = "选入多本，串起共同主题",
                                    tag = "empty-route-booklist",
                                    enabled = !ui.busy,
                                    onClick = onNewBooklist,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(CoachV7.Space.s3),
                            ) {
                                EmptyRouteCard(
                                    title = "为一门课备考",
                                    meta = "放入课本、讲义和习题，按周复习备考",
                                    tag = "empty-route-course",
                                    enabled = !ui.busy,
                                    onClick = onNewCourse,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                EmptyRouteCard(
                                    title = "单读一本书",
                                    meta = "选一本书直接打开读，随时看要点与提问",
                                    tag = "empty-route-single",
                                    enabled = !ui.busy,
                                    onClick = onImport,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                EmptyRouteCard(
                                    title = "读一组书",
                                    meta = "一次选入两本以上，自动串起共同主题",
                                    tag = "empty-route-booklist",
                                    enabled = !ui.busy,
                                    onClick = onNewBooklist,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        if (!showToday) CoachCardMeta(
                            HOME_REVIEW_EMPTY_MESSAGE,
                            Modifier.testTag("home-review-done"),
                        )
                    }
                }
            } else {
                val focusBook = ui.book
                val focusSummary = focusBook?.let { b -> ui.catalog.firstOrNull { it.id == b.id } } ?: ui.catalog.firstOrNull()
                val focusCollection = if (focusBook == null) activeCollections.firstOrNull() else null

                val focusAndReviewBlock: @Composable () -> Unit = focus@{
                    if (showToday) {
                        TodayCard(ui, library, onOpenBook, onOpenReviews, onOpenAskDraft)
                        return@focus
                    }
                    // 1. 顶部焦点卡：全屏唯一 PrimaryButton（「继续」）+ 就地学法切换器（≤2 击开非默认学法）。
                    if (focusBook != null) {
                        var focusPref by remember(focusBook.id, library) {
                            mutableStateOf(
                                if (library != null) StudyModeStore.loadOrResolve(library, focusBook.id, focusBook.title, focusBook.pages.size)
                                else BookStudyPreference(bookId = focusBook.id),
                            )
                        }
                        val chapterTitle = focusBook.lessons.firstOrNull {
                            focusBook.readingPage in it.firstPage..it.lastPage
                        }?.title
                        PaperCard(
                            Modifier.fillMaxWidth().testTag("home-focus-card"),
                            color = colors.surface1,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        focusBook.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontFamily = LocalV7Fonts.current.display,
                                        color = colors.fgPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("home-focus-title"),
                                    )
                                    val savedDraft = ui.activePracticeDraft?.takeIf {
                                        it.bookId == focusBook.id && it.hasMeaningfulDraft()
                                    }
                                    val masteryRec = if (savedDraft == null) {
                                        ui.focusRecommendation?.takeIf { it.bookId == focusBook.id }
                                    } else null
                                    val metaText = MasteryEngine.formatContinueCardMeta(
                                        bookTitle = focusBook.title,
                                        chapterTitle = chapterTitle,
                                        readingPage = focusBook.readingPage,
                                        lastQuestion = ui.lastBookQuestion?.takeIf { it.bookId == focusBook.id }?.text,
                                        savedDraft = savedDraft,
                                        masteryRec = masteryRec,
                                    )
                                    CoachCardMeta(metaText, Modifier.testTag("home-focus-meta"))
                                }
                                StudyModeSelectorButton(
                                    preference = focusPref,
                                    onUpdatePreference = { next, modeChanged ->
                                        focusPref = if (library != null) StudyModeStore.save(library, next) else next
                                        if (modeChanged && !ui.busy) onRead()
                                    },
                                    compact = true,
                                    tag = "home-mode-trigger",
                                )
                                PrimaryButton(
                                    onClick = onRead,
                                    enabled = !ui.busy,
                                    modifier = Modifier.testTag("home-read"),
                                ) { Text("继续") }
                            }
                        }
                    } else if (focusSummary != null) {
                        var summaryPref by remember(focusSummary.id, library) {
                            mutableStateOf(
                                if (library != null) StudyModeStore.loadOrResolve(library, focusSummary.id, focusSummary.title, focusSummary.pageCount)
                                else BookStudyPreference(bookId = focusSummary.id),
                            )
                        }
                        PaperCard(
                            Modifier.fillMaxWidth().testTag("home-focus-card"),
                            color = colors.surface1,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        focusSummary.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontFamily = LocalV7Fonts.current.display,
                                        color = colors.fgPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("home-focus-title"),
                                    )
                                    CoachCardMeta(
                                        MasteryEngine.formatContinueCardMeta(
                                            bookTitle = focusSummary.title,
                                            chapterTitle = null,
                                            readingPage = focusSummary.readingPage,
                                            lastQuestion = ui.lastBookQuestion?.takeIf { it.bookId == focusSummary.id }?.text,
                                        ),
                                        Modifier.testTag("home-focus-meta"),
                                    )
                                }
                                StudyModeSelectorButton(
                                    preference = summaryPref,
                                    onUpdatePreference = { next, modeChanged ->
                                        summaryPref = if (library != null) StudyModeStore.save(library, next) else next
                                        if (modeChanged && !ui.busy) onOpenBook(focusSummary.id)
                                    },
                                    compact = true,
                                    tag = "home-mode-trigger",
                                )
                                PrimaryButton(
                                    onClick = { onOpenBook(focusSummary.id) },
                                    enabled = !ui.busy,
                                    modifier = Modifier.testTag("home-read"),
                                ) { Text("继续") }
                            }
                        }
                    } else if (focusCollection != null) {
                        PaperCard(
                            Modifier.fillMaxWidth().testTag("home-focus-card"),
                            color = colors.surface1,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        focusCollection.record.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontFamily = LocalV7Fonts.current.display,
                                        color = colors.fgPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("home-focus-title"),
                                    )
                                    CoachCardMeta(
                                        collectionMeta(focusCollection),
                                        Modifier.testTag("home-focus-meta"),
                                    )
                                }
                                PrimaryButton(
                                    onClick = { onOpenCollection(focusCollection) },
                                    enabled = !ui.busy,
                                    modifier = Modifier.testTag("home-read"),
                                ) { Text("继续") }
                            }
                        }
                    }

                    // 2. 条件今日复习卡：有到期项写「今天 N 道，约 M 分钟」，点击卡片或按钮进清单；无到期项如实提示，不推送。
                    val dueCount = if (ui.todayQueue.cards.isNotEmpty()) ui.todayQueue.cards.size else ui.reviews.size
                    if (dueCount > 0) {
                        PaperCard(
                            Modifier.fillMaxWidth().testTag("home-review-card"),
                            color = colors.surface1,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        "今日复习",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.fgPrimary,
                                        modifier = Modifier.shelfClick(!ui.busy, onOpenReviews),
                                    )
                                    val reviewMeta = MasteryEngine.formatHomeReviewMeta(dueCount)
                                    CoachCardMeta(
                                        reviewMeta,
                                        Modifier.testTag("home-review-meta").shelfClick(!ui.busy, onOpenReviews),
                                    )
                                }
                                PaperButton(
                                    onClick = onOpenReviews,
                                    enabled = !ui.busy,
                                    modifier = Modifier.testTag("home-review-start"),
                                ) { Text("开始复习") }
                            }
                        }
                    } else {
                        CoachCardMeta(
                            HOME_REVIEW_EMPTY_MESSAGE,
                            Modifier.padding(horizontal = 4.dp).testTag("home-review-done"),
                        )
                    }
                }

                val collectionsAndReadingBlock: @Composable () -> Unit = {
                    if (library != null && ui.catalog.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "批注、问答和错题",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.fgSecondary,
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                            )
                            V7TextButton(
                                onClick = { libraryOpen = true },
                                modifier = Modifier.testTag("open-my-library"),
                                ringInside = true,
                            ) { Text("我的库") }
                        }
                    }
                    // 3. 课程卡片组
                    if (courses.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CoachV7.Space.s2)) {
                            Text(
                                "课程",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.fgSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp).testTag("home-section-courses"),
                            )
                            for (course in courses) {
                                HomeCollectionCard(
                                    summary = course,
                                    badge = null,
                                    enabled = !ui.busy,
                                    onClick = { onOpenCollection(course) },
                                )
                            }
                        }
                    }

                    // 4. 阅读卡片组（书单 + 单书 + 云端待拉回原文件，课程内附带材料不散落占满首页）
                    val visibleBooks = if (standaloneBooks.isNotEmpty()) standaloneBooks else if (courses.isEmpty()) ui.catalog else emptyList()
                    if (booklists.isNotEmpty() || visibleBooks.isNotEmpty() || remoteBooks.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CoachV7.Space.s2)) {
                            Text(
                                "阅读",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.fgSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp).testTag("home-section-reading"),
                            )
                            restoreError?.takeIf { it.isNotBlank() }?.let { err ->
                                Text(
                                    err,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.dangerFg,
                                    modifier = Modifier.padding(horizontal = 4.dp).testTag("cloud-restore-error"),
                                )
                            }
                            for (list in booklists) {
                                HomeCollectionCard(
                                    summary = list,
                                    badge = "书单",
                                    enabled = !ui.busy,
                                    onClick = { onOpenCollection(list) },
                                )
                            }
                            for (item in visibleBooks) {
                                HomeBookCard(
                                    item = item,
                                    current = item.id == focusBook?.id,
                                    enabled = !ui.busy,
                                    activeImport = ui.importing?.takeIf { it.recognizing && it.bookId == item.id },
                                    uploadStatus = ui.uploadStatuses[item.id],
                                    onRetryUpload = { onRetryUpload(item.id) },
                                    onCancelImport = onCancelImport,
                                    onClick = { onOpenBook(item.id) },
                                )
                            }
                            for (remote in remoteBooks) {
                                val restoringThis = restoringShelfBookId == remote.shelfBookId
                                CloudShelfBookCard(
                                    book = remote,
                                    restoring = restoringThis,
                                    restoreProgressPct = if (restoringThis) restoreProgressPct else 0,
                                    enabled = !ui.busy && restoringShelfBookId == null,
                                    onClick = {
                                        val coord = cloudCoordinator ?: return@CloudShelfBookCard
                                        scope.launch {
                                            val localId = coord.downloadAndImport(remote)
                                            if (localId != null) onOpenBook(localId)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                BoxWithConstraints(
                    Modifier.fillMaxSize()
                        .padding(
                            horizontal = if (hinge != null) CoachV7.Space.s3 else CoachV7.Space.s2,
                            vertical = CoachV7.Space.s2,
                        )
                        .onGloballyPositioned { coords -> sheetOriginLeftDp = coords.positionInRoot().x / density.density },
                ) {
                    val verticalSplit = if (focusBook != null) {
                        bookVerticalSplit(maxWidth.value, maxHeight.value, sheetOriginLeftDp ?: 0f, hinge)
                    } else null
                    if (verticalSplit != null) {
                        Row(Modifier.fillMaxSize()) {
                            Column(
                                Modifier.width(verticalSplit.leftDp.dp).fillMaxHeight().verticalScroll(scroll)
                                    .testTag("shelf-half-fold-left"),
                                verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                            ) {
                                focusAndReviewBlock()
                            }
                            Spacer(Modifier.width(verticalSplit.gapDp.dp).fillMaxHeight().testTag("shelf-half-fold-hinge"))
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rightScroll)
                                    .testTag("shelf-half-fold-right"),
                                verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                            ) {
                                collectionsAndReadingBlock()
                            }
                        }
                    } else {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(scroll)
                                .padding(bottom = if (ui.pendingMultiDrop != null) 76.dp else 16.dp)
                                .testTag("home-daily-hub"),
                            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                        ) {
                            focusAndReviewBlock()
                            collectionsAndReadingBlock()
                        }
                        BookScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = CoachV7.Space.s4))
                    }
                }
            }

            // 一次拖入多份文件时，底部浮出单行询问条（建课程 / 建书单 / 分开单放）。
            ui.pendingMultiDrop?.let { drop ->
                MultiDropInquiryBar(
                    count = drop.paths.size,
                    enabled = !ui.busy,
                    onCourse = { onMultiDropAction(MultiDropAction.COURSE) },
                    onBooklist = { onMultiDropAction(MultiDropAction.BOOKLIST) },
                    onSeparate = { onMultiDropAction(MultiDropAction.SEPARATE) },
                    onDismiss = onDismissMultiDrop,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
            }
        }
    }
}

private fun collectionMeta(summary: CollectionSummary): String {
    val count = summary.materialIds.size
    return when (summary.record.kind) {
        CollectionKind.COURSE -> buildString {
            summary.record.examDate?.takeIf(String::isNotBlank)?.let { append("考试 $it · ") }
            append(if (count == 0) "待添加材料" else "$count 份材料")
        }
        CollectionKind.BOOKLIST -> if (summary.activeConceptCount > 0) "$count 本书 · 已关联 ${summary.activeConceptCount} 个共同主题" else "$count 本书"
        else -> "$count 份材料"
    }
}

@Composable
private fun EmptyRouteCard(
    title: String,
    meta: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    PaperTile(
        enabled = enabled,
        modifier = modifier.border(1.dp, colors.borderSubtle, shape).testTag(tag),
        onClick = onClick,
        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        spacing = 6.dp,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.fgPrimary)
        CoachCardMeta(meta)
    }
}

@Composable
private fun HomeCollectionCard(
    summary: CollectionSummary,
    badge: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    PaperTile(
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().border(1.dp, colors.borderSubtle, shape)
            .testTag("collection-${summary.record.id}"),
        onClick = onClick,
        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        spacing = 6.dp,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                summary.record.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.fgPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.fgTertiary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        CoachCardMeta(collectionMeta(summary))
    }
}

@Composable
private fun HomeBookCard(
    item: BookSummary,
    current: Boolean,
    enabled: Boolean,
    activeImport: BookImportProgress? = null,
    uploadStatus: BookUploadEntry? = null,
    onRetryUpload: () -> Unit = {},
    onCancelImport: () -> Unit = {},
    onClick: () -> Unit,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    val statusSuffix = when {
        activeImport != null -> " · 识别中 ${activeImport.page} / ${activeImport.total} 页"
        item.pendingOcrPages > 0 -> " · 待识别 ${item.pendingOcrPages} 页"
        item.learning -> " · 在学"
        else -> ""
    }
    val uploadLine = uploadStatus?.cardStatusLine()
    Column(
        Modifier.fillMaxWidth()
            .paperShadow("card", colors.dark)
            .clip(shape)
            .background(if (current) colors.surface1 else colors.surface2)
            .border(1.dp, if (current) colors.actionPrimary.copy(alpha = 0.35f) else colors.borderSubtle, shape),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .testTag("book-${item.id}")
                .shelfClick(enabled, onClick)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.fgPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CoachCardMeta("${item.pageCount} 页 · 读到第 ${item.readingPage} 页$statusSuffix")
        }
        if (uploadLine != null) {
            Row(
                Modifier.fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, bottom = 10.dp)
                    .testTag("book-upload-status-${item.id}"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    uploadLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uploadStatus.canRetry) colors.dangerFg else colors.fgTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (uploadStatus.canRetry) {
                    V7TextButton(
                        onClick = onRetryUpload,
                        enabled = enabled,
                        ringInside = true,
                        modifier = Modifier.testTag("book-upload-retry-${item.id}"),
                    ) { Text("重试", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        if (activeImport != null) {
            BookImportProgressBar(
                progress = activeImport,
                onCancel = onCancelImport,
                compact = true,
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, bottom = 12.dp)
                    .testTag("book-import-progress"),
            )
        }
    }
}

@Composable
private fun CloudShelfBookCard(
    book: RemoteShelfBook,
    restoring: Boolean,
    restoreProgressPct: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    val statusText = if (restoring) {
        if (restoreProgressPct in 1..99) "正在拉回原文件 · $restoreProgressPct%" else "正在拉回原文件"
    } else {
        book.cardStatusLine()
    }
    Column(
        Modifier.fillMaxWidth()
            .paperShadow("card", colors.dark)
            .clip(shape)
            .background(colors.surface2)
            .border(1.dp, colors.borderSubtle, shape),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .testTag("cloud-book-${book.shelfBookId}")
                .shelfClick(enabled, onClick)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.fgPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CoachCardMeta(
                "${book.format.uppercase()} · $statusText",
                modifier = Modifier.testTag("cloud-book-status-${book.shelfBookId}"),
            )
        }
    }
}

@Composable
internal fun MultiDropInquiryBar(
    count: Int,
    enabled: Boolean,
    onCourse: () -> Unit,
    onBooklist: () -> Unit,
    onSeparate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    Row(
        modifier
            .fillMaxWidth()
            .paperShadow("sheet", colors.dark)
            .clip(shape)
            .background(colors.surface2)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("multi-drop-bar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "要把这 $count 份文件建成课程，还是建成书单。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.fgPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(onClick = onCourse, enabled = enabled, modifier = Modifier.testTag("multi-drop-course")) {
            Text("建课程")
        }
        PaperButton(onClick = onBooklist, enabled = enabled, modifier = Modifier.testTag("multi-drop-booklist")) {
            Text("建书单")
        }
        PaperButton(onClick = onSeparate, enabled = enabled, modifier = Modifier.testTag("multi-drop-separate")) {
            Text("分开单放")
        }
        V7TextButton(onClick = onDismiss, enabled = enabled, modifier = Modifier.testTag("multi-drop-cancel"), ringInside = true) {
            Text("取消")
        }
    }
}

/** Matches the shared V7 button hover, press, pointer and keyboard focus behavior on the sheet. */
@Composable
private fun Modifier.shelfClick(enabled: Boolean, onClick: () -> Unit): Modifier {
    val colors = CoachTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val wash = if (pressed) {
        if (colors.dark) CoachV7.Dark.selBg else CoachV7.Light.selBg
    } else {
        if (colors.dark) CoachV7.Dark.zhuWash else CoachV7.Light.zhuWash
    }
    val shape = RoundedCornerShape(CoachV7.Radius.s)
    return v7FocusRing(shape).clip(shape)
        .background(if (enabled && (hovered || pressed)) wash.compositeOver(colors.surface1) else Color.Transparent)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .alpha(if (enabled) 1f else .5f)
}

/** A card-coloured block on the sheet that reads as one button: V7's card, focus ring and press. */
@Composable
internal fun PaperTile(
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    padding: PaddingValues,
    spacing: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    Column(
        modifier.v7FocusRing(shape).paperShadow("card", colors.dark).clip(shape).background(colors.surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).alpha(if (enabled) 1f else .5f).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}
