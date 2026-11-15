package com.coach.desktop.books

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal enum class AuthSnapshotProblem { INVALID_AUTH, SOURCE_CHANGED, WRITER_BUSY, IO_FAILED, CLEANUP_FAILED }

/** No parser diagnostics, paths or credential bytes are retained in public failures. */
internal class AuthSnapshotFailure(val problem: AuthSnapshotProblem) : Exception(problem.name)

internal enum class AuthCommitStatus {
    UNCHANGED, COMMITTED, SOURCE_CHANGED, INVALID_AUTH, WRITER_BUSY,
    ATOMIC_REPLACE_FAILED, IO_FAILED, ALREADY_FINISHED,
    OUTCOME_UNKNOWN,
}

internal data class AuthCommitReceipt(val status: AuthCommitStatus, val cleaned: Boolean)

internal fun interface AuthAtomicReplace {
    /** Implementations must compare again inside their atomic transaction, not just rename. */
    fun replace(staged: Path, source: Path, expectedHash: String, expectedModified: Long, refreshedHash: String): AuthCommitStatus
}

/**
 * Isolated auth-only CODEX_HOME, with one terminal commit/discard and no process-launch capability.
 *
 * The sidecar lock serializes our writers across processes. An independent transactional
 * committer also compares inside its transaction to exclude uncooperative writers. NIO rename
 * alone cannot do that: the default committer refuses refreshed writeback. Windows callers can
 * supply WindowsAuthAtomicCommit; unsupported transactions fail closed without a copy fallback.
 * This module deliberately neither discovers the user's home nor changes ACLs or configuration.
 */
