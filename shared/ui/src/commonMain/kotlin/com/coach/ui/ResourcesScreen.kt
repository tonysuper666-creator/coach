package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coach.client.SourceDoc
import com.coach.client.SourcePage

/** 从学习页「本次参考」跳来的目标：文档与 PDF 页。 */
data class SourceJump(val docId: String, val pdfPage: Int)

private data class PageKey(val docId: String, val pdfPage: Int)

/**
 * 资料：课程原文（教材各章）只读浏览，按页翻。列表和页内容都向 Mac 取；Mac 离线、或 Mac 上的服务还没更新到有这个接口时，
 * 如实写出来，不显示占位内容。已看过的页留在本机内存里，翻回去不用再等。
 */
@Composable
fun ResourcesScreen(c: CoachController, jump: SourceJump?, onBack: (() -> Unit)?) {
    val http = c.course
    var attempt by remember { mutableStateOf(0) }
    val docs by produceState<List<SourceDoc>?>(null, http, attempt) { value = http?.sources() }
    var open by remember(jump) { mutableStateOf(jump?.let { PageKey(it.docId, it.pdfPage) }) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 学习") }
            else if (open != null) TextButton(onClick = { open = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 全部章节") }
            Text("资料", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()
        val list = docs
        when {
            http == null -> CenterMessage("这个版本没有接入资料浏览。")
            list == null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("取不到课程原文：Mac 没连上，或 Mac 上的服务还没更新到有资料接口。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 56.dp)) { Text("重试") }
            }
            list.isEmpty() -> CenterMessage("课程里还没有可浏览的原文。")
            open == null -> DocList(list) { doc -> open = PageKey(doc.docId, doc.firstPdfPage) }
            else -> PageReader(c, list, open!!, onPage = { open = it })
        }
    }
}

@Composable
private fun DocList(docs: List<SourceDoc>, onOpen: (SourceDoc) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("课程原文（只读）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(docs, key = { it.docId }) { doc ->
            Surface(onClick = { onOpen(doc) }, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(doc.title.removeSuffix(".md"), style = MaterialTheme.typography.bodyLarge)
                    Text("${doc.lastPdfPage - doc.firstPdfPage + 1} 页" + (doc.bookPageOffset?.let { " · 书页 p.${doc.firstPdfPage - it}–${doc.lastPdfPage - it}" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PageReader(c: CoachController, docs: List<SourceDoc>, key: PageKey, onPage: (PageKey) -> Unit) {
    val doc = docs.firstOrNull { it.docId == key.docId }
    val cache = remember { HashMap<PageKey, SourcePage>() }
    var retry by remember { mutableStateOf(0) }
    val page by produceState<SourcePage?>(cache[key], key, retry) {
        value = cache[key] ?: c.course?.page(key.docId, key.pdfPage)?.also { cache[key] = it }
    }
    val first = doc?.firstPdfPage ?: key.pdfPage
    val last = doc?.lastPdfPage ?: key.pdfPage
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(doc?.title?.removeSuffix(".md") ?: "原文", style = MaterialTheme.typography.titleSmall)
            val book = page?.bookPage ?: doc?.bookPageOffset?.let { key.pdfPage - it }
            Text((book?.let { "书页 p.$it · " } ?: "") + "PDF 第 ${key.pdfPage} 页（${key.pdfPage - first + 1} / ${last - first + 1}）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val p = page
            if (p == null) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("这一页取不到（Mac 没连上？）。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 56.dp)) { Text("重试") }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = true).padding(16.dp).widthIn(max = 680.dp)) {
                    Markdown(p.text, true, "src:${key.docId}:${key.pdfPage}", c.math)
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPage(key.copy(pdfPage = key.pdfPage - 1)) }, enabled = key.pdfPage > first, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("上一页") }
            OutlinedButton(onClick = { onPage(key.copy(pdfPage = key.pdfPage + 1)) }, enabled = key.pdfPage < last, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("下一页") }
        }
    }
}
