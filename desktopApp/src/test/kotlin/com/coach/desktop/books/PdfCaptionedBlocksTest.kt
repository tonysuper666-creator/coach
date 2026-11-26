package com.coach.desktop.books

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.util.Matrix
import kotlin.test.*

class PdfCaptionedBlocksTest {
    private val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

    private fun PDPageContentStream.label(x: Int, y: Int, text: String, size: Int = 10) {
        beginText()
        setFont(font, size.toFloat())
        newLineAtOffset(x.toFloat(), y.toFloat())
        showText(text)
        endText()
    }

    private fun PDPageContentStream.rule(x: Int, y: Int, endX: Int, endY: Int) {
        moveTo(x.toFloat(), y.toFloat())
        lineTo(endX.toFloat(), endY.toFloat())
        stroke()
    }

    private fun extract(draw: PDPageContentStream.() -> Unit): Pair<String, List<ExtractedIllustration>> =
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(500f, 700f))
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use(draw)
            val text = PdfPageTextExtraction.extract(pdf, 1).unit.text!!
            text to importedPdfVisuals(pdf, listOf(BookPage(1, text)), BookImportControl(), includeLayoutCandidates = false)
        }

    @Test fun sideBySideGridsKeepOneSharedTitleAndFootnote() {
        val (text, crops) = extract {
            label(50, 650, "This paragraph precedes the table.")
            label(50, 600, "Table 2. Daily totals")
            for (x in listOf(50, 220)) {
                for (y in listOf(587, 566, 530)) rule(x, y, x + 150, y)
                label(x + 6, 574, "Quantity")
                label(x + 6, 548, "Twenty")
            }
            label(50, 519, "Source: Both columns.", 7)
            label(50, 480, "This paragraph follows the table.")
        }
        val block = crops.single()
        assertEquals(PageVisualKind.TABLE, block.kind)
        val captured = text.substring(block.sourceStart!!, block.sourceEnd!!)
        assertTrue(captured.startsWith("Table 2. Daily totals"))
        assertTrue(captured.endsWith("Source: Both columns."))
        assertEquals(2, Regex("Twenty").findAll(captured).count())
        assertFalse("paragraph" in captured)
        assertNull(block.table) // A shared caption does not establish per-panel row origins.
    }

    @Test fun uprightCropAndSourceRangeIncludeRotatedTicksAndCaption() {
        val (text, crops) = extract {
            label(50, 673, "Running head")
            saveGraphicsState()
            transform(Matrix(0f, 1f, -1f, 0f, 390f, 80f))
            for (y in listOf(50, 110, 170)) rule(40, y, 460, y)
            rule(40, 50, 40, 170)
            label(48, 62, "10")
            label(90, 120, "20")
            label(150, 180, "30")
            label(320, 190, "40")
            label(80, 35, "Axis units")
            label(40, 16, "Figure 3. Rotated chart")
            restoreGraphicsState()
        }
        val block = crops.single()
        assertEquals(PageVisualKind.DIAGRAM, block.kind)
        assertTrue(block.width > block.height)
        val captured = text.substring(block.sourceStart!!, block.sourceEnd!!)
        for (label in listOf("10", "20", "30", "40", "Axis units", "Figure 3.")) assertTrue(label in captured)
        assertFalse("Running head" in captured)
    }

    @Test fun displayedSumIncludesBothLimits() {
        val (text, crops) = extract {
            label(50, 620, "The equation below gives the result.")
            label(110, 575, "n", 7)
            beginText()
            setFont(PDType1Font(Standard14Fonts.FontName.SYMBOL), 14f)
            newLineAtOffset(108f, 560f)
            showText("∑")
            endText()
            label(82, 560, "S =")
            label(127, 560, "x")
            label(110, 548, "i=1", 7)
            label(50, 510, "The explanation continues here.")
        }
        val block = crops.single()
        assertEquals(PageVisualKind.FORMULA, block.kind)
        val captured = text.substring(block.sourceStart!!, block.sourceEnd!!)
        for (part in listOf("n", "∑", "i=1", "x")) assertTrue(part in captured)
        assertFalse("explanation" in captured)
    }

    @Test fun inlineOperatorDoesNotTurnItsSurroundingParagraphIntoACrop() {
        val (_, crops) = extract {
            label(50, 620, "The explanation begins here.")
            label(50, 600, "The inline expression is")
            beginText()
            setFont(PDType1Font(Standard14Fonts.FontName.SYMBOL), 12f)
            newLineAtOffset(165f, 600f)
            showText("∑")
            endText()
            label(180, 600, "x = y inside a sentence.")
            label(50, 565, "Another paragraph follows here.")
        }
        assertTrue(crops.isEmpty())
    }

    @Test fun numberedDisplayKeepsItsSmallAnnotationAndRejectsAnInlineReference() {
        val (text, crops) = extract {
            label(40, 640, "This paragraph precedes the equation.")
            label(100, 610, "rate", 6)
            label(90, 595, "x = y")
            label(430, 595, "(3.2)")
            label(40, 550, "The next paragraph cites expression (3.2).")
        }
        val block = crops.single()
        val captured = text.substring(block.sourceStart!!, block.sourceEnd!!)
        assertEquals(PageVisualKind.FORMULA, block.kind)
        for (part in listOf("rate", "x = y", "(3.2)")) assertTrue(part in captured)
        assertFalse("paragraph" in captured)
    }
}
