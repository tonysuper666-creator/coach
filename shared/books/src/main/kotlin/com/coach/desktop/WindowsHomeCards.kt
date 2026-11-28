package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coach.design.tokens.CoachV7
import com.coach.desktop.books.BookExplainer
import com.coach.desktop.books.BookSearchResult
import com.coach.desktop.books.BookSummary
import com.coach.desktop.books.CoachConnectivityStatus
import com.coach.desktop.books.CollectionKind
import com.coach.desktop.books.CollectionQaCitation
import com.coach.desktop.books.CollectionQaEngine
import com.coach.desktop.books.CollectionQaException
import com.coach.desktop.books.CollectionQaTurn
import com.coach.desktop.books.CollectionQaTurnView
import com.coach.desktop.books.CollectionQaVirtualCollections
import com.coach.desktop.books.CollectionSummary
import com.coach.desktop.books.ExplainTrouble
import com.coach.desktop.books.LocalCoachConnectivity
import com.coach.desktop.books.MaterialCollectionRecord
import com.coach.desktop.books.OfflineCopy
import com.coach.desktop.books.OfflineNeedsNetworkNotice
import com.coach.desktop.books.RetrievedPageLinks
import com.coach.desktop.books.PaperColor
import com.coach.desktop.books.ReadingFont
import com.coach.desktop.books.ReadingSize
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.textbookFileDrop
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** 主卡宽度上限：内栏与阅读正文栏（CoachV7.Layout.colw = 594dp）同宽，左右各留 44dp 纸面留白。 */
internal val HostSheetMaxWidth = CoachV7.Layout.colw + 88.dp

/** 测试截图用真实形状路径覆盖设置弹窗显示的目录，不改变实际书库路径。 */
internal val LocalSettingsDirectoryOverride = staticCompositionLocalOf<Path?> { null }

private fun defaultUserHome(): Path? =
    System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }

/** 设置弹窗里将用户主目录缩写成「用户文件夹」（例如「用户文件夹\Coach\books」），目录外路径保持原样。 */
internal fun formatLibraryPathForDisplay(directory: Path, userHome: Path? = defaultUserHome()): String {
    val normalizedDir = directory.toAbsolutePath().normalize()
    val normalizedHome = userHome?.toAbsolutePath()?.normalize()
    if (normalizedHome != null && normalizedDir.startsWith(normalizedHome)) {
        val relative = normalizedHome.relativize(normalizedDir).toString()
        if (relative.isEmpty()) return "用户文件夹"
        return "用户文件夹${normalizedDir.fileSystem.separator}$relative"
    }
    return normalizedDir.toString()
}

/**
 * 新建课程 / 新建书单弹层：空库可直接多选或拖入本地文件一步入库建单/建课；已有书库时可同时勾选已有书与新添文件。
 */
