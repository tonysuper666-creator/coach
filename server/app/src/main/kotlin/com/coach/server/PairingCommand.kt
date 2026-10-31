package com.coach.server

import com.coach.domain.LedgerJson
import com.coach.domain.str
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

/** File fallback avoids adding a native keychain dependency; never accept symlinks. */
internal fun secureTokenFile(path: Path) {
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Desktop token must be a regular file" }
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
}

/** Talks to the running server; never opens its locked database or puts a token in argv/URL. */
internal fun pairingCommand(args: Array<String>, data: Path) {
    val path = Path.of(System.getenv("COACH_TOKEN_FILE") ?: data.resolve("device-token").toString())
    secureTokenFile(path)
    val port = System.getenv("COACH_PORT")?.toInt() ?: 18791
    val endpoint = when (args.first()) {
        "pair-code" -> { require(args.size == 1); "/v1/pairing-code" }
        "devices" -> { require(args.size == 1); "/v1/devices" }
        else -> {
            require(args.size == 2 && args[1].matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "usage: app revoke DEVICE_ID" }
            "/v1/devices/${args[1]}"
        }
    }
    val method = when (args.first()) { "pair-code" -> "POST"; "devices" -> "GET"; else -> "DELETE" }
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$endpoint"))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer ${Files.readString(path).trim()}")
        .header("X-Coach-Device", System.getenv("COACH_DEVICE_ID") ?: "desktop")
        .method(method, HttpRequest.BodyPublishers.noBody()).build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in 200..299) { "Coach management request failed (${response.statusCode()})" }
    if (args.first() == "pair-code") {
        val body = LedgerJson.parseToJsonElement(response.body()).jsonObject
        println("Pairing code: ${body.str("code")} (single use; expires in 5 minutes)")
    } else if (method == "DELETE") println("Device revoked")
    else println(response.body())
}
