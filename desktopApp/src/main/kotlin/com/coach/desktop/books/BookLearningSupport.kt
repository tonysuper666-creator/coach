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

/** Local content identities, never substitutes for a paired course, KP or HTTP document ID. */
internal data class BookExplanationRequest(val book: String, val lesson: String, val blocks: List<SourceBlock>)
internal data class BookPassage(val book: String, val page: Int, val start: Int, val end: Int, val text: String) {
    companion object {
        fun from(book: Textbook, block: SourceBlock): BookPassage {
            val page = book.pages.firstOrNull { it.number == block.page }
            require(page != null && !block.heading && block.start >= 0 && block.end <= page.text.length &&
                block.end > block.start && block.id == "${book.id}:p${block.page}:${block.start}" &&
                page.text.substring(block.start, block.end) == block.text) {
                "这段原文的位置无法确认，请重新选择。"
            }
            return BookPassage(book.id, page.number, block.start, block.end, block.text)
        }
    }
}
internal data class BookPassageQuestion(val passage: BookPassage, val question: String)
internal sealed interface BookLearningReply {
    data class Text(val text: String, val sources: List<BookPassage> = emptyList()) : BookLearningReply
    data class Unavailable(val message: String) : BookLearningReply
}

/** A real, authorized explanation/answer provider must be supplied here; source text is not a reply. */
internal interface BookLearningSupport {
    suspend fun explain(request: BookExplanationRequest): BookLearningReply
    suspend fun ask(request: BookPassageQuestion): BookLearningReply
}

internal object UnconnectedBookLearningSupport : BookLearningSupport {
    override suspend fun explain(request: BookExplanationRequest) =
        BookLearningReply.Unavailable("这节的讲解尚未接通。可以主动打开对应原文，或先试着做题。")
    override suspend fun ask(request: BookPassageQuestion) =
        BookLearningReply.Unavailable("选段问答尚未接通，暂时不能回答。阅读位置已保留。")
}

@Composable
internal fun BookLessonExplanation(
    book: Textbook,
    lesson: BookLesson,
    support: BookLearningSupport,
    reply: MutableState<BookLearningReply?> = remember(book.id, lesson.id, support) { mutableStateOf(null) },
    sources: @Composable (List<BookPassage>) -> Unit = {},
) {
    // A caller may hold this across a screen switch so returning does not re-ask the provider.
    LaunchedEffect(book.id, lesson.id, support) {
        if (reply.value != null) return@LaunchedEffect
        reply.value = try {
            val result = support.explain(BookExplanationRequest(book.id, lesson.id, lesson.blocks))
            coroutineContext.ensureActive()
            if (result is BookLearningReply.Text && (result.text.isBlank() || result.text.trim() == lesson.text.trim()))
                BookLearningReply.Unavailable("这节还没有可用的讲解，可以主动打开对应原文。")
            else result
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { BookLearningReply.Unavailable("讲解暂时读不到，原有作答和位置已保留。") }
    }
    Column(Modifier.fillMaxWidth().testTag("book-lesson-explanation"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("讲解", style = MaterialTheme.typography.titleMedium)
        when (val result = reply.value) {
            null -> Text("正在读取这节的讲解…")
            is BookLearningReply.Text -> {
                Text(result.text, Modifier.testTag("book-explanation-text"))
                sources(result.sources)
            }
            is BookLearningReply.Unavailable -> Text(result.message, Modifier.testTag("book-explanation-unavailable"))
        }
    }
}

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
                Text(checkNotNull(selected).text, Modifier.testTag("book-selected-passage"))
                OutlinedTextField(question, { question = it.take(1000) }, label = { Text("想问什么？") },
                    modifier = Modifier.fillMaxWidth().testTag("book-passage-question"))
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
                }, enabled = question.isNotBlank() && (request == null || reply != null), modifier = Modifier.testTag("book-passage-send")) { Text("发送") }
                when (val result = reply) {
                    null -> if (request != null) Text("正在读这段问题…")
                    is BookLearningReply.Text -> Text(result.text)
                    is BookLearningReply.Unavailable -> Text(result.message, Modifier.testTag("book-passage-unavailable"))
                }
            }
        }
    }, confirmButton = { TextButton(onClose) { Text("回到阅读") } })
}
