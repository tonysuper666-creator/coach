package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import com.coach.store.LedgerStore
import com.sun.net.httpserver.HttpServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookMapBatchServiceTest {
    private val noopStream = object : StudyModeStreamTransport {
        override fun streamGenerate(
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
            friend: String?,
            device: String?,
            onDelta: (String, Long) -> Unit,
        ): StudyModeTransportReply = error("stream not used")
    }

    private val noopLegacyBatch = object : StudyModeBatchTransport {
        override fun complete(
            task: String,
            jobKey: String,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
            useBatchApi: Boolean,
            friend: String?,
            device: String?,
        ): StudyModeTransportReply = error("legacy batch not used")
    }

    @Test
    fun haikuBatchTransportEnforcesHaikuModelBackgroundBatchAndPendingStatus() {
        val observedTask = AtomicReference("")
        val observedBg = AtomicReference("")
        val observedBatchKey = AtomicReference("")
        val observedModel = AtomicReference("")
        val calls = AtomicInteger(0)

        val stubBridge = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stubBridge.createContext("/v1/chat/completions") { ex ->
            val n = calls.incrementAndGet()
            observedTask.set(ex.requestHeaders.getFirst("X-Coach-Task").orEmpty())
            observedBg.set(ex.requestHeaders.getFirst("X-Coach-Background").orEmpty())
            observedBatchKey.set(ex.requestHeaders.getFirst("X-Coach-Batch-Key").orEmpty())
            val reqJson = LedgerJson.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
            observedModel.set(reqJson["model"]?.jsonPrimitive?.contentOrNull.orEmpty())
            if (n == 1) {
                val body = """{"x_coach_batch":"pending","retry_after_ms":7000}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(202, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            } else {
                val content = """{"chapters":[{"chapterId":"ch-1","chapterNum":1,"oneLiner":"本章讲核心机制","points":[{"summary":"第一个要点","refs":[1]},{"summary":"第二个要点","refs":[2]},{"summary":"第三个要点","refs":[3]}]}]}"""
                val body = buildJsonObject {
                    put("model", "claude-haiku-5-5-20260401")
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("message", buildJsonObject { put("role", "assistant"); put("content", content) })
                            put("finish_reason", "stop")
                        })
                    })
                    put("x_coach", buildJsonObject {
                        put("status", "complete")
                        put("served_by", "claude-haiku-5-5-20260401")
                        put("physical_calls", 1)
                    })
                }.toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        stubBridge.start()
        try {
            val bridge = BridgeClient(
                BridgeConfig(
                    baseUrl = "http://127.0.0.1:${stubBridge.address.port}",
                    requestTimeout = java.time.Duration.ofSeconds(10),
                ),
            )
            val transport = HaikuBookMapBatchTransport(bridge)
            val key = StudyModeJsonHelpers.sha256Hex("book-map-test-job")
            val first = transport.submitOrPoll(key, "sys", "user", 4096, friend = null, device = "owner")
            assertTrue(first is BookMapBatchChunkOutcome.Pending, "First poll must return non-blocking Pending")
            assertEquals("book_map_batch", observedTask.get())
            assertEquals("1", observedBg.get())
            assertEquals(BridgeClient.HAIKU, observedModel.get())
            assertTrue(observedBatchKey.get().length == 64, "Must send 64-char durable batch key")

            val second = transport.submitOrPoll(key, "sys", "user", 4096, friend = null, device = "owner")
            assertTrue(second is BookMapBatchChunkOutcome.Ready)
            assertTrue(second.model.startsWith(BridgeClient.HAIKU))
        } finally {
            stubBridge.stop(0)
        }
    }

    @Test
    fun haikuBatchTransportRejectsNonHaikuServedByAndPropagates95PercentGuard() {
        val mode = AtomicInteger(0)
        val stubBridge = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stubBridge.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.readBytes()
            if (mode.get() == 0) {
                val body = buildJsonObject {
                    put("model", "gpt-6.2-astra")
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("message", buildJsonObject { put("role", "assistant"); put("content", """{"chapters":[]}""") })
                            put("finish_reason", "stop")
                        })
                    })
                    put("x_coach", buildJsonObject {
                        put("status", "complete")
                        put("served_by", "gpt-6.2-astra")
                        put("physical_calls", 1)
                    })
                }.toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            } else {
                val body = """{"error":{"type":"credit_limit_95_percent","message":"credit_limit_95_percent"},"x_coach":{"status":"failed","reason":"credit_limit_95_percent","physical_calls":0}}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(429, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        stubBridge.start()
        try {
            val bridge = BridgeClient(
                BridgeConfig(
                    baseUrl = "http://127.0.0.1:${stubBridge.address.port}",
                    requestTimeout = java.time.Duration.ofSeconds(10),
                ),
            )
            val transport = HaikuBookMapBatchTransport(bridge)
            val wrongModel = transport.submitOrPoll("a".repeat(64), "sys", "user", 2048, null, "owner")
            assertTrue(wrongModel is BookMapBatchChunkOutcome.Failed)
            assertFalse(wrongModel.isQuota)

            mode.set(1)
            val guarded = transport.submitOrPoll("b".repeat(64), "sys", "user", 2048, null, "owner")
            assertTrue(guarded is BookMapBatchChunkOutcome.Failed)
            assertTrue(guarded.isQuota, "95% guard denial must surface as quota failure without bypass")
        } finally {
            stubBridge.stop(0)
        }
    }

    @Test
    fun v2RouteValidatesRefsFailClosedSupportsPendingPollAndNeverLogsOrPersistsBookText() = runBlocking(Dispatchers.IO) {
        val dir = Files.createTempDirectory("book-map-v2-privacy-")
        val canaryProse = "CANARY_SECRET_PROSE_TON1889_NEVER_LOG_ME_987654"
        val origOut = System.out
        val origErr = System.err
        val outCapture = ByteArrayOutputStream()
        val errCapture = ByteArrayOutputStream()
        try {
            val store = LedgerStore(dir)
            val ownerToken = "owner-map-v2-token-32-characters"
            store.enroll("owner-dev", ownerToken)
            val actor = LedgerActor(this, store)
            val tokenLedger = BookTokenLedger(dir.resolve("token-ledger"))
            val pollCount = AtomicInteger(0)

            val fakeV2Transport = object : BookMapBatchTransport {
                override fun submitOrPoll(
                    jobKey: String,
                    systemPrompt: String,
                    userPrompt: String,
                    maxTokens: Int,
                    friend: String?,
                    device: String?,
                ): BookMapBatchChunkOutcome {
                    assertTrue(canaryProse in userPrompt, "Book prose should be sent to model during generation")
                    val call = pollCount.incrementAndGet()
                    if (call == 1) return BookMapBatchChunkOutcome.Pending(2_500L)
                    // Include 3 valid refs, 1 cross-chapter bogus ref, and 1 hallucinated ref (999) to prove fail-closed filtering.
                    val text = """{
                        "chapters":[
                            {
                                "chapterId":"ch-1",
                                "chapterNum":1,
                                "oneLiner":"第一章建立供需与激励分析框架",
                                "points":[
                                    {"summary":"价格机制决定资源在医疗市场中的配置方向。","refs":[1]},
                                    {"summary":"信息不对称会导致逆向选择问题。","refs":[2, 999]},
                                    {"summary":"共付比例直接影响患者的边际就医决策。","refs":[3]},
                                    {"summary":"这条编造的要点挂了不存在的引用，必须被丢弃。","refs":[888, 999]}
                                ]
                            }
                        ]
                    }"""
                    return BookMapBatchChunkOutcome.Ready(
                        text = text,
                        model = BridgeClient.HAIKU,
                        inputTokens = 180,
                        outputTokens = 110,
                        elapsedMs = 45L,
                    )
                }
            }

            val service = StudyModePackService(
                streamTransport = noopStream,
                batchTransport = noopLegacyBatch,
                ledger = tokenLedger,
                cache = StudyModePackCache(dir.resolve("study-mode-cache")),
                bookMapV2Service = BookMapBatchService(fakeV2Transport, tokenLedger),
            )

            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                ledgerRoutes(actor, MutableSharedFlow(), adminDevice = "owner-dev")
                studyModeRoutes(actor, service = service)
            }.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val http = HttpClient.newHttpClient()

            System.setOut(PrintStream(outCapture))
            System.setErr(PrintStream(errCapture))
            try {
                val payload = buildJsonObject {
                    put("formatVersion", 2)
                    put("bookId", "bk-privacy")
                    put("bookTitle", "Private Textbook")
                    put("skeletonHash", "skel-abc123")
                    put("bodyStartPage", 9)
                    put("explainLang", "BILINGUAL")
                    put("revision", 0)
                    put("chapters", buildJsonArray {
                        add(buildJsonObject {
                            put("chapterId", "ch-1")
                            put("chapterNum", 1)
                            put("chapterTitle", "1 Introduction")
                            put("firstPage", 9)
                            put("lastPage", 20)
                            put("refs", buildJsonArray {
                                add(buildJsonObject { put("ref", 1); put("pdfPage", 9); put("text", "$canaryProse Price signals allocate healthcare resources across clinics.") })
                                add(buildJsonObject { put("ref", 2); put("pdfPage", 12); put("text", "Information asymmetry creates adverse selection in insurance markets.") })
                                add(buildJsonObject { put("ref", 3); put("pdfPage", 16); put("text", "Coinsurance rates alter marginal patient demand for medical visits.") })
                            })
                        })
                    })
                }.toString()

                fun post(): HttpResponse<String> = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/books/bk-privacy/book-map/batch"))
                        .header("X-Coach-Device", "owner-dev")
                        .header("Authorization", "Bearer $ownerToken")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                val r1 = post()
                assertEquals(200, r1.statusCode())
                val j1 = LedgerJson.parseToJsonElement(r1.body()).jsonObject
                assertEquals(true, j1["pending"]?.jsonPrimitive?.booleanOrNull)
                assertEquals(0, j1["completedChunks"]?.jsonPrimitive?.intOrNull)
                assertEquals(1, j1["totalChunks"]?.jsonPrimitive?.intOrNull)

                val r2 = post()
                assertEquals(200, r2.statusCode())
                assertFalse(canaryProse in r2.body(), "Response must not echo raw book prose")
                val j2 = LedgerJson.parseToJsonElement(r2.body()).jsonObject
                assertEquals(false, j2["pending"]?.jsonPrimitive?.booleanOrNull)
                assertEquals(BridgeClient.HAIKU, j2["model"]?.jsonPrimitive?.contentOrNull)
                val pts = j2["chapters"]!!.jsonArray.single().jsonObject["points"]!!.jsonArray
                assertEquals(3, pts.size, "Hallucinated ref point must be dropped fail-closed")
                assertEquals(listOf(2), pts[1].jsonObject["refs"]!!.jsonArray.map { it.jsonPrimitive.intOrNull })
            } finally {
                System.setOut(origOut)
                System.setErr(origErr)
                server.stop(100, 500)
                actor.close()
            }

            // Assert book prose NEVER leaked to stdout, stderr, or any server disk file.
            val stdoutText = outCapture.toString(Charsets.UTF_8)
            val stderrText = errCapture.toString(Charsets.UTF_8)
            assertFalse(canaryProse in stdoutText, "Book prose must never appear in server stdout")
            assertFalse(canaryProse in stderrText, "Book prose must never appear in server stderr")
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { path ->
                    val bytes = Files.readAllBytes(path).toString(Charsets.ISO_8859_1)
                    assertFalse(canaryProse in bytes, "Book prose must never be written to server disk file: ${path.fileName}")
                }
            }
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
            dir.toFile().deleteRecursively()
        }
    }
}
