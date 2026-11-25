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

class CancelShelfStateReviewTest {
    private class SyntheticShelf(initiallyPresent: Boolean, @Volatile var online: Boolean) : UnifiedBookRemoteClient {
        private val id = "sb_0123456789abcdef01234567"
        @Volatile var storedId: String? = if (initiallyPresent) id else null
        var uploads = 0
        var unbinds = 0

        override suspend fun activeAccountKey() = "synthetic-account"
        override suspend fun checkCapability() =
            if (online) UnifiedBookCapability.Supported(activeAccountKey()) else UnifiedBookCapability.Offline

        override suspend fun uploadBook(file: Path, format: String, title: String, sha256: String, onProgress: (Int) -> Unit): UnifiedBookUploadOutcome {
            uploads++
            storedId = storedId ?: id
            return UnifiedBookUploadOutcome.Accepted(id, sha256, BookUploadState.QUEUED, 0, "")
        }

        override suspend fun fetchStatus(shelfBookId: String) = UnifiedBookStatusOutcome.Offline
        override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome {
            unbinds++
            if (storedId != shelfBookId) return UnifiedBookDeleteOutcome.NotFound
            storedId = null
            return UnifiedBookDeleteOutcome.Deleted
        }
    }

    @Test fun alreadyLocalAndRemote() = checkCancellation(local = true, remote = true)
    @Test fun newLocallyAndRemotely() = checkCancellation(local = false, remote = false)
    @Test fun existingRemoteShelfOnNewLocalLibrary() = checkCancellation(local = false, remote = true)
    @Test fun localOnlyBookLeavesNoNewRemoteShelf() = checkCancellation(local = true, remote = false)
    @Test fun offlineLocalOnlyCancelThenReconnect() = checkCancellation(local = true, remote = false, online = false)

    private fun checkCancellation(local: Boolean, remote: Boolean, online: Boolean = true) = runBlocking {
        val dir = Files.createTempDirectory("review-shelf-state-")
        val image = dir.resolve("scan.png")
        ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY), "png", image.toFile())
        val reading = CompletableDeferred<Unit>()
        val lib = TextbookLibrary(dir.resolve("library"), ocr = BookOcr { _, control ->
            reading.complete(Unit)
            while (!control.isCancelled()) Thread.sleep(5)
            control.check()
            ""
        })
        if (local) assertTrue(lib.stageBookImport(image).book.hasPendingOcr)
        val shelf = SyntheticShelf(remote, online)
        val initialId = shelf.storedId
        val controller = TextbookController(lib).apply { unifiedUploadClient = shelf }
        controller.onUploadIdentityChanged(shelf.activeAccountKey())?.join()
        val task = controller.launchImport(image)
        try {
            withTimeout(15000) { reading.await() }
            controller.syncBookUploads().join()
            if (!online) assertEquals(0, shelf.uploads)
        } finally {
            controller.cancelImport()
            task.join()
        }
        val uploadsAtCancel = shelf.uploads
        shelf.online = true
        controller.syncBookUploads().join()
        assertEquals(local, lib.catalog().isNotEmpty())
        assertEquals(initialId, shelf.storedId,
            "local=$local remote=$remote onlineAtStart=$online uploadsAtCancel=$uploadsAtCancel totalUploads=" +
                shelf.uploads + " unbinds=" + shelf.unbinds)
    }
}
