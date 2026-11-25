package com.coach.desktop.regression

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.books.BookScreen
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem

/**
 * TON-1846: End on a large PDF moves the viewport to the last page and leaves
 * the shelf reading page on the chapter the reader had actually reached.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class R1846EndDoesNotCommitShelfTest {
    @Test
    fun endMovesTheViewportWithoutRewritingTheShelfPage() {
        val root = Files.createTempDirectory("coach-r1846-end-")
        try {
            val totalPages = 20
            val pdf = root.resolve("field-guide.pdf")
            writePdf(pdf, totalPages)
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val imported = library.importBook(pdf)
            val startPage = 4
            assertEquals(startPage, imported.readingPage, "import should open at chapter 1")
            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openBook(imported.id)
            }
            runSkikoComposeUiTest(size = Size(820f, 620f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitUntil(timeoutMillis = 20_000) {
                    !controller.state.value.busy && controller.state.value.screen == BookScreen.READER
                }
                waitForIdle()
                assertEquals(startPage, controller.state.value.page)
                onNodeWithTag("reader-page").performSemanticsAction(SemanticsActions.RequestFocus)
                waitForIdle()
                onNodeWithTag("reader-page").performKeyInput { pressKey(Key.MoveEnd) }
                waitUntil(timeoutMillis = 15_000) {
                    nodeText("reader-page-number").contains("第 $totalPages / $totalPages 页")
                }
                assertEquals(totalPages, controller.state.value.page)
                assertEquals(startPage, controller.state.value.book?.readingPage)
                onNodeWithTag("immersive-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == BookScreen.HOME }
                waitForIdle()
                val continueMeta = nodeText("home-focus-meta")
                assertTrue(continueMeta.contains("第 $startPage 页"), "continue card left chapter 1: $continueMeta")
                assertFalse(continueMeta.contains("第 $totalPages 页"), "continue card jumped to the last page: $continueMeta")
                onNodeWithText("$totalPages 页 · 读到第 $startPage 页").assertExists()
                assertEquals(startPage, TextbookLibrary(library.directory).book(imported.id).readingPage)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.nodeText(tag: String): String =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { node ->
            val own = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            if (own.isNotEmpty()) own
            else onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text }.orEmpty()
        }

    private fun writePdf(path: Path, totalPages: Int) {
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val pages = (1..totalPages).map { number ->
                val page = PDPage(PDRectangle.LETTER)
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 13f)
                    stream.newLineAtOffset(64f, 710f)
                    when (number) {
                        1 -> stream.showText("Title Page - Synthetic Field Guide")
                        2 -> stream.showText("About the Authors of this synthetic field guide.")
                        3 -> stream.showText("Preface. This note comes before the first chapter.")
                        4 -> {
                            stream.showText("Chapter 1 Foundations")
                            stream.newLineAtOffset(0f, -22f)
                            stream.showText("Leaves collect light and store the result as sugars.")
                        }
                        else -> {
                            stream.showText("Chapter page $number continues the synthetic field notes.")
                            stream.newLineAtOffset(0f, -22f)
                            stream.showText("Measurements on page $number stay inside the same short guide.")
                        }
                    }
                    stream.endText()
                }
                page
            }
            val outline = PDDocumentOutline()
            pdf.documentCatalog.documentOutline = outline
            fun add(title: String, index: Int) {
                outline.addLast(PDOutlineItem().apply {
                    this.title = title
                    destination = PDPageFitDestination().apply { page = pages[index] }
                })
            }
            add("About the Authors", 1)
            add("Preface", 2)
            add("Chapter 1 Foundations", 3)
            pdf.save(path.toFile())
        }
    }
}
