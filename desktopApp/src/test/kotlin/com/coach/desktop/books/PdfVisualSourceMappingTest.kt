package com.coach.desktop.books

import com.coach.domain.reader.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.test.*

class PdfVisualSourceMappingTest {
    private val inside = ReaderRect(.1, .1, .2, .1)
    private val outside = ReaderRect(.6, .1, .2, .1)
    private val region = ReaderRect(.05, .05, .3, .2)
    private val frame = PdfPageFrame(PdfPointRect(0.0, 0.0, 500.0, 700.0), PdfPointRect(0.0, 0.0, 500.0, 700.0))
    private fun source(text: String, vararg spans: Pair<IntRange, ReaderRect>): PdfPageText = PdfPageText(
        frame, ReaderTextUnit(ReaderResource(pdfPageIndex0 = 0), text, spans.map { (r, bb) ->
            ReaderTextGeometry(ReaderTextRange(r.first, r.last + 1), ReaderGeometry(frame, listOf(bb)))
        }), emptyList())
    private fun map(source: PdfPageText, text: String = source.unit.text!!) = PdfVisualSourceMapping.map(source, text, region)
    private fun crop(start: Int = 0, end: Int = 2, anchor: Int = start) = BookIllustration(
        1, anchor, "a".repeat(64), "png", 100, 80, kind = PageVisualKind.CALLOUT,
        sourceStart = start, sourceEnd = end, sourceRegion = region)
    private fun visible(text: String, items: List<BookIllustration>, hide: Boolean = true): String {
        val segments = BookIllustrationLayout.segment(text, items, hide)
        val runs = segments.filterIsInstance<BookPageSegment.Text>()
        runs.forEach { assertEquals(text.substring(it.startOffset, it.endOffset), it.text) }
        assertEquals(items.size, segments.filterIsInstance<BookPageSegment.Illustration>().size)
        return runs.joinToString("") { it.text }
    }

    @Test fun whitespaceBetweenExactGlyphsIsTheOnlyGapHiddenWithTheCrop() {
        val text = "A😀\n \tB tail"
        val mapped = map(source(text, (0 until 3) to inside, (6 until 7) to inside, (8 until text.length) to outside))
        assertEquals(PdfVisualMappingStatus.MAPPED, mapped.status)
        assertEquals(ReaderTextRange(0, 7), mapped.singleRange)
        assertEquals(" tail", visible(text, listOf(crop(0, 7))))
        assertEquals(text, visible(text, listOf(crop(0, 7)), hide = false))
    }

    @Test fun interleavedBodyBetweenRepeatedWordsKeepsDisjointRangesAndCannotSupplyAReplacement() {
        val text = "same BODY same"
        val mapped = map(source(text, (0 until 4) to inside, (5 until 9) to outside, (10 until 14) to inside))
        assertEquals(PdfVisualMappingStatus.DISCONTIGUOUS, mapped.status)
        assertEquals(listOf(ReaderTextRange(0, 4), ReaderTextRange(10, 14)), mapped.ranges)
        assertNull(mapped.singleRange)
    }

    @Test fun partialGlyphStaleTextMissingCoverageAndRotationNeverSupplyAReplacement() {
        val good = source("AB", (0 until 2) to inside)
        assertEquals(PdfVisualMappingStatus.STALE_TEXT, map(good, "BA").status)
        assertEquals(PdfVisualMappingStatus.AMBIGUOUS,
            map(source("AB", (0 until 2) to ReaderRect(.3, .1, .2, .1))).status)
        assertEquals(PdfVisualMappingStatus.UNSAFE_GEOMETRY,
            map(good.copy(unmappedRanges = listOf(ReaderTextRange(0, 1)))).status)
        assertEquals(PdfVisualMappingStatus.UNSAFE_GEOMETRY, map(good.copy(frame = frame.copy(rotationClockwise = 90))).status)
        assertEquals(PdfVisualMappingStatus.NO_TEXT, map(source("AB", (0 until 2) to outside)).status)
        assertEquals(PdfVisualMappingStatus.AMBIGUOUS, map(source("😀", (1 until 2) to inside)).status)
    }

    @Test fun oneRunWithMultipleBoxesMustBeEntirelyInsideTheCrop() {
        val good = source("AB", (0 until 2) to inside)
        val mixed = good.copy(unit = good.unit.copy(spans = listOf(
            ReaderTextGeometry(ReaderTextRange(0, 2), ReaderGeometry(frame, listOf(inside, outside))))))
        assertEquals(PdfVisualMappingStatus.AMBIGUOUS, map(mixed).status)
        val overlap = source("ABC", (0 until 2) to inside, (1 until 3) to outside)
        assertEquals(PdfVisualMappingStatus.AMBIGUOUS, map(overlap).status)
    }

    @Test fun anchorBeforeTheRangeNeverHidesInterveningBodyText() {
        val text = "BODY table tail"
        assertEquals(text, visible(text, listOf(crop(5, 10, anchor = 0))))
        assertEquals(text, visible(text, listOf(crop(5, 10, anchor = 7))))
        assertEquals("BODY  tail", visible(text, listOf(crop(5, 10))))
    }

