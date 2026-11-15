package com.coach.desktop.books

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import kotlin.test.*

/** Seam proof: the explanation slot hands typed sources straight to BookSourceRow. */
@OptIn(ExperimentalTestApi::class)
class BookExplanationSourceRowTest {
    private val root = Files.createTempDirectory("book-explanation-source-row-")
    private val library = TextbookLibrary(root.resolve("library"))
    private val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
    private val lesson = book.lessons.first()
    private val passage = BookPassage.from(book, book.lessons.flatMap { it.blocks }.first { !it.heading })

    private class SourceSupport(private val body: String, private val sources: List<BookPassage>) : BookLearningSupport {
        override suspend fun explain(request: BookExplanationRequest) = BookLearningReply.Text(body, sources)
        override suspend fun ask(request: BookPassageQuestion) = BookLearningReply.Unavailable("No answer provider")
    }

    @Test fun explanationSlotRendersTheRowAndOpensTheResolvedTarget() {
        val body = "Synthetic explanation with one typed source."
        val support = SourceSupport(body, listOf(passage))
        val resolver = BookSourceResolver(library)
        var opened: Pair<Int, BookSourceTarget>? = null
        runSkikoComposeUiTest(size = Size(900f, 700f), density = Density(1f)) {
            setContent {
                BookLessonExplanation(book, lesson, support, remember { mutableStateOf<BookLearningReply?>(null) }) { sources ->
                    val requesters = remember(sources.size) { List(sources.size) { FocusRequester() } }
                    BookSourceRow(
                        sources = sources, book = book, resolver = resolver,
                        focus = { requesters[it] },
                        onOpen = { index, target -> opened = index to target },
                    )
                }
            }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-explanation-text").assertTextEquals(body)
            onNodeWithTag("book-source-0").performClick()
            val (index, target) = assertNotNull(opened)
            assertEquals(0, index)
            assertEquals(passage.page, target.pdfPage)
            assertEquals(passage.text, target.quote)
        }
    }
}
