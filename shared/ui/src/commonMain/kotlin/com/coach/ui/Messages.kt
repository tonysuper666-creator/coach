package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coach.client.TutorMessage
import com.coach.ui.markdown.MarkdownBlock
import com.coach.ui.markdown.MarkdownContent
import com.coach.ui.markdown.StreamingMarkdown

@Composable
internal fun LearnerBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.widthIn(max = 560.dp)) {
            Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** 导师消息：消息头写实际出答的模型名（兜底时标「兜底」）；流式正文用 PR #5 的增量 Markdown 与公式渲染。 */
@Composable
internal fun TutorBubble(message: TutorMessage, math: MathAssets, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("导师" + if (message.model.isNotBlank()) " · ${message.model}" else "", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (message.fallback) StatusChip("兜底", MaterialTheme.colorScheme.tertiary)
            if (!message.done) TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 32.dp)) { Text("停止") }
        }
        Markdown(message.text, message.done, "${message.id}:${message.epoch}", math)
        when {
            message.thinking -> Text("还在想…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            !message.done && message.text.isEmpty() -> Text("…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message.status == "truncated" -> Notice("回答中断了（${reasonText(message.reason)}），已保留已出的部分。")
            message.status == "failed" -> Notice("这条没有生成出来（${reasonText(message.reason)}）。")
            message.status == "cancelled" -> Notice("已停止。")
        }
    }
}

@Composable
private fun Notice(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

private fun reasonText(reason: String?) = when (reason) {
    "rate_limited" -> "Claude 暂时限流，正在换模型回答"
    "exam_protection" -> "11 月 2 日考前保护时段，15:00 GMT 后恢复"
    "first_token_timeout" -> "25 秒没有出字"
    "stall" -> "30 秒没有新内容"
    "total_timeout" -> "超过 180 秒"
    "suspended" -> "Mac 休眠过"
    "cancelled" -> "已取消"
    "client_gone" -> "连接断开"
    "cli_exit", "timeout", "max_output_tokens" -> reason
    null, "" -> "原因未知"
    else -> reason
}

/** 增量 Markdown：同一条消息只追加新增的字符；snapshot 整体替换（key 变化）时重建。 */
@Composable
internal fun Markdown(text: String, done: Boolean, key: String, math: MathAssets, modifier: Modifier = Modifier) {
    val engine = remember(key) { IncrementalMarkdown() }
    val blocks = remember(key, text, done) { engine.update(text, done) }
    LaunchedEffectMath(blocks, math)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            key(block.key) { MarkdownContent(block, math.assets, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun LaunchedEffectMath(blocks: List<MarkdownBlock>, math: MathAssets) {
    val missing = remember(blocks, math.assets.size) {
        blocks.filter { it.sealed }.flatMap { com.coach.domain.math.MathDelimiters.tokenize(it.source, true).segments }
            .mapNotNull { s -> s.math?.let { com.coach.ui.markdown.mathKey(it, s.display) } }.distinct().filter { it !in math.assets }
    }
    LaunchedEffect(missing) { missing.forEach { math.request(it) } }
}

internal class IncrementalMarkdown {
    private val engine = StreamingMarkdown()
    private var consumed = 0
    private var ended = false
    private var last: List<MarkdownBlock> = emptyList()
    fun update(text: String, done: Boolean): List<MarkdownBlock> {
        if (ended) return last
        val tail = if (text.length >= consumed) text.substring(consumed) else ""
        if (tail.isEmpty() && !done && last.isNotEmpty()) return last
        consumed = text.length
        last = engine.append(tail, done)
        if (done) ended = true
        return last
    }
}
