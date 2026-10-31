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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.client.SourceDoc
import com.coach.client.SourcePage

/** 从学习页「本次参考」跳来的目标：文档与 PDF 页。 */
data class SourceJump(val docId: String, val pdfPage: Int)

internal data class PageKey(val docId: String, val pdfPage: Int)

private sealed interface Load<out T> {
    data object Pending : Load<Nothing>
    data object Failed : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

/** 学习页打开知识点时顺手把「看原文」那一页取到本机；点下去时多半已经在手里。取不到就算了，点开后再取。 */
internal suspend fun prefetchSourcePage(c: CoachController, docId: String, pdfPage: Int) {
    val key = PageKey(docId, pdfPage)
    if (key in c.sourcePages) return
    c.course?.page(docId, pdfPage)?.let { c.sourcePages[key] = it }
}

/**
 * 资料：课程原文（教材各章）只读浏览，按页翻。列表和页内容都向 Mac 取；Mac 离线、或 Mac 上的服务还没更新到有这个接口时，
 * 如实写出来，不显示占位内容。已看过的页留在本机内存里，翻回去不用再等。
 * 从学习页跳来时（[jump]）直接显示那一页，不等章节列表；列表只用来标页数和翻页边界。
 */
@Composable
fun ResourcesScreen(c: CoachController, jump: SourceJump?, onBack: (() -> Unit)?, backLabel: String = "← 学习") {
    val http = c.course
    var attempt by remember { mutableStateOf(0) }
    val docs by produceState<Load<List<SourceDoc>>>(Load.Pending, http, attempt) {
        value = http?.sources()?.let { Load.Ready(it) } ?: Load.Failed
    }
    var open by remember(jump) { mutableStateOf(jump?.let { PageKey(it.docId, it.pdfPage) }) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("source-back")) { Text(backLabel) }
            else if (open != null) TextButton(onClick = { open = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 全部章节") }
            Text("资料", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()
        val list = (docs as? Load.Ready)?.value
        val key = open
        when {
            http == null -> CenterMessage("这个版本没有接入资料浏览。")
            key != null -> PageReader(c, list, key, onPage = { open = it }, onRetry = { attempt++ })
            docs is Load.Pending -> CenterMessage("正在取课程原文…")
            list == null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("取不到课程原文：Mac 没连上，或 Mac 上的服务还没更新到有资料接口。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 56.dp)) { Text("重试") }
            }
            list.isEmpty() -> CenterMessage("课程里还没有可浏览的原文。")
            else -> DocList(list) { doc -> open = PageKey(doc.docId, doc.firstPdfPage) }
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
                    Text("${doc.lastPdfPage - doc.firstPdfPage + 1} 页" + (if (doc.firstBookPage != null && doc.lastBookPage != null) " · 书页 p.${doc.firstBookPage}–${doc.lastBookPage}" else ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PageReader(c: CoachController, docs: List<SourceDoc>?, key: PageKey, onPage: (PageKey) -> Unit, onRetry: () -> Unit) {
    val doc = docs?.firstOrNull { it.docId == key.docId }
    var retry by remember { mutableStateOf(0) }
    val page by produceState<Load<SourcePage>>(c.sourcePages[key]?.let { Load.Ready(it) } ?: Load.Pending, key, retry) {
        value = c.sourcePages[key]?.let { Load.Ready(it) }
            ?: c.course?.page(key.docId, key.pdfPage)?.also { c.sourcePages[key] = it }?.let { Load.Ready(it) }
            ?: Load.Failed
    }
    val first = doc?.firstPdfPage ?: key.pdfPage
    val last = doc?.lastPdfPage ?: key.pdfPage
    val loaded = (page as? Load.Ready)?.value
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(doc?.title?.removeSuffix(".md") ?: "原文", style = MaterialTheme.typography.titleSmall)
            val book = loaded?.bookPage
            Text((book?.let { "书页 p.$it · " } ?: "") + "PDF 第 ${key.pdfPage} 页" + (if (doc != null) "（${key.pdfPage - first + 1} / ${last - first + 1}）" else "") + (if (page is Load.Pending) " · 正在取…" else ""), Modifier.testTag("source-page-label"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loaded != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = true).padding(16.dp).widthIn(max = 680.dp)) {
                    Markdown(loaded.text, true, "src:${key.docId}:${key.pdfPage}", c.math)
                }
                page is Load.Pending -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) }
                else -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("这一页取不到：Mac 没连上，或 Mac 上的服务还没更新到有资料接口。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { retry++; onRetry() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("重试") }
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
