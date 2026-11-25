package com.coach.desktop.books

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportBindingCrossReviewTest {
    private class SyntheticRemote : UnifiedBookRemoteClient {
        val sessionKey = "synthetic-session"
        val verifiedAccount = "synthetic-account"
        val shelfId = "sb_123456781234567812345678"
        @Volatile var online = true
        var uploads = 0
        var deletes = 0
        override suspend fun activeAccountKey() = sessionKey
        override suspend fun checkCapability() =
            if (online) UnifiedBookCapability.Supported(verifiedAccount) else UnifiedBookCapability.Offline
        override suspend fun uploadBook(file: Path, format: String, title: String, sha256: String, onProgress: (Int) -> Unit): UnifiedBookUploadOutcome {
            uploads++
            return UnifiedBookUploadOutcome.Accepted(shelfId, sha256, BookUploadState.QUEUED, 0, "")
        }
        override suspend fun fetchStatus(shelfBookId: String) = UnifiedBookStatusOutcome.Offline
        override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome {
            assertEquals(shelfId, shelfBookId)
            deletes++
            return UnifiedBookDeleteOutcome.Deleted
        }
    }

    @Test fun explicitImportOfLocalOnlyBookBindsCurrentAccount() = runBlocking {
        val dir = Files.createTempDirectory("review-local-book-")
        val source = Files.writeString(dir.resolve("sample.txt"), "This is a synthetic local book.")
        val library = TextbookLibrary(dir.resolve("library"))
        val controller = TextbookController(library)
        controller.importBook(source)
        val existing = library.catalog().single().id
        val remote = SyntheticRemote()
        controller.unifiedUploadClient = remote
        controller.onUploadIdentityChanged(remote.sessionKey)?.join()
        controller.importBook(source)
        controller.syncBookUploads().join()
        assertEquals(existing, library.catalog().single().id)
        assertEquals(remote.shelfId, library.readUploadEntry(remote.verifiedAccount, existing)?.serverShelfBookId,
            "Expected an account shelf after explicit import; upload count=" + remote.uploads)
    }

    @Test fun booklistScanRetainsOwnerWhenCancelledAfterRestart() = runBlocking {
        val root = Files.createTempDirectory("review-booklist-")
        val dir = root.resolve("library")
        val image = root.resolve("sample.png")
        ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY), "png", image.toFile())
        val original = TextbookLibrary(dir)
        val remote = SyntheticRemote()
        val first = TextbookController(original).apply { unifiedUploadClient = remote }
        first.onUploadIdentityChanged(remote.sessionKey)?.join()
        first.createCollection(CollectionKind.BOOKLIST, "Sample", existingBookIds = emptyList(), newFiles = listOf(image)).join()
        val id = original.catalog().single().id
        withTimeout(15000) {
            while (original.readUploadEntry(remote.verifiedAccount, id)?.serverShelfBookId == null) kotlinx.coroutines.delay(10)
        }
        val recorded = original.importAccountKey(id)
        val resumed = CompletableDeferred<Unit>()
        val reopened = TextbookLibrary(dir, ocr = BookOcr { _, control ->
            resumed.complete(Unit)
            while (!control.isCancelled()) Thread.sleep(5)
            control.check()
            ""
        })
        val second = TextbookController(reopened).apply { unifiedUploadClient = remote }
        second.onUploadIdentityChanged(remote.sessionKey)?.join()
        second.load()
        try {
            withTimeout(15000) { resumed.await() }
        } finally {
            second.cancelImport()
        }
        withTimeout(15000) { while (second.state.value.importing != null) kotlinx.coroutines.delay(10) }
        second.syncBookUploads().join()
        assertEquals(1, remote.deletes, "Recorded import owner=" + recorded + ", queue=" +
            reopened.readUploadEntry(remote.verifiedAccount, id)?.state)
    }

    @Test fun cancelDuringFirstReconnectUsesVerifiedBinding() = runBlocking {
        val dir = Files.createTempDirectory("review-reconnect-")
        val source = dir.resolve("scan.png")
        ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY), "png", source.toFile())
        val started = CompletableDeferred<Unit>()
        val library = TextbookLibrary(dir.resolve("library"), ocr = BookOcr { _, control ->
            started.complete(Unit)
            while (!control.isCancelled()) Thread.sleep(5)
            control.check()
            ""
        })
        val remote = SyntheticRemote().apply { online = false }
        val controller = TextbookController(library).apply { unifiedUploadClient = remote }
        controller.onUploadIdentityChanged(remote.sessionKey)?.join()
        val importing = controller.launchImport(source)
        val bookId: String
        try {
            withTimeout(15000) { started.await() }
            bookId = library.catalog().single().id
            assertEquals(remote.sessionKey, library.importAccountKey(bookId))
            remote.online = true
            controller.syncBookUploads().join()
            assertEquals(remote.verifiedAccount, library.importAccountKey(bookId))
            assertEquals(remote.shelfId, library.readUploadEntry(remote.verifiedAccount, bookId)?.serverShelfBookId)
        } finally {
            controller.cancelImport()
            importing.join()
        }
        controller.syncBookUploads().join()
        assertTrue(library.catalog().isEmpty())
        val state = library.readUploadEntry(remote.verifiedAccount, bookId)?.state
        assertEquals(1, remote.deletes, "Verified account row after cancellation: " + state)
    }
}
