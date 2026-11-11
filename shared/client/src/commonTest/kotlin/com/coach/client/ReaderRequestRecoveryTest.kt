package com.coach.client

import com.coach.protocol.ReaderTimeoutPolicy
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Same CourseHttp after a timed-out or cancelled explanation. Synthetic engine only. */
class ReaderRequestRecoveryTest {
    @Test fun sameClientExplainsAfterATimeout() = runBlocking {
        var calls = 0
        var successLimit: Long? = null
        val client = CourseHttp("http://127.0.0.1:1", "synthetic", "synthetic", "synthetic", { MockEngine { request ->
            calls += 1
            if (calls == 1) throw HttpRequestTimeoutException(request)
            successLimit = request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
            json("recovered-after-timeout")
        } })
        try {
            val failed = client.explainSelection(1, JsonObject(emptyMap()))
            assertNull(failed.body)
            assertEquals("讲解请求等待超时。可继续阅读或手动重试。", failed.error)
            val recovered = client.explainSelection(1, JsonObject(emptyMap()))
            assertNull(recovered.error)
            assertEquals("recovered-after-timeout", recovered.body?.textField())
            assertEquals(ReaderTimeoutPolicy.CLIENT_REQUEST_MS, successLimit)
            assertEquals(2, calls)
        } finally { client.close() }
    }

    @Test fun cancellationStopsTheCallAndTheSameClientStillExplains() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var stoppedByCancellation = false
        var calls = 0
        val client = CourseHttp("http://127.0.0.1:1", "synthetic", "synthetic", "synthetic", { MockEngine { request ->
            calls += 1
            if (calls == 1) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (cause: CancellationException) {
                    // The engine context Job can stay active; the handler itself must still stop.
                    stoppedByCancellation = true
                    throw cause
                }
            }
            json("recovered-after-cancel")
        } })
        try {
            val call = async { client.explainSelection(1, JsonObject(emptyMap())) }
            withTimeout(5_000) { entered.await() }
            call.cancel()
            val thrown = withTimeout(5_000) { runCatching { call.await() }.exceptionOrNull() }
            assertIs<CancellationException>(thrown)
            assertTrue(stoppedByCancellation, "cancelling the caller must stop the in-flight explanation")
            val recovered = withTimeout(5_000) { client.explainSelection(1, JsonObject(emptyMap())) }
            assertNull(recovered.error)
            assertEquals("recovered-after-cancel", recovered.body?.textField())
            assertEquals(2, calls)
        } finally { client.close() }
    }

    @Test fun concurrentExplanationsDoNotCrossResults() = runBlocking {
        val client = CourseHttp("http://127.0.0.1:1", "synthetic", "synthetic", "synthetic", { MockEngine { request ->
            val marker = when {
                requestText(request).contains("alpha-result") -> "alpha-result"
                requestText(request).contains("beta-result") -> "beta-result"
                else -> error("unknown synthetic explanation request")
            }
            delay(40)
            json(marker)
        } })
        try {
            coroutineScope {
                val alpha = async { client.explainSelection(1, buildJsonObject { put("marker", "alpha-result") }) }
                val beta = async { client.explainSelection(1, buildJsonObject { put("marker", "beta-result") }) }
                val first = alpha.await()
                val second = beta.await()
                assertNull(first.error)
                assertNull(second.error)
                assertEquals("alpha-result", first.body?.textField())
                assertEquals("beta-result", second.body?.textField())
            }
        } finally { client.close() }
    }

    private fun JsonObject.textField() = (this["text"] as? JsonPrimitive)?.contentOrNull
}

private suspend fun MockRequestHandleScope.json(text: String) = respond(
    "{\"text\":\"$text\",\"sources\":[]}",
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)

private fun requestText(request: HttpRequestData): String {
    val body = request.body
    if (body is TextContent) return body.text
    if (body is OutgoingContent.ByteArrayContent) return body.bytes().decodeToString()
    error("synthetic request body was ${body::class.simpleName}")
}
