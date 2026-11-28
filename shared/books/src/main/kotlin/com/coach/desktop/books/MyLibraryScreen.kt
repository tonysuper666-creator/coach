package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coach.protocol.BookStance
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachLoadingDots
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

private enum class LibraryTimeWindow(val label: String, val days: Long?, val tag: String) {
    ALL("全部时间", null, "library-time-ALL"),
    WEEK("近 7 天", 7, "library-time-WEEK"),
    MONTH("近 30 天", 30, "library-time-MONTH"),
    YEAR("近一年", 365, "library-time-YEAR"),
}

@Composable
internal fun MyLibraryScreen(
    library: TextbookLibrary,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    clock: Clock = Clock.systemUTC(),
    collections: List<CollectionSummary> = emptyList(),
    onAskCollection: (suspend (JsonObject) -> CourseServerCallResult)? = null,
    onJumpCollectionCitation: ((CollectionQaCitation) -> Unit)? = null,
) {
    val colors = CoachTheme.colors
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val fallbackExplainer = remember(library, onAskCollection) {
        if (onAskCollection == null) BookExplainer(library = library) else null
    }
    var qaDraft by remember { mutableStateOf("") }
    var qaCollectionId by remember { mutableStateOf(CollectionQaVirtualCollections.READ_LIBRARY_ID) }
    var qaLoading by remember { mutableStateOf(false) }
    var qaError by remember { mutableStateOf<String?>(null) }
    var qaTurns by remember { mutableStateOf<List<CollectionQaTurn>>(emptyList()) }
    var qaPanelOpen by remember { mutableStateOf(false) }
    val qaResultScroll = rememberScrollState()

    fun submitLibraryAsk(questionText: String = qaDraft, overrideStance: BookStance? = null) {
        val clean = questionText.trim()
        if (clean.isEmpty() || qaLoading) return
        qaLoading = true
        qaError = null
        qaPanelOpen = true
        scope.launch {
            val res = CollectionQaEngine.ask(
                library = library,
                collectionId = qaCollectionId,
                question = clean,
                overrideStance = overrideStance,
                remoteAsk = { payload ->
                    if (onAskCollection != null) {
                        onAskCollection(payload)
                    } else {
                        val exp = checkNotNull(fallbackExplainer)
                        if (!exp.loaded) exp.load()
                        exp.askCollection(payload)
                    }
                },
            )
            qaLoading = false
            res.fold(
                onSuccess = { turn ->
                    qaTurns = qaTurns + turn
                    if (overrideStance == null && qaDraft.trim() == clean) {
                        qaDraft = ""
                    }
                },
                onFailure = { err ->
                    qaError = err.message ?: "跨书提问暂时不可用，请重试。"
                },
            )
        }
    }

    var text by remember { mutableStateOf("") }
    var bookTitle by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<LibraryKind?>(null) }
    var time by remember { mutableStateOf(LibraryTimeWindow.ALL) }
    var ready by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<LibraryRecord>>(emptyList()) }
    var obsidian by remember { mutableStateOf(ObsidianSyncView()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(reload) {
        failed = null
        val synced = withContext(Dispatchers.IO) {
            runCatching { openLibraryCorpus(library.directory).use { syncLibraryCorpus(it) } }
        }
        failed = synced.exceptionOrNull()?.let { "库没有整理好" }
        ready = true
    }
    LaunchedEffect(ready, text, bookTitle, kind, time, reload) {
        if (!ready || failed != null) return@LaunchedEffect
        searching = true
        val since = time.days?.let { clock.millis() - it * 86_400_000L }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                openLibraryCorpus(library.directory).use { db ->
                    searchLibrary(
                        db,
                        LibraryQuery(
                            text = text,
                            bookTitleContains = bookTitle.trim().ifBlank { null },
                            kind = kind,
                            sinceMs = since,
                        ),
                    ) to loadObsidianSyncView(db)
                }
            }
        }
        searching = false
        found.onSuccess {
            hits = it.first
            obsidian = it.second
        }.onFailure { failed = "没有搜成" }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bgCanvas)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onBack()
                    true
                } else false
            }
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("my-library"),
    ) {
        Row(Modifier.fillMaxWidth().widthIn(max = 880.dp), verticalAlignment = Alignment.CenterVertically) {
            V7TextButton(onBack, modifier = Modifier.testTag("library-back"), ringInside = true) { Text("← 返回书架") }
            Text(
                "我的库",
                modifier = Modifier.padding(horizontal = 12.dp).weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = colors.fgPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            V7TextButton(
                onClick = {
                    when (bookPickObsidianDirectory { path ->
                        scope.launch {
                            if (path == null) {
                                status = "没有选择文件夹"
                                return@launch
                            }
                            status = "正在写入…"
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching {
                                    openLibraryCorpus(library.directory).use { db ->
                                        syncLibraryCorpus(db)
                                        val outcome = exportObsidianVault(path, listLibraryRecords(db), listLibraryBooks(db), Instant.now(clock))
                                        if (outcome is ObsidianExportOutcome.Done) rememberObsidianExportBaseline(path, db)
                                        outcome
                                    }
                                }
                            }
                            status = outcome.fold(
                                onSuccess = { result ->
                                    when (result) {
                                        is ObsidianExportOutcome.Refused -> result.message
                                        is ObsidianExportOutcome.Done -> when {
                                            result.written == 0 && result.skipped == 0 -> "没有要导出的批注、问答、错题、对比或说法"
                                            else -> "已写入 ${result.written} 个文件，${result.skipped} 个没有变化"
                                        }
                                    }
                                },
                                onFailure = { "没有写成文件" },
                            )
                        }
                    }) {
                        ObsidianPick.Scheduled -> status = "正在选择文件夹…"
                        ObsidianPick.Busy -> status = "文件夹窗口已经开着"
                        ObsidianPick.Unsupported -> status = "导出到 Obsidian 目前只在 Windows 上用"
                    }
                },
                modifier = Modifier.testTag("library-export"),
                ringInside = true,
            ) { Text("导出到 Obsidian") }
            V7TextButton(
                onClick = {
                    when (bookPickObsidianDirectory(
                        dialogTitle = "选择 Obsidian 库",
                        approveButtonText = "同步这个库",
                    ) { path ->
                        scope.launch {
                            if (path == null) {
                                status = "没有选择文件夹"
                                return@launch
                            }
                            status = "正在同步…"
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching {
                                    openLibraryCorpus(library.directory).use { db ->
                                        syncObsidianVault(path, db)
                                    }
                                }
                            }
                            status = outcome.fold(
                                onSuccess = { result ->
                                    when (result) {
                                        is ObsidianSyncOutcome.Refused -> result.message
                                        is ObsidianSyncOutcome.Done -> obsidianSyncStatus(result.report)
                                    }
                                },
                                onFailure = { "没有同步成" },
                            )
                            reload += 1
                        }
                    }) {
                        ObsidianPick.Scheduled -> status = "正在选择文件夹…"
                        ObsidianPick.Busy -> status = "文件夹窗口已经开着"
                        ObsidianPick.Unsupported -> status = "和 Obsidian 同步目前只在 Windows 上用"
                    }
                },
                modifier = Modifier.padding(start = 8.dp).testTag("library-sync"),
                ringInside = true,
            ) { Text("从 Obsidian 同步") }
        }
        if (status != null) {
            Text(status.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.padding(top = 6.dp).testTag("library-status"))
        }
        // 库里跨书提问框：默认直接对全部书提问，不出弹窗、不拦路、不抢焦点；回答出在下方结果区。
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 880.dp)
                .padding(top = 10.dp)
                .testTag("library-qa-box"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val askTargets = remember(collections) {
                collections.filter { !it.record.archived && (it.record.kind == CollectionKind.BOOKLIST || it.record.kind == CollectionKind.COURSE) }
            }
            if (askTargets.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip("全部书", qaCollectionId == CollectionQaVirtualCollections.READ_LIBRARY_ID, "library-qa-scope-all") {
                        qaCollectionId = CollectionQaVirtualCollections.READ_LIBRARY_ID
                    }
                    for (col in askTargets) {
                        FilterChip(col.record.title, qaCollectionId == col.record.id, "library-qa-scope-${col.record.id}") {
                            qaCollectionId = col.record.id
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryField(
                    value = qaDraft,
                    onValueChange = { qaDraft = it },
                    placeholder = "向我的库跨书提问（按各书立场作答，每句可点回原文）…",
                    tag = "library-qa-input",
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed &&
                                qaDraft.isNotBlank() && !qaLoading
                            ) {
                                submitLibraryAsk()
                                true
                            } else false
                        },
                )
                PaperButton(
                    onClick = { submitLibraryAsk() },
                    enabled = qaDraft.isNotBlank() && !qaLoading,
                    modifier = Modifier.testTag("library-qa-send"),
                ) { Text("提问") }
                if (!qaPanelOpen && qaTurns.isNotEmpty()) {
                    V7TextButton(
                        onClick = { qaPanelOpen = true },
                        modifier = Modifier.testTag("library-qa-history-toggle"),
                        ringInside = true,
                    ) { Text("历史回答 (${qaTurns.size})") }
                }
            }
            if (qaPanelOpen && (qaLoading || qaError != null || qaTurns.isNotEmpty())) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface1)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                        .testTag("library-qa-results"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (qaLoading) "正在跨书核对原页…" else "跨书回答（${qaTurns.size}）",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.fgSecondary,
                        )
                        Text(
                            "关闭",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.fgSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(role = Role.Button) { qaPanelOpen = false }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("library-qa-close"),
                        )
                    }
                    qaError?.let { errMsg ->
                        Text(
                            errMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = v7Alert(),
                            modifier = Modifier.testTag("library-qa-error"),
                        )
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(qaResultScroll),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        qaTurns.forEachIndexed { idx, turn ->
                            CollectionQaTurnView(
                                turn = turn,
                                turnIndex = idx,
                                tagPrefix = "library-qa",
                                onOpenCitation = { cit ->
                                    if (onJumpCollectionCitation != null) {
                                        onJumpCollectionCitation(cit)
                                    } else {
                                        if (cit.endUtf16 > cit.startUtf16 && cit.quoteAnchor.isNotBlank()) {
                                            library.stageQuoteJump(
                                                BookPassage(cit.bookId, cit.page, cit.startUtf16, cit.endUtf16, cit.quoteAnchor),
                                            )
                                        }
                                        onOpenBook(cit.bookId)
                                    }
                                },
                                onSwitchStance = { nextStance ->
                                    submitLibraryAsk(questionText = turn.question, overrideStance = nextStance)
                                },
                                switchEnabled = !qaLoading,
                                onClose = { qaPanelOpen = false },
                            )
                            if (idx < qaTurns.lastIndex) {
                                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }
        LibraryField(
            value = text,
            onValueChange = { text = it },
            placeholder = "搜批注、问题、错题、对比、提纲和说法",
            tag = "library-search",
            modifier = Modifier.padding(top = 12.dp).focusRequester(focus),
        )
        LibraryField(
            value = bookTitle,
            onValueChange = { bookTitle = it },
            placeholder = "只看某一本书，留空就是全部",
            tag = "library-book-filter",
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip("全部类型", kind == null, "library-kind-ALL") { kind = null }
            for (item in LibraryKind.entries) {
                FilterChip(item.label, kind == item, "library-kind-${item.name}") { kind = item }
            }
            for (item in LibraryTimeWindow.entries) {
                FilterChip(item.label, time == item, item.tag) { time = item }
            }
        }
        Text(
            when {
                !ready || searching -> "正在找…"
                failed != null -> failed.orEmpty()
                else -> "共 ${hits.size} 条" + if (time != LibraryTimeWindow.ALL) " · 没记下时间的只在「全部时间」里" else ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.fgSecondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).testTag(if (failed != null) "library-failed" else "library-count"),
        )
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        fun shows(note: ObsidianLooseNote): Boolean = tokens.all { token ->
            note.title.contains(token, ignoreCase = true) || note.body.contains(token, ignoreCase = true)
        }
        if (obsidian.conflicts.isNotEmpty()) {
            Column(Modifier.widthIn(max = 880.dp).padding(bottom = 8.dp).testTag("library-obsidian-conflict"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (conflict in obsidian.conflicts) {
                    PaperCard(color = colors.surface1) {
                        Text("两边都改了，两份都留着", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                        Text(conflict.title, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("库里：${conflict.coachText}", style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("Obsidian：${conflict.obsidianText}", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val unmatched = obsidian.unmatched.filter(::shows)
        if (unmatched.isNotEmpty()) {
            Column(Modifier.widthIn(max = 880.dp).padding(bottom = 8.dp).testTag("library-obsidian-unmatched")) {
                Text("这些笔记没有挂上", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                for (note in unmatched) {
                    Text(note.title, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val conceptOnly = obsidian.conceptOnly.filter(::shows)
        if (conceptOnly.isNotEmpty()) {
            Column(Modifier.widthIn(max = 880.dp).padding(bottom = 8.dp).testTag("library-obsidian-concept")) {
                Text("挂到概念上，没有原句", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                for (note in conceptOnly) {
                    Text("${note.title} · ${note.body}", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (obsidian.deletedTitles.isNotEmpty()) {
            Text(
                "已在 Obsidian 删除：" + obsidian.deletedTitles.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
                modifier = Modifier.padding(bottom = 8.dp).testTag("library-obsidian-removed"),
            )
        }
        when {
            !ready && failed == null -> CoachLoadingDots(label = "正在整理我的库…", modifier = Modifier.testTag("library-loading"))
            failed == null && hits.isEmpty() && !searching -> Text(
                "还没有对得上的批注、高亮、问答、错题、对比、提纲或说法。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgSecondary,
                modifier = Modifier.testTag("library-empty"),
            )
            else -> LazyColumn(Modifier.weight(1f).widthIn(max = 880.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hits, key = { it.key }) { hit ->
                    LibraryHitCard(hit, deletedInObsidian = hit.key in obsidian.deletedKeys) {
                        stageLibraryJump(library, hit)
                        onOpenBook(hit.bookId)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fgPrimary),
        cursorBrush = SolidColor(colors.fgPrimary),
        modifier = modifier.fillMaxWidth().widthIn(max = 880.dp).testTag(tag),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(shape).background(colors.surface1).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.fgTertiary)
                }
                inner()
            }
        },
    )
}

@Composable
private fun FilterChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = CoachTheme.colors
    Text(
        label,
        color = if (selected) colors.surface1 else colors.fgPrimary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.fgPrimary else colors.surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(tag),
    )
}

@Composable
private fun LibraryHitCard(hit: LibraryRecord, deletedInObsidian: Boolean, onOpen: () -> Unit) {
    val colors = CoachTheme.colors
    PaperCard(
        Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onOpen)
            .testTag(libraryHitTag(hit.key)),
        color = colors.surface1,
    ) {
        Text(
            buildString {
                append(hit.kind.label)
                append(" · ")
                append(hit.bookTitle)
                append(" · ")
                append(notesPageLabel(hit.page))
                if (libraryCitePending(hit)) append(" · 出处待查")
                append(" · ")
                append(libraryWhenLabel(hit.atMs))
                if (deletedInObsidian) append(" · 已在 Obsidian 删除")
            },
            style = MaterialTheme.typography.labelMedium,
            color = colors.fgSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(hit.primary, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (hit.quote.isNotBlank() && hit.quote != hit.primary) {
            Text(
                "「${hit.quote}」",
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun libraryHitTag(key: String): String = "library-hit-" + key.replace(Regex("[^A-Za-z0-9_-]"), "-")

private fun libraryCitePending(hit: LibraryRecord): Boolean =
    libraryDetailValues(hit.detail)[LibraryStudyFields.CITE]?.any { COMPARISON_PENDING in it } == true

private fun libraryWhenLabel(atMs: Long?): String {
    if (atMs == null || atMs <= 0L) return "时间未记下"
    val date = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${date.year}.${date.monthValue}.${date.dayOfMonth}"
}
