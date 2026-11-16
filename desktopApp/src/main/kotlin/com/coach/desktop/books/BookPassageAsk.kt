package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
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
    var pendingConsent by remember(book.id, page.number) { mutableStateOf<BookPassageQuestion?>(null) }
    var source by remember(book.id, page.number) { mutableStateOf<BookPassage?>(null) }
    fun send(input: BookPassageQuestion) { reply = null; request = input; revision++ }
    LaunchedEffect(request, revision) {
        val input = request ?: return@LaunchedEffect
        reply = try {
            val result = support.ask(input)
            coroutineContext.ensureActive()
            result
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { BookLearningReply.Unavailable("暂时读不到回答，可以保留阅读位置后再试。") }
    }
    val colors = CoachTheme.colors
    // V7：弹窗是 surface2 的纸；段落、选中的原文和输入框都是叠在上面的一层 surface1 纸，按钮走纸面按钮，不用 Material 描边。
    AlertDialog(modifier = Modifier.testTag("book-passage-dialog"), onDismissRequest = onClose, title = { Text("就这段问一句") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()).testTag("book-passage-ask"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected == null) {
                Text("选择要问的原文段落", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                if (blocks.isEmpty()) Text("这一页没有可选的文字段落，可以先翻到有文字的页面。", style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
                blocks.forEachIndexed { index, block ->
                    PaperButton({ selected = block }, Modifier.fillMaxWidth().testTag("ask-paragraph-$index"),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(block.text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                    }
                }
            } else {
                val typed = question.trim().length
                val tooLong = typed > BookPassageQuestion.MAX_QUESTION_LENGTH
                PaperCard(color = colors.surface1) {
                    Text(checkNotNull(selected).text, style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary,
                        modifier = Modifier.testTag("book-selected-passage"))
                }
                // Nothing typed here is truncated: over-length text stays editable and copyable, and
                // the hint below says why sending is closed instead of letting the provider reject it.
                V7Field(question, { question = it }, "想问什么？", singleLine = false, minLines = 3, isError = tooLong,
                    modifier = Modifier.fillMaxWidth().testTag("book-passage-question"))
                Text(
                    if (tooLong) "已超过 ${BookPassageQuestion.MAX_QUESTION_LENGTH} 字，删掉 " +
                        "${typed - BookPassageQuestion.MAX_QUESTION_LENGTH} 字才能发送。已输入的文字没有被截断。"
                    else "$typed / ${BookPassageQuestion.MAX_QUESTION_LENGTH}",
                    modifier = Modifier.testTag("book-passage-length"), style = MaterialTheme.typography.bodySmall,
                    color = if (tooLong) v7Alert() else colors.fgSecondary)
                PrimaryButton(onClick = {
                    val passage = runCatching { BookPassage.from(book, checkNotNull(selected)) }.getOrNull()
                    if (passage == null) {
                        request = null
                        reply = BookLearningReply.Unavailable("这段原文的位置无法确认，请重新选择。")
                    } else {
                        val input = BookPassageQuestion(passage, question)
                        if (support.consentRequired(book.id)) pendingConsent = input else send(input)
                    }
                }, enabled = BookPassageQuestion.accepts(question) && (request == null || reply != null), modifier = Modifier.testTag("book-passage-send")) { Text("发送") }
                when (val result = reply) {
                    null -> if (request != null) Text("正在读这段问题…", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                    is BookLearningReply.Text -> PaperCard(Modifier.testTag("book-passage-reply"), color = colors.surface1) {
                        Text(result.text, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                        result.sources.forEach { passage ->
                            V7TextButton({ source = passage }, Modifier.testTag("book-passage-citation")) {
                                Text("出处 · ${book.pageLabel(passage.page)}")
                            }
                        }
                    }
                    is BookLearningReply.Unavailable -> Text(result.message, Modifier.testTag("book-passage-unavailable"),
                        style = MaterialTheme.typography.bodySmall, color = v7Alert())
                }
            }
        }
    }, confirmButton = { V7TextButton(onClose, modifier = Modifier.testTag("book-passage-close")) { Text("回到阅读") } })
    pendingConsent?.let { input ->
        AlertDialog(onDismissRequest = { pendingConsent = null }, title = { Text("把这段发到你的 Mac？") },
            text = { Text("会把你选的原文和问题发给你配对的 Mac，由它的 AI 回答。这本书之后不再询问。《${book.title}》允许吗？") },
            confirmButton = { V7TextButton({
                support.allow(book.id); pendingConsent = null; send(input)
            }, Modifier.testTag("ask-consent-allow")) { Text("允许并提问") } },
            dismissButton = { V7TextButton({ pendingConsent = null }, Modifier.testTag("ask-consent-deny")) { Text("不允许") } })
    }
    source?.let { passage ->
        AlertDialog(onDismissRequest = { source = null }, title = { Text("原文 · ${book.pageLabel(passage.page)}") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(passage.text, Modifier.testTag("book-passage-source"))
            } }, confirmButton = { V7TextButton({ source = null }) { Text("回到问题") } })
    }
}