    @Test fun conflictingRangesAndAnIllustrationInsideACropRetainAllSourceRegardlessOfOrder() {
        val text = "0123456789"
        for (items in listOf(listOf(crop(1, 6), crop(4, 9)), listOf(crop(1, 6), crop(4, 9, anchor = 0)),
            listOf(crop(1, 6), crop(1, 6)), listOf(crop(1, 3), crop(5, 7).copy(page = 2)),
            listOf(crop(1, 6), crop(3, 5).copy(kind = PageVisualKind.ILLUSTRATION, sourceStart = null, sourceEnd = null)))) {
            assertEquals(text, visible(text, items))
            assertEquals(text, visible(text, items.reversed()))
        }
        assertEquals("034789", visible(text, listOf(crop(1, 3), crop(5, 7))))
    }

    @Test fun malformedLegacyRangesAndSurrogateBoundariesRetainAllText() {
        val text = "A😀BC"
        for (item in listOf(crop(-1, 3), crop(0, 99), crop(0, 0), crop(2, 4), crop(1, 2),
            crop().copy(sourceStart = null), crop().copy(sourceEnd = null), crop().copy(sourceRegion = null),
            crop().copy(kind = PageVisualKind.ILLUSTRATION))) assertEquals(text, visible(text, listOf(item)))
        assertEquals("ABC", visible(text, listOf(crop(1, 3))))
    }

    @Test fun realPdfImporterCropStorageAndReopenKeepCanonicalTextAndCitations() {
        val root = Files.createTempDirectory("coach-pdf-crop-regression-")
        try {
            val file = root.resolve("self-authored.pdf")
            PDDocument().use { pdf ->
                val page = PDPage(PDRectangle(-40f, -30f, 620f, 790f))
                page.cropBox = PDRectangle(0f, 0f, 500f, 700f)
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    fun text(x: Float, y: Float, value: String) {
                        out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                        out.newLineAtOffset(x, y); out.showText(value); out.endText()
                    }
                    text(40f, 660f, "Body before special blocks.")
                    out.addRect(35f, 540f, 300f, 70f); out.stroke()
                    out.moveTo(35f, 575f); out.lineTo(335f, 575f); out.stroke()
                    text(45f, 590f, "Table header repeated")
                    text(45f, 555f, "Table row repeated")
                    text(45f, 495f, "Formula E = mc2")
                    out.addRect(35f, 400f, 300f, 60f); out.stroke()
                    text(45f, 425f, "Callout repeated")
                    text(40f, 350f, "Body after special blocks.")
                }
                pdf.save(file.toFile())
            }
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(file)
            val original = book.pages.single().text
            val blocks = StudyCoachCourse.blocks(book.id, book.pages)
            val citations = blocks.filter { !it.heading }.map {
                OwnedBookCitation(book.id, 1, it.start, it.end) to it.text
            }
            assertTrue(citations.isNotEmpty())
            val originalResolutions = BookSourceResolver(library).resolveAll(citations)
            assertTrue(originalResolutions.all { it is BookSourceResolution.Resolved })
            val regions = listOf(
                PageVisualKind.TABLE to ReaderRect(.07, 90.0 / 700, .6, 70.0 / 700),
                PageVisualKind.FORMULA to ReaderRect(.07, 190.0 / 700, .6, 25.0 / 700),
                PageVisualKind.CALLOUT to ReaderRect(.07, 240.0 / 700, .6, 60.0 / 700))
            Loader.loadPDF(file.toFile()).use { pdf ->
                val extracted = PdfPageTextExtraction.extract(pdf, 1)
                assertEquals(original, extracted.unit.text)
                val image = PDFRenderer(pdf).renderImage(0, 1f)
                try {
                    val crops = regions.map { (kind, bb) ->
                        val mapped = PdfVisualSourceMapping.map(extracted, original, bb)
                        val range = assertNotNull(mapped.singleRange)
                        val raster = image.getSubimage((bb.x * image.width).toInt(), (bb.y * image.height).toInt(),
                            (bb.width * image.width).toInt(), (bb.height * image.height).toInt())
                        val bytes = ByteArrayOutputStream().also { ImageIO.write(raster, "png", it) }.toByteArray()
                        ExtractedIllustration(1, range.startUtf16, bytes, "png", raster.width, raster.height,
                            kind = kind, sourceStart = range.startUtf16, sourceEnd = range.endUtf16, sourceRegion = bb)
                    }
                    DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
                        db.autoCommit = false
                        BookPageModelStore.saveInTransaction(db, library.directory, book.id, mapOf(1 to original), crops)
                        db.commit()
                    }
                } finally { image.flush() }
            }
            Files.delete(file)
            val reopened = TextbookLibrary(library.directory).book(book.id)
            val page = reopened.pages.single()
            assertEquals(original, page.text)
            assertEquals(blocks, StudyCoachCourse.blocks(reopened.id, reopened.pages))
            assertEquals(originalResolutions, BookSourceResolver(TextbookLibrary(library.directory)).resolveAll(citations))
            assertEquals(regions.map { it.first }, page.illustrations.map { it.kind })
            val expected = StringBuilder(original)
            for (item in page.illustrations.sortedByDescending { it.sourceStart }) expected.delete(item.sourceStart!!, item.sourceEnd!!)
            assertEquals(expected.toString(), visible(original, page.illustrations))
            assertEquals(original, visible(original, page.illustrations, hide = false))
            for (item in page.illustrations) {
                assertTrue(Files.size(BookIllustrationAssets.imageFile(library.directory, book.id, item)) > 0)
                assertEquals(original.substring(item.sourceStart!!, item.sourceEnd!!),
                    page.text.substring(item.sourceStart, item.sourceEnd))
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
