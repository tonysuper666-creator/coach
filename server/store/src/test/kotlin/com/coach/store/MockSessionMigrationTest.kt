package com.coach.store

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class MockSessionMigrationTest {
    private fun answer(choice: Int) = buildJsonObject { put("Q1", buildJsonObject { put("choice", choice) }) }
    private fun backups(dir: Path) = Files.list(dir.resolve("backups")).use { it.map { p -> p.fileName.toString() }.toList().toSet() }
    private fun <T> withDb(file: Path, block: (java.sql.Connection) -> T): T = DriverManager.getConnection("jdbc:sqlite:$file").use(block)
    private fun columns(file: Path) = withDb(file) { db ->
        db.createStatement().use { s -> s.executeQuery("PRAGMA table_info(mock_sessions)").use { rs -> buildSet { while (rs.next()) add(rs.getString(2)) } } }
    }
    private fun storedAnswers(file: Path) = withDb(file) { db ->
        db.createStatement().use { s -> s.executeQuery("SELECT answers_json FROM mock_sessions WHERE mock_id='M'").use { rs -> assertTrue(rs.next()); Json.parseToJsonElement(rs.getString(1)) } }
    }

    /** An exam left half-done by an earlier test build: the save order column does not exist yet. */
    private fun legacyExam(dir: Path) {
        LedgerStore(dir).close()
        withDb(dir.resolve("coach.sqlite")) { db ->
            db.createStatement().use { s ->
                s.execute("DROP TABLE mock_sessions")
                s.execute("CREATE TABLE mock_sessions(mock_id TEXT PRIMARY KEY,course_id TEXT NOT NULL,kind TEXT NOT NULL,purpose TEXT NOT NULL,paper_json TEXT NOT NULL,started_at INTEGER NOT NULL,deadline INTEGER NOT NULL,answers_json TEXT NOT NULL DEFAULT '{}',submitted_at INTEGER,auto INTEGER NOT NULL DEFAULT 0)")
            }
            db.prepareStatement("INSERT INTO mock_sessions VALUES('M','C','paper','FULL_MOCK','{}',1000,7201000,?,NULL,0)").use { s ->
                s.setString(1, answer(0).toString()); s.executeUpdate()
            }
        }
    }

    @Test fun wideningAnOldExamTableFirstLeavesARestorableCopyAndHappensOnce() {
        val dir = Files.createTempDirectory("coach-mock-migration-")
        try {
            legacyExam(dir)
            val before = backups(dir)
            LedgerStore(dir).use { store ->
                val exam = assertNotNull(store.mock("M"))
                assertEquals(answer(0), exam.answers)
                assertEquals(7201000L, exam.deadline)
                assertNull(exam.submittedAt)
                assertTrue(store.saveMockAnswers("M", answer(1), 7))
            }
            val made = (backups(dir) - before).filter { it.startsWith("pre-mock-") }
            val copy = dir.resolve("backups/${assertNotNull(made.firstOrNull())}/coach.sqlite")
            // Restoring the copy gives back the table exactly as it was, with the learner's saved pick.
            assertFalse("answers_seq" in columns(copy))
            assertEquals(answer(0), storedAnswers(copy))

            val afterFirst = backups(dir)
            LedgerStore(dir).use { store ->
                assertEquals(answer(1), store.mock("M")!!.answers)
                assertFalse(store.saveMockAnswers("M", answer(0), 6))
            }
            assertEquals(afterFirst, backups(dir))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun anExamTableIsNotWidenedWhenItsBackupCannotBeWritten() {
        val dir = Files.createTempDirectory("coach-mock-migration-blocked-")
        try {
            legacyExam(dir)
            dir.resolve("backups").toFile().deleteRecursively()
            Files.writeString(dir.resolve("backups"), "not a directory")
            assertFails { LedgerStore(dir) }
            assertFalse("answers_seq" in columns(dir.resolve("coach.sqlite")))
            assertEquals(answer(0), storedAnswers(dir.resolve("coach.sqlite")))

            // Once backups can be written again, the next start migrates normally and keeps the answer.
            Files.delete(dir.resolve("backups"))
            LedgerStore(dir).use { store -> assertEquals(answer(0), store.mock("M")!!.answers) }
            assertTrue(backups(dir).any { it.startsWith("pre-mock-") })
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun aLedgerFromBeforeMockExamsIsBackedUpBeforeTheTableIsAdded() {
        val dir = Files.createTempDirectory("coach-mock-migration-new-table-")
        try {
            LedgerStore(dir).close()
            withDb(dir.resolve("coach.sqlite")) { db -> db.createStatement().use { it.execute("DROP TABLE mock_sessions") } }
            val before = backups(dir)
            LedgerStore(dir).use { store -> assertNull(store.mock("M")) }
            val made = (backups(dir) - before).filter { it.startsWith("pre-mock-") }
            assertTrue(columns(dir.resolve("backups/${assertNotNull(made.firstOrNull())}/coach.sqlite")).isEmpty())
        } finally { dir.toFile().deleteRecursively() }
    }
}
