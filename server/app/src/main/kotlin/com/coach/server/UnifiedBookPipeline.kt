package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import com.coach.domain.json
import com.coach.store.isSableFriend
import com.coach.store.sha256
import com.coach.store.tutorSourceAllowed
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class UnifiedBookConfig(
    val enabled: Boolean = false,
    val parserVersion: String = DEFAULT_PARSER_VERSION,
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    val accountQuotaBytes: Long = DEFAULT_ACCOUNT_QUOTA_BYTES,
    val minFreeDiskBytes: Long = DEFAULT_MIN_FREE_DISK_BYTES,
    val trashRetentionMs: Long = DEFAULT_TRASH_RETENTION_MS,
    val leaseDurationMs: Long = DEFAULT_LEASE_DURATION_MS,
    val usableSpaceProvider: (Path) -> Long = { path -> Files.getFileStore(path).usableSpace },
    val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(parserVersion.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid parser version" }
        require(maxFileBytes > 0L) { "maxFileBytes must be positive" }
        require(accountQuotaBytes >= maxFileBytes) { "accountQuotaBytes must cover maxFileBytes" }
        require(minFreeDiskBytes >= 0L) { "minFreeDiskBytes must be non-negative" }
        require(trashRetentionMs >= 0L) { "trashRetentionMs must be non-negative" }
        require(leaseDurationMs > 0L) { "leaseDurationMs must be positive" }
    }

    companion object {
        const val DEFAULT_PARSER_VERSION = "v1-queued"
        const val DEFAULT_MAX_FILE_BYTES = 256L * 1024L * 1024L
        const val DEFAULT_ACCOUNT_QUOTA_BYTES = 2L * 1024L * 1024L * 1024L
        const val DEFAULT_MIN_FREE_DISK_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_TRASH_RETENTION_MS = 7L * 24L * 3600L * 1000L
        const val DEFAULT_LEASE_DURATION_MS = 10L * 60L * 1000L

        fun fromEnv(env: (String) -> String? = System::getenv): UnifiedBookConfig {
            val enabledRaw = env("COACH_UNIFIED_BOOKS")?.trim()?.lowercase()
            val enabled = enabledRaw == "1" || enabledRaw == "true"
            val maxFile = env("COACH_UNIFIED_BOOK_MAX_BYTES")?.trim()?.toLongOrNull() ?: DEFAULT_MAX_FILE_BYTES
            val quota = env("COACH_UNIFIED_BOOK_ACCOUNT_QUOTA_BYTES")?.trim()?.toLongOrNull()
                ?: maxOf(DEFAULT_ACCOUNT_QUOTA_BYTES, maxFile)
            val minFree = env("COACH_UNIFIED_BOOK_MIN_FREE_BYTES")?.trim()?.toLongOrNull() ?: DEFAULT_MIN_FREE_DISK_BYTES
            val retention = env("COACH_UNIFIED_BOOK_TRASH_RETENTION_MS")?.trim()?.toLongOrNull() ?: DEFAULT_TRASH_RETENTION_MS
            return UnifiedBookConfig(
                enabled = enabled,
                maxFileBytes = maxFile,
                accountQuotaBytes = quota,
                minFreeDiskBytes = minFree,
                trashRetentionMs = retention,
            )
        }
    }
}

/**
 * Stable isolation scope for unified books:
 * - Sable accounts key off stable `sub` (`sable:<sha256(sub)>`), never username or ephemeral `deviceId`.
 * - Pairing-code-only devices (including owner local devices and manual friend pairings) stay device-scoped
 *   and cannot claim a Sable account's books.
 */
internal data class UnifiedBookScope(
    val scopeKey: String,
    val isSableAccount: Boolean,
) {
    init {
        require(scopeKey.matches(SCOPE_KEY_PATTERN)) { "Invalid unified book scope key" }
    }

    companion object {
        private val SCOPE_KEY_PATTERN = Regex("(?:sable|device)-[0-9a-f]{64}")

        fun fromCall(deviceId: String, friendName: String?): UnifiedBookScope {
            require(deviceId.isNotBlank()) { "Missing device id" }
            return if (friendName != null && isSableFriend(friendName)) {
                val subHash = friendName.substringAfterLast(':')
                UnifiedBookScope("sable-$subHash", isSableAccount = true)
            } else {
                val deviceHash = sha256(deviceId.toByteArray(Charsets.UTF_8))
                UnifiedBookScope("device-$deviceHash", isSableAccount = false)
            }
        }
    }
}

internal data class UnifiedProcessingJob(
    val sha256: String,
    val parserVersion: String,
    val state: String,
    val progressPct: Int,
    val stageLabel: String,
    val errorMessage: String?,
    val publishedVersion: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("sha256", sha256)
        put("parserVersion", parserVersion)
        put("state", state)
        put("progressPct", progressPct)
        put("stageLabel", stageLabel)
        if (errorMessage == null) put("error", JsonNull) else put("error", errorMessage)
        if (publishedVersion == null) put("publishedVersion", JsonNull) else put("publishedVersion", publishedVersion)
    }
}

internal data class UnifiedShelfBook(
    val shelfBookId: String,
    val sha256: String,
    val title: String,
    val format: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val job: UnifiedProcessingJob,
    val deduplicated: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("shelfBookId", shelfBookId)
        put("sha256", sha256)
        put("title", title)
        put("format", format)
        put("sizeBytes", sizeBytes)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("deduplicated", deduplicated)
        put("job", job.toJson())
    }
}

