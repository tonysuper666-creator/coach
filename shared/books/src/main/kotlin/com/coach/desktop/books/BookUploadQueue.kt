package com.coach.desktop.books

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

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
    suspend fun deleteBook(shelfBookId: String): Boolean
}

internal object BookUploadQueueStore {
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
        now: Long = System.currentTimeMillis(),
    ) {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return
        db.prepareStatement(
            """UPDATE book_upload_queue
               SET state=?, progress_pct=?, stage_label=?, error_message=?,
                   server_shelf_book_id=COALESCE(?, server_shelf_book_id),
                   updated_at=?
               WHERE account_key=? AND local_book_id=?""".trimIndent(),
        ).use { sql ->
            sql.setString(1, state.name)
            sql.setInt(2, progressPct.coerceIn(0, 100))
            sql.setString(3, stageLabel)
            if (errorMessage == null) sql.setNull(4, java.sql.Types.VARCHAR) else sql.setString(4, errorMessage)
            if (serverShelfBookId == null) sql.setNull(5, java.sql.Types.VARCHAR) else sql.setString(5, serverShelfBookId)
            sql.setLong(6, now)
            sql.setString(7, accountKey)
            sql.setString(8, localBookId)
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

    fun deleteForBook(db: Connection, localBookId: String) {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return
        db.prepareStatement("DELETE FROM book_upload_queue WHERE local_book_id=?").use { sql ->
            sql.setString(1, localBookId)
            sql.executeUpdate()
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

    fun listForAccount(db: Connection, accountKey: String): Map<String, BookUploadEntry> {
        if (!BookSchemaMigration.tableExists(db, "book_upload_queue")) return emptyMap()
        return db.prepareStatement(
            """SELECT account_key, local_book_id, file_sha256, server_shelf_book_id, format, title,
                      state, progress_pct, stage_label, error_message, paused, updated_at
               FROM book_upload_queue WHERE account_key=? AND paused=0 ORDER BY updated_at ASC""".trimIndent(),
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

/**
 * Coordinates the persistent per-account unified upload queue without ever blocking local reading.
 * - Offline → marks pending books WAITING_NETWORK ("等待联网").
 * - Old / rolled-back server lacking capability → marks pending books WAITING_SERVER ("等待服务支持"),
 *   preserving queue and local reading without falling back to legacy fragment uploads.
 */
internal class UnifiedBookUploadCoordinator(
    private val library: TextbookLibrary,
    private val remoteProvider: () -> UnifiedBookRemoteClient?,
) {
    private val syncLock = Mutex()
    private val mutableStatuses = MutableStateFlow<Map<String, BookUploadEntry>>(emptyMap())
    val statuses: StateFlow<Map<String, BookUploadEntry>> = mutableStatuses.asStateFlow()

    @Volatile private var lastAccountKey: String? = null

    suspend fun enqueueBook(localBookId: String, fileSha256: String, format: String, title: String) {
        val remote = remoteProvider() ?: return
        val accountKey = remote.activeAccountKey() ?: return
        withContext(Dispatchers.IO) {
            library.enqueueBookUpload(accountKey, localBookId, fileSha256, format, title)
            publishForAccount(accountKey)
        }
    }

    suspend fun retryBook(localBookId: String) {
        val remote = remoteProvider() ?: return
        val accountKey = lastAccountKey ?: remote.activeAccountKey() ?: return
        withContext(Dispatchers.IO) {
            library.retryBookUpload(accountKey, localBookId)
            publishForAccount(accountKey)
        }
        syncOnce()
    }

    suspend fun syncOnce() = syncLock.withLock {
        val remote = remoteProvider() ?: return@withLock
        val fallbackKey = remote.activeAccountKey()
        val capability = try {
            remote.checkCapability()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            UnifiedBookCapability.Offline
        }

        val resolvedAccountKey = when (capability) {
            is UnifiedBookCapability.Supported -> capability.accountKey
            is UnifiedBookCapability.Unsupported -> capability.accountKey
            UnifiedBookCapability.Unauthorized, UnifiedBookCapability.Offline -> fallbackKey
        } ?: run {
            mutableStatuses.value = emptyMap()
            return@withLock
        }

        withContext(Dispatchers.IO) {
            // Re-key local queue items created while offline (under fallbackKey) onto the server's stable accountKey.
            if (fallbackKey != null && fallbackKey != resolvedAccountKey) {
                library.rekeyUploadAccount(fallbackKey, resolvedAccountKey)
            }
            if (lastAccountKey != resolvedAccountKey) {
                library.switchUploadAccount(resolvedAccountKey)
                lastAccountKey = resolvedAccountKey
            } else {
                library.recoverInterruptedBookUploads()
            }
            publishForAccount(resolvedAccountKey)
        }

        val entries = withContext(Dispatchers.IO) { library.uploadEntriesForAccount(resolvedAccountKey) }
        if (entries.isEmpty()) return@withLock

        when (capability) {
            UnifiedBookCapability.Offline -> {
                withContext(Dispatchers.IO) {
                    for (entry in entries.values) {
                        if (entry.serverShelfBookId == null && entry.state in setOf(
                                BookUploadState.WAITING_SERVER, BookUploadState.UPLOADING,
                            )
                        ) {
                            library.updateBookUploadState(resolvedAccountKey, entry.localBookId, BookUploadState.WAITING_NETWORK)
                        }
                    }
                    publishForAccount(resolvedAccountKey)
                }
                return@withLock
            }
            UnifiedBookCapability.Unauthorized -> {
                return@withLock
            }
            is UnifiedBookCapability.Unsupported -> {
                // Rolled-back or old server without unified upload: never fall back to legacy fragments.
                withContext(Dispatchers.IO) {
                    for (entry in entries.values) {
                        if (entry.serverShelfBookId == null && entry.state in setOf(
                                BookUploadState.WAITING_NETWORK, BookUploadState.UPLOADING,
                            )
                        ) {
                            library.updateBookUploadState(resolvedAccountKey, entry.localBookId, BookUploadState.WAITING_SERVER)
                        }
                    }
                    publishForAccount(resolvedAccountKey)
                }
                return@withLock
            }
            is UnifiedBookCapability.Supported -> Unit
        }

        // Upload pending books sequentially; local reading remains completely unblocked.
        for (entry in entries.values) {
            if (entry.paused) continue
            when {
                entry.serverShelfBookId == null && entry.state in setOf(
                    BookUploadState.WAITING_NETWORK, BookUploadState.WAITING_SERVER, BookUploadState.UPLOADING,
                ) -> {
                    val file = library.originalFileIfPresent(entry.localBookId, entry.format)
                    if (file == null || !Files.isRegularFile(file)) {
                        withContext(Dispatchers.IO) {
                            library.updateBookUploadState(
                                resolvedAccountKey,
                                entry.localBookId,
                                BookUploadState.FAILED,
                                errorMessage = "本地原文件已丢失，请重新导入",
                            )
                            publishForAccount(resolvedAccountKey)
                        }
                        continue
                    }
                    withContext(Dispatchers.IO) {
                        library.updateBookUploadState(resolvedAccountKey, entry.localBookId, BookUploadState.UPLOADING, progressPct = 0)
                        publishForAccount(resolvedAccountKey)
                    }
                    val outcome = remote.uploadBook(file, entry.format, entry.title, entry.fileSha256) { pct ->
                        library.updateBookUploadState(resolvedAccountKey, entry.localBookId, BookUploadState.UPLOADING, progressPct = pct)
                        publishForAccount(resolvedAccountKey)
                    }
                    withContext(Dispatchers.IO) {
                        when (outcome) {
                            is UnifiedBookUploadOutcome.Accepted -> {
                                library.updateBookUploadState(
                                    accountKey = resolvedAccountKey,
                                    localBookId = entry.localBookId,
                                    state = outcome.jobState,
                                    progressPct = outcome.progressPct,
                                    stageLabel = outcome.stageLabel,
                                    errorMessage = null,
                                    serverShelfBookId = outcome.shelfBookId,
                                )
                            }
                            UnifiedBookUploadOutcome.UnsupportedServer -> {
                                library.updateBookUploadState(resolvedAccountKey, entry.localBookId, BookUploadState.WAITING_SERVER)
                            }
                            UnifiedBookUploadOutcome.Offline -> {
                                library.updateBookUploadState(resolvedAccountKey, entry.localBookId, BookUploadState.WAITING_NETWORK)
                            }
                            UnifiedBookUploadOutcome.Unauthorized -> {
                                library.updateBookUploadState(resolvedAccountKey, entry.localBookId, BookUploadState.WAITING_NETWORK)
                            }
                            is UnifiedBookUploadOutcome.Failed -> {
                                library.updateBookUploadState(
                                    resolvedAccountKey,
                                    entry.localBookId,
                                    BookUploadState.FAILED,
                                    errorMessage = outcome.message,
                                )
                            }
                        }
                        publishForAccount(resolvedAccountKey)
                    }
                }
                entry.serverShelfBookId != null && entry.state in setOf(
                    BookUploadState.RECEIVED, BookUploadState.QUEUED, BookUploadState.PROCESSING,
                ) -> {
                    val status = remote.fetchStatus(entry.serverShelfBookId)
                    withContext(Dispatchers.IO) {
                        when (status) {
                            is UnifiedBookStatusOutcome.Ready -> {
                                library.updateBookUploadState(
                                    accountKey = resolvedAccountKey,
                                    localBookId = entry.localBookId,
                                    state = status.jobState,
                                    progressPct = status.progressPct,
                                    stageLabel = status.stageLabel,
                                    errorMessage = status.errorMessage,
                                )
                            }
                            UnifiedBookStatusOutcome.NotFound -> {
                                // Book was deleted on server; keep local copy intact and allow re-upload on retry.
                                library.updateBookUploadState(
                                    resolvedAccountKey,
                                    entry.localBookId,
                                    BookUploadState.FAILED,
                                    errorMessage = "服务端记录已移除，可点击重试上传",
                                )
                            }
                            UnifiedBookStatusOutcome.UnsupportedServer,
                            UnifiedBookStatusOutcome.Offline,
                            UnifiedBookStatusOutcome.Unauthorized -> Unit
                        }
                        publishForAccount(resolvedAccountKey)
                    }
                }
            }
        }
    }

    private fun publishForAccount(accountKey: String) {
        mutableStatuses.value = library.uploadEntriesForAccount(accountKey)
    }
}
