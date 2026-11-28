package com.coach.desktop.books

import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.coach.ui.CoachTheme
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
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

    @Test fun startupReopenReusesNearWidthDiskCachedPageWithoutReopeningPdf() {
        val root = Files.createTempDirectory("coach-render-near-width-")
        try {
            val libDir = root.resolve("library")
            val pdfPath = syntheticTextbook(root.resolve("src"))
            val library1 = TextbookLibrary(libDir, lazyPdfExtras = true)
            val book1 = library1.importBook(pdfPath)
            library1.saveOpenSession(book1.id, "READER")
            library1.renderPdf(book1, 1, pixelWidth = 680)

            val cacheDir = libDir.resolve(".render-cache")
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                val ready = Files.isDirectory(cacheDir) && Files.list(cacheDir).use { stream ->
                    stream.anyMatch { p -> p.fileName.toString().let { it.startsWith("${book1.id}-p1-w680-") && it.endsWith(".cbm") } }
                }
                if (ready) break
                Thread.sleep(20)
            }
            assertTrue(
                Files.isDirectory(cacheDir) && Files.list(cacheDir).use { stream ->
                    stream.anyMatch { it.fileName.toString().endsWith(".cbm") }
                },
                "磁盘渲染缓存应已落盘",
            )

            BookPdfLoader.clearCache()
            val library2 = TextbookLibrary(libDir, lazyPdfExtras = true)
            val snap = library2.loadStartupSnapshot()
            val book2 = assertNotNull(snap.book)

            val near = library2.renderPdf(book2, 1, pixelWidth = 630)
            assertTrue(near.width > 0)
            val nearTrace = library2.renderTraces().last { it.book == book2.id && it.page == 1 }
            assertTrue(
                nearTrace.outcome == "hit" || nearTrace.outcome == "disk-hit",
                "热重启首帧近似宽度应命中缓存而非重开 PDF：${nearTrace}",
            )

            val exactWide = library2.renderPdf(book2, 1, pixelWidth = 1400)
            assertEquals(1400, exactWide.width)
            val wideTrace = library2.renderTraces().last { it.book == book2.id && it.page == 1 }
            assertEquals("render", wideTrace.outcome)
        } finally {
            BookPdfLoader.clearCache()
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Reproduces first-import "这一页暂时打不开。":
     * PDFs larger than BookPdfLoader's 16 MB in-memory threshold use file-backed RandomAccessReadBufferedFile
     * during stageBookImportTier0. After moving `.import-<uuid>` to `<bookId>.pdf`, rendering on any worker
     * thread other than the staging thread must open `<bookId>.pdf` cleanly instead of failing on the moved stage path.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test fun firstImportOfLargePdfRendersImmediatelyAcrossWorkerThreadsWithoutTemporaryFailure() {
        val root = Files.createTempDirectory("coach-first-import-large-")
        val importExec = Executors.newSingleThreadExecutor { r -> Thread(r, "test-tier0-import") }
        try {
            val largePdf = createLargeTextPdf(root.resolve("large-first-import.pdf"), padBytes = 17 * 1024 * 1024)
            assertTrue(Files.size(largePdf) > 16L * 1024 * 1024, "PDF should exceed 16 MB in-memory threshold")

            val library = TextbookLibrary(root.resolve("library"), streamPdfExtras = true, lazyPdfExtras = true)
            val staged = importExec.submit(Callable { library.stageBookImportTier0(largePdf) }).get()
            val targetPage = staged.book.readingPage

            runComposeUiTest {
                setContent {
                    CoachTheme(false) {
                        val image = rememberOriginalPageImage(
                            library = library,
                            book = staged.book,
                            page = targetPage,
                            enabled = true,
                            pixelWidth = 960,
                            retry = 0,
                        )
                        OriginalBookPage(
                            label = "第 $targetPage 页",
                            image = image,
                            placeholderAspect = 0.707f,
                            layer = null,
                            pageText = staged.book.page(targetPage)?.text.orEmpty(),
                            selected = null,
                            marks = emptyList(),
                            citation = null,
                            quote = null,
                            onResize = {},
                            onRetry = {},
                            onSelection = {},
                        )
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("remote-image").fetchSemanticsNodes().isNotEmpty() ||
                        onAllNodesWithText("这一页暂时打不开。").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(
                    onAllNodesWithText("这一页暂时打不开。").fetchSemanticsNodes().isEmpty(),
                    "First import of >16 MB PDF must not show '这一页暂时打不开。' when rendered on another thread",
                )
                assertEquals(1, onAllNodesWithTag("remote-image").fetchSemanticsNodes().size)
            }
        } finally {
            importExec.shutdownNow()
            BookPdfLoader.clearCache()
            root.toFile().deleteRecursively()
        }
    }

    private fun createLargeTextPdf(path: Path, padBytes: Int): Path {
        PDDocument().use { pdf ->
            pdf.documentInformation.title = "Large First Import Sample"
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val iccBytes = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).data
            val iccColorSpace = org.apache.pdfbox.pdmodel.graphics.color.PDICCBased(pdf).also { icc ->
                icc.pdStream.createOutputStream(COSName.FLATE_DECODE).use { out -> out.write(iccBytes) }
                icc.pdStream.cosObject.setInt(COSName.N, 3)
            }
            repeat(2) { idx ->
                val page = PDPage(PDRectangle.LETTER)
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.setNonStrokingColor(0.15f, 0.2f, 0.25f)
                    out.beginText()
                    out.setFont(font, 14f)
                    out.newLineAtOffset(64f, 700f)
                    out.showText("Chapter ${idx + 1} Introduction to Psychology")
                    out.newLineAtOffset(0f, -28f)
                    out.showText("Empirical observation and experimental controls ground psychological science.")
                    out.endText()
                }
                page.resources.put(COSName.DEFAULT_RGB, iccColorSpace)
            }
            val rawPad = ByteArray(padBytes)
            var state = 0x5DEECE66DL
            var i = 0
            while (i < rawPad.size) {
                state = state * 6364136223846793005L + 1442695040888963407L
                rawPad[i] = (state ushr 24).toByte()
                i++
            }
            val padStream = PDStream(pdf)
            padStream.cosObject.createRawOutputStream().use { out -> out.write(rawPad) }
            pdf.documentCatalog.cosObject.setItem(COSName.getPDFName("CoachPadding"), padStream)
            pdf.save(path.toFile())
        }
        return path
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
