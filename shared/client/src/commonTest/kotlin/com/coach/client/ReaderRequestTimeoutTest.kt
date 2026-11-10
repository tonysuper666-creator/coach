package com.coach.client

import com.coach.protocol.ReaderTimeoutPolicy
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class ReaderRequestTimeoutTest {
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
