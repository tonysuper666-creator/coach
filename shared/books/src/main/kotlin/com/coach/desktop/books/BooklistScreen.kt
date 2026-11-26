package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.CoachEmptyState
import com.coach.ui.v7.CoachLoadingDots
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LearningCoach 2.0 书单主视图（`BooklistScreen`，居中单栏 <= 720dp，全屏唯一 PrimaryButton）：
 * - 顶部：返回、书单名、单行进度小字、唯一主按钮「继续读」、次按钮「＋ 加书」与「⋯」；
 * - 上半区：组内书籍与真实已读章节进度；
 * - 下半区：「共同主题」列表（点开查看跨书片段对照、双向跳转原文、编辑实时落盘的共享笔记、一键消音）；
 * - 最底部：折叠的「已静音主题（N）▾」，支持随时一键恢复。
 */
@Composable
internal fun BooklistScreen(
    collection: CollectionSummary,
    catalog: List<BookSummary>,
    library: TextbookLibrary,
    aligning: Boolean,
    busy: Boolean,
    revision: Int,
    onBack: () -> Unit,
    onOpenBook: (bookId: String, page: Int?) -> Unit,
    onAddBooks: (existingBookIds: List<String>, newFiles: List<Path>) -> Unit,
    onPickFiles: suspend () -> List<Path>,
    onRealign: () -> Unit,
    onMuteConcept: (conceptId: String, muted: Boolean) -> Unit,
    onSaveSharedNote: (conceptId: String, note: String) -> Unit,
) {
    val colors = CoachTheme.colors
    val scroll = rememberScrollState()
    val byId = remember(catalog) { catalog.associateBy { it.id } }
    val memberBooks = remember(collection.materialIds, byId) {
        collection.materialIds.mapNotNull { byId[it] }
    }
    var concepts by remember(collection.record.id) { mutableStateOf(BooklistConceptsSnapshot(emptyList(), emptyList())) }
    var chapterCountsByBook by remember(collection.record.id) { mutableStateOf<Map<String, Pair<Int, Int>>>(emptyMap()) }
    var expandedConceptIds by remember(collection.record.id) { mutableStateOf(setOf<String>()) }
    var mutedExpanded by remember(collection.record.id) { mutableStateOf(false) }
    var addDialogOpen by remember(collection.record.id) { mutableStateOf(false) }
    var moreMenuOpen by remember(collection.record.id) { mutableStateOf(false) }
    var notesStatus by remember(collection.record.id) { mutableStateOf<String?>(null) }
    var notesBusy by remember(collection.record.id) { mutableStateOf(false) }
    val notesExportGate = remember(collection.record.id) { AtomicBoolean(false) }
    val notesScope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(collection.record.id, collection.materialIds, revision, aligning) {
        val loaded = withContext(Dispatchers.IO) {
            val titles = memberBooks.associate { it.id to it.title }
            val chapterTitles = HashMap<String, Map<String, String>>()
            val counts = LinkedHashMap<String, Pair<Int, Int>>()
            for (summary in memberBooks) {
                val book = runCatching { library.book(summary.id) }.getOrNull() ?: continue
                val spans = BookChapterResolver.chapters(book)
                chapterTitles[book.id] = spans.associate { it.tocNodeId to it.title }
                val progress = library.chapterProgressMap(book.id)
                val readCount = spans.count { progress[it.tocNodeId]?.readState == ChapterReadState.READ }
                counts[book.id] = readCount to spans.size
            }
            val snap = library.booklistConcepts(collection.record.id, titles, chapterTitles)
            snap to counts
        }
        concepts = loaded.first
        chapterCountsByBook = loaded.second
    }

    val totalReadChapters = chapterCountsByBook.values.sumOf { it.first }
    val totalChapters = chapterCountsByBook.values.sumOf { it.second }
    val continueBook = memberBooks.firstOrNull()

    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = CoachV7.Space.s5, vertical = CoachV7.Space.s3)
            .testTag("booklist-screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(CoachV7.Space.s4),
            ) {
                // Header: Back + Title + Meta + Single PrimaryButton("继续读") + PaperButton("＋ 加书") + "⋯"
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    V7TextButton(
                        onClick = onBack,
                        enabled = !busy,
                        modifier = Modifier.testTag("booklist-back"),
                        ringInside = true,
                    ) { Text("← 返回") }
                    Spacer(Modifier.weight(1f))
                    PaperButton(
                        onClick = { addDialogOpen = true },
                        enabled = !busy,
                        modifier = Modifier.testTag("booklist-add-book"),
                    ) { Text("＋ 加书") }
                    Box {
                        V7TextButton(
                            onClick = { moreMenuOpen = true },
                            enabled = !busy,
                            modifier = Modifier.testTag("booklist-more-menu"),
                            ringInside = true,
                        ) { Text("⋯") }
                        DropdownMenu(
                            expanded = moreMenuOpen,
                            onDismissRequest = { moreMenuOpen = false },
                            containerColor = colors.surface1,
                        ) {
                            DropdownMenuItem(
                                text = { Text("重新整理共同主题", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary) },
                                enabled = !aligning && memberBooks.size >= 2,
                                onClick = {
                                    moreMenuOpen = false
                                    onRealign()
                                },
                                modifier = Modifier.testTag("booklist-realign"),
                            )
                            DropdownMenuItem(
                                text = { Text("复制书单笔记", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary) },
                                enabled = !notesBusy && memberBooks.isNotEmpty(),
                                onClick = {
                                    moreMenuOpen = false
                                    notesScope.launch {
                                        notesBusy = true
                                        notesStatus = "正在整理书单笔记…"
                                        val export = withContext(Dispatchers.IO) {
                                            loadBooklistNotes(library, collection.record.title, memberBooks)
                                        }
                                        notesBusy = false
                                        if (export == null) {
                                            notesStatus = "书单笔记没有整理出来。"
                                        } else {
                                            clipboard.setText(AnnotatedString(export.markdown))
                                            notesStatus = "已复制 ${export.itemCount} 条"
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("booklist-notes-copy"),
                            )
                            DropdownMenuItem(
                                text = { Text("导出书单笔记", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary) },
                                enabled = !notesBusy && memberBooks.isNotEmpty(),
                                onClick = {
                                    if (!notesExportGate.compareAndSet(false, true)) return@DropdownMenuItem
                                    moreMenuOpen = false
                                    notesScope.launch {
                                        notesBusy = true
                                        notesStatus = "正在整理书单笔记…"
                                        val export = withContext(Dispatchers.IO) {
                                            loadBooklistNotes(library, collection.record.title, memberBooks)
                                        }
                                        if (export == null) {
                                            notesBusy = false
                                            notesExportGate.set(false)
                                            notesStatus = "书单笔记没有整理出来。"
                                            return@launch
                                        }
                                        val scheduled = bookSaveNotesMarkdown(
                                            notesExportFileName(collection.record.title),
                                            export.markdown,
                                        ) { saved ->
                                            notesBusy = false
                                            notesExportGate.set(false)
                                            notesStatus = when (saved) {
                                                NotesFileSave.Busy -> return@bookSaveNotesMarkdown
                                                NotesFileSave.Cancelled -> "没有保存"
                                                is NotesFileSave.Failed -> saved.message
                                                is NotesFileSave.Saved -> "已保存 ${saved.fileName}，共 ${export.itemCount} 条。用记事本打开时中文应可读。"
                                            }
                                        }
                                        if (!scheduled) {
                                            notesBusy = false
                                            notesExportGate.set(false)
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("booklist-notes-export"),
                            )
                        }
                    }
                }

                PaperCard(
                    Modifier.fillMaxWidth().testTag("booklist-header-card"),
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
                                collection.record.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = LocalV7Fonts.current.display,
                                color = colors.fgPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("booklist-title"),
                            )
                            val meta = buildString {
                                append("${memberBooks.size} 本书")
                                if (totalChapters > 0) append(" · 已读 $totalReadChapters / $totalChapters 章")
                                append(" · ${concepts.active.size} 个共同主题")
                            }
                            CoachCardMeta(meta, Modifier.testTag("booklist-meta"))
                            notesStatus?.let { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.testTag("booklist-notes-status"))
                            }
                        }
                        if (continueBook != null) {
                            PrimaryButton(
                                onClick = { onOpenBook(continueBook.id, null) },
                                enabled = !busy,
                                modifier = Modifier.testTag("booklist-continue"),
                            ) { Text("继续读") }
                        }
                    }
                }

                // Upper section: Books in this booklist
                Column(
                    Modifier.fillMaxWidth().testTag("booklist-books-section"),
                    verticalArrangement = Arrangement.spacedBy(CoachV7.Space.s2),
                ) {
                    Text(
                        "书单书目",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.fgSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    if (memberBooks.isEmpty()) {
                        CoachEmptyState(
                            title = "这份书单还没有加入书籍。",
                            hint = "点击右上角「＋ 加书」，一次选入两本以上即可串起共同主题。",
                            actionLabel = "加入书籍",
                            onAction = { addDialogOpen = true },
                        )
                    } else {
                        for (item in memberBooks) {
                            val counts = chapterCountsByBook[item.id]
                            val shape = RoundedCornerShape(CoachV7.Radius.m)
                            PaperTile(
                                enabled = !busy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, colors.borderSubtle, shape)
                                    .testTag("booklist-book-${item.id}"),
                                onClick = { onOpenBook(item.id, null) },
                                padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                                spacing = 4.dp,
                            ) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.fgPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val bookMeta = buildString {
                                    append("第 ${item.readingPage} / ${item.pageCount.coerceAtLeast(1)} 页")
                                    if (counts != null && counts.second > 0) {
                                        append(" · 已读 ${counts.first} / ${counts.second} 章")
                                    }
                                }
                                CoachCardMeta(bookMeta)
                            }
                        }
                    }
                }

                // Lower section: Shared Concepts ("共同主题")
                Column(
                    Modifier.fillMaxWidth().testTag("booklist-concepts-section"),
                    verticalArrangement = Arrangement.spacedBy(CoachV7.Space.s2),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "共同主题（${concepts.active.size}）",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.fgSecondary,
                            modifier = Modifier.testTag("booklist-concepts-heading"),
                        )
                        if (aligning) {
                            CoachCardMeta("正在整理共同主题…")
                        }
                    }

                    when {
                        aligning && concepts.active.isEmpty() -> {
                            Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                                CoachLoadingDots(label = "正在对齐跨书共同主题…")
                            }
                        }
                        memberBooks.size < 2 -> {
                            CoachEmptyState(
                                title = "再加入一本书即可发现共同主题。",
                                hint = "书单包含两本以上书籍时，后台会自动对齐共同概念并关联已读章节。",
                                actionLabel = "加入书籍",
                                onAction = { addDialogOpen = true },
                            )
                        }
                        concepts.active.isEmpty() -> {
                            CoachEmptyState(
                                title = "暂未发现高置信跨书共同主题。",
                                hint = "可点下方按钮重新整理，或向书单加入更多相关书目。",
                                actionLabel = "整理共同主题",
                                onAction = onRealign,
                            )
                        }
                        else -> {
                            for (item in concepts.active) {
                                val expanded = item.entity.conceptId in expandedConceptIds
                                BooklistConceptCard(
                                    item = item,
                                    expanded = expanded,
                                    onToggleExpand = {
                                        expandedConceptIds = if (expanded) {
                                            expandedConceptIds - item.entity.conceptId
                                        } else {
                                            expandedConceptIds + item.entity.conceptId
                                        }
                                    },
                                    onJumpMention = { mention -> onOpenBook(mention.bookId, mention.page) },
                                    onMute = { onMuteConcept(item.entity.conceptId, true) },
                                    onSaveSharedNote = { note -> onSaveSharedNote(item.entity.conceptId, note) },
                                )
                            }
                        }
                    }
                }

                // Bottom collapsed section: Muted themes ("已静音主题")
                if (concepts.muted.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().testTag("booklist-muted-section"),
                        verticalArrangement = Arrangement.spacedBy(CoachV7.Space.s2),
                    ) {
                        HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.7f))
                        V7TextButton(
                            onClick = { mutedExpanded = !mutedExpanded },
                            modifier = Modifier.testTag("booklist-muted-toggle"),
                            ringInside = true,
                        ) {
                            Text(
                                if (mutedExpanded) "已静音主题（${concepts.muted.size}）▴"
                                else "已静音主题（${concepts.muted.size}）▾",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.fgSecondary,
                            )
                        }
                        if (mutedExpanded) {
                            for (mutedItem in concepts.muted) {
                                PaperCard(
                                    Modifier.fillMaxWidth().testTag("booklist-muted-item-${mutedItem.entity.conceptId}"),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                mutedItem.entity.canonicalName,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = colors.fgSecondary,
                                            )
                                            CoachCardMeta("${mutedItem.bookCount} 本书讨论 · 已在阅读抽屉静音")
                                        }
                                        PaperButton(
                                            onClick = { onMuteConcept(mutedItem.entity.conceptId, false) },
                                            modifier = Modifier.testTag("booklist-restore-${mutedItem.entity.conceptId}"),
                                        ) { Text("恢复") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            BookScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = CoachV7.Space.s4))
        }
    }

    if (addDialogOpen) {
        val alreadyIn = collection.materialIds.toSet()
        val availableBooks = remember(catalog, alreadyIn) { catalog.filterNot { it.id in alreadyIn } }
        BooklistAddBooksDialog(
            availableBooks = availableBooks,
            onPickFiles = onPickFiles,
            onConfirm = { existingIds, newFiles ->
                addDialogOpen = false
                onAddBooks(existingIds, newFiles)
            },
            onClose = { addDialogOpen = false },
        )
    }
}

