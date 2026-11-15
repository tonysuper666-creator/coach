package com.coach.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsCitedProviderHostTest {
    @Test fun realProviderCitationReturnsToSameAnswerWithoutAnotherCall() {
        val root = Files.createTempDirectory("cited-provider-host-")
        val file = root.resolve("synthetic.pdf")
        PDDocument().use { pdf ->
            repeat(2) { i ->
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                    out.newLineAtOffset(50f, 710f)
                    if (i == 0) { out.showText("Chapter 1 Plants"); out.newLineAtOffset(0f, -48f) }
                    out.showText(if (i == 0) "Plants use light to grow." else "Roots absorb water and minerals from surrounding soil.")
                    out.endText()
                }
            }
            pdf.save(file.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(file)
        library.save(imported.copy(learning = true, readingPage = 1, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.learn(LearningPath.READ_AND_LEARN) }
        val book = controller.state.value.book!!
        val blocks = book.lessons.first().blocks.filterNot { it.heading }
        val second = blocks.indexOfFirst { it.page == 2 }
        assertTrue(second >= 0)
        var calls = 0
        val support = windowsBookLearningSupport(library, OwnedBookCliTransport {
            calls++
            """{"answer":"Roots bring water into a plant.","citations":[$second]}"""
        }, OwnedBookOutboundPolicy { prompt -> prompt.action == OwnedBookAction.EXPLAIN &&
            prompt.excerpts.map { it.text } == blocks.map { it.text } })
        runComposeUiTest {
            setContent { WindowsTextbookHost(controller, support = support) { null } }
            waitUntil(timeoutMillis = 8_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-explanation-text").assertTextEquals("Roots bring water into a plant.")
            onNodeWithTag("book-source-0").assertExists()
            onNodeWithTag("guided-start-questions").performScrollTo().performClick()
            onNodeWithTag("short-answer").performScrollTo().performTextInput("unfinished synthetic draft")
            waitUntil(timeoutMillis = 8_000) { controller.state.value.answer.text == "unfinished synthetic draft" }
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 8_000) { controller.state.value.screen == BookScreen.READER && controller.state.value.page == 2 && !controller.state.value.busy }
            onNodeWithTag("windows-textbooks").performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 8_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            onNodeWithTag("book-source-0").assertIsFocused()
            onNodeWithTag("book-explanation-text").assertTextEquals("Roots bring water into a plant.")
            onNodeWithTag("short-answer").performScrollTo().assertTextContains("unfinished synthetic draft")
            assertEquals(1, calls)
            assertEquals(1, library.book(book.id).readingPage)
            Files.write(library.directory.resolve("${book.id}.${book.format}"), byteArrayOf(10), StandardOpenOption.APPEND)
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            onNodeWithTag("book-source-stale-0").assertExists()
            assertEquals(BookScreen.LEARNING, controller.state.value.screen)
        }
    }
}
