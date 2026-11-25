package com.coach.server

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReimportRecoveryReviewTest {
    @Test
    fun pendingRemovalPreservesLaterImportInSameShelf() = runBlocking {
        val root = Files.createTempDirectory("coach-review-reimport-")
        val config = UnifiedBookConfig(enabled = true)
        val account = UnifiedBookScope.fromCall("synthetic-local-device", null)
        val bytes = "A synthetic chapter for recovery testing.".encodeToByteArray()
        suspend fun importCopy(pipeline: UnifiedBookPipeline) =
            pipeline.upload(account, "txt", "Synthetic book", null, bytes.size.toLong(), ByteReadChannel(bytes))
        val original = UnifiedBookPipeline(root, config)
        val old = importCopy(original)
        original.recordPendingDeleteForTest(account, old.shelfBookId, old.sha256)
        original.deleteOnlyShelfRowForTest(account, old.shelfBookId)
        val restarted = UnifiedBookPipeline(root, config)
        val current = importCopy(restarted)
        assertNotEquals(old.shelfBookId, current.shelfBookId)
        assertEquals(1, restarted.sharedRefCount(current.sha256))
        restarted.reconcilePendingDeletes()
        val verified = restarted.hasVerifiedShelfBook(account, current.shelfBookId)
        val inTrash = restarted.isTrashed(current.sha256)
        assertEquals(1, restarted.sharedRefCount(current.sha256), "Later import: verified=$verified, inTrash=$inTrash")
    }
}
