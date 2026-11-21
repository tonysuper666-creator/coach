package com.coach.desktop.books

import androidx.compose.ui.graphics.toComposeImageBitmap
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

/** 同一本书同一页：并发/重复调用只真正渲染一次，且每次调用都留下分段计时。 */
class RenderPdfOnceTest {
    private fun lib(): Pair<TextbookLibrary, Textbook> {
        val root = Files.createTempDirectory("coach-render-once-")
        val library = TextbookLibrary(root.resolve("library"))
        return library to library.importBook(syntheticTextbook(root.resolve("src")))
    }

    @Test fun concurrentAndRepeatedCallsRenderThePageOnceAndShareTheImage() {
        val (library, book) = lib()
        val n = 4
        val pool = Executors.newFixedThreadPool(n); val go = CountDownLatch(1)
        val futures = (1..n).map { pool.submit<java.awt.image.BufferedImage> { go.await(); library.renderPdf(book, 2) } }
        go.countDown()
        val images = futures.map { it.get() }
        pool.shutdown()
        assertTrue(images.all { it === images[0] }, "所有调用拿到同一张图")
        val again = library.renderPdf(book, 2)
        assertSame(images[0], again)
        val t = library.renderTraces().filter { it.book == book.id && it.page == 2 }
        assertEquals(1, t.count { it.outcome == "render" }, "只真正渲染一次：$t")
        assertEquals(n + 1, t.size)
        val r = t.single { it.outcome == "render" }
        assertTrue(r.loadMs >= 0 && r.renderMs > 0 && r.totalMs >= r.loadMs + r.renderMs, "分段计时：$r")
        assertEquals("hit", t.last().outcome)
        assertNotSame(images[0], library.renderPdf(book, 3), "不同页不共用")
    }

    @Test fun aFailedRenderIsNotCachedAndTheNextCallRendersAgain() {
        val (library, book) = lib()
        val file = library.directory.resolve(book.id + ".pdf").toFile()
        val bytes = file.readBytes()
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertFails { library.renderPdf(book, 1) }
        file.writeBytes(bytes)
        assertTrue(library.renderPdf(book, 1).width > 0)
        assertEquals(0, library.renderTraces().count { it.outcome == "hit" && it.page == 1 })
    }

    @Test fun requestedPixelsFollowTheDisplayedWidthOfAQuarterTurnPage() {
        val root = Files.createTempDirectory("coach-render-rotated-")
        val file = root.resolve("Synthetic quarter turn.pdf")
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(400f, 600f)).apply { rotation = 90 }
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                stream.newLineAtOffset(60f, 400f)
                stream.showText("The displayed page width includes the quarter turn.")
                stream.endText()
            }
            pdf.save(file.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(file)
        val normal = library.renderPdf(book, 1)
        val wide = library.renderPdf(book, 1, pixelWidth = 1440)
        val narrow = library.renderPdf(book, 1, pixelWidth = 720)
        assertEquals(listOf(900, 600), listOf(normal.width, normal.height))
        assertEquals(listOf(1440, 960), listOf(wide.width, wide.height))
        assertEquals(listOf(720, 480), listOf(narrow.width, narrow.height))
        assertSame(wide, library.renderPdf(book, 1, pixelWidth = 1440))
        assertSame(normal, library.renderPdf(book, 1))
    }

    @Test fun fastAsBookImageBitmapMatchesComposeConverterAcrossImageTypes() {
        fun pixelsOf(bmp: androidx.compose.ui.graphics.ImageBitmap): IntArray {
            val out = IntArray(bmp.width * bmp.height)
            bmp.readPixels(out, 0, 0, bmp.width, bmp.height, 0, bmp.width)
            return out
        }

        val rgbImg = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        val rgbData = (rgbImg.raster.dataBuffer as DataBufferInt).data
        rgbData[0] = 0x00112233
        rgbData[1] = 0xFFAA5511.toInt()
        rgbData[2] = 0x12345678
        rgbData[3] = 0x00FF0000
        rgbData[4] = 0x0000FF00
        rgbData[5] = 0x000000FF
        val fastRgb = rgbImg.asBookImageBitmap()
        val expRgb = rgbImg.toComposeImageBitmap()
        assertEquals(expRgb.width, fastRgb.width)
        assertEquals(expRgb.height, fastRgb.height)
        assertContentEquals(pixelsOf(expRgb), pixelsOf(fastRgb))

        val argbImg = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
        val argbData = (argbImg.raster.dataBuffer as DataBufferInt).data
        argbData[0] = 0xFF123456.toInt()
        argbData[1] = 0x80336699.toInt()
        argbData[2] = 0x00000000
        argbData[3] = 0xFFABCDEF.toInt()
        argbData[4] = 0x40112233
        argbData[5] = 0xFFFFFFFF.toInt()
        assertContentEquals(pixelsOf(argbImg.toComposeImageBitmap()), pixelsOf(argbImg.asBookImageBitmap()))

        val subImg = rgbImg.getSubimage(1, 0, 2, 2)
        assertContentEquals(pixelsOf(subImg.toComposeImageBitmap()), pixelsOf(subImg.asBookImageBitmap()))

        val bgrImg = BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR).apply {
            setRGB(0, 0, 0xFFE63946.toInt())
            setRGB(1, 0, 0xFF2A9D8F.toInt())
            setRGB(0, 1, 0xFFE9C46A.toInt())
            setRGB(1, 1, 0xFF264653.toInt())
        }
        assertContentEquals(pixelsOf(bgrImg.toComposeImageBitmap()), pixelsOf(bgrImg.asBookImageBitmap()))
    }
}

class PdfWarmupTest {
    @Test fun openingALibraryStartsAOneShotBackgroundWarmupThatFinishes() {
        TextbookLibrary(Files.createTempDirectory("coach-warm-"))
        val ms = PdfWarmup.done.get(60, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(ms >= 0, "预热成功：$ms")
        TextbookLibrary(Files.createTempDirectory("coach-warm2-")) // 再开不会重复预热
        assertTrue(PdfWarmup.done.isDone)
    }
}
