package com.coach.desktop.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
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

    @Test fun consentedWholeLessonShowsBodyAndOpensTheLastBlocksVerifiedSource() {
        val file = root.resolve("self-authored.txt")
        Files.writeString(file, (1..9).joinToString("\n\n") { "Self authored paragraph $it explains a different plant process." })
        val imported = library.importBook(file)
        val whole = imported.lessons.first()
        val blocks = whole.blocks.filterNot { it.heading }
        assertTrue(blocks.size > 6)
        val last = blocks.last()
        var calls = 0
        val support = PairedBookLearningSupport(library, sendLesson = { sent, pages ->
            calls++
            assertEquals(blocks, sent)
            assertEquals(imported.pages.size, pages)
            ExplainResult.Done(Explanation("Plants connect these processes throughout the lesson.【S9】",
                listOf(Citation("S9", last.id, last.page)), 1))
        })
        val resolver = BookSourceResolver(library)
        val focus = FocusRequester()
        var opened: BookSourceTarget? = null
        runSkikoComposeUiTest(size = Size(900f, 700f), density = Density(1f)) {
            setContent {
                BookLessonExplanation(imported, whole, support, sources = { sources ->
                    BookSourceRow(sources, imported, resolver, { focus }, { _, target -> opened = target })
                })
            }
            onNodeWithTag("book-lesson-consent-allow").assertIsDisplayed()
            runOnIdle { assertEquals(0, calls) }
            onNodeWithTag("book-lesson-consent-allow").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().size == 1 }
            onNodeWithTag("book-explanation-text").assertTextEquals("Plants connect these processes throughout the lesson.")
            onNodeWithTag("book-source-0").assertTextContains("第 ${last.page} 页", substring = true).performClick()
            waitUntil(timeoutMillis = 20_000) { opened != null }
            runOnIdle {
                val target = checkNotNull(opened)
                assertEquals(imported.id, target.book)
                assertEquals(last.page, target.pdfPage)
                assertEquals(last.start, target.start)
                assertEquals(last.end, target.end)
                assertEquals(last.text, target.quote)
                assertEquals(1, calls)
            }
        }
    }

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

    @Test fun unavailableServiceOffersRetry() {
        val message = "讲解服务暂时不可用，请稍后再试。"
        val support = object : BookLearningSupport {
            override suspend fun explain(request: BookExplanationRequest) =
                BookLearningReply.Unavailable(message, ExplainTrouble.Busy)
            override suspend fun ask(request: BookPassageQuestion): BookLearningReply = error("Not a passage question")
        }
        runSkikoComposeUiTest(size = Size(900f, 700f), density = Density(1f)) {
            setContent { BookLessonExplanation(book, lesson, support) }
            onNodeWithTag("explain-trouble").assertTextEquals(message)
            onNodeWithTag("explain-trouble-retry").assertIsEnabled()
        }
    }
}
