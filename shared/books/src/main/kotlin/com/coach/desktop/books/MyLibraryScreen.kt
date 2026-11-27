package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachLoadingDots
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) {
    val colors = CoachTheme.colors
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    var bookTitle by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<LibraryKind?>(null) }
    var time by remember { mutableStateOf(LibraryTimeWindow.ALL) }
    var ready by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<LibraryRecord>>(emptyList()) }
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
                    )
                }
            }
        }
        searching = false
        found.onSuccess { hits = it }.onFailure { failed = "没有搜成" }
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
                                        exportObsidianVault(path, listLibraryRecords(db), listLibraryBooks(db), Instant.now(clock))
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
        }
        if (status != null) {
            Text(status.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.padding(top = 6.dp).testTag("library-status"))
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
                    LibraryHitCard(hit) {
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
private fun LibraryHitCard(hit: LibraryRecord, onOpen: () -> Unit) {
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
