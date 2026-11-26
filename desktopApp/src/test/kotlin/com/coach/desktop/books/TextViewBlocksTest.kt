package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.ui.CoachTheme
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class TextViewBlocksTest {
    private fun pdf(path: Path) {
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val bitmap = BufferedImage(90, 45, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().also { g ->
                    try {
                        g.color = Color.WHITE
                        g.fillRect(0, 0, width, height)
                        g.color = Color.BLACK
                        g.fillOval(8, 8, 28, 28)
                    } finally {
                        g.dispose()
                    }
                }
            }
            val drawing = LosslessFactory.createFromImage(doc, bitmap)
            for (number in 1..3) {
                val page = PDPage(PDRectangle(500f, 700f))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { out ->
                    fun line(y: Float, value: String) {
                        out.beginText(); out.setFont(font, 12f)
                        out.newLineAtOffset(50f, y); out.showText(value); out.endText()
                    }
                    line(650f, "Chapter $number")
                    out.addRect(35f, 510f, 320f, 70f); out.stroke()
                    line(540f, "A framed observation on page $number.")
                    line(410f, "x = y + $number")
                    line(320f, "The original explanation follows.")
                    out.drawImage(drawing, 50f, 200f, 90f, 45f)
                    line(180f, "Figure 1. Printed symbol.")
                }
            }
            doc.save(path.toFile())
        }
    }

    @Test fun pagedReaderLoadsVisibleCrops() = openCrop(false)
    @Test fun scrollingReaderLoadsVisibleCrops() = openCrop(true)

    private fun openCrop(scroll: Boolean) {
        val root = Files.createTempDirectory("text-view-blocks-")
        try {
            val source = root.resolve("frames.pdf").also(::pdf)
            val library = TextbookLibrary(root.resolve("books"), streamPdfExtras = true, lazyPdfExtras = true)
            val book = library.indexBookTier1(library.stageBookImportTier0(source).book.id)
            assertTrue(book.pages.all { it.illustrations.isEmpty() })
            library.saveTextView(book.id, true)
            library.saveTextScrollMode(book.id, scroll)
            val controller = TextbookController(library)
            runBlocking { controller.openBook(book.id, targetPage = 2) }
            runSkikoComposeUiTest(size = Size(900f, 820f), density = Density(1f)) {
                setContent { CoachTheme {
                    TextbookWorkspace(controller, loadOnMount = false, chooseBook = { null })
                } }
                val cropTag = "book-figure-2-0"
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag(cropTag).fetchSemanticsNodes().isNotEmpty() }
                val rendered = assertNotNull(controller.state.value.book?.page(2))
                assertEquals(book.page(2)!!.text, rendered.text)
                assertEquals(PageVisualKind.ILLUSTRATION, rendered.illustrations.single().kind)
                assertNull(rendered.illustrations.single().replacedSourceRange)
                onNodeWithTag(cropTag).performClick()
                onNodeWithTag("figure-full-image").assertExists()
                onNodeWithTag("figure-original").performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("original-pdf-page-2").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(2, controller.state.value.page)
            }
            assertEquals(book.pages.map { it.text }, library.book(book.id).pages.map { it.text })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun onlyApprovedObjectsEnterTheReaderAndSurviveLaterLazyVisits() = runBlocking {
        val root = Files.createTempDirectory("text-view-reviewed-")
        try {
            val source = root.resolve("frames.pdf").also(::pdf)
            val library = TextbookLibrary(root.resolve("books"), streamPdfExtras = true, lazyPdfExtras = true)
            val book = library.indexBookTier1(library.stageBookImportTier0(source).book.id)
            val controller = TextbookController(library)
            controller.openBook(book.id, targetPage = 2)
            val draft = PdfReviewedVisuals.preview(Files.readAllBytes(source), book.pages, listOf(
                PdfVisualSelection(2, 60, 530, 660, 630, PageVisualKind.FORMULA))).single()
            val file = root.resolve("formula.review.json")
            Files.writeString(file, PdfVisualReviewCodec.encode(draft.review))
            controller.importReviewedBlocks(book.id, listOf(file))
            assertTrue(library.book(book.id).page(2)!!.illustrations.isEmpty())
            Files.writeString(file, PdfVisualReviewCodec.encode(draft.review.copy(decision = PdfVisualDecision.COMPLETE_OBJECT_WITH_LABELS)))
            controller.importReviewedBlocks(book.id, listOf(file))
            val formula = assertNotNull(controller.state.value.book).page(2)!!.illustrations.single()
            assertEquals(PageVisualKind.FORMULA, formula.kind)
            assertNull(formula.replacedSourceRange)
            controller.ensureVisualsForPages(book.id, listOf(2))
            val reopened = library.book(book.id).page(2)!!
            assertEquals(book.page(2)!!.text, reopened.text)
            assertEquals(formula, reopened.illustrations.single { it.kind == PageVisualKind.FORMULA })
            assertEquals(1, reopened.illustrations.count { it.kind == PageVisualKind.ILLUSTRATION })
            assertEquals(reopened.illustrations, controller.state.value.book!!.page(2)!!.illustrations)
        } finally { root.toFile().deleteRecursively() }
    }
}
