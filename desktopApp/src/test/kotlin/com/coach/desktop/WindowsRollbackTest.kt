package com.coach.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsRollbackTest {
    @Test fun rollbackThenUpgradeMergesNotesAndMistakesAddedAfterTheSnapshot() {
        val library = Files.createTempDirectory("coach-rollback-lib")
        val updates = Files.createTempDirectory("coach-rollback-upd")
        try {
            val db = library.resolve("library.sqlite")
            sqlite(db) { dbc ->
                dbc.createStatement().use { sql ->
                    sql.execute("CREATE TABLE book_notes(book TEXT NOT NULL, id TEXT NOT NULL, note TEXT NOT NULL, PRIMARY KEY(book, id))")
                    sql.execute("CREATE TABLE book_reviews(book TEXT NOT NULL, lesson TEXT NOT NULL, mode TEXT NOT NULL, prompt TEXT NOT NULL, wrong_text TEXT NOT NULL, PRIMARY KEY(book, lesson, mode))")
                    sql.execute("INSERT INTO book_notes(book, id, note) VALUES ('book', 'A', '升级前的笔记')")
                }
            }
            WindowsRollback.prepareLaunch(library, "2.0.0")
            sqlite(db) { dbc ->
                dbc.createStatement().use { sql ->
                    sql.execute("INSERT INTO book_notes(book, id, note) VALUES ('book', 'B', '升级后的笔记')")
                    sql.execute("INSERT INTO book_reviews(book, lesson, mode, prompt, wrong_text) VALUES ('book', 'L1', 'CHOICE', '题干', '选错了')")
                }
            }
            Files.write(updates.resolve("previous.msi"), byteArrayOf(1))
            var launched: Path? = null
            val status = WindowsRollback.rollback(library, updates) { previous, _, _ -> launched = previous; 0 }
            assertEquals("", status)
            assertEquals(updates.resolve("previous.msi"), launched)
            assertEquals(listOf("A" to "升级前的笔记"), notes(db))
            assertEquals(emptyList(), mistakes(db))
            WindowsRollback.mergeParked(library)
            assertEquals(listOf("A" to "升级前的笔记", "B" to "升级后的笔记"), notes(db))
            assertEquals(listOf("L1" to "选错了"), mistakes(db))
            assertFalse(Files.exists(library.resolve(".rollback/pending.sqlite")))
        } finally {
            library.toFile().deleteRecursively()
            updates.toFile().deleteRecursively()
        }
    }

    @Test fun failedRollbackAndMissingPackageLeaveTheCurrentLibrary() {
        val library = Files.createTempDirectory("coach-rollback-stay")
        val updates = Files.createTempDirectory("coach-rollback-stay-upd")
        try {
            val db = library.resolve("library.sqlite")
            sqlite(db) { dbc ->
                dbc.createStatement().use { sql ->
                    sql.execute("CREATE TABLE book_notes(book TEXT NOT NULL, id TEXT NOT NULL, note TEXT NOT NULL, PRIMARY KEY(book, id))")
                    sql.execute("INSERT INTO book_notes(book, id, note) VALUES ('book', 'A', '还在')")
                }
            }
            WindowsRollback.prepareLaunch(library, "2.0.0")
            sqlite(db) { dbc ->
                dbc.createStatement().execute("INSERT INTO book_notes(book, id, note) VALUES ('book', 'B', '不能丢')")
            }
            assertEquals(ROLLBACK_NO_PACKAGE, WindowsRollback.rollback(library, updates) { _, _, _ -> 0 })
            assertEquals(listOf("A" to "还在", "B" to "不能丢"), notes(db))
            Files.write(updates.resolve("previous.msi"), byteArrayOf(1))
            assertEquals(UPDATE_SPACE, WindowsRollback.rollback(library, updates, install = { _, _, _ -> 0 }, freeBytes = { 0L }))
            assertEquals(listOf("A" to "还在", "B" to "不能丢"), notes(db))
            assertEquals(ROLLBACK_FAILED, WindowsRollback.rollback(library, updates) { _, _, _ -> throw IllegalStateException("not started") })
            assertEquals(listOf("A" to "还在", "B" to "不能丢"), notes(db))
            assertFalse(Files.exists(library.resolve(".rollback/pending.sqlite")))
        } finally {
            library.toFile().deleteRecursively()
            updates.toFile().deleteRecursively()
        }
    }

    @Test fun failedInstallLeavesTheLibraryAndThePackagesUntouched() {
        val library = Files.createTempDirectory("coach-rollback-fail")
        val updates = Files.createTempDirectory("coach-rollback-fail-upd")
        try {
            val db = library.resolve("library.sqlite")
            sqlite(db) { dbc ->
                dbc.createStatement().use { sql ->
                    sql.execute("CREATE TABLE book_notes(book TEXT NOT NULL, id TEXT NOT NULL, note TEXT NOT NULL, PRIMARY KEY(book, id))")
                    sql.execute("INSERT INTO book_notes(book, id, note) VALUES ('book', 'A', '升级前')")
                }
            }
            WindowsRollback.prepareLaunch(library, "2.0.0")
            sqlite(db) { dbc ->
                dbc.createStatement().execute("INSERT INTO book_notes(book, id, note) VALUES ('book', 'B', '升级后')")
            }
            val previous = updates.resolve("previous.msi")
            val current = updates.resolve("current.msi")
            Files.write(previous, byteArrayOf(1, 2, 3))
            Files.write(current, byteArrayOf(4, 5, 6))
            var called = false
            val status = WindowsRollback.rollback(library, updates) { prev, curr, _ ->
                called = prev == previous && curr == current
                1603
            }
            assertEquals(ROLLBACK_FAILED, status)
            assertTrue(called)
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(previous))
            assertContentEquals(byteArrayOf(4, 5, 6), Files.readAllBytes(current))
            assertEquals(listOf("A" to "升级前", "B" to "升级后"), notes(db))
            assertFalse(Files.exists(library.resolve(".rollback/pending.sqlite")))
        } finally {
            library.toFile().deleteRecursively()
            updates.toFile().deleteRecursively()
        }
    }

    @Test fun highlightOnlyMarksAndBooksImportedAfterUpgradeSurviveTheRoundTrip() {
        val library = Files.createTempDirectory("coach-rollback-added")
        val updates = Files.createTempDirectory("coach-rollback-added-upd")
        try {
            val db = library.resolve("library.sqlite")
            sqlite(db) { dbc ->
                dbc.createStatement().use { sql ->
                    sql.execute("CREATE TABLE books(id TEXT PRIMARY KEY, title TEXT NOT NULL)")
                    sql.execute("CREATE TABLE pages(book TEXT NOT NULL, page INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(book, page))")
                    sql.execute("CREATE TABLE book_highlights(book TEXT NOT NULL, page INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, quote TEXT NOT NULL, PRIMARY KEY(book, page, start, end))")
                    sql.execute("INSERT INTO books(id, title) VALUES ('old', '原来的书')")
                    sql.execute("INSERT INTO pages(book, page, text) VALUES ('old', 1, '旧页')")
                }
            }
            WindowsRollback.prepareLaunch(library, "2.0.0")
            sqlite(db) { dbc ->
                dbc.createStatement().use { sql ->
                    sql.execute("INSERT INTO books(id, title) VALUES ('new', '升级后的书')")
                    sql.execute("INSERT INTO pages(book, page, text) VALUES ('new', 1, '新页')")
                    sql.execute("INSERT INTO book_highlights(book, page, start, end, quote) VALUES ('new', 2, 0, 4, '只标高亮')")
                    sql.execute("INSERT INTO book_highlights(book, page, start, end, quote) VALUES ('new', 3, 1, 5, '第二处')")
                }
            }
            Files.write(updates.resolve("previous.msi"), byteArrayOf(1))
            Files.writeString(updates.resolve("current.json"), "2.0.0\n")
            assertEquals("", WindowsRollback.rollback(library, updates) { _, _, _ -> 0 })
            assertEquals(listOf("old"), bookIds(db))
            assertEquals(emptyList(), highlights(db))
            WindowsRollback.mergeParked(library, "1.0.0")
            assertEquals(listOf("old"), bookIds(db))
            assertEquals(emptyList(), highlights(db))
            assertTrue(Files.isRegularFile(library.resolve(".rollback/pending-v2.sqlite")))
            WindowsRollback.prepareLaunch(library, "2.0.0")
            WindowsRollback.mergeParked(library, "2.0.0")
            assertEquals(listOf("new", "old"), bookIds(db))
            assertEquals(listOf("new" to "新页"), pageTexts(db).filter { it.first == "new" })
            assertEquals(listOf("只标高亮", "第二处"), highlights(db))
            assertFalse(Files.exists(library.resolve(".rollback/pending-v2.sqlite")))
        } finally {
            library.toFile().deleteRecursively()
            updates.toFile().deleteRecursively()
        }
    }

    @Test fun laterEditWinsAndTheOtherCopyIsKept() {
        val library = Files.createTempDirectory("coach-rollback-conflict")
        val updates = Files.createTempDirectory("coach-rollback-conflict-upd")
        try {
            val db = library.resolve("library.sqlite")
            sqlite(db) { dbc ->
                dbc.createStatement().use { sql ->
                    sql.execute("CREATE TABLE book_notes(book TEXT NOT NULL, id TEXT NOT NULL, note TEXT NOT NULL, PRIMARY KEY(book, id))")
                    sql.execute("INSERT INTO book_notes(book, id, note) VALUES ('book', 'A', '旧笔记')")
                }
            }
            WindowsRollback.prepareLaunch(library, "2.0.0")
            sqlite(db) { dbc ->
                dbc.createStatement().execute("UPDATE book_notes SET note = '升级后改的' WHERE id = 'A'")
            }
            Files.write(updates.resolve("previous.msi"), byteArrayOf(1))
            Files.writeString(updates.resolve("current.json"), "2.0.0\n")
            assertEquals("", WindowsRollback.rollback(library, updates) { _, _, _ -> 0 })
            assertEquals(listOf("A" to "旧笔记"), notes(db))
            sqlite(db) { dbc ->
                dbc.createStatement().execute("UPDATE book_notes SET note = '回退后改的' WHERE id = 'A'")
            }
            WindowsRollback.prepareLaunch(library, "2.0.0")
            WindowsRollback.mergeParked(library, "2.0.0")
            assertEquals(listOf("A" to "回退后改的"), notes(db))
            assertTrue(copyPayloads(db).any { it.contains("升级后改的") })
        } finally {
            library.toFile().deleteRecursively()
            updates.toFile().deleteRecursively()
        }
    }

    @Test fun anEditMadeOnlyOnTheNewVersionComesBackWhenTheOldVersionDidNotTouchIt() {
        val library = Files.createTempDirectory("coach-rollback-one-side")
        val updates = Files.createTempDirectory("coach-rollback-one-side-upd")
        try {
            val db = library.resolve("library.sqlite")
            sqlite(db) { dbc ->
                dbc.createStatement().use { sql ->
                    sql.execute("CREATE TABLE book_notes(book TEXT NOT NULL, id TEXT NOT NULL, note TEXT NOT NULL, PRIMARY KEY(book, id))")
                    sql.execute("INSERT INTO book_notes(book, id, note) VALUES ('book', 'A', '旧笔记')")
                }
            }
            WindowsRollback.prepareLaunch(library, "2.0.0")
            sqlite(db) { dbc ->
                dbc.createStatement().execute("UPDATE book_notes SET note = '只在新版改' WHERE id = 'A'")
            }
            Files.write(updates.resolve("previous.msi"), byteArrayOf(1))
            assertEquals("", WindowsRollback.rollback(library, updates) { _, _, _ -> 0 })
            WindowsRollback.mergeParked(library)
            assertEquals(listOf("A" to "只在新版改"), notes(db))
            assertEquals(emptyList(), copyPayloads(db))
        } finally {
            library.toFile().deleteRecursively()
            updates.toFile().deleteRecursively()
        }
    }

    @Test fun missingPreviousPackageIsVerifiedBeforeInstallAndAFailedDownloadStaysPut() {
        val library = Files.createTempDirectory("coach-rollback-fetch")
        val updates = Files.createTempDirectory("coach-rollback-fetch-upd")
        try {
            val db = library.resolve("library.sqlite")
            sqlite(db) { dbc ->
                dbc.createStatement().use { sql ->
                    sql.execute("CREATE TABLE book_notes(book TEXT NOT NULL, id TEXT NOT NULL, note TEXT NOT NULL, PRIMARY KEY(book, id))")
                    sql.execute("INSERT INTO book_notes(book, id, note) VALUES ('book', 'A', '还在')")
                }
            }
            WindowsRollback.prepareLaunch(library, "2.0.0")
            var asked = false
            var installed = false
            val failed = WindowsRollback.rollback(library, updates, acquirePrevious = {
                asked = true
                throw UpdateFailed(UpdateFailure.CHECKSUM, UPDATE_CHECKSUM)
            }) { _, _, _ ->
                installed = true
                0
            }
            assertEquals(UPDATE_CHECKSUM, failed)
            assertTrue(asked)
            assertFalse(installed)
            assertEquals(listOf("A" to "还在"), notes(db))
            assertFalse(Files.exists(updates.resolve("previous.msi")))

            asked = false
            val staged = updates.resolve("downloaded.msi")
            val status = WindowsRollback.rollback(library, updates, acquirePrevious = {
                asked = true
                Files.write(staged, byteArrayOf(9))
                staged to "1.9.0"
            }) { previous, _, _ ->
                installed = previous == updates.resolve("previous.msi")
                0
            }
            assertEquals("", status)
            assertTrue(asked)
            assertTrue(installed)
            assertContentEquals(byteArrayOf(9), Files.readAllBytes(updates.resolve("previous.msi")))
            assertEquals("1.9.0", Files.readString(updates.resolve("previous.json")).trim())
            assertEquals(listOf("A" to "还在"), notes(db))
        } finally {
            library.toFile().deleteRecursively()
            updates.toFile().deleteRecursively()
        }
    }

    @Test fun retainInstallersKeepsOnlyTheImmediatelyPreviousPackage() {
        val updates = Files.createTempDirectory("coach-rollback-keep")
        try {
            val v1 = updates.resolve("v1.msi")
            val v2 = updates.resolve("v2.msi")
            val v3 = updates.resolve("v3.msi")
            Files.write(v1, byteArrayOf(1))
            Files.write(v2, byteArrayOf(2))
            Files.write(v3, byteArrayOf(3))
            WindowsRollback.acceptPrevious(updates, v1, "1.0.0")
            WindowsRollback.retainInstallers(updates, v2, "2.0.0", "1.0.0")
            assertContentEquals(byteArrayOf(1), Files.readAllBytes(updates.resolve("previous.msi")))
            assertContentEquals(byteArrayOf(2), Files.readAllBytes(updates.resolve("current.msi")))
            WindowsRollback.retainInstallers(updates, v3, "3.0.0", "2.0.0")
            assertContentEquals(byteArrayOf(2), Files.readAllBytes(updates.resolve("previous.msi")))
            assertContentEquals(byteArrayOf(3), Files.readAllBytes(updates.resolve("current.msi")))
            assertEquals("2.0.0", Files.readString(updates.resolve("previous.json")).trim())
            assertTrue(Files.isRegularFile(updates.resolve("current.msi")))
        } finally {
            updates.toFile().deleteRecursively()
        }
    }

    private fun sqlite(path: Path, block: (java.sql.Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
            block(db)
        }
    }

    private fun notes(path: Path): List<Pair<String, String>> = query(path, "SELECT id, note FROM book_notes ORDER BY id")
    private fun mistakes(path: Path): List<Pair<String, String>> =
        query(path, "SELECT lesson, wrong_text FROM book_reviews ORDER BY lesson")
    private fun bookIds(path: Path): List<String> = query(path, "SELECT id, title FROM books ORDER BY id").map { it.first }
    private fun pageTexts(path: Path): List<Pair<String, String>> = query(path, "SELECT book, text FROM pages ORDER BY book, page")
    private fun highlights(path: Path): List<String> = query(path, "SELECT quote, quote FROM book_highlights ORDER BY page").map { it.first }
    private fun copyPayloads(path: Path): List<String> {
        if (!tableExists(path, "rollback_copies")) return emptyList()
        return query(path, "SELECT payload, payload FROM rollback_copies").map { it.first }
    }

    private fun tableExists(path: Path, name: String): Boolean {
        var found = false
        sqlite(path) { db ->
            db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { sql ->
                sql.setString(1, name)
                sql.executeQuery().use { found = it.next() }
            }
        }
        return found
    }

    private fun query(path: Path, sql: String): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        sqlite(path) { db ->
            db.createStatement().use { statement ->
                statement.executeQuery(sql).use { cursor ->
                    while (cursor.next()) rows += cursor.getString(1) to cursor.getString(2)
                }
            }
        }
        return rows
    }
}
