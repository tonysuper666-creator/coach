package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

internal data class BookBookmark(
    val book: String,
    val page: Int,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 书签持久化（SQLite `book_bookmarks` 表）：独立于导入/目录代码，与 `ReadingSettings` 同库共用 `library.sqlite`。
 */
internal class BookBookmarkStore(
    directory: Path,
    private val busyTimeoutMs: Int = DEFAULT_BUSY_TIMEOUT_MS,
) {
    private val url = "jdbc:sqlite:${directory.resolve("library.sqlite")}"

    init {
        Files.createDirectories(directory)
        withBusyRetry { db ->
            db.createStatement().use { sql ->
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS book_bookmarks(
                        book TEXT NOT NULL,
                        page INTEGER NOT NULL,
                        note TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(book, page)
                    )""".trimIndent(),
                )
            }
        }
    }

    private fun database(): Connection = DriverManager.getConnection(url).apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=$busyTimeoutMs") }
    }

    private fun <T> withBusyRetry(attempts: Int = BUSY_RETRY_ATTEMPTS, block: (Connection) -> T): T {
        var remaining = attempts
        while (true) {
            try {
                return database().use(block)
            } catch (error: SQLException) {
                if ((error.errorCode and 0xff) != SQLITE_BUSY || --remaining <= 0) throw error
            }
        }
    }

    @Synchronized
    fun list(bookId: String): List<BookBookmark> = withBusyRetry { db ->
        db.prepareStatement(
            "SELECT book, page, note, created_at FROM book_bookmarks WHERE book=? ORDER BY page ASC",
        ).use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            BookBookmark(
                                book = rows.getString("book"),
                                page = rows.getInt("page"),
                                note = rows.getString("note").orEmpty(),
                                createdAt = rows.getLong("created_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    fun add(bookId: String, page: Int, note: String = "") {
        val now = System.currentTimeMillis()
        withBusyRetry { db ->
            db.prepareStatement(
                "INSERT OR REPLACE INTO book_bookmarks(book, page, note, created_at) VALUES(?,?,?,?)",
            ).use { sql ->
                sql.setString(1, bookId)
                sql.setInt(2, page)
                sql.setString(3, note)
                sql.setLong(4, now)
                sql.executeUpdate()
            }
        }
    }

    @Synchronized
    fun remove(bookId: String, page: Int) {
        withBusyRetry { db ->
            db.prepareStatement("DELETE FROM book_bookmarks WHERE book=? AND page=?").use { sql ->
                sql.setString(1, bookId)
                sql.setInt(2, page)
                sql.executeUpdate()
            }
        }
    }

    @Synchronized
    fun toggle(bookId: String, page: Int, note: String = ""): Boolean {
        val existing = list(bookId).any { it.page == page }
        if (existing) {
            remove(bookId, page)
            return false
        }
        add(bookId, page, note)
        return true
    }

    companion object {
        private const val DEFAULT_BUSY_TIMEOUT_MS = 5_000
        private const val BUSY_RETRY_ATTEMPTS = 2
        private const val SQLITE_BUSY = 5
    }
}
