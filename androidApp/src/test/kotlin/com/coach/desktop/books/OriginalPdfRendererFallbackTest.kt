package com.coach.desktop.books

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalPdfRendererFallbackTest {
    @Before fun initPdfBox() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    @Test fun rotatedPageUsesPdfBoxFallbackAndRendersInkWithMatchingFrame() {
        val root = Files.createTempDirectory("pdf-fallback-")
        val pdfFile = root.resolve("rotated.pdf").toFile()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(360f, 480f)).apply { rotation = 90 }
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 24f)
                content.newLineAtOffset(40f, 240f)
                content.showText("Repeated letters: EEEE AAAA")
                content.endText()
            }
            document.save(pdfFile)
        }
        BookPdfLoader.load(pdfFile).use { pdf ->
            val bitmap = renderBookPdfPage(pdfFile, pdf, 0, 1f)
            try {
                // Rotated 90° turns 360x480 into 480x360.
                assertEquals(480, bitmap.width)
                assertEquals(360, bitmap.height)
                var inkPixels = 0
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        if (Color.red(bitmap.getPixel(x, y)) < 200) inkPixels++
                    }
                }
                assertTrue("Expected rendered glyph ink on rotated fallback page, got $inkPixels", inkPixels > 200)
                assertTrue(bookPdfBitmapBytes(bitmap) > 0L)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
