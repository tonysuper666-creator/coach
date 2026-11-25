package com.coach.desktop.books

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExistingShelfCancelReviewTest {
    @Test fun cancellingLocalReimportLeavesPreexistingAccountShelfAlone() = runBlocking {
        val root = Files.createTempDirectory("review-existing-shelf-")
        val source = root.resolve("sample.png")
        ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY), "png", source.toFile())
        val enteredOcr = CompletableDeferred<Unit>()
        val library = TextbookLibrary(root.resolve("library"), ocr = BookOcr { _, control ->
            enteredOcr.complete(Unit)
            while (!control.isCancelled()) Thread.sleep(5)
            control.check()
            ""
        })
        val book = library.stageBookImport(source).book
        assertTrue(book.hasPendingOcr)
        val account = "synthetic-account"
        assertNull(library.readUploadEntry(account, book.id))
        val existingShelf = mapOf(book.id to "sb_123456781234567812345678")
        val deleteRequests = CopyOnWriteArrayList<String>()
        val remote = object : UnifiedBookRemoteClient {
            override suspend fun activeAccountKey() = account
            override suspend fun checkCapability() = UnifiedBookCapability.Supported(account)
            override suspend fun uploadBook(file: Path, format: String, title: String, sha256: String, onProgress: (Int) -> Unit) =
                UnifiedBookUploadOutcome.Accepted(existingShelf.getValue(sha256), sha256, BookUploadState.QUEUED, 0, "")
            override suspend fun fetchStatus(shelfBookId: String) = UnifiedBookStatusOutcome.Offline
            override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome {
                deleteRequests.add(shelfBookId)
                return UnifiedBookDeleteOutcome.Deleted
            }
        }
        val controller = TextbookController(library).apply { unifiedUploadClient = remote }
        controller.onUploadIdentityChanged(account)?.join()
        val importing = controller.launchImport(source)
        try {
            withTimeout(15000) {
                enteredOcr.await()
            }
            controller.syncBookUploads().join()
            assertNull(library.readUploadEntry(account, book.id))
        } finally {
            controller.cancelImport()
            importing.join()
        }
        controller.syncBookUploads().join()
        assertEquals(book.id, library.catalog().single().id)
        assertEquals(emptyList<String>(), deleteRequests.toList(),
            "Cancelling a duplicate import must preserve the shelf that existed before import")
    }
}
