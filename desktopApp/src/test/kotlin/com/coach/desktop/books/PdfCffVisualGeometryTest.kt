package com.coach.desktop.books

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor
import org.apache.pdfbox.pdmodel.font.PDType1CFont
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

class PdfCffVisualGeometryTest {
    // Own synthetic CFF, not a book font: 1000 units/em, advance 600; A is a rectangle
    // (0,-800)..(500,0), B is (0,0)..(500,800), .notdef/space have empty paths.
    // Generated with FontTools 4.68 FontBuilder.setupCFF / T2CharStringPen.
    private val cff = Base64.getDecoder().decode(
        "AQAEAQABAQEPT3duQ2ZmR2VvbWV0cnkAAQEBGvgbAvgbA/gYBIv9tPiI+bQF1Q+L9wwS3BEAAQEBD093bkNmZkdlb21ldHJ5AAAAAAEAIgAjAAQBAQQHFCD47A747A747P20BPiI+bT8iAYO+OyLFviI+bT8iAYO")
    private val complete = PdfVisualSelection(1, 70, 295, 130, 370, PageVisualKind.FORMULA)

    private fun fixture(origin: Float = 0f, baseline: Float = 150f,
        mode: RenderingMode = RenderingMode.FILL, embedded: Boolean = true): ByteArray = PDDocument().use { pdf ->
        val descriptor = PDFontDescriptor(COSDictionary()).apply {
            fontName = "OwnCffGeometry"; flags = 32
            fontBoundingBox = PDRectangle(0f, -800f, 500f, 1600f)
            capHeight = 800f; ascent = 800f; descent = -800f
            if (embedded) fontFile3 = PDStream(pdf, cff.inputStream()).apply {
                cosObject.setItem(COSName.SUBTYPE, COSName.getPDFName("Type1C"))
            }
        }
        val dictionary = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT); setItem(COSName.SUBTYPE, COSName.TYPE1)
            setName(COSName.BASE_FONT, "OwnCffGeometry")
            setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING)
            setInt(COSName.FIRST_CHAR, 32); setInt(COSName.LAST_CHAR, 66)
            setItem(COSName.WIDTHS, COSArray().apply { repeat(35) { add(COSInteger.get(600)) } })
            setItem(COSName.FONT_DESC, descriptor)
        }
        val font = PDType1CFont(dictionary)
        val page = PDPage(PDRectangle(0f, 0f, 400f, 400f)).apply {
            cropBox = PDRectangle(origin, origin, 300f, 300f)
        }
        pdf.addPage(page)
        PDPageContentStream(pdf, page).use { out ->
            out.beginText(); out.setFont(font, 40f); out.setRenderingMode(mode)
            out.newLineAtOffset(40f + origin, baseline + origin); out.showText("A"); out.endText()
        }
        ByteArrayOutputStream().use { out -> pdf.save(out); out.toByteArray() }
    }

    private fun pages(bytes: ByteArray) = Loader.loadPDF(bytes).use { pdf ->
        listOf(BookPage(1, PdfPageTextExtraction.extract(pdf, 1).unit.text!!))
    }

    @Test fun belowBaselineInkCanBeReviewedWithoutChangingCanonicalExtraction() {
        val bytes = fixture(); val pages = pages(bytes)
        Loader.loadPDF(bytes).use { pdf ->
            val before = PdfPageTextExtraction.extract(pdf, 1)
            val precise = PdfPageTextExtraction.extractForVisualReview(pdf, 1, 2.0)
            assertEquals(before.unit.text, precise.unit.text)
            assertEquals(before.unit.spans.map { it.range }, precise.unit.spans.map { it.range })
            assertTrue(precise.unmappedRanges.isEmpty())
            assertEquals(before, PdfPageTextExtraction.extract(pdf, 1))
            val old = before.unit.spans.single().geometry.boxes.single()
            val ink = precise.unit.spans.single().geometry.boxes.single()
            assertTrue(old.y < 0.4 && old.y + old.height <= 0.5)
            assertTrue(ink.y < 0.5 && ink.y + ink.height > 0.6)
        }
        val preview = PdfReviewedVisuals.preview(bytes, pages, listOf(complete)).single()
        assertEquals(PdfVisualDecision.UNREVIEWED, preview.review.decision)
        val approved = preview.review.copy(decision = PdfVisualDecision.COMPLETE_OBJECT_WITH_LABELS)
        val asset = PdfReviewedVisuals.extract(bytes, pages, listOf(approved)).single()
        assertNull(asset.sourceStart); assertNull(asset.sourceEnd)
        assertEquals(pages, pages(bytes))
    }

    @Test fun cuttingActualInkOrSelectingOnlyTheOldHeuristicBoxIsRejected() {
        val bytes = fixture(); val pages = pages(bytes)
        for (bad in listOf(complete.copy(bottom = 350), complete.copy(top = 320),
            complete.copy(top = 234, bottom = 298), complete.copy(left = 90))) {
            assertFailsWith<IllegalArgumentException> { PdfReviewedVisuals.preview(bytes, pages, listOf(bad)) }
        }
    }

    @Test fun cropBoxOriginUsesTheSameOutlineAndEveryRenderedInkPixelIsInsideTheCrop() {
        for (origin in listOf(0f, 25f)) {
            val bytes = fixture(origin)
            val preview = PdfReviewedVisuals.preview(bytes, pages(bytes), listOf(complete)).single()
            val crop = ImageIO.read(preview.image.bytes.inputStream())
            Loader.loadPDF(bytes).use { pdf ->
                val raster = PDFRenderer(pdf).renderImage(0, 2f)
                try {
                    var ink = 0
                    for (y in 0 until raster.height) for (x in 0 until raster.width) {
                        if (raster.getRGB(x, y) and 0xffffff != 0xffffff) {
                            ink++
                            assertTrue(x in complete.left until complete.right && y in complete.top until complete.bottom)
                        }
                    }
                    assertTrue(ink > 100)
                    for (y in 0 until crop.height) for (x in 0 until crop.width)
                        assertEquals(raster.getRGB(x + complete.left, y + complete.top), crop.getRGB(x, y))
                } finally { raster.flush(); crop.flush() }
            }
        }
    }

    @Test fun unsupportedPaintModesAndUnembeddedFontNeverUseTheOutlineAsProof() {
        for (mode in RenderingMode.entries.filter { it != RenderingMode.FILL }) {
            val bytes = fixture(mode = mode)
            assertFailsWith<IllegalArgumentException> { PdfReviewedVisuals.preview(bytes, pages(bytes), listOf(complete)) }
        }
        val bytes = fixture(embedded = false)
        assertFailsWith<IllegalArgumentException> { PdfReviewedVisuals.preview(bytes, pages(bytes), listOf(complete)) }
    }

    @Test fun inkBeyondTheCropBoxIsUnmappedInsteadOfClamped() {
        val bytes = fixture(baseline = 20f)
        Loader.loadPDF(bytes).use { pdf ->
            assertTrue(PdfPageTextExtraction.extractForVisualReview(pdf, 1, 2.0).unmappedRanges.isNotEmpty())
        }
        assertFailsWith<IllegalArgumentException> {
            PdfReviewedVisuals.preview(bytes, pages(bytes), listOf(complete.copy(top = 550, bottom = 600)))
        }
    }
}
