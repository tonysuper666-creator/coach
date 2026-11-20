package com.coach.android

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.coach.desktop.books.BookImportCancelled
import com.coach.desktop.books.BookImportControl
import com.coach.desktop.books.BookImportProgress
import com.coach.desktop.books.BookOcr
import com.coach.desktop.books.BundledBookOcr
import com.coach.desktop.books.SafeBookArchive
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.bookOcrMaxSide
import com.coach.desktop.books.exam.LocalExamController
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBookImportParityTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        Class.forName("org.sqlite.JDBC")
    }

    @Test
    fun largeDocxOver64MiBStreamsOnAndroidXmlPullParser() {
        val root = Files.createTempDirectory("coach-android-large-docx-")
        try {
            val docx = root.resolve("large-algebra.docx")
            val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            val relNs = "http://schemas.openxmlformats.org/package/2006/relationships"
            ZipOutputStream(Files.newOutputStream(docx)).use { zip ->
                fun entry(name: String, block: (java.io.OutputStream) -> Unit) {
                    zip.putNextEntry(ZipEntry(name))
                    block(zip)
                    zip.closeEntry()
                }
                entry("word/styles.xml") { out ->
                    out.write("""<w:styles xmlns:w="$w"><w:style w:styleId="Heading1"><w:name w:val="heading 1"/></w:style></w:styles>""".toByteArray())
                }
                entry("word/_rels/document.xml.rels") { out ->
                    out.write("""<Relationships xmlns="$relNs"><Relationship Id="rId1" Type="$r/image" Target="media/formula.emf"/></Relationships>""".toByteArray())
                }
                entry("word/document.xml") { out ->
                    out.write("""<w:document xmlns:w="$w"><w:body>""".toByteArray())
                    out.write("""<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Chapter 1 Linear Equations</w:t></w:r></w:p>""".toByteArray())
                    val chunk = "<w:p><w:r><w:rPr><w:b/><w:sz w:val=\"24\"/></w:rPr><w:t>x</w:t></w:r></w:p>".toByteArray()
                    repeat(260_000) { out.write(chunk) }
                    out.write("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""".toByteArray())
                    out.write("""<w:p><w:r><w:t>Final page verified on Android.</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
                }
                val zeros = ByteArray(64 * 1024)
                for (i in 1..4) {
                    entry("word/media/pad$i.emf") { out -> repeat(224) { out.write(zeros) } }
                }
            }
            SafeBookArchive(docx).use { archive ->
                assertTrue(archive.size("word/document.xml")!! > 16L * 1024 * 1024)
                assertTrue(archive.declaredTotalBytes > 64L * 1024 * 1024)
            }
            val library = TextbookLibrary(root.resolve("library"), streamPdfExtras = true)
            val book = library.importBook(docx)
            assertEquals("docx", book.format)
            assertTrue(book.pages.size >= 2)
            assertTrue(book.pages.first().text.contains("Chapter 1 Linear Equations"))
            assertTrue(book.pages.last().text.contains("Final page verified on Android."))
            val reopened = TextbookLibrary(library.directory, streamPdfExtras = true).book(book.id)
            assertEquals(book.pages, reopened.pages)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun androidScanPdfOcrBoundsRasterSizeReportsAllPagesAndCleansUpOnCancel() {
        val root = Files.createTempDirectory("coach-android-ocr-")
        try {
            val pdfFile = root.resolve("scan-3pages.pdf")
            PDDocument().use { doc ->
                // Tall scanned-like physical pages (473 x 807 pt -> previously 1970 x 3362 at 300 dpi).
                repeat(3) { doc.addPage(PDPage(PDRectangle(473f, 807f))) }
                doc.save(pdfFile.toFile())
            }
            val observedSizes = mutableListOf<Pair<Int, Int>>()
            val progressEvents = mutableListOf<BookImportProgress>()
            val library = TextbookLibrary(
                root.resolve("library"),
                ocr = BookOcr { bitmap, control ->
                    control.check()
                    observedSizes += bitmap.width to bitmap.height
                    "OCR page ${observedSizes.size}"
                },
                streamPdfExtras = true,
            )
            val book = library.importBook(pdfFile, BookImportControl(progress = { progressEvents += it }))
            assertEquals(3, book.pages.size)
            assertEquals(3, observedSizes.size)
            assertTrue(
                "Expected Android OCR raster long edge <= ${bookOcrMaxSide.toInt()}, got $observedSizes",
                observedSizes.all { (w, h) -> maxOf(w, h) <= bookOcrMaxSide.toInt() },
            )
            val recognizingPages = progressEvents.filter { it.recognizing }.map { it.page to it.total }
            assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), recognizingPages)
            assertTrue(book.pages.all { it.ocr && it.text.startsWith("OCR page ") })

            // Verify prepareBitmap downscales oversize scan photos and returns an isolated copy.
            val huge = Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888)
            val prepared = BundledBookOcr.prepareBitmap(huge)
            try {
                assertNotSame(huge, prepared)
                assertTrue(maxOf(prepared.width, prepared.height) <= bookOcrMaxSide.toInt())
                huge.recycle()
                assertFalse(prepared.isRecycled)
            } finally { prepared.recycle() }

            // Verify cancellation during OCR leaves zero partial imports.
            val cancelLib = TextbookLibrary(
                root.resolve("cancel-library"),
                ocr = BookOcr { _, _ -> throw BookImportCancelled() },
                streamPdfExtras = true,
            )
            var cancelled = false
            try { cancelLib.importBook(pdfFile) } catch (_: BookImportCancelled) { cancelled = true }
            assertTrue(cancelled)
            assertTrue(cancelLib.catalog().isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun large620PagePdfStreamsImportAndColdStartsShelfWithoutLoadingTextBoxes() = runBlocking {
        val root = Files.createTempDirectory("coach-android-620p-")
        try {
            val pdfFile = root.resolve("health-econ-620p.pdf")
            PDDocument().use { doc ->
                val font = PDType1Font.HELVETICA
                for (i in 1..620) {
                    val page = PDPage(PDRectangle.LETTER)
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { cs ->
                        cs.beginText()
                        cs.setFont(font, 11f)
                        cs.newLineAtOffset(50f, 740f)
                        cs.showText("Chapter ${(i - 1) / 20 + 1} Health Economics Page $i")
                        cs.newLineAtOffset(0f, -18f)
                        cs.showText("Demand for healthcare is derived from demand for health capital and market outcomes.")
                        cs.endText()
                    }
                }
                doc.save(pdfFile.toFile())
            }
            val libraryDir = root.resolve("library")
            val library = TextbookLibrary(libraryDir, streamPdfExtras = true)
            val imported = library.importBook(pdfFile)
            assertEquals(620, imported.pages.size)
            assertTrue("Imported book must not retain 620 PageTextLayouts in RAM", imported.pages.all { it.textLayout == null })

            // Simulate Android cold start onto shelf with a 620-page book already persisted.
            val t0 = System.nanoTime()
            val reopened = TextbookLibrary(libraryDir, streamPdfExtras = true)
            val controller = TextbookController(reopened)
            controller.load()
            val coldMs = (System.nanoTime() - t0) / 1_000_000
            println("Android620PageColdStartMs=$coldMs")
            val loaded = controller.state.value.book!!
            assertEquals(620, loaded.pages.size)
            assertTrue("Cold-start shelf must skip eager textLayout decode", loaded.pages.all { it.textLayout == null })
            assertTrue("Cold-start shelf load with 620p book took ${coldMs}ms", coldMs < 10_000)

            // Shelf entry defers reference paper creation while on-demand page text layers still work.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val exams = LocalExamController(reopened, scope)
                exams.load(loaded, prepareReference = false)
                assertNull(exams.state.value.reference)
            } finally { scope.cancel() }
            assertNotNull(reopened.pdfTextLayer(loaded, loaded.pages.first()))
            assertNotNull(reopened.pdfTextLayer(loaded, loaded.pages.last()))
        } finally { root.toFile().deleteRecursively() }
    }
}
