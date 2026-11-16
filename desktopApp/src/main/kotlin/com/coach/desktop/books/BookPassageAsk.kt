package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Explicit paragraph selection keeps the original page offsets even for Unicode text. */
@Composable
internal fun BookPassageAsk(book: Textbook, page: BookPage, support: BookLearningSupport, onClose: () -> Unit) {
    val blocks = remember(book.id, page.number) { StudyCoachCourse.blocks(book.id, listOf(page)).filterNot { it.heading } }
    var selected by remember(book.id, page.number) { mutableStateOf<SourceBlock?>(null) }
    var question by remember(book.id, page.number) { mutableStateOf("") }
    var request by remember(book.id, page.number) { mutableStateOf<BookPassageQuestion?>(null) }
    var reply by remember(book.id, page.number) { mutableStateOf<BookLearningReply?>(null) }
    var revision by remember(book.id, page.number) { mutableIntStateOf(0) }
    LaunchedEffect(request, revision) {
        val input = request ?: return@LaunchedEffect
        reply = try {
            val result = support.ask(input)
            coroutineContext.ensureActive()
            result
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { BookLearningReply.Unavailable("暂时读不到回答，可以保留阅读位置后再试。") }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("就这段问一句") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()).testTag("book-passage-ask"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected == null) {
                Text("选择要问的原文段落")
                if (blocks.isEmpty()) Text("这一页没有可选的文字段落，可以先翻到有文字的页面。")
                blocks.forEachIndexed { index, block ->
                    OutlinedButton({ selected = block }, Modifier.fillMaxWidth().testTag("ask-paragraph-$index")) {
                        Text(block.text)
                    }
                }
            } else {
                val typed = question.trim().length
                val tooLong = typed > BookPassageQuestion.MAX_QUESTION_LENGTH
                Text(checkNotNull(selected).text, Modifier.testTag("book-selected-passage"))
                // Nothing typed here is truncated: over-length text stays editable and copyable, and
                // the hint below says why sending is closed instead of letting the provider reject it.
                OutlinedTextField(question, { question = it }, label = { Text("想问什么？") },
                    modifier = Modifier.fillMaxWidth().testTag("book-passage-question"), isError = tooLong)
                Text(
                    if (tooLong) "已超过 ${BookPassageQuestion.MAX_QUESTION_LENGTH} 字，删掉 " +
                        "${typed - BookPassageQuestion.MAX_QUESTION_LENGTH} 字才能发送。已输入的文字没有被截断。"
                    else "$typed / ${BookPassageQuestion.MAX_QUESTION_LENGTH}",
                    modifier = Modifier.testTag("book-passage-length"), style = MaterialTheme.typography.bodySmall,
                    color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    val passage = runCatching { BookPassage.from(book, checkNotNull(selected)) }.getOrNull()
                    if (passage == null) {
                        request = null
                        reply = BookLearningReply.Unavailable("这段原文的位置无法确认，请重新选择。")
                    } else {
                        reply = null
                        request = BookPassageQuestion(passage, question)
                        revision++
                    }
                }, enabled = BookPassageQuestion.accepts(question) && (request == null || reply != null), modifier = Modifier.testTag("book-passage-send")) { Text("发送") }
                when (val result = reply) {
                    null -> if (request != null) Text("正在读这段问题…")
                    is BookLearningReply.Text -> Text(result.text)
                    is BookLearningReply.Unavailable -> Text(result.message, Modifier.testTag("book-passage-unavailable"))
                }
            }
        }
    }, confirmButton = { TextButton(onClose) { Text("回到阅读") } })
}
