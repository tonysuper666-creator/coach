package com.coach.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SableLoginTest {
    private val issuer = "http://127.0.0.1:18080"
    private val friendUrl = "http://127.0.0.1:18792"
    private val validToken = "t".repeat(43)

    private fun codeGrantJson(intervalSec: Int = 5) =
        """{"device_code":"dev-1","user_code":"BCDF-GHJK","expires_in":120,"interval":$intervalSec,"verification_uri_complete":"$issuer/link?code=BCDF-GHJK"}"""

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test fun retriesTransientNetworkErrorsWithBoundedBackoffAndRecovers() = runBlocking {
        var tokenPolls = 0
        val delays = mutableListOf<Long>()
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/device/code" -> respond(codeGrantJson(), HttpStatusCode.OK, jsonHeaders())
                "/api/device/token" -> {
                    tokenPolls += 1
                    when (tokenPolls) {
                        1 -> throw HttpRequestTimeoutException(request)
                        2 -> throw IllegalStateException("offline-1")
                        3 -> throw IllegalStateException("offline-2")
                        4 -> throw IllegalStateException("offline-3")
                        5 -> respond("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest, jsonHeaders())
                        else -> respond("""{"access_token":"upstream-token"}""", HttpStatusCode.OK, jsonHeaders())
                    }
                }
                "/v1/public/sable" -> respond(
                    """{"deviceId":"sable-dev-1","token":"$validToken","role":"friend"}""",
                    HttpStatusCode.OK,
                    jsonHeaders(),
                )
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        })
        try {
            val result = sableLogin(client, friendUrl, { _, code -> assertEquals("BCDF-GHJK", code) }, issuer) { ms ->
                delays += ms
            }
            assertEquals("sable-dev-1", result.deviceId)
            assertEquals(validToken, result.token)
            assertEquals("friend", result.role)
            assertEquals(6, tokenPolls)
            assertEquals(listOf(5_000L, 10_000L, 15_000L, 15_000L, 15_000L, 5_000L), delays)
        } finally {
            client.close()
        }
    }

    @Test fun slowDownUpdatesBaseIntervalAcrossSubsequentNetworkRecovery() = runBlocking {
        var tokenPolls = 0
        val delays = mutableListOf<Long>()
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/device/code" -> respond(codeGrantJson(), HttpStatusCode.OK, jsonHeaders())
                "/api/device/token" -> {
                    tokenPolls += 1
                    when (tokenPolls) {
                        1 -> respond("""{"error":"slow_down"}""", HttpStatusCode.BadRequest, jsonHeaders())
                        2 -> throw IllegalStateException("background-network-blocked")
                        3 -> respond("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest, jsonHeaders())
                        else -> respond("""{"access_token":"upstream-token"}""", HttpStatusCode.OK, jsonHeaders())
                    }
                }
                "/v1/public/sable" -> respond(
                    """{"deviceId":"sable-dev-2","token":"$validToken","role":"friend"}""",
                    HttpStatusCode.OK,
                    jsonHeaders(),
                )
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        })
        try {
            val result = sableLogin(client, friendUrl, { _, _ -> }, issuer) { ms -> delays += ms }
            assertEquals("sable-dev-2", result.deviceId)
            assertEquals(4, tokenPolls)
            assertEquals(listOf(5_000L, 10_000L, 15_000L, 10_000L), delays)
        } finally {
            client.close()
        }
    }

    @Test fun cancellationDuringPollingPropagatesImmediately() = runBlocking {
        var tokenPolls = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/device/code" -> respond(codeGrantJson(), HttpStatusCode.OK, jsonHeaders())
                "/api/device/token" -> {
                    tokenPolls += 1
                    throw CancellationException("user cancelled")
                }
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        })
        try {
            assertFailsWith<CancellationException> {
                sableLogin(client, friendUrl, { _, _ -> }, issuer) { }
            }
            assertEquals(1, tokenPolls)
        } finally {
            client.close()
        }
    }

    @Test fun accessDeniedFailsImmediatelyWithoutRetry() = runBlocking {
        var tokenPolls = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/device/code" -> respond(codeGrantJson(), HttpStatusCode.OK, jsonHeaders())
                "/api/device/token" -> {
                    tokenPolls += 1
                    respond("""{"error":"access_denied"}""", HttpStatusCode.BadRequest, jsonHeaders())
                }
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        })
        try {
            val err = assertFailsWith<SableLoginError> {
                sableLogin(client, friendUrl, { _, _ -> }, issuer) { }
            }
            assertEquals("你已拒绝授权。", err.message)
            assertEquals(1, tokenPolls)
        } finally {
            client.close()
        }
    }
}
