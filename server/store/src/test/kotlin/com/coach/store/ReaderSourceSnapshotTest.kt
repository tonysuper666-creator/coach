package com.coach.store

import java.nio.file.Files
import java.sql.DriverManager
import com.coach.domain.scope.VisibleScope
import kotlin.test.*

class ReaderSourceSnapshotTest {
    @Test fun delayedAnswerChecksRejectRevocationReplacementAndOutOfScopeSnapshots() {
        val dir = Files.createTempDirectory("synthetic-source-version-")
        try { LedgerStore(dir).use { store ->
            store.registerSourcePage("c", "book", "Synthetic book", 2, "Synthetic pears grow here.")
            fun update(sql: String) = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
                db.createStatement().use { it.executeUpdate(sql) }
            }
            val hash = "a".repeat(64)
            update("UPDATE source_docs SET blob_sha256='$hash' WHERE doc_id='book'")
            val snapshot = SourcePage("book", 2, "Synthetic pears grow here.", sourceSha256 = hash)
            val scope = VisibleScope.learner().narrowToPages("book", 1..2)
            assertEquals(hash, snapshot.sourceSha256)
            assertTrue(store.readerSourceCurrent("c", snapshot, scope))
            assertFalse(store.readerSourceCurrent("other-course", snapshot, scope))
            assertFalse(store.readerSourceCurrent("c", snapshot, VisibleScope.NONE))
            assertFalse(store.readerSourceCurrent("c", snapshot, VisibleScope.learner().narrowToDocs("other")))
            assertFalse(store.readerSourceCurrent("c", snapshot, VisibleScope.learner().narrowToPages("book", 3..5)))
            assertFalse(store.readerSourceCurrent("c", snapshot.copy(learnerVisible = false), VisibleScope.tutorInternal()))
            update("UPDATE source_docs SET learner_visible=0 WHERE doc_id='book'")
            assertFalse(store.readerSourceCurrent("c", snapshot, scope))
            update("UPDATE source_docs SET learner_visible=1,tutor_visible=0 WHERE doc_id='book'")
            assertFalse(store.readerSourceCurrent("c", snapshot, scope))
            update("UPDATE source_docs SET tutor_visible=1,blob_sha256='${"b".repeat(64)}' WHERE doc_id='book'")
            assertFalse(store.readerSourceCurrent("c", snapshot, scope))
            update("UPDATE source_docs SET blob_sha256='$hash' WHERE doc_id='book'")
            update("UPDATE source_pages SET text='Synthetic replacement' WHERE doc_id='book'")
            assertFalse(store.readerSourceCurrent("c", snapshot, scope))
        } } finally { dir.toFile().deleteRecursively() }
    }
}
