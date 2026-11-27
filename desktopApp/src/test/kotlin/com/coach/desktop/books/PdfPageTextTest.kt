package com.coach.desktop.books

import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderTextRange
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.*

class PdfPageTextTest {
    private val font get() = PDType1Font(Standard14Fonts.FontName.HELVETICA)

    private fun PDPageContentStream.text(x: Float, y: Float, text: String, size: Float = 12f) {
        beginText(); setFont(font, size); newLineAtOffset(x, y); showText(text); endText()
    }

    private fun baseline(document: PDDocument, page: Int): String = TextbookExtraction.clean(
        PDFTextStripper().apply { startPage = page; endPage = page; sortByPosition = true; paragraphEnd = "\n\n" }
            .getText(document))

    @Test fun recurringMarginsAreRemovedOnlyAtTheirPrintedPosition() {
        PDDocument().use { doc ->
            for (n in 1..4) {
                val page = PDPage(); doc.addPage(page)
                PDPageContentStream(doc, page).use { out ->
                    out.text(40f, 758f, "Journal of Reading")
                    out.text(40f, 640f, "Section $n", 16f)
                    out.text(40f, 580f, "Journal of Reading")
                    out.text(40f, 36f, "Publication information     $n / 4")
                    out.text(40f, 22f, "Journal")
                    out.text(300f, 22f, "$n")
                    out.text(425f, 22f, "Publisher")
                }
            }
            val fast = PdfPageTextExtraction.extractPlainTextAndTypography(doc, 2)
            val exact = PdfPageTextExtraction.extract(doc, 2)
            assertEquals(fast.text, exact.unit.text)
            assertEquals(fast.text, PdfPageTextExtraction.extractPlainText(doc, 2))
            assertEquals(listOf("Section 2", "Journal of Reading"), fast.lines.map { it.text })
            assertFalse("Publication" in fast.text)
            assertEquals(1, Regex("Journal of Reading").findAll(fast.text).count())
            assertFalse(LiteraturePdfLayout.extract(doc, 2).any { "Publication" in it.text })
        }
    }

