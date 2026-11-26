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
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class UnifiedBookConfig(
    val enabled: Boolean = false,
    val parserVersion: String = DEFAULT_PARSER_VERSION,
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    val accountQuotaBytes: Long = DEFAULT_ACCOUNT_QUOTA_BYTES,
    val minFreeDiskBytes: Long = DEFAULT_MIN_FREE_DISK_BYTES,
    val leaseDurationMs: Long = DEFAULT_LEASE_DURATION_MS,
    val trashRetentionMs: Long = DEFAULT_TRASH_RETENTION_MS,
    val autoProcess: Boolean = true,
    val usableSpaceProvider: (Path) -> Long = { path -> Files.getFileStore(path).usableSpace },
    val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(parserVersion.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid parser version" }
        require(maxFileBytes > 0L) { "maxFileBytes must be positive" }
        require(accountQuotaBytes >= maxFileBytes) { "accountQuotaBytes must cover maxFileBytes" }
        require(minFreeDiskBytes >= 0L) { "minFreeDiskBytes must be non-negative" }
        require(leaseDurationMs > 0L) { "leaseDurationMs must be positive" }
        require(trashRetentionMs >= 0L) { "trashRetentionMs must be non-negative" }
    }

    companion object {
        const val DEFAULT_PARSER_VERSION = "v2-skeleton"
        const val DEFAULT_MAX_FILE_BYTES = 256L * 1024L * 1024L
        const val DEFAULT_ACCOUNT_QUOTA_BYTES = 2L * 1024L * 1024L * 1024L
        const val DEFAULT_MIN_FREE_DISK_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_LEASE_DURATION_MS = 10L * 60L * 1000L
        const val DEFAULT_TRASH_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

        fun fromEnv(env: (String) -> String? = System::getenv): UnifiedBookConfig {
            val enabledRaw = env("COACH_UNIFIED_BOOKS")?.trim()?.lowercase()
            val enabled = enabledRaw == "1" || enabledRaw == "true"
            val maxFile = env("COACH_UNIFIED_BOOK_MAX_BYTES")?.trim()?.toLongOrNull() ?: DEFAULT_MAX_FILE_BYTES
            val quota = env("COACH_UNIFIED_BOOK_ACCOUNT_QUOTA_BYTES")?.trim()?.toLongOrNull()
                ?: maxOf(DEFAULT_ACCOUNT_QUOTA_BYTES, maxFile)
            val minFree = env("COACH_UNIFIED_BOOK_MIN_FREE_BYTES")?.trim()?.toLongOrNull() ?: DEFAULT_MIN_FREE_DISK_BYTES
            val trashRetention = env("COACH_UNIFIED_BOOK_TRASH_RETENTION_MS")?.trim()?.toLongOrNull()
                ?: DEFAULT_TRASH_RETENTION_MS
            return UnifiedBookConfig(
                enabled = enabled,
                maxFileBytes = maxFile,
                accountQuotaBytes = quota,
                minFreeDiskBytes = minFree,
                trashRetentionMs = trashRetention,
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
        internal val SCOPE_KEY_PATTERN = Regex("(?:sable|device)-[0-9a-f]{64}")

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
    val processCount: Int = 0,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("sha256", sha256)
        put("parserVersion", parserVersion)
        put("state", state)
        put("progressPct", progressPct)
        put("stageLabel", stageLabel)
        put("processCount", processCount)
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
 * lease-based skeleton processing worker, original-file pullback, and crash-safe delete/account cleanup.
 */
class UnifiedBookPipeline internal constructor(
    private val root: Path,
    internal val config: UnifiedBookConfig = UnifiedBookConfig(),
    private val processor: UnifiedBookProcessor = DefaultUnifiedBookProcessor(),
) {
    constructor(root: Path, enabled: Boolean, autoProcess: Boolean = true) : this(
        root = root,
        config = UnifiedBookConfig(enabled = enabled, autoProcess = autoProcess),
    )

    val enabled: Boolean get() = config.enabled

    fun processCountForSha(sha256: String): Int = processCount(sha256)

    internal val rootDir: Path get() = root
    internal val blobsDirPath: Path get() = blobsDir
    internal val accountsDirPath: Path get() = accountsDir
    internal val registryFilePath: Path get() = registryFile

    private val sharedDir get() = root.resolve("shared")
    private val blobsDir get() = sharedDir.resolve("blobs")
    private val artifactsDir get() = sharedDir.resolve("artifacts")
    private val tmpDir get() = sharedDir.resolve("tmp")
    private val trashDir get() = sharedDir.resolve("trash")
    private val trashBlobsDir get() = trashDir.resolve("blobs")
    private val trashArtifactsRoot get() = trashDir.resolve("artifacts")
    private val accountsDir get() = root.resolve("accounts")
    private val registryFile get() = sharedDir.resolve("registry.sqlite")

    private val initLock = Any()
    @Volatile private var initialized = false
    private val shaLocks = ConcurrentHashMap<String, Mutex>()
    private val quotaLock = Any()
    private val inFlightAccountBytes = HashMap<String, Long>()
    private var totalInFlightBytes = 0L
    private val random = SecureRandom()
    private val workerStarted = AtomicBoolean(false)
    private val workerWake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var workerJob: Job? = null

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            privateFiles.directory(sharedDir)
            privateFiles.directory(blobsDir)
            privateFiles.directory(artifactsDir)
            privateFiles.directory(tmpDir)
            privateFiles.directory(trashDir)
            privateFiles.directory(trashBlobsDir)
            privateFiles.directory(trashArtifactsRoot)
            privateFiles.directory(accountsDir)
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
                    migrateSharedBlobsIfNeeded(db)
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
                        """CREATE TABLE IF NOT EXISTS pending_book_deletes(
                            scope_key TEXT NOT NULL,
                            shelf_book_id TEXT NOT NULL,
                            sha256 TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            PRIMARY KEY(scope_key, shelf_book_id)
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
                            process_count INTEGER NOT NULL DEFAULT 0,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(sha256, parser_version)
                        )""".trimIndent(),
                    )
                    migrateProcessingJobsIfNeeded(db)
                    sql.execute("CREATE INDEX IF NOT EXISTS idx_processing_jobs_state ON processing_jobs(state, lease_expires_at)")
                }
            }
            initialized = true
        }
    }

    private fun migrateProcessingJobsIfNeeded(db: Connection) {
        val hasProcessCount = db.createStatement().use { sql ->
            sql.executeQuery("PRAGMA table_info(processing_jobs)").use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString(2) == "process_count") {
                        found = true
                        break
                    }
                }
                found
            }
        }
        if (!hasProcessCount) {
            db.createStatement().use { sql ->
                sql.execute("ALTER TABLE processing_jobs ADD COLUMN process_count INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    private fun migrateSharedBlobsIfNeeded(db: Connection) {
        val ddl = db.prepareStatement(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='shared_blobs'",
        ).use { sql -> sql.executeQuery().use { if (it.next()) it.getString(1).orEmpty() else "" } }
        if ("'TRASHED'" in ddl && "trashed_at" in ddl) return
        db.autoCommit = false
        try {
            db.createStatement().use { sql ->
                sql.execute("DROP TABLE IF EXISTS shared_blobs_v1_old")
                sql.execute("ALTER TABLE shared_blobs RENAME TO shared_blobs_v1_old")
                sql.execute(
                    """CREATE TABLE shared_blobs(
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
                    """INSERT INTO shared_blobs(sha256, format, size_bytes, state, ref_count, trashed_at, created_at, updated_at)
                       SELECT sha256, format, size_bytes, state, ref_count, NULL, created_at, updated_at
                       FROM shared_blobs_v1_old""".trimIndent(),
                )
                sql.execute("DROP TABLE shared_blobs_v1_old")
            }
            db.commit()
        } catch (e: Exception) {
            runCatching { db.rollback() }
            throw e
        } finally {
            db.autoCommit = true
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
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS legacy_book_mappings(
                        legacy_record_id TEXT PRIMARY KEY,
                        source_kind TEXT NOT NULL,
                        legacy_doc_id TEXT NOT NULL,
                        course_id TEXT NOT NULL,
                        explain_source_id TEXT,
                        pages INTEGER NOT NULL,
                        printed_page_offset INTEGER,
                        pages_sha256 TEXT,
                        sha256 TEXT NOT NULL,
                        shelf_book_id TEXT NOT NULL,
                        migrated_at INTEGER NOT NULL
                    )""".trimIndent(),
                )
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS legacy_receipt_alignments(
                        legacy_receipt_id TEXT NOT NULL,
                        source_kind TEXT NOT NULL,
                        shelf_book_id TEXT NOT NULL,
                        book_sha256 TEXT NOT NULL,
                        page INTEGER NOT NULL,
                        start_utf16 INTEGER NOT NULL,
                        end_utf16 INTEGER NOT NULL,
                        snapshot_sha256 TEXT NOT NULL,
                        snapshot_length INTEGER NOT NULL,
                        snapshot_ocr INTEGER NOT NULL,
                        aligned_at INTEGER NOT NULL,
                        PRIMARY KEY(legacy_receipt_id, page, start_utf16, end_utf16)
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

    private fun trashBlobPath(sha256: String): Path {
        require(sha256.matches(SHA256_PATTERN)) { "Invalid sha256" }
        return trashBlobsDir.resolve("$sha256.blob")
    }

    private fun artifactDir(sha256: String, parserVersion: String): Path {
        require(sha256.matches(SHA256_PATTERN)) { "Invalid sha256" }
        return artifactsDir.resolve(sha256).resolve(parserVersion)
    }

    private fun trashArtifactsDir(sha256: String): Path {
        require(sha256.matches(SHA256_PATTERN)) { "Invalid sha256" }
        return trashArtifactsRoot.resolve(sha256)
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

            shaLock(actualSha).withLock {
                commitUploadedFile(scope, actualSha, format, title, bytesWritten, tempFile)
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
        var deduplicated = false

        if (Files.isRegularFile(finalBlob)) {
            deduplicated = true
            runCatching { Files.deleteIfExists(tempFile) }
        } else {
            moveAtomically(tempFile, finalBlob)
            privateFiles.file(finalBlob)
        }
        val activeArtifactsRoot = artifactsDir.resolve(sha256)
        val trashedArtifactsRoot = trashArtifactsDir(sha256)
        if (!Files.exists(activeArtifactsRoot) && Files.exists(trashedArtifactsRoot)) {
            privateFiles.directory(artifactsDir)
            runCatching { moveAtomically(trashedArtifactsRoot, activeArtifactsRoot) }
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
                db.prepareStatement(
                    "DELETE FROM pending_book_deletes WHERE scope_key=? AND shelf_book_id=?",
                ).use { sql ->
                    sql.setString(1, scope.scopeKey)
                    sql.setString(2, shelfBookId)
                    sql.executeUpdate()
                }
                val activeRefs = countRefs(db, sha256)
                db.prepareStatement("UPDATE shared_blobs SET ref_count=?, updated_at=? WHERE sha256=?").use { sql ->
                    sql.setInt(1, activeRefs)
                    sql.setLong(2, now)
                    sql.setString(3, sha256)
                    sql.executeUpdate()
                }
                // Insert initial queued job only if this (sha256, parser_version) has not been queued/processed yet.
                db.prepareStatement(
                    """INSERT OR IGNORE INTO processing_jobs(
                           sha256, parser_version, state, progress_pct, stage_label,
                           error_message, lease_owner, lease_expires_at, published_version, process_count, created_at, updated_at
                       ) VALUES(?, ?, 'queued', 0, '已排队，等待整理', NULL, NULL, NULL, NULL, 0, ?, ?)""".trimIndent(),
                ).use { sql ->
                    sql.setString(1, sha256)
                    sql.setString(2, config.parserVersion)
                    sql.setLong(3, now)
                    sql.setLong(4, now)
                    sql.executeUpdate()
                }
                val currentJob = checkNotNull(readJob(db, sha256, config.parserVersion))
                if (currentJob.state == "ready" && !Files.isRegularFile(artifactFilePath(sha256, config.parserVersion))) {
                    db.prepareStatement(
                        """UPDATE processing_jobs
                           SET state='queued', progress_pct=0, stage_label='已排队，等待整理',
                               error_message=NULL, lease_owner=NULL, lease_expires_at=NULL,
                               published_version=NULL, updated_at=?
                           WHERE sha256=? AND parser_version=?""".trimIndent(),
                    ).use { sql ->
                        sql.setLong(1, now)
                        sql.setString(2, sha256)
                        sql.setString(3, config.parserVersion)
                        sql.executeUpdate()
                    }
                    job = checkNotNull(readJob(db, sha256, config.parserVersion))
                } else {
                    job = currentJob
                }
                db.commit()
            } catch (e: Exception) {
                db.rollback()
                throw e
            }
        }
        runCatching { Files.deleteIfExists(trashBlobPath(sha256)) }
        runCatching {
            val trashedArtifacts = trashArtifactsDir(sha256)
            if (Files.exists(trashedArtifacts)) trashedArtifacts.toFile().deleteRecursively()
        }
        if (config.autoProcess && job.state == "queued") {
            wakeWorker()
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
     * Lease-based job claim for server-side skeleton processing workers: claims either a `queued` job
     * or a `processing` job whose lease has expired, restricted strictly to active referenced blobs.
     */
    internal fun claimNextJob(workerId: String): UnifiedProcessingJob? {
        require(workerId.isNotBlank())
        val now = config.clock()
        val leaseUntil = now + config.leaseDurationMs
        return registryDb().use { db ->
            db.autoCommit = false
            try {
                val candidate = db.prepareStatement(
                    """SELECT j.sha256, j.parser_version FROM processing_jobs j
                       JOIN shared_blobs b ON b.sha256 = j.sha256
                       WHERE b.state='ACTIVE' AND b.ref_count > 0
                         AND j.parser_version=?
                         AND (j.state='queued' OR (j.state='processing' AND (j.lease_expires_at IS NULL OR j.lease_expires_at <= ?)))
                       ORDER BY j.updated_at ASC LIMIT 1""".trimIndent(),
                ).use { sql ->
                    sql.setString(1, config.parserVersion)
                    sql.setLong(2, now)
                    sql.executeQuery().use { rs ->
                        if (rs.next()) rs.getString(1) to rs.getString(2) else null
                    }
                } ?: run {
                    db.rollback()
                    return@use null
                }
                db.prepareStatement(
                    """UPDATE processing_jobs
                       SET state='processing', lease_owner=?, lease_expires_at=?,
                           progress_pct=CASE WHEN progress_pct < 5 THEN 5 ELSE progress_pct END,
                           stage_label=CASE WHEN stage_label='' OR state='queued' THEN '正在整理' ELSE stage_label END,
                           process_count=process_count + 1,
                           updated_at=?
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

    internal fun wakeWorker() {
        workerWake.trySend(Unit)
    }

    internal fun startWorkerIfNeeded(scope: CoroutineScope) {
        if (!enabled || !config.autoProcess) return
        if (!workerStarted.compareAndSet(false, true)) return
        workerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    var processedAny = false
                    while (isActive) {
                        val ran = try {
                            processNextJob("worker-bg")
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            false
                        }
                        if (!ran) break
                        processedAny = true
                    }
                    if (!processedAny) {
                        withTimeoutOrNull(10_000L) { workerWake.receive() }
                    }
                }
            } finally {
                workerStarted.set(false)
            }
        }
        wakeWorker()
    }

    internal fun stopWorker() {
        workerJob?.cancel()
        workerJob = null
        workerStarted.set(false)
    }

    internal suspend fun processNextJob(workerId: String = "worker-1"): Boolean = withContext(Dispatchers.IO) {
        val claimed = claimNextJob(workerId) ?: return@withContext false
        val sha256 = claimed.sha256
        val parserVersion = claimed.parserVersion
        val blobMeta = shaLock(sha256).withLock {
            val file = blobPath(sha256)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return@withLock null
            registryDb().use { db ->
                val refs = countRefs(db, sha256)
                if (refs <= 0) return@use null
                db.prepareStatement(
                    "SELECT format FROM shared_blobs WHERE sha256=? AND state='ACTIVE'",
                ).use { sql ->
                    sql.setString(1, sha256)
                    sql.executeQuery().use { rs ->
                        if (rs.next()) rs.getString(1) to file else null
                    }
                }
            }
        }
        if (blobMeta == null) {
            markJobAbandonedIfUnreferenced(sha256, parserVersion, workerId)
            return@withContext true
        }
        val (format, blobFile) = blobMeta
        var tempArtifact: Path? = null
        try {
            val artifact = processor.process(
                blobFile = blobFile,
                format = format,
                title = sha256.take(12),
                sha256 = sha256,
                parserVersion = parserVersion,
            ) { pct, label ->
                updateJobProgress(sha256, parserVersion, workerId, pct, label)
            }
            val staged = privateFiles.file(artifactsDir.resolve(".artifact-${UUID.randomUUID()}.json"))
            tempArtifact = staged
            Files.writeString(staged, artifact.toJson().toString(), StandardCharsets.UTF_8)
            privateFiles.file(staged)

            shaLock(sha256).withLock {
                val active = registryDb().use { db ->
                    val isActiveBlob = db.prepareStatement(
                        "SELECT 1 FROM shared_blobs WHERE sha256=? AND state='ACTIVE'",
                    ).use { sql ->
                        sql.setString(1, sha256)
                        sql.executeQuery().use { it.next() }
                    }
                    isActiveBlob && countRefs(db, sha256) > 0
                }
                if (!active || !Files.isRegularFile(blobPath(sha256), LinkOption.NOFOLLOW_LINKS)) {
                    runCatching { Files.deleteIfExists(staged) }
                    return@withLock
                }
                val targetDir = privateFiles.directory(artifactDir(sha256, parserVersion))
                val targetFile = targetDir.resolve(SKELETON_ARTIFACT_FILENAME)
                moveAtomically(staged, targetFile)
                privateFiles.file(targetFile)

                val doneAt = config.clock()
                registryDb().use { db ->
                    db.prepareStatement(
                        """UPDATE processing_jobs
                           SET state='ready', progress_pct=100, stage_label='整理完成',
                               error_message=NULL, published_version=?, lease_owner=NULL, lease_expires_at=NULL, updated_at=?
                           WHERE sha256=? AND parser_version=? AND lease_owner=?""".trimIndent(),
                    ).use { sql ->
                        sql.setString(1, parserVersion)
                        sql.setLong(2, doneAt)
                        sql.setString(3, sha256)
                        sql.setString(4, parserVersion)
                        sql.setString(5, workerId)
                        sql.executeUpdate()
                    }
                }
            }
            true
        } catch (cancel: CancellationException) {
            tempArtifact?.let { runCatching { Files.deleteIfExists(it) } }
            throw cancel
        } catch (e: Exception) {
            tempArtifact?.let { runCatching { Files.deleteIfExists(it) } }
            val failedAt = config.clock()
            val safeMsg = when (e) {
                is IllegalArgumentException -> e.message?.take(120)?.takeIf { it.isNotBlank() } ?: "文件内容无法整理，请检查后重试。"
                else -> "整理失败，请稍后重试。"
            }
            registryDb().use { db ->
                db.prepareStatement(
                    """UPDATE processing_jobs
                       SET state='failed', error_message=?, lease_owner=NULL, lease_expires_at=NULL, updated_at=?
                       WHERE sha256=? AND parser_version=? AND lease_owner=?""".trimIndent(),
                ).use { sql ->
                    sql.setString(1, safeMsg)
                    sql.setLong(2, failedAt)
                    sql.setString(3, sha256)
                    sql.setString(4, parserVersion)
                    sql.setString(5, workerId)
                    sql.executeUpdate()
                }
            }
            true
        }
    }

    private fun updateJobProgress(
        sha256: String,
        parserVersion: String,
        workerId: String,
        progressPct: Int,
        stageLabel: String,
    ) {
        val now = config.clock()
        val leaseUntil = now + config.leaseDurationMs
        registryDb().use { db ->
            db.prepareStatement(
                """UPDATE processing_jobs
                   SET progress_pct=?, stage_label=?, lease_expires_at=?, updated_at=?
                   WHERE sha256=? AND parser_version=? AND state='processing' AND lease_owner=?""".trimIndent(),
            ).use { sql ->
                sql.setInt(1, progressPct.coerceIn(0, 99))
                sql.setString(2, stageLabel)
                sql.setLong(3, leaseUntil)
                sql.setLong(4, now)
                sql.setString(5, sha256)
                sql.setString(6, parserVersion)
                sql.setString(7, workerId)
                sql.executeUpdate()
            }
        }
    }

    private fun markJobAbandonedIfUnreferenced(sha256: String, parserVersion: String, workerId: String) {
        val now = config.clock()
        registryDb().use { db ->
            db.prepareStatement(
                """UPDATE processing_jobs
                   SET state='queued', lease_owner=NULL, lease_expires_at=NULL, updated_at=?
                   WHERE sha256=? AND parser_version=? AND lease_owner=?""".trimIndent(),
            ).use { sql ->
                sql.setLong(1, now)
                sql.setString(2, sha256)
                sql.setString(3, parserVersion)
                sql.setString(4, workerId)
                sql.executeUpdate()
            }
        }
    }

    internal fun processCount(sha256: String, parserVersion: String = config.parserVersion): Int {
        if (!sha256.matches(SHA256_PATTERN) || !Files.isRegularFile(registryFile)) return 0
        return registryDb().use { db ->
            db.prepareStatement("SELECT process_count FROM processing_jobs WHERE sha256=? AND parser_version=?").use { sql ->
                sql.setString(1, sha256)
                sql.setString(2, parserVersion)
                sql.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    internal fun artifactFilePath(sha256: String, parserVersion: String = config.parserVersion): Path =
        artifactDir(sha256, parserVersion).resolve(SKELETON_ARTIFACT_FILENAME)

    internal data class DownloadableOriginal(
        val shelfBookId: String,
        val sha256: String,
        val title: String,
        val format: String,
        val sizeBytes: Long,
        val blobFile: Path,
    )

    internal fun resolveDownloadableOriginal(scope: UnifiedBookScope, shelfBookId: String): DownloadableOriginal? {
        if (!shelfBookId.matches(SHELF_BOOK_ID_PATTERN)) return null
        val shelfFile = accountShelfFile(scope)
        if (!Files.isRegularFile(shelfFile, LinkOption.NOFOLLOW_LINKS)) return null
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
        val blobFile = blobPath(row.sha256)
        if (!Files.isRegularFile(blobFile, LinkOption.NOFOLLOW_LINKS)) return null
        val activeRef = registryDb().use { db ->
            db.prepareStatement(
                """SELECT 1 FROM shared_blobs b
                   JOIN shared_blob_refs r ON r.sha256 = b.sha256
                   WHERE b.sha256=? AND b.state='ACTIVE' AND r.scope_key=? AND r.shelf_book_id=?""".trimIndent(),
            ).use { sql ->
                sql.setString(1, row.sha256)
                sql.setString(2, scope.scopeKey)
                sql.setString(3, row.shelfBookId)
                sql.executeQuery().use { it.next() }
            }
        }
        if (!activeRef) return null
        val diskSize = runCatching { Files.size(blobFile) }.getOrNull() ?: return null
        return DownloadableOriginal(
            shelfBookId = row.shelfBookId,
            sha256 = row.sha256,
            title = row.title,
            format = row.format,
            sizeBytes = diskSize,
            blobFile = blobFile,
        )
    }

    internal sealed interface ArtifactLookupResult {
        data object NotFound : ArtifactLookupResult
        data class NotReady(val book: UnifiedShelfBook) : ArtifactLookupResult
        data class Ready(val book: UnifiedShelfBook, val artifactJson: JsonObject) : ArtifactLookupResult
    }

    internal fun readProcessedArtifact(scope: UnifiedBookScope, shelfBookId: String): ArtifactLookupResult {
        val book = shelfBookStatus(scope, shelfBookId) ?: return ArtifactLookupResult.NotFound
        val activeRef = registryDb().use { db ->
            db.prepareStatement(
                """SELECT 1 FROM shared_blobs b
                   JOIN shared_blob_refs r ON r.sha256 = b.sha256
                   WHERE b.sha256=? AND b.state='ACTIVE' AND r.scope_key=? AND r.shelf_book_id=?""".trimIndent(),
            ).use { sql ->
                sql.setString(1, book.sha256)
                sql.setString(2, scope.scopeKey)
                sql.setString(3, book.shelfBookId)
                sql.executeQuery().use { it.next() }
            }
        }
        if (!activeRef) return ArtifactLookupResult.NotFound
        val version = book.job.publishedVersion
        if (book.job.state != "ready" || version.isNullOrBlank()) {
            return ArtifactLookupResult.NotReady(book)
        }
        val file = artifactFilePath(book.sha256, version)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return ArtifactLookupResult.NotReady(book)
        }
        val parsed = runCatching {
            LedgerJson.parseToJsonElement(Files.readString(file, StandardCharsets.UTF_8)).jsonObject
        }.getOrNull() ?: return ArtifactLookupResult.NotReady(book)
        return ArtifactLookupResult.Ready(book, parsed)
    }

    internal data class AccountDeleteResult(
        val removedBooks: Int,
        val trashedSharedBlobs: Int,
        val purgedSharedBlobs: Int,
    )

    /**
     * Deletes an entire account scope's shelf records, notes, Q&A, overrides, and active references.
     * Shared blobs and shared processed artifacts are moved to trash (and purged when eligible) ONLY
     * when zero accounts reference that SHA-256.
     */
    internal suspend fun deleteAccount(
        scope: UnifiedBookScope,
        purgeImmediately: Boolean = false,
    ): AccountDeleteResult = withContext(Dispatchers.IO) {
        val entries = LinkedHashMap<String, String>() // shelfBookId -> sha256
        val shelfFile = accountShelfFile(scope)
        if (Files.isRegularFile(shelfFile, LinkOption.NOFOLLOW_LINKS)) {
            runCatching {
                openReadOnly(shelfFile).use { db ->
                    db.createStatement().use { sql ->
                        sql.executeQuery("SELECT shelf_book_id, sha256 FROM shelf_books").use { rs ->
                            while (rs.next()) entries[rs.getString(1)] = rs.getString(2)
                        }
                    }
                }
            }
        }
        if (Files.isRegularFile(registryFile, LinkOption.NOFOLLOW_LINKS)) {
            registryDb().use { db ->
                db.prepareStatement("SELECT shelf_book_id, sha256 FROM shared_blob_refs WHERE scope_key=?").use { sql ->
                    sql.setString(1, scope.scopeKey)
                    sql.executeQuery().use { rs ->
                        while (rs.next()) entries.putIfAbsent(rs.getString(1), rs.getString(2))
                    }
                }
                db.prepareStatement("SELECT shelf_book_id, sha256 FROM pending_book_deletes WHERE scope_key=?").use { sql ->
                    sql.setString(1, scope.scopeKey)
                    sql.executeQuery().use { rs ->
                        while (rs.next()) entries.putIfAbsent(rs.getString(1), rs.getString(2))
                    }
                }
            }
        }

        var removed = 0
        var trashed = 0
        for ((shelfBookId, sha256) in entries) {
            if (!shelfBookId.matches(SHELF_BOOK_ID_PATTERN) || !sha256.matches(SHA256_PATTERN)) continue
            val wasTrashed = isTrashed(sha256)
            shaLock(sha256).withLock {
                val now = config.clock()
                registryDb().use { db ->
                    db.prepareStatement(
                        """INSERT OR IGNORE INTO pending_book_deletes(scope_key, shelf_book_id, sha256, created_at)
                           VALUES(?, ?, ?, ?)""".trimIndent(),
                    ).use { sql ->
                        sql.setString(1, scope.scopeKey)
                        sql.setString(2, shelfBookId)
                        sql.setString(3, sha256)
                        sql.setLong(4, now)
                        sql.executeUpdate()
                    }
                }
                reconcilePendingDeleteLocked(scope.scopeKey, shelfBookId, sha256)
            }
            removed++
            if (!wasTrashed && isTrashed(sha256)) trashed++
        }

        val accountDir = accountsDir.resolve(scope.scopeKey)
        if (Files.exists(accountDir)) {
            accountDir.toFile().deleteRecursively()
        }

        val purged = if (purgeImmediately || config.trashRetentionMs == 0L) {
            purgeExpiredTrash(0L)
        } else 0
        AccountDeleteResult(removedBooks = removed, trashedSharedBlobs = trashed, purgedSharedBlobs = purged)
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

    internal fun isTrashed(sha256: String): Boolean {
        if (!sha256.matches(SHA256_PATTERN)) return false
        if (Files.isRegularFile(trashBlobPath(sha256))) return true
        if (!Files.isRegularFile(registryFile)) return false
        return registryDb().use { db ->
            db.prepareStatement("SELECT 1 FROM shared_blobs WHERE sha256=? AND state='TRASHED'").use { sql ->
                sql.setString(1, sha256)
                sql.executeQuery().use { it.next() }
            }
        }
    }

    internal fun recordPendingDeleteForTest(scope: UnifiedBookScope, shelfBookId: String, sha256: String) {
        require(shelfBookId.matches(SHELF_BOOK_ID_PATTERN)) { "Invalid shelf book id" }
        require(sha256.matches(SHA256_PATTERN)) { "Invalid sha256" }
        val now = config.clock()
        registryDb().use { db ->
            db.prepareStatement(
                """INSERT OR IGNORE INTO pending_book_deletes(scope_key, shelf_book_id, sha256, created_at)
                   VALUES(?, ?, ?, ?)""".trimIndent(),
            ).use { sql ->
                sql.setString(1, scope.scopeKey)
                sql.setString(2, shelfBookId)
                sql.setString(3, sha256)
                sql.setLong(4, now)
                sql.executeUpdate()
            }
        }
    }

    internal fun deleteOnlyShelfRowForTest(scope: UnifiedBookScope, shelfBookId: String) {
        val file = accountShelfFile(scope)
        if (!Files.isRegularFile(file)) return
        accountShelfDb(scope).use { db ->
            db.prepareStatement("DELETE FROM shelf_books WHERE shelf_book_id=?").use { sql ->
                sql.setString(1, shelfBookId)
                sql.executeUpdate()
            }
        }
    }

    /**
     * Crash-safe per-shelf-book unbind and delete:
     * 1. Persists delete intent `(scope_key, shelf_book_id, sha256)` before mutating shelf/refs.
     * 2. Deletes the account shelf record by exact `shelf_book_id`.
     * 3. Unbinds `shared_blob_refs` strictly by `(sha256, scope_key, shelf_book_id)`, recalculates
     *    remaining refs across all active references, and moves unreferenced blobs to trash before
     *    marking `TRASHED` and clearing the pending delete record.
     */
    internal suspend fun deleteShelfBook(scope: UnifiedBookScope, shelfBookId: String): Boolean = withContext(Dispatchers.IO) {
        if (!shelfBookId.matches(SHELF_BOOK_ID_PATTERN)) return@withContext false
        val initialSha = lookupShelfOrPendingSha(scope.scopeKey, shelfBookId) ?: return@withContext false
        shaLock(initialSha).withLock {
            val sha = lookupShelfOrPendingSha(scope.scopeKey, shelfBookId) ?: return@withLock false
            val now = config.clock()
            registryDb().use { db ->
                db.prepareStatement(
                    """INSERT OR IGNORE INTO pending_book_deletes(scope_key, shelf_book_id, sha256, created_at)
                       VALUES(?, ?, ?, ?)""".trimIndent(),
                ).use { sql ->
                    sql.setString(1, scope.scopeKey)
                    sql.setString(2, shelfBookId)
                    sql.setString(3, sha)
                    sql.setLong(4, now)
                    sql.executeUpdate()
                }
            }
            reconcilePendingDeleteLocked(scope.scopeKey, shelfBookId, sha)
            true
        }
    }

    internal suspend fun reconcilePendingDeletes(): Int = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(registryFile)) return@withContext 0
        val pending = registryDb().use { db ->
            db.createStatement().use { sql ->
                sql.executeQuery(
                    "SELECT scope_key, shelf_book_id, sha256 FROM pending_book_deletes ORDER BY created_at ASC, shelf_book_id ASC",
                ).use { rs ->
                    buildList {
                        while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getString(3)))
                    }
                }
            }
        }
        var reconciled = 0
        for ((scopeKey, shelfBookId, sha256) in pending) {
            if (!sha256.matches(SHA256_PATTERN) || !shelfBookId.matches(SHELF_BOOK_ID_PATTERN)) continue
            shaLock(sha256).withLock {
                reconcilePendingDeleteLocked(scopeKey, shelfBookId, sha256)
            }
            reconciled++
        }
        reconciled
    }

    internal suspend fun purgeExpiredTrash(retentionMs: Long = config.trashRetentionMs): Int = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(registryFile)) return@withContext 0
        val candidates = registryDb().use { db ->
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT sha256 FROM shared_blobs WHERE state='TRASHED'").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
        var purged = 0
        for (sha256 in candidates) {
            if (!sha256.matches(SHA256_PATTERN)) continue
            shaLock(sha256).withLock {
                val now = config.clock()
                val eligible = registryDb().use { db ->
                    val trashedAt = db.prepareStatement(
                        "SELECT trashed_at FROM shared_blobs WHERE sha256=? AND state='TRASHED'",
                    ).use { sql ->
                        sql.setString(1, sha256)
                        sql.executeQuery().use { rs ->
                            if (!rs.next()) null else (rs.getObject(1) as? Number)?.toLong()
                        }
                    } ?: return@use false
                    if (now - trashedAt < retentionMs) return@use false
                    if (countRefs(db, sha256) > 0) return@use false
                    val hasPending = db.prepareStatement(
                        "SELECT 1 FROM pending_book_deletes WHERE sha256=? LIMIT 1",
                    ).use { sql ->
                        sql.setString(1, sha256)
                        sql.executeQuery().use { it.next() }
                    }
                    !hasPending
                }
                if (!eligible) return@withLock

                val trashedBlob = trashBlobPath(sha256)
                val trashedArtifacts = trashArtifactsDir(sha256)
                val blobRemoved = runCatching {
                    Files.deleteIfExists(trashedBlob)
                    !Files.exists(trashedBlob)
                }.getOrDefault(false)
                val artifactsRemoved = runCatching {
                    if (Files.exists(trashedArtifacts)) {
                        trashedArtifacts.toFile().deleteRecursively()
                    }
                    !Files.exists(trashedArtifacts)
                }.getOrDefault(false)
                if (!blobRemoved || !artifactsRemoved) return@withLock

                registryDb().use { db ->
                    db.autoCommit = false
                    try {
                        val stillTrashed = db.prepareStatement(
                            "SELECT 1 FROM shared_blobs WHERE sha256=? AND state='TRASHED'",
                        ).use { sql ->
                            sql.setString(1, sha256)
                            sql.executeQuery().use { it.next() }
                        }
                        val hasPending = db.prepareStatement(
                            "SELECT 1 FROM pending_book_deletes WHERE sha256=? LIMIT 1",
                        ).use { sql ->
                            sql.setString(1, sha256)
                            sql.executeQuery().use { it.next() }
                        }
                        if (!stillTrashed || countRefs(db, sha256) > 0 || hasPending) {
                            db.rollback()
                            return@use
                        }
                        db.prepareStatement("DELETE FROM processing_jobs WHERE sha256=?").use { sql ->
                            sql.setString(1, sha256)
                            sql.executeUpdate()
                        }
                        db.prepareStatement("DELETE FROM shared_blobs WHERE sha256=? AND state='TRASHED'").use { sql ->
                            sql.setString(1, sha256)
                            if (sql.executeUpdate() > 0) purged++
                        }
                        db.commit()
                    } catch (e: Exception) {
                        db.rollback()
                        throw e
                    }
                }
            }
        }
        purged
    }

    private fun lookupShelfOrPendingSha(scopeKey: String, shelfBookId: String): String? {
        if (!scopeKey.matches(UnifiedBookScope.SCOPE_KEY_PATTERN)) return null
        val shelfFile = accountsDir.resolve(scopeKey).resolve("shelf.sqlite")
        if (Files.isRegularFile(shelfFile)) {
            val fromShelf = openReadOnly(shelfFile).use { db ->
                db.prepareStatement("SELECT sha256 FROM shelf_books WHERE shelf_book_id=?").use { sql ->
                    sql.setString(1, shelfBookId)
                    sql.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }
            if (fromShelf != null) return fromShelf
        }
        if (!Files.isRegularFile(registryFile)) return null
        return openReadOnly(registryFile).use { db ->
            val hasTable = db.createStatement().use { s ->
                s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='pending_book_deletes'").use { it.next() }
            }
            if (!hasTable) return@use null
            db.prepareStatement("SELECT sha256 FROM pending_book_deletes WHERE scope_key=? AND shelf_book_id=?").use { sql ->
                sql.setString(1, scopeKey)
                sql.setString(2, shelfBookId)
                sql.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }

    private fun deleteAccountShelfRowById(scopeKey: String, shelfBookId: String) {
        val shelfFile = accountsDir.resolve(scopeKey).resolve("shelf.sqlite")
        if (!Files.isRegularFile(shelfFile)) return
        DriverManager.getConnection("jdbc:sqlite:$shelfFile").use { accountDb ->
            accountDb.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
            accountDb.autoCommit = false
            try {
                for (table in listOf(
                    "shelf_book_notes", "shelf_book_qa", "shelf_book_overrides",
                    "legacy_book_mappings", "legacy_receipt_alignments", "shelf_books",
                )) {
                    val exists = accountDb.prepareStatement(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                    ).use { sql ->
                        sql.setString(1, table)
                        sql.executeQuery().use { it.next() }
                    }
                    if (exists) {
                        accountDb.prepareStatement("DELETE FROM $table WHERE shelf_book_id=?").use { sql ->
                            sql.setString(1, shelfBookId)
                            sql.executeUpdate()
                        }
                    }
                }
                accountDb.commit()
            } catch (e: Exception) {
                accountDb.rollback()
                throw e
            }
        }
    }

    private fun reconcilePendingDeleteLocked(scopeKey: String, shelfBookId: String, sha256: String) {
        deleteAccountShelfRowById(scopeKey, shelfBookId)
        val now = config.clock()
        val remainingRefs = registryDb().use { db ->
            db.autoCommit = false
            try {
                // Consume strictly the reference bound to (sha256, scope_key, shelf_book_id).
                db.prepareStatement(
                    "DELETE FROM shared_blob_refs WHERE sha256=? AND scope_key=? AND shelf_book_id=?",
                ).use { sql ->
                    sql.setString(1, sha256)
                    sql.setString(2, scopeKey)
                    sql.setString(3, shelfBookId)
                    sql.executeUpdate()
                }
                val refs = countRefs(db, sha256)
                db.prepareStatement("UPDATE shared_blobs SET ref_count=?, updated_at=? WHERE sha256=?").use { sql ->
                    sql.setInt(1, refs)
                    sql.setLong(2, now)
                    sql.setString(3, sha256)
                    sql.executeUpdate()
                }
                if (refs > 0) {
                    db.prepareStatement(
                        "DELETE FROM pending_book_deletes WHERE scope_key=? AND shelf_book_id=?",
                    ).use { sql ->
                        sql.setString(1, scopeKey)
                        sql.setString(2, shelfBookId)
                        sql.executeUpdate()
                    }
                }
                db.commit()
                refs
            } catch (e: Exception) {
                db.rollback()
                throw e
            }
        }
        if (remainingRefs > 0) return

        val activeBlob = blobPath(sha256)
        if (Files.isRegularFile(activeBlob)) {
            privateFiles.directory(trashBlobsDir)
            val trashedBlob = trashBlobPath(sha256)
            moveAtomically(activeBlob, trashedBlob)
            privateFiles.file(trashedBlob)
        }
        val activeArtifacts = artifactsDir.resolve(sha256)
        if (Files.exists(activeArtifacts)) {
            privateFiles.directory(trashArtifactsRoot)
            val trashedArtifacts = trashArtifactsDir(sha256)
            if (Files.exists(trashedArtifacts)) trashedArtifacts.toFile().deleteRecursively()
            moveAtomically(activeArtifacts, trashedArtifacts)
        }
        if (Files.exists(activeBlob)) return

        val trashedNow = config.clock()
        registryDb().use { db ->
            db.autoCommit = false
            try {
                if (countRefs(db, sha256) > 0) {
                    db.rollback()
                    return@use
                }
                db.prepareStatement(
                    """UPDATE shared_blobs
                       SET state='TRASHED', ref_count=0, trashed_at=COALESCE(trashed_at, ?), updated_at=?
                       WHERE sha256=?""".trimIndent(),
                ).use { sql ->
                    sql.setLong(1, trashedNow)
                    sql.setLong(2, trashedNow)
                    sql.setString(3, sha256)
                    sql.executeUpdate()
                }
                db.prepareStatement(
                    "DELETE FROM pending_book_deletes WHERE scope_key=? AND shelf_book_id=?",
                ).use { sql ->
                    sql.setString(1, scopeKey)
                    sql.setString(2, shelfBookId)
                    sql.executeUpdate()
                }
                db.commit()
            } catch (e: Exception) {
                db.rollback()
                throw e
            }
        }
    }

    internal fun accountShelfFile(scope: UnifiedBookScope): Path = accountsDir.resolve(scope.scopeKey).resolve("shelf.sqlite")

    internal fun blobPathForVerify(sha256: String): Path = blobPath(sha256)

    /**
     * Strictly read-only lookup for PR B inventory/migration: never creates directories or SQLite files.
     * Returns the verified `shelf_book_id` only when the account shelf row, shared registry active ref,
     * and on-disk shared blob file all exist.
     */
    internal fun existingShelfBookIdByShaReadOnly(scope: UnifiedBookScope, sha256: String): String? {
        if (!sha256.matches(SHA256_PATTERN)) return null
        val shelfFile = accountShelfFile(scope)
        if (!Files.isRegularFile(shelfFile) || !Files.isRegularFile(registryFile) || !Files.isRegularFile(blobPath(sha256))) {
            return null
        }
        val shelfBookId = openReadOnly(shelfFile).use { accountDb ->
            accountDb.prepareStatement("SELECT shelf_book_id FROM shelf_books WHERE sha256=?").use { sql ->
                sql.setString(1, sha256)
                sql.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        } ?: return null
        val activeRef = openReadOnly(registryFile).use { db ->
            db.prepareStatement(
                """SELECT 1 FROM shared_blobs b
                   JOIN shared_blob_refs r ON r.sha256 = b.sha256
                   WHERE b.sha256=? AND b.state='ACTIVE' AND r.scope_key=? AND r.shelf_book_id=?""".trimIndent(),
            ).use { sql ->
                sql.setString(1, sha256)
                sql.setString(2, scope.scopeKey)
                sql.setString(3, shelfBookId)
                sql.executeQuery().use { it.next() }
            }
        }
        return if (activeRef) shelfBookId else null
    }

    internal data class LegacyBookMappingSnapshot(
        val sourceKind: String,
        val legacyDocId: String,
        val courseId: String,
        val explainSourceId: String?,
        val pages: Int,
        val printedPageOffset: Int?,
        val pagesSha256: String?,
        val sha256: String,
        val shelfBookId: String,
    )

    private fun readLegacyBookMapping(db: Connection, legacyRecordId: String): LegacyBookMappingSnapshot? =
        db.prepareStatement(
            """SELECT source_kind, legacy_doc_id, course_id, explain_source_id,
                      pages, printed_page_offset, pages_sha256, sha256, shelf_book_id
               FROM legacy_book_mappings WHERE legacy_record_id=?""".trimIndent(),
        ).use { sql ->
            sql.setString(1, legacyRecordId)
            sql.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    val rawOffset = rs.getObject(6)
                    LegacyBookMappingSnapshot(
                        sourceKind = rs.getString(1),
                        legacyDocId = rs.getString(2),
                        courseId = rs.getString(3),
                        explainSourceId = rs.getString(4),
                        pages = rs.getInt(5),
                        printedPageOffset = (rawOffset as? Number)?.toInt(),
                        pagesSha256 = rs.getString(7),
                        sha256 = rs.getString(8),
                        shelfBookId = rs.getString(9),
                    )
                }
            }
        }

    internal fun hasLegacyBookMappingReadOnly(
        scope: UnifiedBookScope,
        legacyRecordId: String,
        expectedSourceKind: String,
        expectedLegacyDocId: String,
        expectedCourseId: String,
        expectedExplainSourceId: String?,
        expectedPages: Int,
        expectedPrintedPageOffset: Int?,
        expectedPagesSha256: String?,
        expectedSha256: String,
    ): Boolean {
        val shelfFile = accountShelfFile(scope)
        if (!Files.isRegularFile(shelfFile)) return false
        return openReadOnly(shelfFile).use { db ->
            val hasTable = db.createStatement().use { s ->
                s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='legacy_book_mappings'").use { it.next() }
            }
            if (!hasTable) return@use false
            val existing = readLegacyBookMapping(db, legacyRecordId) ?: return@use false
            val matchesMetadata = existing.sourceKind == expectedSourceKind &&
                existing.legacyDocId == expectedLegacyDocId &&
                existing.courseId == expectedCourseId &&
                existing.explainSourceId == expectedExplainSourceId &&
                existing.pages == expectedPages &&
                existing.printedPageOffset == expectedPrintedPageOffset &&
                existing.pagesSha256 == expectedPagesSha256 &&
                existing.sha256 == expectedSha256
            if (!matchesMetadata) return@use false
            val shelfMatches = db.prepareStatement("SELECT 1 FROM shelf_books WHERE shelf_book_id=? AND sha256=?").use { sql ->
                sql.setString(1, existing.shelfBookId)
                sql.setString(2, expectedSha256)
                sql.executeQuery().use { it.next() }
            }
            shelfMatches && Files.isRegularFile(blobPath(expectedSha256))
        }
    }

    internal fun alignedReceiptBlockKeysReadOnly(
        scope: UnifiedBookScope,
        legacyReceiptId: String,
        shelfBookId: String,
    ): Set<Triple<Int, Int, Int>> {
        val shelfFile = accountShelfFile(scope)
        if (!Files.isRegularFile(shelfFile)) return emptySet()
        return openReadOnly(shelfFile).use { db ->
            val hasTable = db.createStatement().use { s ->
                s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='legacy_receipt_alignments'").use { it.next() }
            }
            if (!hasTable) return@use emptySet()
            db.prepareStatement(
                "SELECT page, start_utf16, end_utf16 FROM legacy_receipt_alignments WHERE legacy_receipt_id=? AND shelf_book_id=?",
            ).use { sql ->
                sql.setString(1, legacyReceiptId)
                sql.setString(2, shelfBookId)
                sql.executeQuery().use { rs ->
                    buildSet { while (rs.next()) add(Triple(rs.getInt(1), rs.getInt(2), rs.getInt(3))) }
                }
            }
        }
    }

    /**
     * Imports a verified server-local original file into [scope] for PR B migration.
     * Always streams and re-hashes the actual file bytes before establishing or reusing any mapping;
     * returns `(shelfBook, alreadyExistedOnScope)` so repeated runs deduplicate cleanly.
     */
    internal suspend fun importVerifiedLocalFile(
        scope: UnifiedBookScope,
        file: Path,
        formatRaw: String,
        titleRaw: String,
        expectedSha256: String,
    ): Pair<UnifiedShelfBook, Boolean> = withContext(Dispatchers.IO) {
        val format = formatRaw.trim().lowercase()
        if (format !in SUPPORTED_FORMATS) {
            throw UnifiedBookError(HttpStatusCode.BadRequest, "unsupported_format", "暂不支持这种教材格式。")
        }
        val title = titleRaw.trim()
        if (title.length !in 1..300 || title.any(Char::isISOControl) || !tutorSourceAllowed(title)) {
            throw UnifiedBookError(HttpStatusCode.BadRequest, "invalid_metadata", "书名无效，请检查后重试。")
        }
        val expectedSha = expectedSha256.trim().lowercase()
        if (!expectedSha.matches(SHA256_PATTERN)) {
            throw UnifiedBookError(HttpStatusCode.BadRequest, "invalid_metadata", "文件校验值格式不对。")
        }
        if (!Files.isRegularFile(file)) {
            throw UnifiedBookError(HttpStatusCode.NotFound, "missing_file", "原文件不存在。")
        }
        val fileSize = Files.size(file)
        if (fileSize <= 0L) {
            throw UnifiedBookError(HttpStatusCode.BadRequest, "invalid_metadata", "这份教材是空文件，无法迁移。")
        }
        if (fileSize > config.maxFileBytes) {
            throw UnifiedBookError(HttpStatusCode.PayloadTooLarge, "file_too_large", "文件太大")
        }

        // Verify actual file bytes match expectedSha before taking locks or modifying state.
        val actualSha = sha256File(file)
        if (actualSha != expectedSha) {
            throw UnifiedBookError(HttpStatusCode.BadRequest, "sha256_mismatch", "文件内容校验不一致。")
        }

        shaLock(actualSha).withLock {
            val existingId = existingShelfBookIdByShaReadOnly(scope, actualSha)
            if (existingId != null) {
                val existing = shelfBookStatus(scope, existingId)
                if (existing != null) {
                    return@withLock existing to true
                }
            }
            ensureInitialized()
            val tempFile = privateFiles.file(tmpDir.resolve(".migrate-${UUID.randomUUID()}.part"))
            var reserved = 0L
            try {
                reserved = reserveSpace(scope, fileSize, 0L)
                val copyDigest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                Files.newInputStream(file).use { input ->
                    Files.newOutputStream(tempFile).use { out ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            copied += n
                            if (copied > config.maxFileBytes) {
                                throw UnifiedBookError(HttpStatusCode.PayloadTooLarge, "file_too_large", "文件太大")
                            }
                            copyDigest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                        }
                    }
                }
                val copiedSha = copyDigest.digest().joinToString("") { "%02x".format(it) }
                if (copied != fileSize || copiedSha != expectedSha) {
                    throw UnifiedBookError(HttpStatusCode.BadRequest, "sha256_mismatch", "文件内容校验不一致。")
                }
                val committed = commitUploadedFile(scope, actualSha, format, title, copied, tempFile)
                committed to false
            } finally {
                runCatching { Files.deleteIfExists(tempFile) }
                releaseSpace(scope, reserved)
            }
        }
    }

    internal fun upsertLegacyBookMapping(
        scope: UnifiedBookScope,
        legacyRecordId: String,
        sourceKind: String,
        legacyDocId: String,
        courseId: String,
        explainSourceId: String?,
        pages: Int,
        printedPageOffset: Int?,
        pagesSha256: String?,
        sha256: String,
        shelfBookId: String,
    ): Boolean {
        val now = config.clock()
        val desired = LegacyBookMappingSnapshot(
            sourceKind = sourceKind,
            legacyDocId = legacyDocId,
            courseId = courseId,
            explainSourceId = explainSourceId,
            pages = pages,
            printedPageOffset = printedPageOffset,
            pagesSha256 = pagesSha256,
            sha256 = sha256,
            shelfBookId = shelfBookId,
        )
        return accountShelfDb(scope).use { db ->
            val existing = readLegacyBookMapping(db, legacyRecordId)
            if (existing == desired) return@use false
            db.prepareStatement(
                """INSERT INTO legacy_book_mappings(
                       legacy_record_id, source_kind, legacy_doc_id, course_id, explain_source_id,
                       pages, printed_page_offset, pages_sha256, sha256, shelf_book_id, migrated_at
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(legacy_record_id) DO UPDATE SET
                       source_kind=excluded.source_kind,
                       legacy_doc_id=excluded.legacy_doc_id,
                       course_id=excluded.course_id,
                       explain_source_id=excluded.explain_source_id,
                       pages=excluded.pages,
                       printed_page_offset=excluded.printed_page_offset,
                       pages_sha256=excluded.pages_sha256,
                       sha256=excluded.sha256,
                       shelf_book_id=excluded.shelf_book_id,
                       migrated_at=excluded.migrated_at""".trimIndent(),
            ).use { sql ->
                sql.setString(1, legacyRecordId)
                sql.setString(2, sourceKind)
                sql.setString(3, legacyDocId)
                sql.setString(4, courseId)
                if (explainSourceId == null) sql.setNull(5, java.sql.Types.VARCHAR) else sql.setString(5, explainSourceId)
                sql.setInt(6, pages)
                if (printedPageOffset == null) sql.setNull(7, java.sql.Types.INTEGER) else sql.setInt(7, printedPageOffset)
                if (pagesSha256 == null) sql.setNull(8, java.sql.Types.VARCHAR) else sql.setString(8, pagesSha256)
                sql.setString(9, sha256)
                sql.setString(10, shelfBookId)
                sql.setLong(11, now)
                sql.executeUpdate()
            }
            true
        }
    }

    internal data class LegacyReceiptAlignmentRow(
        val page: Int,
        val startUtf16: Int,
        val endUtf16: Int,
        val snapshotSha256: String,
        val snapshotLength: Int,
        val snapshotOcr: Boolean,
    )

    internal fun upsertLegacyReceiptAlignments(
        scope: UnifiedBookScope,
        legacyReceiptId: String,
        sourceKind: String,
        shelfBookId: String,
        bookSha256: String,
        rows: List<LegacyReceiptAlignmentRow>,
    ): Int {
        if (rows.isEmpty()) return 0
        val now = config.clock()
        return accountShelfDb(scope).use { db ->
            db.autoCommit = false
            try {
                var inserted = 0
                db.prepareStatement(
                    """INSERT OR IGNORE INTO legacy_receipt_alignments(
                           legacy_receipt_id, source_kind, shelf_book_id, book_sha256,
                           page, start_utf16, end_utf16, snapshot_sha256, snapshot_length, snapshot_ocr, aligned_at
                       ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
                ).use { sql ->
                    for (row in rows) {
                        sql.setString(1, legacyReceiptId)
                        sql.setString(2, sourceKind)
                        sql.setString(3, shelfBookId)
                        sql.setString(4, bookSha256)
                        sql.setInt(5, row.page)
                        sql.setInt(6, row.startUtf16)
                        sql.setInt(7, row.endUtf16)
                        sql.setString(8, row.snapshotSha256)
                        sql.setInt(9, row.snapshotLength)
                        sql.setInt(10, if (row.snapshotOcr) 1 else 0)
                        sql.setLong(11, now)
                        inserted += sql.executeUpdate()
                    }
                }
                db.commit()
                inserted
            } catch (e: Exception) {
                db.rollback()
                throw e
            }
        }
    }

    private fun countRefs(db: Connection, sha256: String): Int =
        db.prepareStatement("SELECT COUNT(*) FROM shared_blob_refs WHERE sha256=?").use { sql ->
            sql.setString(1, sha256)
            sql.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun readJob(db: Connection, sha256: String, parserVersion: String): UnifiedProcessingJob? =
        db.prepareStatement(
            """SELECT sha256, parser_version, state, progress_pct, stage_label, error_message, published_version, process_count
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
                    processCount = rs.getInt(8),
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
        internal const val SKELETON_ARTIFACT_FILENAME = "skeleton.json"
        internal val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        internal val SHELF_BOOK_ID_PATTERN = Regex("sb_[0-9a-f]{24}")
        internal val SUPPORTED_FORMATS = setOf(
            "pdf", "epub", "md", "txt", "docx", "pptx", "html", "mobi", "azw3", "rtf", "odt", "png", "jpg", "srt", "vtt",
        )

        internal fun openReadOnly(file: Path): Connection {
            val props = java.util.Properties().apply {
                setProperty("open_mode", "1") // SQLITE_OPEN_READONLY
            }
            return DriverManager.getConnection("jdbc:sqlite:$file", props)
        }

        internal fun sha256File(file: Path): String {
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
    }
}

fun Application.unifiedBookRoutes(
    actor: LedgerActor,
    unifiedBooks: UnifiedBookPipeline? = null,
    friends: FriendAccess? = null,
) {
    if (unifiedBooks != null && unifiedBooks.enabled && unifiedBooks.config.autoProcess) {
        unifiedBooks.startWorkerIfNeeded(this)
        monitor.subscribe(ApplicationStopping) {
            unifiedBooks.stopWorker()
        }
    }
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

        get("/v1/books/{id}/original") {
            val pipeline = unifiedBooks?.takeIf { it.enabled }
                ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
            val device = call.attributes[observedDevice]
            val friend = call.attributes.getOrNull(observedFriend)
            val bearer = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ").orEmpty()
            if (friend != null && !actor.execute { authorize(device, bearer) && friendName(device) == friend }) {
                return@get call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            }
            val scope = UnifiedBookScope.fromCall(device, friend)
            val shelfBookId = call.parameters["id"].orEmpty()
            val original = withContext(Dispatchers.IO) { pipeline.resolveDownloadableOriginal(scope, shelfBookId) }
                ?: return@get call.respondText(
                    json("code" to "not_found", "error" to "书架中找不到这本书。").toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("X-Coach-Book-Sha256", original.sha256)
            call.response.header("X-Coach-Book-Format", original.format)
            call.response.header("X-Coach-Book-Title", URLEncoder.encode(original.title, StandardCharsets.UTF_8))
            call.respond(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType: ContentType = ContentType.Application.OctetStream
                    override val contentLength: Long = original.sizeBytes
                    override val status: HttpStatusCode = HttpStatusCode.OK
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        withContext(Dispatchers.IO) {
                            Files.newInputStream(original.blobFile).use { input ->
                                val buf = ByteArray(65_536)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    channel.writeFully(buf, 0, n)
                                }
                            }
                        }
                    }
                },
            )
        }

        get("/v1/books/{id}/artifact") {
            val pipeline = unifiedBooks?.takeIf { it.enabled }
                ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
            val device = call.attributes[observedDevice]
            val friend = call.attributes.getOrNull(observedFriend)
            val bearer = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ").orEmpty()
            if (friend != null && !actor.execute { authorize(device, bearer) && friendName(device) == friend }) {
                return@get call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            }
            val scope = UnifiedBookScope.fromCall(device, friend)
            val shelfBookId = call.parameters["id"].orEmpty()
            when (val result = withContext(Dispatchers.IO) { pipeline.readProcessedArtifact(scope, shelfBookId) }) {
                UnifiedBookPipeline.ArtifactLookupResult.NotFound -> call.respondText(
                    json("code" to "not_found", "error" to "书架中找不到这本书。").toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
                is UnifiedBookPipeline.ArtifactLookupResult.NotReady -> call.respondText(
                    json(
                        "code" to "not_ready",
                        "error" to "这本书还在整理中，请稍后再试。",
                        "job" to result.book.job.toJson(),
                    ).toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
                is UnifiedBookPipeline.ArtifactLookupResult.Ready -> call.respondText(
                    json(
                        "shelfBookId" to result.book.shelfBookId,
                        "sha256" to result.book.sha256,
                        "title" to result.book.title,
                        "format" to result.book.format,
                        "job" to result.book.job.toJson(),
                        "artifact" to result.artifactJson,
                    ).toString(),
                    ContentType.Application.Json,
                )
            }
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
                    json("code" to "not_found", "error" to "书架中找不到这本书。").toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            }
            call.respondText(
                json("deleted" to true, "shelfBookId" to shelfBookId).toString(),
                ContentType.Application.Json,
            )
        }

        delete("/v1/account") {
            val pipeline = unifiedBooks?.takeIf { it.enabled }
                ?: return@delete call.respondText("", status = HttpStatusCode.NotFound)
            val device = call.attributes[observedDevice]
            val friend = call.attributes.getOrNull(observedFriend)
            val bearer = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ").orEmpty()
            if (friend != null && !actor.execute { authorize(device, bearer) && friendName(device) == friend }) {
                return@delete call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            }
            val scope = UnifiedBookScope.fromCall(device, friend)
            val summary = pipeline.deleteAccount(scope)
            if (friend != null) {
                withContext(Dispatchers.IO) { runCatching { friends?.deleteAccountData(friend) } }
                actor.execute { revokeFriend(friend) }
            }
            call.respondText(
                json(
                    "deleted" to true,
                    "removedBooks" to summary.removedBooks,
                    "trashedSharedBlobs" to summary.trashedSharedBlobs,
                    "purgedSharedBlobs" to summary.purgedSharedBlobs,
                ).toString(),
                ContentType.Application.Json,
            )
        }
    }
}
