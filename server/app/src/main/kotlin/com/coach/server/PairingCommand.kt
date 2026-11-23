package com.coach.server

import com.coach.domain.LedgerJson
import com.coach.domain.str
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** File fallback avoids adding a native keychain dependency; never accept symlinks. */
internal fun secureTokenFile(path: Path) {
    require(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Desktop token must be a regular file" }
    privateFiles.directory(path.toAbsolutePath().parent)
    privateFiles.secureFile(path)
}

/** Talks to the running server; never opens its locked database or puts a token in argv/URL. */
internal fun pairingCommand(args: Array<String>, data: Path) {
    val path = Path.of(System.getenv("COACH_TOKEN_FILE") ?: data.resolve("device-token").toString())
    secureTokenFile(path)
    val port = System.getenv("COACH_PORT")?.toInt() ?: 18791
    val endpoint = when (args.first()) {
        "pair-code" -> { require(args.size == 1 || (args.size == 3 && args[1] == "--friend")); "/v1/pairing-code" }
        "revoke-friend" -> { require(args.size == 2); "/v1/friends/revoke" }
        "sable-users" -> { require(args.size == 1); "/v1/sable/users" }
        "disable-sable" -> { require(args.size == 2); "/v1/sable/disable" }
        "devices" -> { require(args.size == 1); "/v1/devices" }
        "books-inventory" -> { require(args.size == 1); "/v1/admin/books/migration/inventory" }
        "books-migrate" -> { require(args.size == 1); "/v1/admin/books/migration/run" }
        "books-verify" -> { require(args.size == 1); "/v1/admin/books/migration/verify" }
        else -> {
            require(args.size == 2 && args[1].matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "usage: app revoke DEVICE_ID" }
            "/v1/devices/${args[1]}"
        }
    }
    val method = when (args.first()) {
        "pair-code", "revoke-friend", "disable-sable", "books-migrate" -> "POST"
        "devices", "sable-users", "books-inventory", "books-verify" -> "GET"
        else -> "DELETE"
    }
    val body = when {
        args.first() == "pair-code" && args.size == 3 -> com.coach.domain.json("friend" to args[2]).toString()
        args.first() == "disable-sable" -> com.coach.domain.json("username" to args[1]).toString()
        args.first() == "revoke-friend" -> com.coach.domain.json("friend" to args[1]).toString()
        else -> ""
    }
    val timeoutSeconds = if (args.first() in setOf("books-inventory", "books-migrate", "books-verify")) 300L else 10L
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$endpoint"))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("Authorization", "Bearer ${Files.readString(path).trim()}")
        .header("X-Coach-Device", System.getenv("COACH_DEVICE_ID") ?: "desktop")
        .method(method, HttpRequest.BodyPublishers.ofString(body)).build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in 200..299) { "Coach management request failed (${response.statusCode()})" }
    if (args.first() == "pair-code") {
        val body = LedgerJson.parseToJsonElement(response.body()).jsonObject
        println("Pairing code: ${body.str("code")} (single use; expires in 5 minutes)")
    } else if (method == "DELETE" || args.first() == "revoke-friend") println("Device(s) revoked")
    else println(response.body())
}
