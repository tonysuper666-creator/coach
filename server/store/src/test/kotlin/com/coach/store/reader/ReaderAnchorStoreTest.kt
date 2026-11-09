package com.coach.store.reader

import com.coach.domain.reader.*
import java.nio.file.Files
import kotlin.test.*

class ReaderAnchorStoreTest {
    @Test fun restartMigrationAndTwoDeviceConflictPreserveCapturedNoteAndProgress() {
        val dir = Files.createTempDirectory("synthetic-reader-anchor-")
        val oldVersion = ReaderAnchorStore.version("synthetic", "old synthetic PDF bytes".toByteArray(), ReaderFormat.PDF)
        val newVersion = ReaderAnchorStore.version("synthetic", "new synthetic PDF bytes".toByteArray(), ReaderFormat.PDF)
        val anchor = ReaderAnchor("a", oldVersion, ReaderResource(pdfPageIndex0 = 0), TextQuoteSelector("A synthetic sentence."),
            ReaderTextRange(0, 21))
        val original = ReaderAnchorEntry(anchor, "A private synthetic note", 0.4)
        fun index(version: DocumentVersion, text: String?) = ReaderDocumentIndex(version,
            listOf(ReaderTextUnit(ReaderResource(pdfPageIndex0 = 0), text)), true, 1,
            VerifiedPageLabels(version, 1, listOf(VerifiedPageLabel(0, "iv")), "checked-synthetic"))
        ReaderAnchorStore(dir).use { store ->
            store.register(index(oldVersion, "A synthetic sentence."))
            store.register(index(newVersion, "A synthetic sentence."))
            store.save(original)
            store.resolve("a", newVersion).also { assertEquals(AnchorStatus.RESOLVED, it.status) }
        }
        ReaderAnchorStore(dir).use { deviceA -> ReaderAnchorStore(dir).use { deviceB ->
            val old = assertNotNull(deviceA.get("a"))
            assertEquals(original.copy(revision = 1), old)
            assertEquals(AnchorStatus.RESOLVED, deviceA.resolution("a", newVersion)?.status)
            assertEquals("iv", deviceA.document(newVersion)?.pageLabels?.forPdfPage1(1)?.pages?.single()?.label)
            val next = deviceA.save(old.copy(note = "Device A updated note"), old.revision)
            assertFailsWith<ReaderAnchorConflict> { deviceB.save(old.copy(progress = 0.9), old.revision) }
            assertFailsWith<ReaderAnchorConflict> { deviceB.save(original) }
            assertFailsWith<ReaderAnchorConflict> { deviceB.save(next.copy(anchor = anchor.copy(version = newVersion)), next.revision) }
            val removed = newVersion.copy(sha256 = "d".repeat(64))
            deviceB.register(index(removed, "Sentence removed."))
            assertEquals(AnchorStatus.STALE, deviceB.resolve("a", removed).status)
            assertEquals(next, deviceB.get("a"))
            assertEquals(0.4, deviceB.get("a")?.progress)
            assertEquals(oldVersion, deviceB.get("a")?.anchor?.version)
            assertFailsWith<ReaderVersionConflict> { deviceA.register(index(newVersion, "different text under the same hash")) }
            assertEquals("A synthetic sentence.", deviceA.document(newVersion)?.units?.single()?.text)
            deviceA.register(index(newVersion, "A synthetic sentence.")) // idempotent import
        } }
        ReaderAnchorStore(dir).use { store ->
            assertEquals("Device A updated note", store.get("a")?.note)
            assertNull(store.resolution("a", newVersion.copy(sha256 = "d".repeat(64)))?.target)
            assertFalse(Files.exists(dir.resolve("coach.sqlite")))
        }
    }

    @Test fun newEntriesRequireRegisteredIndexAndVerifiedCapture() {
        val dir = Files.createTempDirectory("synthetic-reader-capture-")
        val version = DocumentVersion("synthetic", "a".repeat(64), ReaderFormat.PDF)
        val page = ReaderResource(pdfPageIndex0 = 0)
        val index = ReaderDocumentIndex(version, listOf(ReaderTextUnit(page, "Defiﬁned. Water boils. End."),
            ReaderTextUnit(ReaderResource(pdfPageIndex0 = 1), "Defined. Water boils. Other.")), true, 2)
        val anchor = ReaderAnchorResolver.capture(index, page, ReaderTextRange(10, 22), "capture")
        ReaderAnchorStore(dir).use { store ->
            assertFailsWith<IllegalArgumentException> { store.save(ReaderAnchorEntry(anchor)) }
            assertNull(store.get(anchor.id))
            store.register(index)
            val bad = listOf(anchor.copy(quote = TextQuoteSelector("Water boils.", "Defined. ")),
                anchor.copy(originalRange = null), anchor.copy(originalRange = ReaderTextRange(0, 12)),
                anchor.copy(originalRange = ReaderTextRange(100, 112)))
            for (unverified in bad) {
                assertFailsWith<IllegalArgumentException> { store.save(ReaderAnchorEntry(unverified)) }
                assertNull(store.get(anchor.id))
            }
            assertEquals(anchor, store.save(ReaderAnchorEntry(anchor, "synthetic note", 0.2)).anchor)
        }
        ReaderAnchorStore(dir).use { store ->
            assertEquals(AnchorReason.SAME_VERSION_RANGE, store.resolve(anchor.id, version).reason)
            assertEquals("synthetic note", store.get(anchor.id)?.note)
        }
    }
}
