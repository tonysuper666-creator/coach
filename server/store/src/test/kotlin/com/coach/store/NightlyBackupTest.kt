package com.coach.store

import com.coach.domain.json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.*

/** Real SQLite/filesystem drills; only the scheduler's clock is advanced. */
class NightlyBackupTest {
    private fun at(day: LocalDate, hour: Int, minute: Int): Long =
        day.atTime(LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun integrity(path: Path) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("ok", rows.getString(1))
                }
            }
        }
    }

    @Test fun scheduledBackupRestoresEventsMessagesAndHardLinkedBlobs() {
        val root = Files.createTempDirectory("coach-nightly-drill-")
        val data = root.resolve("live")
        val day = LocalDate.now()
        var clock = at(day, 3, 29)
        var store = LedgerStore(data) { clock }
        try {
            // Establish yesterday's successful backup, less than 24 hours ago.
            clock = at(day.minusDays(1), 4, 0)
            store.backup(root.resolve("baseline"))
            clock = at(day, 3, 29)
            store.append("synthetic-course", "note_saved", json("noteId" to "before-nightly"))
            val blob = store.putBlob("synthetic immutable attachment".toByteArray(), "text/plain")
            store.startMessage("synthetic-thread", "synthetic-message", "synthetic partial reply")
            val before = store.hello()
            val events = store.events("synthetic-course")
            assertNull(store.backupIfDue(), "must not run before 03:30 when less than 24h elapsed")

            clock = at(day, 3, 30)
            val backup = assertNotNull(store.backupIfDue())
            assertEquals(before.lastSeq.toString(), Files.readString(backup.resolve("complete")))
            assertEquals(before.lastSeq, store.hello().backedUpThroughSeq)
            integrity(backup.resolve("coach.sqlite"))
            assertTrue(Files.isSameFile(data.resolve("blobs/$blob"), backup.resolve("blobs/$blob")))
            val export = Files.readAllLines(backup.resolve("events.jsonl"))
            assertTrue(export.any { it.contains("before-nightly") })
            assertTrue(export.any { it.contains("synthetic partial reply") && it.contains("\"recordType\":\"message\"") })
            assertNull(store.backupIfDue(), "maintenance polling must not repeat today's backup")

            store.append("synthetic-course", "note_saved", json("noteId" to "after-nightly"))
            store.close()
            store = LedgerStore.restore(data, backup)
            integrity(data.resolve("coach.sqlite"))
            assertNotEquals(before.epoch, store.hello().epoch)
            assertEquals(before.lastSeq, store.hello().lastSeqBefore)
            assertEquals(events, store.events("synthetic-course"))
            assertEquals("truncated" to "server_restored", store.messageStatus("synthetic-message"))
            assertEquals("synthetic immutable attachment", Files.readString(data.resolve("blobs/$blob")))
            val safety = Files.list(data).use { paths ->
                paths.filter { it.fileName.toString().startsWith("before-restore-") }.toList().single()
            }
            integrity(safety.resolve("coach.sqlite"))
            println("NIGHTLY scheduled=03:30 duplicateBackup=false integrity=ok blobHardLink=true eventsRestored=true messageRestored=true epochChanged=true safetyCopy=true")
        } finally {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun restartOverTwentyFourHoursLaterCatchesUpBeforeScheduledTime() {
        val root = Files.createTempDirectory("coach-backup-catchup-")
        val day = LocalDate.now()
        var clock = at(day, 1, 0)
        try {
            LedgerStore(root) { clock }.use { store -> store.backup(root.resolve("initial")) }
            clock = at(day.plusDays(1), 1, 1)
            LedgerStore(root) { clock }.use { store ->
                val backup = assertNotNull(store.backupIfDue())
                integrity(backup.resolve("coach.sqlite"))
                assertNull(store.backupIfDue())
            }
            // Maintenance resumes the following day's scheduled backup after catch-up.
            clock = at(day.plusDays(2), 3, 30)
            LedgerStore(root) { clock }.use { store -> assertNotNull(store.backupIfDue()) }
            println("NIGHTLY restarted=true overdue24h=true before03:30Catchup=true repeatSuppressed=true")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun failedExportCannotAdvanceBackupWatermarkAndIncompleteRestoreIsRejected() {
        val root = Files.createTempDirectory("coach-backup-failure-")
        val data = root.resolve("live")
        try {
            LedgerStore(data).use { store ->
                store.append("synthetic-course", "note_saved", json("noteId" to "unprotected"))
                val before = store.hello().backedUpThroughSeq
                // A directory cannot be hard-linked: deterministic filesystem failure.
                Files.createDirectory(data.resolve("blobs/unreadable-entry"))
                val failed = root.resolve("failed")
                assertFails { store.backup(failed) }
                assertEquals(before, store.hello().backedUpThroughSeq)
                assertFalse(Files.exists(failed))
                assertFailsWith<IllegalArgumentException> { LedgerStore.restore(data, failed) }
                assertEquals(1, store.events("synthetic-course").size)
                Files.delete(data.resolve("blobs/unreadable-entry"))
                assertNotNull(store.backupIfDue(), "failed backup must remain due")
                println("NIGHTLY failedBackupWatermarkUnchanged=true incompleteRestoreRejected=true retrySucceeded=true")
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
