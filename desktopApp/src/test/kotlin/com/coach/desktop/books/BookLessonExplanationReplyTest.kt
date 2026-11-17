package com.coach.desktop.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CompletableDeferred
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

    @Test fun consentPrecedesEveryRequestAndRetryKeepsTheWholeLesson() {
        var allowed = false
        val requests = mutableListOf<BookExplanationRequest>()
        val response = CompletableDeferred<BookLearningReply>()
        val support = object : BookLearningSupport {
            override fun consentRequired(book: String): Boolean = !allowed
            override fun allow(book: String) { allowed = true }
            override suspend fun explain(request: BookExplanationRequest): BookLearningReply {
                requests += request
                return if (requests.size == 1)
                    BookLearningReply.Unavailable(ExplainTrouble.UNREACHABLE, ExplainTrouble.Unreachable)
                else response.await()
            }
            override suspend fun ask(request: BookPassageQuestion): BookLearningReply = error("Not a passage question")
        }
        val reply = mutableStateOf<BookLearningReply?>(null)
        runSkikoComposeUiTest(size = Size(900f, 700f), density = Density(1f)) {
            setContent { BookLessonExplanation(book, lesson, support, reply) }
            onNodeWithTag("book-lesson-consent").assertIsDisplayed()
            runOnIdle { assertTrue(requests.isEmpty(), "No source text may be sent before consent") }
            onNodeWithTag("book-lesson-consent-allow").performClick()
            onNodeWithTag("explain-trouble").assertTextEquals(ExplainTrouble.UNREACHABLE)
            onNodeWithTag("explain-trouble-retry").performClick()
            onNodeWithText("正在读取这节的讲解…").assertIsDisplayed()
            runOnIdle {
                assertEquals(List(2) { BookExplanationRequest(book.id, lesson.id, lesson.blocks) }, requests)
                response.complete(BookLearningReply.Text("Synthetic lesson explanation."))
            }
            waitUntil(timeoutMillis = 20_000) { reply.value is BookLearningReply.Text }
            onNodeWithTag("book-explanation-text").assertTextEquals("Synthetic lesson explanation.")
            onNodeWithTag("book-lesson-consent").assertDoesNotExist()
        }
    }

    @Test fun exhaustedQuotaOffersNoRetry() {
        val support = object : BookLearningSupport {
            override suspend fun explain(request: BookExplanationRequest) =
                BookLearningReply.Unavailable("今天的讲解额度用完了。", ExplainTrouble.Quota)
            override suspend fun ask(request: BookPassageQuestion): BookLearningReply = error("Not a passage question")
        }
        runSkikoComposeUiTest(size = Size(900f, 700f), density = Density(1f)) {
            setContent { BookLessonExplanation(book, lesson, support) }
            onNodeWithTag("explain-trouble").assertTextEquals("今天的讲解额度用完了。")
            onNodeWithTag("explain-trouble-retry").assertDoesNotExist()
        }
    }
}
