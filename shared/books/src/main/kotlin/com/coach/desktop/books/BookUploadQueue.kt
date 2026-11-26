package com.coach.desktop.books

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

internal enum class BookUploadState {
    WAITING_NETWORK,
    WAITING_SERVER,
    UPLOADING,
    RECEIVED,
    QUEUED,
    PROCESSING,
    READY,
    FAILED,
    PAUSED,
    PENDING_DELETE,
}

/**
 * Separately records local book ID, server account shelf ID, and original file SHA-256
 * so re-importing the same file preserves all local bookmarks, annotations, and reading progress.
 */
internal data class BookUploadEntry(
    val accountKey: String,
    val localBookId: String,
    val fileSha256: String,
    val serverShelfBookId: String? = null,
    val format: String,
    val title: String,
    val state: BookUploadState,
    val progressPct: Int = 0,
    val stageLabel: String = "",
    val errorMessage: String? = null,
    val paused: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** Single-line factual status shown on the home book card; never fabricates progress. */
    fun cardStatusLine(): String? {
        if (paused) return null
        return when (state) {
            BookUploadState.WAITING_NETWORK -> "等待联网"
            BookUploadState.WAITING_SERVER -> "等待服务支持"
            BookUploadState.UPLOADING -> if (progressPct in 1..99) "正在上传 · $progressPct%" else "正在上传"
            BookUploadState.RECEIVED -> "已收到，等待整理"
            BookUploadState.QUEUED -> "已排队，等待整理"
            BookUploadState.PROCESSING -> if (progressPct in 1..99) "正在整理 · $progressPct%" else "正在整理"
            BookUploadState.READY -> null
            BookUploadState.FAILED -> errorMessage?.takeIf { it.isNotBlank() } ?: "上传失败"
            BookUploadState.PAUSED -> null
            BookUploadState.PENDING_DELETE -> null
        }
    }

    val canRetry: Boolean get() = !paused && state == BookUploadState.FAILED
}

internal sealed interface UnifiedBookCapability {
    data class Supported(val accountKey: String) : UnifiedBookCapability
    data class Unsupported(val accountKey: String) : UnifiedBookCapability
    data object Unauthorized : UnifiedBookCapability
    data object Offline : UnifiedBookCapability
}

internal sealed interface UnifiedBookUploadOutcome {
    data class Accepted(
        val shelfBookId: String,
        val sha256: String,
        val jobState: BookUploadState,
        val progressPct: Int,
        val stageLabel: String,
    ) : UnifiedBookUploadOutcome

    data object UnsupportedServer : UnifiedBookUploadOutcome
    data object Offline : UnifiedBookUploadOutcome
    data object Unauthorized : UnifiedBookUploadOutcome
    data class Failed(val message: String, val retryable: Boolean = true) : UnifiedBookUploadOutcome
}

internal sealed interface UnifiedBookStatusOutcome {
    data class Ready(
        val jobState: BookUploadState,
        val progressPct: Int,
        val stageLabel: String,
        val errorMessage: String? = null,
    ) : UnifiedBookStatusOutcome

    data object UnsupportedServer : UnifiedBookStatusOutcome
    data object Offline : UnifiedBookStatusOutcome
    data object Unauthorized : UnifiedBookStatusOutcome
    data object NotFound : UnifiedBookStatusOutcome
}

internal sealed interface UnifiedBookDeleteOutcome {
    data object Deleted : UnifiedBookDeleteOutcome
    data object NotFound : UnifiedBookDeleteOutcome
    data object UnsupportedServer : UnifiedBookDeleteOutcome
    data object Offline : UnifiedBookDeleteOutcome
    data object Unauthorized : UnifiedBookDeleteOutcome
    data class Failed(val message: String, val retryable: Boolean = true) : UnifiedBookDeleteOutcome
}

internal data class RemoteShelfBook(
    val shelfBookId: String,
    val sha256: String,
    val title: String,
    val format: String,
    val sizeBytes: Long,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val jobState: BookUploadState = BookUploadState.QUEUED,
    val progressPct: Int = 0,
    val stageLabel: String = "",
    val errorMessage: String? = null,
    val processCount: Int = 0,
) {
    fun cardStatusLine(): String = when (jobState) {
        BookUploadState.PROCESSING -> if (progressPct in 1..99) "正在整理 · $progressPct% · 点击下载阅读" else "正在整理 · 点击下载阅读"
        BookUploadState.QUEUED, BookUploadState.RECEIVED -> "已排队整理 · 点击下载阅读"
        BookUploadState.FAILED -> errorMessage?.takeIf { it.isNotBlank() }?.let { "$it · 点击下载阅读" } ?: "点击下载阅读"
        else -> "云端书架 · 点击下载阅读"
    }
}

internal sealed interface UnifiedBookListOutcome {
    data class Ready(val books: List<RemoteShelfBook>) : UnifiedBookListOutcome
    data object UnsupportedServer : UnifiedBookListOutcome
    data object Offline : UnifiedBookListOutcome
    data object Unauthorized : UnifiedBookListOutcome
}

internal sealed interface UnifiedBookDownloadOutcome {
    data class Downloaded(val sha256: String, val sizeBytes: Long) : UnifiedBookDownloadOutcome
    data object NotFound : UnifiedBookDownloadOutcome
    data object UnsupportedServer : UnifiedBookDownloadOutcome
    data object Offline : UnifiedBookDownloadOutcome
    data object Unauthorized : UnifiedBookDownloadOutcome
    data class Failed(val message: String) : UnifiedBookDownloadOutcome
}

/**
 * Immutable authenticated session snapshot pinned for an entire sync batch so an identity switch
 * mid-batch can never send an old account's queued book under the new account's credentials.
 */
internal interface UnifiedBookRemoteSession {
    val sessionId: String
    val fallbackAccountKey: String
    suspend fun checkCapability(): UnifiedBookCapability
    suspend fun uploadBook(
        file: Path,
        format: String,
        title: String,
        sha256: String,
        onProgress: (Int) -> Unit,
    ): UnifiedBookUploadOutcome

    suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome
    suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome = UnifiedBookDeleteOutcome.UnsupportedServer
    suspend fun listRemoteBooks(): UnifiedBookListOutcome = UnifiedBookListOutcome.UnsupportedServer
    suspend fun downloadBookOriginal(
        shelfBookId: String,
        destination: Path,
        expectedSha256: String,
        onProgress: (Int) -> Unit = {},
    ): UnifiedBookDownloadOutcome = UnifiedBookDownloadOutcome.UnsupportedServer
}

internal interface UnifiedBookRemoteClient {
    suspend fun activeAccountKey(): String?
    suspend fun checkCapability(): UnifiedBookCapability
    suspend fun uploadBook(
        file: Path,
        format: String,
        title: String,
        sha256: String,
        onProgress: (Int) -> Unit,
    ): UnifiedBookUploadOutcome

    suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome
    suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome = UnifiedBookDeleteOutcome.UnsupportedServer
    suspend fun listRemoteBooks(): UnifiedBookListOutcome = UnifiedBookListOutcome.UnsupportedServer
    suspend fun downloadBookOriginal(
        shelfBookId: String,
        destination: Path,
        expectedSha256: String,
        onProgress: (Int) -> Unit = {},
    ): UnifiedBookDownloadOutcome = UnifiedBookDownloadOutcome.UnsupportedServer

