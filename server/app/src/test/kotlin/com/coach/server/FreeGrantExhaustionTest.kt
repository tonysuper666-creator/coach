package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** TON-740 review: every model entry point turns the bridge's `free_grant_exhausted` into the same 429 sentence. */
class FreeGrantExhaustionTest {
    private fun exhausted(block: () -> Unit) {
        val error = assertFailsWith<FriendServiceError> { block() }
        assertEquals(FREE_GRANT_EXHAUSTED, error.code)
        assertEquals(429, error.status.value)
        assertEquals("这个月的免费额度用完了", error.body["error"]!!.jsonPrimitive.content)
    }

    @Test fun realBridgeTransportsRaiseTheExhaustedGrantInsteadOfAGenericFailure() = StubTutor().use { stub ->
        val bridge = BridgeClient(BridgeConfig(baseUrl = stub.baseUrl))
        // 学法（流式）
        stub.stream(1, StubTutor.terminal("failed", FREE_GRANT_EXHAUSTED), StubTutor.DONE)
        exhausted { HaikuStudyModeStreamTransport(bridge).streamGenerate("s", "u", 512, null, null) { _, _ -> } }
        // 全书地图（非流式批处理）
        stub.failedCompletion(FREE_GRANT_EXHAUSTED)
        exhausted { HaikuStudyModeBatchTransport(bridge).complete("BOOK_MAP_BATCH_CHUNK", "k", "s", "u", 1024, false, null, null) }
        // 伴读、书单对齐、专题合卷（实时 Haiku，非流式）
        stub.failedCompletion(FREE_GRANT_EXHAUSTED)
        exhausted { runBlocking { HaikuRealtimeBridgeTransport(bridge).generateSection("s", "u", "chapter_open", "k", 512) } }
        // 批改、模考出题、考点出题（固定模型）
        stub.failedCompletion(FREE_GRANT_EXHAUSTED)
        exhausted { runBlocking { HaikuPinnedBridgeTransport(bridge).generateSection("s", "u", "practice_grade", "k", 512) } }
    }

    @Test fun fallbackWrappersAndOptionalCallsNeverSwallowTheExhaustedGrant() {
        val refusing = object : BatchAiTransport {
            override suspend fun generateSection(systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int): BatchTransportReply =
                throw FriendServiceError(FREE_GRANT_EXHAUSTED)
        }
        exhausted { runBlocking { RoutedBatchAiTransport(primary = refusing, overflow = null).generateSection("s", "u", "t", "k", 100) } }
        exhausted { modelCatching<Unit> { throw FriendServiceError(FREE_GRANT_EXHAUSTED) } }
        // Any other failure still degrades quietly, as before.
        assertTrue(modelCatching<Unit> { error("repair failed") }.isFailure)
        assertTrue(modelCatching<Unit> { throw FriendServiceError("unavailable") }.isFailure)
    }

    @Test fun studyModeAndBookMapRoutesAnswerWith429AndTheSentenceIncludingMidStream() = runBlocking(Dispatchers.IO) {
        val dir = Files.createTempDirectory("free-grant-study-")
        try {
            val store = LedgerStore(dir)
            val token = "owner-free-grant-test-token-32chars"
            store.enroll("owner-dev", token)
            val actor = LedgerActor(this, store)
            val refusingStream = object : StudyModeStreamTransport {
                override fun streamGenerate(systemPrompt: String, userPrompt: String, maxTokens: Int, friend: String?, device: String?,
                    onDelta: (String, Long) -> Unit): StudyModeTransportReply = throw FriendServiceError(FREE_GRANT_EXHAUSTED)
            }
            val refusingBatch = object : StudyModeBatchTransport {
                override fun complete(task: String, jobKey: String, systemPrompt: String, userPrompt: String, maxTokens: Int,
                    useBatchApi: Boolean, friend: String?, device: String?): StudyModeTransportReply = throw FriendServiceError(FREE_GRANT_EXHAUSTED)
            }
            val service = StudyModePackService(refusingStream, refusingBatch)
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                ledgerRoutes(actor, MutableSharedFlow(), adminDevice = "owner-dev")
                studyModeRoutes(actor, service = service)
            }.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val http = HttpClient.newHttpClient()
            fun post(path: String, body: String, sse: Boolean = false): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                    .header("X-Coach-Device", "owner-dev").header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json").apply { if (sse) header("Accept", "text/event-stream") }
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val page = buildJsonArray { add(buildJsonObject { put("pdfPage", 141); put("text", "The RAND Health Insurance Experiment randomly assigned families to plans with different coinsurance rates.") }) }
            val mode = buildJsonObject {
                put("bookTitle", "Health Economics"); put("chapterNum", 8); put("chapterTitle", "RAND")
                put("detailLevel", "DETAILED"); put("explainLang", "BILINGUAL"); put("pages", page)
            }
            try {
                val plain = post("/api/books/he-1/modes/BOOK_MAP", buildJsonObject { mode.forEach { (k, v) -> put(k, v) }; put("stream", false) }.toString())
                assertEquals(429, plain.statusCode())
                val plainBody = LedgerJson.parseToJsonElement(plain.body()).jsonObject
                assertEquals(FREE_GRANT_EXHAUSTED, plainBody["code"]!!.jsonPrimitive.content)
                assertEquals("这个月的免费额度用完了", plainBody["error"]!!.jsonPrimitive.content)

                val stream = post("/api/books/he-1/modes/BOOK_MAP", buildJsonObject { mode.forEach { (k, v) -> put(k, v) }; put("stream", true) }.toString(), sse = true)
                assertEquals(200, stream.statusCode())
                assertTrue("event: error" in stream.body(), stream.body())
                assertTrue("\"code\":\"free_grant_exhausted\"" in stream.body(), stream.body())
                assertTrue("这个月的免费额度用完了" in stream.body(), stream.body())

                val batch = post("/api/books/he-1/book-map/batch", buildJsonObject {
                    put("bookTitle", "Health Economics"); put("explainLang", "BILINGUAL"); put("useBatchApi", true)
                    put("chapters", buildJsonArray { add(buildJsonObject { put("chapterNum", 8); put("chapterTitle", "RAND"); put("pages", page) }) })
                }.toString())
                assertEquals(429, batch.statusCode())
                assertEquals(FREE_GRANT_EXHAUSTED, LedgerJson.parseToJsonElement(batch.body()).jsonObject["code"]!!.jsonPrimitive.content)
            } finally {
                server.stop(100, 500)
                actor.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
