package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.v7.V7TextButton

internal fun explanationScope(first: Int, last: Int, title: String): String {
    val pages = if (first == last) "$first" else "$first–$last"
    return "讲的是：第 $pages 页 · $title"
}

@Composable
internal fun <T> RetrievedPageLinks(
    sources: List<T>,
    label: (T) -> String,
    onOpen: (T) -> Unit,
) {
    if (sources.isEmpty()) return
    Text("这次没讲成，先看原文", style = MaterialTheme.typography.bodySmall)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.forEachIndexed { index, source ->
            V7TextButton({ onOpen(source) }, modifier = Modifier.testTag("explain-fallback-page-$index")) {
                Text(label(source))
            }
        }
    }
}

@Composable
internal fun PageExplanation(
    book: Textbook,
    page: Int,
    explainer: BookExplainer,
    enabled: Boolean,
    onOpen: (SourceBlock) -> Unit,
    onLogin: () -> Unit,
) {
    val blocks = remember(book, page) { book.sourceBlocks().filter { it.page == page && it.isBodyParagraph } }
    var attempt by remember(book.id, page) { mutableIntStateOf(0) }
    val reply by produceState<ExplainResult?>(null, blocks, explainer.loaded, explainer.identity, enabled, attempt) {
        value = null
        if (enabled && blocks.isNotEmpty() && explainer.loaded && explainer.identity != null) {
            value = try {
                explainer.explainLesson(blocks, book.pages.maxOf { it.number })
            } catch (_: IllegalArgumentException) {
                ExplainResult.Refused("这一页的段落位置无法核对，可以选中一段再问。")
            }
        }
    }
    when {
        blocks.isEmpty() -> Text("这一页没有可讲解的正文。")
        !enabled -> Text("伴读讲解已在设置中关闭。")
        !explainer.loaded -> Text("正在打开讲解…")
        explainer.identity == null -> {
            RetrievedPageLinks(blocks.take(1), { "第 ${it.page} 页" }, onOpen)
            V7TextButton(onLogin) { Text("登录后讲解这一页") }
        }
        else -> when (val result = reply) {
            null -> Text("正在讲解这一页…")
            is ExplainResult.Done -> ExplanationView(book, book.sourceBlocks(), result.explanation, blocks, showReaderPage = true) { citation ->
                blocks.firstOrNull { it.id == citation.blockId }?.let(onOpen)
            }
            is ExplainResult.Refused -> {
                Text(result.message, modifier = Modifier.testTag("page-explanation-error"))
                RetrievedPageLinks(blocks.take(1), { "第 ${it.page} 页" }, onOpen)
                if (result.trouble.needsPairing) V7TextButton(onLogin) { Text("重新登录") }
                else V7TextButton({ attempt++ }) { Text("重试") }
            }
        }
    }
}
