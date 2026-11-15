package com.coach.desktop.books

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
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
class BookCitationWorkspaceTest {
    private fun setup(): Pair<TextbookController, BookLearningSupport> {
        val root = Files.createTempDirectory("citation-workspace-")
        val file = root.resolve("synthetic.pdf")
        PDDocument().use { pdf ->
            repeat(2) { index ->
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                    out.newLineAtOffset(50f, 710f)
                    if (index == 0) { out.showText("Chapter 1 Plants"); out.newLineAtOffset(0f, -48f) }
                    out.showText(if (index == 0) "Plants use light to produce energy and grow." else "Roots absorb water and minerals from the surrounding soil.")
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
        var calls = 0
        val support = object : BookLearningSupport {
            override suspend fun explain(request: BookExplanationRequest): BookLearningReply {
                calls++
                val book = library.book(request.book)
                val block = request.blocks.first { !it.heading && it.page == 2 }
                val valid = BookPassage.from(book, block)
                return BookLearningReply.Text("A synthetic explanation ($calls)", listOf(valid, valid.copy(page = 99)))
            }
            override suspend fun ask(request: BookPassageQuestion) = BookLearningReply.Unavailable("unused")
        }
        return controller to support
    }

    @Test fun citationOpensPageAndEscapeRestoresReplyDraftAndFocus() {
        val (controller, support) = setup()
        runComposeUiTest {
            setContent { TextbookWorkspace(controller, loadOnMount = false, support = support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-stale-1").assertExists()
            onNodeWithTag("guided-start-questions").performScrollTo().performClick()
            onNodeWithTag("short-answer").performScrollTo().performTextInput("unfinished draft")
            waitUntil(timeoutMillis = 20_000) { controller.state.value.answer.text == "unfinished draft" }
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && controller.state.value.page == 2 && !controller.state.value.busy }
            onNodeWithTag("windows-textbooks").performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            onNodeWithTag("book-source-0").assertIsFocused()
            onNodeWithTag("book-explanation-text").assertTextEquals("A synthetic explanation (1)")
            onNodeWithTag("short-answer").performScrollTo().assertTextContains("unfinished draft")
            assertEquals(1, controller.library.book(controller.state.value.book!!.id).readingPage)
        }
    }

    @Test fun sourceChangedWhileReadingReturnsWithoutTryingToFocusMissingButton() {
        val (controller, support) = setup()
        runComposeUiTest {
            setContent { TextbookWorkspace(controller, loadOnMount = false, support = support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && controller.state.value.page == 2 && !controller.state.value.busy }
            val book = controller.state.value.book!!
            Files.write(controller.library.directory.resolve("${book.id}.${book.format}"), byteArrayOf(10), StandardOpenOption.APPEND)
            onNodeWithTag("windows-textbooks").performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            onNodeWithTag("book-source-stale-0").assertExists()
            onNodeWithTag("open-lesson-source").assertIsFocused()
        }
    }
}
