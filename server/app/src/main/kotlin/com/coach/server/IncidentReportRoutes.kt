package com.coach.server

import com.coach.domain.IncidentRedactor
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

internal class IncidentReportStore(
    private val root: Path,
    private val maxPerHour: Int = 10,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val deviceName = Regex("[A-Za-z0-9_-]{1,80}")

    fun accept(deviceId: String, body: String): IncidentAccept {
        if (!deviceName.matches(deviceId)) return IncidentAccept.Rejected
        val redacted = IncidentRedactor.redact(body)
        if (redacted.isBlank()) return IncidentAccept.Rejected
        val dir = root.resolve(deviceId)
        Files.createDirectories(dir)
        val hourAgo = now() - 3_600_000
        val recent = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".txt") }
                .filter { Files.getLastModifiedTime(it).toMillis() >= hourAgo }
                .count()
        }
        if (recent >= maxPerHour) return IncidentAccept.Limited
        val file = dir.resolve("${now()}-${recent + 1}.txt")
        Files.writeString(file, redacted)
        Files.setLastModifiedTime(file, FileTime.fromMillis(now()))
        val all = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".txt") }
                .sorted { a, b -> Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b)) }
                .toList()
        }
        if (all.size > 200) all.dropLast(200).forEach { Files.deleteIfExists(it) }
        return IncidentAccept.Stored
    }

    fun storedText(deviceId: String): String {
        val dir = root.resolve(deviceId)
        if (!Files.isDirectory(dir)) return ""
        return Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".txt") }
                .sorted()
                .map { Files.readString(it) }
                .toList()
                .joinToString("\n")
        }
    }
}

internal enum class IncidentAccept { Stored, Limited, Rejected }

/** Authenticated inbox for a redacted incident record. No list or admin page. */
fun Application.incidentReportRoutes(
    directory: Path,
    maxBytes: Int = 256 * 1024,
    maxPerHour: Int = 10,
    now: () -> Long = System::currentTimeMillis,
) {
    val store = IncidentReportStore(directory, maxPerHour, now)
    routing {
        post("/v1/incidents") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val channel = call.receiveChannel()
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = channel.readAvailable(chunk, 0, chunk.size)
                if (n < 0) break
                if (buffer.size() + n > maxBytes) {
                    call.respond(HttpStatusCode.PayloadTooLarge)
                    return@post
                }
                buffer.write(chunk, 0, n)
            }
            val body = buffer.toString(Charsets.UTF_8)
            if (body.isBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            when (store.accept(call.attributes[observedDevice], body)) {
                IncidentAccept.Stored -> call.respond(HttpStatusCode.NoContent)
                IncidentAccept.Limited -> call.respond(HttpStatusCode.TooManyRequests)
                IncidentAccept.Rejected -> call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
}
