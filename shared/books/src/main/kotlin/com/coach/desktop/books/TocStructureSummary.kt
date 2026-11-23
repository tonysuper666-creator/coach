package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
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
) {
    val colors = CoachTheme.colors
    val count = remember(book.tocNodes) { book.tocNodes.count { it.kind == TocNodeKind.CHAPTER } }
    val source = when (book.structure?.source) {
        HeadingSource.EPUB_TOC.name, HeadingSource.PDF_OUTLINE.name, HeadingSource.WORD_STYLE.name -> ""
        else -> "自动识别 · "
    }
    val scope = rememberCoroutineScope()
    var editing by remember(book.id) { mutableStateOf(false) }
    var input by remember(book.id, bodyStart) { mutableStateOf(bodyStart.importPage.toString()) }
    var saving by remember(book.id) { mutableStateOf(false) }
    var saveFailed by remember(book.id) { mutableStateOf(false) }
    val target = input.toIntOrNull()?.takeIf { book.page(it) != null }
    fun save(page: Int?) {
        saving = true
        saveFailed = false
        scope.launch {
            try {
                onSave(page)
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

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val mark = if (corrected) " · 已修改" else ""
            Text(
                "${source}共 $count 章，正文从第 ${bodyStart.importPage} 页开始$mark",
                modifier = Modifier.weight(1f).testTag("toc-structure-summary"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
            )
            if (!editing) V7TextButton(onClick = {
                input = bodyStart.importPage.toString()
                editing = true
            }, enabled = !busy, modifier = Modifier.testTag("toc-edit-structure")) { Text("改") }
        }
        if (editing) {
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
                V7TextButton(onClick = { target?.let { save(it) } }, enabled = !saving && target != null) { Text("保存") }
                V7TextButton(onClick = { editing = false; saveFailed = false }, enabled = !saving) { Text("取消") }
                if (corrected) V7TextButton(onClick = { save(null) }, enabled = !saving) { Text("恢复原值") }
            }
            if (saveFailed) Text("没能保存，可以再试一次。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
        }
    }
}
