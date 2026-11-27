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
