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
    fun otherRateLimitsKeepTheGenericRetryGuidance() = runBlocking {
        val reply = client("""{"code":"unavailable","error":"讲解服务暂时连不上，读书照常。"}""").explainLibrary(buildJsonObject { })
        assertEquals("讲解服务暂时不可用，请稍后再试。", reply.error)
        assertEquals("unavailable", reply.code)
    }
}
