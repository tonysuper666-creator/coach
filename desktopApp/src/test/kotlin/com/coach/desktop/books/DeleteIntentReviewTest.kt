package com.coach.desktop.books

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeleteIntentReviewTest {
    private val account = "synthetic-account"
    private val shelfId = "sb_0123456789abcdef01234567"

    private inner class ReadOnlyRemote : UnifiedBookRemoteClient {
        var status: suspend () -> UnifiedBookStatusOutcome = { UnifiedBookStatusOutcome.Offline }
        override suspend fun activeAccountKey() = account
        override suspend fun checkCapability() = UnifiedBookCapability.Supported(account)
        override suspend fun uploadBook(file: Path, format: String, title: String, sha256: String, onProgress: (Int) -> Unit) =
            UnifiedBookUploadOutcome.Offline
        override suspend fun fetchStatus(shelfBookId: String) = status()
    }

    private fun rememberSyncedBook(lib: TextbookLibrary, book: Textbook) {
        lib.enqueueBookUpload(account, book.id, book.id, book.format, book.title)
        lib.updateBookUploadState(account, book.id, BookUploadState.QUEUED, serverShelfBookId = shelfId)
    }

    @Test fun cancellingDuplicateImportPreservesExistingShelfAndLocalBook() = runBlocking {
        val root = Files.createTempDirectory("review-duplicate-")
        val lib = TextbookLibrary(root.resolve("library"))
        val source = Files.writeString(root.resolve("input.txt"), "An existing synthetic book.")
        val book = lib.importBook(source)
        rememberSyncedBook(lib, book)
        val controller = TextbookController(lib).apply { unifiedUploadClient = ReadOnlyRemote() }
        controller.onUploadIdentityChanged(account)?.join()
        controller.launchImport(source, onStaged = { controller.cancelImport() }).join()
        val actual = lib.readUploadEntry(account, book.id)?.state
        val localPresent = lib.catalog().any { it.id == book.id }
        assertTrue(localPresent && actual == BookUploadState.QUEUED, "localPresent=$localPresent queue=$actual")
    }

    @Test fun statusReplyPreservesPendingDelete() = runBlocking {
        val root = Files.createTempDirectory("review-status-")
        val lib = TextbookLibrary(root.resolve("library"))
        val book = lib.importBook(Files.writeString(root.resolve("input.txt"), "Synthetic sample for status."))
        rememberSyncedBook(lib, book)
        val requested = CompletableDeferred<Unit>()
        val allowReply = CompletableDeferred<Unit>()
        val remote = ReadOnlyRemote().apply {
            status = {
                requested.complete(Unit)
                allowReply.await()
                UnifiedBookStatusOutcome.Ready(BookUploadState.QUEUED, 0, "")
            }
        }
        val coordinator = UnifiedBookUploadCoordinator(lib) { remote }
        val poll = launch { coordinator.syncOnce() }
        withTimeout(15000) { requested.await() }
        try {
            lib.deleteBook(book.id, account)
            assertEquals(BookUploadState.PENDING_DELETE, lib.readUploadEntry(account, book.id)?.state)
        } finally {
            allowReply.complete(Unit)
        }
        poll.join()
        assertEquals(BookUploadState.PENDING_DELETE, lib.readUploadEntry(account, book.id)?.state)
    }

    @Test fun finalOfflineRemovalRetainsUnacknowledgedShelfId() {
        val root = Files.createTempDirectory("review-offline-")
        val lib = TextbookLibrary(root.resolve("library"))
        val source = Files.writeString(root.resolve("input.txt"), "Synthetic offline reimport.")
        val book = lib.importBook(source)
        rememberSyncedBook(lib, book)
        lib.deleteBook(book.id, account)
        assertEquals(shelfId, lib.readUploadEntry(account, book.id)?.serverShelfBookId)
        val again = lib.importBook(source)
        lib.enqueueBookUpload(account, again.id, again.id, again.format, again.title)
        lib.deleteBook(again.id, account)
        assertEquals(shelfId, lib.syncTasksForAccount(account).singleOrNull { it.state == BookUploadState.PENDING_DELETE }?.serverShelfBookId)
    }

    @Test fun resumedOcrWithoutRecordedOwnerPreservesExistingShelf() = runBlocking {
        val root = Files.createTempDirectory("review-ocr-")
        val dir = root.resolve("library")
        val image = root.resolve("scan.png")
        ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY), "png", image.toFile())
        val setup = TextbookLibrary(dir)
        val book = setup.stageBookImport(image).book
        assertTrue(book.hasPendingOcr)
        rememberSyncedBook(setup, book)
        val started = CompletableDeferred<Unit>()
        val reopened = TextbookLibrary(dir, ocr = BookOcr { _, control ->
            started.complete(Unit)
            while (!control.isCancelled()) Thread.sleep(5)
            control.check()
            ""
        })
        val controller = TextbookController(reopened).apply { unifiedUploadClient = ReadOnlyRemote() }
        controller.onUploadIdentityChanged(account)?.join()
        controller.load()
        try {
            withTimeout(15000) { started.await() }
        } finally {
            controller.cancelImport()
        }
        withTimeout(15000) { while (controller.state.value.importing != null) delay(10) }
        assertEquals(BookUploadState.QUEUED, reopened.readUploadEntry(account, book.id)?.state)
    }
}