internal class UnifiedBookError(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : RuntimeException(message)

/**
 * Unified server-side book intake, shared content-addressed storage, per-account shelf isolation,
 * reference-counted trash quarantine, and lease-based processing job queue (TON-1823 PR A).
 *
 * PR A intentionally stops at `queued` and never marks books `ready` until TON-1821's shared parser lands.
 */
class UnifiedBookPipeline internal constructor(
    private val root: Path,
    internal val config: UnifiedBookConfig = UnifiedBookConfig(),
) {
    val enabled: Boolean get() = config.enabled

    private val sharedDir get() = root.resolve("shared")
    private val blobsDir get() = sharedDir.resolve("blobs")
    private val artifactsDir get() = sharedDir.resolve("artifacts")
    private val tmpDir get() = sharedDir.resolve("tmp")
    private val accountsDir get() = root.resolve("accounts")
    private val trashDir get() = root.resolve("trash")
    private val registryFile get() = sharedDir.resolve("registry.sqlite")

    private val initLock = Any()
    @Volatile private var initialized = false
    private val shaLocks = ConcurrentHashMap<String, Mutex>()
    private val inFlightShas = ConcurrentHashMap<String, Int>()
    private val quotaLock = Any()
    private val inFlightAccountBytes = HashMap<String, Long>()
    private var totalInFlightBytes = 0L
    private val random = SecureRandom()

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            privateFiles.directory(sharedDir)
            privateFiles.directory(blobsDir)
            privateFiles.directory(artifactsDir)
            privateFiles.directory(tmpDir)
            privateFiles.directory(accountsDir)
            privateFiles.directory(trashDir)
            privateFiles.file(registryFile)
            DriverManager.getConnection("jdbc:sqlite:$registryFile").use { db ->
                db.createStatement().use { sql ->
                    sql.execute("PRAGMA busy_timeout=5000")
                    sql.execute(
                        """CREATE TABLE IF NOT EXISTS shared_blobs(
                            sha256 TEXT PRIMARY KEY,
                            format TEXT NOT NULL,
                            size_bytes INTEGER NOT NULL,
                            state TEXT NOT NULL CHECK(state IN ('ACTIVE','TRASHED')),
                            ref_count INTEGER NOT NULL DEFAULT 0,
                            trashed_at INTEGER,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )""".trimIndent(),
                    )
                    sql.execute(
                        """CREATE TABLE IF NOT EXISTS shared_blob_refs(
                            sha256 TEXT NOT NULL,
                            scope_key TEXT NOT NULL,
                            shelf_book_id TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            PRIMARY KEY(sha256, scope_key)
                        )""".trimIndent(),
                    )
                    sql.execute(
                        """CREATE TABLE IF NOT EXISTS processing_jobs(
                            sha256 TEXT NOT NULL,
                            parser_version TEXT NOT NULL,
                            state TEXT NOT NULL CHECK(state IN ('received','queued','processing','ready','failed')),
                            progress_pct INTEGER NOT NULL DEFAULT 0,
                            stage_label TEXT NOT NULL DEFAULT '',
                            error_message TEXT,
                            lease_owner TEXT,
                            lease_expires_at INTEGER,
                            published_version TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(sha256, parser_version)
                        )""".trimIndent(),
                    )
                    sql.execute("CREATE INDEX IF NOT EXISTS idx_shared_blobs_trash ON shared_blobs(state, trashed_at)")
                    sql.execute("CREATE INDEX IF NOT EXISTS idx_processing_jobs_state ON processing_jobs(state, lease_expires_at)")
                }
            }
            initialized = true
        }
    }

    private fun registryDb(): Connection {
        ensureInitialized()
        return DriverManager.getConnection("jdbc:sqlite:$registryFile").apply {
            createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
        }
    }

    private fun accountShelfDb(scope: UnifiedBookScope): Connection {
        val dir = privateFiles.directory(accountsDir.resolve(scope.scopeKey))
        val file = privateFiles.file(dir.resolve("shelf.sqlite"))
        return DriverManager.getConnection("jdbc:sqlite:$file").apply {
            createStatement().use { sql ->
                sql.execute("PRAGMA busy_timeout=5000")
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS shelf_books(
                        shelf_book_id TEXT PRIMARY KEY,
                        sha256 TEXT NOT NULL UNIQUE,
                        title TEXT NOT NULL,
                        format TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""".trimIndent(),
                )
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS shelf_book_notes(
                        shelf_book_id TEXT NOT NULL,
                        note_id TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(shelf_book_id, note_id)
                    )""".trimIndent(),
                )
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS shelf_book_qa(
                        shelf_book_id TEXT NOT NULL,
                        qa_id TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(shelf_book_id, qa_id)
                    )""".trimIndent(),
                )
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS shelf_book_overrides(
                        shelf_book_id TEXT PRIMARY KEY,
                        toc_override_json TEXT,
                        progress_json TEXT,
                        updated_at INTEGER NOT NULL
                    )""".trimIndent(),
                )
            }
        }
    }

    private fun shaLock(sha256: String): Mutex = shaLocks.computeIfAbsent(sha256) { Mutex() }

    private fun blobPath(sha256: String): Path {
        require(sha256.matches(SHA256_PATTERN)) { "Invalid sha256" }
        return blobsDir.resolve("$sha256.blob")
    }

    private fun artifactDir(sha256: String, parserVersion: String): Path {
        require(sha256.matches(SHA256_PATTERN)) { "Invalid sha256" }
        return artifactsDir.resolve(sha256).resolve(parserVersion)
    }

    private fun trashEntryDir(sha256: String): Path {
        require(sha256.matches(SHA256_PATTERN)) { "Invalid sha256" }
        return trashDir.resolve(sha256)
    }

    private fun accountUsedBytes(scope: UnifiedBookScope): Long {
        val dir = accountsDir.resolve(scope.scopeKey)
        val file = dir.resolve("shelf.sqlite")
        if (!Files.exists(file)) return 0L
        return accountShelfDb(scope).use { db ->
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT COALESCE(SUM(size_bytes), 0) FROM shelf_books").use { row ->
                    if (row.next()) row.getLong(1) else 0L
                }
            }
        }
    }

    private fun reserveSpace(scope: UnifiedBookScope, bytes: Long, previousReserved: Long): Long {
        synchronized(quotaLock) {
            val delta = bytes - previousReserved
            if (delta <= 0L) return previousReserved
            val used = accountUsedBytes(scope)
            val accountInFlight = inFlightAccountBytes[scope.scopeKey] ?: 0L
            if (used + accountInFlight + delta > config.accountQuotaBytes) {
                throw UnifiedBookError(
                    HttpStatusCode.PayloadTooLarge,
                    "account_quota_exceeded",
                    "存储空间不足，请稍后再试",
                )
            }
            val usable = runCatching { config.usableSpaceProvider(sharedDir) }.getOrDefault(0L)
            if (usable - totalInFlightBytes - delta < config.minFreeDiskBytes) {
                throw UnifiedBookError(
                    HttpStatusCode.InsufficientStorage,
                    "disk_space_insufficient",
                    "存储空间不足，请稍后再试",
                )
            }
            inFlightAccountBytes[scope.scopeKey] = accountInFlight + delta
            totalInFlightBytes += delta
            return bytes
        }
    }

    private fun releaseSpace(scope: UnifiedBookScope, reserved: Long) {
        if (reserved <= 0L) return
        synchronized(quotaLock) {
            val current = inFlightAccountBytes[scope.scopeKey] ?: 0L
            val next = (current - reserved).coerceAtLeast(0L)
            if (next == 0L) inFlightAccountBytes.remove(scope.scopeKey) else inFlightAccountBytes[scope.scopeKey] = next
            totalInFlightBytes = (totalInFlightBytes - reserved).coerceAtLeast(0L)
        }
    }

    /**
     * Streams and verifies full file bytes before establishing any shelf record.
     * Client-supplied SHA-256 is treated strictly as an integrity cross-check, never as proof of possession.
     */
    internal suspend fun upload(
        scope: UnifiedBookScope,
        formatRaw: String,
        titleRaw: String,
        declaredSha256: String?,
        declaredLength: Long?,
        bodyChannel: ByteReadChannel,
    ): UnifiedShelfBook = withContext(Dispatchers.IO) {
        val format = formatRaw.trim().lowercase()
        if (format !in SUPPORTED_FORMATS) {
            throw UnifiedBookError(HttpStatusCode.BadRequest, "unsupported_format", "暂不支持这种教材格式。")
        }
        val title = titleRaw.trim()
        if (title.length !in 1..300 || title.any(Char::isISOControl) || !tutorSourceAllowed(title)) {
            throw UnifiedBookError(HttpStatusCode.BadRequest, "invalid_title", "书名无效，请检查后重试。")
        }
        val expectedSha = declaredSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (expectedSha != null && !expectedSha.matches(SHA256_PATTERN)) {
            throw UnifiedBookError(HttpStatusCode.BadRequest, "invalid_sha256", "文件校验值格式不对。")
        }
        if (declaredLength != null) {
            if (declaredLength <= 0L) {
                throw UnifiedBookError(HttpStatusCode.BadRequest, "empty_file", "这份教材是空文件，无法上传。")
            }
            if (declaredLength > config.maxFileBytes) {
                throw UnifiedBookError(HttpStatusCode.PayloadTooLarge, "file_too_large", "文件太大")
            }
        }

        ensureInitialized()
        val tempFile = privateFiles.file(tmpDir.resolve(".upload-${UUID.randomUUID()}.part"))
        var reserved = 0L
        var bytesWritten = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (declaredLength != null) {
                reserved = reserveSpace(scope, declaredLength, 0L)
            }
            Files.newOutputStream(tempFile).use { out ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val n = bodyChannel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    val nextTotal = bytesWritten + n
                    if (nextTotal > config.maxFileBytes) {
                        throw UnifiedBookError(HttpStatusCode.PayloadTooLarge, "file_too_large", "文件太大")
                    }
                    if (nextTotal > reserved) {
                        val targetReserve = minOf(config.maxFileBytes, maxOf(nextTotal, reserved + 256L * 1024L))
                        reserved = reserveSpace(scope, targetReserve, reserved)
                    }
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    bytesWritten = nextTotal
                }
            }
            if (bytesWritten <= 0L) {
                throw UnifiedBookError(HttpStatusCode.BadRequest, "empty_file", "这份教材是空文件，无法上传。")
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha != null && expectedSha != actualSha) {
                throw UnifiedBookError(HttpStatusCode.BadRequest, "sha256_mismatch", "文件内容校验不一致，请重试上传。")
            }

            inFlightShas.merge(actualSha, 1, Int::plus)
            try {
                shaLock(actualSha).withLock {
                    commitUploadedFile(scope, actualSha, format, title, bytesWritten, tempFile)
                }
            } finally {
                inFlightShas.compute(actualSha) { _, count ->
                    val next = (count ?: 1) - 1
                    if (next <= 0) null else next
                }
            }
        } finally {
            runCatching { Files.deleteIfExists(tempFile) }
            releaseSpace(scope, reserved)
        }
    }

    private fun commitUploadedFile(
        scope: UnifiedBookScope,
        sha256: String,
        format: String,
        title: String,
        sizeBytes: Long,
        tempFile: Path,
    ): UnifiedShelfBook {
        val now = config.clock()
        val finalBlob = blobPath(sha256)
        val trashEntry = trashEntryDir(sha256)
        var deduplicated = false

        if (Files.isRegularFile(finalBlob)) {
            deduplicated = true
            runCatching { Files.deleteIfExists(tempFile) }
        } else {
            // Recover from trash quarantine if within retention window, replacing or restoring blob atomically.
            if (Files.exists(trashEntry)) {
                val trashedArtifacts = trashEntry.resolve("artifacts")
                val targetArtifacts = artifactsDir.resolve(sha256)
                if (Files.isDirectory(trashedArtifacts) && !Files.exists(targetArtifacts)) {
                    moveAtomically(trashedArtifacts, targetArtifacts)
                }
                trashEntry.toFile().deleteRecursively()
                deduplicated = true
            }
            moveAtomically(tempFile, finalBlob)
            privateFiles.file(finalBlob)
        }

        val shelfBookId: String
        val createdAt: Long
        accountShelfDb(scope).use { accountDb ->
            accountDb.autoCommit = false
            try {
                val existing = accountDb.prepareStatement(
                    "SELECT shelf_book_id, created_at FROM shelf_books WHERE sha256=?",
                ).use { sql ->
                    sql.setString(1, sha256)
                    sql.executeQuery().use { row ->
                        if (row.next()) row.getString(1) to row.getLong(2) else null
                    }
                }
                if (existing != null) {
                    shelfBookId = existing.first
                    createdAt = existing.second
                    accountDb.prepareStatement(
                        "UPDATE shelf_books SET title=?, format=?, size_bytes=?, updated_at=? WHERE shelf_book_id=?",
                    ).use { sql ->
                        sql.setString(1, title)
                        sql.setString(2, format)
                        sql.setLong(3, sizeBytes)
                        sql.setLong(4, now)
                        sql.setString(5, shelfBookId)
                        sql.executeUpdate()
                    }
                } else {
                    shelfBookId = newShelfBookId()
                    createdAt = now
                    accountDb.prepareStatement(
                        """INSERT INTO shelf_books(shelf_book_id, sha256, title, format, size_bytes, created_at, updated_at)
                           VALUES(?,?,?,?,?,?,?)""".trimIndent(),
                    ).use { sql ->
                        sql.setString(1, shelfBookId)
                        sql.setString(2, sha256)
                        sql.setString(3, title)
                        sql.setString(4, format)
                        sql.setLong(5, sizeBytes)
                        sql.setLong(6, now)
                        sql.setLong(7, now)
                        sql.executeUpdate()
                    }
                }
                accountDb.commit()
            } catch (e: Exception) {
                accountDb.rollback()
                throw e
            }
        }

        val job: UnifiedProcessingJob
        registryDb().use { db ->
            db.autoCommit = false
            try {
                val existingJob = readJob(db, sha256, config.parserVersion)
                if (existingJob != null) deduplicated = true
                db.prepareStatement(
                    """INSERT INTO shared_blobs(sha256, format, size_bytes, state, ref_count, trashed_at, created_at, updated_at)
                       VALUES(?, ?, ?, 'ACTIVE', 0, NULL, ?, ?)
                       ON CONFLICT(sha256) DO UPDATE SET
                           state='ACTIVE',
                           trashed_at=NULL,
                           size_bytes=excluded.size_bytes,
                           updated_at=excluded.updated_at""".trimIndent(),
                ).use { sql ->
                    sql.setString(1, sha256)
                    sql.setString(2, format)
                    sql.setLong(3, sizeBytes)
                    sql.setLong(4, now)
                    sql.setLong(5, now)
                    sql.executeUpdate()
                }
                db.prepareStatement(
                    """INSERT INTO shared_blob_refs(sha256, scope_key, shelf_book_id, created_at)
                       VALUES(?, ?, ?, ?)
                       ON CONFLICT(sha256, scope_key) DO UPDATE SET shelf_book_id=excluded.shelf_book_id""".trimIndent(),
                ).use { sql ->
                    sql.setString(1, sha256)
                    sql.setString(2, scope.scopeKey)
                    sql.setString(3, shelfBookId)
                    sql.setLong(4, now)
                    sql.executeUpdate()
                }
                val activeRefs = countRefs(db, sha256)
                db.prepareStatement("UPDATE shared_blobs SET ref_count=?, updated_at=? WHERE sha256=?").use { sql ->
                    sql.setInt(1, activeRefs)
                    sql.setLong(2, now)
                    sql.setString(3, sha256)
                    sql.executeUpdate()
                }
                // PR A stops at queued and never marks books ready until TON-1821 parser is connected.
                db.prepareStatement(
                    """INSERT OR IGNORE INTO processing_jobs(
                           sha256, parser_version, state, progress_pct, stage_label,
                           error_message, lease_owner, lease_expires_at, published_version, created_at, updated_at
                       ) VALUES(?, ?, 'queued', 0, '已排队，等待整理', NULL, NULL, NULL, NULL, ?, ?)""".trimIndent(),
                ).use { sql ->
                    sql.setString(1, sha256)
                    sql.setString(2, config.parserVersion)
                    sql.setLong(3, now)
                    sql.setLong(4, now)
                    sql.executeUpdate()
                }
                job = checkNotNull(readJob(db, sha256, config.parserVersion))
                db.commit()
            } catch (e: Exception) {
                db.rollback()
                throw e
            }
        }

        return UnifiedShelfBook(
            shelfBookId = shelfBookId,
            sha256 = sha256,
            title = title,
            format = format,
            sizeBytes = sizeBytes,
            createdAt = createdAt,
            updatedAt = now,
            job = job,
            deduplicated = deduplicated,
        )
    }

    internal fun listShelfBooks(scope: UnifiedBookScope): List<UnifiedShelfBook> {
        val dir = accountsDir.resolve(scope.scopeKey)
        if (!Files.exists(dir.resolve("shelf.sqlite"))) return emptyList()
        val rows = accountShelfDb(scope).use { accountDb ->
            accountDb.prepareStatement(
                "SELECT shelf_book_id, sha256, title, format, size_bytes, created_at, updated_at FROM shelf_books ORDER BY updated_at DESC, shelf_book_id",
            ).use { sql ->
                sql.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ShelfRow(
                                    shelfBookId = rs.getString(1),
                                    sha256 = rs.getString(2),
                                    title = rs.getString(3),
                                    format = rs.getString(4),
                                    sizeBytes = rs.getLong(5),
                                    createdAt = rs.getLong(6),
                                    updatedAt = rs.getLong(7),
                                ),
                            )
                        }
                    }
                }
            }
        }
        if (rows.isEmpty()) return emptyList()
        return registryDb().use { db ->
            rows.map { row ->
                val job = readJob(db, row.sha256, config.parserVersion)
                    ?: UnifiedProcessingJob(row.sha256, config.parserVersion, "queued", 0, "已排队，等待整理", null, null)
                UnifiedShelfBook(
                    shelfBookId = row.shelfBookId,
                    sha256 = row.sha256,
                    title = row.title,
                    format = row.format,
                    sizeBytes = row.sizeBytes,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    job = job,
                )
            }
        }
    }

    internal fun shelfBookStatus(scope: UnifiedBookScope, shelfBookId: String): UnifiedShelfBook? {
        if (!shelfBookId.matches(SHELF_BOOK_ID_PATTERN)) return null
        val dir = accountsDir.resolve(scope.scopeKey)
        if (!Files.exists(dir.resolve("shelf.sqlite"))) return null
        val row = accountShelfDb(scope).use { accountDb ->
            accountDb.prepareStatement(
                "SELECT shelf_book_id, sha256, title, format, size_bytes, created_at, updated_at FROM shelf_books WHERE shelf_book_id=?",
            ).use { sql ->
                sql.setString(1, shelfBookId)
                sql.executeQuery().use { rs ->
                    if (!rs.next()) null else ShelfRow(
                        shelfBookId = rs.getString(1),
                        sha256 = rs.getString(2),
                        title = rs.getString(3),
                        format = rs.getString(4),
                        sizeBytes = rs.getLong(5),
                        createdAt = rs.getLong(6),
                        updatedAt = rs.getLong(7),
                    )
                }
            }
        } ?: return null
        val job = registryDb().use { db ->
            readJob(db, row.sha256, config.parserVersion)
                ?: UnifiedProcessingJob(row.sha256, config.parserVersion, "queued", 0, "已排队，等待整理", null, null)
        }
        return UnifiedShelfBook(
            shelfBookId = row.shelfBookId,
            sha256 = row.sha256,
            title = row.title,
            format = row.format,
            sizeBytes = row.sizeBytes,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
            job = job,
        )
    }

    /**
     * Checks whether [scope] holds an active verified shelf record for [bookIdOrSha] and the shared blob is active.
     * Used by AI routes to authorize unified books without colliding with legacy catalog 409 reservations.
     */
    internal fun hasVerifiedShelfBook(scope: UnifiedBookScope, bookIdOrSha: String): Boolean {
        val dir = accountsDir.resolve(scope.scopeKey)
        if (!Files.exists(dir.resolve("shelf.sqlite"))) return false
        val sha = accountShelfDb(scope).use { accountDb ->
            accountDb.prepareStatement(
                "SELECT sha256 FROM shelf_books WHERE sha256=? OR shelf_book_id=?",
            ).use { sql ->
                sql.setString(1, bookIdOrSha)
                sql.setString(2, bookIdOrSha)
                sql.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        } ?: return false
        if (!Files.isRegularFile(blobPath(sha))) return false
        return registryDb().use { db ->
            db.prepareStatement(
                """SELECT 1 FROM shared_blobs b
                   JOIN shared_blob_refs r ON r.sha256 = b.sha256
                   WHERE b.sha256=? AND b.state='ACTIVE' AND r.scope_key=?""".trimIndent(),
            ).use { sql ->
                sql.setString(1, sha)
                sql.setString(2, scope.scopeKey)
                sql.executeQuery().use { it.next() }
            }
        }
    }

    /**
     * Deletes a book from [scope]'s shelf and cleans account-owned records (notes/Q&A/overrides).
     * Shared blob refcount is updated idempotently under the per-SHA lock; when refcount reaches 0,
     * the shared original and derived artifacts are moved to the quarantine trash directory.
     */
    internal suspend fun deleteShelfBook(scope: UnifiedBookScope, shelfBookId: String): Boolean = withContext(Dispatchers.IO) {
        if (!shelfBookId.matches(SHELF_BOOK_ID_PATTERN)) return@withContext false
        val dir = accountsDir.resolve(scope.scopeKey)
        if (!Files.exists(dir.resolve("shelf.sqlite"))) return@withContext false
        val sha = accountShelfDb(scope).use { accountDb ->
            accountDb.prepareStatement("SELECT sha256 FROM shelf_books WHERE shelf_book_id=?").use { sql ->
                sql.setString(1, shelfBookId)
                sql.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        } ?: return@withContext false

        shaLock(sha).withLock {
            val deletedFromShelf = accountShelfDb(scope).use { accountDb ->
                accountDb.autoCommit = false
                try {
                    for (table in listOf("shelf_book_notes", "shelf_book_qa", "shelf_book_overrides")) {
                        accountDb.prepareStatement("DELETE FROM $table WHERE shelf_book_id=?").use { sql ->
                            sql.setString(1, shelfBookId)
                            sql.executeUpdate()
                        }
                    }
                    val count = accountDb.prepareStatement("DELETE FROM shelf_books WHERE shelf_book_id=?").use { sql ->
                        sql.setString(1, shelfBookId)
                        sql.executeUpdate()
                    }
                    accountDb.commit()
                    count > 0
                } catch (e: Exception) {
                    accountDb.rollback()
                    throw e
                }
            }
            if (!deletedFromShelf) return@withLock false

            val now = config.clock()
            val shouldQuarantine = registryDb().use { db ->
                db.autoCommit = false
                try {
                    db.prepareStatement("DELETE FROM shared_blob_refs WHERE sha256=? AND scope_key=?").use { sql ->
                        sql.setString(1, sha)
                        sql.setString(2, scope.scopeKey)
                        sql.executeUpdate()
                    }
                    val remaining = countRefs(db, sha)
                    val quarantine = remaining == 0 && (inFlightShas[sha] ?: 0) == 0
                    if (quarantine) {
                        db.prepareStatement(
                            "UPDATE shared_blobs SET ref_count=0, state='TRASHED', trashed_at=?, updated_at=? WHERE sha256=?",
                        ).use { sql ->
                            sql.setLong(1, now)
                            sql.setLong(2, now)
                            sql.setString(3, sha)
                            sql.executeUpdate()
                        }
                    } else {
                        db.prepareStatement(
                            "UPDATE shared_blobs SET ref_count=?, updated_at=? WHERE sha256=?",
                        ).use { sql ->
                            sql.setInt(1, remaining)
                            sql.setLong(2, now)
                            sql.setString(3, sha)
                            sql.executeUpdate()
                        }
                    }
                    db.commit()
                    quarantine
                } catch (e: Exception) {
                    db.rollback()
                    throw e
                }
            }

            if (shouldQuarantine) {
                quarantineSharedFiles(sha)
            }
            true
        }
    }

    private fun quarantineSharedFiles(sha256: String) {
        val entry = privateFiles.directory(trashEntryDir(sha256))
        val blob = blobPath(sha256)
        if (Files.isRegularFile(blob)) {
            moveAtomically(blob, entry.resolve("blob"))
        }
        val shaArtifacts = artifactsDir.resolve(sha256)
        if (Files.isDirectory(shaArtifacts)) {
            val dest = entry.resolve("artifacts")
            if (Files.exists(dest)) dest.toFile().deleteRecursively()
            moveAtomically(shaArtifacts, dest)
        }
    }

    /**
     * Purges quarantined blobs whose retention period has elapsed, re-checking under the per-SHA lock
     * that ref_count == 0, no upload is in flight, and no processing lease is active.
     */
    internal suspend fun purgeExpiredTrash(): Int = withContext(Dispatchers.IO) {
        val now = config.clock()
        val cutoff = now - config.trashRetentionMs
        val candidates = registryDb().use { db ->
            db.prepareStatement(
                "SELECT sha256 FROM shared_blobs WHERE state='TRASHED' AND ref_count=0 AND trashed_at IS NOT NULL AND trashed_at <= ?",
            ).use { sql ->
                sql.setLong(1, cutoff)
                sql.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
        var purged = 0
        for (sha in candidates) {
            shaLock(sha).withLock {
                if ((inFlightShas[sha] ?: 0) > 0) return@withLock
                val eligible = registryDb().use { db ->
                    db.autoCommit = false
                    try {
                        val refs = countRefs(db, sha)
                        val activeLease = hasActiveLease(db, sha, config.clock())
                        val stillExpired = db.prepareStatement(
                            "SELECT 1 FROM shared_blobs WHERE sha256=? AND state='TRASHED' AND trashed_at IS NOT NULL AND trashed_at <= ?",
                        ).use { sql ->
                            sql.setString(1, sha)
                            sql.setLong(2, config.clock() - config.trashRetentionMs)
                            sql.executeQuery().use { it.next() }
                        }
                        if (refs == 0 && !activeLease && stillExpired) {
                            db.prepareStatement("DELETE FROM processing_jobs WHERE sha256=?").use { sql ->
                                sql.setString(1, sha)
                                sql.executeUpdate()
                            }
                            db.prepareStatement("DELETE FROM shared_blobs WHERE sha256=?").use { sql ->
                                sql.setString(1, sha)
                                sql.executeUpdate()
                            }
                            db.commit()
                            true
                        } else {
                            db.rollback()
                            false
                        }
                    } catch (e: Exception) {
                        db.rollback()
                        throw e
                    }
                }
                if (eligible) {
                    val entry = trashEntryDir(sha)
                    if (Files.exists(entry)) entry.toFile().deleteRecursively()
                    val leftoverArtifacts = artifactsDir.resolve(sha)
                    if (Files.exists(leftoverArtifacts)) leftoverArtifacts.toFile().deleteRecursively()
                    purged++
                }
            }
        }
        purged
    }

    /**
     * Lease-based job claim for future TON-1821 parser workers: claims either a `queued` job
     * or a `processing` job whose lease has expired.
     */
    internal fun claimNextJob(workerId: String): UnifiedProcessingJob? {
        require(workerId.isNotBlank())
        val now = config.clock()
        val leaseUntil = now + config.leaseDurationMs
        return registryDb().use { db ->
            db.autoCommit = false
            try {
                val candidate = db.prepareStatement(
                    """SELECT sha256, parser_version FROM processing_jobs
                       WHERE state='queued' OR (state='processing' AND (lease_expires_at IS NULL OR lease_expires_at <= ?))
                       ORDER BY updated_at ASC LIMIT 1""".trimIndent(),
                ).use { sql ->
                    sql.setLong(1, now)
                    sql.executeQuery().use { rs ->
                        if (rs.next()) rs.getString(1) to rs.getString(2) else null
                    }
                } ?: run {
                    db.rollback()
                    return@use null
                }
                db.prepareStatement(
                    """UPDATE processing_jobs
                       SET state='processing', lease_owner=?, lease_expires_at=?, updated_at=?
                       WHERE sha256=? AND parser_version=?""".trimIndent(),
                ).use { sql ->
                    sql.setString(1, workerId)
                    sql.setLong(2, leaseUntil)
                    sql.setLong(3, now)
                    sql.setString(4, candidate.first)
                    sql.setString(5, candidate.second)
                    sql.executeUpdate()
                }
                val claimed = readJob(db, candidate.first, candidate.second)
                db.commit()
                claimed
            } catch (e: Exception) {
                db.rollback()
                throw e
            }
        }
    }

    internal fun sharedBlobCount(): Int = registryDb().use { db ->
        db.createStatement().use { sql ->
            sql.executeQuery("SELECT COUNT(*) FROM shared_blobs WHERE state='ACTIVE'").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    internal fun sharedJobCount(sha256: String): Int = registryDb().use { db ->
        db.prepareStatement("SELECT COUNT(*) FROM processing_jobs WHERE sha256=?").use { sql ->
            sql.setString(1, sha256)
            sql.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    internal fun sharedRefCount(sha256: String): Int = registryDb().use { db -> countRefs(db, sha256) }

    internal fun isTrashed(sha256: String): Boolean = Files.exists(trashEntryDir(sha256).resolve("blob"))

    internal fun saveAccountNoteForTest(scope: UnifiedBookScope, shelfBookId: String, noteId: String, payloadJson: String) {
        accountShelfDb(scope).use { db ->
            db.prepareStatement(
                "INSERT OR REPLACE INTO shelf_book_notes(shelf_book_id, note_id, payload_json, updated_at) VALUES(?,?,?,?)",
            ).use { sql ->
                sql.setString(1, shelfBookId)
                sql.setString(2, noteId)
                sql.setString(3, payloadJson)
                sql.setLong(4, config.clock())
                sql.executeUpdate()
            }
        }
    }

    internal fun accountNoteCount(scope: UnifiedBookScope, shelfBookId: String): Int {
        val file = accountsDir.resolve(scope.scopeKey).resolve("shelf.sqlite")
        if (!Files.exists(file)) return 0
        return accountShelfDb(scope).use { db ->
            db.prepareStatement("SELECT COUNT(*) FROM shelf_book_notes WHERE shelf_book_id=?").use { sql ->
                sql.setString(1, shelfBookId)
                sql.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    private fun countRefs(db: Connection, sha256: String): Int =
        db.prepareStatement("SELECT COUNT(*) FROM shared_blob_refs WHERE sha256=?").use { sql ->
            sql.setString(1, sha256)
            sql.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun hasActiveLease(db: Connection, sha256: String, now: Long): Boolean =
        db.prepareStatement(
            "SELECT 1 FROM processing_jobs WHERE sha256=? AND state='processing' AND lease_expires_at IS NOT NULL AND lease_expires_at > ?",
        ).use { sql ->
            sql.setString(1, sha256)
            sql.setLong(2, now)
            sql.executeQuery().use { it.next() }
        }

    private fun readJob(db: Connection, sha256: String, parserVersion: String): UnifiedProcessingJob? =
        db.prepareStatement(
            """SELECT sha256, parser_version, state, progress_pct, stage_label, error_message, published_version
               FROM processing_jobs WHERE sha256=? AND parser_version=?""".trimIndent(),
        ).use { sql ->
            sql.setString(1, sha256)
            sql.setString(2, parserVersion)
            sql.executeQuery().use { rs ->
                if (!rs.next()) null else UnifiedProcessingJob(
                    sha256 = rs.getString(1),
                    parserVersion = rs.getString(2),
                    state = rs.getString(3),
                    progressPct = rs.getInt(4),
                    stageLabel = rs.getString(5),
                    errorMessage = rs.getString(6),
                    publishedVersion = rs.getString(7),
                )
            }
        }

    private fun newShelfBookId(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return "sb_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class ShelfRow(
        val shelfBookId: String,
        val sha256: String,
        val title: String,
        val format: String,
        val sizeBytes: Long,
        val createdAt: Long,
        val updatedAt: Long,
    )

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        internal val SHELF_BOOK_ID_PATTERN = Regex("sb_[0-9a-f]{24}")
        private val SUPPORTED_FORMATS = setOf(
            "pdf", "epub", "md", "txt", "docx", "pptx", "html", "mobi", "azw3", "rtf", "odt", "png", "jpg", "srt", "vtt",
        )
    }
}

fun Application.unifiedBookRoutes(
    actor: LedgerActor,
    unifiedBooks: UnifiedBookPipeline? = null,
    friends: FriendAccess? = null,
) {
    routing {
        post("/v1/books/upload") {
            val pipeline = unifiedBooks?.takeIf { it.enabled }
                ?: return@post call.respondText("", status = HttpStatusCode.NotFound)
            val device = call.attributes[observedDevice]
            val friend = call.attributes.getOrNull(observedFriend)
            val bearer = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ").orEmpty()
            if (friend != null && !actor.execute { authorize(device, bearer) && friendName(device) == friend }) {
                return@post call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            }
            val scope = UnifiedBookScope.fromCall(device, friend)
            val format = call.request.header("X-Coach-Book-Format").orEmpty()
            val encodedTitle = call.request.header("X-Coach-Book-Title").orEmpty()
            val title = runCatching { URLDecoder.decode(encodedTitle, StandardCharsets.UTF_8) }.getOrDefault(encodedTitle)
            val declaredSha = call.request.header("X-Coach-Book-Sha256")
            val contentLength = call.request.header(HttpHeaders.ContentLength)?.trim()?.toLongOrNull()
            try {
                val shelfBook = pipeline.upload(
                    scope = scope,
                    formatRaw = format,
                    titleRaw = title,
                    declaredSha256 = declaredSha,
                    declaredLength = contentLength,
                    bodyChannel = call.receiveChannel(),
                )
                call.respondText(shelfBook.toJson().toString(), ContentType.Application.Json)
            } catch (e: UnifiedBookError) {
                call.respondText(
                    json("code" to e.code, "error" to e.message).toString(),
                    ContentType.Application.Json,
                    e.status,
                )
            }
        }

        get("/v1/books") {
            val pipeline = unifiedBooks?.takeIf { it.enabled }
                ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
            val device = call.attributes[observedDevice]
            val friend = call.attributes.getOrNull(observedFriend)
            val scope = UnifiedBookScope.fromCall(device, friend)
            val books = withContext(Dispatchers.IO) { pipeline.listShelfBooks(scope) }
            call.respondText(
                json("books" to JsonArray(books.map { it.toJson() })).toString(),
                ContentType.Application.Json,
            )
        }

        get("/v1/books/{id}/status") {
            val pipeline = unifiedBooks?.takeIf { it.enabled }
                ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
            val device = call.attributes[observedDevice]
            val friend = call.attributes.getOrNull(observedFriend)
            val scope = UnifiedBookScope.fromCall(device, friend)
            val shelfBookId = call.parameters["id"].orEmpty()
            val status = withContext(Dispatchers.IO) { pipeline.shelfBookStatus(scope, shelfBookId) }
                ?: return@get call.respondText(
                    json("error" to "书架中找不到这本书。").toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            call.respondText(status.toJson().toString(), ContentType.Application.Json)
        }

        delete("/v1/books/{id}") {
            val pipeline = unifiedBooks?.takeIf { it.enabled }
                ?: return@delete call.respondText("", status = HttpStatusCode.NotFound)
            val device = call.attributes[observedDevice]
            val friend = call.attributes.getOrNull(observedFriend)
            val bearer = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ").orEmpty()
            if (friend != null && !actor.execute { authorize(device, bearer) && friendName(device) == friend }) {
                return@delete call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            }
            val scope = UnifiedBookScope.fromCall(device, friend)
            val shelfBookId = call.parameters["id"].orEmpty()
            val deleted = pipeline.deleteShelfBook(scope, shelfBookId)
            if (!deleted) {
                return@delete call.respondText(
                    json("error" to "书架中找不到这本书。").toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            }
            call.respondText(
                json("deleted" to true, "shelfBookId" to shelfBookId).toString(),
                ContentType.Application.Json,
            )
        }
    }
}
