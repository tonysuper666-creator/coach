package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.sun.net.httpserver.HttpServer
import io.ktor.server.application.install
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SectionBatchPipelineTest {

    @Test
    fun chunkSplitterEnforces3500TokenBudgetAndPreservesUtf16Offsets() {
        val bookId = "a".repeat(64)
        // Generate an oversized single section (~7,500 tokens) to ensure it splits strictly <= 3,500 tokens/chunk.
        val longParagraph = buildString {
            repeat(600) { i ->
                append("Elasticity measures responsive quantity demand shifts at point $i in equilibrium. ")
            }
        }
        val section = SectionBatchInput(
            sectionId = "sec-2-1",
            title = "2.1 Demand elasticity",
            blocks = listOf(SectionBlockInput("$bookId:p40:0", pdfPage = 40, startUtf16 = 0, text = longParagraph)),
        )
        val chunks = SectionChunkSplitter.split(listOf(section))
        assertTrue(chunks.size >= 3, "Oversized section must split into >=3 bounded chunks, got ${chunks.size}")
        for (chunk in chunks) {
            assertTrue(
                chunk.estimatedRequestTokens <= SectionBatchLimits.MAX_REQUEST_TOKENS,
                "Chunk exceeded 3,500 token cap: ${chunk.estimatedRequestTokens}",
            )
            for (blk in chunk.blocks) {
                assertEquals(
                    longParagraph.substring(blk.startUtf16, blk.startUtf16 + blk.text.length),
                    blk.text,
                    "UTF-16 slice offset must match raw page text exactly",
                )
            }
        }
    }

    @Test
    fun exercisesAreStrictlyIsolatedFromBodyPretestAndAnchorsVerifiedUtf16() = runBlocking {
        val dir = Files.createTempDirectory("coach-companion-pipe-")
        val ledger = BookTokenLedger(dir)
        val cache = ChapterCompanionCache(dir)
        val bookId = "b".repeat(64)
        val bodyText = "When patients face zero out-of-pocket coinsurance, utilization rises because marginal private price falls to zero."
        val exText = "Problem 1: Suppose demand is Q = 100 - 2P and marginal cost is 20. Compute deadweight loss."

        val concurrentActive = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        val stubTransport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String,
                userPrompt: String,
                task: String,
                batchKey: String,
                maxTokens: Int,
            ): BatchTransportReply {
                val now = concurrentActive.incrementAndGet()
                maxObservedConcurrency.accumulateAndGet(now, ::maxOf)
                delay(40)
                concurrentActive.decrementAndGet()

                val jsonReply = if (task == "BOOK_EXERCISE_HINTS") {
                    buildJsonObject {
                        put("exerciseHints", buildJsonArray {
                            add(buildJsonObject {
                                put("problemLabel", "习题 1")
                                put("questionSummary", "给定线性需求与恒定边际成本，估算全保下的效率损失。")
                                put("hint1Direction", "提示 1：先找出无保险有效数量与全保数量之差。")
                                put("hint2Method", "提示 2：利用边际成本和需求曲线围成的三角形面积求解。")
                                put("hint3Check", "核对结论：核对单位与横纵轴截距是否一致。")
                                put("pdfPage", 58)
                                put("quoteAnchor", "Suppose demand is Q = 100")
                            })
                        })
                    }.toString()
                } else {
                    buildJsonObject {
                        put("coreQuestion", "自付比例下降如何推高医疗服务使用量？")
                        put("keyPoints", buildJsonArray {
                            add(buildJsonObject {
                                put("summary", "患者面对零共保时，边际自付价格降为零从而扩大就医需求。")
                                put("pdfPage", 42)
                                put("quoteAnchor", "zero out-of-pocket coinsurance")
                            })
                            add(buildJsonObject {
                                put("summary", "伪造页码的引用必须被服务端核验丢弃。")
                                put("pdfPage", 42)
                                put("quoteAnchor", "totally fabricated sentence not in book")
                            })
                        })
                        put("jargonGlosses", buildJsonArray {
                            add(buildJsonObject {
                                put("term", "coinsurance")
                                put("plainMeaning", "共保比例，即患者按百分比自行承担的那部分费用。")
                                put("pdfPage", 42)
                                put("quoteAnchor", "zero out-of-pocket coinsurance")
                            })
                        })
                        put("pretest", buildJsonArray {
                            add(buildJsonObject {
                                put("stem", "当共保率降至零时，患者就医行为会发生什么变化？")
                                put("options", buildJsonArray {
                                    add(kotlinx.serialization.json.JsonPrimitive("边际自付价降为零，就医量上升"))
                                    add(kotlinx.serialization.json.JsonPrimitive("边际成本随之归零，就医量不变"))
                                    add(kotlinx.serialization.json.JsonPrimitive("需求曲线整体左移"))
                                    add(kotlinx.serialization.json.JsonPrimitive("就诊次数强制减半"))
                                })
                                put("correctOption", "边际自付价降为零，就医量上升")
                                put("explanation", "零共保压低了患者面对的直接价格，因此使用量扩大。")
                                put("pdfPage", 42)
                                put("quoteAnchor", "marginal private price falls")
                            })
                        })
                        put("derivations", buildJsonArray {
                            add(buildJsonObject {
                                put("title", "共保价格效应推导")
                                put("setup", "设全价为 P，共保率为 c，则患者面临价格为 cP。")
                                put("steps", buildJsonArray {
                                    add(kotlinx.serialization.json.JsonPrimitive("第一步：写出自付价格 cP。"))
                                    add(kotlinx.serialization.json.JsonPrimitive("第二步：代入需求函数求对应就医量。"))
                                })
                                put("graphCheck", "确认纵轴为价格、横轴为医疗服务量。")
                                put("pitfall", "切勿把沿需求曲线移动错画成整条曲线平移。")
                                put("pdfPage", 42)
                                put("quoteAnchor", "marginal private price falls")
                            })
                        })
                        put("optionalPractice", buildJsonArray {
                            add(buildJsonObject {
                                put("stem", "为什么零共保会诱发超额医疗消费？")
                                put("options", buildJsonArray {
                                    add(kotlinx.serialization.json.JsonPrimitive("因为患者边际自付低于社会边际成本"))
                                    add(kotlinx.serialization.json.JsonPrimitive("因为医院降低了技术成本"))
                                })
                                put("correctOption", "因为患者边际自付低于社会边际成本")
                                put("explanation", "当边际收益低于边际成本时出现效率损失。")
                                put("pdfPage", 42)
                                put("quoteAnchor", "utilization rises because")
                            })
                        })
                    }.toString()
                }
                return BatchTransportReply(
                    text = jsonReply,
                    routeChannel = CompanionRouteChannel.CODEBUDDY_DEEPSEEK_FLASH,
                    model = "deepseek-v4.1-flash",
                    inputTokens = 820,
                    outputTokens = 410,
                    ttftMs = 35,
                    elapsedMs = 45,
                )
            }
        }

        val pipeline = SectionConcurrentCompanionPipeline(stubTransport, ledger, cache, maxConcurrency = 4)
        val req = ChapterCompanionRequest(
            bookId = bookId,
            chapterId = "ch-2",
            chapterTitle = "2 Demand for health care",
            sections = listOf(
                SectionBatchInput(
                    sectionId = "sec-2-1",
                    title = "2.1 Coinsurance and utilization",
                    blocks = listOf(SectionBlockInput("$bookId:p42:10", 42, 10, bodyText)),
                ),
                SectionBatchInput(
                    sectionId = "sec-2-2",
                    title = "2.2 Price elasticity",
                    blocks = listOf(SectionBlockInput("$bookId:p45:0", 45, 0, bodyText)),
                ),
                SectionBatchInput(
                    sectionId = "sec-2-ex",
                    title = "2.6 Exercises",
                    blocks = listOf(SectionBlockInput("$bookId:p58:5", 58, 5, exText)),
                ),
            ),
        )

        val pack = pipeline.generateChapter(req)
        assertTrue(maxObservedConcurrency.get() >= 2, "Sections must execute concurrently")
        assertEquals(1, pack.stats.exercisesIsolated, "Exercises section must be detected & isolated")
        assertTrue(pack.pretest.all { it.sectionId != "sec-2-ex" }, "Exercises must never leak into body pretest")
        assertEquals(1, pack.exerciseHints.size, "Exercises must populate 3-tier exerciseHints")
        assertEquals("习题 1", pack.exerciseHints.single().problemLabel)

        // Verify UTF-16 anchor verification & rejection of fabricated anchors
        val verifiedKp = pack.keyPoints.first()
        val kpAnchor = verifiedKp.anchor
        assertNotNull(kpAnchor)
        assertEquals(42, kpAnchor.pdfPage)
        assertEquals(10 + bodyText.indexOf("zero out-of-pocket coinsurance"), kpAnchor.startUtf16)
        assertNull(pack.keyPoints[1].anchor, "Fabricated quote anchor must be rejected")
        assertTrue(pack.stats.rejectedAnchors >= 2)
        assertTrue(pack.stats.overlapAudit.passed, "Overlap gate must pass (en 8-gram = 0, zh 12-gram = 0)")

        // Token ledger recorded all 3 section calls at $0 marginal cash via CodeBuddy DeepSeek
        val summary = ledger.bookSummary(bookId)
        assertEquals(3, summary.calls)
        assertEquals(0L, summary.totalCostMicrodollars)
        assertTrue(summary.withinBudget)

        // Second call hits server cache cleanly
        val cached = pipeline.generateChapter(req)
        assertTrue(cached.stats.fromCache)
        assertEquals(3, ledger.bookSummary(bookId).calls, "Cache hit must not trigger new model calls")
    }

    @Test
    fun routedTransportFallsBackToHaikuBatchOverflowAndLedgerTracksCostUnder30Cents() = runBlocking {
        val dir = Files.createTempDirectory("coach-overflow-test-")
        val ledger = BookTokenLedger(dir)
        val bookId = "c".repeat(64)

        val failingPrimary = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int,
            ): BatchTransportReply = throw IllegalStateException("codebuddy_rate_limited")
        }
        val overflowHaiku = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int,
            ): BatchTransportReply = BatchTransportReply(
                text = """{"coreQuestion":"测试溢出。","keyPoints":[],"jargonGlosses":[],"pretest":[],"derivations":[],"optionalPractice":[]}""",
                routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                model = "claude-haiku-5-5",
                inputTokens = 2_000,
                outputTokens = 700,
                ttftMs = 420,
                elapsedMs = 1_100,
            )
        }
        val routed = RoutedBatchAiTransport(primary = failingPrimary, overflow = overflowHaiku)
        val pipeline = SectionConcurrentCompanionPipeline(routed, ledger)
        val pack = pipeline.generateChapter(
            ChapterCompanionRequest(
                bookId = bookId,
                chapterId = "ch-7",
                chapterTitle = "7 Demand for insurance",
                sections = listOf(
                    SectionBatchInput(
                        sectionId = "sec-7-1",
                        title = "7.1 Risk aversion",
                        blocks = listOf(SectionBlockInput("$bookId:p157:0", 157, 0, "Risk averse consumers prefer certain income to fair gambles.")),
                    ),
                ),
            ),
        )
        assertEquals(listOf("haiku_batch"), pack.stats.routeChannelsUsed)
        // Haiku Batch cost: 2000 * 0.05 + 700 * 0.25 = 100 + 175 = 275 microdollars ($0.000275)
        val summary = ledger.bookSummary(bookId)
        assertEquals(275L, summary.totalCostMicrodollars)
        assertTrue(summary.withinBudget)
    }

    @Test
    fun haikuOverflowAnswersSynchronouslySoTheApiOnlyBridgeNeverParksItInBatch() = runBlocking {
        // TON-1795: a background request on the API-only bridge returns 202 BatchPending, which the
        // waiting chapter-companion request cannot handle, so the drawer showed "暂时不可用".
        val seen = CopyOnWriteArrayList<Pair<String?, JsonObject>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = kotlinx.serialization.json.Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()) as JsonObject
            seen.add(exchange.requestHeaders.getFirst("X-Coach-Background") to body)
            val reply = buildJsonObject {
                put("model", "claude-haiku-5-5")
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("message", buildJsonObject { put("role", "assistant"); put("content", "{}") })
                    })
                })
                put("x_coach", buildJsonObject {
                    put("status", "complete"); put("finish_reason", "stop"); put("served_by", "claude-haiku-5-5")
                    put("fallback", false); put("physical_calls", 1); put("request_id", "synthetic")
                    put("queue_ms", 1); put("ttft_ms", 1); put("elapsed_ms", 1)
                })
            }.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val transport = HaikuRealtimeBridgeTransport(BridgeClient(BridgeConfig(baseUrl = "http://127.0.0.1:${server.address.port}")))
            val reply = transport.generateSection("system", "user", "BOOK_SECTION_BATCH", "d".repeat(64), 1800)
            assertEquals(CompanionRouteChannel.HAIKU_REALTIME, reply.routeChannel)
            assertEquals(1, seen.size)
            assertNull(seen.single().first, "Interactive overflow must not opt into background batch")
            assertEquals("claude-haiku-5-5", seen.single().second["model"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun overlapGuardCatchesEnglish8GramAndChinese12Gram() {
        val source = "In health insurance markets adverse selection arises when buyers know their risk better than insurers. 健康保险市场中投保人比保险公司更了解自身健康风险状况。"
        val clean = listOf("当投保人对自身风险更知情时，保险市场会出现逆向选择。")
        val cleanAudit = VerbatimOverlapGuard.audit(clean, source)
        assertTrue(cleanAudit.passed)
        assertEquals(0, cleanAudit.en8GramHits)
        assertEquals(0, cleanAudit.zh12GramHits)

        val enLeak = listOf("Note that adverse selection arises when buyers know their risk better in practice.")
        val enAudit = VerbatimOverlapGuard.audit(enLeak, source)
        assertFalse(enAudit.passed)
        assertTrue(enAudit.en8GramHits > 0)

        val zhLeak = listOf("书中提到健康保险市场中投保人比保险公司更了解自身风险。")
        val zhAudit = VerbatimOverlapGuard.audit(zhLeak, source)
        assertFalse(zhAudit.passed)
        assertTrue(zhAudit.zh12GramHits > 0)

        val scrubbedEn = VerbatimOverlapGuard.scrubOverlaps(enLeak.single(), source)
        assertTrue(VerbatimOverlapGuard.audit(listOf(scrubbedEn), source).passed, "Scrubbed English must pass en 8-gram = 0")
        val scrubbedZh = VerbatimOverlapGuard.scrubOverlaps(zhLeak.single(), source)
        assertTrue(VerbatimOverlapGuard.audit(listOf(scrubbedZh), source).passed, "Scrubbed Chinese must pass zh 12-gram = 0")
    }

    @Test
    fun codeBuddyStreamJsonParserExtractsIncrementalTextDeltasAndFallbackResult() {
        val deltaLine = """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"{\"coreQuestion\":"}}}"""
        val parsedDelta = CodeBuddyDeepSeekTransport.parseStreamJsonLine(deltaLine)
        assertNotNull(parsedDelta)
        assertTrue(parsedDelta.isDelta)
        assertEquals("""{"coreQuestion":""", parsedDelta.text)

        val resultLine = """{"type":"result","result":"{\"coreQuestion\":\"ok\"}"}"""
        val parsedResult = CodeBuddyDeepSeekTransport.parseStreamJsonLine(resultLine)
        assertNotNull(parsedResult)
        assertFalse(parsedResult.isDelta)
        assertEquals("""{"coreQuestion":"ok"}""", parsedResult.text)

        assertNull(CodeBuddyDeepSeekTransport.parseStreamJsonLine("""{"type":"system","subtype":"init"}"""))
    }

    private fun companionBody(
        bookId: String,
        text: String = "In empirical work, arc elasticity measures price sensitivity across finite intervals.",
        pageSha: String = "a".repeat(64),
        pageLen: Int = text.length,
        consented: Boolean = true,
        includeLocalSource: Boolean = true,
        customLocalSource: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("requestId", "comp0001")
        put("bookId", bookId)
        put("bookPages", 10)
        put("chapterId", "ch-2")
        put("chapterTitle", "2 Demand for health care")
        put("sections", buildJsonArray {
            add(buildJsonObject {
                put("sectionId", "sec-2-3")
                put("title", "2.3 Elasticities")
                put("isExerciseSection", false)
                put("blocks", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "$bookId:p1:0")
                        put("pdfPage", 1)
                        put("startUtf16", 0)
                        put("text", text)
                    })
                })
            })
        })
        if (customLocalSource != null) {
            put("localSource", customLocalSource)
        } else if (includeLocalSource) {
            put("localSource", buildJsonObject {
                put("schema", 1)
                put("book", bookId)
                put("format", "pdf")
                put("title", "Synthetic Economics")
                put("consented", consented)
                put("pages", buildJsonArray {
                    add(buildJsonObject {
                        put("number", 1)
                        put("length", pageLen)
                        put("ocr", false)
                        put("sha256", pageSha)
                    })
                })
            })
        }
    }

    @Test
    fun companionChapterAndLedgerRoutesWorkOverHttpAndEnforceReceiptSecurity() = runBlocking {
        val dir = Files.createTempDirectory("coach-companion-http-")
        val store = com.coach.store.LedgerStore(dir)
        val ownerToken = "secret-token-for-companion-route-test-0001"
        store.enroll("owner-dev", ownerToken)
        val friendCred = requireNotNull(store.pair(store.createPairingCode(friend = "alice").code, "alice-laptop"))
        val friendToken = friendCred.token
        val friendDev = friendCred.deviceId
        val ledger = BookTokenLedger(dir.resolve("companion"))
        val cache = ChapterCompanionCache(dir.resolve("companion"))
        val imports = ImportedBookSources(dir.resolve("owner-imports"))
        val friends = FriendAccess(dir.resolve("friends"), state = dir.resolve("state"), config = dir.resolve("friends.json"))
        val transportCalls = AtomicInteger(0)
        val delayMs = java.util.concurrent.atomic.AtomicLong(0L)
        val stubTransport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int,
            ): BatchTransportReply {
                transportCalls.incrementAndGet()
                val wait = delayMs.get()
                if (wait > 0L) delay(wait)
                return BatchTransportReply(
                    text = """{"coreQuestion":"需求弹性如何衡量价格敏感度？","keyPoints":[{"summary":"弧弹性用于区间中点衡量价格变化对就医量的影响。","pdfPage":1,"quoteAnchor":"arc elasticity measures price"}],"jargonGlosses":[],"pretest":[],"derivations":[],"optionalPractice":[]}""",
                    routeChannel = CompanionRouteChannel.CODEBUDDY_DEEPSEEK_FLASH,
                    model = "deepseek-v4.1-flash",
                    inputTokens = 640,
                    outputTokens = 210,
                    ttftMs = 28,
                    elapsedMs = 40,
                )
            }
        }
        val pipeline = SectionConcurrentCompanionPipeline(stubTransport, ledger, cache)
        val actor = com.coach.core.LedgerActor(this, store)
        val server = io.ktor.server.engine.embeddedServer(io.ktor.server.netty.Netty, host = "127.0.0.1", port = 0) {
            install(io.ktor.server.application.createApplicationPlugin("TestAuth") {
                onCall { call ->
                    val bearer = call.request.headers["Authorization"]?.removePrefix("Bearer ").orEmpty()
                    if (bearer == friendToken) {
                        call.attributes.put(observedDevice, friendDev)
                        call.attributes.put(observedFriend, "alice")
                    } else {
                        call.attributes.put(observedDevice, "owner-dev")
                    }
                }
            })
            libraryExplainRoutes(
                actor = actor,
                reader = null,
                library = null,
                imports = imports,
                friends = friends,
                companionPipeline = pipeline,
                tokenLedger = ledger,
            )
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val http = java.net.http.HttpClient.newHttpClient()
            fun post(payload: JsonObject, token: String = ownerToken): java.net.http.HttpResponse<String> = http.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/v1/library/companion/chapter"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $token")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )

            val bookId = "d".repeat(64)

            // 1. Security gate: missing localSource, dummy {}, unconsented, forged offset, non-64-hex bookId ALL fail closed BEFORE transport/ledger!
            assertEquals(403, post(companionBody(bookId, includeLocalSource = false), friendToken).statusCode())
            assertEquals(409, post(companionBody(bookId, includeLocalSource = false), ownerToken).statusCode())
            assertEquals(400, post(companionBody(bookId, customLocalSource = buildJsonObject { }), friendToken).statusCode())
            assertEquals(400, post(companionBody(bookId, consented = false), friendToken).statusCode())
            assertEquals(400, post(companionBody(bookId, pageLen = 5), friendToken).statusCode())
            assertEquals(400, post(companionBody("short-unregistered-book"), friendToken).statusCode())
            assertEquals(0, transportCalls.get(), "Unauthorized/invalid companion requests must never call AI transport")
            assertEquals(0, ledger.bookSummary(bookId).calls, "Unauthorized/invalid companion requests must never write Token Ledger")

            // 2. Friend cannot read global ledger endpoint
            val friendLedgerResp = http.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/v1/library/companion/ledger?bookId=$bookId"))
                    .header("Authorization", "Bearer $friendToken")
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(403, friendLedgerResp.statusCode())

            // 3. Valid consented personal book succeeds for owner and registers receipt
            val postResp = post(companionBody(bookId), ownerToken)
            assertEquals(200, postResp.statusCode())
            val pack = ChapterCompanionCodec.decode(postResp.body())
            assertEquals(1, pack.keyPoints.size)
            assertNotNull(pack.keyPoints.single().anchor)
            assertEquals(1, pack.stats.verifiedAnchors)
            assertEquals(1, transportCalls.get())

            // 4. Tampering with page sha256 or block text on registered book fails closed (409 Conflict) before transport
            assertEquals(409, post(companionBody(bookId, pageSha = "b".repeat(64)), ownerToken).statusCode())
            assertEquals(409, post(companionBody(bookId, text = "Tampered client text that does not match registered page."), ownerToken).statusCode())
            assertEquals(1, transportCalls.get(), "Snapshot conflict must not invoke AI transport")

            // 5. Friend with valid consented receipt succeeds on isolated friend store & cache (does NOT leak owner's cache)
            val friendOk = post(companionBody(bookId), friendToken)
            assertEquals(200, friendOk.statusCode())
            val friendPack = ChapterCompanionCodec.decode(friendOk.body())
            assertFalse(friendPack.stats.fromCache, "Friend must not hit owner's cache scope")
            assertEquals(2, transportCalls.get())

            // 6. Friend tampering with registered page fails closed (409 Conflict)
            assertEquals(409, post(companionBody(bookId, pageSha = "c".repeat(64)), friendToken).statusCode())
            assertEquals(2, transportCalls.get())

            // 7. Revoking friend's receipt mid-flight (isCurrent == false) withholds response (409 Conflict) and does NOT cache
            delayMs.set(350L)
            val friendBook2 = "e".repeat(64)
            val inFlight = async(kotlinx.coroutines.Dispatchers.IO) {
                post(companionBody(friendBook2), friendToken)
            }
            while (transportCalls.get() < 3) delay(10)
            val friendImportsDb = dir.resolve("friends").resolve(com.coach.store.sha256("alice".toByteArray())).resolve("imports/sources.sqlite")
            java.sql.DriverManager.getConnection("jdbc:sqlite:$friendImportsDb").use { db ->
                db.createStatement().use { it.executeUpdate("DELETE FROM blocks") }
            }
            val revokedResp = inFlight.await()
            assertEquals(409, revokedResp.statusCode(), "Deleted receipt during generation must withhold companion response")
            delayMs.set(0L)

            // 8. Owner ledger reflects verified calls
            val ledgerResp = http.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/v1/library/companion/ledger?bookId=$bookId"))
                    .header("Authorization", "Bearer $ownerToken")
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, ledgerResp.statusCode())
            assertTrue(ledgerResp.body().contains("\"withinBudget\":true"))
            assertTrue(ledgerResp.body().contains("\"calls\":2"))
        } finally {
            server.stop(100, 500)
            actor.close()
        }
    }

    @Test
    fun companionChapterFailsClosedWhenImportStoreOrFriendAccessIsUnconfiguredOrCatalogReserved() = runBlocking {
        val dir = Files.createTempDirectory("coach-companion-failclosed-")
        val store = com.coach.store.LedgerStore(dir)
        val friendCred = requireNotNull(store.pair(store.createPairingCode(friend = "bob").code, "bob-laptop"))
        val friendToken = friendCred.token
        val friendDev = friendCred.deviceId
        val ledger = BookTokenLedger(dir.resolve("companion"))
        val transportCalls = AtomicInteger(0)
        val stubTransport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int,
            ): BatchTransportReply {
                transportCalls.incrementAndGet()
                error("Transport must never be called when import/friend store is missing")
            }
        }
        val pipeline = SectionConcurrentCompanionPipeline(stubTransport, ledger)
        val actor = com.coach.core.LedgerActor(this, store)
        val server = io.ktor.server.engine.embeddedServer(io.ktor.server.netty.Netty, host = "127.0.0.1", port = 0) {
            install(io.ktor.server.application.createApplicationPlugin("TestAuth") {
                onCall { call ->
                    val bearer = call.request.headers["Authorization"]?.removePrefix("Bearer ").orEmpty()
                    if (bearer == friendToken) {
                        call.attributes.put(observedDevice, friendDev)
                        call.attributes.put(observedFriend, "bob")
                    } else {
                        call.attributes.put(observedDevice, "owner-dev")
                    }
                }
            })
            // Deliberately omit imports & friends (exact Reviewer-Kimi probe scenario)
            libraryExplainRoutes(actor, reader = null, companionPipeline = pipeline, tokenLedger = ledger)
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val http = java.net.http.HttpClient.newHttpClient()
            val bookId = "f".repeat(64)
            for (token in listOf(friendToken, "owner-token")) {
                val resp = http.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/v1/library/companion/chapter"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $token")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(companionBody(bookId).toString()))
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(503, resp.statusCode(), "Missing importStore/FriendAccess must fail closed with 503")
            }
            assertEquals(0, transportCalls.get())
            assertEquals(0, ledger.bookSummary(bookId).calls)
        } finally {
            server.stop(100, 500)
            actor.close()
        }
    }
}