    @Test fun readsEachColumnAfterTheSpanningHeadingWithMatchingQuoteOffsets() {
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            PDPageContentStream(doc, page).use { out ->
                out.text(160f, 720f, "This full width heading crosses the gutter", 16f)
                for (i in 1..4) {
                    out.text(322f, 695f - 16f * i, "Right paragraph number $i.")
                    out.text(45f, 692f - 16f * i, "Left paragraph number $i.")
                }
            }
            val text = PdfPageTextExtraction.extractPlainText(doc, 1)
            val fast = PdfPageTextExtraction.extractPlainTextAndTypography(doc, 1)
            val exact = PdfPageTextExtraction.extract(doc, 1)
            assertEquals(text, exact.unit.text)
            assertEquals(text, fast.text)
            val leftEnd = text.indexOf("Left paragraph number 4.")
            val rightStart = text.indexOf("Right paragraph number 1.")
            assertTrue(leftEnd > text.indexOf("heading") && rightStart > leftEnd)
            assertEquals("Left paragraph number 4.", fast.lines[4].text)
            assertTrue(exact.unit.spans.single { it.range.startUtf16 == rightStart }.geometry.boxes.single().x > 0.5)
            val previous = baseline(doc, 1)
            assertNotEquals(text, previous)
            assertEquals(previous, PdfPageTextExtraction.extractMatching(doc, BookPage(1, previous))?.unit?.text)
        }
    }

    @Test fun paperTitleWrapperExposesSectionBookmarksAndSamePageOffsets() {
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            doc.documentInformation.title = "An example paper"
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            val wrapper = PDOutlineItem().apply { title = doc.documentInformation.title; setDestination(page) }
            outline.addLast(wrapper)
            for (label in listOf("Introduction", "Methods")) {
                wrapper.addLast(PDOutlineItem().apply { title = label; setDestination(page) })
            }
            val items = extractBookPdfOutline(doc)
            assertEquals(listOf("Introduction", "Methods"), items.map { it.title })
            assertTrue(items.all { it.level == 1 })
            val text = "1 Introduction\n\nA complete opening paragraph introduces the subject.\n\n2 Methods\n\nA different paragraph explains the method."
            val plan = BookTocWaterfall.plan("paper", wrapper.title, "pdf", listOf(BookPage(1, text)), items)
            assertEquals(listOf(0, text.indexOf("2 Methods")), plan.tocNodes.map { it.startOffset })
        }
    }

    @Test fun pdfSummaryUsesCompleteSentencesAcrossPrintedLineBreaks() {
        val text = "a continuation from the previous page.\nThe first complete sentence extends across\n" +
            "a printed line and retains its original ending. An unfinished last line"
        val lesson = BookLesson("part", "Section", listOf(SourceBlock("p", 2, 0, text.length, false, text)))
        assertEquals(listOf("The first complete sentence extends across a printed line and retains its original ending."),
            StudyCoachCourse.localSectionSummary(lesson, "pdf"))
    }

    @Test fun typographyUsesPrintedSizeWhenTheTextMatrixScalesAOnePointFont() {
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            PDPageContentStream(doc, page).use { out ->
                for ((y, size, label) in listOf(Triple(620f, 14f, "Topic"), Triple(593f, 10f, "Body text"))) {
                    out.beginText()
                    out.setFont(font, 1f)
                    out.setTextMatrix(Matrix(size, 0f, 0f, size, 40f, y))
                    out.showText(label)
                    out.endText()
                }
            }
            val lines = PdfPageTextExtraction.extractPlainTextAndTypography(doc, 1).lines
            assertEquals(listOf(14.0, 10.0), lines.map { it.fontSizePt })
        }
    }

    @Test fun complexPageHasExactlyTheExistingTextAndEveryVisibleCharacterHasAGlyphBox() {
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(620f, 790f)); document.addPage(page)
            PDPageContentStream(document, page).use { out ->
                out.text(50f, 740f, "Chapter 1  Layout", 18f)
                // Deliberately emit the right column first: content-stream order is not reading order.
                out.text(340f, 690f, "Right column repeated phrase.")
                out.text(50f, 690f, "Left column repeated phrase.")
                out.text(340f, 660f, "A second right paragraph.")
                out.text(50f, 660f, "A second left paragraph.")
                out.addRect(50f, 430f, 500f, 120f); out.stroke()
                out.moveTo(50f, 490f); out.lineTo(550f, 490f); out.stroke()
                out.moveTo(300f, 430f); out.lineTo(300f, 550f); out.stroke()
                out.text(65f, 520f, "Quantity"); out.text(320f, 520f, "Value")
                out.text(65f, 460f, "Mass"); out.text(320f, 460f, "12 kg")
                out.text(65f, 390f, "Formula: E = m c^2")
                out.addRect(50f, 270f, 500f, 75f); out.stroke()
                out.text(65f, 310f, "Knowledge box: keep this whole region.")
            }
            val result = PdfPageTextExtraction.extract(document, 1)
            assertEquals(baseline(document, 1), result.unit.text)
            assertTrue(result.unmappedRanges.isEmpty(), result.unmappedRanges.toString())
            val text = result.unit.text!!
            for (i in text.indices.filter { !text[it].isWhitespace() }) {
                assertEquals(1, result.unit.spans.count { i in it.range.startUtf16 until it.range.endUtf16 },
                    "Missing or overlapping glyph at UTF-16 $i (${text[i]})")
            }
            assertTrue(text.contains("Right column repeated phrase."))
            assertTrue(text.contains("Knowledge box:"))
            assertTrue(result.unit.spans.all { it.geometry.boxes.size == 1 })
            val phrase = "Left column repeated phrase."
            val start = text.indexOf(phrase)
            assertTrue(start >= 0)
            val range = ReaderTextRange(start, start + phrase.length)
            val boxes = PdfTextSelection.boxesForRange(result.unit, range)
            val a = boxes.first(); val b = boxes.last()
            val selected = PdfTextSelection.rangeBetweenPoints(result.unit,
                a.x + a.width / 2, a.y + a.height / 2, b.x + b.width / 2, b.y + b.height / 2)
            assertEquals(range, selected)
            assertEquals(phrase, text.substring(selected!!.startUtf16, selected.endUtf16))
            assertEquals(selected, PdfTextSelection.rangeBetweenPoints(result.unit,
                b.x + b.width / 2, b.y + b.height / 2, a.x + a.width / 2, a.y + a.height / 2))
            val root = Path.of("build/screens/pdf-page-text")
            Files.createDirectories(root)
            document.save(root.resolve("complex-layout.pdf").toFile())
            for (scale in listOf(1f, 1.5f)) {
                verifyInkAndSaveOverlay(document, 0, result, scale, "complex")
            }
        }
    }

    @Test fun cropBoxPageRotationAndTextRotationAreAppliedExactlyOnceAtBothScales() {
        PDDocument().use { document ->
            for (rotation in listOf(0, 90, 180, 270)) {
                val page = PDPage(PDRectangle(700f, 700f))
                page.cropBox = PDRectangle(50f, 70f, 500f, 400f)
                page.rotation = rotation
                document.addPage(page)
                PDPageContentStream(document, page).use { out ->
                    out.text(110f, 400f, "Horizontal source")
                    out.beginText(); out.setFont(font, 12f)
                    out.setTextMatrix(Matrix.getRotateInstance(Math.PI / 2, 430f, 180f))
                    out.showText("Vertical source"); out.endText()
                }
            }
            val first = PdfPageTextExtraction.extract(document, 1)
            val source = first.unit.spans.first { first.unit.text!!.substring(it.range.startUtf16, it.range.endUtf16) == "H" }
            assertEquals(60.0 / 500, source.geometry.boxes.single().x, .00001)
            assertEquals(70.0 / 400, source.geometry.boxes.single().let { it.y + it.height }, .00001)
            for (page in 1..4) {
                val result = PdfPageTextExtraction.extract(document, page)
                assertEquals(baseline(document, page), result.unit.text)
                assertTrue(result.unmappedRanges.isEmpty(), result.unmappedRanges.toString())
                assertEquals(first.unit.spans.map { it.geometry.boxes }, result.unit.spans.map { it.geometry.boxes })
                val span = result.unit.spans.first()
                val b = span.geometry.displayBoxes().single()
                assertEquals(span.range, PdfTextSelection.rangeBetweenPoints(result.unit,
                    b.x + b.width / 2, b.y + b.height / 2, b.x + b.width / 2, b.y + b.height / 2))
                for (scale in listOf(1f, 1.5f)) verifyInkAndSaveOverlay(document, page - 1, result, scale, "crop-rotation")
            }
        }
    }

    @Test fun surrogatePairsLigaturesAndRemovedNulsKeepExactUtf16Offsets() {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(); document.addPage(page)
            val mappedFont = font
            val cmap = """
                /CIDInit /ProcSet findresource begin
                12 dict begin begincmap
                /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                /CMapName /TestUnicode def /CMapType 2 def
                1 begincodespacerange <00> <FF> endcodespacerange
                4 beginbfchar
                <41> <D83DDE00>
                <42> <FB03>
                <43> <0000>
                <44> <005A>
                endbfchar
                endcmap CMapName currentdict /CMap defineresource pop end end
            """.trimIndent()
            val stream = PDStream(document)
            stream.createOutputStream().use { it.write(cmap.toByteArray(Charsets.US_ASCII)) }
            mappedFont.cosObject.setItem(COSName.TO_UNICODE, stream)
            PDPageContentStream(document, page).use { out ->
                out.beginText(); out.setFont(mappedFont, 14f); out.newLineAtOffset(70f, 700f)
                out.showText("ABCD"); out.endText()
            }
            document.save(bytes)
        }
        Loader.loadPDF(bytes.toByteArray()).use { document ->
            val result = PdfPageTextExtraction.extract(document, 1)
            assertEquals(baseline(document, 1), result.unit.text)
            assertTrue(result.unit.text!!.startsWith("\uD83D\uDE00ffiZ"), result.unit.text)
            assertTrue(result.unmappedRanges.isEmpty(), result.unmappedRanges.toString())
            assertEquals(listOf(0 to 2, 2 to 5, 5 to 6),
                result.unit.spans.map { it.range.startUtf16 to it.range.endUtf16 })
            val emoji = result.unit.spans.first().geometry.displayBoxes().single()
            assertEquals(ReaderTextRange(0, 2), PdfTextSelection.rangeBetweenPoints(result.unit,
                emoji.x + emoji.width / 2, emoji.y + emoji.height / 2,
                emoji.x + emoji.width / 2, emoji.y + emoji.height / 2))
        }
    }

    @Test fun emptyPageDoesNotBorrowTextOrGeometryFromItsNeighbour() {
        PDDocument().use { document ->
            val first = PDPage(); document.addPage(first); document.addPage(PDPage())
            PDPageContentStream(document, first).use { it.text(70f, 700f, "Only on page one") }
            assertTrue(PdfPageTextExtraction.extract(document, 1).unit.spans.isNotEmpty())
            val empty = PdfPageTextExtraction.extract(document, 2)
            assertEquals(baseline(document, 2), empty.unit.text)
            assertTrue(empty.unit.spans.isEmpty())
            assertTrue(empty.unmappedRanges.isEmpty())
            assertEquals(1, empty.unit.resource.pdfPageIndex0)
            assertNull(PdfTextSelection.rangeBetweenPoints(empty.unit, 0.0, 0.0, 1.0, 1.0))
        }
    }

    @Test fun extractionChecksPageAndExistingPdfPermissions() {
        PDDocument().use { document ->
            document.addPage(PDPage())
            assertFailsWith<IllegalArgumentException> { PdfPageTextExtraction.extract(document, 0) }
            assertFailsWith<IllegalArgumentException> { PdfPageTextExtraction.extract(document, 2) }
            document.currentAccessPermission.setCanExtractContent(false)
            assertFailsWith<IllegalArgumentException> { PdfPageTextExtraction.extract(document, 1) }
        }
    }

    private fun verifyInkAndSaveOverlay(document: PDDocument, page: Int, result: PdfPageText, scale: Float, name: String) {
        val image = PDFRenderer(document).renderImage(page, scale)
        val visible = result.unit.spans.filter {
            result.unit.text!!.substring(it.range.startUtf16, it.range.endUtf16).any { c -> !c.isWhitespace() }
        }
        for (span in visible) {
            val box = span.geometry.displayBoxes().single()
            val x0 = floor(box.x * image.width).toInt().coerceIn(0, image.width - 1)
            val x1 = ceil((box.x + box.width) * image.width).toInt().coerceIn(x0 + 1, image.width)
            val y0 = floor(box.y * image.height).toInt().coerceIn(0, image.height - 1)
            val y1 = ceil((box.y + box.height) * image.height).toInt().coerceIn(y0 + 1, image.height)
            assertTrue((y0 until y1).any { y -> (x0 until x1).any { x ->
                val rgb = image.getRGB(x, y)
                ((rgb shr 16) and 255) < 200 && ((rgb shr 8) and 255) < 200 && (rgb and 255) < 200
            } }, "Glyph has no rendered ink at page ${page + 1}, scale $scale: $span")
        }
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(200, 35, 45, 150)
            for (span in visible) {
                val b: ReaderRect = span.geometry.displayBoxes().single()
                graphics.drawRect((b.x * image.width).toInt(), (b.y * image.height).toInt(),
                    maxOf(1, (b.width * image.width).toInt()), maxOf(1, (b.height * image.height).toInt()))
            }
        } finally { graphics.dispose() }
        val root = Path.of("build/screens/pdf-page-text")
        Files.createDirectories(root)
        ImageIO.write(image, "png", root.resolve("$name-p${page + 1}-${(scale * 100).toInt()}.png").toFile())
    }
}
