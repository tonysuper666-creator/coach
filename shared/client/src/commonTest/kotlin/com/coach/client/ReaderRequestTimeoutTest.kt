package com.coach.client

import com.coach.protocol.ReaderTimeoutPolicy
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class ReaderRequestTimeoutTest {
    @Test fun readerRetriesOneHttpRejectionIncludingNonJsonErrors() = runBlocking {
        for (collection in listOf(false, true)) {
            for (status in listOf(429, 500, 503, 401, 403)) {
                var calls = 0
                val client = CourseHttp("http://127.0.0.1:1", "test", "test", "library", { MockEngine {
                    calls++
                    respond("unavailable", io.ktor.http.HttpStatusCode.fromValue(status), headersOf(HttpHeaders.RetryAfter, "0"))
                } })
                try {
                    val reply = withReaderRetry({ true }) {
                        if (collection) client.askCollection(JsonObject(emptyMap())) else client.explainLibrary(JsonObject(emptyMap()))
                    }
                    assertEquals(status, reply.statusCode)
                    assertEquals(if (status == 429 || status >= 500) 2 else 1, calls)
                    if (calls == 2) assertEquals(0L, reply.retryAfterMs)
                } finally { client.close() }
            }
        }
    }

    @Test fun retryUsesServerDelayAndCanRecoverOrBeCancelled() = runBlocking {
        var calls = 0
        val client = CourseHttp("http://127.0.0.1:1", "test", "test", "library", { MockEngine {
            calls++
            if (calls == 1) respond("""{"retryAfterSec":1}""", io.ktor.http.HttpStatusCode.TooManyRequests)
            else respond("""{"text":"ok"}""")
        } })
        try {
            val start = kotlin.time.TimeSource.Monotonic.markNow()
            val reply = withReaderRetry({ true }) { client.explainLibrary(JsonObject(emptyMap())) }
            assertNotNull(reply.body)
            assertTrue(start.elapsedNow().inWholeMilliseconds >= 1000)
            assertEquals(2, calls)
        } finally { client.close() }

        val started = CompletableDeferred<Unit>()
        var attempts = 0
        val request = launch {
            withReaderRetry({ true }) {
                attempts++
                started.complete(Unit)
                MockReply(null, "busy", statusCode = 503, retryAfterMs = 60_000L)
            }
        }
        started.await()
        request.cancelAndJoin()
        assertEquals(1, attempts)
    }

    @Test fun explanationRequestOutlivesTheServerDefaultDeadline() = runBlocking {
        var requestLimit: Long? = null
        val client = CourseHttp("http://127.0.0.1:1", "synthetic", "synthetic", "synthetic", { MockEngine { request ->
            requestLimit = request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
            respond("{\"text\":\"服务端已停止等待\",\"sources\":[]}", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        } })
        try {
            val reply = client.explainSelection(1, JsonObject(emptyMap()))
            assertNotNull(reply.body)
            assertNull(reply.error)
            assertTrue(assertNotNull(requestLimit) > ReaderTimeoutPolicy.DEFAULT_TOTAL_MS,
                "The real explainSelection request must allow the server deadline plus response time")
        } finally { client.close() }
    }

    @Test fun transportTimeoutDoesNotClaimThatMacIsUnreachable() = runBlocking {
        val client = CourseHttp("http://127.0.0.1:1", "synthetic", "synthetic", "synthetic", { MockEngine { request ->
            throw HttpRequestTimeoutException(request)
        } })
        try {
            val reply = client.explainSelection(1, JsonObject(emptyMap()))
            assertNull(reply.body)
            assertTrue(assertNotNull(reply.error).contains("超时"))
            assertFalse(reply.error.orEmpty().contains("连不上"))
        } finally { client.close() }
    }
}
