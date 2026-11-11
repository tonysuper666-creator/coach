package com.coach.server

import kotlinx.serialization.json.*
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Metadata-only, process-owned admission ledger. All counters and the seal share one monitor. */
class ActivityCoverage internal constructor(
    private val directory: Path,
    private val day: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) },
    private val persist: ((Path, ByteArray) -> Unit)? = null,
    private val instant: () -> Instant = Instant::now,
    private val nano: () -> Long = System::nanoTime,
) : AutoCloseable {
    val lifetime: String = UUID.randomUUID().toString()
    private var sequence = 0L
    private var ingress = 0L
    private var failures = 0L
    private var rejected = 0L
    private var tail = "0".repeat(64)
    private val active = mutableSetOf<Long>()
    private var sealed: String? = null
    private var closed = false
    private val origin = nano()
    private var listener: ServerSocketChannel? = null
    @Volatile private var control: SocketChannel? = null
    private var worker: Thread? = null
    private var socketPath: Path? = null
    // Detached replies/pool waits must also have finished after their socket disconnects.
    var workQuiet: () -> Boolean = { true }

    init { record("open") }

    @Synchronized fun begin(kind: String): Long? {
        check(kind in setOf("http", "ws"))
        if (closed || sealed != null) {
            rejected++
            record("rejected")
            return null
        }
        val id = ++ingress
        active += id
        record("begin", kind, id)
        return id
    }

    @Synchronized fun end(id: Long) {
        if (!active.remove(id)) { failures++; return }
        record("end", id = id)
    }

    @Synchronized fun writeFailed() { failures++; record("write_failure") }

    private fun record(event: String, kind: String? = null, id: Long? = null) {
        val row = buildJsonObject {
            put("protocol", 1); put("lifetime", lifetime); put("pid", ProcessHandle.current().pid())
            put("jvmStart", ProcessHandle.current().info().startInstant().orElseThrow().toString())
            put("seq", ++sequence); put("prev", tail); put("at", instant().toString())
            put("monoNs", nano()-origin); put("event", event)
            put("ingress", ingress); put("active", active.size); put("failures", failures); put("rejected", rejected)
            kind?.let { put("kind", it) }; id?.let { put("id", it) }
            sealed?.let { put("seal", it) }
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            privateFiles.directory(directory)
            val path = directory.resolve("activity-$lifetime-${day()}.log")
            val bytes = row + byteArrayOf(10)
            if (persist != null) persist.invoke(path, bytes) else {
                privateFiles.file(path)
                FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                // Windows cannot open a directory as a FileChannel; retain POSIX directory fsync.
                if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
                    FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            }
            tail = MessageDigest.getInstance("SHA-256").digest(row).joinToString("") { "%02x".format(it) }
        } catch (error: PrivateFilePermissionException) { throw error
        } catch (error: UnsupportedOperationException) { throw error
        } catch (_: Exception) { failures++ } // sticky; never turn a write failure into an idle proof
    }

    @Synchronized internal fun snapshot(): JsonObject = buildJsonObject {
        put("protocol", 1); put("lifetime", lifetime); put("pid", ProcessHandle.current().pid())
        put("jvmStart", ProcessHandle.current().info().startInstant().orElseThrow().toString())
        put("seq", sequence); put("tail", tail); put("ingress", ingress); put("active", active.size)
        put("failures", failures); put("rejected", rejected); put("closed", closed)
        put("monoNs", nano()-origin); put("at", instant().toString())
        put("workQuiet", workQuiet()); sealed?.let { put("seal", it) }
    }

    @Synchronized internal fun seal(expected: JsonObject): JsonObject {
        check(!closed && sealed == null && failures == 0L && active.isEmpty() && workQuiet())
        check(expected["lifetime"] == JsonPrimitive(lifetime) && expected["ingress"] == JsonPrimitive(ingress))
        check(expected["rejected"] == JsonPrimitive(rejected))
        sealed = UUID.randomUUID().toString()
        record("seal")
        check(failures == 0L)
        return snapshot()
    }

    @Synchronized private fun release() {
        if (!closed && sealed != null) { sealed = null; record("release") }
    }

    /** Local filesystem permissions are the authority; no learner HTTP token/control endpoint. */
    fun startControl(path: Path) {
        check(listener == null)
        privateFiles.directory(path.toAbsolutePath().parent)
        // Never unlink a possibly-live owner's socket. A stale path requires explicit diagnosis.
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        var bound = false
        try {
            server.bind(UnixDomainSocketAddress.of(path))
            bound = true
            privateFiles.secureSocket(path)
        } catch (e: Exception) {
            server.close()
            if (bound) try { Files.deleteIfExists(path) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
        listener = server; socketPath = path
        worker = Thread({
            while (server.isOpen) {
                try {
                    server.accept().use { client ->
                        control = client
                        try {
                            val input = java.nio.channels.Channels.newInputStream(client)
                            val output = java.nio.channels.Channels.newOutputStream(client)
                            while (true) {
                                val bytes = java.io.ByteArrayOutputStream()
                                while (true) {
                                    val b = input.read()
                                    if (b == -1) return@use
                                    if (b == 10) break
                                    check(bytes.size() < 8192)
                                    bytes.write(b)
                                }
                                val command = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                                val result = try {
                                    when (command["command"]?.jsonPrimitive?.content) {
                                        "snapshot" -> snapshot()
                                        "seal" -> seal(command.getValue("expected").jsonObject)
                                        else -> error("unsupported")
                                    }
                                } catch (_: Exception) { buildJsonObject { put("error", "coverage unavailable") } }
                                output.write((result.toString()+"\n").toByteArray()); output.flush()
                            }
                        } finally { release(); control = null }
                    }
                } catch (_: Exception) { if (!server.isOpen) break }
            }
        }, "coach-activity-control").apply { isDaemon = true; start() }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            // Called only after both listeners have stopped. A shutdown without a held seal is unusable.
            record("closed")
            closed = true
        }
        listener?.close(); control?.close()
        worker?.join(2000)
        socketPath?.let { Files.deleteIfExists(it) }
    }
}
