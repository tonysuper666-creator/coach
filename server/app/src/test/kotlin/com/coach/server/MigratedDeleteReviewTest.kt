package com.coach.server

import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigratedDeleteReviewTest {
    @Test fun deletingMigratedShelfLeavesValidMappings() = runBlocking {
        val root = Files.createTempDirectory("review-mapped-")
        val scope = UnifiedBookScope.fromCall("synthetic-device", null)
        val pipeline = UnifiedBookPipeline(root.resolve("books"), UnifiedBookConfig(enabled = true))
        val verifier = UnifiedBookMigration(root, null, pipeline) { emptyList() }
        val data = "A synthetic mapped book.".encodeToByteArray()
        val book = pipeline.upload(scope, "txt", "Synthetic", null, data.size.toLong(), ByteReadChannel(data))
        pipeline.upsertLegacyBookMapping(scope, "record", "catalog", "doc", "course", null, 1, null, null, book.sha256, book.shelfBookId)
        val block = UnifiedBookPipeline.LegacyReceiptAlignmentRow(1, 0, 1, book.sha256, 1, false)
        pipeline.upsertLegacyReceiptAlignments(scope, "receipt", "receipt", book.shelfBookId, book.sha256, listOf(block))
        assertTrue(verifier.verify().ok)
        assertTrue(pipeline.deleteShelfBook(scope, book.shelfBookId))
        val after = verifier.verify()
        assertEquals(0, after.accounts.single().orphanAccountRecords, "verify.ok=" + after.ok)
    }
}
