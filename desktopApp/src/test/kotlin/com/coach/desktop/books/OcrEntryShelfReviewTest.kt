package com.coach.desktop.books

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class OcrEntryShelfReviewTest {
    private enum class Entry { DIRECT_IMAGE, DESKTOP_PDF, BOOKLIST }

    @Test fun directImagePreservesExistingShelf() = checkEntry(Entry.DIRECT_IMAGE)
    @Test fun windowsProgressivePdfPreservesExistingShelf() = checkEntry(Entry.DESKTOP_PDF)
    @Test fun reopenedBooklistPreservesExistingShelf() = checkEntry(Entry.BOOKLIST)

    private fun checkEntry(entry: Entry) = runBlocking {
        val root = Files.createTempDirectory("review-ocr-entry-")
        val directory = root.resolve("library")
        val source = root.resolve(if (entry == Entry.DESKTOP_PDF) "scan.pdf" else "scan.png")
        val raster = BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB)
        if (entry == Entry.DESKTOP_PDF) {
            PDDocument().use { pdf ->
                val page = PDPage(PDRectangle(96f, 96f))
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use {
                    it.drawImage(LosslessFactory.createFromImage(pdf, raster), 0f, 0f, 96f, 96f)
                }
                pdf.save(source.toFile())
            }
        } else {
            ImageIO.write(raster, "png", source.toFile())
        }
        val started = CompletableDeferred<Unit>()
        val gate = BookOcr { _, control ->
            started.complete(Unit)
            while (!control.isCancelled()) Thread.sleep(5)
            control.check()
            ""
        }
        val existingId = "sb_123456781234567812345678"
        val unbinds = CopyOnWriteArrayList<String>()
        val uploads = CopyOnWriteArrayList<String>()
        val remote = object : UnifiedBookRemoteClient {
            override suspend fun activeAccountKey() = "synthetic-account"
            override suspend fun checkCapability() = UnifiedBookCapability.Supported(activeAccountKey())
            // This account already has the one book in the test on its shelf.
            override suspend fun uploadBook(file: Path, format: String, title: String, sha256: String, onProgress: (Int) -> Unit): UnifiedBookUploadOutcome {
                uploads.add(sha256)
                return UnifiedBookUploadOutcome.Accepted(existingId, sha256, BookUploadState.QUEUED, 0, "")
            }
            override suspend fun fetchStatus(shelfBookId: String) = UnifiedBookStatusOutcome.Offline
            override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome {
                unbinds.add(shelfBookId)
                return UnifiedBookDeleteOutcome.Deleted
            }
        }
        suspend fun openLibrary(): TextbookController {
            val lib = TextbookLibrary(directory, ocr = gate, streamPdfExtras = true, lazyPdfExtras = true)
            val controller = TextbookController(lib).apply { unifiedUploadClient = remote }
            controller.onUploadIdentityChanged(remote.activeAccountKey())?.join()
            return controller
        }
        val first = openLibrary()
        val controller = if (entry == Entry.BOOKLIST) {
            first.createCollection(CollectionKind.BOOKLIST, "Sample", newFiles = listOf(source)).join()
            openLibrary().also { it.load() }
        } else {
            first.launchImport(source)
            first
        }
        try {
            withTimeout(20000) { started.await() }
            controller.syncBookUploads().join()
        } finally {
            controller.cancelImport()
        }
        withTimeout(20000) { while (controller.state.value.importing != null) delay(5) }
        controller.syncBookUploads().join()
        assertEquals(emptyList<String>(), unbinds.toList(),
            "Cancelling OCR via " + entry + " must keep the pre-existing shelf; uploads=" + uploads.size)
    }
}
