package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionQaServiceTest {

    @Test
    fun citationGateHandlesLigaturesExtractorMathSpacingAndStripsFabricatedQuotes() {
        // NFKC + ligature + em-dash + cross-extractor math spacing trimming
        val rawPage = "The ﬁrm offers partial insurance where Ω 3 is strictly preferred−and consumer utility rises with income."
        val exact = ServerSentenceCitationGate.verifyOrSnapAnchor("firm offers partial insurance", rawPage)
        assertTrue(exact.verified)
        assertTrue(exact.rawExactMatch)
        assertEquals("firm offers partial insurance", exact.canonicalAnchor)

        val mathMixed = ServerSentenceCitationGate.verifyOrSnapAnchor(
            "Ω 3 is strictly preferred-and consumer utility rises with income",
            rawPage,
        )
        assertTrue(mathMixed.verified)
        assertTrue(mathMixed.canonicalAnchor.contains("strictly preferred"))

        // >= 85% word window snap
        val snapped = ServerSentenceCitationGate.verifyOrSnapAnchor(
            "offers partial insurance where Omega is strictly preferred",
            rawPage,
        )
        assertTrue(snapped.verified)
        assertFalse(snapped.rawExactMatch)

        // Fabricated quote must be rejected
        val bogus = ServerSentenceCitationGate.verifyOrSnapAnchor(
            "completely fabricated sentence from nowhere in textbook",
            rawPage,
        )
        assertFalse(bogus.verified)
        assertEquals("", bogus.canonicalAnchor)

        // Transcript timestamp extraction
        val transcriptPage = "[42:25] Wait, healthy people use weak inequality >= 700.\n[42:41] Actually it must be a strict inequality > 700 so no insurance is bought."
        val ts = ServerSentenceCitationGate.extractNearestTimestamp(transcriptPage, "must be a strict inequality > 700")
        assertEquals("42:41", ts)
    }

    @Test
    fun endpointVerifiesCitationsStripsBogusMergesConflictsRecordsLedgerAndEnforcesSize(): Unit = runBlocking(Dispatchers.IO) {
        val dir = Files.createTempDirectory("coach-collection-qa-srv-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ledger = BookTokenLedger(dir.resolve("companion"))
        val token = "owner-test-token-32-characters-xx"
        val store = LedgerStore(dir.resolve("ledger")).also { it.enroll("desktop", token) }
        val actor = LedgerActor(scope, store)

        var capturedSystem = ""
        var capturedUser = ""
        val transport = object : CollectionQaTransport {
            override suspend fun ask(
                systemPrompt: String,
                userPrompt: String,
                friend: String?,
                device: String?,
                maxTokens: Int,
            ): CollectionQaTransportReply {
                capturedSystem = systemPrompt
                capturedUser = userPrompt
                return CollectionQaTransportReply(
                    text = """
                        {
                          "sentences": [
                            {
                              "claim": "讲义中把零利润线右上方标注为保险公司盈利或亏损分界。",
                              "blockId": "B1",
                              "quoteAnchor": "contracts northeast of the zero-profit line"
                            },
                            {
                              "claim": "这是一句捏造出处的幻觉陈述，应当被门禁剥离。",
                              "blockId": "B2",
                              "quoteAnchor": "completely invented phantom sentence"
                            },
                            {
                              "claim": "讨论课录音在 42 分 41 秒更正为严格不等号。",
                              "blockId": "B3",
                              "quoteAnchor": "must be a strict inequality"
                            }
                          ],
                          "hasMaterialConflict": true,
                          "conflictSummaryZh": "讲义写零利润线右上方，课本原文写零利润线右侧。",
                          "matchedDiscrepancyIds": ["topic:w5:0"]
                        }
                    """.trimIndent(),
                    servedBy = "claude-haiku-5-5",
                    inputTokens = 950,
                    outputTokens = 210,
                    ttftMs = 240L,
                    elapsedMs = 680L,
                )
            }
        }
        val service = CollectionQaService(transport, ledger)
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8))
            collectionQaRoutes(actor, service = service)
        }.apply { start(false) }

        try {
            val port = engine.engine.resolvedConnectors().single().port
            fun newClient(): HttpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build()
            val client = newClient()

            val payload = buildJsonObject {
                put("requestId", "qa01")
                put("collectionId", "course-ecnm")
                put("collectionKind", "COURSE")
                put("collectionTitle", "Health Economics")
                put("question", "零利润线哪个方位会亏损？")
                put("blocks", buildJsonArray {
                    add(buildJsonObject {
                        put("blockId", "B1")
                        put("bookId", "slides-w5")
                        put("docLabel", "第5周讲义 (L1)")
                        put("authorityRank", 1)
                        put("page", 18)
                        put("text", "Insurers earn positive profit on contracts northeast of the zero-profit line.")
                    })
                    add(buildJsonObject {
                        put("blockId", "B2")
                        put("bookId", "book-bht")
                        put("docLabel", "课本 (L2)")
                        put("authorityRank", 2)
                        put("page", 197)
                        put("text", "Any contract strictly to the right of the zero-profit line incurs losses.")
                    })
                    add(buildJsonObject {
                        put("blockId", "B3")
                        put("bookId", "trans-w4")
                        put("docLabel", "第4周录音 (L1)")
                        put("authorityRank", 1)
                        put("page", 4)
                        put("text", "[42:41] Actually it must be a strict inequality rather than weak inequality.")
                    })
                })
                put("discrepancies", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "topic:w5:0")
                        put("point", "零利润线亏损方位表述差异")
                        put("authoritativeView", "第5周讲义按横轴消费量画图，标为右上方（northeast）亏损")
                        put("secondaryView", "课本第197页标为右侧")
                        put("advice", "两种说法几何方向等价，考试以讲义坐标系为准")
                        put("directAspectMatch", true)
                        put("citations", buildJsonArray {
                            add(buildJsonObject {
                                put("bookId", "slides-w5")
                                put("docLabel", "第5周讲义")
                                put("page", 18)
                                put("quoteAnchor", "northeast of the zero-profit line")
                            })
                            add(buildJsonObject {
                                put("bookId", "book-bht")
                                put("docLabel", "课本")
                                put("page", 197)
                                put("quoteAnchor", "to the right of the zero-profit line")
                            })
                        })
                    })
                })
            }

            // Unauthenticated call must fail with 401 (separate HTTP/1.1 client so early 401 unread body cannot poison keep-alive)
            val unauth = newClient().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/library/collection/ask"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, unauth.statusCode())

            // Authenticated request succeeds and strips the fabricated B2 sentence
            val ok = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/library/collection/ask"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Coach-Device", "desktop")
                    .header("Authorization", "Bearer $token")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, ok.statusCode())
            val res = LedgerJson.parseToJsonElement(ok.body()).jsonObject
            val sentences = res["sentences"]!!.jsonArray
            assertEquals(2, sentences.size)
            assertEquals("B1", sentences[0].jsonObject["blockId"]!!.jsonPrimitive.content)
            assertEquals("B3", sentences[1].jsonObject["blockId"]!!.jsonPrimitive.content)
            assertEquals("42:41", sentences[1].jsonObject["timestampLabel"]!!.jsonPrimitive.content)
            assertEquals(1, res["droppedUnverifiedCount"]!!.jsonPrimitive.int)
            assertTrue(res["hasMaterialConflict"]!!.jsonPrimitive.boolean)
            val conflicts = res["conflicts"]!!.jsonArray
            assertEquals(1, conflicts.size)
            assertEquals(2, conflicts[0].jsonObject["citations"]!!.jsonArray.size)
            assertTrue(capturedSystem.contains("quoteAnchor"))
            assertTrue(capturedUser.contains("Student Question:"))

            // Ledger entry recorded
            val summary = ledger.bookSummary("collection:course-ecnm")
            assertEquals(1, summary.calls)
            assertTrue(summary.byChannel.containsKey("haiku_realtime"))

            // Past-exam leakage text must be refused (409)
            val leakyPayload = buildJsonObject {
                put("collectionId", "course-ecnm")
                put("collectionKind", "COURSE")
                put("question", "What is this?")
                put("blocks", buildJsonArray {
                    add(buildJsonObject {
                        put("blockId", "B1")
                        put("bookId", "bad")
                        put("docLabel", "Bad")
                        put("authorityRank", 1)
                        put("page", 1)
                        put("text", "This is Dec 2024 final exam past paper question 1.")
                    })
                })
            }
            val refused = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/library/collection/ask"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Coach-Device", "desktop")
                    .header("Authorization", "Bearer $token")
                    .POST(HttpRequest.BodyPublishers.ofString(leakyPayload.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(409, refused.statusCode())

            // CJK UTF-8 prompt byte-budget guard keeps system + userPrompt <= 78_000 bytes
            val cjkPage = "经济学课程核心概念分析。".repeat(350).take(4_200)
            val cjkPayload = buildJsonObject {
                put("collectionId", "course-cjk")
                put("collectionKind", "COURSE")
                put("question", "这些页面讨论了什么核心概念？")
                put("blocks", buildJsonArray {
                    for (idx in 1..12) {
                        add(buildJsonObject {
                            put("blockId", "B$idx")
                            put("bookId", "cjk-$idx")
                            put("docLabel", "材料$idx")
                            put("authorityRank", 1)
                            put("page", idx)
                            put("text", cjkPage)
                        })
                    }
                })
            }
            val cjkRes = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/library/collection/ask"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Coach-Device", "desktop")
                    .header("Authorization", "Bearer $token")
                    .POST(HttpRequest.BodyPublishers.ofString(cjkPayload.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, cjkRes.statusCode())
            val totalUtf8Bytes = capturedSystem.toByteArray(Charsets.UTF_8).size +
                capturedUser.toByteArray(Charsets.UTF_8).size
            assertTrue(totalUtf8Bytes <= CollectionQaService.MAX_PROMPT_UTF8_BYTES, "UTF-8 bytes=$totalUtf8Bytes")
            assertTrue(capturedUser.contains("[B1]"))

            // Oversized payload (> 512 KiB) returns 413 on a dedicated HTTP/1.1 client
            val hugeText = "a".repeat(530 * 1024)
            val hugeRes = newClient().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/library/collection/ask"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Coach-Device", "desktop")
                    .header("Authorization", "Bearer $token")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"collectionId":"x","question":"q","blocks":[{"blockId":"B1","bookId":"b","docLabel":"d","page":1,"text":"$hugeText"}]}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(413, hugeRes.statusCode())
        } finally {
            engine.stopSuspend(0, 500)
            actor.close()
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }
}
