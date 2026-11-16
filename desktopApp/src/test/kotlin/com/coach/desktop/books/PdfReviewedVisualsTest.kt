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
import kotlin.test.*

class PdfReviewedVisualsTest {
    private val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val table = PdfVisualSelection(1, 50, 100, 750, 500, PageVisualKind.TABLE)
    private val figure = PdfVisualSelection(1, 50, 700, 750, 1100, PageVisualKind.DIAGRAM)
    private fun PDPageContentStream.text(y: Float, value: String) {
        beginText(); setFont(font, 12f); newLineAtOffset(40f, y); showText(value); endText()
    }
    private fun fixture(file: Path, rotation: Int = 0) {
        PDDocument().use { pdf ->
            repeat(2) {
                val page = PDPage(PDRectangle(-40f, -30f, 620f, 790f))
                page.cropBox = PDRectangle(10f, 20f, 500.25f, 700.25f)
                page.rotation = rotation
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.text(690f, "Body before the table.")
                    out.text(650f, "Table title repeated")
                    out.text(610f, "Header A       Header B")
                    out.text(580f, "Cell one         Cell two")
                    out.text(520f, "Table note and source repeated")
                    out.text(450f, "Body between the objects.")
                    out.text(350f, "Top annotation of borderless figure")
                    out.moveTo(60f, 280f); out.lineTo(120f, 300f); out.lineTo(200f, 260f); out.stroke()
                    out.text(210f, "Figure caption repeated")
                    out.text(190f, "Credit repeated")
                    out.text(130f, "Body after the figure.")
                }
            }
            pdf.save(file.toFile())
        }
    }
    private fun pages(file: Path): List<BookPage> = Loader.loadPDF(file.toFile()).use { pdf ->
        (1..pdf.numberOfPages).map { BookPage(it, PdfPageTextExtraction.extract(pdf, it).unit.text!!) }
    }
    private fun <T> temp(action: (Path) -> T): T {
        val root = Files.createTempDirectory("coach-reviewed-objects-")
        try { return action(root) } finally { root.toFile().deleteRecursively() }
    }
    private fun approved(draft: PdfVisualPreview) = draft.review.copy(decision = PdfVisualDecision.COMPLETE_OBJECT_WITH_LABELS)
    private fun visible(page: BookPage, hide: Boolean) = BookIllustrationLayout.segment(page.text, page.illustrations, hide)
        .filterIsInstance<BookPageSegment.Text>().joinToString("") { it.text }

    @Test fun completeBorderlessObjectsPersistWithLabelsAndExactPixelsWithoutChangingTextOrCitations() = temp { root ->
        val file = root.resolve("own.pdf"); fixture(file)
        val library = TextbookLibrary(root.resolve("library"))
        val before = library.importBook(file)
        assertTrue(before.pages.all { it.illustrations.isEmpty() })
        val blocks = StudyCoachCourse.blocks(before.id, before.pages)
        val citations = blocks.filter { !it.heading }.map { OwnedBookCitation(before.id, it.page, it.start, it.end) to it.text }
        val resolutions = BookSourceResolver(library).resolveAll(citations)
        val bytes = Files.readAllBytes(file)
        val drafts = PdfReviewedVisuals.preview(bytes, before.pages, listOf(table, figure))
        assertTrue(drafts.all { it.review.decision == PdfVisualDecision.UNREVIEWED })
        val reviews = drafts.map(::approved)
        val saved = library.addReviewedPdfVisuals(before.id, reviews)
        assertEquals(listOf(2, 0), saved.pages.map { it.illustrations.size })
        assertEquals(before.pages.map { it.text }, saved.pages.map { it.text })
        assertEquals(blocks, StudyCoachCourse.blocks(saved.id, saved.pages))
        assertEquals(resolutions, BookSourceResolver(library).resolveAll(citations))
        Loader.loadPDF(file.toFile()).use { pdf ->
            val full = PDFRenderer(pdf).renderImage(0, 2f)
            try {
                for ((i, selection) in listOf(table, figure).withIndex()) {
                    val crop = saved.pages[0].illustrations[i]
                    assertNull(crop.sourceStart); assertNull(crop.sourceEnd)
                    val png = ImageIO.read(BookIllustrationAssets.imageFile(library.directory, before.id, crop).toFile())
                    try {
                        for (y in 0 until png.height) for (x in 0 until png.width)
                            assertEquals(full.getRGB(selection.left + x, selection.top + y), png.getRGB(x, y))
                    } finally { png.flush() }
                }
            } finally { full.flush() }
        }
        // Both caption and source/credit are inside each one-piece raster, not detached metadata.
        for ((index, words) in listOf(listOf("Table title", "Table note"), listOf("Top annotation", "Figure caption", "Credit")).withIndex()) {
            val region = assertNotNull(saved.pages[0].illustrations[index].sourceRegion)
            Loader.loadPDF(bytes).use { pdf ->
                val source = PdfPageTextExtraction.extract(pdf, 1)
                val text = PdfVisualSourceMapping.map(source, before.pages[0].text, region).ranges
                    .joinToString("") { before.pages[0].text.substring(it.startUtf16, it.endUtf16) }
                words.forEach { assertTrue(it in text) }
                assertFalse("Body between" in text)
            }
        }
        for (page in saved.pages) for (hide in listOf(false, true)) assertEquals(page.text, visible(page, hide))
        Files.delete(file)
        val reopened = TextbookLibrary(library.directory)
        assertEquals(saved.pages, reopened.book(before.id).pages)
        assertEquals(reviews.toSet(), reopened.pdfVisualReviews(before.id).toSet())
        assertEquals(saved.pages, reopened.addReviewedPdfVisuals(before.id, reviews).pages) // Idempotent.
        val second = PdfReviewedVisuals.preview(bytes, before.pages, listOf(table.copy(page = 2))).map(::approved)
        assertEquals(listOf(2, 1), reopened.addReviewedPdfVisuals(before.id, second).pages.map { it.illustrations.size })
    }

    @Test fun contactSheetsRejectedObjectsAndStalePdfTextOrCropNeverAuthorizeImport() = temp { root ->
        val file = root.resolve("own.pdf"); fixture(file)
        val bytes = Files.readAllBytes(file); val pages = pages(file)
        val draft = PdfReviewedVisuals.preview(bytes, pages, listOf(table)).single()
        val review = approved(draft)
        for (bad in listOf(draft.review, review.copy(decision = PdfVisualDecision.REJECTED),
            review.copy(schema = 2), review.copy(documentSha256 = "0".repeat(64)),
            review.copy(pageTextSha256 = "0".repeat(64)), review.copy(pngSha256 = "0".repeat(64)),
            review.copy(selection = table.copy(right = table.right + 1)))) {
            assertFailsWith<IllegalArgumentException> { PdfReviewedVisuals.extract(bytes, pages, listOf(bad)) }
        }
        assertFailsWith<IllegalArgumentException> { PdfReviewedVisuals.extract(bytes,
            listOf(pages[0].copy(text = pages[0].text + "changed"), pages[1]), listOf(review)) }
        assertFailsWith<IllegalArgumentException> { PdfReviewedVisuals.preview(bytes,
            listOf(pages[0].copy(ocr = true)), listOf(table)) }
        val rotated = root.resolve("rotated.pdf"); fixture(rotated, 90)
        assertFailsWith<IllegalArgumentException> { PdfReviewedVisuals.preview(Files.readAllBytes(rotated), pages(rotated), listOf(table)) }
        Unit
    }

    @Test fun duplicateNestedOrPartiallyOverlappingBoxesAreRejectedAsASetAndCannotEraseAssets() = temp { root ->
        val file = root.resolve("own.pdf"); fixture(file)
        val library = TextbookLibrary(root.resolve("library")); val book = library.importBook(file)
        val bytes = Files.readAllBytes(file)
        val review = approved(PdfReviewedVisuals.preview(bytes, book.pages, listOf(table)).single())
        val enlarged = approved(PdfReviewedVisuals.preview(bytes, book.pages, listOf(table.copy(right = 800))).single())
        for (set in listOf(listOf(review, review), listOf(review, enlarged))) {
            assertFailsWith<IllegalArgumentException> { library.addReviewedPdfVisuals(book.id, set) }
            assertTrue(library.book(book.id).pages.all { it.illustrations.isEmpty() })
            assertTrue(library.pdfVisualReviews(book.id).isEmpty())
        }
        val saved = library.addReviewedPdfVisuals(book.id, listOf(review))
        assertFailsWith<IllegalArgumentException> { library.addReviewedPdfVisuals(book.id, listOf(enlarged)) }
        assertEquals(saved.pages, library.book(book.id).pages)
        assertEquals(listOf(review), library.pdfVisualReviews(book.id))
    }

    @Test fun receiptWriteFailureRollsBackAssetsAndRetainsExistingPages() = temp { root ->
        val file = root.resolve("own.pdf"); fixture(file)
        val library = TextbookLibrary(root.resolve("library")); val book = library.importBook(file)
        val reviews = PdfReviewedVisuals.preview(Files.readAllBytes(file), book.pages, listOf(table, figure)).map(::approved)
        library.addReviewedPdfVisuals(book.id, listOf(reviews[0]))
        val before = library.book(book.id)
        val dir = BookIllustrationAssets.bookDirectory(library.directory, book.id)
        val files = Files.list(dir).use { it.toList().toSet() }
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER fail_review BEFORE INSERT ON book_pdf_visual_reviews BEGIN SELECT RAISE(ABORT,'test disk failure'); END") }
        }
        assertFails { library.addReviewedPdfVisuals(book.id, listOf(reviews[1])) }
        assertEquals(before.pages, library.book(book.id).pages)
        assertEquals(files, Files.list(dir).use { it.toList().toSet() })
        assertEquals(listOf(reviews[0]), library.pdfVisualReviews(book.id))
    }

    @Test fun missingPagesPartialGlyphsAndInvalidPixelBoxesCannotProducePreviews() = temp { root ->
        val file = root.resolve("own.pdf"); fixture(file)
        val bytes = Files.readAllBytes(file); val pages = pages(file)
        for (selection in listOf(table.copy(page = 3), table.copy(left = -1), table.copy(right = 5000),
            table.copy(bottom = table.top), table.copy(top = 139))) {
            assertFailsWith<IllegalArgumentException> { PdfReviewedVisuals.preview(bytes, pages, listOf(selection)) }
        }
        assertFailsWith<BookImportCancelled> { PdfReviewedVisuals.preview(bytes, pages, listOf(table),
            BookImportControl(cancelledExternally = { true })) }
        Unit
    }
}
