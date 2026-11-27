package com.coach.client

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TON-740: the only spend refusal left is the exhausted free grant, and the reader is told exactly that. */
class FreeGrantHttpTest {
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun client(body: String) = CourseHttp("http://127.0.0.1:1", "dev-1", "tok-1", "library", {
        MockEngine { respond(body, HttpStatusCode.TooManyRequests, json) }
    })

    @Test
    fun exhaustedFreeGrantIsNamedOnEveryLibraryPath() = runBlocking {
        val friend = """{"code":"free_grant_exhausted","error":"这个月的免费额度用完了"}"""
        val replies = listOf(
            client(friend).explainLibrary(buildJsonObject { }),
            client(friend).companionChapter(buildJsonObject { }),
            client(friend).gradeAndDiagnosePractice(buildJsonObject { }),
            client(friend).askCollection(buildJsonObject { }),
            // Owner routes send the sentence without a friend code.
            client("""{"error":"这个月的免费额度用完了"}""").askCollection(buildJsonObject { }),
        )
        for (reply in replies) {
            assertEquals("这个月的免费额度用完了", reply.error)
            assertEquals("free_grant_exhausted", reply.code)
            assertFalse(reply.retryable)
        }
    }

    @Test
    fun exhaustedFreeGrantInsideAStudyModeStreamIsNamedToo() = runBlocking {
        val sse = "event: meta\ndata: {\"stage\":\"started\"}\n\n" +
            "event: error\ndata: {\"code\":\"free_grant_exhausted\",\"error\":\"这个月的免费额度用完了\"}\n\n"
        val client = CourseHttp("http://127.0.0.1:1", "dev-1", "tok-1", "library", {
            MockEngine { respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())) }
        })
        val reply = client.streamStudyModePack("bk-1", "EXAM_FOCUS", buildJsonObject { })
        assertEquals("这个月的免费额度用完了", reply.error)
        assertEquals("free_grant_exhausted", reply.code)
        assertFalse(reply.retryable)
        // Book map and every other course endpoint share the same mapping.
        assertEquals("这个月的免费额度用完了",
            client("""{"code":"free_grant_exhausted","error":"这个月的免费额度用完了"}""").fetchBookMapBatch("bk-1", buildJsonObject { }).error)
    }

    @Test
    fun busyStudyModeAndBookMapAreNamedAndCarryTheWait() = runBlocking {
        val busy = """{"code":"unavailable","error":"讲解服务暂时连不上，读书照常。","retryAfterSec":5}"""
        fun busyClient(status: HttpStatusCode, body: String, type: ContentType) = CourseHttp("http://127.0.0.1:1", "dev-1", "tok-1", "library", {
            MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, type.toString())) }
        })
        val replies = listOf(
            busyClient(HttpStatusCode.ServiceUnavailable, busy, ContentType.Application.Json).streamStudyModePack("bk-1", "EXAM_FOCUS", buildJsonObject { }),
            busyClient(HttpStatusCode.OK, "event: error\ndata: $busy\n\n", ContentType.Text.EventStream).streamStudyModePack("bk-1", "EXAM_FOCUS", buildJsonObject { }),
            busyClient(HttpStatusCode.ServiceUnavailable, busy, ContentType.Application.Json).fetchBookMapBatch("bk-1", buildJsonObject { }),
        )
        for (reply in replies) {
            assertEquals(SERVICE_BUSY_CODE, reply.code)
            assertEquals("人多，几秒后自动重试", reply.error)
            assertEquals(5_000L, reply.retryAfterMs)
            assertTrue(reply.retryable)
        }
    }

    @Test
    fun busyRepliesAreRetriedAutomaticallyAfterTellingTheReader() = runBlocking {
        val notices = mutableListOf<String>()
        var calls = 0
        val reply = withBusyRetry(onBusy = { notices += it }) {
            calls += 1
            if (calls == 1) MockReply(null, SERVICE_BUSY_TEXT, retryable = true, statusCode = 503, code = SERVICE_BUSY_CODE, retryAfterMs = 1_000L)
            else MockReply(buildJsonObject { }, null, statusCode = 200)
        }
        assertEquals(2, calls)
        assertEquals(listOf("人多，几秒后自动重试"), notices)
        assertEquals(200, reply.statusCode)
        // Anything else is returned at once, without waiting or retrying.
        var plain = 0
        withBusyRetry { plain += 1; MockReply(null, "x", statusCode = 500) }
        assertEquals(1, plain)
    }

    @Test
    fun otherRateLimitsKeepTheGenericRetryGuidance() = runBlocking {
        val reply = client("""{"code":"unavailable","error":"讲解服务暂时连不上，读书照常。"}""").explainLibrary(buildJsonObject { })
        assertEquals("讲解服务暂时不可用，请稍后再试。", reply.error)
        assertEquals("unavailable", reply.code)
    }
}
