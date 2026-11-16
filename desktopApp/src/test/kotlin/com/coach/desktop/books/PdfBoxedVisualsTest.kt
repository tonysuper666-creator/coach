package com.coach.desktop.books

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.rendering.PDFRenderer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*

class PdfBoxedVisualsTest {
    private val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private fun PDPageContentStream.text(x: Float, y: Float, value: String) {
        beginText(); setFont(font, 12f); newLineAtOffset(x, y); showText(value); endText()
    }
    private fun fixture(path: Path, mode: String = "normal") {
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(-40f, -30f, 620f, 790f))
            page.cropBox = PDRectangle(10f, 20f, 500.25f, 700.25f)
            if (mode == "rotation") page.rotation = 90
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { out ->
                out.text(40f, 680f, "Body before special blocks.")
                if (mode == "clip") { out.addRect(0f, 0f, 600f, 780f); out.clip() }
                out.setLineWidth(3f)
                out.addRect(35f, 540f, 300f, 70f); out.stroke()
                out.moveTo(35f, 575f); out.lineTo(335f, 575f); out.stroke()
                out.text(45f, 590f, "Table header repeated")
                out.text(45f, 555f, "Table row repeated")
                out.addRect(35f, 470f, 300f, 45f); out.stroke()
                out.text(45f, 490f, "Formula E = mc2")
                out.addRect(35f, 360f, 300f, 70f); out.stroke()
                out.text(45f, 400f, "Callout repeated")
                if (mode == "nested") { out.addRect(40f, 370f, 270f, 45f); out.stroke() }
                if (mode == "overlap") { out.addRect(280f, 550f, 100f, 40f); out.stroke() }
                if (mode == "crossing") { out.moveTo(300f, 580f); out.lineTo(380f, 580f); out.stroke() }
                if (mode == "budget") repeat(257) { out.addRect(60f, 365f, 80f, 20f); out.stroke() }
                out.text(40f, 320f, "Body after special blocks.")
            }
            pdf.save(path.toFile())
        }
    }
    private fun <T> temp(action: (Path) -> T): T {
        val root = Files.createTempDirectory("coach-enclosed-pdf-")
        try { return action(root) } finally { root.toFile().deleteRecursively() }
    }
    private fun visible(page: BookPage, hide: Boolean) = BookIllustrationLayout.segment(page.text, page.illustrations, hide)
        .filterIsInstance<BookPageSegment.Text>().joinToString("") { it.text }

    @Test fun ordinaryImportAutomaticallyPersistsThreeCropsWithoutMovingTextOrCitations() = temp { root ->
        val file = root.resolve("self-authored.pdf")
        fixture(file)
        val canonical = Loader.loadPDF(file.toFile()).use { pdf -> PdfPageTextExtraction.extract(pdf, 1).unit.text!! }
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(file)
        assertEquals(canonical, book.pages.single().text)
        val blocks = StudyCoachCourse.blocks(book.id, book.pages)
        val citations = blocks.filter { !it.heading }.map { OwnedBookCitation(book.id, 1, it.start, it.end) to it.text }
        val resolutions = BookSourceResolver(library).resolveAll(citations)
        assertTrue(resolutions.isNotEmpty())
        assertTrue(resolutions.all { it is BookSourceResolution.Resolved })
        assertEquals(3, book.pages.single().illustrations.size)
        Loader.loadPDF(file.toFile()).use { pdf ->
            val full = PDFRenderer(pdf).renderImage(0, 2f)
            try {
                for (crop in book.pages.single().illustrations) {
                    assertEquals(PageVisualKind.COMPLEX_REGION, crop.kind)
                    assertNull(crop.sourceStart); assertNull(crop.sourceEnd)
                    val region = assertNotNull(crop.sourceRegion)
                    val image = ImageIO.read(BookIllustrationAssets.imageFile(library.directory, book.id, crop).toFile())
                    try {
                        val x = (region.x * 500.25 * 2).roundToInt()
                        val y = (region.y * 700.25 * 2).roundToInt()
                        assertEquals(crop.width, image.width); assertEquals(crop.height, image.height)
                        assertEquals(crop.width, (region.width * 500.25 * 2).roundToInt())
                        assertEquals(crop.height, (region.height * 700.25 * 2).roundToInt())
                        for (iy in 0 until image.height) for (ix in 0 until image.width)
                            assertEquals(full.getRGB(x + ix, y + iy), image.getRGB(ix, iy), "crop pixels")
                        assertTrue((0 until image.width).all { image.getRGB(it, 0) and 0xffffff == 0xffffff })
                    } finally { image.flush() }
                }
            } finally { full.flush() }
        }
        Files.delete(file)
        val reopenedLibrary = TextbookLibrary(library.directory)
        val reopened = reopenedLibrary.book(book.id)
        assertEquals(book.pages, reopened.pages)
        assertEquals(blocks, StudyCoachCourse.blocks(reopened.id, reopened.pages))
        assertEquals(resolutions, BookSourceResolver(reopenedLibrary).resolveAll(citations))
        assertEquals(canonical, visible(reopened.pages.single(), false))
        assertEquals(canonical, visible(reopened.pages.single(), true))
        assertEquals(book.id, reopenedLibrary.importBook(library.directory.resolve("${book.id}.pdf")).id)
        assertEquals(3, reopenedLibrary.book(book.id).pages.single().illustrations.size)
    }

    @Test fun crossingInkOverlappingFramesAndNestedFramesCannotTruncateOrDuplicateEnclosures() = temp { root ->
        for ((mode, expected) in listOf("crossing" to 2, "overlap" to 2, "nested" to 3)) {
            val file = root.resolve("$mode.pdf"); fixture(file, mode)
            val extracted = TextbookExtraction.extract(file, "pdf", mode)
            assertEquals(expected, extracted.illustrations.size, mode)
            assertTrue(extracted.illustrations.all { it.sourceStart == null && it.sourceEnd == null })
        }
    }

    @Test fun unsupportedPageGeometryAndVisualBudgetsFallBackToOriginalText() = temp { root ->
        for (mode in listOf("clip", "rotation", "budget")) {
            val file = root.resolve("$mode.pdf"); fixture(file, mode)
            val extracted = TextbookExtraction.extract(file, "pdf", mode)
            assertTrue(extracted.illustrations.isEmpty(), mode)
            assertTrue(extracted.pages.single().text.contains("Body after special blocks."), mode)
        }
    }

    @Test fun staleTextOcrPagesAndCancelledWorkNeverProduceCrops(): Unit = temp { root ->
        val file = root.resolve("self-authored.pdf"); fixture(file)
        Loader.loadPDF(file.toFile()).use { pdf ->
            val canonical = PdfPageTextExtraction.extract(pdf, 1).unit.text!!
            assertTrue(PdfBoxedVisuals.extract(pdf, BookPage(1, "stale" + canonical), BookImportControl()).isEmpty())
            assertTrue(PdfBoxedVisuals.extract(pdf, BookPage(1, canonical, ocr = true), BookImportControl()).isEmpty())
            assertFailsWith<BookImportCancelled> {
                PdfBoxedVisuals.extract(pdf, BookPage(1, canonical), BookImportControl(cancelledExternally = { true }))
            }
        }
    }

    @Test fun replacingAutomaticCropsIsPageScopedAndRollbackKeepsPriorAssets(): Unit = temp { root ->
        val file = root.resolve("two-pages.pdf"); fixture(file)
        Loader.loadPDF(file.toFile()).use { pdf ->
            // Duplicate the page without changing its text; the second page must stay intact.
            pdf.importPage(pdf.getPage(0)); pdf.save(root.resolve("copy.pdf").toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(root.resolve("copy.pdf"))
        assertEquals(listOf(3, 3), book.pages.map { it.illustrations.size })
        val original = book.pages[0].illustrations
        val kept = original.first()
        val replacement = listOf(ExtractedIllustration(1, kept.anchorOffset,
            Files.readAllBytes(BookIllustrationAssets.imageFile(library.directory, book.id, kept)), kept.extension,
            kept.width, kept.height, kind = PageVisualKind.TABLE, sourceRegion = kept.sourceRegion))
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.autoCommit = false
            BookPageModelStore.saveInTransaction(db, library.directory, book.id, book.pages.associate { it.number to it.text }, replacement)
            db.rollback()
        }
        assertEquals(book.pages, TextbookLibrary(library.directory).book(book.id).pages)
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.autoCommit = false
            BookPageModelStore.saveInTransaction(db, library.directory, book.id, book.pages.associate { it.number to it.text }, replacement)
            db.commit()
        }
        val updated = TextbookLibrary(library.directory).book(book.id)
        assertEquals(book.pages.map { it.text }, updated.pages.map { it.text })
        assertEquals(listOf(PageVisualKind.TABLE), updated.pages[0].illustrations.map { it.kind })
        assertEquals(book.pages[1], updated.pages[1])
        assertTrue(original.all { Files.exists(BookIllustrationAssets.imageFile(library.directory, book.id, it)) })
    }
}
