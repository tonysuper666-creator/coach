package com.coach.desktop.books

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * 2.1「这本书讲什么」全书地图本机 SQLite 持久化（TON-1889）。
 *
 * - 独立表 `book_what_about_maps`（随用随建，不修改 `BookMigration.kt` 避免 2.0.4 冲突）；
 * - 生成一次存本机，再打开直接读库，零 API 额度；
 * - 读取时比对当前书的骨架指纹，若用户改了骨架（TON-1876），立即按新骨架重排并写回本机；
 * - 独立保存未完成的 `pending_job_key`，中断重开后复用同一批处理任务，不重复提交。
 */
internal object BookMapStore {
    private const val TABLE_DDL = """
        CREATE TABLE IF NOT EXISTS book_what_about_maps(
            book_id TEXT PRIMARY KEY,
            skeleton_hash TEXT NOT NULL,
            map_json TEXT NOT NULL,
            pending_job_key TEXT NOT NULL DEFAULT '',
            pending_revision INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL
        )
    """

    private fun openDb(libraryDir: Path): Connection =
        DriverManager.getConnection("jdbc:sqlite:${libraryDir.resolve("library.sqlite")}").apply {
            createStatement().use {
                it.execute("PRAGMA busy_timeout=5000")
                it.execute(TABLE_DDL.trimIndent())
            }
        }

    /**
     * 读取本机已存地图；若书的骨架已变化，自动重排并落盘，返回与当前骨架一致的地图。
     */
    fun load(library: TextbookLibrary, book: Textbook): BookMapDocument? = runCatching {
        val raw = openDb(library.directory).use { db ->
            db.prepareStatement("SELECT map_json FROM book_what_about_maps WHERE book_id = ?").use { ps ->
                ps.setString(1, book.id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1)?.takeIf { it.isNotBlank() } else null
                }
            }
        } ?: return@runCatching null

        val decoded = BookMapWire.decodeDocument(raw) ?: return@runCatching null
        val reconciled = BookMapReconciler.reconcile(decoded, book)
        if (reconciled !== decoded && reconciled != decoded) {
            save(library, reconciled)
        }
        reconciled
    }.getOrNull()

    fun save(library: TextbookLibrary, doc: BookMapDocument, expected: Textbook? = null) {
        runCatching {
            val encoded = BookMapWire.encodeDocument(doc)
            openDb(library.directory).use { db ->
                db.cacheForStructure(expected) {
                    db.prepareStatement(
                        """INSERT INTO book_what_about_maps(book_id, skeleton_hash, map_json, pending_job_key, pending_revision, updated_at)
                           VALUES(?, ?, ?, '', 0, ?)
                           ON CONFLICT(book_id) DO UPDATE SET
                               skeleton_hash = excluded.skeleton_hash,
                               map_json = excluded.map_json,
                               pending_job_key = '',
                               pending_revision = 0,
                               updated_at = excluded.updated_at""".trimIndent(),
                    ).use { ps ->
                        ps.setString(1, doc.bookId)
                        ps.setString(2, doc.skeletonHash)
                        ps.setString(3, encoded)
                        ps.setLong(4, System.currentTimeMillis())
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    fun savePendingJob(
        library: TextbookLibrary,
        bookId: String,
        skeletonHash: String,
        jobKey: String,
        revision: Int = 0,
    ) {
        if (jobKey.isBlank()) return
        runCatching {
            openDb(library.directory).use { db ->
                db.prepareStatement(
                    """INSERT INTO book_what_about_maps(book_id, skeleton_hash, map_json, pending_job_key, pending_revision, updated_at)
                       VALUES(?, ?, '', ?, ?, ?)
                       ON CONFLICT(book_id) DO UPDATE SET
                           pending_job_key = excluded.pending_job_key,
                           pending_revision = excluded.pending_revision,
                           updated_at = excluded.updated_at""".trimIndent(),
                ).use { ps ->
                    ps.setString(1, bookId)
                    ps.setString(2, skeletonHash)
                    ps.setString(3, jobKey)
                    ps.setInt(4, revision)
                    ps.setLong(5, System.currentTimeMillis())
                    ps.executeUpdate()
                }
            }
        }
    }

    fun loadPendingJob(library: TextbookLibrary, bookId: String): BookMapPendingJob? = runCatching {
        openDb(library.directory).use { db ->
            db.prepareStatement(
                """SELECT skeleton_hash, pending_job_key, pending_revision, updated_at
                   FROM book_what_about_maps WHERE book_id = ?""".trimIndent(),
            ).use { ps ->
                ps.setString(1, bookId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    val jobKey = rs.getString(2).orEmpty()
                    if (jobKey.isBlank()) return@use null
                    BookMapPendingJob(
                        bookId = bookId,
                        skeletonHash = rs.getString(1).orEmpty(),
                        jobKey = jobKey,
                        revision = rs.getInt(3),
                        updatedAt = rs.getLong(4),
                    )
                }
            }
        }
    }.getOrNull()

    fun clearPendingJob(library: TextbookLibrary, bookId: String) {
        runCatching {
            openDb(library.directory).use { db ->
                db.prepareStatement(
                    "UPDATE book_what_about_maps SET pending_job_key = '', pending_revision = 0 WHERE book_id = ?",
                ).use { ps ->
                    ps.setString(1, bookId)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun delete(library: TextbookLibrary, bookId: String) {
        runCatching {
            openDb(library.directory).use { db ->
                db.prepareStatement("DELETE FROM book_what_about_maps WHERE book_id = ?").use { ps ->
                    ps.setString(1, bookId)
                    ps.executeUpdate()
                }
            }
        }
    }
}
