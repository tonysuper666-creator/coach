package com.coach.client

import com.coach.domain.LedgerJson
import com.coach.domain.json
import com.coach.domain.str
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.jsonObject

/** Keep secrets out of generated toString/exception messages. */
class PairedCredentials(val deviceId: String, val token: String, val role: String = "owner") {
    init { require(role in setOf("owner", "friend")) }
}

internal expect fun checkCoachNetworkPolicy(url: Url)

fun validateCoachBaseUrl(baseUrl: String) {
    val url = Url(baseUrl)
    require(url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) && url.user == null && url.password == null &&
        url.parameters.isEmpty() && url.fragment.isEmpty() && url.encodedPath in listOf("", "/")) { "Invalid Coach server address" }
    checkCoachNetworkPolicy(url)
}

class PairingClient(private val baseUrl: String) {
    init { validateCoachBaseUrl(baseUrl) }
    private val client = HttpClient(CoachHttpEngineFactory) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis=10000; connectTimeoutMillis=3000; socketTimeoutMillis=10000 }
    }
    suspend fun sable(open: suspend (String, String) -> Unit, issuer: String = SABLE_ORIGIN): PairedCredentials =
        sableLogin(client, baseUrl, open, issuer)

    suspend fun pair(code: String, name: String): PairedCredentials {
        require(code.matches(Regex("[0-9]{6}"))) { "Enter a six digit code" }
        val response = client.post("${baseUrl.trimEnd('/')}/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody(json("code" to code, "name" to name).toString())
        }
        check(response.status == HttpStatusCode.OK) { "Pairing rejected; request a new code on your Mac" }
        val body = LedgerJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return PairedCredentials(body.str("deviceId"), body.str("token"), body.str("role").ifBlank { "owner" }).also {
            check(it.deviceId.isNotBlank() && it.token.length == 43) { "Invalid pairing response" }
        }
    }
    /** Friends never probe hello, snapshots or the owner's learning ledger. */
    suspend fun friendAuthorized(credentials: PairedCredentials, startup: Boolean = false): Boolean {
        val response = client.get("${baseUrl.trimEnd('/')}/v1/public/status") {
            if (startup) header("X-Coach-Startup", "1")
            header("X-Coach-Device", credentials.deviceId)
            header(HttpHeaders.Authorization, "Bearer ${credentials.token}")
            coachVersionHeader()
        }
        return response.status != HttpStatusCode.Unauthorized
    }
    fun close() = client.close()
}