@Composable
internal fun CreateCollectionDialog(
    kind: CollectionKind,
    existingBooks: List<BookSummary>,
    onPickFiles: suspend () -> List<Path>,
    onConfirm: (title: String, examDate: String?, existingBookIds: List<String>, newFiles: List<Path>) -> Unit,
    onClose: () -> Unit,
    initialSelectedBookIds: List<String> = emptyList(),
) {
    val colors = CoachTheme.colors
    val scope = rememberCoroutineScope()
    val isCourse = kind == CollectionKind.COURSE
    var title by remember(kind) { mutableStateOf("") }
    var examDate by remember(kind) { mutableStateOf("") }
    val selectedBookIds = remember(kind, initialSelectedBookIds) {
        mutableStateListOf<String>().apply { addAll(initialSelectedBookIds.filter { id -> existingBooks.any { it.id == id } }) }
    }
    val stagedFiles = remember(kind) { mutableStateListOf<Path>() }
    val totalSelected = selectedBookIds.size + stagedFiles.size
    // A booklist may start empty or with a single book; books can be added later.
    val canConfirm = title.isNotBlank()
    val shape = RoundedCornerShape(CoachV7.Radius.btn)

    fun addFiles(paths: List<Path>) {
        for (p in paths) {
            if (p !in stagedFiles) stagedFiles.add(p)
        }
        if (title.isBlank() && paths.isNotEmpty()) {
            title = paths.first().fileName?.toString()?.substringBeforeLast('.')?.trim().orEmpty()
        }
    }

    AlertDialog(
        modifier = Modifier.testTag("create-collection-dialog"),
        onDismissRequest = onClose,
        title = { Text(if (isCourse) "新建课程" else "新建书单") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp)
                    .textbookFileDrop { dropped -> addFiles(dropped) }
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text(if (isCourse) "课程名" else "书单名") },
                    placeholder = { Text(if (isCourse) "如：健康经济学" else "如：信息与激励") },
                    modifier = Modifier.fillMaxWidth().testTag("collection-title-input"),
                )
                if (isCourse) {
                    OutlinedTextField(
                        value = examDate,
                        onValueChange = { examDate = it },
                        singleLine = true,
                        label = { Text("考试日期（可选）") },
                        placeholder = { Text("如：2027-01-15") },
                        modifier = Modifier.fillMaxWidth().testTag("collection-exam-date-input"),
                    )
                }
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(colors.surface1)
                        .border(1.dp, colors.borderSubtle, shape).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (isCourse) "添加本地材料（支持多选或拖入）" else "选入本地书籍（可先空着）",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.fgSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        PaperButton(
                            onClick = { scope.launch { addFiles(onPickFiles()) } },
                            modifier = Modifier.testTag("collection-pick-files"),
                        ) { Text("选择文件") }
                    }
                    if (stagedFiles.isEmpty()) {
                        Text(
                            "把文件直接拖到这里，或点击「选择文件」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.fgTertiary,
                        )
                    } else {
                        stagedFiles.forEachIndexed { idx, file ->
                            Row(
                                Modifier.fillMaxWidth().testTag("collection-staged-file-$idx"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    file.fileName?.toString() ?: file.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.fgPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                V7TextButton(
                                    onClick = { stagedFiles.remove(file) },
                                    ringInside = true,
                                ) { Text("移除", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
                if (existingBooks.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("或从已有书库勾选", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                        existingBooks.forEach { item ->
                            val checked = item.id in selectedBookIds
                            Row(
                                Modifier.fillMaxWidth().clip(shape)
                                    .clickable {
                                        if (checked) selectedBookIds.remove(item.id) else selectedBookIds.add(item.id)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                    .testTag("collection-existing-${item.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.fgPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (!isCourse && totalSelected < 2) {
                    Text(
                        "一本也能建；空书单也行，之后可以继续往里面加书。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fgSecondary,
                        modifier = Modifier.testTag("collection-booklist-hint"),
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    onConfirm(
                        title.trim(),
                        examDate.trim().takeIf(String::isNotEmpty),
                        selectedBookIds.toList(),
                        stagedFiles.toList(),
                    )
                },
                enabled = canConfirm,
                modifier = Modifier.testTag("collection-confirm"),
            ) { Text(if (isCourse) "创建课程" else "创建书单") }
        },
        dismissButton = {
            V7TextButton(onClose, modifier = Modifier.testTag("collection-cancel"), ringInside = true) { Text("取消") }
        },
    )
}

/** 顶栏「搜索」（Ctrl/Cmd+K）：搜全库教材、课程与书单，并支持直接向课程或已读书目跨材料提问，底部可切换查看全部已导入文件。 */
@Composable
internal fun HostSearchDialog(
    library: TextbookLibrary,
    catalog: List<BookSummary>,
    collections: List<CollectionSummary>,
    onOpenBook: (bookId: String, page: Int?) -> Unit,
    onOpenCollection: (CollectionSummary) -> Unit,
    onClose: () -> Unit,
    explainer: BookExplainer? = null,
    preferredCollectionId: String? = null,
    onJumpCollectionCitation: ((CollectionQaCitation) -> Unit)? = null,
) {
    val colors = CoachTheme.colors
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var showAllFiles by remember { mutableStateOf(false) }
    var bookMatches by remember { mutableStateOf<List<Pair<BookSummary, BookSearchResult>>>(emptyList()) }
    val askableCollections = remember(collections, catalog) {
        val explicit = collections.filter {
            !it.record.archived && (it.record.kind == CollectionKind.COURSE || it.record.kind == CollectionKind.BOOKLIST)
        }
        val hasBooklist = explicit.any { it.record.kind == CollectionKind.BOOKLIST }
        val hasUncoveredBooks = catalog.any { book -> explicit.none { book.id in it.materialIds } }
        if (catalog.isNotEmpty() && (!hasBooklist || hasUncoveredBooks)) {
            explicit + CollectionSummary(
                record = MaterialCollectionRecord(
                    id = CollectionQaVirtualCollections.READ_LIBRARY_ID,
                    kind = CollectionKind.BOOKLIST,
                    title = CollectionQaVirtualCollections.READ_LIBRARY_TITLE,
                ),
                materialIds = catalog.map { it.id },
            )
        } else {
            explicit
        }
    }
    var selectedCollectionId by remember(askableCollections, preferredCollectionId) {
        mutableStateOf(
            preferredCollectionId?.takeIf { id -> askableCollections.any { it.record.id == id } }
                ?: askableCollections.firstOrNull()?.record?.id,
        )
    }
    val selectedCollection = askableCollections.firstOrNull { it.record.id == selectedCollectionId }
    val offline = LocalCoachConnectivity.current == CoachConnectivityStatus.OFFLINE
    var qaLoading by remember { mutableStateOf(false) }
    var qaError by remember(selectedCollectionId) { mutableStateOf<CollectionQaException?>(null) }
    var qaTurns by remember(selectedCollectionId) { mutableStateOf<List<CollectionQaTurn>>(emptyList()) }

    fun submitCollectionAsk() {
        val col = selectedCollection ?: return
        val q = query.trim()
        if (q.length !in 2..600 || qaLoading) return
        qaLoading = !offline
        qaError = null
        scope.launch {
            val activeExplainer = explainer ?: BookExplainer(library = library)
            val res = CollectionQaEngine.ask(
                library = library,
                collectionId = col.record.id,
                question = q,
                remoteAsk = { payload ->
                    if (!activeExplainer.loaded) activeExplainer.load()
                    activeExplainer.askCollection(payload)
                },
            )
            qaLoading = false
            res.fold(
                onSuccess = { turn -> qaTurns = qaTurns + turn },
                onFailure = { err ->
                    val qaErr = err as? CollectionQaException
                    qaError = when {
                        qaErr?.trouble == ExplainTrouble.Unreachable -> CollectionQaException(
                            OfflineCopy.COLLECTION_ASK_OFFLINE,
                            ExplainTrouble.Unreachable,
                            qaErr.fallbackPages,
                        )
                        qaErr != null -> qaErr
                        else -> CollectionQaException("这次没讲成，请重试。", ExplainTrouble.Other)
                    }
                },
            )
        }
    }

    LaunchedEffect(query, catalog) {
        qaError = null
        val q = query.trim()
        bookMatches = if (q.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { library.searchAllBooks(q, limitPerBook = 3) }
        }
    }

    val matchingCollections = remember(query, collections) {
        val q = query.trim()
        if (q.isEmpty()) emptyList() else collections.filter { it.record.title.contains(q, ignoreCase = true) }
    }

    AlertDialog(
        modifier = Modifier
            .testTag("host-search-dialog")
            // Esc closes the search box. It stays a modal because the user opened it on purpose.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose()
                    true
                } else false
            },
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        title = { Text("搜索与跨材料提问") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("输入关键词搜全库，或直接提问课程/已读书目…") },
                    modifier = Modifier.fillMaxWidth().testTag("host-search-input"),
                )
                if (selectedCollection != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (col in askableCollections) {
                                val active = col.record.id == selectedCollection.record.id
                                V7TextButton(
                                    onClick = { selectedCollectionId = col.record.id },
                                    modifier = Modifier.testTag("host-search-col-${col.record.id}"),
                                ) {
                                    Text(
                                        col.record.title,
                                        color = if (active) colors.actionPrimary else colors.fgSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (offline) {
                            OfflineNeedsNetworkNotice(
                                message = OfflineCopy.COLLECTION_ASK_OFFLINE,
                                tag = "host-search-offline-notice",
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PaperButton(
                                onClick = ::submitCollectionAsk,
                                enabled = !qaLoading && query.trim().length in 2..600,
                                modifier = Modifier.testTag("host-search-ask-collection"),
                            ) {
                                val baseLabel = when {
                                    qaError?.trouble?.retry == true -> "重试"
                                    selectedCollection.record.id == CollectionQaVirtualCollections.READ_LIBRARY_ID ->
                                        "问「已读书目」"
                                    selectedCollection.record.kind == CollectionKind.BOOKLIST ->
                                        "问「${selectedCollection.record.title}」已读书目"
                                    else ->
                                        "问「${selectedCollection.record.title}」整门课"
                                }
                                Text(
                                    if (offline && qaError?.trouble?.retry != true) "$baseLabel（需要联网）" else baseLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (qaLoading) {
                                Text(
                                    "正在核对原页…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.fgSecondary,
                                    modifier = Modifier.testTag("host-search-qa-loading"),
                                )
                            }
                        }
                        qaError?.let { error ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    error.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.accentDeep,
                                    modifier = Modifier.weight(1f).testTag("host-search-qa-error"),
                                )
                                if (error.trouble.retry) {
                                    V7TextButton(
                                        onClick = ::submitCollectionAsk,
                                        modifier = Modifier.testTag("host-search-qa-retry"),
                                        ringInside = true,
                                    ) { Text("重试", maxLines = 1, softWrap = false) }
                                }
                            }
                            RetrievedPageLinks(error.fallbackPages, { "${it.shortLabel} · 第 ${it.page} 页" }) { cit ->
                                if (onJumpCollectionCitation != null) onJumpCollectionCitation(cit)
                                else onOpenBook(cit.bookId, cit.page)
                            }
                        }
                        for ((idx, turn) in qaTurns.withIndex()) {
                            CollectionQaTurnView(
                                turn = turn,
                                turnIndex = idx,
                                tagPrefix = "host-search-qa",
                                onOpenCitation = { cit ->
                                    if (onJumpCollectionCitation != null) onJumpCollectionCitation(cit)
                                    else onOpenBook(cit.bookId, cit.page)
                                },
                            )
                            HorizontalDivider(color = colors.borderSubtle)
                        }
                    }
                }
                if (query.isNotBlank()) {
                    if (matchingCollections.isEmpty() && bookMatches.isEmpty()) {
                        Text("没找到匹配内容。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                    } else {
                        for (col in matchingCollections) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenCollection(col) }.padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(col.record.title, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                                CoachCardMeta(if (col.record.kind == CollectionKind.COURSE) "课程" else "书单")
                            }
                        }
                        for ((summary, result) in bookMatches) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { onOpenBook(summary.id, result.hits.firstOrNull()?.page) }
                                    .padding(vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(summary.title, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                                val firstHit = result.hits.firstOrNull()
                                CoachCardMeta(
                                    if (firstHit != null) "第 ${firstHit.page} 页 · ${firstHit.snippet}"
                                    else "${summary.pageCount} 页 · 读到第 ${summary.readingPage} 页",
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = colors.borderSubtle)
                V7TextButton(
                    onClick = { showAllFiles = !showAllFiles },
                    modifier = Modifier.testTag("host-search-all-files"),
                    ringInside = true,
                ) {
                    Text(if (showAllFiles) "收起全部已导入文件（${catalog.size}）" else "全部已导入文件（${catalog.size}）")
                }
                if (showAllFiles) {
                    for (item in catalog) {
                        Column(
                            Modifier.fillMaxWidth().clickable { onOpenBook(item.id, null) }.padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                            CoachCardMeta("${item.pageCount} 页 · 读到第 ${item.readingPage} 页")
                        }
                    }
                }
            }
        },
        confirmButton = {
            PaperButton(onClose, modifier = Modifier.testTag("host-search-close")) { Text("关闭") }
        },
    )
}

/** 课程 / 书单材料清单弹层：一键打开任意所属材料。 */
@Composable
internal fun CollectionDetailDialog(
    summary: CollectionSummary,
    catalog: List<BookSummary>,
    onOpenBook: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = CoachTheme.colors
    val byId = remember(catalog) { catalog.associateBy { it.id } }
    val items = remember(summary.materialIds, byId) { summary.materialIds.mapNotNull { byId[it] } }
    val label = if (summary.record.kind == CollectionKind.COURSE) "课程" else "书单"

    AlertDialog(
        modifier = Modifier.testTag("collection-detail-dialog"),
        onDismissRequest = onClose,
        title = { Text(summary.record.title) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val meta = buildString {
                    append(label)
                    summary.record.examDate?.takeIf(String::isNotBlank)?.let { append(" · 考试 $it") }
                    append(" · ${items.size} 份材料")
                }
                CoachCardMeta(meta)
                if (items.isEmpty()) {
                    Text("还没有加入材料。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                } else {
                    for (item in items) {
                        Column(
                            Modifier.fillMaxWidth().clickable { onOpenBook(item.id) }.padding(vertical = 6.dp)
                                .testTag("collection-material-${item.id}"),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                            CoachCardMeta("${item.pageCount} 页 · 读到第 ${item.readingPage} 页")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (items.isNotEmpty()) {
                PrimaryButton(onClick = { onOpenBook(items.first().id) }) { Text("继续阅读") }
            } else {
                PaperButton(onClose) { Text("知道了") }
            }
        },
        dismissButton = if (items.isNotEmpty()) {
            { V7TextButton(onClose, ringInside = true) { Text("关闭") } }
        } else null,
    )
}

@Composable
private fun SettingsSection(title: String, tag: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = CoachTheme.colors.fgPrimary,
        modifier = Modifier.padding(top = 4.dp).testTag(tag),
    )
}

@Composable
private fun <T> SettingsChoice(label: String, options: List<T>, selected: T, name: (T) -> String, tag: String, onChoose: (T) -> Unit) {
    val colors = CoachTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary, modifier = Modifier.weight(1f))
        options.forEachIndexed { index, option ->
            V7TextButton(onClick = { onChoose(option) }, modifier = Modifier.testTag("$tag-$index"), ringInside = true) {
                Text(
                    name(option),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (option == selected) colors.fgPrimary else colors.fgTertiary,
                    fontWeight = if (option == selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = CoachTheme.colors.fgPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/**
 * V7 单页四分区设置（UX-14）：账号与连接 / 阅读与外观 / 学习默认项 / 数据与关于。
 */
internal val LocalOpenAccountSettings = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalAccountSettings = androidx.compose.runtime.staticCompositionLocalOf<(@Composable () -> Unit)?> { null }
internal val LocalDataSettings = androidx.compose.runtime.staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

@Composable
internal fun HostSettings(
    directory: Path,
    onPersonalCourse: (() -> Unit)? = null,
    preferences: com.coach.desktop.books.ReadingSettings? = null,
    onRollback: (() -> Unit)? = null,
    rollbackStatus: String = "",
    rollbackBusy: Boolean = false,
    onClose: () -> Unit,
) {
    val colors = CoachTheme.colors
    val paper = RoundedCornerShape(CoachV7.Radius.btn)
    val effectiveDirectory = LocalSettingsDirectoryOverride.current ?: directory
    val fullPath = remember(effectiveDirectory) { effectiveDirectory.toAbsolutePath().normalize().toString() }
    val displayPath = remember(effectiveDirectory) { formatLibraryPathForDisplay(effectiveDirectory) }
    val abbreviated = displayPath != fullPath
    var showFullPath by remember(fullPath) { mutableStateOf(false) }
    var copied by remember(fullPath) { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val incidents = LocalIncidentFolderActions.current

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (LocalAccountSettings.current == null) "设置" else "账号与设置") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).testTag("settings-page"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsSection("账号与连接", "settings-section-account")
                CompositionLocalProvider(LocalContentColor provides colors.fgPrimary) {
                    LocalAccountSettings.current?.invoke()
                }
                Text(
                    "教材和进度保存在本地设备。提问与讲解时仅发送当前所选片段或本节正文至学习服务。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.fgPrimary,
                )
                if (onPersonalCourse != null) {
                    V7TextButton(onPersonalCourse, ringInside = true) { Text("打开已配对课程") }
                }
                if (preferences != null) {
                    SettingsSection("阅读与外观", "settings-section-reading")
                    SettingsChoice("字号", ReadingSize.entries, preferences.size, { it.label }, "settings-size") { preferences.chooseSize(it) }
                    SettingsChoice("字体", ReadingFont.entries, preferences.font, { it.label }, "settings-font") { preferences.chooseFont(it) }
                    SettingsChoice("纸色", PaperColor.entries, preferences.paper, { it.label }, "settings-paper") { preferences.choosePaper(it) }
                    SettingsSection("学习默认项", "settings-section-learning")
                    SettingsSwitch("读完一节先给导读", preferences.autoGuide, "settings-auto-guide") { preferences.chooseAutoGuide(it) }
                    SettingsSwitch("每节读完给小结", preferences.sectionSummary, "settings-section-summary") { preferences.chooseSectionSummary(it) }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("伴读讲解与提问", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                            Text(
                                if (preferences.aiExplain) "已开启，可在阅读时使用讲解与提问。" else "已关闭，仅本地阅读不发送正文。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.fgSecondary,
                            )
                        }
                        Switch(
                            checked = preferences.aiExplain,
                            onCheckedChange = { preferences.chooseAiExplain(it) },
                            modifier = Modifier.testTag("settings-ai-toggle"),
                        )
                    }
                }
                SettingsSection("数据与关于", "settings-section-data")
                CompositionLocalProvider(LocalContentColor provides colors.fgPrimary) {
                    LocalDataSettings.current?.invoke()
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("教材位置", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                    SelectionContainer {
                        Text(
                            displayPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.fgSecondary,
                            fontFamily = LocalV7Fonts.current.numbers,
                            modifier = Modifier.fillMaxWidth().clip(paper).background(colors.surface1)
                                .border(1.dp, colors.borderSubtle, paper)
                                .padding(horizontal = 12.dp, vertical = 8.dp).testTag("settings-library-directory"),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        V7TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(fullPath))
                                copied = true
                            },
                            modifier = Modifier.testTag("settings-library-copy"),
                            ringInside = true,
                        ) {
                            Text(if (copied) "已复制完整路径" else "复制完整路径", style = MaterialTheme.typography.labelSmall, color = colors.fgSecondary)
                        }
                        if (abbreviated) {
                            V7TextButton(
                                onClick = { showFullPath = !showFullPath },
                                modifier = Modifier.testTag("settings-library-toggle-full"),
                                ringInside = true,
                            ) {
                                Text(if (showFullPath) "收起完整路径" else "查看完整路径", style = MaterialTheme.typography.labelSmall, color = colors.fgTertiary)
                            }
                        }
                    }
                    if (abbreviated && showFullPath) {
                        SelectionContainer {
                            Text(
                                fullPath,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.fgTertiary,
                                fontFamily = LocalV7Fonts.current.numbers,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("settings-library-full-path"),
                            )
                        }
                    }
                    if (onRollback != null) {
                        V7TextButton(
                            onClick = onRollback,
                            enabled = !rollbackBusy,
                            modifier = Modifier.testTag("settings-rollback"),
                            ringInside = true,
                        ) { Text("回到上一版") }
                        if (rollbackStatus.isNotEmpty()) {
                            Text(
                                rollbackStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.fgSecondary,
                                modifier = Modifier.testTag("settings-rollback-status"),
                            )
                        }
                    }
                }
                if (incidents != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "问题记录只留在这台电脑。只有你点发送，才会交出去。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.fgSecondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            V7TextButton(
                                onClick = incidents.openFolder,
                                modifier = Modifier.testTag("settings-incident-open"),
                                ringInside = true,
                            ) {
                                Text("打开记录文件夹", style = MaterialTheme.typography.labelSmall, color = colors.fgSecondary)
                            }
                            V7TextButton(
                                onClick = { confirmClear = true },
                                modifier = Modifier.testTag("settings-incident-clear"),
                                ringInside = true,
                            ) {
                                Text("清空记录", style = MaterialTheme.typography.labelSmall, color = colors.fgSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            PaperButton(onClose, modifier = Modifier.testTag("close-settings")) { Text("知道了") }
        },
    )
    if (confirmClear && incidents != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空问题记录？") },
            text = { Text("这台电脑上的问题记录会删掉，不会发给任何人。") },
            confirmButton = {
                TextButton(
                    {
                        incidents.clearRecords()
                        confirmClear = false
                    },
                    Modifier.testTag("settings-incident-clear-confirm"),
                ) { Text("清空记录") }
            },
            dismissButton = {
                TextButton({ confirmClear = false }, Modifier.testTag("settings-incident-clear-cancel")) { Text("取消") }
            },
        )
    }
}
