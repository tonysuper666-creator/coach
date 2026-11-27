package com.coach.server

import com.coach.ai.BatchPending
import com.coach.ai.BridgeClient
import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudyModePackServiceTest {
    private val samplePages = listOf(
        StudyPageInput(
            141,
            "The RAND Health Insurance Experiment randomly assigned families to plans with different coinsurance rates across multiple sites. Individuals in the free-care plan consumed about 30 percent more medical services than those on the catastrophic plan.",
        ),
        StudyPageInput(
            142,
            "When the coinsurance rate falls toward zero, total medical expenditure rises significantly while health outcomes improve mainly for low-income participants with hypertension.",
        ),
        StudyPageInput(
            145,
            "In our benchmark model, optimal insurance sets coinsurance proportional to the elasticity times 3/2 times the variance term, so marginal benefit equals marginal moral hazard cost.",
        ),
        StudyPageInput(
            150,
            "Exercise 8.2 Compare total welfare loss under full insurance versus 25 percent coinsurance when demand elasticity equals -0.2.",
        ),
    )

    @Test
    fun originalOnlyUsesZeroAiTokensAndZeroTransportCalls() {
        val streamCalls = AtomicInteger(0)
        val batchCalls = AtomicInteger(0)
        val service = StudyModePackService(
            streamTransport = object : StudyModeStreamTransport {
                override fun streamGenerate(
                    systemPrompt: String,
                    userPrompt: String,
                    maxTokens: Int,
                    friend: String?,
                    device: String?,
                    onDelta: (String, Long) -> Unit,
                ): StudyModeTransportReply {
                    streamCalls.incrementAndGet()
                    error("Transport must not be called in ORIGINAL_ONLY mode")
                }
            },
            batchTransport = object : StudyModeBatchTransport {
                override fun complete(
                    task: String,
                    jobKey: String,
                    systemPrompt: String,
                    userPrompt: String,
                    maxTokens: Int,
                    useBatchApi: Boolean,
                    friend: String?,
                    device: String?,
                ): StudyModeTransportReply {
                    batchCalls.incrementAndGet()
                    error("Batch transport must not be called in ORIGINAL_ONLY mode")
                }
            },
        )
        val pack = service.generateModePack(
            StudyModeRequest(
                bookId = "he-1",
                bookTitle = "Health Economics",
                chapterNum = 8,
                chapterTitle = "The RAND Health Insurance Experiment",
                mode = ServerStudyMode.EXAM_FOCUS,
                detailLevel = ServerDetailLevel.ORIGINAL_ONLY,
                explainLang = ServerExplainLang.BILINGUAL,
                pages = samplePages,
                sections = listOf(StudySectionInput("8.1", "8.1 Design", 141, 145)),
            ),
        )
        assertEquals(0, streamCalls.get())
        assertEquals(0, batchCalls.get())
        assertEquals("true", pack["zeroAiTokens"]?.jsonPrimitive?.content)
        assertEquals(0, pack["anchorStats"]?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun anchorGateRelocatesRepairsAndFailClosedDropsUnverifiableItems() {
        val batchTransport = object : StudyModeBatchTransport {
            override fun complete(
                task: String,
                jobKey: String,
                systemPrompt: String,
                userPrompt: String,
                maxTokens: Int,
                useBatchApi: Boolean,
                friend: String?,
                device: String?,
            ): StudyModeTransportReply = StudyModeTransportReply(
                text = """{"repairs":[{"id":"root.mustKnows[1]#pdfPage","pdfPage":142,"quoteAnchor":"total medical expenditure rises significantly while health outcomes improve"}]}""",
                routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                model = BridgeClient.HAIKU,
                inputTokens = 80,
                outputTokens = 40,
                firstTokenMs = 120,
                elapsedMs = 200,
            )
        }
        val payload = LedgerJson.parseToJsonElement(
            """{
                "triageSummary":"Summary of Ch8 focus.",
                "mustKnows":[
                    {"topic":"Relocated item","examWeight":"HIGH","whyTested":"Experiment design","pdfPage":145,"quoteAnchor":"randomly assigned families to plans with different coinsurance rates","scoringRubric":["Mention randomization"]},
                    {"topic":"Repaired item","examWeight":"CORE","whyTested":"Outcomes","pdfPage":141,"quoteAnchor":"paraphrased bad anchor that is not verbatim","scoringRubric":["State outcome"]},
                    {"topic":"Unrepairable item","examWeight":"CORE","whyTested":"Hallucinated","pdfPage":142,"quoteAnchor":"completely invented quote not anywhere in chapter text","scoringRubric":["Drop me"]}
                ]
            }""",
        ).jsonObject

        val (verifiedObj, stats, _) = StudyModeAnchorGate.verifyAndRepair(
            payload = payload,
            pages = samplePages,
            repairTransport = batchTransport,
            bookId = "he-1",
            chapterNum = 8,
            friend = null,
            device = null,
        )
        val kept = verifiedObj["mustKnows"]!!.jsonArray
        assertEquals(2, kept.size)
        assertEquals(141, kept[0].jsonObject["pdfPage"]!!.jsonPrimitive.intOrNull)
        assertEquals(142, kept[1].jsonObject["pdfPage"]!!.jsonPrimitive.intOrNull)
        assertEquals(2, stats.total)
        assertEquals(2, stats.verified)
        assertEquals(0, stats.unverified)
        assertEquals(1, stats.dropped)
        assertEquals(2, stats.relocated)
    }

    @Test
    fun mathGateCatchesRatioDriftAndPlaceholderLeaksThenRepairsOrDropsFailClosed() {
        val batchTransport = object : StudyModeBatchTransport {
            override fun complete(
                task: String,
                jobKey: String,
                systemPrompt: String,
                userPrompt: String,
                maxTokens: Int,
                useBatchApi: Boolean,
                friend: String?,
                device: String?,
            ): StudyModeTransportReply = StudyModeTransportReply(
                text = """{"items":[{"id":"formulaSheets[0]","obj":{"name":"Optimal Coinsurance","formulaLatex":"c = (3/2) * E","coefficientNote":"系数精确为 3/2","examUsage":"均衡条件","pdfPage":145,"quoteAnchor":"optimal insurance sets coinsurance proportional to the elasticity times 3/2 times the variance term"}}]}""",
                routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                model = BridgeClient.HAIKU,
                inputTokens = 90,
                outputTokens = 60,
                firstTokenMs = 100,
                elapsedMs = 190,
            )
        }
        val badMathPayload = LedgerJson.parseToJsonElement(
            """{
                "formulaSheets":[
                    {"name":"Optimal Coinsurance","formulaLatex":"c = 3 * E","coefficientNote":"系数变成 3 倍","examUsage":"见 [依原文公式]","pdfPage":145,"quoteAnchor":"optimal insurance sets coinsurance proportional to the elasticity times 3/2 times the variance term"},
                    {"name":"Drop Broken","formulaLatex":"c = 5/2 * X","coefficientNote":"Placeholder leak [依原文公式]","examUsage":"unrepaired item","pdfPage":145,"quoteAnchor":"optimal insurance sets coinsurance proportional to the elasticity times 3/2 times the variance term"}
                ]
            }""",
        ).jsonObject

        val (cleaned, stats, _) = StudyModeMathGate.verifyAndRepair(
            payload = badMathPayload,
            pages = samplePages,
            sympy = SympySubprocessEngine(),
            repairTransport = batchTransport,
            bookId = "he-1",
            chapterNum = 8,
            friend = null,
            device = null,
        )
        val formulas = cleaned["formulaSheets"]!!.jsonArray
        assertEquals(1, formulas.size)
        assertEquals("系数精确为 3/2", formulas[0].jsonObject["coefficientNote"]!!.jsonPrimitive.content)
        assertFalse(PlaceholderLeakGuard.hasLeak(cleaned.toString()))
        assertEquals(0, stats.failed)
        assertEquals(1, stats.repaired)
        assertEquals(1, stats.dropped)
    }

    @Test
    fun antiGhostwriteAndPastExamOverlapGuardsWork() {
        val raw = LedgerJson.parseToJsonElement(
            """{
                "directConclusion":"参考范文 Full conclusion text. Second paragraph.",
                "evidenceHits":[{"hitId":"e1","bodyJumpPage":141,"quoteAnchor":"randomly assigned families to plans with different coinsurance rates","howItAnswersQuestion":"Shows demand response","limitOrCounterpoint":"Short run"}],
                "argumentationScaffold":["参考范文 full essay draft","Point 1: Contrast free plan with coinsurance"]
            }""",
        ).jsonObject
        val guarded = StudyModeAntiGhostwriteGuard.enforce(ServerStudyMode.QUESTION_DRIVEN, raw)
        assertFalse("参考范文" in guarded["directConclusion"]!!.jsonPrimitive.content)
        assertEquals(1, guarded["argumentationScaffold"]!!.jsonArray.size)

        val overlap = StudyModePastExamGuard.checkOverlap(
            guarded,
            listOf("free care plan raises outpatient spending significantly for insured households"),
        )
        assertEquals(0, overlap)

        val leakObj = buildJsonObject {
            put("directConclusion", "The free care plan raises outpatient spending significantly for families.")
        }
        assertTrue(
            StudyModePastExamGuard.checkOverlap(
                leakObj,
                listOf("free care plan raises outpatient spending significantly for insured households"),
            ) > 0,
        )
    }

    @Test
    fun sseRoutesStreamAndBookMapBatchGenerates24ChaptersAndClusters() = runBlocking(Dispatchers.IO) {
        val dir = Files.createTempDirectory("study-mode-route-")
        try {
            val store = LedgerStore(dir)
            val ownerToken = "owner-study-mode-test-token-32ch"
            store.enroll("owner-dev", ownerToken)
            val actor = LedgerActor(this, store)
            val ledger = BookTokenLedger(dir.resolve("ledger"))
            val cache = StudyModePackCache(dir.resolve("cache"))
            val streamCalls = AtomicInteger(0)

            val streamTransport = object : StudyModeStreamTransport {
                override fun streamGenerate(
                    systemPrompt: String,
                    userPrompt: String,
                    maxTokens: Int,
                    friend: String?,
                    device: String?,
                    onDelta: (String, Long) -> Unit,
                ): StudyModeTransportReply {
                    streamCalls.incrementAndGet()
                    val jsonBody = when {
                        "DIAGNOSTIC_SKIP" in systemPrompt ->
                            """{"skipPolicy":"Pass gate to skip section 8.1","diagnosticGates":[{"gateId":"g1","sectionTitle":"8.1","targetConcept":"Coinsurance","questionType":"CONCEPT_CHECK","questionPrompt":"Which group consumed 30% more care?","options":["Free care","Catastrophic"],"correctIndex":0,"passSkipNote":"Skip 8.1","missRemediation":"Check free plan result","missJumpPdfPage":141,"quoteAnchor":"Individuals in the free-care plan consumed about 30 percent more medical services"}]}"""
                        "PROBLEM_DRIVEN" in systemPrompt ->
                            """{"strategyNote":"Attempt Exercise 8.2 first","problems":[{"problemId":"p1","title":"Ex 8.2","exercisePdfPage":150,"exerciseQuoteAnchor":"Compare total welfare loss under full insurance versus 25 percent coinsurance","attemptPrompt":"Set up deadweight loss triangle","scaffoldingHints":["Find change in quantity"],"keyDerivationCheck":"marginal benefit equals marginal moral hazard cost","bodyPdfPage":145,"quoteAnchor":"marginal benefit equals marginal moral hazard cost","answerOutline":["Draw DWL area"]}]}"""
                        "QUESTION_DRIVEN" in systemPrompt ->
                            """{"questionFocus":"How does coinsurance affect utilization?","directConclusion":"Lower coinsurance increases medical utilization via moral hazard.","evidenceHits":[{"hitId":"h1","bodyJumpPage":141,"quoteAnchor":"Individuals in the free-care plan consumed about 30 percent more medical services","howItAnswersQuestion":"Controlled experiment isolates price effect","limitOrCounterpoint":"Low-income hypertension exception"}],"argumentationScaffold":["Cite RAND randomization","Discuss income heterogeneity"],"antiGhostwriteNotice":"仅提供原文证据索引与论证骨架，请用自己的话完成作答。","followUpQuestions":["What about supply side?"]}"""
                        else ->
                            """{"chapterRole":"CORE","oneLiner":"RAND HIE proves coinsurance alters care usage.","estimatedMinutes":20,"readingDepth":"CORE_SKIM","prerequisites":["Ch.7"],"unlocks":["Ch.9"],"coreClaims":[{"claim":"Free plan raised utilization ~30%","importance":"CORE","pdfPage":141,"quoteAnchor":"Individuals in the free-care plan consumed about 30 percent more medical services"}],"sections":[{"sectionTitle":"8.1 Design","summary":"Randomized trial design","skipUnlessExam":false,"pdfPage":141,"quoteAnchor":"The RAND Health Insurance Experiment randomly assigned families to plans"}]}"""
                    }
                    onDelta(jsonBody.take(40), 50L)
                    onDelta(jsonBody.drop(40), 120L)
                    return StudyModeTransportReply(
                        text = jsonBody,
                        routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                        model = BridgeClient.HAIKU,
                        inputTokens = 650,
                        outputTokens = 210,
                        firstTokenMs = 50L,
                        elapsedMs = 180L,
                    )
                }
            }

            val batchTransport = object : StudyModeBatchTransport {
                override fun complete(
                    task: String,
                    jobKey: String,
                    systemPrompt: String,
                    userPrompt: String,
                    maxTokens: Int,
                    useBatchApi: Boolean,
                    friend: String?,
                    device: String?,
                ): StudyModeTransportReply {
                    if ("Synthesize a whole-book" in systemPrompt) {
                        return StudyModeTransportReply(
                            text = """{"thematicClusters":[{"clusterId":"cl-1","title":"Demand & Insurance","themeSummary":"Core demand theory","chapterNums":[1,2,3,4,5,6,7,8]}],"dependencyEdges":[{"fromChapter":6,"toChapter":8,"relation":"PREREQUISITE","reason":"Insurance demand"}],"fastSkimOrder40Min":[{"chapterNum":8,"minutes":8,"focus":"RAND HIE"}],"masterGlossary":[{"en":"Moral hazard","zh":"道德风险","chapterNum":8}]}""",
                            routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                            model = BridgeClient.HAIKU,
                            inputTokens = 1200,
                            outputTokens = 300,
                            firstTokenMs = 200L,
                            elapsedMs = 400L,
                        )
                    }
                    // Parse chapter numbers from prompt and return verbatim quotes
                    val nums = Regex("CHAPTER (\\d+):").findAll(userPrompt).map { it.groupValues[1].toInt() }.toList()
                    val chaptersJson = buildJsonArray {
                        nums.forEach { n ->
                            add(
                                buildJsonObject {
                                    put("chapterNum", n)
                                    put("chapterTitle", "Chapter $n")
                                    put("chapterRole", "CORE")
                                    put("oneLiner", "Summary of chapter $n")
                                    put("estimatedMinutes", 18)
                                    put("readingDepth", "CORE_SKIM")
                                    put(
                                        "coreClaims",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("claim", "Core concept in chapter $n")
                                                    put("importance", "CORE")
                                                    put("pdfPage", n * 10 + 1)
                                                    put("quoteAnchor", "Chapter $n core economic mechanism governs equilibrium insurance demand")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    }
                    return StudyModeTransportReply(
                        text = buildJsonObject { put("chapters", chaptersJson) }.toString(),
                        routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                        model = BridgeClient.HAIKU,
                        inputTokens = 3000,
                        outputTokens = 800,
                        firstTokenMs = 300L,
                        elapsedMs = 600L,
                    )
                }
            }

            val service = StudyModePackService(streamTransport, batchTransport, ledger, cache)
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                ledgerRoutes(actor, MutableSharedFlow(), adminDevice = "owner-dev")
                studyModeRoutes(actor, service = service)
            }.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val http = HttpClient.newHttpClient()
            try {
                // Unauthorized request -> 401
                val unauth = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/books/he-1/modes/BOOK_MAP"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(401, unauth.statusCode())

                val reqBody = buildJsonObject {
                    put("bookTitle", "Health Economics")
                    put("chapterNum", 8)
                    put("chapterTitle", "The RAND Health Insurance Experiment")
                    put("detailLevel", "DETAILED")
                    put("explainLang", "BILINGUAL")
                    put("stream", true)
                    put(
                        "pages",
                        buildJsonArray {
                            samplePages.forEach { p ->
                                add(buildJsonObject { put("pdfPage", p.pdfPage); put("text", p.text) })
                            }
                        },
                    )
                }.toString()

                // SSE stream request -> meta + first_screen + pack + done
                val sseResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/books/he-1/modes/BOOK_MAP"))
                        .header("X-Coach-Device", "owner-dev")
                        .header("Authorization", "Bearer $ownerToken")
                        .header("Accept", "text/event-stream")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, sseResp.statusCode())
                val sseBody = sseResp.body()
                assertTrue("event: meta" in sseBody)
                assertTrue("event: first_screen" in sseBody)
                assertTrue("event: pack" in sseBody)
                assertTrue("event: done" in sseBody)
                assertEquals(1, streamCalls.get())

                // Second identical call hits SQLite cache -> streamCalls remains 1
                val jsonResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/books/he-1/modes/BOOK_MAP"))
                        .header("X-Coach-Device", "owner-dev")
                        .header("Authorization", "Bearer $ownerToken")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(reqBody.replace("\"stream\":true", "\"stream\":false")))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, jsonResp.statusCode())
                assertEquals(1, streamCalls.get())
                assertEquals("true", LedgerJson.parseToJsonElement(jsonResp.body()).jsonObject["cacheHit"]?.jsonPrimitive?.content)

                // Whole-book 24-chapter batch endpoint
                val batchPayload = buildJsonObject {
                    put("bookTitle", "Health Economics")
                    put("explainLang", "BILINGUAL")
                    put("useBatchApi", true)
                    put(
                        "chapters",
                        buildJsonArray {
                            for (c in 1..24) {
                                add(
                                    buildJsonObject {
                                        put("chapterNum", c)
                                        put("chapterTitle", "Chapter $c")
                                        put(
                                            "pages",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("pdfPage", c * 10 + 1)
                                                        put("text", "Chapter $c core economic mechanism governs equilibrium insurance demand across healthcare markets.")
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                }.toString()

                val batchResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/books/he-1/book-map/batch"))
                        .header("X-Coach-Device", "owner-dev")
                        .header("Authorization", "Bearer $ownerToken")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(batchPayload))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, batchResp.statusCode())
                val batchJson = LedgerJson.parseToJsonElement(batchResp.body()).jsonObject
                assertEquals(24, batchJson["chapterCount"]?.jsonPrimitive?.intOrNull)
                assertEquals(0, batchJson["anchorStats"]?.jsonObject?.get("unverified")?.jsonPrimitive?.intOrNull)
                assertTrue(batchJson["thematicClusters"]!!.jsonArray.isNotEmpty())
                assertTrue(batchJson["fastSkimOrder40Min"]!!.jsonArray.isNotEmpty())
                assertTrue(batchJson["masterGlossary"]!!.jsonArray.isNotEmpty())
                assertEquals("true", batchJson["costStats"]?.jsonObject?.get("batchMode")?.jsonPrimitive?.content)
            } finally {
                server.stop(100, 500)
                actor.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun publicFriendRoutesAllowStudyModesAndBatchWhileRejectingTraversal() = runBlocking(Dispatchers.IO) {
        val dir = Files.createTempDirectory("study-mode-friend-")
        try {
            val store = LedgerStore(dir)
            val ownerToken = "owner-friend-mode-test-token-32ch"
            store.enroll("owner-dev", ownerToken)
            val actor = LedgerActor(this, store)
            val friends = FriendAccess(dir.resolve("friends"))
            val streamTransport = object : StudyModeStreamTransport {
                override fun streamGenerate(
                    systemPrompt: String,
                    userPrompt: String,
                    maxTokens: Int,
                    friend: String?,
                    device: String?,
                    onDelta: (String, Long) -> Unit,
                ) = StudyModeTransportReply(
                    text = """{"chapterRole":"CORE","oneLiner":"Friend summary","coreClaims":[{"claim":"Coinsurance affects demand","importance":"CORE","pdfPage":141,"quoteAnchor":"randomly assigned families to plans with different coinsurance rates"}]}""",
                    routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                    model = BridgeClient.HAIKU,
                    inputTokens = 100,
                    outputTokens = 50,
                    firstTokenMs = 40L,
                    elapsedMs = 90L,
                )
            }
            val batchTransport = object : StudyModeBatchTransport {
                override fun complete(
                    task: String,
                    jobKey: String,
                    systemPrompt: String,
                    userPrompt: String,
                    maxTokens: Int,
                    useBatchApi: Boolean,
                    friend: String?,
                    device: String?,
                ) = StudyModeTransportReply(
                    text = """{"chapters":[{"chapterNum":8,"chapterTitle":"RAND","chapterRole":"CORE","oneLiner":"Friend batch","coreClaims":[]}],"thematicClusters":[],"dependencyEdges":[],"fastSkimOrder40Min":[],"masterGlossary":[]}""",
                    routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                    model = BridgeClient.HAIKU,
                    inputTokens = 120,
                    outputTokens = 60,
                    firstTokenMs = 50L,
                    elapsedMs = 100L,
                )
            }
            val service = StudyModePackService(streamTransport, batchTransport)
            val local = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                ledgerRoutes(actor, MutableSharedFlow(), adminDevice = "owner-dev", friends = friends)
                studyModeRoutes(actor, friends = friends, service = service)
            }.start(wait = false)
            val pub = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                ledgerRoutes(actor, MutableSharedFlow(), adminDevice = "owner-dev", friends = friends, publicOnly = true)
                studyModeRoutes(actor, friends = friends, service = service)
            }.start(wait = false)
            val localPort = local.engine.resolvedConnectors().first().port
            val pubPort = pub.engine.resolvedConnectors().first().port
            val http = HttpClient.newHttpClient()
            try {
                val codeResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$localPort/v1/pairing-code"))
                        .header("X-Coach-Device", "owner-dev")
                        .header("Authorization", "Bearer $ownerToken")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"friend":"study-friend"}"""))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, codeResp.statusCode())
                val code = LedgerJson.parseToJsonElement(codeResp.body()).jsonObject["code"]!!.jsonPrimitive.content

                val pairResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$pubPort/v1/pair"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"code":"$code","name":"Friend Laptop"}"""))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, pairResp.statusCode())
                val pairJson = LedgerJson.parseToJsonElement(pairResp.body()).jsonObject
                val fDev = pairJson["deviceId"]!!.jsonPrimitive.content
                val fTok = pairJson["token"]!!.jsonPrimitive.content

                val okMode = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$pubPort/api/books/he-1/modes/BOOK_MAP"))
                        .header("X-Coach-Device", fDev)
                        .header("Authorization", "Bearer $fTok")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                """{"chapterNum":8,"stream":false,"pages":[{"pdfPage":141,"text":"The RAND Health Insurance Experiment randomly assigned families to plans with different coinsurance rates."}]}""",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, okMode.statusCode())

                val traversal = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$pubPort/api/books/he-1/modes/..%2fBOOK_MAP"))
                        .header("X-Coach-Device", fDev)
                        .header("Authorization", "Bearer $fTok")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(404, traversal.statusCode())
            } finally {
                pub.stop(100, 500)
                local.stop(100, 500)
                actor.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun bookMapBatchBackfillsRealSectionPdfPagesAndSeparatesJobKeysByLanguageAndContent() = runBlocking(Dispatchers.IO) {
        val observedJobKeys = mutableListOf<String>()
        val noopStream = object : StudyModeStreamTransport {
            override fun streamGenerate(
                systemPrompt: String,
                userPrompt: String,
                maxTokens: Int,
                friend: String?,
                device: String?,
                onDelta: (String, Long) -> Unit,
            ): StudyModeTransportReply = error("not used")
        }
        val batchTransport = object : StudyModeBatchTransport {
            override fun complete(
                task: String,
                jobKey: String,
                systemPrompt: String,
                userPrompt: String,
                maxTokens: Int,
                useBatchApi: Boolean,
                friend: String?,
                device: String?,
            ): StudyModeTransportReply {
                observedJobKeys += jobKey
                val text = if ("Synthesize a whole-book" in systemPrompt) {
                    """{"thematicClusters":[],"dependencyEdges":[],"fastSkimOrder40Min":[],"masterGlossary":[]}"""
                } else {
                    // Simulate model omitting pdfPage on section 8.1 and emitting bogus pdfPage=1 on section 8.2
                    """{"chapters":[{"chapterNum":8,"chapterTitle":"RAND","chapterRole":"CORE","oneLiner":"Ch8","estimatedMinutes":15,"readingDepth":"CORE_SKIM","coreClaims":[{"claim":"Core","importance":"CORE","pdfPage":141,"quoteAnchor":"The RAND Health Insurance Experiment randomly assigned families to plans"}],"sections":[{"sectionTitle":"8.1 Design","summary":"Design","skipUnlessExam":false},{"sectionTitle":"8.2 Results","summary":"Results","skipUnlessExam":false,"pdfPage":1}]}]}"""
                }
                return StudyModeTransportReply(
                    text = text,
                    routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                    model = BridgeClient.HAIKU,
                    inputTokens = 100,
                    outputTokens = 80,
                    firstTokenMs = 10L,
                    elapsedMs = 20L,
                )
            }
        }
        val service = StudyModePackService(noopStream, batchTransport)
        val ch8Sections = listOf(
            StudySectionInput("8.1", "8.1 Design", 141, 145),
            StudySectionInput("8.2", "8.2 Results", 146, 155),
        )
        val baseReq = BookMapBatchRequest(
            bookId = "he-1",
            bookTitle = "Health Economics",
            detailLevel = ServerDetailLevel.BRIEF,
            explainLang = ServerExplainLang.BILINGUAL,
            chapters = listOf(BookMapChapterInput(8, "RAND", samplePages, ch8Sections)),
            bypassCache = true,
        )
        val bilingualRes = service.generateBookMapBatch(baseReq)
        val chObj = bilingualRes["chapters"]!!.jsonArray.single().jsonObject
        val secs = chObj["sections"]!!.jsonArray
        assertEquals(2, secs.size)
        assertEquals(141, secs[0].jsonObject["pdfPage"]!!.jsonPrimitive.intOrNull)
        assertEquals(146, secs[1].jsonObject["pdfPage"]!!.jsonPrimitive.intOrNull)
        val keysBilingual = observedJobKeys.toList()
        assertEquals(2, keysBilingual.size)

        observedJobKeys.clear()
        service.generateBookMapBatch(baseReq.copy(explainLang = ServerExplainLang.EN))
        val keysEn = observedJobKeys.toList()
        assertFalse(keysBilingual[0] == keysEn[0], "Batch chunk jobKey must vary by explainLang")
        assertFalse(keysBilingual[1] == keysEn[1], "Batch graph jobKey must vary by explainLang")

        observedJobKeys.clear()
        val alteredPages = listOf(samplePages.first().copy(text = "Altered chapter text on page 141 for cache digest check.")) + samplePages.drop(1)
        service.generateBookMapBatch(baseReq.copy(chapters = listOf(BookMapChapterInput(8, "RAND", alteredPages, ch8Sections))))
        val keysAltered = observedJobKeys.toList()
        assertFalse(keysBilingual[0] == keysAltered[0], "Batch chunk jobKey must vary by chapter text digest")
        assertFalse(keysBilingual[1] == keysAltered[1], "Batch graph jobKey must vary by chapter text digest")
    }

    @Test
    fun friendBookMapLeavesTheOwnersAiCallTableUntouched() = runBlocking(Dispatchers.IO) {
        val dir = java.nio.file.Files.createTempDirectory("coach-friend-book-map-")
        try {
            val friends = mutableListOf<String?>()
            val noopStream = object : StudyModeStreamTransport {
                override fun streamGenerate(systemPrompt: String, userPrompt: String, maxTokens: Int, friend: String?, device: String?,
                    onDelta: (String, Long) -> Unit): StudyModeTransportReply = error("not used")
            }
            val batchTransport = object : StudyModeBatchTransport {
                override fun complete(task: String, jobKey: String, systemPrompt: String, userPrompt: String, maxTokens: Int,
                    useBatchApi: Boolean, friend: String?, device: String?): StudyModeTransportReply {
                    friends += friend
                    val text = if ("Synthesize a whole-book" in systemPrompt) {
                        """{"thematicClusters":[],"dependencyEdges":[],"fastSkimOrder40Min":[],"masterGlossary":[]}"""
                    } else {
                        """{"chapters":[{"chapterNum":8,"chapterTitle":"RAND","chapterRole":"CORE","oneLiner":"Ch8","estimatedMinutes":15,"readingDepth":"CORE_SKIM","coreClaims":[],"sections":[{"sectionTitle":"8.1 Design","summary":"Design","skipUnlessExam":false}]}]}"""
                    }
                    return StudyModeTransportReply(text = text, routeChannel = CompanionRouteChannel.HAIKU_REALTIME, model = BridgeClient.HAIKU,
                        inputTokens = 100, outputTokens = 80, firstTokenMs = 10L, elapsedMs = 20L)
                }
            }
            val ledger = BookTokenLedger(dir.resolve("ledger"))
            val service = StudyModePackService(noopStream, batchTransport, ledger)
            val req = BookMapBatchRequest(
                bookId = "friend-book",
                bookTitle = "Health Economics",
                detailLevel = ServerDetailLevel.BRIEF,
                explainLang = ServerExplainLang.BILINGUAL,
                chapters = listOf(BookMapChapterInput(8, "RAND", samplePages, listOf(StudySectionInput("8.1", "8.1 Design", 141, 145)))),
                bypassCache = true,
                friend = "甲",
                device = "friend-pc",
            )
            service.generateBookMapBatch(req)
            assertTrue(friends.isNotEmpty() && friends.all { it == "甲" })
            assertTrue(ledger.entriesForBook("friend-book").isEmpty(), "friend book map reached the owner's AI-call table")
            service.generateBookMapBatch(req.copy(friend = null, device = "owner-dev"))
            assertTrue(ledger.entriesForBook("friend-book").isNotEmpty(), "owner book map must still be recorded")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun epubAnchorGateMatchesWrappedLinesCurlyQuotesEmDashEmitsOffsetsAndBackfillsMissingClaims() = runBlocking(Dispatchers.IO) {
        val wealthP23 = """# CHAPTER V.




OF THE REAL AND NOMINAL PRICE OF COMMODITIES, OR OF THEIR PRICE IN LABOUR, AND
THEIR PRICE IN MONEY.






Every man is rich or poor according to the degree in which he can afford to
enjoy the necessaries, conveniencies, and amusements of human life. But after
the division of labour has once thoroughly taken place, it is but a very small
part of these with which a man’s own labour can supply him."""

        val originP33 = """Finally, then, varieties have the same general characters as species, for they
cannot be distinguished from species,—except, firstly, by the discovery
of intermediate linking forms, and the occurrence of such links cannot affect
the actual characters of the forms which they connect; and except, secondly, by
a certain amount of

difference, for two forms, if differing very little, are generally ranked as
varieties."""

        val epubPages = listOf(StudyPageInput(23, wealthP23), StudyPageInput(33, originP33))

        // 1. Verify hard-wrapped EPUB quote + curly apostrophe + em-dash spacing snap to exact raw UTF-16 spans,
        //    and heading-to-body mashup is rejected.
        val payload = LedgerJson.parseToJsonElement(
            """{
                "coreClaims":[
                    {"claim":"劳动衡量真实财富","importance":"CORE","pdfPage":23,"quoteAnchor":"part of these with which a man's own labour can supply him"},
                    {"claim":"变种与物种连续性","importance":"CORE","pdfPage":33,"quoteAnchor":"cannot be distinguished from species, - except, firstly, by the discovery"},
                    {"claim":"误拼接标题与正文","importance":"CORE","pdfPage":23,"quoteAnchor":"THEIR PRICE IN MONEY. Every man is rich or poor"}
                ]
            }""",
        ).jsonObject

        val (verified, stats, _) = StudyModeAnchorGate.verifyAndRepair(
            payload = payload,
            pages = epubPages,
            repairTransport = null,
            bookId = "epub-1",
            chapterNum = 5,
            friend = null,
            device = null,
        )
        val kept = verified["coreClaims"]!!.jsonArray
        assertEquals(2, kept.size, "Two valid EPUB quotes kept; heading-to-body mashup rejected")
        assertEquals(1, stats.dropped)

        val c0 = kept[0].jsonObject
        val s0 = c0["startUtf16"]!!.jsonPrimitive.intOrNull!!
        val e0 = c0["endUtf16"]!!.jsonPrimitive.intOrNull!!
        assertTrue(s0 in 0 until e0)
        assertEquals(wealthP23.substring(s0, e0), c0["quoteAnchor"]!!.jsonPrimitive.content)

        val c1 = kept[1].jsonObject
        val s1 = c1["startUtf16"]!!.jsonPrimitive.intOrNull!!
        val e1 = c1["endUtf16"]!!.jsonPrimitive.intOrNull!!
        assertTrue(s1 in 0 until e1)
        assertEquals(originP33.substring(s1, e1), c1["quoteAnchor"]!!.jsonPrimitive.content)

        // 2. Verify prompt formatter keeps heading separated from prose (never collapses \n\n\n into a single space).
        val formatted = StudyModeAnchorGate.formatPageExcerptForPrompt(wealthP23, 1400)
        assertTrue("\n" in formatted, "Paragraph/heading breaks must remain separated in prompt excerpts")
        assertFalse("MONEY. Every man" in formatted, "Heading and body paragraph must not be merged onto one line")

        // 3. Verify asyncPending returns non-blocking pending states for chunks -> graph -> complete,
        //    and backfills a verified clickable claim when the model emits empty coreClaims for a chapter.
        val pollStep = AtomicInteger(0)
        val noopStream = object : StudyModeStreamTransport {
            override fun streamGenerate(systemPrompt: String, userPrompt: String, maxTokens: Int, friend: String?, device: String?, onDelta: (String, Long) -> Unit): StudyModeTransportReply =
                error("not used")
        }
        val pendingTransport = object : StudyModeBatchTransport {
            override fun complete(task: String, jobKey: String, systemPrompt: String, userPrompt: String, maxTokens: Int, useBatchApi: Boolean, friend: String?, device: String?): StudyModeTransportReply =
                error("asyncPending must use completeOrPending")

            override fun completeOrPending(task: String, jobKey: String, systemPrompt: String, userPrompt: String, maxTokens: Int, useBatchApi: Boolean, friend: String?, device: String?): StudyModeTransportReply {
                val step = pollStep.get()
                if ("Synthesize a whole-book" in systemPrompt) {
                    if (step < 2) throw BatchPending(8_000L)
                    return StudyModeTransportReply(
                        text = """{"thematicClusters":[{"clusterId":"c1","title":"价值与分工","themeSummary":"劳动决定价值","chapterNums":[1,2,3,4,5,6,7]}],"dependencyEdges":[],"fastSkimOrder40Min":[{"chapterNum":5,"minutes":5,"focus":"劳动价值"}],"masterGlossary":[]}""",
                        routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                        model = BridgeClient.HAIKU,
                        inputTokens = 100,
                        outputTokens = 80,
                        firstTokenMs = 10L,
                        elapsedMs = 20L,
                    )
                }
                val isSecondChunk = "CHAPTER 7:" in userPrompt
                if (step == 0 && isSecondChunk) throw BatchPending(12_000L)
                val nums = Regex("CHAPTER (\\d+):").findAll(userPrompt).map { it.groupValues[1].toInt() }.toList()
                val chaptersJson = buildJsonArray {
                    nums.forEach { n ->
                        add(
                            buildJsonObject {
                                put("chapterNum", n)
                                put("chapterTitle", "Chapter $n")
                                put("chapterRole", "CORE")
                                put("oneLiner", "第 $n 章核心主题")
                                put("estimatedMinutes", 15)
                                put("readingDepth", "CORE_SKIM")
                                // Simulate Ch.3 returning empty coreClaims (e.g. dropped or omitted)
                                put("coreClaims", buildJsonArray {})
                            },
                        )
                    }
                }
                return StudyModeTransportReply(
                    text = buildJsonObject { put("chapters", chaptersJson) }.toString(),
                    routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                    model = BridgeClient.HAIKU,
                    inputTokens = 100,
                    outputTokens = 80,
                    firstTokenMs = 10L,
                    elapsedMs = 20L,
                )
            }
        }
        val service = StudyModePackService(noopStream, pendingTransport)
        val chapters7 = (1..7).map { n ->
            BookMapChapterInput(
                chapterNum = n,
                chapterTitle = "Chapter $n",
                pages = listOf(StudyPageInput(n * 10 + 3, if (n == 3) originP33 else wealthP23)),
                sections = listOf(StudySectionInput("ch-$n", "Chapter $n", n * 10 + 3, n * 10 + 8)),
            )
        }
        val batchReq = BookMapBatchRequest(
            bookId = "epub-async",
            bookTitle = "Wealth and Origin",
            detailLevel = ServerDetailLevel.BRIEF,
            explainLang = ServerExplainLang.BILINGUAL,
            chapters = chapters7,
            useBatchApi = true,
            bypassCache = true,
            asyncPending = true,
        )

        val poll1 = service.generateBookMapBatch(batchReq)
        assertEquals(true, poll1["pending"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("chapters", poll1["stage"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, poll1["completedChunks"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, poll1["totalChunks"]?.jsonPrimitive?.intOrNull)

        pollStep.set(1)
        val poll2 = service.generateBookMapBatch(batchReq)
        assertEquals(true, poll2["pending"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("graph", poll2["stage"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, poll2["completedChunks"]?.jsonPrimitive?.intOrNull)

        pollStep.set(2)
        val done = service.generateBookMapBatch(batchReq)
        assertEquals(null, done["pending"]?.jsonPrimitive?.booleanOrNull)
        val doneChapters = done["chapters"]!!.jsonArray
        assertEquals(7, doneChapters.size)
        for (chEl in doneChapters) {
            val ch = chEl.jsonObject
            val claims = ch["coreClaims"]!!.jsonArray
            assertTrue(claims.isNotEmpty(), "Every chapter must have at least one clickable coreClaim: $ch")
            val cl = claims[0].jsonObject
            val start = cl["startUtf16"]!!.jsonPrimitive.intOrNull!!
            val end = cl["endUtf16"]!!.jsonPrimitive.intOrNull!!
            val pg = cl["pdfPage"]!!.jsonPrimitive.intOrNull!!
            val expectedPageText = chapters7.first { it.chapterNum == ch["chapterNum"]!!.jsonPrimitive.intOrNull }.pages.first { it.pdfPage == pg }.text
            assertTrue(start in 0 until end)
            assertEquals(expectedPageText.substring(start, end), cl["quoteAnchor"]!!.jsonPrimitive.content)
        }
    }
}