internal class CodexAuthSnapshot private constructor(
    private val source: Path,
    private val original: Version,
    val home: Path,
    private val replace: AuthAtomicReplace,
) : AutoCloseable {
    private class Version(val digest: ByteArray, val attributes: BasicFileAttributes) {
        fun same(other: Version): Boolean = digest.contentEquals(other.digest) &&
            attributes.fileKey() == other.attributes.fileKey() &&
            attributes.size() == other.attributes.size() &&
            attributes.lastModifiedTime() == other.attributes.lastModifiedTime() &&
            attributes.creationTime() == other.attributes.creationTime()
    }

    private class AuthBytes(val bytes: ByteArray, val version: Version)
    private var finished = false
    private var cleaned = false

    /** Call only after the isolated child and its descendants have stopped writing the copy. */
    @Synchronized
    fun commit(): AuthCommitReceipt {
        if (finished) return AuthCommitReceipt(AuthCommitStatus.ALREADY_FINISHED, cleaned)
        finished = true
        var staged: Path? = null
        val status = try {
            guarded(source) {
                val refreshed = readAuth(home.resolve(AUTH_FILE))
                try {
                    if (!original.same(readAndForget(source))) return@guarded AuthCommitStatus.SOURCE_CHANGED
                    if (original.digest.contentEquals(refreshed.version.digest)) return@guarded AuthCommitStatus.UNCHANGED
                    // Stage on the original filesystem, never in the container's writable home.
                    staged = Files.createTempFile(source.parent, ".coach-auth-stage-", ".tmp")
                    FileChannel.open(staged, WRITE, TRUNCATE_EXISTING).use { channel ->
                        val buffer = ByteBuffer.wrap(refreshed.bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        channel.force(true)
                    }
                    // Check again after staging; a changed/replaced/missing source is never recreated.
                    if (!original.same(readAndForget(source))) return@guarded AuthCommitStatus.SOURCE_CHANGED
                    try {
                        val instant = original.attributes.lastModifiedTime().toInstant()
                        replace.replace(staged!!, source, original.digest.hex(),
                            Math.addExact(Math.multiplyExact(instant.epochSecond, 10_000_000L), instant.nano / 100L),
                            refreshed.version.digest.hex())
                    } catch (_: IOException) {
                        // No non-atomic copy/move fallback, and no child process can be launched here.
                        AuthCommitStatus.ATOMIC_REPLACE_FAILED
                    }
                } finally {
                    refreshed.bytes.fill(0)
                }
            }
        } catch (failure: AuthSnapshotFailure) {
            when (failure.problem) {
                AuthSnapshotProblem.INVALID_AUTH -> AuthCommitStatus.INVALID_AUTH
                AuthSnapshotProblem.SOURCE_CHANGED -> AuthCommitStatus.SOURCE_CHANGED
                AuthSnapshotProblem.WRITER_BUSY -> AuthCommitStatus.WRITER_BUSY
                else -> AuthCommitStatus.IO_FAILED
            }
        } catch (_: Exception) {
            AuthCommitStatus.IO_FAILED
        }
        val stageCleaned = runCatching { staged?.let { Files.deleteIfExists(it) }; true }.getOrDefault(false)
        val homeCleaned = runCatching { removeHome(home); true }.getOrDefault(false)
        cleaned = stageCleaned && homeCleaned
        return AuthCommitReceipt(status, cleaned)
    }

    /** Cancellation/failed exec discards the copy without touching the source. Idempotent. */
    @Synchronized
    override fun close() {
        finished = true
        try {
            removeHome(home)
            cleaned = true
        } catch (_: Exception) {
            throw AuthSnapshotFailure(AuthSnapshotProblem.CLEANUP_FAILED)
        }
    }

    companion object {
        private const val AUTH_FILE = "auth.json"
        private const val MAX_AUTH_BYTES = 1_048_576
        // No safe conditional-rename primitive exists in portable NIO. Refuse until a verified
        // transaction backend is explicitly supplied; never silently downgrade to check + move.
        private val atomicReplace = AuthAtomicReplace { _, _, _, _, _ -> AuthCommitStatus.ATOMIC_REPLACE_FAILED }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

        /** Explicit paths only. Copies exactly auth.json, never sibling configuration or history. */
        fun create(source: Path, runParent: Path): CodexAuthSnapshot = create(source, runParent, atomicReplace)

        /** Filesystem-failure seam; production consumers use the two-argument factory. */
        internal fun create(source: Path, runParent: Path, replace: AuthAtomicReplace): CodexAuthSnapshot {
            var home: Path? = null
            try {
                require(source.fileName.toString() == AUTH_FILE)
                val canonicalSource = source.parent.toRealPath().resolve(source.fileName)
                val parent = runParent.toRealPath()
                require(parent != canonicalSource.parent && !parent.startsWith(canonicalSource.parent))
                return guarded(canonicalSource) {
                    val auth = readAuth(canonicalSource)
                    try {
                        val allocated = Files.createTempDirectory(parent, "coach-auth-home-")
                        home = allocated
                        Files.write(allocated.resolve(AUTH_FILE), auth.bytes, CREATE_NEW, WRITE)
                        CodexAuthSnapshot(canonicalSource, auth.version, allocated, replace)
                    } finally {
                        auth.bytes.fill(0)
                    }
                }
            } catch (failure: Exception) {
                val cleaned = home?.let { runCatching { removeHome(it); true }.getOrDefault(false) } ?: true
                if (!cleaned) throw AuthSnapshotFailure(AuthSnapshotProblem.CLEANUP_FAILED)
                if (failure is AuthSnapshotFailure) throw failure
                throw AuthSnapshotFailure(AuthSnapshotProblem.IO_FAILED)
            }
        }

        /** Stable filename must not be unlinked after release: that would split the lock domain. */
        private fun <T> guarded(source: Path, action: () -> T): T {
            val lockPath = source.resolveSibling(".${source.fileName}.coach-refresh.lock")
            FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) throw AuthSnapshotFailure(AuthSnapshotProblem.WRITER_BUSY)
                lock.use { return action() }
            }
        }

        private fun readAndForget(path: Path): Version {
            val auth = try { readAuth(path) } catch (_: Exception) {
                throw AuthSnapshotFailure(AuthSnapshotProblem.SOURCE_CHANGED)
            }
            auth.bytes.fill(0)
            return auth.version
        }

        private fun readAuth(path: Path): AuthBytes {
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!before.isRegularFile || before.size() !in 1..MAX_AUTH_BYTES.toLong())
                throw AuthSnapshotFailure(AuthSnapshotProblem.INVALID_AUTH)
            val bytes = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { it.readNBytes(MAX_AUTH_BYTES + 1) }
            try {
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                val version = Version(digest, before)
                if (!version.same(Version(digest, after)) || bytes.size != before.size().toInt())
                    throw AuthSnapshotFailure(AuthSnapshotProblem.SOURCE_CHANGED)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                val json = Json.parseToJsonElement(text)
                if (json !is JsonObject || json.isEmpty()) throw AuthSnapshotFailure(AuthSnapshotProblem.INVALID_AUTH)
                return AuthBytes(bytes, version)
            } catch (failure: Exception) {
                bytes.fill(0)
                if (failure is AuthSnapshotFailure) throw failure
                throw AuthSnapshotFailure(AuthSnapshotProblem.INVALID_AUTH)
            }
        }

        /** Only the newly allocated home is traversed, without following links made by a child. */
        private fun removeHome(home: Path) {
            if (!Files.exists(home, NOFOLLOW_LINKS)) return
            Files.walkFileTree(home, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
