package com.coach.desktop.books

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*

class PdfBookImagesTest {
    private fun PDPageContentStream.text(x: Float, y: Float, value: String) {
        beginText(); setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
        newLineAtOffset(x, y); showText(value); endText()
    }

    private fun fixture(path: Path) {
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(-40f, -30f, 700f, 860f))
            page.cropBox = PDRectangle(10f, 20f, 600f, 760f)
            pdf.addPage(page)
            val bitmap = BufferedImage(70, 40, BufferedImage.TYPE_INT_RGB)
            val drawing = bitmap.createGraphics()
            try {
                drawing.color = Color.WHITE; drawing.fillRect(0, 0, 70, 40)
                drawing.color = Color.BLUE; drawing.fillRect(5, 5, 60, 30)
                drawing.color = Color.RED; drawing.fillOval(20, 10, 20, 20)
            } finally { drawing.dispose() }
            val resource = try { LosslessFactory.createFromImage(pdf, bitmap) } finally { bitmap.flush() }
            PDPageContentStream(pdf, page).use { out ->
                out.text(60f, 740f, "Body before the diagrams.")
                out.drawImage(resource, 60f, 600f, 140f, 80f)
                out.text(60f, 580f, "Figure 1 bitmap")
                out.moveTo(60f, 490f); out.lineTo(60f, 350f); out.lineTo(250f, 350f); out.stroke()
                out.moveTo(70f, 360f); out.lineTo(120f, 410f); out.lineTo(170f, 390f); out.lineTo(240f, 470f); out.stroke()
                out.text(85f, 465f, "rate")
                out.text(60f, 325f, "Figure 2 line chart")
                out.addRect(350f, 360f, 170f, 80f); out.stroke()
                out.text(360f, 420f, "Box label")
                // An embedded bitmap inside an already captured enclosure is not another asset.
                out.drawImage(resource, 370f, 370f, 80f, 40f)
                out.text(350f, 340f, "Figure 3 enclosure")
                out.text(60f, 240f, "Body after the diagrams.")
            }
            val second = PDPage(); pdf.addPage(second)
            PDPageContentStream(pdf, second).use { it.text(60f, 700f, "Another page with no illustration.") }
            pdf.save(path.toFile())
        }
    }

    private fun <T> temporary(block: (Path) -> T): T {
        val root = Files.createTempDirectory("coach-pdf-images-")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test fun ordinaryImportKeepsBitmapLineArtAndEnclosureOnceWithExactPageTextAndRaster() = temporary { root ->
        val file = root.resolve("self-authored.pdf"); fixture(file)
        val canonical = Loader.loadPDF(file.toFile()).use { doc ->
            (1..doc.numberOfPages).map { PdfPageTextExtraction.extract(doc, it).unit.text }
        }
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(file)
        assertEquals(canonical, book.pages.map { it.text })
        assertEquals(listOf(3, 0), book.pages.map { it.illustrations.size })
        val page = book.pages.first()
        assertEquals(setOf("Figure 1 bitmap", "Figure 2 line chart", "Figure 3 enclosure"), page.illustrations.map { it.caption }.toSet())
        for (caption in listOf("Figure 1 bitmap", "Figure 2 line chart")) {
            val image = page.illustrations.single { it.caption == caption }
            assertEquals(1, image.page)
            assertEquals(page.text.indexOf(caption), image.anchorOffset)
            assertTrue(BookIllustrationAnchors.isValidAnchor(page.text, image.anchorOffset))
            assertNull(image.sourceStart); assertNull(image.sourceEnd)
        }
        Loader.loadPDF(file.toFile()).use { doc ->
            val raster = PDFRenderer(doc).renderImage(0, 2f)
            try {
                for (asset in page.illustrations) {
                    val region = assertNotNull(asset.sourceRegion)
                    val x = (region.x * 1200).roundToInt()
                    val y = (region.y * 1520).roundToInt()
                    val crop = ImageIO.read(BookIllustrationAssets.imageFile(library.directory, book.id, asset).toFile())
                    try {
                        assertEquals(asset.width, crop.width); assertEquals(asset.height, crop.height)
                        for (cy in 0 until crop.height step 13) for (cx in 0 until crop.width step 13) {
                            assertEquals(raster.getRGB(x + cx, y + cy), crop.getRGB(cx, cy))
                        }
                    } finally { crop.flush() }
                }
            } finally { raster.flush() }
        }
        val blocks = StudyCoachCourse.blocks(book.id, book.pages)
        val citations = blocks.filter { !it.heading }.map { OwnedBookCitation(book.id, it.page, it.start, it.end) to it.text }
        val resolved = BookSourceResolver(library).resolveAll(citations)
        assertTrue(resolved.isNotEmpty() && resolved.all { it is BookSourceResolution.Resolved })
        Files.delete(file)
        val reopenedLibrary = TextbookLibrary(library.directory)
        val reopened = reopenedLibrary.book(book.id)
        assertEquals(book.pages, reopened.pages)
        assertEquals(blocks, StudyCoachCourse.blocks(reopened.id, reopened.pages))
        assertEquals(resolved, BookSourceResolver(reopenedLibrary).resolveAll(citations))
    }

    @Test fun unsupportedGeometryStaleTextAndCancellationNeverCreateGuessedSupplements() = temporary { root ->
        val file = root.resolve("self-authored.pdf"); fixture(file)
        Loader.loadPDF(file.toFile()).use { doc ->
            val page = BookPage(1, PdfPageTextExtraction.extract(doc, 1).unit.text!!)
            assertTrue(PdfBookImages.extract(doc, page.copy(text = "stale" + page.text), emptyList(), BookImportControl()).isEmpty())
            assertTrue(PdfBookImages.extract(doc, page.copy(ocr = true), emptyList(), BookImportControl()).isEmpty())
            assertFailsWith<BookImportCancelled> {
                PdfBookImages.extract(doc, page, emptyList(), BookImportControl(cancelledExternally = { true }))
            }
            doc.getPage(0).rotation = 90
            assertTrue(PdfBookImages.extract(doc, page, emptyList(), BookImportControl()).isEmpty())
        }
    }
}
