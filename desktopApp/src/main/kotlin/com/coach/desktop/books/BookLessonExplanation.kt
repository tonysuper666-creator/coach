package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

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
