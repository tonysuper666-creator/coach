package com.coach.client

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudyModeHttpTest {
    @Test
    fun streamStudyModePackParsesSseProgressAndFinalPackAndHandlesRevocation() = runBlocking {
        var mode = HttpStatusCode.OK
        val ssePayload = buildString {
            append("event: meta\n")
            append("data: {\"stage\":\"started\"}\n\n")
            append("event: first_screen\n")
            append("data: {\"chars\":48,\"firstTokenMs\":510,\"delta\":\"{\\\"oneLiner\\\":\\\"First\"}\n\n")
            append("event: chunk\n")
            append("data: {\"chars\":190,\"delta\":\" screen summary\\\"}\"}\n\n")
            append("event: pack\n")
            append("data: {\"studyMode\":\"EXAM_FOCUS\",\"chapterNum\":8,\"mustKnows\":[{\"topic\":\"RAND\",\"pdfPage\":141,\"quoteAnchor\":\"randomly assigned families to plans\"}]}\n\n")
            append("event: done\n")
            append("data: {\"ok\":true}\n\n")
        }
        val client = CourseHttp("http://127.0.0.1:1", "dev-1", "tok-1", "library", {
            MockEngine { req ->
                when {
                    req.url.encodedPath == "/api/books/bk-1/modes/EXAM_FOCUS" && mode == HttpStatusCode.OK -> {
                        assertEquals("text/event-stream", req.headers[HttpHeaders.Accept])
                        respond(
                            content = ssePayload,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                        )
                    }
                    req.url.encodedPath == "/api/books/bk-1/modes/EXAM_FOCUS" ->
                        respond("""{"error":"unauthorized"}""", mode, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                    req.url.encodedPath == "/api/books/bk-1/book-map/batch" ->
                        respond(
                            """{"bookId":"bk-1","chapterCount":24,"thematicClusters":[]}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    else -> respond("{}", HttpStatusCode.NotFound)
                }
            }
        })
        try {
            val progressEvents = mutableListOf<Triple<Int, Long?, String>>()
            val reply = client.streamStudyModePack(
                bookId = "bk-1",
                mode = "EXAM_FOCUS",
                input = buildJsonObject { put("chapterNum", 8) },
            ) { chars, ttft, delta ->
                progressEvents += Triple(chars, ttft, delta)
            }
            assertNull(reply.error)
            assertNotNull(reply.body)
            assertEquals("EXAM_FOCUS", reply.body.get("studyMode")?.jsonPrimitive?.content)
            assertEquals(
                listOf(
                    Triple(48, 510L, "{\"oneLiner\":\"First"),
                    Triple(190, null, " screen summary\"}"),
                ),
                progressEvents,
            )

            val batchReply = client.fetchBookMapBatch("bk-1", buildJsonObject { put("bookTitle", "Health") })
            assertEquals(24, batchReply.body?.get("chapterCount")?.jsonPrimitive?.intOrNull)

            mode = HttpStatusCode.Unauthorized
            val revoked = client.streamStudyModePack("bk-1", "EXAM_FOCUS", buildJsonObject { put("chapterNum", 8) })
            assertEquals(401, revoked.statusCode)
            assertTrue("重新登录" in revoked.error.orEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun withBookMapBatchPollPollsPendingStagesAndRecoversTransientTimeout() = runBlocking {
        val notices = mutableListOf<String>()
        var attempts = 0
        val reply = withBookMapBatchPoll(maxPolls = 10, onProgress = { notices += it }) {
            attempts += 1
            when (attempts) {
                1 -> MockReply(
                    buildJsonObject {
                        put("pending", true)
                        put("stage", "chapters")
                        put("completedChunks", 1)
                        put("totalChunks", 3)
                        put("retryAfterMs", 500)
                    },
                    null,
                    statusCode = 200,
                )
                2 -> MockReply(
                    buildJsonObject {
                        put("pending", true)
                        put("stage", "graph")
                        put("completedChunks", 3)
                        put("totalChunks", 3)
                        put("retryAfterMs", 500)
                    },
                    null,
                    statusCode = 200,
                )
                3 -> MockReply(null, BOOK_MAP_BATCH_TIMEOUT_TEXT, retryable = true, code = "offline")
                else -> MockReply(
                    buildJsonObject {
                        put("bookId", "bk-1")
                        put("chapterCount", 14)
                    },
                    null,
                    statusCode = 200,
                )
            }
        }
        assertEquals(4, attempts)
        assertEquals(14, reply.body?.get("chapterCount")?.jsonPrimitive?.intOrNull)
        assertTrue(notices.any { "1 / 3" in it }, notices.toString())
        assertTrue(notices.any { "跨章脉络" in it }, notices.toString())
        assertTrue(notices.any { "后台运行" in it }, notices.toString())
    }
}
