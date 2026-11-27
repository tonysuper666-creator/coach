package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import com.coach.domain.json
import com.coach.domain.str
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
    fun insufficientEvidenceDropsEvenAnExactQuoteAndModelConflict() = runBlocking {
        val phrase = "Copper louvers direct the breeze."
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?,
                                     maxTokens: Int): CollectionQaTransportReply {
                assertTrue("insufficientEvidence" in systemPrompt)
                val output = buildJsonObject {
                    put("insufficientEvidence", true)
                    put("sentences", buildJsonArray { add(json("claim" to "The speed is unknown.", "blockId" to "B1", "quoteAnchor" to phrase)) })
                    put("hasMaterialConflict", true)
                    put("conflictSummaryZh", "Conflicting materials.")
                }
                return CollectionQaTransportReply(output.toString(), "fixture", 10, 10, 1, 2)
            }
        }
        val result = CollectionQaService(transport).ask(buildJsonObject {
            put("collectionId", "shelf")
            put("question", "How fast was the wind?")
            put("blocks", buildJsonArray {
                add(json("blockId" to "B1", "bookId" to "book", "docLabel" to "House", "page" to 1, "text" to phrase))
            })
        })
        assertTrue(result.getValue("insufficientEvidence").jsonPrimitive.boolean)
        assertTrue(result.getValue("sentences").jsonArray.isEmpty())
        assertTrue(result.getValue("conflicts").jsonArray.isEmpty())
        assertFalse(result.getValue("hasMaterialConflict").jsonPrimitive.boolean)
    }

    @Test
    fun readBooklistAnswerResolvesTwoIndependentPageCitations() = runBlocking {
        val pages = listOf("Green shutters keep the room cool.", "Copper louvers direct the breeze.")
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?,
                                     maxTokens: Int): CollectionQaTransportReply {
                pages.forEach { assertTrue(it in userPrompt) }
                val response = buildJsonObject {
                    put("sentences", buildJsonArray {
                        pages.forEachIndexed { index, page ->
                            add(json("claim" to page, "blockId" to "B${index + 1}", "quoteAnchor" to page))
                        }
                    })
                }
                return CollectionQaTransportReply(response.toString(), "fixture", 10, 10, 1, 2)
            }
        }
        val request = buildJsonObject {
            put("collectionId", "read-library")
            put("collectionKind", "BOOKLIST")
            put("question", "这两本书分别怎样给房间降温？")
            put("blocks", buildJsonArray {
                pages.forEachIndexed { index, page ->
                    add(json("blockId" to "B${index + 1}", "bookId" to "book-$index", "docLabel" to "书 $index",
                        "page" to index + 3, "text" to page))
                }
            })
        }
        val sentences = CollectionQaService(transport).ask(request)["sentences"]!!.jsonArray
        assertEquals(listOf("book-0" to 3, "book-1" to 4), sentences.map {
            assertTrue(it.jsonObject.getValue("verifiedOnSourcePage").jsonPrimitive.boolean)
            it.jsonObject.str("bookId") to it.jsonObject.getValue("page").jsonPrimitive.int
        })
    }

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

    @Test
    fun sableAndFriendUseCollectionAskOnPublicPortWithHashedBridgeIdQuotaIsolationAnd429Mapping(): Unit = runBlocking(Dispatchers.IO) {
        val dir = Files.createTempDirectory("coach-collection-qa-sable-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ownerToken = "owner-test-token-32-characters-yy"
        val store = LedgerStore(dir.resolve("ledger")).also { it.enroll("desktop", ownerToken) }
        val actor = LedgerActor(scope, store)
        val ledger = BookTokenLedger(dir.resolve("companion"))
        val stub = StubTutor()
        val bridge = BridgeClient(BridgeConfig(stub.baseUrl))
        val service = CollectionQaService(HaikuStreamingCollectionQaTransport(bridge), ledger)
        val friendConfig = dir.resolve("friends.json")
        val friends = FriendAccess(dir.resolve("friends"), dir.resolve("state"), friendConfig)
        var sableActive = true
        val sableToken = "synthetic-sable-qa-bearer-token-12345"
        friends.sable = SableSessions(actor, SableVault(dir.resolve("vault")), { raw ->
            if (sableActive && raw == sableToken) SableIdentity("sub-qa-1", "qa-reader") else null
        })

        val privateEngine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), friends = friends, publicOnly = false)
            collectionQaRoutes(actor, friends = friends, service = service)
        }.apply { start(false) }
        val publicEngine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), friends = friends, publicOnly = true)
            collectionQaRoutes(actor, friends = friends, service = service)
        }.apply { start(false) }

        try {
            val privatePort = privateEngine.engine.resolvedConnectors().single().port
            val publicPort = publicEngine.engine.resolvedConnectors().single().port
            val client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build()

            fun post(port: Int, path: String, body: String, deviceId: String? = null, token: String? = null): HttpResponse<String> {
                val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                if (deviceId != null) b.header("X-Coach-Device", deviceId)
                if (token != null) b.header("Authorization", "Bearer $token")
                return client.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            }

            val payload = buildJsonObject {
                put("requestId", "qa-sable-1")
                put("collectionId", "course-sable-1")
                put("collectionKind", "COURSE")
                put("collectionTitle", "Health Economics")
                put("question", "什么是逆向选择？")
                put("blocks", buildJsonArray {
                    add(buildJsonObject {
                        put("blockId", "B1")
                        put("bookId", "sable-upload-book-1")
                        put("docLabel", "课本 (L2)")
                        put("authorityRank", 2)
                        put("page", 179)
                        put("text", "Adverse selection arises when healthier consumers drop out of pooled insurance contracts.")
                    })
                })
            }.toString()

            // 1. Unauthenticated requests on BOTH public and private ports are rejected with 401
            assertEquals(401, post(publicPort, "/v1/library/collection/ask", payload).statusCode())
            assertEquals(401, post(privatePort, "/v1/library/collection/ask", payload).statusCode())

            // 2. Owner credential cannot use public port (404)
            assertEquals(404, post(publicPort, "/v1/library/collection/ask", payload, "desktop", ownerToken).statusCode())

            // 3. Independent Sable identity exchanges token and asks on public port -> 200 + verified citation
            val exchange = post(publicPort, "/v1/public/sable", "", token = sableToken)
            assertEquals(200, exchange.statusCode())
            val sableCred = LedgerJson.parseToJsonElement(exchange.body()).jsonObject
            assertEquals("friend", sableCred.str("role"))
            val sableDevice = sableCred.str("deviceId")
            val sableAuth = sableCred.str("token")

            val modelReply = """{"sentences":[{"claim":"健康人群退出混同保险合同会引发逆向选择。","blockId":"B1","quoteAnchor":"healthier consumers drop out of pooled insurance"}],"hasMaterialConflict":false,"conflictSummaryZh":"","matchedDiscrepancyIds":[]}"""
            stub.stream(1, modelReply, StubTutor.terminal("complete"), StubTutor.DONE)

            val sableRes = post(publicPort, "/v1/library/collection/ask", payload, sableDevice, sableAuth)
            assertEquals(200, sableRes.statusCode(), sableRes.body())
            val sableJson = LedgerJson.parseToJsonElement(sableRes.body()).jsonObject
            val sents = sableJson["sentences"]!!.jsonArray
            assertEquals(1, sents.size)
            assertEquals("sable-upload-book-1", sents[0].jsonObject["bookId"]!!.jsonPrimitive.content)
            assertEquals(179, sents[0].jsonObject["page"]!!.jsonPrimitive.int)

            // Verify bridge received SHA-256 hashed friendId (64 hex chars), NOT raw friend name
            val expectedFriendName = actor.execute { friendName(sableDevice) }!!
            val (sentFriendId, sentFriendDev) = stub.requestFriends.last()
            assertEquals(friendId(expectedFriendName), sentFriendId)
            assertTrue(sentFriendId!!.matches(Regex("[a-f0-9]{64}")))
            assertEquals(sableDevice, sentFriendDev)

            // Verify owner BookTokenLedger has 0 calls from friend traffic; friend private audit has record
            assertEquals(0, ledger.bookSummary("collection:course-sable-1").calls)
            val friendDir = dir.resolve("friends").resolve(friendId(expectedFriendName))
            assertTrue(Files.exists(friendDir.resolve("explanations.sqlite")))
            assertTrue(Files.readString(friendDir.resolve("requests.log")).contains("complete"))

            // 4. TON-740: an explicit daily_calls=0 is recorded, not enforced — the question still reaches the bridge.
            val beforeQuotaStubCalls = stub.requests
            Files.writeString(friendConfig, json("defaults" to json("daily_calls" to 0)).toString())
            stub.stream(1, modelReply, StubTutor.terminal("complete"), StubTutor.DONE)
            val quotaRes = post(publicPort, "/v1/library/collection/ask", payload, sableDevice, sableAuth)
            assertEquals(200, quotaRes.statusCode())
            assertEquals(beforeQuotaStubCalls + 1, stub.requests)
            Files.deleteIfExists(friendConfig)

            // 4a. Only the exhausted free grant refuses, with its own code and sentence.
            stub.stream(1, StubTutor.terminal("failed", "free_grant_exhausted"), StubTutor.DONE)
            val exhaustedRes = post(publicPort, "/v1/library/collection/ask", payload, sableDevice, sableAuth)
            assertEquals(429, exhaustedRes.statusCode())
            val exhaustedBody = LedgerJson.parseToJsonElement(exhaustedRes.body()).jsonObject
            assertEquals("free_grant_exhausted", exhaustedBody.str("code"))
            assertEquals("这个月的免费额度用完了", exhaustedBody.str("error"))

            // 4b. Sable hitting over-budget upstream 429 gets HTTP 429 + Retry-After header + plain-Chinese wait time
            stub.stream(1, StubTutor.terminal("failed", "unavailable", retryAfterSec = 60), StubTutor.DONE)
            val sableRateLimited = post(publicPort, "/v1/library/collection/ask", payload, sableDevice, sableAuth)
            assertEquals(429, sableRateLimited.statusCode())
            assertEquals("60", sableRateLimited.headers().firstValue("Retry-After").orElse(""))
            val sableRateJson = LedgerJson.parseToJsonElement(sableRateLimited.body()).jsonObject
            assertEquals(60, sableRateJson["retryAfterSec"]!!.jsonPrimitive.int)
            assertTrue(sableRateJson.str("error").contains("60 秒"), "Expected retryable seconds in error: ${sableRateJson.str("error")}")

            // 5. Revoked Sable account is rejected with 401
            assertEquals(204, post(privatePort, "/v1/sable/disable", """{"username":"qa-reader"}""", "desktop", ownerToken).statusCode())
            assertEquals(401, post(publicPort, "/v1/library/collection/ask", payload, sableDevice, sableAuth).statusCode())

            // 6. Owner hitting over-budget upstream bridge 429 gets HTTP 429 + Retry-After + clear plain-Chinese wait time (not 503)
            stub.stream(1, StubTutor.terminal("failed", "api_http_429", retryAfterSec = 60), StubTutor.DONE)
            val rateLimited = post(privatePort, "/v1/library/collection/ask", payload, "desktop", ownerToken)
            assertEquals(429, rateLimited.statusCode())
            assertEquals("60", rateLimited.headers().firstValue("Retry-After").orElse(""))
            val rateJson = LedgerJson.parseToJsonElement(rateLimited.body()).jsonObject
            assertEquals(60, rateJson["retryAfterSec"]!!.jsonPrimitive.int)
            val rateBody = rateJson.str("error")
            assertTrue(rateBody.contains("60 秒") && rateBody.contains("重试"), "Expected retryable wait guidance, got: $rateBody")
        } finally {
            privateEngine.stopSuspend(0, 500)
            publicEngine.stopSuspend(0, 500)
            actor.close()
            scope.cancel()
            stub.close()
            dir.toFile().deleteRecursively()
        }
    }
}
