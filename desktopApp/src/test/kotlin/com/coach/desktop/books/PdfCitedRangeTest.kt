package com.coach.desktop.books

import com.coach.desktop.reader.Located
import com.coach.desktop.reader.PageBoxes
import com.coach.desktop.reader.PdfBoxWordStripper
import com.coach.desktop.reader.QuoteLocator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import kotlin.test.*

/** Synthetic ToUnicode mappings exercise CJK, presentation ligatures and UTF-16 surrogate pairs. */
internal fun citedRangePdf(vararg rows: Pair<Float, String>, rotation: Int = 0): ByteArray {
    val bytes = ByteArrayOutputStream()
    PDDocument().use { pdf ->
        val page = PDPage().apply { this.rotation = rotation }; pdf.addPage(page)
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val cmap = """
            /CIDInit /ProcSet findresource begin
            12 dict begin begincmap
            /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
            /CMapName /CitedRangeUnicode def /CMapType 2 def
            1 begincodespacerange <00> <FF> endcodespacerange
            7 beginbfchar
            <41> <4E2D> <42> <6587> <43> <65E0> <44> <7A7A> <45> <683C>
            <46> <FB03> <47> <D83DDE00>
            endbfchar
            endcmap CMapName currentdict /CMap defineresource pop end end
        """.trimIndent()
        val stream = PDStream(pdf)
        stream.createOutputStream().use { it.write(cmap.toByteArray(Charsets.US_ASCII)) }
        font.cosObject.setItem(COSName.TO_UNICODE, stream)
        PDPageContentStream(pdf, page).use { out ->
            for ((y, text) in rows) {
                out.beginText(); out.setFont(font, 12f); out.newLineAtOffset(50f, y)
                out.showText(text); out.endText()
            }
        }
        pdf.save(bytes)
    }
    return bytes.toByteArray()
}

class PdfCitedRangeTest {
    @Test fun wholeChineseBlockReproducesTokenFailureButVerifiedUtf16BoxesAreFound() {
        Loader.loadPDF(citedRangePdf(700f to "ABCDE oFce.")).use { pdf ->
            val extracted = PdfPageTextExtraction.extract(pdf, 1)
            val text = extracted.unit.text!!
            val quote = text.trim()
            assertEquals("中文无空格 office.", quote)
            val legacy = PdfBoxWordStripper(1, 612.0, 792.0).apply { getText(pdf) }
            assertEquals(Located.NotFound, QuoteLocator.locate(PageBoxes(612.0, 792.0, legacy.words), quote))
            val result = locateCitedPdfRange(extracted, text, 0 until quote.length)
            assertTrue(assertIs<Located.Found>(result.located).rects.isNotEmpty())
        }
    }

    @Test fun wholeMultilineBlockKeepsBothLinesAndGeneratedSeparators() {
        Loader.loadPDF(citedRangePdf(700f to "ABCDE oFce first line.", 684f to "Second line of the same paragraph.")).use { pdf ->
            val extracted = PdfPageTextExtraction.extract(pdf, 1)
            val text = extracted.unit.text!!
            assertTrue(text.trim().contains('\n'))
            val rects = assertIs<Located.Found>(locateCitedPdfRange(extracted, text, text.indices).located).rects
            assertTrue(rects.maxOf { it.y1 } - rects.minOf { it.y0 } > 16)
            assertEquals(extracted.unit.spans.sumOf { it.geometry.displayBoxes().size }, rects.size)
        }
    }

    @Test fun repeatedSentenceUsesTheSecondUtf16RangeAfterASurrogatePair() {
        Loader.loadPDF(citedRangePdf(740f to "prefix G", 700f to "ABCDE oFce.", 100f to "ABCDE oFce.")).use { pdf ->
            val extracted = PdfPageTextExtraction.extract(pdf, 1)
            val text = extracted.unit.text!!
            val quote = "中文无空格 office."
            val start = text.lastIndexOf(quote)
            assertTrue(start > text.indexOf(quote))
            assertTrue(text.substring(0, start).contains("\uD83D\uDE00"))
            val rects = assertIs<Located.Found>(locateCitedPdfRange(extracted, text, start until start + quote.length).located).rects
            assertTrue(rects.all { it.y0 > 650 }, "The first occurrence must never be boxed")
        }
    }

    @Test fun missingGlyphsStaleTextAndOutOfBoundsRangesRefuseToBorrowNearbyBoxes() {
        Loader.loadPDF(citedRangePdf(700f to "ABCDE oFce.")).use { pdf ->
            val extracted = PdfPageTextExtraction.extract(pdf, 1)
            val text = extracted.unit.text!!
            val incomplete = extracted.copy(unit = extracted.unit.copy(spans = extracted.unit.spans.drop(1)))
            assertEquals(Located.NotFound, locateCitedPdfRange(incomplete, text, text.indices).located)
            assertEquals(Located.NotFound, locateCitedPdfRange(extracted, text + "changed", text.indices).located)
            assertEquals(Located.NotFound, locateCitedPdfRange(extracted, text, 0..text.length).located)
            val noGeometry = extracted.copy(unit = extracted.unit.copy(spans = emptyList()))
            assertEquals(Located.NoTextLayer, locateCitedPdfRange(noGeometry, text, text.indices).located)
        }
    }

    @Test fun rotationUsesDisplayDimensionsAndAppliesGlyphRotationOnlyOnce() {
        for (rotation in listOf(0, 90, 180, 270)) {
            Loader.loadPDF(citedRangePdf(700f to "ABCDE oFce.", rotation = rotation)).use { pdf ->
                val extracted = PdfPageTextExtraction.extract(pdf, 1)
                val result = locateCitedPdfRange(extracted, extracted.unit.text!!, extracted.unit.text!!.indices)
                val sideways = rotation == 90 || rotation == 270
                assertEquals(if (sideways) 792.0 else 612.0, result.boxes.width)
                assertEquals(if (sideways) 612.0 else 792.0, result.boxes.height)
                val box = assertIs<Located.Found>(result.located).rects.first()
                val normalized = extracted.unit.spans.first().geometry.displayBoxes().single()
                assertEquals(normalized.x, box.x0 / result.boxes.width, 1e-9)
                assertEquals(normalized.y, box.y0 / result.boxes.height, 1e-9)
            }
        }
    }
}
