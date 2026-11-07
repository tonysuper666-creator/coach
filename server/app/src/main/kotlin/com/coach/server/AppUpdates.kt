package com.coach.server

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

/** A private, immutable APK named by its digest; latest.json is replaced atomically by publish-app.py. */
class AppUpdates(private val directory: File = File(System.getenv("COACH_APP_UPDATE_DIR")
    ?: File(System.getProperty("user.home"), "Coach/builds/updates").path)) {
    fun latest(packageName: String): JsonObject? {
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")))
        val manifest = directory.resolve("$packageName/latest.json")
        if (!manifest.isFile) return null
        val metadata = Json.parseToJsonElement(manifest.readText()).jsonObject
        require(metadata.getValue("packageName").jsonPrimitive.content == packageName)
        require(metadata.getValue("versionCode").jsonPrimitive.long in 2..2100000000)
        require(metadata.getValue("versionName").jsonPrimitive.content.length in 1..100)
        val digest = metadata.getValue("sha256").jsonPrimitive.content
        val file = apk(digest) ?: error("Missing update APK")
        require(file.length() == metadata.getValue("size").jsonPrimitive.long)
        return metadata
    }
    fun apk(digest: String): File? {
        if (!digest.matches(Regex("[a-f0-9]{64}"))) return null
        return directory.resolve("$digest.apk").takeIf { it.isFile }
    }
}

// ledgerRoutes installs the same device-authentication interceptor for these routes as for learning.
fun Route.appUpdates(updates: AppUpdates) {
    get("/v1/app-update") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val packageName = call.request.queryParameters["packageName"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val metadata = withContext(Dispatchers.IO) { runCatching { updates.latest(packageName) } }
        if (metadata.isFailure) return@get call.respond(HttpStatusCode.ServiceUnavailable)
        val value = metadata.getOrNull() ?: return@get call.respond(HttpStatusCode.NoContent)
        call.respondText(value.toString(), ContentType.Application.Json)
    }
    get("/app.apk") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val file = updates.apk(call.request.queryParameters["sha256"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondFile(file)
    }
}
