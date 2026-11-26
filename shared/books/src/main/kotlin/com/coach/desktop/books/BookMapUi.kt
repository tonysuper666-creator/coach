package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.CoachLoadingDots
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * 2.1「这本书讲什么」全书地图完整受控面板（TON-1889）。
 *
 * - 首次打开自动读本机 `BookMapStore`，已有地图零额度直接显示；
 * - 监听书的骨架指纹（`BookMapSkeleton.computeSkeletonHash(book)`），改了骨架立即重排；
 * - 新生成走 Haiku 5.5 Batch（支持非阻塞 `pending: true` 轮询），生成中/失败/断网各有人话状态；
 * - 点任意要点或原句标签，调用 `BookMapAnchorResolver.toCompanionAnchor` 精准跳页并高亮原句。
 */
@Composable
internal fun BookWhatAboutMapPanel(
    book: Textbook,
    library: TextbookLibrary,
    currentPage: Int,
    explainLang: ExplainLanguage = ExplainLanguage.BILINGUAL,
    aiAvailable: Boolean = true,
    fetchBatch: suspend (JsonObject) -> CourseServerCallResult,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    onJumpPage: (Int) -> Unit = { p -> onOpenAnchor(BookCompanionAnchor(p, "", 0, 0, "")) },
    pollDelayOverrideMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val latestBook by rememberUpdatedState(book)
    val skeletonHash = remember(book.id, book.structure?.editRevision, book.structure, book.tocNodes, book.correctedBodyPage, book.pages.size) {
        BookMapSkeleton.computeSkeletonHash(book)
    }
    var state by remember(book.id) { mutableStateOf<BookMapUiState>(BookMapUiState.Idle) }
    var progressByChapter by remember(book.id) { mutableStateOf<Map<String, ChapterProgressRecord>>(emptyMap()) }
    var activeJob by remember(book.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var revision by remember(book.id) { mutableIntStateOf(0) }

    // 1. Load locally cached map & reading progress (zero quota spend on reopen).
    //    Re-runs automatically whenever the book's skeleton changes (TON-1876) to reconcile chapters immediately.
    LaunchedEffect(book.id, skeletonHash, currentPage) {
        val loadedProgress = withContext(Dispatchers.IO) { BookMapReadProgress.loadForBook(library, book.id) }
        progressByChapter = loadedProgress
        val loadedDoc = withContext(Dispatchers.IO) { BookMapStore.load(library, book) }
        if (loadedDoc != null) {
            state = BookMapUiState.Ready(loadedDoc)
        } else if (state !is BookMapUiState.Generating) {
            state = BookMapUiState.Idle
        }
    }

    fun startGenerate(targetChapterIds: Set<String> = emptySet(), bumpRevision: Boolean = false) {
        val existingDoc = when (val cur = state) {
            is BookMapUiState.Ready -> cur.document
            is BookMapUiState.Failed -> cur.existing
            else -> null
        }
        if (!aiAvailable) {
            state = BookMapUiState.Failed(
                message = if (existingDoc != null) {
                    "当前未连接学习服务，已保留本机存好的地图，联网或登录后再更新。"
                } else {
                    "当前离线或未登录，联网后再生成地图；生成一次会存在本机，以后离线也能看。"
                },
                offline = true,
                retryable = true,
                existing = existingDoc,
            )
            return
        }
        activeJob?.cancel()
        if (bumpRevision) revision += 1
        val currentRev = revision
        state = BookMapUiState.Generating(
            message = if (targetChapterIds.isNotEmpty()) {
                "正在按新目录补齐 ${targetChapterIds.size} 章的要点，可先继续看书…"
            } else {
                "正在按目录整理全书地图，整理完会自动保存在本机，以后打开不再花额度…"
            },
            pending = false,
        )

        activeJob = scope.launch {
            try {
                val built = withContext(Dispatchers.IO) {
                    BookMapWire.buildBatchRequest(
                        book = book,
                        explainLang = explainLang,
                        revision = currentRev,
                        targetChapterIds = targetChapterIds,
                    )
                }
                if (built.refMap.isEmpty()) {
                    state = BookMapUiState.Failed(
                        message = "这本书还没提取出可读正文，先确认正文起始页或导入文字版后再试。",
                        offline = false,
                        retryable = true,
                        existing = existingDoc,
                    )
                    return@launch
                }

                var polls = 0
                while (true) {
                    val reply = fetchBatch(built.payload)
                    val body = reply.body
                    if (body == null) {
                        state = BookMapErrors.fromCallFailure(reply.error, reply.trouble, existingDoc)
                        return@launch
                    }
                    when (val parsed = BookMapWire.parseBatchResponse(book, body, built.refMap, existingToMerge = if (targetChapterIds.isNotEmpty()) existingDoc else null)) {
                        is BookMapBatchParseResult.Pending -> {
                            polls += 1
                            withContext(Dispatchers.IO) {
                                BookMapStore.savePendingJob(library, book.id, built.skeletonHash, parsed.jobKey, currentRev)
                            }
                            val chunkHint = if (parsed.totalChunks > 1) {
                                "（已完成 ${parsed.completedChunks}/${parsed.totalChunks} 批）"
                            } else ""
                            state = BookMapUiState.Generating(
                                message = "批处理正在后台整理全书要点$chunkHint，可先继续读书，完成后自动保存…",
                                pending = true,
                                completedChunks = parsed.completedChunks,
                                totalChunks = parsed.totalChunks,
                            )
                            if (polls >= 120) {
                                state = BookMapUiState.Failed(
                                    message = "批处理还在排队中，稍后点一下继续获取，不会重复花额度。",
                                    offline = false,
                                    retryable = true,
                                    existing = existingDoc,
                                )
                                return@launch
                            }
                            delay(pollDelayOverrideMs ?: parsed.retryAfterMs)
                        }
                        is BookMapBatchParseResult.Ready -> {
                            val currentBook = latestBook
                            val finalDoc = BookMapReconciler.reconcile(parsed.document, currentBook)
                            withContext(Dispatchers.IO) {
                                BookMapStore.save(library, finalDoc, expected = currentBook)
                            }
                            progressByChapter = withContext(Dispatchers.IO) { BookMapReadProgress.loadForBook(library, book.id) }
                            state = BookMapUiState.Ready(finalDoc)
                            return@launch
                        }
                        is BookMapBatchParseResult.Invalid -> {
                            state = BookMapUiState.Failed(
                                message = parsed.message,
                                offline = false,
                                retryable = true,
                                existing = existingDoc,
                            )
                            return@launch
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = BookMapUiState.Failed(
                    message = if (existingDoc != null) {
                        "现在连不上学习服务，已保留本机存好的地图，联网后再试。"
                    } else {
                        "现在连不上学习服务，联网后再生成地图。"
                    },
                    offline = true,
                    retryable = true,
                    existing = existingDoc,
                )
            }
        }
    }

    DisposableEffect(book.id) {
        onDispose { activeJob?.cancel() }
    }

    BookWhatAboutMapView(
        book = book,
        currentPage = currentPage,
        state = state,
        progressByChapter = progressByChapter,
        onGenerateAll = { startGenerate(emptySet(), bumpRevision = false) },
        onRegenerateStale = { staleIds -> startGenerate(staleIds, bumpRevision = true) },
        onRetry = { startGenerate(emptySet(), bumpRevision = false) },
        onClickAnchor = { anchor ->
            onOpenAnchor(BookMapAnchorResolver.toCompanionAnchor(book, anchor))
        },
        onJumpPage = onJumpPage,
        modifier = modifier,
    )
}

/**
 * 纯展示与交互组件：按章展示 3–7 个要点、已读/没读筛选、骨架重排提示与原句高亮跳转。
 * 遵循 V7 纸感克制原则：不新增 PrimaryButton，所有操作用 PaperButton / V7TextButton。
 */
@Composable
internal fun BookWhatAboutMapView(
    book: Textbook,
    currentPage: Int,
    state: BookMapUiState,
    progressByChapter: Map<String, ChapterProgressRecord>,
    onGenerateAll: () -> Unit,
    onRegenerateStale: (Set<String>) -> Unit,
    onRetry: () -> Unit,
    onClickAnchor: (BookMapAnchor) -> Unit,
    onJumpPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val cardShape = RoundedCornerShape(CoachV7.Radius.s)
    var filter by remember(book.id) { mutableStateOf(BookMapFilter.ALL) }
    val spans = remember(book.id, book.structure, book.tocNodes, book.correctedBodyPage) { BookMapSkeleton.chapters(book) }
    val bodyStart = remember(book.id, book.structure, book.correctedBodyPage) { BookMapSkeleton.bodyStartPage(book) }
    val activeDoc: BookMapDocument? = when (state) {
        is BookMapUiState.Ready -> state.document
        is BookMapUiState.Failed -> state.existing
        else -> null
    }

    val currentChapterId = remember(activeDoc, currentPage) {
        activeDoc?.chapters?.firstOrNull { currentPage in it.firstPage..it.lastPage }?.chapterId
            ?: activeDoc?.chapters?.firstOrNull()?.chapterId
    }
    var expandedChapters by remember(book.id, activeDoc?.skeletonHash) {
        mutableStateOf(setOfNotNull(currentChapterId))
    }
    LaunchedEffect(currentChapterId) {
        if (currentChapterId != null) {
            expandedChapters = expandedChapters + currentChapterId
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag("book-what-about-map"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header card: skeleton summary + read progress + trigger button
        PaperCard(
            Modifier.fillMaxWidth(),
            color = colors.surface2,
            contentPadding = PaddingValues(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "这本书讲什么",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.fgPrimary,
                        modifier = Modifier.testTag("book-map-v2-title"),
                    )
                    CoachCardMeta(
                        "共 ${spans.size} 章 · 正文 p.$bodyStart 起",
                        Modifier.testTag("book-map-v2-skeleton-meta"),
                    )
                }

                if (activeDoc != null) {
                    val totalPts = activeDoc.totalPoints
                    val readPts = activeDoc.chapters.sumOf { ch ->
                        val prog = progressByChapter[ch.chapterId]
                        ch.points.count { BookMapReadProgress.isPointRead(it, prog) }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoachCardMeta(
                            "已读 $readPts / $totalPts 个要点 · 已存本机，再打开不花额度",
                            Modifier.testTag("book-map-v2-progress-summary"),
                        )
                        val allExpanded = activeDoc.chapters.all { it.chapterId in expandedChapters }
                        V7TextButton(
                            onClick = {
                                expandedChapters = if (allExpanded) {
                                    setOfNotNull(currentChapterId)
                                } else {
                                    activeDoc.chapters.map { it.chapterId }.toSet()
                                }
                            },
                            modifier = Modifier.testTag("book-map-v2-expand-all"),
                        ) {
                            Text(if (allExpanded) "只看当前章" else "展开全部")
                        }
                    }
                } else {
                    Text(
                        "按书的目录把每章拆成 3–7 句大白话要点，每条都能点回书里那句原文。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fgSecondary,
                    )
                }

                // Filter strip when map is ready
                if (activeDoc != null && activeDoc.totalPoints > 0) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (opt in BookMapFilter.entries) {
                            val selected = filter == opt
                            Box(
                                Modifier
                                    .clip(cardShape)
                                    .background(if (selected) colors.actionPrimary.copy(alpha = 0.14f) else colors.surface1)
                                    .border(
                                        1.dp,
                                        if (selected) colors.actionPrimary.copy(alpha = 0.45f) else colors.borderSubtle,
                                        cardShape,
                                    )
                                    .clickable(role = Role.RadioButton) { filter = opt }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .testTag(opt.tag),
                            ) {
                                Text(
                                    opt.labelZh,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) colors.actionPrimary else colors.fgSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // State banners (generating / offline / failed / skeleton reconciled)
        when (state) {
            BookMapUiState.Idle -> {
                PaperButton(
                    onClick = onGenerateAll,
                    modifier = Modifier.fillMaxWidth().testTag("book-map-v2-generate"),
                ) {
                    Text("生成这本书的地图")
                }
            }

            is BookMapUiState.Generating -> {
                PaperCard(
                    Modifier.fillMaxWidth().testTag("book-map-v2-generating"),
                    color = colors.surface2,
                    contentPadding = PaddingValues(12.dp),
                ) {
                    CoachLoadingDots(label = state.message)
                }
            }

            is BookMapUiState.Failed -> {
                PaperCard(
                    Modifier.fillMaxWidth().testTag(if (state.offline) "book-map-v2-offline" else "book-map-v2-error"),
                    color = colors.surface2,
                    contentPadding = PaddingValues(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.fgSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.retryable) {
                            V7TextButton(
                                onClick = onRetry,
                                modifier = Modifier.testTag("book-map-v2-retry"),
                            ) {
                                Text("重试")
                            }
                        }
                    }
                }
            }

            is BookMapUiState.Ready -> {
                if (state.document.reconciledFromSkeleton) {
                    PaperCard(
                        Modifier.fillMaxWidth().testTag("book-map-v2-reconciled-banner"),
                        color = colors.surface2,
                        contentPadding = PaddingValues(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val staleCount = state.document.staleChapterIds.size
                            Text(
                                if (staleCount > 0) {
                                    "目录已修改，地图已按新章节重排；有 $staleCount 章要点偏少，可补齐。"
                                } else {
                                    "目录已修改，地图已按新章节重排。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.fgSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.document.staleChapterIds.isNotEmpty()) {
                                V7TextButton(
                                    onClick = { onRegenerateStale(state.document.staleChapterIds) },
                                    modifier = Modifier.testTag("book-map-v2-refresh-stale"),
                                ) {
                                    Text("补齐缺章")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Chapters & points list
        if (activeDoc != null) {
            activeDoc.chapters.forEachIndexed { chIdx, chapter ->
                val prog = progressByChapter[chapter.chapterId]
                val status = BookMapReadProgress.chapterStatus(chapter, prog)
                val visiblePoints = chapter.points.filter { pt ->
                    val isRead = BookMapReadProgress.isPointRead(pt, prog)
                    when (filter) {
                        BookMapFilter.ALL -> true
                        BookMapFilter.READ -> isRead
                        BookMapFilter.UNREAD -> !isRead
                    }
                }
                if (filter != BookMapFilter.ALL && visiblePoints.isEmpty()) return@forEachIndexed

                val isCurrent = chapter.chapterId == currentChapterId
                val expanded = chapter.chapterId in expandedChapters

                PaperCard(
                    Modifier
                        .fillMaxWidth()
                        .testTag("book-map-v2-chapter-$chIdx"),
                    color = colors.surface2,
                    contentPadding = PaddingValues(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(cardShape)
                                .clickable(role = Role.Button) {
                                    expandedChapters = if (expanded) {
                                        expandedChapters - chapter.chapterId
                                    } else {
                                        expandedChapters + chapter.chapterId
                                    }
                                }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(vertical = 2.dp)
                                .testTag("book-map-v2-chapter-header-$chIdx"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "第 ${chapter.chapterNum} 章 · ${chapter.title}",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isCurrent) colors.actionPrimary else colors.fgPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (isCurrent) {
                                        CoachCardMeta("正在读")
                                    }
                                }
                                if (chapter.oneLiner.isNotBlank()) {
                                    Text(
                                        chapter.oneLiner,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.fgSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ChapterReadPill(status = status, tag = "book-map-v2-chapter-status-$chIdx")
                                CoachCardMeta("p.${chapter.firstPage}")
                                Text(
                                    if (expanded) "▾" else "▸",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.fgSecondary,
                                )
                            }
                        }

                        if (expanded) {
                            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.6f))
                            if (visiblePoints.isEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CoachCardMeta("这一章暂时还没有生成要点。")
                                    V7TextButton(onClick = { onJumpPage(chapter.firstPage) }) {
                                        Text("去第 ${chapter.firstPage} 页")
                                    }
                                }
                            } else {
                                visiblePoints.forEachIndexed { ptIdx, point ->
                                    val isRead = BookMapReadProgress.isPointRead(point, prog)
                                    BookMapPointRow(
                                        point = point,
                                        isRead = isRead,
                                        rowTag = "book-map-v2-point-$chIdx-$ptIdx",
                                        onClickAnchor = onClickAnchor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterReadPill(
    status: BookMapReadProgress.ChapterStatus,
    tag: String,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(999.dp)
    val bg = when (status.state) {
        ChapterReadState.READ -> colors.actionPrimary.copy(alpha = 0.14f)
        ChapterReadState.PARTIAL -> colors.surface1
        ChapterReadState.UNREAD -> Color.Transparent
    }
    val fg = when (status.state) {
        ChapterReadState.READ -> colors.actionPrimary
        ChapterReadState.PARTIAL -> colors.fgPrimary
        ChapterReadState.UNREAD -> colors.fgSecondary
    }
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .testTag(tag),
    ) {
        Text(
            status.labelZh,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
private fun BookMapPointRow(
    point: BookMapPoint,
    isRead: Boolean,
    rowTag: String,
    onClickAnchor: (BookMapAnchor) -> Unit,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.s)
    val primary = point.primaryAnchor
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface1)
            .border(1.dp, colors.borderSubtle.copy(alpha = 0.7f), shape)
            .then(
                if (primary != null) {
                    Modifier
                        .clickable(role = Role.Button) { onClickAnchor(primary) }
                        .pointerHoverIcon(PointerIcon.Hand)
                } else Modifier,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(rowTag),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isRead) colors.actionPrimary else colors.borderSubtle),
            )
            Text(
                point.summary,
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (isRead) "已读" else "没读",
                style = MaterialTheme.typography.labelSmall,
                color = if (isRead) colors.actionPrimary else colors.fgSecondary,
                modifier = Modifier.testTag("$rowTag-read-state"),
            )
        }
        if (point.anchors.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                point.anchors.forEachIndexed { aIdx, anchor ->
                    val snippet = anchor.quote.replace(Regex("\\s+"), " ").trim().let {
                        if (it.length > 36) it.take(36) + "…" else it
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surface2)
                            .border(1.dp, colors.actionPrimary.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) { onClickAnchor(anchor) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                            .testTag("$rowTag-anchor-$aIdx"),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "p.${anchor.pdfPage}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = colors.actionPrimary,
                        )
                        Text(
                            "回原句：$snippet",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.fgSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
