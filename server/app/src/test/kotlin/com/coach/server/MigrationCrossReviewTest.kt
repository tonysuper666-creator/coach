package com.coach.server

import com.coach.domain.json
import com.coach.store.PairedDevice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationCrossReviewTest {
    private class Fixture : AutoCloseable {
        val root: Path = Files.createTempDirectory("coach-migration-review-")
        val reader: Path = Files.createDirectory(root.resolve("reader"))
        val unifiedDir: Path = root.resolve("unified-books")
        val pipeline = UnifiedBookPipeline(unifiedDir, UnifiedBookConfig(enabled = true))
        val service = UnifiedBookMigration(root, reader, pipeline) { listOf(PairedDevice("desktop", "Fixture", 1, null)) }
        val scope = UnifiedBookScope.fromCall("desktop", null)
        val original: Path = Files.writeString(reader.resolve("example.txt"), "Synthetic migration fixture")
        val sha: String = UnifiedBookPipeline.sha256File(original)
        fun saveCatalog(offset: Int) {
            val entry = json(
                "id" to "example",
                "course" to "course",
                "title" to "Synthetic title",
                "file" to "example.txt",
                "sha256" to sha,
                "pages" to 12,
                "kind" to "textbook",
                "printedPageOffset" to offset,
            )
            Files.writeString(reader.resolve("catalog.json"), json("schema" to 1, "documents" to JsonArray(listOf(entry))).toString())
        }

        override fun close() {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unchangedLegacyInputsRemainUsableByOldReader() = runBlocking {
        Fixture().use { f ->
            f.saveCatalog(1)
            val catalog = f.reader.resolve("catalog.json")
            val receiptFile = Files.createDirectory(f.root.resolve("personal-imports")).resolve("sources.sqlite")
            DriverManager.getConnection("jdbc:sqlite:$receiptFile").use { db ->
                db.createStatement().use { it.execute("CREATE TABLE blocks(device TEXT,book TEXT,page INTEGER,start INTEGER,end INTEGER,text TEXT,snapshot TEXT)") }
                db.prepareStatement("INSERT INTO blocks VALUES('desktop',?,1,0,1,'a',?)").use {
                    it.setString(1, f.sha)
                    it.setString(2, json("sha256" to f.sha, "length" to 20, "ocr" to false).toString())
                    it.executeUpdate()
                }
            }
            val oldFiles = listOf(catalog, f.original, receiptFile)
            val before = oldFiles.map { UnifiedBookPipeline.sha256File(it) }
            f.service.inventory()
            assertFalse(Files.exists(f.unifiedDir))
            assertEquals(1, f.service.migrate().migratedBooks)
            assertEquals(0, f.service.migrate().migratedBooks)
            assertEquals(before, oldFiles.map { UnifiedBookPipeline.sha256File(it) })
            assertEquals(1, ReaderLibrary(f.reader).list("course").single().offset)
        }
    }

    @Test
    fun remigrationPreservesPageOffsetCorrectedInLegacyCatalog() = runBlocking {
        Fixture().use { f ->
            f.saveCatalog(1)
            f.service.migrate()
            f.saveCatalog(3)
            assertEquals(3, ReaderLibrary(f.reader).list("course").single().offset)
            val inv = f.service.inventory()
            assertEquals(1, inv.counts.catalogReady)
            assertEquals(0, inv.counts.catalogAlreadyMigrated)
            val second = f.service.migrate()
            val actual = UnifiedBookPipeline.openReadOnly(f.pipeline.accountShelfFile(f.scope)).use { db ->
                db.createStatement().use { st ->
                    st.executeQuery("SELECT printed_page_offset FROM legacy_book_mappings").use {
                        assertTrue(it.next())
                        it.getInt(1)
                    }
                }
            }
            assertEquals(3, actual, "Re-run returned skipped=${second.deduplicatedSkipped}, ok=${second.verification.ok}")
            // Re-running again with no further change deduplicates cleanly.
            val third = f.service.migrate()
            assertEquals(0, third.migratedBooks)
            assertEquals(1, third.deduplicatedSkipped)
        }
    }

    @Test
    fun verifyFlagsIncompleteRestoreWithNonemptyShelfAndMissingRegistry() = runBlocking {
        Fixture().use { f ->
            f.saveCatalog(1)
            f.service.migrate()
            val partial = UnifiedBookPipeline(f.root.resolve("restore/unified-books"), UnifiedBookConfig(enabled = true))
            val shelf = partial.accountShelfFile(f.scope)
            Files.createDirectories(shelf.parent)
            Files.copy(f.pipeline.accountShelfFile(f.scope), shelf)
            val restored = UnifiedBookMigration(f.root.resolve("restore"), f.reader, partial) { emptyList() }
            val result = restored.verify()
            assertFalse(result.ok, "Restored one populated account shelf; accounts reported=${result.accounts.size}")
            assertTrue(result.missingRegistry)
            assertEquals(1, result.accounts.size)
            assertEquals(1, result.accounts.single().shelfBooks)
            assertFalse(Files.exists(partial.registryFilePath), "verify() must stay strictly read-only")

            // Fresh empty directory still reports ok=true and missingRegistry=false.
            val emptyPipeline = UnifiedBookPipeline(f.root.resolve("fresh/unified-books"), UnifiedBookConfig(enabled = true))
            val emptyService = UnifiedBookMigration(f.root.resolve("fresh"), f.reader, emptyPipeline) { emptyList() }
            val emptyResult = emptyService.verify()
            assertTrue(emptyResult.ok)
            assertFalse(emptyResult.missingRegistry)
            assertEquals(0, emptyResult.accounts.size)
        }
    }

    @Test
    fun verificationReportOmitsBackupDirectoryNames() = runBlocking {
        Fixture().use { f ->
            f.saveCatalog(1)
            f.service.migrate()
            val backupLabel = "synthetic-profile-backup"
            val existingShelf = f.pipeline.accountShelfFile(f.scope)
            val backup = Files.createDirectory(existingShelf.parent.parent.resolve(backupLabel))
            Files.copy(existingShelf, backup.resolve("shelf.sqlite"))
            val report = f.service.verify()
            assertFalse(
                report.toJson().toString().contains(backupLabel),
                "The verification report contained the synthetic local folder name",
            )
            assertEquals(1, report.unexpectedAccountEntries)
            assertFalse(report.ok)
            // Embedded verification report inside migrate() must also omit backup directory names.
            val migrated = f.service.migrate()
            assertFalse(migrated.toJson().toString().contains(backupLabel))
            assertEquals(1, migrated.verification.unexpectedAccountEntries)
        }
    }
}