    /**
     * Captures a pinned session for one sync batch. Production implementations bind an immutable
     * `(serverUrl, deviceId, token)` credential and abort if the active identity changes mid-batch.
     */
    suspend fun openSession(): UnifiedBookRemoteSession? {
        val key = activeAccountKey() ?: return null
        val client = this
        return object : UnifiedBookRemoteSession {
            override val sessionId: String = key
            override val fallbackAccountKey: String = key
            override suspend fun checkCapability(): UnifiedBookCapability = client.checkCapability()
            override suspend fun uploadBook(
                file: Path,
                format: String,
                title: String,
                sha256: String,
                onProgress: (Int) -> Unit,
            ): UnifiedBookUploadOutcome = client.uploadBook(file, format, title, sha256, onProgress)

            override suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome = client.fetchStatus(shelfBookId)
            override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome = client.deleteBook(shelfBookId)
            override suspend fun listRemoteBooks(): UnifiedBookListOutcome = client.listRemoteBooks()
            override suspend fun downloadBookOriginal(
                shelfBookId: String,
                destination: Path,
                expectedSha256: String,
                onProgress: (Int) -> Unit,
            ): UnifiedBookDownloadOutcome = client.downloadBookOriginal(shelfBookId, destination, expectedSha256, onProgress)
        }
    }
}

