package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TocStructureSummary(
    book: Textbook,
    bodyStart: BookPageAnchor,
    corrected: Boolean,
    busy: Boolean,
    onSave: suspend (Int?) -> Unit,
    currentChapterId: String?,
    currentAnchor: BookPageAnchor,
    onEditChapter: (suspend (StructureEdit) -> Unit)?,
) {
    val colors = CoachTheme.colors
    val count = remember(book.tocNodes) { book.tocNodes.count { it.kind == TocNodeKind.CHAPTER } }
    val source = when (book.structure?.source) {
        HeadingSource.EPUB_TOC.name, HeadingSource.PDF_OUTLINE.name, HeadingSource.WORD_STYLE.name, HeadingSource.MANUAL.name -> ""
        else -> "自动识别 · "
    }
    val scope = rememberCoroutineScope()
    var editing by remember(book.id) { mutableStateOf(false) }
    var chaptersOpen by remember(book.id) { mutableStateOf(false) }
    var input by remember(book.id, bodyStart) { mutableStateOf(bodyStart.importPage.toString()) }
    var saving by remember(book.id) { mutableStateOf(false) }
    var saveFailed by remember(book.id) { mutableStateOf(false) }
    val target = input.toIntOrNull()?.takeIf { book.page(it) != null }
    fun save(action: suspend () -> Unit) {
        saving = true
        saveFailed = false
        scope.launch {
            try {
                action()
                editing = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveFailed = true
            } finally {
                saving = false
            }
        }
    }

    val editorScroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().then(if (editing) Modifier.heightIn(max = 400.dp).verticalScroll(editorScroll) else Modifier).padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val mark = if (corrected || book.structure?.source == HeadingSource.MANUAL.name) " · 已修改" else ""
            Text(
                "${source}共 $count 章，正文从第 ${bodyStart.importPage} 页开始$mark",
                modifier = Modifier.weight(1f).testTag("toc-structure-summary"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
            )
            if (!editing) V7TextButton(onClick = {
                input = bodyStart.importPage.toString()
                chaptersOpen = false
                editing = true
            }, enabled = !busy, modifier = Modifier.testTag("toc-edit-structure")) { Text("不对？改") }
            else V7TextButton(onClick = { editing = false; saveFailed = false }, enabled = !saving) { Text("取消") }
        }
        if (editing) {
            if (chaptersOpen && onEditChapter != null) {
                ChapterEditor(book, currentChapterId, currentAnchor, !saving) { edit -> save { onEditChapter(edit) } }
            } else {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit).take(6); saveFailed = false },
                    enabled = !saving,
                    isError = input.isNotEmpty() && target == null,
                    label = { Text("正文起始页") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("toc-body-start-field"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    V7TextButton(onClick = { target?.let { save { onSave(it) } } }, enabled = !saving && target != null) { Text("保存") }
                    if (onEditChapter != null) V7TextButton(onClick = { chaptersOpen = true }, enabled = !saving) { Text("改章节") }
                    if (corrected) V7TextButton(onClick = { save { onSave(null) } }, enabled = !saving) { Text("恢复原值") }
                }
            }
            if (saveFailed) Text("没能保存，可以再试一次。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
        }
    }
}

@Composable
private fun ChapterEditor(book: Textbook, currentId: String?, currentAnchor: BookPageAnchor, enabled: Boolean, submit: (StructureEdit) -> Unit) {
    val chapters = remember(book.tocNodes) { book.tocNodes.filter { it.kind == TocNodeKind.CHAPTER } }
    var selected by remember(chapters) { mutableIntStateOf(chapters.indexOfFirst { it.id == currentId }.coerceAtLeast(0)) }
    val node = chapters.getOrNull(selected)
    var menu by remember { mutableStateOf(false) }
    var name by remember(node) { mutableStateOf(node?.title.orEmpty()) }
    var page by remember(node) { mutableStateOf("") }
    var splitName by remember(node) { mutableStateOf("") }
    var splitting by remember(node) { mutableStateOf(node == null) }
    if (node != null) {
        Box {
            V7TextButton(onClick = { menu = true }, enabled = enabled) { Text("第 ${selected + 1} 章 · 选择章节") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                chapters.forEachIndexed { index, item ->
                    DropdownMenuItem(text = { Text("第 ${item.startPage} 页 · ${item.title}", maxLines = 1) },
                        onClick = { selected = index; menu = false })
                }
            }
        }
        if (!splitting) {
            OutlinedTextField(name, { name = it.take(160) }, singleLine = true, enabled = enabled,
                label = { Text("章名") }, modifier = Modifier.fillMaxWidth().testTag("toc-chapter-name"))
            Row {
                V7TextButton(onClick = { submit(StructureEdit.Rename(node.id, name)) },
                    enabled = enabled && name.isNotBlank() && name.trim() != node.title) { Text("保存章名") }
                V7TextButton(onClick = { submit(StructureEdit.MergeWithPrevious(node.id)) },
                    enabled = enabled && selected > 0 && chapters[selected - 1].parentId == node.parentId) { Text("并入上一章") }
                V7TextButton(onClick = { splitting = true }, enabled = enabled) { Text("拆章") }
            }
            return
        }
    }
    // A reflowed EPUB can contain several chapters on the same import page.
    val paragraph = remember(book.structure, currentAnchor, node) {
        if (node == null || currentAnchor.offsetUtf16 <= 0) null else book.sourceBlocks().firstOrNull {
            it.page == currentAnchor.importPage && it.start >= currentAnchor.offsetUtf16 &&
                (it.page > node.startPage || it.start > node.startOffset) &&
                BookChapterResolver.chapterForPage(book, it.page, it.start)?.tocNodeId == node.id
        }
    }
    val target = page.toIntOrNull()
    val valid = target != null && book.page(target) != null &&
        (node == null || target in (node.startPage + 1)..node.endPage)
    OutlinedTextField(page, { page = it.filter(Char::isDigit).take(6) }, singleLine = true, enabled = enabled,
        isError = page.isNotEmpty() && !valid, label = { Text("新章从第几页开始") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(splitName, { splitName = it.take(160) }, singleLine = true, enabled = enabled,
        label = { Text("新章名称") }, modifier = Modifier.fillMaxWidth())
    if (paragraph != null && node != null) {
        V7TextButton(onClick = { submit(StructureEdit.Split(node.id, paragraph.page, splitName, paragraph.start)) },
            enabled = enabled && splitName.isNotBlank()) { Text("从当前段落拆章") }
    }
    V7TextButton(onClick = { submit(StructureEdit.Split(node?.id, checkNotNull(target), splitName)) },
        enabled = enabled && valid && splitName.isNotBlank()) { Text(if (node == null) "添加章" else "拆出新章") }
}
