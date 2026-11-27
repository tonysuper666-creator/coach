package com.coach.desktop.books

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LargeAndBadPdfImportTest {
    @Test fun thousandPageImportCheckpointsAndResumesWithoutBlockingAnotherBook() {
        val root = Files.createTempDirectory("coach-thousand-pdf-")
        try {
            val pdf = thousandPagePdf(root.resolve("thousand.pdf"))
            val libDir = root.resolve("library")
            val library = TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true)
            val other = library.stageBookImportTier0(onePagePdf(root.resolve("other.pdf"), "OTHER-BOOK-TOKEN")).book
            val staged = library.stageBookImportTier0(pdf)
            assertEquals("TIER0", staged.book.indexState)
            assertEquals(1000, staged.book.pages.size)

            val control = BookImportControl()
            assertFailsWith<BookImportCancelled> {
                library.indexBookTier1(staged.book.id, control) { page, _ ->
                    if (page >= 40) control.cancel()
                }
            }
            val settled = settledPages(libDir, staged.book.id)
            assertTrue(settled in 25..999, "expected a committed batch, settled=$settled")
            assertEquals("TIER0", library.book(staged.book.id).indexState)

            val library2 = TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true)
            var firstProgress = -1
            val peak = AtomicLong()
            val stop = AtomicBoolean(false)
            val sampler = Thread {
                val runtime = Runtime.getRuntime()
                while (!stop.get()) {
                    val used = runtime.totalMemory() - runtime.freeMemory()
                    peak.updateAndGet { current -> maxOf(current, used) }
                    Thread.sleep(40)
                }
            }
            sampler.isDaemon = true
            sampler.start()
            val startedIndexing = java.util.concurrent.CountDownLatch(1)
            val readyRef = arrayOfNulls<Textbook>(1)
            val indexError = arrayOfNulls<Throwable>(1)
            val indexThread = Thread {
                try {
                    readyRef[0] = library2.indexBookTier1(staged.book.id) { page, _ ->
                        if (firstProgress < 0) {
                            firstProgress = page
                            startedIndexing.countDown()
                        }
                    }
                } catch (error: Throwable) {
                    indexError[0] = error
                    startedIndexing.countDown()
                }
            }
            indexThread.start()
            assertTrue(startedIndexing.await(120, java.util.concurrent.TimeUnit.SECONDS))
            indexError[0]?.let { throw it }
            assertTrue(indexThread.isAlive, "indexing should still be running while another book is saved")
            val started = System.nanoTime()
            val loaded = library2.book(other.id)
            library2.save(loaded.copy(readingPage = 1))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            assertTrue(elapsedMs < 3_000, "another book stayed readable during indexing (${elapsedMs}ms)")
            indexThread.join(180_000)
            stop.set(true)
            sampler.join(1_000)
            indexError[0]?.let { throw it }
            val ready = checkNotNull(readyRef[0])
            assertTrue(firstProgress >= 25, "resume started at $firstProgress")
            assertEquals("READY", ready.indexState)
            assertTrue(ready.page(1000)!!.text.contains("TAIL-TOKEN-1000"))
            assertTrue(library2.searchBook(ready.id, "TAIL-TOKEN-1000").hits.any { it.page == 1000 })
            assertTrue(peak.get() < 2L * 1024 * 1024 * 1024, "heap peak ${peak.get()} bytes")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun indexingProgressIsPerPageAndCancelKeepsTheBook() {
        val root = Files.createTempDirectory("coach-index-progress-")
        try {
            val libDir = root.resolve("library")
            val library = TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true)
            val pdf = thousandPagePdf(root.resolve("thousand.pdf"))
            val controller = TextbookController(library)
            val job = controller.launchImport(pdf)
            val deadline = System.nanoTime() + 180_000_000_000L
            var sawPage = 0
            while (System.nanoTime() < deadline) {
                val indexing = controller.state.value.indexing
                if (indexing != null && indexing.total == 1000 && indexing.page > sawPage) sawPage = indexing.page
                if (controller.state.value.importing == null && sawPage >= 40) break
                if (controller.state.value.book?.indexState == "READY") break
                Thread.sleep(20)
            }
            assertTrue(sawPage >= 40, "indexing progress only reached $sawPage")
            controller.cancelImport()
            runBlocking { job.join() }
            val quiet = System.nanoTime() + 30_000_000_000L
            while (controller.state.value.indexing != null && System.nanoTime() < quiet) Thread.sleep(20)
            assertEquals(null, controller.state.value.indexing)
            val book = controller.state.value.catalog.single()
            assertEquals("TIER0", library.book(book.id).indexState)
            val settled = settledPages(libDir, book.id)
            assertTrue(settled in 25..999, "cancel kept a committed batch, settled=$settled")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun truncatedNotPdfAndPasswordEachSayWhatToDoNext() {
        val root = Files.createTempDirectory("coach-bad-pdf-")
        try {
            val library = TextbookLibrary(root.resolve("library"), streamPdfExtras = true, lazyPdfExtras = true)
            val truncated = assertFailsWith<IllegalArgumentException> {
                library.stageBookImportTier0(truncatedPdf(root.resolve("half.pdf")))
            }
            assertTrue(truncated.message.orEmpty().contains("损坏"))
            assertTrue(truncated.message.orEmpty().contains("请"))
            assertTrue(truncated.message.orEmpty().contains("换一份"))

            val notPdf = assertFailsWith<IllegalArgumentException> {
                library.stageBookImportTier0(Files.writeString(root.resolve("notes.pdf"), "hello, this is not a pdf"))
            }
            assertTrue(notPdf.message.orEmpty().contains("不是 PDF"))
            assertTrue(notPdf.message.orEmpty().contains("换"))

            val encrypted = passwordPdf(root.resolve("locked.pdf"), "LOCKED-TOKEN")
            val missing = assertFailsWith<IllegalArgumentException> { library.stageBookImportTier0(encrypted) }
            assertTrue(missing.message.orEmpty().contains("输入打开密码"))
            assertTrue(missing.message.orEmpty().contains("密码保护"))
            val wrong = assertFailsWith<IllegalArgumentException> {
                library.stageBookImportTier0(encrypted, password = "wrong-password")
            }
            assertTrue(wrong.message.orEmpty().contains("密码不对"))
            assertTrue(library.catalog().isEmpty())

            val opened = library.stageBookImportTier0(encrypted, password = "reader-password").book
            assertTrue(opened.pages.any { "LOCKED-TOKEN" in it.text })
            BookPdfLoader.read(library.directory.resolve("${opened.id}.pdf").toFile()) { pdf -> pdf.numberOfPages }
            val again = TextbookLibrary(root.resolve("library"), streamPdfExtras = true, lazyPdfExtras = true)
            assertTrue(again.book(opened.id).pages.any { "LOCKED-TOKEN" in it.text })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun oneDamagedPageStaysListedAndTheRestStayReadable() {
        val root = Files.createTempDirectory("coach-damaged-page-")
        try {
            val library = TextbookLibrary(root.resolve("library"), streamPdfExtras = true, lazyPdfExtras = true)
            val staged = library.stageBookImportTier0(damagedPagePdf(root.resolve("torn.pdf")))
            val seen = ArrayList<Int>()
            val ready = library.indexBookTier1(staged.book.id) { page, _ -> seen += page }
            assertEquals(listOf(4), ready.damagedPages, "page4='${ready.page(4)?.text}' pending=${ready.page(4)?.ocrPending}")
            assertFalse(ready.page(4)!!.ocrPending)
            assertTrue(ready.page(1)!!.text.contains("GOOD-PAGE-1"))
            assertTrue(ready.page(2)!!.text.contains("GOOD-PAGE-2"))
            assertTrue(ready.page(3)!!.text.contains("GOOD-PAGE-3"))
            assertTrue(seen.contains(1) && seen.contains(4))
            assertTrue(ready.indexState == "READY")
            val sentence = BookImportMessages.damagedPages(ready.damagedPages)
            assertTrue(sentence.contains("第 4 页损坏，已跳过"))
            assertTrue(sentence.contains("其余页可以照常读"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun mixedPdfRecognizesOnlyTheBlankPageAndSearchJumpsToThatSentence() {
        val root = Files.createTempDirectory("coach-mixed-ocr-")
        try {
            var calls = 0
            val ocr = BookOcr { _, control ->
                control.check()
                calls += 1
                "SCANNED-SENTENCE from the empty page."
            }
            val library = TextbookLibrary(root.resolve("library"), ocr = ocr, streamPdfExtras = true, lazyPdfExtras = true)
            val staged = library.stageBookImportTier0(mixedPdf(root.resolve("mixed.pdf")))
            val indexed = library.indexBookTier1(staged.book.id)
            assertFalse(indexed.page(1)!!.ocrPending)
            assertTrue(indexed.page(1)!!.text.contains("VISIBLE-TEXT-PAGE"))
            assertTrue(indexed.page(2)!!.ocrPending)
            val recognized = library.resumeBookOcr(indexed.id)
            assertEquals(1, calls)
            assertTrue(recognized.page(2)!!.ocr)
            assertFalse(recognized.page(1)!!.ocr)
            val hit = recognized.let { library.searchBook(it.id, "SCANNED-SENTENCE").hits.single() }
            assertEquals(2, hit.page)
            val passage = hit.toPassage(recognized.id)
            assertEquals(2, passage.page)
            assertTrue(passage.start >= 0 && passage.end > passage.start)
            assertTrue(passage.text.contains("SCANNED-SENTENCE"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun passwordBoxAndDamagedLineShowInTheWorkspace() = runComposeUiTest {
        val root = Files.createTempDirectory("coach-pdf-ui-")
        try {
            val library = TextbookLibrary(root.resolve("library"), streamPdfExtras = true, lazyPdfExtras = true)
            val controller = TextbookController(library)
            val encrypted = passwordPdf(root.resolve("locked.pdf"), "LOCKED-TOKEN")
            runBlocking { controller.launchImport(encrypted).join() }
            setContent {
                TextbookWorkspace(controller = controller, loadOnMount = false, chooseBook = { null })
            }
            waitForIdle()
            onNodeWithTag("pdf-password").performTextInput("reader-password")
            onNodeWithTag("pdf-password-submit").performClick()
            waitUntil(timeoutMillis = 20_000) {
                controller.state.value.book?.pages?.any { "LOCKED-TOKEN" in it.text } == true
            }
            assertEquals(null, controller.state.value.passwordPromptPath)

            val damaged = library.stageBookImportTier0(damagedPagePdf(root.resolve("torn.pdf")))
            library.indexBookTier1(damaged.book.id)
            runBlocking { controller.openBook(damaged.book.id) }
            waitForIdle()
            onNodeWithTag("damaged-pages").assertExists()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun importSizeLimitIs512MbAndRejectsOversizeWithClearMessage() {
        val root = Files.createTempDirectory("coach-size-limit-")
        try {
            assertEquals(512L * 1024 * 1024, MAX_BOOK_IMPORT_BYTES)
            val libDir = root.resolve("library")
            val library = TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true)

            // A sparse file 1 byte past the 512 MB limit must fail fast in both import paths without leaving temp files.
            val oversize = root.resolve("oversize.pdf")
            java.io.RandomAccessFile(oversize.toFile(), "rw").use { it.setLength(MAX_BOOK_IMPORT_BYTES + 1L) }

            val errTier0 = assertFailsWith<IllegalArgumentException> {
                library.stageBookImportTier0(oversize)
            }
            assertEquals("请选择不超过 512 MB 的教材。", errTier0.message)

            val errStage = assertFailsWith<IllegalArgumentException> {
                library.stageBookImport(oversize)
            }
            assertEquals("请选择不超过 512 MB 的教材。", errStage.message)
            assertTrue(library.catalog().isEmpty())
            assertTrue(Files.list(libDir).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".import-") } })

            // Verify boundary enforcement at exact limit vs limit + 1 byte.
            val sample = onePagePdf(root.resolve("sample.pdf"), "SIZE-BOUNDARY-OK")
            val sampleSize = Files.size(sample)
            val tightLibrary = TextbookLibrary(root.resolve("tight"), streamPdfExtras = true, lazyPdfExtras = true, maxImportBytes = sampleSize - 1L)
            val tightErr = assertFailsWith<IllegalArgumentException> { tightLibrary.stageBookImportTier0(sample) }
            assertTrue(tightErr.message.orEmpty().contains("请选择不超过"))
            assertTrue(tightLibrary.catalog().isEmpty())

            val exactLibrary = TextbookLibrary(root.resolve("exact"), streamPdfExtras = true, lazyPdfExtras = true, maxImportBytes = sampleSize)
            val imported = exactLibrary.stageBookImportTier0(sample).book
            assertEquals(1, imported.pages.size)
            assertTrue(imported.page(1)!!.text.contains("SIZE-BOUNDARY-OK"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun settledPages(libDir: Path, bookId: String): Int =
        DriverManager.getConnection("jdbc:sqlite:${libDir.resolve("library.sqlite")}").use { db ->
            db.prepareStatement("SELECT COUNT(*) FROM pages WHERE book=? AND text_pending=0").use { sql ->
                sql.setString(1, bookId)
                sql.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
        }

    private fun onePagePdf(target: Path, token: String): Path {
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { out ->
                out.beginText()
                out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                out.newLineAtOffset(72f, 700f)
                out.showText(token)
                out.endText()
            }
            pdf.save(target.toFile())
        }
        return target
    }

    private fun thousandPagePdf(target: Path): Path {
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        PDDocument(org.apache.pdfbox.io.MemoryUsageSetting.setupTempFileOnly().streamCache).use { pdf ->
            pdf.documentInformation.title = "Synthetic Thousand"
            val outline = PDDocumentOutline()
            pdf.documentCatalog.documentOutline = outline
            val opening = "This opening page is the body of a synthetic textbook. It explains scarcity and incentives. Readers keep turning pages while indexing continues in the background."
            for (number in 1..1000) {
                val page = PDPage()
                pdf.addPage(page)
                if (number == 1) {
                    val dest = PDPageFitDestination().apply { this.page = page }
                    outline.addLast(PDOutlineItem().apply { title = "1 Body"; destination = dest })
                }
                PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { out ->
                    out.beginText()
                    out.setFont(font, 11f)
                    out.newLineAtOffset(72f, 700f)
                    out.showText(
                        when (number) {
                            1 -> opening
                            1000 -> "TAIL-TOKEN-1000 confirms the last page was indexed."
                            else -> "Synthetic body page $number."
                        },
                    )
                    out.endText()
                }
            }
            pdf.save(target.toFile())
        }
        return target
    }

    private fun truncatedPdf(target: Path): Path {
        val bytes = PDDocument().use { pdf ->
            repeat(3) { index ->
                val page = PDPage().also { pdf.addPage(it) }
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                    stream.newLineAtOffset(72f, 700f)
                    stream.showText("Coastal notes page ${index + 1}")
                    stream.endText()
                }
            }
            java.io.ByteArrayOutputStream().also { pdf.save(it) }.toByteArray()
        }
        Files.write(target, bytes.copyOf(bytes.size * 60 / 100))
        return target
    }

    private fun passwordPdf(target: Path, token: String): Path {
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                stream.newLineAtOffset(72f, 700f)
                stream.showText(token)
                stream.endText()
            }
            pdf.protect(StandardProtectionPolicy("owner-password", "reader-password", AccessPermission()).apply {
                encryptionKeyLength = 128
            })
            pdf.save(target.toFile())
        }
        return target
    }

    private fun damagedPagePdf(target: Path): Path {
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            repeat(4) { index ->
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 12f)
                    stream.newLineAtOffset(72f, 700f)
                    stream.showText("GOOD-PAGE-${index + 1}")
                    stream.endText()
                }
            }
            // PDFBox 3 skips a malformed content stream and returns blank text. A Type 0 font
            // with no descendant throws while reading that page only, which is the damaged-page case.
            val broken = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.FONT)
                setItem(COSName.SUBTYPE, COSName.TYPE0)
                setItem(COSName.BASE_FONT, COSName.getPDFName("BrokenPage"))
            }
            val fonts = COSDictionary()
            fonts.setItem(COSName.getPDFName("F1"), broken)
            val resources = PDResources()
            resources.cosObject.setItem(COSName.FONT, fonts)
            val torn = pdf.getPage(3)
            torn.resources = resources
            val contents = PDStream(pdf)
            contents.createOutputStream().use { out ->
                out.write("BT /F1 12 Tf 72 700 Td (GOOD-PAGE-4) Tj ET\n".toByteArray(Charsets.US_ASCII))
            }
            torn.setContents(contents)
            pdf.save(target.toFile())
        }
        return target
    }

    private fun mixedPdf(target: Path): Path {
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                stream.newLineAtOffset(72f, 700f)
                stream.showText("VISIBLE-TEXT-PAGE stays selectable.")
                stream.endText()
            }
            pdf.addPage(PDPage())
            pdf.save(target.toFile())
        }
        return target
    }
}
