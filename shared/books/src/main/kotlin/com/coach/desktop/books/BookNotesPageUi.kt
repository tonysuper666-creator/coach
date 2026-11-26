package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
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

@Composable
internal fun BookNotesPageOverlay(
    bookId: String,
    bookTitle: String,
    library: TextbookLibrary,
    lessonFirstPages: () -> Map<String, Int>,
    onBack: () -> Unit,
    onJump: (Int) -> Unit,
) {
    var page by remember(bookId) { mutableStateOf<BookNotesPage?>(null) }
    var failed by remember(bookId) { mutableStateOf(false) }
    var status by remember(bookId) { mutableStateOf<String?>(null) }
    LaunchedEffect(bookId) {
        failed = false
        val loaded = withContext(Dispatchers.IO) {
            val pages = runCatching { lessonFirstPages() }.getOrDefault(emptyMap())
            runCatching { library.loadBookNotes(bookId, bookTitle, pages) }.getOrNull()
        }
        if (loaded == null) failed = true else page = loaded
    }
    val ready = page
    if (ready == null) {
        BookNotesPageFrame(onBack = onBack, onCopy = null, onExport = null, status = null) {
            if (failed) {
                Text("这本书的笔记没有打开。", color = CoachTheme.colors.fgSecondary, modifier = Modifier.testTag("book-notes-failed"))
            } else {
                CoachLoadingDots(label = "正在整理这本书的笔记…", modifier = Modifier.testTag("book-notes-loading"))
            }
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val markdown = remember(ready) { renderBookNotesMarkdown(ready) }
    BookNotesPageView(
        page = ready,
        status = status,
        onBack = onBack,
        onJump = onJump,
        onCopy = {
            clipboard.setText(AnnotatedString(markdown))
            status = "已复制 ${ready.itemCount} 条"
        },
        onExport = {
            scope.launch {
                status = "正在保存…"
                val result = bookSaveNotesMarkdown(notesExportFileName(ready.title), markdown)
                status = when (result) {
                    NotesFileSave.Cancelled -> "没有保存"
                    is NotesFileSave.Failed -> result.message
                    is NotesFileSave.Saved -> "已保存 ${result.fileName}。用记事本打开时中文应可读。"
                }
            }
        },
    )
}

@Composable
internal fun BookNotesPageView(
    page: BookNotesPage,
    status: String?,
    onBack: () -> Unit,
    onJump: (Int) -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
) {
    BookNotesPageFrame(onBack = onBack, onCopy = onCopy, onExport = onExport, status = status) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("book-notes-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(page.title, style = MaterialTheme.typography.titleMedium, color = CoachTheme.colors.fgPrimary)
                Text(
                    "共 ${page.itemCount} 条 · 批注 ${page.annotations.size} · 问答 ${page.questions.size} · 错题和不确定题 ${page.mistakes.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CoachTheme.colors.fgSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).testTag("book-notes-count"),
                )
            }
            for (kind in BookNoteKind.entries) {
                val entries = page.of(kind)
                item(key = "head-${kind.name}") {
                    Text(
                        kind.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = CoachTheme.colors.fgPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp).testTag("book-notes-section-${kind.name}"),
                    )
                }
                if (entries.isEmpty()) {
                    item(key = "empty-${kind.name}") {
                        Text("这一类还没有。", style = MaterialTheme.typography.bodySmall, color = CoachTheme.colors.fgSecondary)
                    }
                } else {
                    items(entries, key = { it.id }) { entry ->
                        BookNoteRow(entry, onJump)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookNotesPageFrame(
    onBack: () -> Unit,
    onCopy: (() -> Unit)?,
    onExport: (() -> Unit)?,
    status: String?,
    body: @Composable () -> Unit,
) {
    val colors = CoachTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bgCanvas)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.Escape) {
                    onBack()
                    return@onPreviewKeyEvent true
                }
                val turnsPage = event.key in setOf(
                    Key.DirectionLeft, Key.DirectionRight, Key.PageUp, Key.PageDown,
                    Key.MoveHome, Key.MoveEnd,
                )
                turnsPage && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
            }
            .testTag("book-notes-page"),
    ) {
        // 垫在内容后面，挡住落到阅读页上的点击；不用包住内容的 clickable，否则子按钮的测试标记会被合并掉。
        Box(
            Modifier
                .matchParentSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().widthIn(max = 880.dp), verticalAlignment = Alignment.CenterVertically) {
            V7TextButton(onBack, modifier = Modifier.testTag("book-notes-back"), ringInside = true) { Text("← 返回阅读") }
            Spacer(Modifier.weight(1f))
            Text("这本书的笔记", style = MaterialTheme.typography.titleMedium, color = colors.fgPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (onCopy != null) {
                V7TextButton(onCopy, modifier = Modifier.testTag("book-notes-copy"), ringInside = true) { Text("复制") }
            }
            if (onExport != null) {
                V7TextButton(onExport, modifier = Modifier.testTag("book-notes-export"), ringInside = true) { Text("导出 Markdown") }
            }
        }
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
                modifier = Modifier.padding(top = 6.dp).testTag("book-notes-status"),
            )
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).widthIn(max = 880.dp)) { body() }
        }
    }
}

@Composable
private fun BookNoteRow(entry: BookNoteEntry, onJump: (Int) -> Unit) {
    val colors = CoachTheme.colors
    val page = entry.page?.takeIf { it > 0 }
    val jump = if (page != null) {
        val target = page
        Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { onJump(target) }
    } else {
        Modifier
    }
    PaperCard(
        Modifier
            .fillMaxWidth()
            .then(jump)
            .testTag("book-notes-item-${entry.id}"),
    ) {
        Text(
            notesPageLabel(entry.page),
            style = MaterialTheme.typography.labelMedium,
            color = if (page != null) colors.actionPrimary else colors.fgSecondary,
            modifier = Modifier.testTag("book-notes-page-label-${entry.id}"),
        )
        for ((label, text) in entry.lines) {
            Text(
                "$label：$text",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (page != null) {
            Text(
                "点这一条回到第 $page 页",
                style = MaterialTheme.typography.labelSmall,
                color = colors.fgSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
