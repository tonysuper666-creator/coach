package com.coach.desktop.incident

import com.coach.domain.IncidentRedactor
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque

internal class PendingOffer(val files: List<String>, val text: String)

/**
 * Local incident files. Nothing here is uploaded; the prompt sends [PendingOffer.text] only after a click.
 * Records rotate by age and by total size, and every string is redacted before it hits disk.
 */
internal class IncidentStore(
    val root: Path,
    private val maxBytes: Long = 50L * 1024 * 1024,
    private val maxAgeDays: Long = 14,
    private val uploadBytes: Int = 256 * 1024,
    private val clock: () -> Instant = Instant::now,
    private val pid: () -> Long = { ProcessHandle.current().pid() },
    private val processStart: () -> Long? = { currentProcessStart() },
    private val alive: (pid: Long, processStart: Long) -> Boolean = ::processStillRunning,
) {
    private val operations = ArrayDeque<String>()
    private var sequence = 0L
    private var sessionStarted = false

    @Synchronized
    fun note(name: String) {
        if (!name.matches(OPERATION)) return
        operations.addLast(name)
        while (operations.size > 12) operations.removeFirst()
    }

    @Synchronized
    fun record(kind: String, operation: String, detail: String? = null, error: Throwable? = null) {
        if (!kind.matches(OPERATION)) return
        note(operation)
        Files.createDirectories(root)
        val raw = buildString {
            append("kind: ").append(kind).append('\n')
            append("at: ").append(clock().truncatedTo(ChronoUnit.SECONDS)).append('\n')
            append("version: ").append(coachVersion()).append('\n')
            append("os: ").append(osLabel()).append('\n')
            append("operations: ").append(operations.joinToString(", ")).append('\n')
            append("location:\n")
            append(location(error)).append('\n')
            val message = (detail ?: error?.message).orEmpty().take(4_000)
            if (error != null) append("error: ").append(error.javaClass.name).append('\n')
            if (message.isNotBlank()) append("detail:\n").append(message).append('\n')
        }
        val safe = IncidentRedactor.redact(raw).take(24_000)
        sequence += 1
        val name = "inc-${clock().toEpochMilli()}-$sequence-$kind.txt"
        val target = root.resolve(name)
        val temp = root.resolve("$name.part")
        Files.writeString(temp, safe)
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        rotate()
    }

    fun recordImportFailure(detail: String) = record("import_failure", "import_book", detail)

    fun recordSyncFailure(detail: String) = record("sync_failure", "sync_push", detail)

    /**
     * Previous process did not exit cleanly. Returns the text that would be sent, or null.
     *
     * Several copies of the app can share one incident directory, so session.txt keeps one open entry per
     * process. An open entry whose process is gone is an unclean exit; one whose process still runs stays (TON-1941).
     */
    @Synchronized
    fun beginSession(): PendingOffer? {
        Files.createDirectories(root)
        note("app_start")
        return withSessionLock {
            val mine = pid()
            val start = processStart()
            val others = readSessions().filter { it.open && !it.isProcess(mine, start) }
            val running = others.filter { it.pid != null && it.processStart != null && alive(it.pid, it.processStart) }
            val ended = others - running.toSet()
            val offer = if (ended.isNotEmpty()) {
                if (ended.any { marker -> incidentFiles().none { it.epoch >= marker.startedAt } }) {
                    record("unclean_exit", "app_start", "上次进程没有正常退出。")
                }
                pack(unconsented())
            } else null
            writeSessions(running + SessionMarker(true, clock().toEpochMilli(), mine, start))
            sessionStarted = true
            offer
        }
    }

    /** Drops only this process's entry; entries of other copies stay for their own close or the next start. */
    @Synchronized
    fun markClean() {
        if (!sessionStarted || !Files.isDirectory(root)) return
        withSessionLock {
            val mine = pid()
            val start = processStart()
            val open = readSessions().filter { it.open }
            if (open.none { it.isProcess(mine, start) }) return@withSessionLock
            val rest = open.filter { !it.isProcess(mine, start) }
            writeSessions(rest.ifEmpty { listOf(SessionMarker(false, clock().toEpochMilli(), mine, start)) })
        }
    }

    @Synchronized
    fun decline(offer: PendingOffer) = remember(offer)

    @Synchronized
    fun markSent(offer: PendingOffer) = remember(offer)

    @Synchronized
    fun clear() {
        if (!Files.isDirectory(root)) return
        Files.list(root).use { stream ->
            stream.forEach { path ->
                val name = path.fileName.toString()
                if (name.startsWith("inc-") || name == "consent.txt") Files.deleteIfExists(path)
            }
        }
    }

    @Synchronized
    fun incidentFiles(): List<IncidentFile> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter { it.fileName.toString().startsWith("inc-") && it.fileName.toString().endsWith(".txt") }
                .map { path ->
                    val name = path.fileName.toString()
                    IncidentFile(name, name.removePrefix("inc-").substringBefore('-').toLongOrNull() ?: 0L, Files.size(path))
                }
                .toList()
                .sortedBy { it.epoch }
        }
    }

    private fun unconsented(): List<IncidentFile> {
        val done = consented()
        return incidentFiles().filter { it.name !in done }
    }

    private fun pack(files: List<IncidentFile>): PendingOffer? {
        if (files.isEmpty()) return null
        val included = ArrayList<String>()
        val builder = StringBuilder()
        var bytes = 0
        for (file in files.asReversed()) {
            val text = Files.readString(root.resolve(file.name))
            val piece = if (builder.isEmpty()) text else "$text\n\n"
            val size = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + size > uploadBytes) continue
            builder.insert(0, if (builder.isEmpty()) text else text + "\n\n")
            bytes += size
            included += file.name
        }
        if (builder.isEmpty()) return null
        return PendingOffer(included, builder.toString())
    }

    private fun remember(offer: PendingOffer) {
        Files.createDirectories(root)
        val names = consented().toMutableSet()
        names.addAll(offer.files)
        Files.writeString(root.resolve("consent.txt"), names.sorted().joinToString("\n"))
    }

    private fun consented(): Set<String> {
        val file = root.resolve("consent.txt")
        if (!Files.isRegularFile(file)) return emptySet()
        return Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun rotate() {
        val cutoff = clock().minus(maxAgeDays, ChronoUnit.DAYS).toEpochMilli()
        val files = incidentFiles().toMutableList()
        for (file in files.toList()) {
            if (file.epoch in 1 until cutoff) {
                Files.deleteIfExists(root.resolve(file.name))
                files.remove(file)
            }
        }
        var total = files.sumOf { it.bytes }
        for (file in files) {
            if (total <= maxBytes) break
            Files.deleteIfExists(root.resolve(file.name))
            total -= file.bytes
        }
    }

    /** Entries back to back: "open" or "clean", then startedAt, pid and the process start time, one per line. */
    private fun readSessions(): List<SessionMarker> {
        val file = root.resolve("session.txt")
        if (!Files.isRegularFile(file)) return emptyList()
        val markers = ArrayList<SessionMarker>()
        var state: String? = null
        val values = ArrayList<Long?>()
        fun flush() {
            val started = values.getOrNull(0)
            if (state != null && started != null) {
                markers += SessionMarker(state == "open", started, values.getOrNull(1), values.getOrNull(2))
            }
            values.clear()
        }
        for (raw in Files.readAllLines(file)) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line == "open" || line == "clean") {
                flush()
                state = line
            } else {
                values += line.toLongOrNull()
            }
        }
        flush()
        return markers
    }

    private fun writeSessions(markers: List<SessionMarker>) {
        val body = markers.joinToString("") { marker ->
            (if (marker.open) "open" else "clean") + "\n" + marker.startedAt + "\n" + (marker.pid ?: "") + "\n" +
                (marker.processStart?.let { "$it\n" } ?: "")
        }
        // Replace the marker whole and flush it: a power cut right after start must leave "open", not an empty file.
        val target = root.resolve("session.txt")
        val temp = root.resolve("session.txt.part")
        FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val bytes = ByteBuffer.wrap(body.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Copies of the app starting or closing at the same moment take turns on session.txt. */
    private fun <T> withSessionLock(block: () -> T): T = synchronized(SESSION_LOCK) {
        FileChannel.open(root.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { block() }
        }
    }

    private data class SessionMarker(val open: Boolean, val startedAt: Long, val pid: Long?, val processStart: Long?) {
        /** Same pid and, when both sides know it, the same start time: a reused pid is another process. */
        fun isProcess(otherPid: Long, otherStart: Long?) =
            pid == otherPid && (processStart == null || otherStart == null || processStart == otherStart)
    }
}

/** One JVM holds one file lock per file; stores in the same process wait on this first. */
private val SESSION_LOCK = Any()

internal fun currentProcessStart(): Long? =
    ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(null)

/** A pid alone can be reused by an unrelated program, so the start time has to match as well. */
internal fun processStillRunning(pid: Long, processStart: Long): Boolean =
    ProcessHandle.of(pid).map { handle ->
        handle.isAlive && handle.info().startInstant().map { it.toEpochMilli() == processStart }.orElse(false)
    }.orElse(false)

internal data class IncidentFile(val name: String, val epoch: Long, val bytes: Long)

private val OPERATION = Regex("[a-z][a-z0-9_]{0,40}")

internal fun location(error: Throwable?): String {
    val frames = error?.stackTrace?.filter { it.className.startsWith("com.coach.") }?.take(8).orEmpty()
    if (frames.isEmpty()) return "  unknown"
    return frames.joinToString("\n") { frame ->
        "  at ${frame.className}.${frame.methodName}(${frame.fileName ?: "Unknown"}:${frame.lineNumber})"
    }
}

internal fun osLabel(): String = listOf("os.name", "os.version", "os.arch")
    .map { System.getProperty(it).orEmpty().trim() }
    .filter { it.isNotEmpty() }
    .joinToString(" ")

internal fun coachVersion(): String {
    System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() }?.let { return it }
    val text = IncidentStore::class.java.getResourceAsStream("/coach-version.txt")?.bufferedReader()?.use { it.readText() }?.trim()
    return text?.takeIf { it.isNotEmpty() } ?: "dev"
}
