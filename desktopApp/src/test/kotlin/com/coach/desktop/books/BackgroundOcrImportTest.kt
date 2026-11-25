package com.coach.desktop.books

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import com.coach.ui.CoachTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class BackgroundOcrImportTest {
    private fun scannedPdf(path: Path, pageCount: Int, title: String = "Scanned Sample"): Path {
        PDDocument().use { pdf ->
            pdf.documentInformation.title = title
            repeat(pageCount) { index ->
                val page = PDPage(PDRectangle.A4)
                pdf.addPage(page)
                val img = BufferedImage(840, 1188, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                try {
                    g.color = Color(248, 245, 238)
                    g.fillRect(0, 0, img.width, img.height)
                    g.color = Color(36, 33, 28)
                    g.font = Font(Font.SERIF, Font.PLAIN, 28)
                    g.drawString("Public Domain Page ${index + 1}", 60, 140)
                    g.drawString("Nature never deceives us; it is always we who deceive", 60, 210)
                    g.drawString("ourselves. Observation and experiment remain steady.", 60, 260)
                } finally { g.dispose() }
                val xObj = JPEGFactory.createFromImage(pdf, img, 0.85f)
                PDPageContentStream(pdf, page).use { stream ->
                    stream.drawImage(xObj, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height)
                }
            }
            pdf.save(path.toFile())
        }
        return path
    }

    private fun textPdf(path: Path, title: String = "Another Book"): Path {
        PDDocument().use { pdf ->
            pdf.documentInformation.title = title
            val page = PDPage(PDRectangle.A4)
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { out ->
                out.beginText()
                out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                out.newLineAtOffset(50f, 700f)
                out.showText("Chapter 1 Other Book")
                out.newLineAtOffset(0f, -36f)
                out.showText("Another book remains open and interactive while scanning runs in background.")
                out.endText()
            }
            pdf.save(path.toFile())
        }
        return path
    }

    /** Public-domain 300 dpi scanned image PDF (>= 250 pages, >= 50 MB). */
    private fun large300DpiScannedPdf(path: Path, pageCount: Int = 256): Path {
        // 300 dpi A4 is 2480 x 3508; render crisp public-domain text with light scanned paper grain so JPEG stays ~220 KB/page.
        val width = 2480
        val height = 3508
        val template = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val rng = Random(1767L)
        val g = template.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(246, 243, 236)
            g.fillRect(0, 0, width, height)
            // Deterministic subtle paper scan grain so compression resembles real 300 dpi scans (~55-75 MB across 256 pages).
            val raster = template.raster
            for (y in 0 until height step 4) {
                for (x in 0 until width step 4) {
                    val delta = rng.nextInt(15) - 7
                    val r = (244 + delta).coerceIn(0, 255)
                    val gr = (241 + delta).coerceIn(0, 255)
                    val b = (234 + delta).coerceIn(0, 255)
                    raster.setPixel(x, y, intArrayOf(r, gr, b))
                    if (x + 1 < width) raster.setPixel(x + 1, y, intArrayOf(r, gr, b))
                }
            }
            g.color = Color(30, 28, 25)
            g.font = Font(Font.SERIF, Font.BOLD, 56)
            g.drawString("On the Origin of Species — Public Domain Edition", 180, 260)
            g.font = Font(Font.SERIF, Font.PLAIN, 40)
            val lines = listOf(
                "When we look to the individuals of the same variety or sub-variety of our older",
                "cultivated plants and animals, one of the first points which strikes us, is, that",
                "they generally differ much more from each other, than do the individuals of any",
                "one species or variety in a state of nature. Nothing is more easy than to admit",
                "in words the truth of the universal struggle for life, or more difficult than",
                "constantly to bear this conclusion in mind.",
            )
            for (rep in 0 until 6) {
                lines.forEachIndexed { idx, line ->
                    g.drawString(line, 180, 420 + (rep * 7 + idx) * 62)
                }
            }
        } finally { g.dispose() }

        val pageBytes = java.io.ByteArrayOutputStream().use { out ->
            val writers = ImageIO.getImageWritersByFormatName("jpeg")
            val writer = writers.next()
            try {
                val param = writer.defaultWriteParam.apply {
                    compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.45f
                }
                ImageIO.createImageOutputStream(out).use { ios ->
                    writer.output = ios
                    writer.write(null, javax.imageio.IIOImage(template, null, null), param)
                }
            } finally { writer.dispose() }
            out.toByteArray()
        }
        template.flush()

        PDDocument(org.apache.pdfbox.io.MemoryUsageSetting.setupTempFileOnly().streamCache).use { pdf ->
            pdf.documentInformation.title = "Public Domain Large Scan"
            repeat(pageCount) { index ->
                val page = PDPage(PDRectangle.A4)
                pdf.addPage(page)
                val img = org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(pdf, pageBytes, "page-$index.jpg")
                PDPageContentStream(pdf, page).use { stream ->
                    stream.drawImage(img, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height)
                }
            }
            pdf.save(path.toFile())
        }
        return path
    }

    @Test fun etaEstimatorWithholdsEarlySamplesAndFormatsClearly() {
        val estimator = ImportEtaEstimator(windowSize = 4, minSamples = 3)
        assertNull(estimator.estimateRemainingSeconds(100))
        estimator.record(2_000)
        assertNull(estimator.estimateRemainingSeconds(100))
        estimator.record(2_000)
        assertNull(estimator.estimateRemainingSeconds(100))
        estimator.record(2_000)
        assertEquals(200L, estimator.estimateRemainingSeconds(100))
        assertEquals("约剩 45 秒", formatImportEta(45))
        assertEquals("约剩 6 分钟", formatImportEta(360))
        assertEquals(
            "识别文字 76 / 284 页 · 约剩 6 分钟",
            formatImportProgressLabel(BookImportProgress(76, 284, recognizing = true, estimatedRemainingSeconds = 360)),
        )
        assertEquals("正在准备教材…", formatImportProgressLabel(BookImportProgress(0, 0, recognizing = false)))
        assertEquals("正在读取教材 · 第 12 / 284 页", formatImportProgressLabel(BookImportProgress(12, 284, recognizing = false)))
    }

    @Test fun backgroundOcrUnblocksShelfSwitchBackAndReaderShowsOriginalPagesAndToast() = runBlocking {
        val root = Files.createTempDirectory("coach-bg-ocr-ui-")
        val gatePage3 = CompletableDeferred<Unit>()
        val gateFinish = CompletableDeferred<Unit>()
        var pending: kotlinx.coroutines.Job? = null
        try {
            val otherPdf = textPdf(root.resolve("other.pdf"), "生物学导论")
            val scanPdf = scannedPdf(root.resolve("scan.pdf"), 6, "扫描教材")
            val reachedPage3 = CountDownLatch(1)
            val reachedPage4 = CountDownLatch(1)
            val recognizedPages = AtomicInteger(0)

            val library = TextbookLibrary(root.resolve("library"), ocr = BookOcr { _, control ->
                val pageIndex = recognizedPages.incrementAndGet()
                Thread.sleep(60)
                if (pageIndex == 3) {
                    reachedPage3.countDown()
                    runBlocking { gatePage3.await() }
                }
                if (pageIndex == 4) {
                    reachedPage4.countDown()
                    runBlocking { gateFinish.await() }
                }
                control.check()
                "第 $pageIndex 页识别出的正文：细胞是生命活动的基本单位。"
            })

            val controller = TextbookController(library)
            controller.importBook(otherPdf)
            controller.back()
            assertEquals(BookScreen.HOME, controller.state.value.screen)

            for (dark in listOf(false, true)) {
                // Only run full UI flow in light mode once, and also verify dark screenshot
                if (dark) break
                val importJob = controller.launchImport(scanPdf).also { pending = it }
                assertTrue(withContext(Dispatchers.IO) { reachedPage3.await(15, TimeUnit.SECONDS) })

                runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1.0f)) {
                    setContent {
                        CompositionLocalProvider(LocalSystemTheme provides SystemTheme.Light) {
                            CoachTheme(false) {
                                Surface(Modifier.fillMaxSize(), color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                                    WindowsTextbookHost(controller) { null }
                                }
                            }
                        }
                    }
                    fun shot(name: String) {
                        waitForIdle()
                        val out = Path.of("build/screens/ton1767/$name.png")
                        Files.createDirectories(out.parent)
                        val awt = (this as SkikoComposeUiTest).captureToImage().toAwtImage()
                        assertTrue(ImageIO.write(awt, "png", out.toFile()))
                    }

                    // TON-1784 opens the newly imported book while the remaining pages are recognized.
                    waitUntil(timeoutMillis = 10_000) {
                        controller.state.value.screen == BookScreen.READER && controller.state.value.book?.title == "扫描教材"
                    }
                    assertNull(controller.state.value.prompt)
                    // Verify page 1 is already recognized, readable, and explainable while later pages remain pending!
                    assertFalse(controller.state.value.busy)
                    assertEquals(false, controller.state.value.book!!.page(1)!!.ocrPending)
                    onNodeWithTag("immersive-companion-toggle").performClick()
                    waitUntil(timeoutMillis = 5_000) {
                        onAllNodesWithTag("companion-tab-explain").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("immersive-companion-toggle").performClick()
                    waitUntil(timeoutMillis = 5_000) {
                        onAllNodesWithTag("companion-tab-explain").fetchSemanticsNodes().isEmpty()
                    }
                    // Jump to page 4 (still pending OCR!) -> must show "这一页还在识别" + original PDF page, NOT white!
                    runBlocking { controller.page(4) }
                    waitUntil(timeoutMillis = 10_000) {
                        runCatching { onNodeWithTag("page-ocr-pending").assertIsDisplayed() }.isSuccess &&
                            onAllNodesWithTag("remote-image").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("book-import-progress").assertIsDisplayed()
                    onNodeWithTag("book-import-progress-bar").assertIsDisplayed()
                    shot("reader-ocr-pending-light")

                    // 2. Return to shelf during OCR — response time must be well under 1 second!
                    val backStart = System.nanoTime()
                    onNodeWithTag("immersive-back").performClick()
                    waitUntil(timeoutMillis = 1_000) { controller.state.value.screen == BookScreen.HOME }
                    val backElapsedMs = (System.nanoTime() - backStart) / 1_000_000
                    assertTrue(backElapsedMs < 1_000, "返回书架耗时应 < 1 秒，实际 ${backElapsedMs}ms")
                    println("TON1767_BACK_TO_SHELF_MS=$backElapsedMs")

                    // Shelf shows book card progress bar and allows opening another book while OCR runs!
                    onNodeWithTag("book-import-progress").assertIsDisplayed()
                    onNodeWithTag("book-import-progress-bar").assertIsDisplayed()
                    shot("shelf-ocr-progress-light")

                    onNodeWithTag("book-${library.catalog().first { it.title == "生物学导论" }.id}").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 5_000) { controller.state.value.book?.title == "生物学导论" }
                    waitUntil(timeoutMillis = 5_000) { controller.state.value.screen == BookScreen.READER && controller.state.value.book?.title == "生物学导论" }
                    // Esc from reader returns to shelf even while OCR runs in background!
                    onNodeWithTag("windows-textbooks").performKeyInput { pressKey(Key.Escape) }
                    waitUntil(timeoutMillis = 1_000) { controller.state.value.screen == BookScreen.HOME }

                    // 3. Let OCR finish -> progress bar disappears and toast "《扫描教材》识别完了" shows then auto-dismisses!
                    gatePage3.complete(Unit)
                    assertTrue(withContext(Dispatchers.IO) { reachedPage4.await(10, TimeUnit.SECONDS) })
                    gateFinish.complete(Unit)
                    runBlocking { importJob.join() }

                    waitUntil(timeoutMillis = 10_000) {
                        controller.state.value.importing == null &&
                            onAllNodesWithTag("book-import-toast").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("book-import-toast").assertIsDisplayed()
                    shot("ocr-completed-toast-light")
                    assertTrue(onAllNodesWithTag("book-import-progress").fetchSemanticsNodes().isEmpty())
                    // Auto-dismisses after ~4s without clicking
                    waitUntil(timeoutMillis = 8_000) {
                        controller.state.value.importNotice == null &&
                            onAllNodesWithTag("book-import-toast").fetchSemanticsNodes().isEmpty()
                    }
                }
            }
        } finally {
            gatePage3.complete(Unit)
            gateFinish.complete(Unit)
            pending?.join()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun darkThemeProgressBarAndToastRenderCleanly() = runBlocking {
        val root = Files.createTempDirectory("coach-bg-ocr-dark-")
        try {
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1.0f)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides SystemTheme.Dark) {
                        CoachTheme(true) {
                            Surface(Modifier.fillMaxSize(), color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                                val book = Textbook("a".repeat(64), "深色扫描教材", "pdf",
                                    listOf(BookPage(1, "", ocrPending = true)), readingPage = 76)
                                val ui = LibraryUi(
                                    catalog = listOf(BookSummary(book.id, book.title, 284, 284, 76, false, pendingOcrPages = 208)),
                                    book = book,
                                    importing = BookImportProgress(
                                        76, 284, recognizing = true, bookId = book.id, bookTitle = book.title,
                                        completedOcr = 76, totalOcr = 284, estimatedRemainingSeconds = 360,
                                    ),
                                )
                                LibraryHome(ui, {}, {}, {}, {}, {})
                            }
                        }
                    }
                }
                waitForIdle()
                onNodeWithText("识别文字 76 / 284 页 · 约剩 6 分钟").assertIsDisplayed()
                onNodeWithTag("book-import-progress-bar").assertIsDisplayed()
                val out = Path.of("build/screens/ton1767/shelf-ocr-progress-dark.png")
                Files.createDirectories(out.parent)
                val awt = (this as SkikoComposeUiTest).captureToImage().toAwtImage()
                assertTrue(ImageIO.write(awt, "png", out.toFile()))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun restartResumesFromBreakpointWithoutRepeatingCompletedPagesAndHandlesMissingSource() = runBlocking {
        val root = Files.createTempDirectory("coach-ocr-resume-")
        try {
            val pdf = scannedPdf(root.resolve("resume.pdf"), 5, "断点续识教材")
            val callsRun1 = mutableListOf<Int>()
            val stopAt3 = CountDownLatch(1)
            val lib1 = TextbookLibrary(root.resolve("library"), ocr = BookOcr { _, _ ->
                val n = callsRun1.size + 1
                callsRun1 += n
                if (n == 3) {
                    stopAt3.countDown()
                    Thread.sleep(5_000)
                }
                "第 $n 页识别结果"
            })
            val staged = lib1.stageBookImport(pdf)
            val runner = launch(Dispatchers.IO) {
                runCatching { lib1.resumeBookOcr(staged.book.id) }
            }
            assertTrue(withContext(Dispatchers.IO) { stopAt3.await(10, TimeUnit.SECONDS) })
            runner.cancel()
            // Wait briefly for in-flight page 2 DB commit and verify pages 1..2 are done, 3..5 pending
            val beforeRestart = lib1.book(staged.book.id)
            assertFalse(beforeRestart.page(1)!!.ocrPending)
            assertFalse(beforeRestart.page(2)!!.ocrPending)
            assertTrue(beforeRestart.page(3)!!.ocrPending)

            // Reopen a brand-new controller on the same directory -> auto-resumes from page 3!
            val callsRun2 = AtomicInteger(0)
            val lib2 = TextbookLibrary(root.resolve("library"), ocr = BookOcr { _, _ ->
                callsRun2.incrementAndGet()
                "断点续识文字"
            })
            val reopened = TextbookController(lib2)
            reopened.load()
            withTimeout(15_000) {
                while (reopened.state.value.importing != null || lib2.book(staged.book.id).hasPendingOcr) {
                    delay(50)
                }
            }
            assertEquals(3, callsRun2.get(), "只补识剩余的 3..5 页，不重跑已完成的 1..2 页")
            val finished = lib2.book(staged.book.id)
            assertFalse(finished.hasPendingOcr)
            assertEquals("第 1 页识别结果", finished.page(1)!!.text)
            assertEquals("断点续识文字", finished.page(3)!!.text)

            // Missing original file on restart must not hang or loop: clears pending and shows clear error.
            val brokenDir = root.resolve("broken-lib")
            val libBroken1 = TextbookLibrary(brokenDir)
            val stagedBroken = libBroken1.stageBookImport(pdf)
            Files.delete(brokenDir.resolve("${stagedBroken.book.id}.pdf"))
            val brokenController = TextbookController(TextbookLibrary(brokenDir))
            brokenController.load()
            withTimeout(10_000) {
                while (brokenController.state.value.error == null) delay(50)
            }
            assertContains(brokenController.state.value.error!!, "重新导入")
            assertFalse(TextbookLibrary(brokenDir).book(stagedBroken.book.id).hasPendingOcr)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun large256Page50MbScannedPdfImportsResponsivelyAndRendersPage76NonWhite() = runBlocking {
        val root = Files.createTempDirectory("coach-large-scan-256-")
        try {
            val largePdf = large300DpiScannedPdf(root.resolve("large-256.pdf"), pageCount = 256)
            val sizeBytes = Files.size(largePdf)
            val sizeMb = sizeBytes / (1024.0 * 1024.0)
            assertTrue(sizeBytes >= 50L * 1024 * 1024, "合成扫描件应 ≥50 MB，实际 ${"%.1f".format(sizeMb)} MB")

            val rt = Runtime.getRuntime()
            var peakUsedMb = 0L
            fun sampleMemory() {
                val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                if (usedMb > peakUsedMb) peakUsedMb = usedMb
            }

            val totalStart = System.nanoTime()
            val library = TextbookLibrary(root.resolve("library"), ocr = BookOcr { image, _ ->
                sampleMemory()
                assertTrue(image.width > 1000 && image.height > 1000)
                "Public domain paragraph recognized cleanly."
            })
            val staged = library.stageBookImport(largePdf)
            assertEquals(256, staged.book.pages.size)
            assertTrue(staged.book.hasPendingOcr)
            val stageMs = (System.nanoTime() - totalStart) / 1_000_000

            // Render page 76 original image BEFORE OCR finishes page 76 -> must succeed and contain non-white text pixels!
            val page76Image = library.renderOriginal(staged.book, 76, pixelWidth = 1000)
            var nonWhitePixels = 0
            for (y in 0 until page76Image.height step 2) {
                for (x in 0 until page76Image.width step 2) {
                    val rgb = page76Image.getRGB(x, y)
                    if ((rgb and 0xFFFFFF) != 0xFFFFFF) nonWhitePixels++
                }
            }
            assertTrue(nonWhitePixels > 1000, "第 76 页原图渲染不应是全白页")
            sampleMemory()

            // Run full 256-page OCR via controller and measure back-to-shelf UI response while OCR runs
            val c2 = TextbookController(TextbookLibrary(root.resolve("library2"), ocr = BookOcr { image, _ ->
                sampleMemory()
                "On the Origin of Species — Public Domain recognized text."
            }))
            val job = c2.launchImport(largePdf)
            // Wait until OCR is in progress
            withTimeout(15_000) {
                while (c2.state.value.importing?.recognizing != true) delay(20)
            }
            val backStart = System.nanoTime()
            c2.read()
            c2.back()
            val backMs = (System.nanoTime() - backStart) / 1_000_000
            assertTrue(backMs < 1_000, "256 页后台识别期间返回书架耗时应 < 1 秒，实际 ${backMs}ms")
            withTimeout(120_000) { job.join() }
            val totalMs = (System.nanoTime() - totalStart) / 1_000_000
            val finished = c2.state.value.book!!
            assertEquals(256, finished.pages.size)
            assertFalse(finished.hasPendingOcr)
            assertEquals(256, finished.pages.count { it.ocr && it.text.isNotBlank() })
            println("TON1767_LARGE_256_METRICS sizeMb=${"%.1f".format(sizeMb)} pages=256 stageMs=$stageMs totalMs=$totalMs backMs=$backMs peakHeapMb=$peakUsedMb")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun preparingAndReadingPhasesShowIndeterminateThenDeterminateProgressBars() = runComposeUiTest {
        val phase = androidx.compose.runtime.mutableStateOf(BookImportProgress(0, 0, recognizing = false))
        setContent {
            CoachTheme(false) {
                BookImportProgressBar(phase.value, onCancel = {})
            }
        }
        onNodeWithText("正在准备教材…").assertIsDisplayed()
        onNodeWithTag("book-import-progress-bar").assertIsDisplayed()
        phase.value = BookImportProgress(12, 284, recognizing = false)
        onNodeWithText("正在读取教材 · 第 12 / 284 页").assertIsDisplayed()
        onNodeWithTag("book-import-progress-bar").assertIsDisplayed()
    }

    @Test fun originalPageRenderFailureOrOutOfMemoryTransitionsToFailedRetryInsteadOfWhitePlaceholder() = runComposeUiTest {
        val root = Files.createTempDirectory("coach-render-oom-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(syntheticTextbook(root.resolve("src")))
            // Corrupt original PDF so rendering fails, verifying OriginalBookPage shows retry instead of staying white Loading.
            Files.write(library.directory.resolve("${book.id}.pdf"), byteArrayOf(1, 2, 3, 4))
            setContent {
                CoachTheme(false) {
                    val image = rememberOriginalPageImage(library, book, 1, enabled = true, pixelWidth = 800, retry = 0)
                    OriginalBookPage(
                        label = "第 1 页",
                        image = image,
                        placeholderAspect = 0.707f,
                        layer = null,
                        pageText = "",
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
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("reader-page-placeholder").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 10_000) {
                runCatching { onNodeWithText("这一页暂时打不开。").assertIsDisplayed() }.isSuccess
            }
            onNodeWithText("重试").assertIsDisplayed()
        } finally { root.toFile().deleteRecursively() }
    }
}
