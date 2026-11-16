package com.coach.desktop.books

import com.coach.desktop.reader.LocalBooksReaderSource
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.test.*

class BookOcrTest {
    private fun fixture(root: Path, name: String) = root.resolve(name).also { file ->
        checkNotNull(javaClass.getResourceAsStream("/books/ocr/$name")).use { Files.copy(it, file) }
    }
    private fun mixedPdf(root: Path, blanks: Int = 1): Path = root.resolve("mixed.pdf").also { path ->
        PDDocument().use { pdf ->
            pdf.addPage(PDPage())
            PDPageContentStream(pdf, pdf.getPage(0)).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                stream.newLineAtOffset(50f, 700f); stream.showText("Existing text layer stays exact."); stream.endText()
            }
            repeat(blanks) { pdf.addPage(PDPage()) }
            pdf.save(path.toFile())
        }
    }

    @Test fun mixedPagesPersistOnceAndKeepUtf16CitationsAfterRestart() = runBlocking {
        val root = Files.createTempDirectory("coach-ocr-persistence-")
        try {
            var calls = 0
            val recognized = "自制学习材料 🧪\n\n细胞是生命活动的基本单位。Energy flows through cells.\n"
            val progress = mutableListOf<BookImportProgress>()
            val library = TextbookLibrary(root.resolve("library"), ocr = BookOcr { _, _ -> calls++; recognized })
            val input = mixedPdf(root)
            val book = library.importBook(input, BookImportControl(progress = { progress += it }))
            assertEquals(listOf(false, true), book.pages.map { it.ocr })
            assertContains(book.pages[0].text, "Existing text layer stays exact.")
            assertEquals(recognized, book.pages[1].text)
            assertEquals(listOf(2), progress.filter { it.recognizing }.map { it.page })
            assertEquals(1, calls)
            assertEquals(book, library.importBook(input))
            assertEquals(1, calls, "deduplication never runs OCR again")
            Files.delete(input)
            val reopened = TextbookLibrary(library.directory, ocr = BookOcr { _, _ -> error("must not re-recognize") })
            assertEquals(book.pages, reopened.book(book.id).pages)
            val quote = "细胞是生命活动的基本单位。"
            val start = recognized.indexOf(quote)
            assertTrue(start > recognized.codePointCount(0, start), "offset includes supplementary character in UTF-16")
            val target = assertIs<BookSourceResolution.Resolved>(BookSourceResolver(reopened).resolve(
                OwnedBookCitation(book.id, 2, start, start + quote.length), quote)).target
            assertEquals(quote, reopened.book(book.id).page(2)!!.text.substring(target.start, target.end))
            assertTrue(BookSourceResolver(reopened).stillValid(target))
            val remote = LocalBooksReaderSource(reopened)
            assertEquals(recognized, remote.page(book.id, 2).body!!.jsonObject["text"]!!.jsonPrimitive.content)
            assertNotNull(remote.image(book.id, 2, "1"))
            assertTrue(remote.boxes(book.id, 2, "1").body.toString().contains("\"words\":[]"), "never fabricate coordinates on scans")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun oldLibraryMigrationKeepsTextAndDefaultsToNoOcr() {
        val root = Files.createTempDirectory("coach-ocr-migration-")
        try {
            DriverManager.getConnection("jdbc:sqlite:${root.resolve("library.sqlite")}").use { db ->
                db.createStatement().use { sql ->
                    sql.execute("CREATE TABLE pages(book TEXT,page INTEGER,text TEXT,PRIMARY KEY(book,page))")
                    sql.execute("INSERT INTO pages VALUES('legacy',1,'旧文字 🧪')")
                }
            }
            TextbookLibrary(root)
            DriverManager.getConnection("jdbc:sqlite:${root.resolve("library.sqlite")}").use { db ->
                db.createStatement().executeQuery("SELECT text,ocr FROM pages").use { rows ->
                    assertTrue(rows.next()); assertEquals("旧文字 🧪", rows.getString(1)); assertEquals(0, rows.getInt(2))
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cancelFailureAndLimitsLeaveNoPartialBookAndNextImportWorks() = runBlocking {
        val root = Files.createTempDirectory("coach-ocr-cancel-")
        try {
            val input = mixedPdf(root, 2)
            val library = TextbookLibrary(root.resolve("library"), ocr = BookOcr { _, control -> control.cancel(); control.check(); "" })
            assertFailsWith<BookImportCancelled> { library.importBook(input) }
            assertTrue(library.catalog().isEmpty())
            assertTrue(Files.list(library.directory).use { stream -> stream.noneMatch { it.fileName.toString().startsWith(".import-") || it.toString().endsWith(".pdf") } })
            val failed = TextbookLibrary(root.resolve("failed"), ocr = BookOcr { _, _ -> throw IllegalArgumentException("文字识别模型无法读取，请重新安装。") })
            val controller = TextbookController(failed)
            controller.importBook(input)
            assertContains(controller.state.value.error!!, "模型")
            assertFalse(controller.state.value.busy); assertNull(controller.state.value.importing)
            assertTrue(failed.catalog().isEmpty())
            val good = root.resolve("good.txt").also { Files.writeString(it, "细胞是生命活动的基本单位。") }
            controller.importBook(good)
            assertEquals(1, failed.catalog().size); assertNull(controller.state.value.error)
            assertFailsWith<IllegalArgumentException> { BookScanImages.checkSize(5000, 5000) }
            val tooMany = mixedPdf(root, 201)
            assertContains(assertFailsWith<IllegalArgumentException> { library.importBook(tooMany) }.message!!, "200")
            assertTrue(library.catalog().isEmpty())
            // Metadata limit rejects the image before allocating a 25-million-pixel raster.
            val image = root.resolve("oversize.png")
            val raster = BufferedImage(5000, 5000, BufferedImage.TYPE_BYTE_GRAY)
            ImageIO.write(raster, "png", image.toFile()); raster.flush()
            assertContains(assertFailsWith<IllegalArgumentException> { library.importBook(image) }.message!!, "1600")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun controllerCancelRemainsResponsiveWhileImportOwnsLibraryMonitor() = runBlocking {
        val root = Files.createTempDirectory("coach-ocr-controller-")
        try {
            val entered = java.util.concurrent.CountDownLatch(1)
            val library = TextbookLibrary(root.resolve("library"), ocr = BookOcr { _, control ->
                entered.countDown()
                while (!control.isCancelled()) Thread.sleep(10)
                control.check(); ""
            })
            val controller = TextbookController(library)
            val job = launch(Dispatchers.Default) { controller.importBook(mixedPdf(root)) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, java.util.concurrent.TimeUnit.SECONDS) })
            assertTrue(controller.state.value.importing!!.recognizing)
            controller.cancelImport()
            withTimeout(3000) { job.join() }
            assertContains(controller.state.value.error!!, "取消")
            assertFalse(controller.state.value.busy); assertNull(controller.state.value.importing)
            assertTrue(library.catalog().isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun actualBundledEngineImportsThreeScansJpegAndPdfOffline() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-ocr-native-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            for (name in listOf("01-clean-serif.png", "02-two-columns.png", "03-camera-skew.png", "03-camera-skew.jpg", "three-scanned-pages.pdf")) {
                val start = System.nanoTime()
                val book = library.importBook(fixture(root, name))
                assertEquals(if (name.endsWith("pdf")) 3 else 1, book.pages.size)
                for (page in book.pages) {
                    assertTrue(page.ocr); assertTrue(page.text.length > 200, "$name: ${page.text}")
                    assertTrue(page.text.any { it in '\u4e00'..'\u9fff' })
                    assertTrue(page.text.any { it in 'A'..'Z' || it in 'a'..'z' })
                }
                assertEquals(book.pages, TextbookLibrary(library.directory).book(book.id).pages)
                assertNotNull(LocalBooksReaderSource(library).image(book.id, 1, "1"))
                println("NATIVE_OCR file=$name pages=${book.pages.size} chars=${book.pages.sumOf { it.text.length }} ms=${(System.nanoTime()-start)/1_000_000} persisted=true")
            }
            val image = BookScanImages.read(root.resolve("02-two-columns.png"))
            try {
                val control = BookImportControl()
                val canceller = launch(Dispatchers.IO) { delay(500); control.cancel() }
                val start = System.nanoTime()
                assertFailsWith<BookImportCancelled> { BundledBookOcr.recognize(image, control) }
                canceller.join()
                assertTrue((System.nanoTime() - start) < 5_000_000_000)
                println("NATIVE_OCR cancellationMs=${(System.nanoTime()-start)/1_000_000}")
            } finally { image.flush() }
        } finally { root.toFile().deleteRecursively() }
    }
}