internal object BookUploadQueueStore {
    fun saveAccountBinding(
        db: Connection,
        sessionKey: String,
        accountKey: String,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!BookSchemaMigration.tableExists(db, "book_upload_account_bindings")) return
        db.prepareStatement(
            """INSERT INTO book_upload_account_bindings(session_key, account_key, updated_at)
               VALUES(?, ?, ?)
               ON CONFLICT(session_key) DO UPDATE SET account_key=excluded.account_key, updated_at=excluded.updated_at""".trimIndent(),
        ).use { sql ->
            sql.setString(1, sessionKey)
            sql.setString(2, accountKey)
            sql.setLong(3, now)
            sql.executeUpdate()
        }
    }

    fun boundAccountKey(db: Connection, sessionKey: String): String? {
        if (!BookSchemaMigration.tableExists(db, "book_upload_account_bindings")) return null
        return db.prepareStatement(
            "SELECT account_key FROM book_upload_account_bindings WHERE session_key=?",
        ).use { sql ->
            sql.setString(1, sessionKey)
            sql.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun enqueue(
        db: Connection,
        accountKey: String,
        localBookId: String,
        fileSha256: String,
        format: String,
        title: String,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return
        val existing = read(db, accountKey, localBookId)
        if (existing != null && existing.fileSha256 == fileSha256 &&
            existing.state in setOf(BookUploadState.RECEIVED, BookUploadState.QUEUED, BookUploadState.PROCESSING, BookUploadState.READY)
        ) {
            db.prepareStatement(
                "UPDATE book_upload_queue SET title=?, format=?, paused=0, updated_at=? WHERE account_key=? AND local_book_id=?",
            ).use { sql ->
                sql.setString(1, title)
                sql.setString(2, format)
                sql.setLong(3, now)
                sql.setString(4, accountKey)
                sql.setString(5, localBookId)
                sql.executeUpdate()
            }
            return
        }
        db.prepareStatement(
            """INSERT INTO book_upload_queue(
                   account_key, local_book_id, file_sha256, server_shelf_book_id, format, title,
                   state, progress_pct, stage_label, error_message, paused, updated_at
               ) VALUES(?, ?, ?, NULL, ?, ?, 'WAITING_NETWORK', 0, '', NULL, 0, ?)
               ON CONFLICT(account_key, local_book_id) DO UPDATE SET
                   file_sha256=excluded.file_sha256,
                   server_shelf_book_id=NULL,
                   format=excluded.format,
                   title=excluded.title,
                   state='WAITING_NETWORK',
                   progress_pct=0,
                   stage_label='',
                   error_message=NULL,
                   paused=0,
                   updated_at=excluded.updated_at""".trimIndent(),
        ).use { sql ->
            sql.setString(1, accountKey)
            sql.setString(2, localBookId)
            sql.setString(3, fileSha256)
            sql.setString(4, format)
            sql.setString(5, title)
            sql.setLong(6, now)
            sql.executeUpdate()
        }
    }

    /**
     * Pauses every other account's upload queue when switching accounts,
     * and resumes the active account's queue (resetting interrupted UPLOADING tasks to WAITING_NETWORK).
     */
    fun switchAccount(db: Connection, activeAccountKey: String, now: Long = System.currentTimeMillis()) {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return
        db.prepareStatement(
            """UPDATE book_upload_queue
               SET paused=1,
                   state=CASE WHEN state='UPLOADING' THEN 'WAITING_NETWORK' ELSE state END,
                   updated_at=?
               WHERE account_key <> ? AND paused=0""".trimIndent(),
        ).use { sql ->
            sql.setLong(1, now)
            sql.setString(2, activeAccountKey)
            sql.executeUpdate()
        }
        db.prepareStatement(
            """UPDATE book_upload_queue
               SET paused=0,
                   state=CASE WHEN state='UPLOADING' THEN 'WAITING_NETWORK' ELSE state END,
                   updated_at=?
               WHERE account_key = ?""".trimIndent(),
        ).use { sql ->
            sql.setLong(1, now)
            sql.setString(2, activeAccountKey)
            sql.executeUpdate()
        }
    }

    fun recoverInterruptedUploads(db: Connection, now: Long = System.currentTimeMillis()) {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return
        db.prepareStatement(
            "UPDATE book_upload_queue SET state='WAITING_NETWORK', progress_pct=0, updated_at=? WHERE state='UPLOADING'",
        ).use { sql ->
            sql.setLong(1, now)
            sql.executeUpdate()
        }
    }

    fun updateState(
        db: Connection,
        accountKey: String,
        localBookId: String,
        state: BookUploadState,
        progressPct: Int = 0,
        stageLabel: String = "",
        errorMessage: String? = null,
        serverShelfBookId: String? = null,
        clearServerShelfBookId: Boolean = false,
        now: Long = System.currentTimeMillis(),
        expectedServerShelfBookId: String? = null,
        requireNullServerShelfBookId: Boolean = false,
        allowedCurrentStates: Set<BookUploadState>? = null,
    ): Int {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return 0
        if (allowedCurrentStates != null && allowedCurrentStates.isEmpty()) return 0
        val shelfClause = if (clearServerShelfBookId) {
            "server_shelf_book_id=NULL"
        } else {
            "server_shelf_book_id=COALESCE(?, server_shelf_book_id)"
        }
        val whereClauses = mutableListOf("account_key=?", "local_book_id=?")
        if (requireNullServerShelfBookId) {
            whereClauses += "server_shelf_book_id IS NULL"
        } else if (expectedServerShelfBookId != null) {
            whereClauses += "server_shelf_book_id=?"
        }
        if (allowedCurrentStates != null) {
            val placeholders = allowedCurrentStates.joinToString(",") { "?" }
            whereClauses += "state IN ($placeholders)"
        }
        return db.prepareStatement(
            """UPDATE book_upload_queue
               SET state=?, progress_pct=?, stage_label=?, error_message=?,
                   $shelfClause,
                   updated_at=?
               WHERE ${whereClauses.joinToString(" AND ")}""".trimIndent(),
        ).use { sql ->
            var idx = 1
            sql.setString(idx++, state.name)
            sql.setInt(idx++, progressPct.coerceIn(0, 100))
            sql.setString(idx++, stageLabel)
            if (errorMessage == null) sql.setNull(idx++, java.sql.Types.VARCHAR) else sql.setString(idx++, errorMessage)
            if (!clearServerShelfBookId) {
                if (serverShelfBookId == null) sql.setNull(idx++, java.sql.Types.VARCHAR) else sql.setString(idx++, serverShelfBookId)
            }
            sql.setLong(idx++, now)
            sql.setString(idx++, accountKey)
            sql.setString(idx++, localBookId)
            if (!requireNullServerShelfBookId && expectedServerShelfBookId != null) {
                sql.setString(idx++, expectedServerShelfBookId)
            }
            if (allowedCurrentStates != null) {
                for (allowed in allowedCurrentStates) {
                    sql.setString(idx++, allowed.name)
                }
            }
            sql.executeUpdate()
        }
    }

    fun retry(db: Connection, accountKey: String, localBookId: String, now: Long = System.currentTimeMillis()) {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return
        db.prepareStatement(
            """UPDATE book_upload_queue
               SET state='WAITING_NETWORK', progress_pct=0, stage_label='', error_message=NULL, paused=0, updated_at=?
               WHERE account_key=? AND local_book_id=?""".trimIndent(),
        ).use { sql ->
            sql.setLong(1, now)
            sql.setString(2, accountKey)
            sql.setString(3, localBookId)
            sql.executeUpdate()
        }
    }

    /**
     * Marks an already-synced shelf record as `PENDING_DELETE` for [initiatingAccountKey] (persisting
     * `(account_key, server_shelf_book_id)` independently in `book_pending_deletes` so offline re-imports
     * never lose an unacknowledged shelf ID), or drops an unsynced local queue row for [initiatingAccountKey].
     * Never touches other accounts' rows and performs no mutation when [initiatingAccountKey] is unavailable (`null`).
     */
    fun markDeleteOrDropForAccount(
        db: Connection,
        localBookId: String,
        initiatingAccountKey: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        if (initiatingAccountKey == null || !BookSchemaMigration.tableExists(db, "book_upload_queue")) return
        val existing = read(db, initiatingAccountKey, localBookId) ?: return
        if (existing.serverShelfBookId == null) {
            db.prepareStatement(
                "DELETE FROM book_upload_queue WHERE account_key=? AND local_book_id=? AND server_shelf_book_id IS NULL",
            ).use { sql ->
                sql.setString(1, initiatingAccountKey)
                sql.setString(2, localBookId)
                sql.executeUpdate()
            }
        } else {
            if (BookSchemaMigration.tableExists(db, "book_pending_deletes")) {
                db.prepareStatement(
                    """INSERT INTO book_pending_deletes(
                           account_key, server_shelf_book_id, local_book_id, file_sha256, format, title, created_at
                       ) VALUES(?, ?, ?, ?, ?, ?, ?)
                       ON CONFLICT(account_key, server_shelf_book_id) DO UPDATE SET
                           local_book_id=excluded.local_book_id,
                           file_sha256=excluded.file_sha256,
                           format=excluded.format,
                           title=excluded.title,
                           created_at=excluded.created_at""".trimIndent(),
                ).use { sql ->
                    sql.setString(1, initiatingAccountKey)
                    sql.setString(2, existing.serverShelfBookId)
                    sql.setString(3, existing.localBookId)
                    sql.setString(4, existing.fileSha256)
                    sql.setString(5, existing.format)
                    sql.setString(6, existing.title)
                    sql.setLong(7, now)
                    sql.executeUpdate()
                }
            }
            db.prepareStatement(
                """UPDATE book_upload_queue
                   SET state='PENDING_DELETE', progress_pct=0, stage_label='', error_message=NULL, updated_at=?
                   WHERE account_key=? AND local_book_id=? AND server_shelf_book_id IS NOT NULL""".trimIndent(),
            ).use { sql ->
                sql.setLong(1, now)
                sql.setString(2, initiatingAccountKey)
                sql.setString(3, localBookId)
                sql.executeUpdate()
            }
        }
    }

    fun recordOrphanedPendingDelete(
        db: Connection,
        accountKey: String,
        localBookId: String,
        fileSha256: String,
        serverShelfBookId: String,
        format: String,
        title: String,
        paused: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) {
        if (BookSchemaMigration.tableExists(db, "book_pending_deletes")) {
            db.prepareStatement(
                """INSERT INTO book_pending_deletes(
                       account_key, server_shelf_book_id, local_book_id, file_sha256, format, title, created_at
                   ) VALUES(?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(account_key, server_shelf_book_id) DO UPDATE SET
                       local_book_id=excluded.local_book_id,
                       file_sha256=excluded.file_sha256,
                       format=excluded.format,
                       title=excluded.title,
                       created_at=excluded.created_at""".trimIndent(),
            ).use { sql ->
                sql.setString(1, accountKey)
                sql.setString(2, serverShelfBookId)
                sql.setString(3, localBookId)
                sql.setString(4, fileSha256)
                sql.setString(5, format)
                sql.setString(6, title)
                sql.setLong(7, now)
                sql.executeUpdate()
            }
        }
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return
        db.prepareStatement(
            """INSERT INTO book_upload_queue(
                   account_key, local_book_id, file_sha256, server_shelf_book_id, format, title,
                   state, progress_pct, stage_label, error_message, paused, updated_at
               ) VALUES(?, ?, ?, ?, ?, ?, 'PENDING_DELETE', 0, '', NULL, ?, ?)
               ON CONFLICT(account_key, local_book_id) DO UPDATE SET
                   server_shelf_book_id=excluded.server_shelf_book_id,
                   state='PENDING_DELETE',
                   progress_pct=0,
                   stage_label='',
                   error_message=NULL,
                   updated_at=excluded.updated_at""".trimIndent(),
        ).use { sql ->
            sql.setString(1, accountKey)
            sql.setString(2, localBookId)
            sql.setString(3, fileSha256)
            sql.setString(4, serverShelfBookId)
            sql.setString(5, format)
            sql.setString(6, title)
            sql.setInt(7, if (paused) 1 else 0)
            sql.setLong(8, now)
            sql.executeUpdate()
        }
    }

    fun completePendingDelete(
        db: Connection,
        accountKey: String,
        localBookId: String,
        expectedShelfBookId: String?,
    ) {
        if (expectedShelfBookId != null && BookSchemaMigration.tableExists(db, "book_pending_deletes")) {
            db.prepareStatement(
                "DELETE FROM book_pending_deletes WHERE account_key=? AND server_shelf_book_id=?",
            ).use { sql ->
                sql.setString(1, accountKey)
                sql.setString(2, expectedShelfBookId)
                sql.executeUpdate()
            }
        }
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return
        if (expectedShelfBookId == null) {
            db.prepareStatement(
                """DELETE FROM book_upload_queue
                   WHERE account_key=? AND local_book_id=? AND state='PENDING_DELETE' AND server_shelf_book_id IS NULL""".trimIndent(),
            ).use { sql ->
                sql.setString(1, accountKey)
                sql.setString(2, localBookId)
                sql.executeUpdate()
            }
        } else {
            db.prepareStatement(
                """DELETE FROM book_upload_queue
                   WHERE account_key=? AND local_book_id=? AND state='PENDING_DELETE' AND server_shelf_book_id=?""".trimIndent(),
            ).use { sql ->
                sql.setString(1, accountKey)
                sql.setString(2, localBookId)
                sql.setString(3, expectedShelfBookId)
                sql.executeUpdate()
            }
        }
    }

    fun hasPendingUploadRequiringOriginal(db: Connection, localBookId: String): Boolean {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return false
        return db.prepareStatement(
            """SELECT 1 FROM book_upload_queue
               WHERE (local_book_id=? OR file_sha256=?)
                 AND server_shelf_book_id IS NULL
                 AND state <> 'PENDING_DELETE'
               LIMIT 1""".trimIndent(),
        ).use { sql ->
            sql.setString(1, localBookId)
            sql.setString(2, localBookId)
            sql.executeQuery().use { it.next() }
        }
    }

    fun read(db: Connection, accountKey: String, localBookId: String): BookUploadEntry? {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return null
        return db.prepareStatement(
            """SELECT account_key, local_book_id, file_sha256, server_shelf_book_id, format, title,
                      state, progress_pct, stage_label, error_message, paused, updated_at
               FROM book_upload_queue WHERE account_key=? AND local_book_id=?""".trimIndent(),
        ).use { sql ->
            sql.setString(1, accountKey)
            sql.setString(2, localBookId)
            sql.executeQuery().use { rs -> if (rs.next()) rowToEntry(rs) else null }
        }
    }

    /** Active book-card entries for [accountKey] (excludes paused and pending-delete entries). */
    fun listForAccount(db: Connection, accountKey: String): Map<String, BookUploadEntry> {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return emptyMap()
        return db.prepareStatement(
            """SELECT account_key, local_book_id, file_sha256, server_shelf_book_id, format, title,
                      state, progress_pct, stage_label, error_message, paused, updated_at
               FROM book_upload_queue
               WHERE account_key=? AND paused=0 AND state <> 'PENDING_DELETE'
               ORDER BY updated_at ASC""".trimIndent(),
        ).use { sql ->
            sql.setString(1, accountKey)
            sql.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val entry = rowToEntry(rs)
                        put(entry.localBookId, entry)
                    }
                }
            }
        }
    }

    /**
     * Ordered sync tasks for [accountKey]: independent `PENDING_DELETE` unbind intents first
     * (so stale shelf IDs unbind before any re-import upload of the same SHA), followed by active upload/status rows.
     */
    fun listSyncTasksForAccount(db: Connection, accountKey: String): List<BookUploadEntry> {
        val pendingDeletes = if (BookSchemaMigration.tableExists(db, "book_pending_deletes")) {
            db.prepareStatement(
                """SELECT account_key, server_shelf_book_id, local_book_id, file_sha256, format, title, created_at
                   FROM book_pending_deletes
                   WHERE account_key=?
                   ORDER BY created_at ASC, server_shelf_book_id ASC""".trimIndent(),
            ).use { sql ->
                sql.setString(1, accountKey)
                sql.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                BookUploadEntry(
                                    accountKey = rs.getString(1),
                                    localBookId = rs.getString(3),
                                    fileSha256 = rs.getString(4),
                                    serverShelfBookId = rs.getString(2),
                                    format = rs.getString(5),
                                    title = rs.getString(6),
                                    state = BookUploadState.PENDING_DELETE,
                                    paused = false,
                                    updatedAt = rs.getLong(7),
                                ),
                            )
                        }
                    }
                }
            }
        } else emptyList()

        val seenDeleteShelfIds = pendingDeletes.mapNotNullTo(HashSet()) { it.serverShelfBookId }
        val queueRows = if (BookSchemaMigration.tableExists(db, "book_upload_queue")) {
            db.prepareStatement(
                """SELECT account_key, local_book_id, file_sha256, server_shelf_book_id, format, title,
                          state, progress_pct, stage_label, error_message, paused, updated_at
                   FROM book_upload_queue
                   WHERE account_key=? AND paused=0
                   ORDER BY updated_at ASC""".trimIndent(),
            ).use { sql ->
                sql.setString(1, accountKey)
                sql.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rowToEntry(rs)) }
                }
            }
        } else emptyList()

        val extraQueueDeletes = queueRows.filter {
            it.state == BookUploadState.PENDING_DELETE &&
                (it.serverShelfBookId == null || it.serverShelfBookId !in seenDeleteShelfIds)
        }
        val activeUploads = queueRows.filter { it.state != BookUploadState.PENDING_DELETE }
        return pendingDeletes + extraQueueDeletes + activeUploads
    }

    fun listAllForAccount(db: Connection, accountKey: String): List<BookUploadEntry> {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return emptyList()
        return db.prepareStatement(
            """SELECT account_key, local_book_id, file_sha256, server_shelf_book_id, format, title,
                      state, progress_pct, stage_label, error_message, paused, updated_at
               FROM book_upload_queue
               WHERE account_key=?
               ORDER BY updated_at ASC""".trimIndent(),
        ).use { sql ->
            sql.setString(1, accountKey)
            sql.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rowToEntry(rs)) }
            }
        }
    }

    fun listAll(db: Connection): List<BookUploadEntry> {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return emptyList()
        return db.createStatement().use { sql ->
            sql.executeQuery(
                """SELECT account_key, local_book_id, file_sha256, server_shelf_book_id, format, title,
                          state, progress_pct, stage_label, error_message, paused, updated_at
                   FROM book_upload_queue ORDER BY updated_at ASC""".trimIndent(),
            ).use { rs ->
                buildList { while (rs.next()) add(rowToEntry(rs)) }
            }
        }
    }

    private fun rowToEntry(rs: java.sql.ResultSet): BookUploadEntry = BookUploadEntry(
        accountKey = rs.getString(1),
        localBookId = rs.getString(2),
        fileSha256 = rs.getString(3),
        serverShelfBookId = rs.getString(4),
        format = rs.getString(5),
        title = rs.getString(6),
        state = runCatching { BookUploadState.valueOf(rs.getString(7)) }.getOrDefault(BookUploadState.WAITING_NETWORK),
        progressPct = rs.getInt(8),
        stageLabel = rs.getString(9).orEmpty(),
        errorMessage = rs.getString(10),
        paused = rs.getInt(11) != 0,
        updatedAt = rs.getLong(12),
    )

    fun parseServerJobState(raw: String): BookUploadState = when (raw.trim().lowercase()) {
        "received" -> BookUploadState.RECEIVED
        "queued" -> BookUploadState.QUEUED
        "processing" -> BookUploadState.PROCESSING
        "ready" -> BookUploadState.READY
        "failed" -> BookUploadState.FAILED
        else -> BookUploadState.QUEUED
    }
}