@Composable
private fun BooklistConceptCard(
    item: BooklistConceptView,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onJumpMention: (ConceptMentionRecord) -> Unit,
    onMute: () -> Unit,
    onSaveSharedNote: (String) -> Unit,
) {
    val colors = CoachTheme.colors
    val chipShape = RoundedCornerShape(CoachV7.Radius.s)
    val noteShape = RoundedCornerShape(CoachV7.Radius.btn)
    var noteDraft by remember(item.entity.conceptId, item.entity.sharedNote) { mutableStateOf(item.entity.sharedNote) }

    PaperCard(
        Modifier.fillMaxWidth().testTag("booklist-concept-${item.entity.conceptId}"),
        color = colors.surface1,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        contentSpacing = 10.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggleExpand)
                .pointerHoverIcon(PointerIcon.Hand)
                .testTag("booklist-concept-toggle-${item.entity.conceptId}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.entity.canonicalName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.fgPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    append("${item.bookCount} 本书讨论")
                    if (item.headlineDiff.isNotBlank()) append(" · ${item.headlineDiff}")
                }
                CoachCardMeta(meta)
            }
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = colors.fgSecondary,
            )
            Text(
                "×",
                style = MaterialTheme.typography.titleSmall,
                color = colors.fgTertiary,
                modifier = Modifier
                    .clip(chipShape)
                    .clickable(role = Role.Button, onClickLabel = "静音主题 ${item.entity.canonicalName}") { onMute() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("booklist-concept-mute-${item.entity.conceptId}"),
            )
        }

        if (expanded) {
            Column(
                Modifier.fillMaxWidth().testTag("booklist-concept-detail-${item.entity.conceptId}"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.6f))
                if (item.links.isNotEmpty()) {
                    for (link in item.links.take(3)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                link.relationType.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = v7Pine(),
                                modifier = Modifier
                                    .clip(chipShape)
                                    .background(v7Pine().copy(alpha = 0.08f))
                                    .border(1.dp, v7Pine().copy(alpha = 0.28f), chipShape)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                            Text(
                                link.diffSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.fgPrimary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                for (mv in item.mentions) {
                    val m = mv.mention
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(chipShape)
                            .background(colors.surface2)
                            .border(1.dp, colors.borderSubtle, chipShape)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                mv.bookTitle,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = colors.fgPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                when (mv.readState) {
                                    ChapterReadState.READ -> "已读"
                                    ChapterReadState.PARTIAL -> "读了一半"
                                    ChapterReadState.UNREAD -> "未读"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (mv.readState == ChapterReadState.READ) v7Pine() else colors.fgTertiary,
                            )
                            Text(
                                "第 ${m.page} 页",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = LocalV7Fonts.current.numbers,
                                color = v7Pine(),
                                modifier = Modifier
                                    .clip(chipShape)
                                    .background(v7Pine().copy(alpha = 0.08f))
                                    .border(1.dp, v7Pine().copy(alpha = 0.28f), chipShape)
                                    .clickable(role = Role.Button) { onJumpMention(m) }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .testTag("booklist-jump-${m.mentionId}"),
                            )
                        }
                        if (m.claimSummary.isNotBlank()) {
                            Text(
                                m.claimSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.fgSecondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (m.quoteAnchor.isNotBlank()) {
                            Text(
                                "「${m.quoteAnchor}」",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.fgTertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Shared note editor persisted to concept_entities.shared_note
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("合并笔记", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                    BasicTextField(
                        value = noteDraft,
                        onValueChange = { next ->
                            noteDraft = next
                            onSaveSharedNote(next)
                        },
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.fgPrimary),
                        cursorBrush = SolidColor(colors.fgPrimary),
                        decorationBox = { inner ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(noteShape)
                                    .background(colors.surface2)
                                    .border(1.dp, colors.borderSubtle, noteShape)
                                    .padding(10.dp),
                            ) {
                                if (noteDraft.isEmpty()) {
                                    Text(
                                        "写下跨书对照的思考，自动保存。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.fgTertiary,
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("booklist-note-${item.entity.conceptId}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun BooklistAddBooksDialog(
    availableBooks: List<BookSummary>,
    onPickFiles: suspend () -> List<Path>,
    onConfirm: (existingBookIds: List<String>, newFiles: List<Path>) -> Unit,
    onClose: () -> Unit,
) {
    val colors = CoachTheme.colors
    val scope = rememberCoroutineScope()
    val selectedIds = remember { mutableStateListOf<String>() }
    val stagedFiles = remember { mutableStateListOf<Path>() }
    val shape = RoundedCornerShape(CoachV7.Radius.btn)

    AlertDialog(
        modifier = Modifier.testTag("booklist-add-dialog"),
        onDismissRequest = onClose,
        title = { Text("加入书籍到书单") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("导入本地书籍", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                    PaperButton(
                        onClick = {
                            scope.launch {
                                for (p in onPickFiles()) {
                                    if (p !in stagedFiles) stagedFiles.add(p)
                                }
                            }
                        },
                        modifier = Modifier.testTag("booklist-pick-files"),
                    ) { Text("选择文件") }
                }
                for ((idx, f) in stagedFiles.withIndex()) {
                    Row(
                        Modifier.fillMaxWidth().testTag("booklist-staged-$idx"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            f.fileName?.toString() ?: f.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.fgPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        V7TextButton({ stagedFiles.remove(f) }, ringInside = true) { Text("移除") }
                    }
                }
                if (availableBooks.isNotEmpty()) {
                    HorizontalDivider(color = colors.borderSubtle)
                    Text("或从已有书库勾选", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                    for (book in availableBooks) {
                        val checked = book.id in selectedIds
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .clickable { if (checked) selectedIds.remove(book.id) else selectedIds.add(book.id) }
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                .testTag("booklist-existing-${book.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(
                                book.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.fgPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = { onConfirm(selectedIds.toList(), stagedFiles.toList()) },
                enabled = selectedIds.isNotEmpty() || stagedFiles.isNotEmpty(),
                modifier = Modifier.testTag("booklist-add-confirm"),
            ) { Text("加入书单") }
        },
        dismissButton = {
            V7TextButton(onClose, modifier = Modifier.testTag("booklist-add-cancel"), ringInside = true) { Text("取消") }
        },
    )
}
