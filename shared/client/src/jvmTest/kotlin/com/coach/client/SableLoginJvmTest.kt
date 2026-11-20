package com.coach.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class SableLoginJvmTest {
    @Test fun retriesRealUnknownHostExceptionDuringBackgroundNetworkBlock() = runBlocking {
        val issuer = "http://127.0.0.1:18080"
        val friendUrl = "http://127.0.0.1:18792"
        val validToken = "u".repeat(43)
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        var tokenPolls = 0
        val delays = mutableListOf<Long>()
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/device/code" -> respond(
                    """{"device_code":"dev-jvm","user_code":"BCDF-GHJK","expires_in":120,"interval":5,"verification_uri_complete":"$issuer/link?code=BCDF-GHJK"}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                "/api/device/token" -> {
                    tokenPolls += 1
                    when (tokenPolls) {
                        1, 2 -> throw UnknownHostException("Unable to resolve host: GetAddrInfoHandler::run: network access blocked")
                        else -> respond("""{"access_token":"upstream-token"}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
                "/v1/public/sable" -> respond(
                    """{"deviceId":"sable-jvm-1","token":"$validToken","role":"friend"}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        })
        try {
            val result = sableLogin(client, friendUrl, { _, _ -> }, issuer) { ms -> delays += ms }
            assertEquals("sable-jvm-1", result.deviceId)
            assertEquals(validToken, result.token)
            assertEquals("friend", result.role)
            assertEquals(3, tokenPolls)
            assertEquals(listOf(5_000L, 10_000L, 15_000L), delays)
        } finally {
            client.close()
        }
    }
}
