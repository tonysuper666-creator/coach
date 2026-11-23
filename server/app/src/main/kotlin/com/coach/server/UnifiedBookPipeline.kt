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
    val leaseDurationMs: Long = DEFAULT_LEASE_DURATION_MS,
    val usableSpaceProvider: (Path) -> Long = { path -> Files.getFileStore(path).usableSpace },
    val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(parserVersion.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid parser version" }
        require(maxFileBytes > 0L) { "maxFileBytes must be positive" }
        require(accountQuotaBytes >= maxFileBytes) { "accountQuotaBytes must cover maxFileBytes" }
        require(minFreeDiskBytes >= 0L) { "minFreeDiskBytes must be non-negative" }
        require(leaseDurationMs > 0L) { "leaseDurationMs must be positive" }
    }

    companion object {
        const val DEFAULT_PARSER_VERSION = "v1-queued"
        const val DEFAULT_MAX_FILE_BYTES = 256L * 1024L * 1024L
        const val DEFAULT_ACCOUNT_QUOTA_BYTES = 2L * 1024L * 1024L * 1024L
        const val DEFAULT_MIN_FREE_DISK_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_LEASE_DURATION_MS = 10L * 60L * 1000L

        fun fromEnv(env: (String) -> String? = System::getenv): UnifiedBookConfig {
            val enabledRaw = env("COACH_UNIFIED_BOOKS")?.trim()?.lowercase()
            val enabled = enabledRaw == "1" || enabledRaw == "true"
            val maxFile = env("COACH_UNIFIED_BOOK_MAX_BYTES")?.trim()?.toLongOrNull() ?: DEFAULT_MAX_FILE_BYTES
            val quota = env("COACH_UNIFIED_BOOK_ACCOUNT_QUOTA_BYTES")?.trim()?.toLongOrNull()
                ?: maxOf(DEFAULT_ACCOUNT_QUOTA_BYTES, maxFile)
            val minFree = env("COACH_UNIFIED_BOOK_MIN_FREE_BYTES")?.trim()?.toLongOrNull() ?: DEFAULT_MIN_FREE_DISK_BYTES
            return UnifiedBookConfig(
                enabled = enabled,
                maxFileBytes = maxFile,
                accountQuotaBytes = quota,
                minFreeDiskBytes = minFree,
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
 * and lease-based processing job queue (TON-1823 PR A; remote unbinding is tracked in TON-1833).
 *
 * PR A intentionally stops at `queued` and never marks books `ready` until TON-1821's shared parser lands.
 */
class UnifiedBookPipeline internal constructor(
    private val root: Path,
    internal val config: UnifiedBookConfig = UnifiedBookConfig(),
) {
    val enabled: Boolean get() = config.enabled

    internal val rootDir: Path get() = root
    internal val blobsDirPath: Path get() = blobsDir
    internal val accountsDirPath: Path get() = accountsDir
    internal val registryFilePath: Path get() = registryFile

    private val sharedDir get() = root.resolve("shared")
    private val blobsDir get() = sharedDir.resolve("blobs")
    private val artifactsDir get() = sharedDir.resolve("artifacts")
    private val tmpDir get() = sharedDir.resolve("tmp")
    private val accountsDir get() = root.resolve("accounts")
    private val registryFile get() = sharedDir.resolve("registry.sqlite")

    private val initLock = Any()
    @Volatile private var initialized = false
    private val shaLocks = ConcurrentHashMap<String, Mutex>()
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
            privateFiles.file(registryFile)
            DriverManager.getConnection("jdbc:sqlite:$registryFile").use { db ->
                db.createStatement().use { sql ->
                    sql.execute("PRAGMA busy_timeout=5000")
                    sql.execute(
                        """CREATE TABLE IF NOT EXISTS shared_blobs(
                            sha256 TEXT PRIMARY KEY,
                            format TEXT NOT NULL,
                            size_bytes INTEGER NOT NULL,
                            state TEXT NOT NULL CHECK(state IN ('ACTIVE')),
                            ref_count INTEGER NOT NULL DEFAULT 0,
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

    private fun artifactDir(sha256: String, parserVersion: String): Path {
        require(sha256.matches(SHA256_PATTERN)) { "Invalid sha256" }
        return artifactsDir.resolve(sha256).resolve(parserVersion)
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
                    """INSERT INTO shared_blobs(sha256, format, size_bytes, state, ref_count, created_at, updated_at)
                       VALUES(?, ?, ?, 'ACTIVE', 0, ?, ?)
                       ON CONFLICT(sha256) DO UPDATE SET
                           state='ACTIVE',
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

    internal fun hasLegacyBookMappingReadOnly(scope: UnifiedBookScope, legacyRecordId: String): Boolean {
        val shelfFile = accountShelfFile(scope)
        if (!Files.isRegularFile(shelfFile)) return false
        return openReadOnly(shelfFile).use { db ->
            val hasTable = db.createStatement().use { s ->
                s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='legacy_book_mappings'").use { it.next() }
            }
            if (!hasTable) return@use false
            db.prepareStatement(
                """SELECT m.shelf_book_id, m.sha256
                   FROM legacy_book_mappings m
                   JOIN shelf_books b ON b.shelf_book_id = m.shelf_book_id AND b.sha256 = m.sha256
                   WHERE m.legacy_record_id=?""".trimIndent(),
            ).use { sql ->
                sql.setString(1, legacyRecordId)
                sql.executeQuery().use { rs ->
                    if (!rs.next()) false else Files.isRegularFile(blobPath(rs.getString(2)))
                }
            }
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
        return accountShelfDb(scope).use { db ->
            val existing = db.prepareStatement("SELECT shelf_book_id FROM legacy_book_mappings WHERE legacy_record_id=?").use { sql ->
                sql.setString(1, legacyRecordId)
                sql.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
            if (existing == shelfBookId) return@use false
            db.prepareStatement(
                """INSERT INTO legacy_book_mappings(
                       legacy_record_id, source_kind, legacy_doc_id, course_id, explain_source_id,
                       pages, printed_page_offset, pages_sha256, sha256, shelf_book_id, migrated_at
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(legacy_record_id) DO UPDATE SET
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
    }
}
