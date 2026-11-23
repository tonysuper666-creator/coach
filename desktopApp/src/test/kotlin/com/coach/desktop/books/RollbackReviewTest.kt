package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class RollbackReviewTest {
    private class Remote : UnifiedBookRemoteClient {
        val device = "local-synthetic-device"
        val account = "sable-synthetic-account"
        var capability: UnifiedBookCapability = UnifiedBookCapability.Supported(account)
        var outcome: UnifiedBookUploadOutcome = UnifiedBookUploadOutcome.Offline
        var calls = 0
        override suspend fun activeAccountKey() = device
        override suspend fun checkCapability() = capability
        override suspend fun uploadBook(file: Path, format: String, title: String, sha256: String, onProgress: (Int) -> Unit): UnifiedBookUploadOutcome {
            calls++
            return outcome
        }
        override suspend fun fetchStatus(shelfBookId: String) = UnifiedBookStatusOutcome.NotFound
        override suspend fun deleteBook(shelfBookId: String) = true
    }

    private suspend fun withBook(check: suspend (UnifiedBookUploadCoordinator, Remote, String) -> Unit) {
        val root = Files.createTempDirectory("coach-review-rollback-")
        try {
            val lib = TextbookLibrary(root.resolve("library"))
            val book = lib.importBook(Files.writeString(root.resolve("sample.txt"), "Synthetic chapter for rollback."))
            val remote = Remote()
            val queue = UnifiedBookUploadCoordinator(lib) { remote }
            queue.enqueueBook(book.id, book.id, book.format, book.title)
            check(queue, remote, book.id)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun sameSessionPendingBookShowsWaitingOnOldServer() = runBlocking {
        withBook { queue, remote, id ->
            queue.syncOnce()
            assertEquals(BookUploadState.WAITING_NETWORK, queue.statuses.value[id]?.state)
            remote.capability = UnifiedBookCapability.Unsupported(remote.device)
            queue.syncOnce()
            assertEquals(BookUploadState.WAITING_SERVER, queue.statuses.value[id]?.state)
        }
    }

    @Test
    fun retryUploadsLocalCopyAfterServerShelfWasRemoved() = runBlocking {
        withBook { queue, remote, id ->
            remote.outcome = UnifiedBookUploadOutcome.Accepted("sb_000000000000000000000001", id, BookUploadState.QUEUED, 0, "")
            queue.syncOnce()
            assertEquals(BookUploadState.QUEUED, queue.statuses.value[id]?.state)
            queue.syncOnce()
            assertEquals(BookUploadState.FAILED, queue.statuses.value[id]?.state)
            queue.retryBook(id)
            assertEquals(2, remote.calls)
        }
    }
}
