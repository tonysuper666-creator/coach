package com.coach.desktop.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BookLessonExplanationReplyTest {
    private val root = Files.createTempDirectory("book-explanation-reply-")
    private val library = TextbookLibrary(root.resolve("library"))
    private val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
    private val lesson = book.lessons.first()

    private class CountingSupport(private val body: String) : BookLearningSupport {
        var explainCalls = 0
            private set
        override suspend fun explain(request: BookExplanationRequest): BookLearningReply {
            explainCalls++
            return BookLearningReply.Text(body)
        }
        override suspend fun ask(request: BookPassageQuestion): BookLearningReply =
            BookLearningReply.Unavailable("No answer provider")
    }

    @Test fun heldReplySurvivesLeavingCompositionAndExplainsOnce() {
        val body = "Synthetic explanation kept across the screen switch."
        val support = CountingSupport(body)
        val reply = mutableStateOf<BookLearningReply?>(null)
        var visible by mutableStateOf(true)
        runSkikoComposeUiTest(size = Size(900f, 700f), density = Density(1f)) {
            setContent {
                if (visible) BookLessonExplanation(book, lesson, support, reply)
            }
            waitUntil(timeoutMillis = 20_000) { support.explainCalls >= 1 && reply.value != null }
            onNodeWithTag("book-explanation-text").assertTextEquals(body)

            runOnIdle { visible = false }
            waitForIdle()
            onNodeWithTag("book-lesson-explanation").assertDoesNotExist()

            runOnIdle { visible = true }
            waitForIdle()
            onNodeWithTag("book-explanation-text").assertTextEquals(body)
            assertEquals(1, support.explainCalls, "Returning to the lesson must reuse the held reply")
            assertEquals(BookLearningReply.Text(body), reply.value)
        }
    }
}
