package com.coach.desktop.incident

import com.coach.domain.IncidentRedactor
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
) {
    private val operations = ArrayDeque<String>()
    private var sequence = 0L

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

    /** Previous process did not exit cleanly. Returns the text that would be sent, or null. */
    @Synchronized
    fun beginSession(): PendingOffer? {
        Files.createDirectories(root)
        note("app_start")
        val previous = readSession()
        val offer = if (previous != null && previous.open && previous.pid != pid()) {
            if (incidentFiles().none { it.epoch >= previous.startedAt }) {
                record("unclean_exit", "app_start", "上次进程没有正常退出。")
            }
            pack(unconsented())
        } else null
        writeSession(open = true)
        return offer
    }

    @Synchronized
    fun markClean() {
        if (Files.isDirectory(root)) writeSession(open = false)
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

    private fun readSession(): SessionMarker? {
        val file = root.resolve("session.txt")
        if (!Files.isRegularFile(file)) return null
        val lines = Files.readAllLines(file)
        val state = lines.firstOrNull()?.trim().orEmpty()
        val started = lines.getOrNull(1)?.trim()?.toLongOrNull() ?: return null
        val process = lines.getOrNull(2)?.trim()?.toLongOrNull()
        return SessionMarker(open = state == "open", startedAt = started, pid = process)
    }

    private fun writeSession(open: Boolean) {
        val body = (if (open) "open" else "clean") + "\n" + clock().toEpochMilli() + "\n" + pid() + "\n"
        Files.writeString(root.resolve("session.txt"), body)
    }

    private data class SessionMarker(val open: Boolean, val startedAt: Long, val pid: Long?)
}

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
