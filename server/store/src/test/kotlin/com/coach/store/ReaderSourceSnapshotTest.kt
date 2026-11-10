package com.coach.store

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class ReaderSourceSnapshotTest {
    @Test fun importFileVersionSurvivesSearchAndDelayedAnswerChecksRejectRevocationAndReplacement() {
        val dir = Files.createTempDirectory("synthetic-source-version-")
        try { LedgerStore(dir).use { store ->
            store.registerSourcePage("c", "book", "Synthetic book", 2, "Synthetic pears grow here.")
            fun update(sql: String) = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
                db.createStatement().use { it.executeUpdate(sql) }
            }
            val hash = "a".repeat(64)
            update("UPDATE source_docs SET blob_sha256='$hash' WHERE doc_id='book'")
            val snapshot = store.searchSourcePages("c", "pears").single()
            assertEquals(hash, snapshot.sourceSha256)
            assertTrue(store.readerSourceCurrent("c", snapshot))
            assertFalse(store.readerSourceCurrent("other-course", snapshot))
            update("UPDATE source_docs SET learner_visible=0 WHERE doc_id='book'")
            assertFalse(store.readerSourceCurrent("c", snapshot))
            update("UPDATE source_docs SET learner_visible=1,tutor_visible=0 WHERE doc_id='book'")
            assertFalse(store.readerSourceCurrent("c", snapshot))
            update("UPDATE source_docs SET tutor_visible=1,blob_sha256='${"b".repeat(64)}' WHERE doc_id='book'")
            assertFalse(store.readerSourceCurrent("c", snapshot))
            update("UPDATE source_docs SET blob_sha256='$hash' WHERE doc_id='book'")
            update("UPDATE source_pages SET text='Synthetic replacement' WHERE doc_id='book'")
            assertFalse(store.readerSourceCurrent("c", snapshot))
        } } finally { dir.toFile().deleteRecursively() }
    }
}