internal object UnifiedBookCloudBridge {
    private val lock = Any()
    private val coordinators = WeakHashMap<TextbookLibrary, UnifiedBookUploadCoordinator>()

    fun register(library: TextbookLibrary, coordinator: UnifiedBookUploadCoordinator) {
        synchronized(lock) { coordinators[library] = coordinator }
    }

    fun forLibrary(library: TextbookLibrary): UnifiedBookUploadCoordinator? =
        synchronized(lock) { coordinators[library] }
}

/**
 * Coordinates the persistent per-account unified upload, unbind, and cloud shelf pullback queue
 * without ever blocking local reading.
 * - Pins each sync batch to a single immutable [UnifiedBookRemoteSession] and cancels old batches on identity switch.
 * - Persists verified session->account bindings so rolling back to an older server preserves the pending book status.
 * - Clears stale server shelf IDs on 404 NotFound so user retries re-upload the local original file.
 * - Persists per-account `PENDING_DELETE` unbind tasks on delete/cancel and clears them only after server confirmation.
 * - Discovers server-side shelf books on a fresh data directory and pulls back verified originals on demand.
 */
internal class UnifiedBookUploadCoordinator(
    private val library: TextbookLibrary,
    private val remoteProvider: () -> UnifiedBookRemoteClient?,
) {
    private val syncLock = Mutex()
    private val restoreLock = Mutex()
    private val mutableStatuses = MutableStateFlow<Map<String, BookUploadEntry>>(emptyMap())
    val statuses: StateFlow<Map<String, BookUploadEntry>> = mutableStatuses.asStateFlow()
    private val mutableRemoteShelfBooks = MutableStateFlow<List<RemoteShelfBook>>(emptyList())
    val remoteShelfBooks: StateFlow<List<RemoteShelfBook>> = mutableRemoteShelfBooks.asStateFlow()
    private val mutableRestoringShelfBookId = MutableStateFlow<String?>(null)
    val restoringShelfBookId: StateFlow<String?> = mutableRestoringShelfBookId.asStateFlow()
    private val mutableRestoreProgressPct = MutableStateFlow(0)
    val restoreProgressPct: StateFlow<Int> = mutableRestoreProgressPct.asStateFlow()
    private val mutableRestoreError = MutableStateFlow<String?>(null)
    val restoreError: StateFlow<String?> = mutableRestoreError.asStateFlow()

    private val sessionStateLock = Any()
    private val sessionGeneration = AtomicLong(0L)
    @Volatile private var activeSessionId: String? = null
    @Volatile private var lastAccountKey: String? = null
    @Volatile private var activeBatchJob: Job? = null
    @Volatile private var activeRestoreJob: Job? = null
    @Volatile internal var beforeRestoreCommitForTest: (suspend (RemoteShelfBook, String) -> Unit)? = null

    init {
        UnifiedBookCloudBridge.register(library, this)
    }

    internal fun currentAccountKey(): String? = lastAccountKey

    internal suspend fun resolveInitiatingAccountKey(): String? {
        lastAccountKey?.let { return it }
        val remote = remoteProvider() ?: return null
        val session = remote.openSession() ?: return null
        return withContext(Dispatchers.IO) {
            library.boundUploadAccountKey(session.fallbackAccountKey) ?: session.fallbackAccountKey
        }
    }

    internal fun refreshCurrentAccountStatuses() {
        val accountKey = lastAccountKey
        val sessionId = activeSessionId
        if (accountKey == null || sessionId == null) {
            mutableStatuses.value = emptyMap()
            return
        }
        publishForAccount(accountKey, sessionGeneration.get(), sessionId)
    }

    /**
     * Cancels any in-flight sync batch or cloud restore belonging to a previous account/session and prevents
     * late responses from mutating the new session's queue bindings or UI state.
     */
    fun onSessionChanged(newSessionId: String?) {
        val batchToCancel: Job?
        val restoreToCancel: Job?
        synchronized(sessionStateLock) {
            val changed = newSessionId != activeSessionId
            sessionGeneration.incrementAndGet()
            batchToCancel = activeBatchJob
            activeBatchJob = null
            restoreToCancel = activeRestoreJob
            activeRestoreJob = null
            if (changed) {
                activeSessionId = newSessionId
                lastAccountKey = null
                mutableStatuses.value = emptyMap()
                mutableRemoteShelfBooks.value = emptyList()
                mutableRestoringShelfBookId.value = null
                mutableRestoreProgressPct.value = 0
                mutableRestoreError.value = null
            }
        }
        batchToCancel?.cancel()
        restoreToCancel?.cancel()
    }

    suspend fun enqueueBook(localBookId: String, fileSha256: String, format: String, title: String) {
        val remote = remoteProvider() ?: return
        val session = remote.openSession() ?: return
        val accountKey = withContext(Dispatchers.IO) {
            library.boundUploadAccountKey(session.fallbackAccountKey) ?: session.fallbackAccountKey
        }
        withContext(Dispatchers.IO) {
            library.enqueueBookUpload(accountKey, localBookId, fileSha256, format, title)
            if (activeSessionId == null || activeSessionId == session.sessionId) {
                activeSessionId = session.sessionId
                publishForAccount(accountKey, sessionGeneration.get(), session.sessionId)
            }
        }
    }

    suspend fun enqueueBookForAccount(
        accountKey: String,
        localBookId: String,
        fileSha256: String,
        format: String,
        title: String,
    ) {
        withContext(Dispatchers.IO) {
            library.enqueueBookUpload(accountKey, localBookId, fileSha256, format, title)
            val currentActive = lastAccountKey
            if (currentActive != null && currentActive != accountKey) {
                library.switchUploadAccount(currentActive)
            } else {
                val sessionId = activeSessionId
                if (sessionId != null) {
                    publishForAccount(accountKey, sessionGeneration.get(), sessionId)
                }
            }
        }
    }

    suspend fun retryBook(localBookId: String) {
        val remote = remoteProvider() ?: return
        val session = remote.openSession() ?: return
        val accountKey = lastAccountKey
            ?: withContext(Dispatchers.IO) { library.boundUploadAccountKey(session.fallbackAccountKey) }
            ?: session.fallbackAccountKey
        withContext(Dispatchers.IO) {
            library.retryBookUpload(accountKey, localBookId)
            publishForAccount(accountKey, sessionGeneration.get(), session.sessionId)
        }
        syncOnce()
    }

    suspend fun syncOnce() {
        val remote = remoteProvider() ?: return
        val myGen = sessionGeneration.get()
        val session = remote.openSession() ?: run {
            synchronized(sessionStateLock) {
                if (sessionGeneration.get() == myGen) {
                    activeSessionId = null
                    lastAccountKey = null
                    mutableStatuses.value = emptyMap()
                    mutableRemoteShelfBooks.value = emptyList()
                }
            }
            return
        }
        val previousSession = activeSessionId
        if (previousSession != null && previousSession != session.sessionId) {
            // Identity switched between syncs; bump generation and cancel any stale in-flight batch/restore first.
            onSessionChanged(session.sessionId)
        }
        val batchGen = sessionGeneration.get()
        val batchJob = currentCoroutineContext()[Job]
        syncLock.withLock {
            val registered = synchronized(sessionStateLock) {
                if (sessionGeneration.get() != batchGen) {
                    false
                } else {
                    activeSessionId = session.sessionId
                    activeBatchJob = batchJob
                    true
                }
            }
            if (!registered) return@withLock
            try {
                runPinnedBatch(session, batchGen)
            } finally {
                synchronized(sessionStateLock) {
                    if (activeBatchJob === batchJob) activeBatchJob = null
                }
            }
        }
    }

    private suspend fun runPinnedBatch(session: UnifiedBookRemoteSession, batchGen: Long) {
        fun isBatchCurrent(): Boolean =
            sessionGeneration.get() == batchGen && activeSessionId == session.sessionId

        currentCoroutineContext().ensureActive()
        val capability = try {
            session.checkCapability()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            UnifiedBookCapability.Offline
        }
        if (!isBatchCurrent()) return

        val resolvedAccountKey = withContext(Dispatchers.IO) {
            when (capability) {
                is UnifiedBookCapability.Supported -> {
                    if (capability.accountKey != session.fallbackAccountKey) {
                        library.saveUploadAccountBinding(session.fallbackAccountKey, capability.accountKey)
                    }
                    capability.accountKey
                }
                is UnifiedBookCapability.Unsupported -> {
                    if (capability.accountKey != session.fallbackAccountKey) {
                        library.saveUploadAccountBinding(session.fallbackAccountKey, capability.accountKey)
                        capability.accountKey
                    } else {
                        library.boundUploadAccountKey(session.fallbackAccountKey) ?: capability.accountKey
                    }
                }
                UnifiedBookCapability.Unauthorized, UnifiedBookCapability.Offline -> {
                    library.boundUploadAccountKey(session.fallbackAccountKey) ?: session.fallbackAccountKey
                }
            }
        }
        if (!isBatchCurrent()) return

        withContext(Dispatchers.IO) {
            if (session.fallbackAccountKey != resolvedAccountKey) {
                library.rekeyUploadAccount(session.fallbackAccountKey, resolvedAccountKey)
            }
            val shouldSwitch = synchronized(sessionStateLock) {
                if (!isBatchCurrent()) return@synchronized null
                val switched = lastAccountKey != resolvedAccountKey
                if (switched) lastAccountKey = resolvedAccountKey
                switched
            } ?: return@withContext
            if (shouldSwitch) {
                library.switchUploadAccount(resolvedAccountKey)
            } else {
                library.recoverInterruptedBookUploads()
            }
            publishForAccount(resolvedAccountKey, batchGen, session.sessionId)
        }

        val entries = withContext(Dispatchers.IO) { library.syncTasksForAccount(resolvedAccountKey) }

        when (capability) {
            UnifiedBookCapability.Offline -> {
                if (entries.isEmpty()) return
                withContext(Dispatchers.IO) {
                    if (!isBatchCurrent()) return@withContext
                    for (entry in entries) {
                        if (entry.serverShelfBookId == null && entry.state in setOf(
                                BookUploadState.WAITING_SERVER, BookUploadState.UPLOADING,
                            )
                        ) {
                            library.updateBookUploadState(
                                accountKey = resolvedAccountKey,
                                localBookId = entry.localBookId,
                                state = BookUploadState.WAITING_NETWORK,
                                requireNullServerShelfBookId = true,
                                allowedCurrentStates = setOf(BookUploadState.WAITING_SERVER, BookUploadState.UPLOADING),
                            )
                        }
                    }
                    publishForAccount(resolvedAccountKey, batchGen, session.sessionId)
                }
                return
            }
            UnifiedBookCapability.Unauthorized -> return
            is UnifiedBookCapability.Unsupported -> {
                if (entries.isEmpty()) return
                // Rolled-back or old server without unified upload: never fall back to legacy fragments.
                withContext(Dispatchers.IO) {
                    if (!isBatchCurrent()) return@withContext
                    for (entry in entries) {
                        if (entry.serverShelfBookId == null && entry.state in setOf(
                                BookUploadState.WAITING_NETWORK, BookUploadState.UPLOADING,
                            )
                        ) {
                            library.updateBookUploadState(
                                accountKey = resolvedAccountKey,
                                localBookId = entry.localBookId,
                                state = BookUploadState.WAITING_SERVER,
                                requireNullServerShelfBookId = true,
                                allowedCurrentStates = setOf(BookUploadState.WAITING_NETWORK, BookUploadState.UPLOADING),
                            )
                        }
                    }
                    publishForAccount(resolvedAccountKey, batchGen, session.sessionId)
                }
                return
            }
            is UnifiedBookCapability.Supported -> Unit
        }

        // Process pending unbinds first, then reconcile remote shelf, then uploads and status polls sequentially.
        val blockedUploadShas = HashSet<String>()
        val pendingDeleteEntries = entries.filter { it.state == BookUploadState.PENDING_DELETE }
        for (entry in pendingDeleteEntries) {
            currentCoroutineContext().ensureActive()
            if (!isBatchCurrent() || entry.paused) return
            val shelfId = entry.serverShelfBookId
            if (shelfId == null) {
                withContext(Dispatchers.IO) {
                    if (!isBatchCurrent()) return@withContext
                    library.completePendingBookDelete(resolvedAccountKey, entry.localBookId, null)
                    library.cleanupOrphanOriginalIfUnused(entry.localBookId, entry.format)
                    publishForAccount(resolvedAccountKey, batchGen, session.sessionId)
                }
                continue
            }
            val deleteOutcome = session.deleteBook(shelfId)
            if (!isBatchCurrent()) return
            when (deleteOutcome) {
                UnifiedBookDeleteOutcome.Deleted,
                UnifiedBookDeleteOutcome.NotFound -> {
                    withContext(Dispatchers.IO) {
                        if (!isBatchCurrent()) return@withContext
                        library.completePendingBookDelete(resolvedAccountKey, entry.localBookId, shelfId)
                        library.cleanupOrphanOriginalIfUnused(entry.localBookId, entry.format)
                        publishForAccount(resolvedAccountKey, batchGen, session.sessionId)
                    }
                }
                UnifiedBookDeleteOutcome.UnsupportedServer,
                UnifiedBookDeleteOutcome.Offline,
                UnifiedBookDeleteOutcome.Unauthorized,
                is UnifiedBookDeleteOutcome.Failed -> {
                    blockedUploadShas += entry.fileSha256
                }
            }
        }

        // Reconcile server shelf books (enables fresh-device pullback and avoids redundant re-uploads).
        reconcileRemoteShelf(session, resolvedAccountKey, batchGen, blockedUploadShas)
        if (!isBatchCurrent()) return

        val remainingEntries = withContext(Dispatchers.IO) {
            library.syncTasksForAccount(resolvedAccountKey).filter { it.state != BookUploadState.PENDING_DELETE }
        }
        val activeUploadStates = setOf(
            BookUploadState.WAITING_NETWORK,
            BookUploadState.WAITING_SERVER,
            BookUploadState.UPLOADING,
            BookUploadState.FAILED,
        )
        val activePollStates = setOf(
            BookUploadState.RECEIVED,
            BookUploadState.QUEUED,
            BookUploadState.PROCESSING,
        )
        for (entry in remainingEntries) {
            currentCoroutineContext().ensureActive()
            if (!isBatchCurrent() || entry.paused) return
            when {
                entry.serverShelfBookId == null && entry.state in setOf(
                    BookUploadState.WAITING_NETWORK, BookUploadState.WAITING_SERVER, BookUploadState.UPLOADING,
                ) -> {
                    if (entry.fileSha256 in blockedUploadShas) continue
                    val file = library.originalFileIfPresent(entry.localBookId, entry.format)
                    if (file == null || !Files.isRegularFile(file)) {
                        withContext(Dispatchers.IO) {
                            if (!isBatchCurrent()) return@withContext
                            library.updateBookUploadState(
                                accountKey = resolvedAccountKey,
                                localBookId = entry.localBookId,
                                state = BookUploadState.FAILED,
                                errorMessage = "本地原文件已丢失，请重新导入",
                                requireNullServerShelfBookId = true,
                                allowedCurrentStates = activeUploadStates,
                            )
                            publishForAccount(resolvedAccountKey, batchGen, session.sessionId)
                        }
                        continue
                    }
                    val enteredUploading = withContext(Dispatchers.IO) {
                        if (!isBatchCurrent()) return@withContext false
                        val updated = library.updateBookUploadState(
                            accountKey = resolvedAccountKey,
                            localBookId = entry.localBookId,
                            state = BookUploadState.UPLOADING,
                            progressPct = 0,
                            requireNullServerShelfBookId = true,
                            allowedCurrentStates = activeUploadStates,
                        )
                        publishForAccount(resolvedAccountKey, batchGen, session.sessionId)
                        updated > 0
                    }
                    if (!enteredUploading) continue
                    val outcome = session.uploadBook(file, entry.format, entry.title, entry.fileSha256) { pct ->
                        if (isBatchCurrent()) {
                            library.updateBookUploadState(
                                accountKey = resolvedAccountKey,
                                localBookId = entry.localBookId,
                                state = BookUploadState.UPLOADING,
                                progressPct = pct,
                                requireNullServerShelfBookId = true,
                                allowedCurrentStates = activeUploadStates,
                            )
                            publishForAccount(resolvedAccountKey, batchGen, session.sessionId)
                        }
                    }
                    if (!isBatchCurrent()) return
                    var deletedWhileUploadingShelfId: String? = null
                    withContext(Dispatchers.IO) {
                        if (!isBatchCurrent()) return@withContext
                        when (outcome) {
                            is UnifiedBookUploadOutcome.Accepted -> {
                                val updated = library.updateBookUploadState(
                                    accountKey = resolvedAccountKey,
                                    localBookId = entry.localBookId,
                                    state = outcome.jobState,
                                    progressPct = outcome.progressPct,
                                    stageLabel = outcome.stageLabel,
                                    errorMessage = null,
                                    serverShelfBookId = outcome.shelfBookId,
                                    requireNullServerShelfBookId = true,
                                    allowedCurrentStates = activeUploadStates,
                                )
                                if (updated > 0) {
                                    library.cleanupOrphanOriginalIfUnused(entry.localBookId, entry.format)
                                } else {
                                    val currentRow = library.readUploadEntry(resolvedAccountKey, entry.localBookId)
                                    if (currentRow == null || currentRow.state == BookUploadState.PENDING_DELETE) {
                                        // Initiating account cancelled/deleted this book while the upload was in flight.
                                        library.recordOrphanedPendingDelete(
                                            accountKey = resolvedAccountKey,
                                            localBookId = entry.localBookId,
                                            fileSha256 = entry.fileSha256,
                                            serverShelfBookId = outcome.shelfBookId,
                                            format = entry.format,
                                            title = entry.title,
                                        )
                                        deletedWhileUploadingShelfId = outcome.shelfBookId
                                    }
                                }
                            }
                            UnifiedBookUploadOutcome.UnsupportedServer -> {
                                library.updateBookUploadState(
                                    accountKey = resolvedAccountKey,
                                    localBookId = entry.localBookId,
                                    state = BookUploadState.WAITING_SERVER,
                                    requireNullServerShelfBookId = true,
                                    allowedCurrentStates = activeUploadStates,
                                )
                            }
                            UnifiedBookUploadOutcome.Offline -> {
                                library.updateBookUploadState(
                                    accountKey = resolvedAccountKey,
                                    localBookId = entry.localBookId,
                                    state = BookUploadState.WAITING_NETWORK,
                                    requireNullServerShelfBookId = true,
                                    allowedCurrentStates = activeUploadStates,
                                )
                            }
                            UnifiedBookUploadOutcome.Unauthorized -> {
                                library.updateBookUploadState(
                                    accountKey = resolvedAccountKey,
                                    localBookId = entry.localBookId,
                                    state = BookUploadState.WAITING_NETWORK,
                                    requireNullServerShelfBookId = true,
                                    allowedCurrentStates = activeUploadStates,
                                )
                            }
                            is UnifiedBookUploadOutcome.Failed -> {
                                library.updateBookUploadState(
                                    accountKey = resolvedAccountKey,
                                    localBookId = entry.localBookId,
                                    state = BookUploadState.FAILED,
                                    errorMessage = outcome.message,
                                    requireNullServerShelfBookId = true,
                                    allowedCurrentStates = activeUploadStates,
                                )
                            }
                        }
                        publishForAccount(resolvedAccountKey, batchGen, session.sessionId)
                    }
                    val orphanShelfId = deletedWhileUploadingShelfId
                    if (orphanShelfId != null && isBatchCurrent()) {
                        val unbindOutcome = session.deleteBook(orphanShelfId)
                        if (isBatchCurrent() && (unbindOutcome == UnifiedBookDeleteOutcome.Deleted || unbindOutcome == UnifiedBookDeleteOutcome.NotFound)) {
                            withContext(Dispatchers.IO) {
                                if (!isBatchCurrent()) return@withContext
                                library.completePendingBookDelete(resolvedAccountKey, entry.localBookId, orphanShelfId)
                                library.cleanupOrphanOriginalIfUnused(entry.localBookId, entry.format)
                                publishForAccount(resolvedAccountKey, batchGen, session.sessionId)
                            }
                        }
                    }
                }
                entry.serverShelfBookId != null && entry.state in activePollStates -> {
                    val status = session.fetchStatus(entry.serverShelfBookId)
                    if (!isBatchCurrent()) return
                    withContext(Dispatchers.IO) {
                        if (!isBatchCurrent()) return@withContext
                        when (status) {
                            is UnifiedBookStatusOutcome.Ready -> {
                                library.updateBookUploadState(
                                    accountKey = resolvedAccountKey,
                                    localBookId = entry.localBookId,
                                    state = status.jobState,
                                    progressPct = status.progressPct,
                                    stageLabel = status.stageLabel,
                                    errorMessage = status.errorMessage,
                                    expectedServerShelfBookId = entry.serverShelfBookId,
                                    allowedCurrentStates = activePollStates,
                                )
                            }
                            UnifiedBookStatusOutcome.NotFound -> {
                                // Clear stale serverShelfBookId only if row is still in an active poll state for this shelf ID.
                                library.updateBookUploadState(
                                    accountKey = resolvedAccountKey,
                                    localBookId = entry.localBookId,
                                    state = BookUploadState.FAILED,
                                    errorMessage = "服务端记录已移除，可点击重试上传",
                                    clearServerShelfBookId = true,
                                    expectedServerShelfBookId = entry.serverShelfBookId,
                                    allowedCurrentStates = activePollStates,
                                )
                            }
                            UnifiedBookStatusOutcome.UnsupportedServer,
                            UnifiedBookStatusOutcome.Offline,
                            UnifiedBookStatusOutcome.Unauthorized -> Unit
                        }
                        publishForAccount(resolvedAccountKey, batchGen, session.sessionId)
                    }
                }
            }
        }
    }

    private suspend fun reconcileRemoteShelf(
        session: UnifiedBookRemoteSession,
        accountKey: String,
        batchGen: Long,
        blockedShas: Set<String>,
    ) {
        val listOutcome = try {
            session.listRemoteBooks()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return
        }
        if (sessionGeneration.get() != batchGen || activeSessionId != session.sessionId) return
        val remoteBooks = (listOutcome as? UnifiedBookListOutcome.Ready)?.books ?: return
        val cloudOnly = withContext(Dispatchers.IO) {
            if (sessionGeneration.get() != batchGen || activeSessionId != session.sessionId) return@withContext emptyList()
            val localIds = library.catalog().mapTo(HashSet()) { it.id }
            val pendingDeleteIds = library.syncTasksForAccount(accountKey)
                .filter { it.state == BookUploadState.PENDING_DELETE }
                .mapNotNullTo(HashSet()) { it.serverShelfBookId }
            val missingLocally = mutableListOf<RemoteShelfBook>()
            for (remote in remoteBooks) {
                if (remote.shelfBookId in pendingDeleteIds || remote.sha256 in blockedShas) continue
                if (remote.sha256 in localIds) {
                    val existing = library.readUploadEntry(accountKey, remote.sha256)
                    if (existing == null) {
                        library.enqueueBookUpload(accountKey, remote.sha256, remote.sha256, remote.format, remote.title)
                    }
                    if (existing == null || existing.state != BookUploadState.PENDING_DELETE) {
                        library.updateBookUploadState(
                            accountKey = accountKey,
                            localBookId = remote.sha256,
                            state = remote.jobState,
                            progressPct = remote.progressPct,
                            stageLabel = remote.stageLabel,
                            errorMessage = remote.errorMessage,
                            serverShelfBookId = remote.shelfBookId,
                        )
                    }
                } else {
                    missingLocally += remote
                }
            }
            publishForAccount(accountKey, batchGen, session.sessionId)
            missingLocally
        }
        if (sessionGeneration.get() == batchGen && activeSessionId == session.sessionId) {
            mutableRemoteShelfBooks.value = cloudOnly
        }
    }

    /**
     * Pulls a cloud-only shelf book's verified original file from the server, confirms SHA-256 byte identity,
     * imports it into the local library, binds its server shelf ID strictly to the initiating account without
     * re-uploading, and returns its local ID.
     * Cleanup of temporary files and cloud-card state always executes under [NonCancellable] on cancellation.
     */
    suspend fun downloadAndImport(remote: RemoteShelfBook): String? = restoreLock.withLock {
        val remoteClient = remoteProvider() ?: run {
            mutableRestoreError.value = "请先登录学习服务后再下载原文件。"
            return@withLock null
        }
        val session = remoteClient.openSession() ?: run {
            mutableRestoreError.value = "请先登录学习服务后再下载原文件。"
            return@withLock null
        }
        val fallbackBoundAccountKey = withContext(Dispatchers.IO) {
            library.boundUploadAccountKey(session.fallbackAccountKey) ?: session.fallbackAccountKey
        }
        val currentJob = currentCoroutineContext()[Job]
        val pinnedSessionId = session.sessionId
        val myGen: Long
        val initiatingAccountKey: String
        val abortedBeforeStart = synchronized(sessionStateLock) {
            if (activeSessionId != null && activeSessionId != pinnedSessionId) {
                myGen = -1L
                initiatingAccountKey = ""
                true
            } else {
                if (activeSessionId == null) {
                    activeSessionId = pinnedSessionId
                }
                myGen = sessionGeneration.get()
                initiatingAccountKey = lastAccountKey ?: fallbackBoundAccountKey
                false
            }
        }
        if (abortedBeforeStart) return@withLock null

        fun isRestoreCurrent(): Boolean =
            sessionGeneration.get() == myGen && activeSessionId == pinnedSessionId

        val alreadyLocal = withContext(Dispatchers.IO) {
            library.catalog().any { it.id == remote.sha256 }
        }
        if (alreadyLocal) {
            return@withLock synchronized(sessionStateLock) {
                if (!isRestoreCurrent()) {
                    null
                } else {
                    mutableRemoteShelfBooks.value = mutableRemoteShelfBooks.value.filterNot { it.shelfBookId == remote.shelfBookId }
                    remote.sha256
                }
            }
        }

        val registered = synchronized(sessionStateLock) {
            if (!isRestoreCurrent()) {
                false
            } else {
                activeRestoreJob = currentJob
                mutableRestoringShelfBookId.value = remote.shelfBookId
                mutableRestoreProgressPct.value = 0
                mutableRestoreError.value = null
                true
            }
        }
        if (!registered) return@withLock null

        val tempDir = withContext(Dispatchers.IO) { Files.createTempDirectory("coach-book-restore-") }
        val ext = remote.format.trim().lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        val baseTitle = remote.title.trim()
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]+"""), "_")
            .trim('.', ' ')
            .take(80)
            .ifBlank { "book" }
        val tempFile = tempDir.resolve("$baseTitle.$ext")
        try {
            val outcome = session.downloadBookOriginal(remote.shelfBookId, tempFile, remote.sha256) { pct ->
                if (isRestoreCurrent()) {
                    mutableRestoreProgressPct.value = pct.coerceIn(0, 100)
                }
            }
            if (!isRestoreCurrent()) return@withLock null
            when (outcome) {
                is UnifiedBookDownloadOutcome.Downloaded -> {
                    val actualSha = withContext(Dispatchers.IO) { sha256File(tempFile) }
                    if (!isRestoreCurrent()) return@withLock null
                    if (actualSha != remote.sha256 || outcome.sha256 != remote.sha256) {
                        if (isRestoreCurrent()) mutableRestoreError.value = "原文件校验不一致，请重试。"
                        return@withLock null
                    }
                    beforeRestoreCommitForTest?.invoke(remote, initiatingAccountKey)
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.IO) {
                        synchronized(sessionStateLock) {
                            if (!isRestoreCurrent()) return@synchronized null
                            val imported = library.importBook(tempFile)
                            if (imported.id != remote.sha256) {
                                mutableRestoreError.value = "导入后的文件校验不一致，请重试。"
                                return@synchronized null
                            }
                            library.enqueueBookUpload(initiatingAccountKey, imported.id, remote.sha256, remote.format, remote.title)
                            library.updateBookUploadState(
                                accountKey = initiatingAccountKey,
                                localBookId = imported.id,
                                state = remote.jobState,
                                progressPct = remote.progressPct,
                                stageLabel = remote.stageLabel,
                                errorMessage = remote.errorMessage,
                                serverShelfBookId = remote.shelfBookId,
                            )
                            publishForAccount(initiatingAccountKey, myGen, pinnedSessionId)
                            mutableRemoteShelfBooks.value = mutableRemoteShelfBooks.value.filterNot {
                                it.shelfBookId == remote.shelfBookId || it.sha256 == remote.sha256
                            }
                            imported.id
                        }
                    }
                }
                UnifiedBookDownloadOutcome.NotFound -> {
                    synchronized(sessionStateLock) {
                        if (isRestoreCurrent()) {
                            mutableRestoreError.value = "服务端书架中已找不到这本书。"
                            mutableRemoteShelfBooks.value = mutableRemoteShelfBooks.value.filterNot { it.shelfBookId == remote.shelfBookId }
                        }
                    }
                    null
                }
                UnifiedBookDownloadOutcome.Unauthorized -> {
                    if (isRestoreCurrent()) mutableRestoreError.value = "授权已失效，请重新登录学习服务。"
                    null
                }
                UnifiedBookDownloadOutcome.UnsupportedServer -> {
                    if (isRestoreCurrent()) mutableRestoreError.value = "当前服务版本暂不支持拉回原文件。"
                    null
                }
                UnifiedBookDownloadOutcome.Offline -> {
                    if (isRestoreCurrent()) mutableRestoreError.value = "当前无法联网，请联网后再下载原文件。"
                    null
                }
                is UnifiedBookDownloadOutcome.Failed -> {
                    if (isRestoreCurrent()) mutableRestoreError.value = outcome.message
                    null
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            if (isRestoreCurrent()) {
                mutableRestoreError.value = e.message?.takeIf { it.isNotBlank() } ?: "拉回原文件失败，请重试。"
            }
            null
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { Files.deleteIfExists(tempFile) }
                    runCatching { tempDir.toFile().deleteRecursively() }
                }
            } finally {
                synchronized(sessionStateLock) {
                    if (activeRestoreJob === currentJob) activeRestoreJob = null
                    if (mutableRestoringShelfBookId.value == remote.shelfBookId) {
                        mutableRestoringShelfBookId.value = null
                        mutableRestoreProgressPct.value = 0
                    }
                }
            }
        }
    }

    private fun sha256File(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(65_536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun publishForAccount(accountKey: String, expectedGen: Long, expectedSessionId: String) {
        if (sessionGeneration.get() != expectedGen || activeSessionId != expectedSessionId) return
        mutableStatuses.value = library.uploadEntriesForAccount(accountKey)
    }
}
