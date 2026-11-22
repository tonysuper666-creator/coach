package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import com.sun.net.httpserver.HttpServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PracticeGradeAndDiagnoseTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun stubReply(text: String, channel: CompanionRouteChannel = CompanionRouteChannel.HAIKU_REALTIME): BatchTransportReply =
        BatchTransportReply(
            text = text,
            routeChannel = channel,
            model = PracticeGradeLimits.PINNED_HAIKU_MODEL,
            inputTokens = 400,
            outputTokens = 180,
            ttftMs = 120,
            elapsedMs = 350,
        )

    @Test
    fun diagnosticSystemPromptIsStrictlyZeroInstance() {
        val banned = listOf(
            "externality", "externalities", "vaccine", "vaccination", "herd", "antibiotic",
            "pigou", "pigouvian", "coase", "coasian", "subsidy", "organ", "repugnance",
            "smoke", "cigarette", "grossman", "depreciation", "mec", "qaly", "coinsurance",
        )
        val low = PracticeGradeAndDiagnoseService.GENERIC_SHIELDED_SYSTEM.lowercase()
        for (w in banned) {
            assertFalse(Regex("""\b${Regex.escape(w)}\b""").containsMatchIn(low), "Leaked domain term: $w")
        }
    }

    @Test
    fun leakageGuardRejectsPastPaperInputs() {
        val transport = object : BatchAiTransport {
            override suspend fun generateSection(systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int): BatchTransportReply =
                error("Should not invoke transport on leaked input")
        }
        val svc = PracticeGradeAndDiagnoseService(transport)
        val leakedReq = PracticeGradeAndDiagnoseRequest(
            bookId = "book-1",
            questionStem = "Explain QALYs based on 01-Dec2024真题详解",
            studentAnswer = "Some draft",
        )
        val ex = runCatching { runBlocking { svc.process(leakedReq) } }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun studentQuoteGateRepairsOnceThenForciblyZerosUnverifiedCredit() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val transport = object : BatchAiTransport {
            var repairCount = 0
            override suspend fun generateSection(systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int): BatchTransportReply {
                calls += task to userPrompt
                return when {
                    systemPrompt == PracticeGradeAndDiagnoseService.GRADER_SYSTEM -> stubReply(
                        buildJsonObject {
                            put("items", buildJsonArray {
                                add(buildJsonObject {
                                    put("cid", "C1")
                                    put("verdict", "FULL")
                                    put("awardedMarks", 3.0)
                                    put("studentQuote", "fabricated paraphrase not in draft")
                                    put("feedbackNoteZh", "模型答对核心定义。")
                                    put("pdfPage", 73)
                                    put("quoteAnchor", "optimal health level")
                                })
                                add(buildJsonObject {
                                    put("cid", "C2")
                                    put("verdict", "PARTIAL")
                                    put("awardedMarks", 2.0)
                                    put("studentQuote", "another fake quote")
                                    put("feedbackNoteZh", "Model answer: here is a full-mark answer that you should copy word for word to get full credit in the final exam every single time without thinking.")
                                    put("pdfPage", 75)
                                    put("quoteAnchor", "depreciation of health increases")
                                })
                            })
                        }.toString(),
                    )
                    systemPrompt == PracticeGradeAndDiagnoseService.QUOTE_REPAIR_SYSTEM -> {
                        repairCount++
                        if (repairCount == 1) {
                            stubReply("""{"studentQuote":"MEC(H*) = r + gamma"}""")
                        } else {
                            stubReply("""{"studentQuote":"still not present anywhere"}""")
                        }
                    }
                    systemPrompt == PracticeGradeAndDiagnoseService.GENERIC_SHIELDED_SYSTEM -> stubReply(
                        """{"isEquivalentValid":false,"predicted":"GRAPH_SHIFT_OR_ROTATION","studentErrorSpan":"shifts the MEC curve downward","diagnosisZh":"折旧率上升应使r+γ线上移，MEC不动。"}""",
                    )
                    else -> stubReply(
                        """{"stem":"当利率固定为3%且健康折旧率从4%升至7%时，图上哪条线移动？","options":["A. 水平线r+γ上移，MEC不动","B. MEC下移，r+γ不动","C. 两条线同时下移","D. 两条线均不动"],"correctOption":"A. 水平线r+γ上移，MEC不动","targetedTrapExplanation":"折旧率变化只抬高资本使用成本线r+γ，不改变由教育决定的MEC曲线。","pdfPage":75,"quoteAnchor":"depreciation of health increases"}""",
                    )
                }
            }
        }

        val dir = Files.createTempDirectory("practice-grade-ledger-")
        try {
            val ledger = BookTokenLedger(dir)
            val svc = PracticeGradeAndDiagnoseService(transport, ledger)
            val req = PracticeGradeAndDiagnoseRequest(
                bookId = "book-ch3",
                chapterId = "ch3",
                sectionId = "sec-3.4",
                conceptKey = "book-ch3:sec-3.4",
                questionId = "q-ch3",
                questionStem = "Explain aging in Grossman model.",
                studentAnswer = "At optimum MEC(H*) = r + gamma. Aging shifts the MEC curve downward while r + gamma stays fixed.",
                rubricCriteria = listOf(
                    PracticeRubricCriterionInput("C1", "Equilibrium condition", 3.0, 73, "optimal health level"),
                    PracticeRubricCriterionInput("C2", "Aging comparative statics", 4.0, 75, "depreciation of health increases"),
                ),
                sourcePages = listOf(
                    PracticeSourcePageInput(73, "The optimal health level associated with MEC(H*) = r + gamma holds at the intersection."),
                    PracticeSourcePageInput(75, "As the individual ages, depreciation of health increases, shifting r + gamma upward while MEC stays fixed."),
                ),
                generateVariant = true,
            )

            val resp = svc.process(req)
            assertEquals(0, resp.unverifiedPositivePassCount)
            assertEquals(0, resp.rewrittenParagraphsEmitted)
            assertTrue(resp.rewrittenParagraphsBlocked >= 1)
            assertFalse("rewrite" in resp.toJson().keys)

            // C1 repaired on 1st retry and passed!
            val c1 = resp.rubricItems.first { it.cid == "C1" }
            assertEquals(PracticeRubricVerdict.FULL, c1.verdict)
            assertEquals(3.0, c1.awardedMarks, 1e-6)
            assertTrue(c1.studentQuoteVerified)
            assertTrue(c1.quoteRepairAttempted)
            assertFalse(c1.quoteGateZeroed)
            assertTrue(c1.anchorVerified)

            // C2 failed repair retry => FORCIBLY ZEROED!
            val c2 = resp.rubricItems.first { it.cid == "C2" }
            assertEquals(PracticeRubricVerdict.ZERO, c2.verdict)
            assertEquals(0.0, c2.awardedMarks, 1e-6)
            assertFalse(c2.studentQuoteVerified)
            assertTrue(c2.quoteGateZeroed)
            assertTrue(NoRewriteOutputGuard.isCompliant(c2.feedbackNoteZh))
            assertTrue(NoRewriteOutputGuard.chineseCharCount(c2.feedbackNoteZh) <= PracticeGradeLimits.MAX_FEEDBACK_ZH_CHARS)

            // Diagnosis & variant verified
            assertEquals(PracticeMisconceptionClass.GRAPH_SHIFT_OR_ROTATION, resp.diagnosis?.errorClass)
            val variant = assertNotNull(resp.variant)
            assertTrue(variant.anchorVerified)
            assertEquals(75, variant.pdfPage)
            assertTrue(variant.overlapAudit.passed)
            assertEquals(0, variant.overlapAudit.en8GramHits)
            assertEquals(0, variant.overlapAudit.zh12GramHits)

            // Token ledger recorded calls
            assertTrue(ledger.entriesForBook("book-ch3").size >= 4)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun serverNumericFactCheckerVerifiesAnalyticalValuesAndOverridesWrongFull() = runBlocking {
        val specs = listOf(
            PracticeNumericKeySpec(cid = "Q6-Ca", label = "Part (a)", expected = "-0.26", absTolerance = "0.015"),
            PracticeNumericKeySpec(cid = "Q6-Cd", label = "Part (d)", expected = "0.00", absTolerance = "0.015"),
        )
        val goodAnswer = "(a) eps_arc = -0.26. (d) DeltaQ = 0 so eps = 0.00."
        val goodFacts = PracticeServerNumericChecker.evaluate(specs, goodAnswer)
        assertTrue(goodFacts.all { it.correct })

        val badAnswer = "(a) eps_arc = -0.52. (d) eps = 2.00."
        val badFacts = PracticeServerNumericChecker.evaluate(specs, badAnswer)
        assertTrue(badFacts.none { it.correct })

        // Even if model tries to give FULL on wrong numeric, ServerNumericFact downgrades it!
        val transport = object : BatchAiTransport {
            override suspend fun generateSection(systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int): BatchTransportReply {
                assertTrue("Authoritative ServerNumericFact" in userPrompt || "Deterministic Numeric Check" in userPrompt)
                return if (systemPrompt == PracticeGradeAndDiagnoseService.GRADER_SYSTEM) {
                    stubReply(
                        """{"items":[{"cid":"Q6-Ca","verdict":"FULL","awardedMarks":3.0,"studentQuote":"eps_arc = -0.52","feedbackNoteZh":"数值计算有误。","pdfPage":51,"quoteAnchor":"arc elasticity"}]}""",
                    )
                } else {
                    stubReply("""{"isEquivalentValid":false,"predicted":"ARITHMETIC_OR_ALGEBRA","studentErrorSpan":"-0.52","diagnosisZh":"代入公式正确，但数值算错。"}""")
                }
            }
        }
        val svc = PracticeGradeAndDiagnoseService(transport)
        val resp = svc.process(
            PracticeGradeAndDiagnoseRequest(
                bookId = "book-q6",
                questionStem = "Compute arc elasticity.",
                studentAnswer = "(a) eps_arc = -0.52.",
                rubricCriteria = listOf(
                    PracticeRubricCriterionInput(
                        cid = "Q6-Ca",
                        slotLabel = "Part (a)",
                        maxMarks = 3.0,
                        pdfPage = 51,
                        quoteAnchor = "arc elasticity",
                        numericKey = PracticeNumericKeySpec("Q6-Ca", "Part (a)", "-0.26"),
                    ),
                ),
                sourcePages = listOf(PracticeSourcePageInput(51, "The arc elasticity measures percentage change over midpoint.")),
                generateVariant = false,
            ),
        )
        val item = resp.rubricItems.single()
        assertEquals(PracticeRubricVerdict.PARTIAL, item.verdict)
        assertTrue(item.awardedMarks <= 1.5)
    }

    @Test
    fun haikuPinnedBridgeTransportExplicitlyPinsHaikuAnd8192MaxTokens() = runBlocking {
        var seenModel: String? = null
        var seenMaxTokens: Int? = null
        var seenTask: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            val raw = ex.requestBody.readBytes().decodeToString()
            val body = json.parseToJsonElement(raw).jsonObject
            seenModel = body["model"]?.jsonPrimitive?.contentOrNull
            seenMaxTokens = body["max_tokens"]?.jsonPrimitive?.intOrNull
            seenTask = ex.requestHeaders.getFirst("X-Coach-Task")
            val reply = buildJsonObject {
                put("model", "claude-haiku-5-5")
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("message", buildJsonObject { put("content", """{"isEquivalentValid":true,"predicted":"NONE","studentErrorSpan":"","diagnosisZh":"正确。"}""") })
                    })
                })
                put("x_coach", buildJsonObject {
                    put("status", "complete")
                    put("finish_reason", "stop")
                    put("served_by", "claude-haiku-5-5")
                    put("fallback", false)
                    put("physical_calls", 1)
                })
            }.toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, reply.size.toLong())
            ex.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val bridge = BridgeClient(BridgeConfig(baseUrl = "http://127.0.0.1:${server.address.port}", testTraffic = true))
            val transport = HaikuPinnedBridgeTransport(bridge, defaultMaxTokens = PracticeGradeLimits.BATCH_MAX_TOKENS, backgroundBatch = false)
            val reply = transport.generateSection(
                systemPrompt = "sys",
                userPrompt = "usr",
                task = PracticeGradeLimits.TASK_DIAGNOSE,
                batchKey = "k1",
                maxTokens = 2_048,
            )
            assertEquals("claude-haiku-5-5", seenModel)
            assertEquals(8_192, seenMaxTokens)
            assertEquals("practice_diagnose", seenTask)
            assertEquals("claude-haiku-5-5", reply.model)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun ktorPracticeGradeRouteEndToEnd() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val dir = Files.createTempDirectory("practice-route-test-")
        val store = LedgerStore(dir)
        val token = "synthetic-private-token-32-characters"
        store.enroll("dev-1", token)
        val actor = LedgerActor(this, store)
        val transport = object : BatchAiTransport {
            override suspend fun generateSection(systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int): BatchTransportReply =
                when (systemPrompt) {
                    PracticeGradeAndDiagnoseService.GRADER_SYSTEM -> stubReply(
                        """{"items":[{"cid":"R1","verdict":"FULL","awardedMarks":2.0,"studentQuote":"MSB = MPB + MEB","feedbackNoteZh":"公式正确，方向准确。","pdfPage":461,"quoteAnchor":"marginal social benefit"}]}""",
                    )
                    else -> stubReply(
                        """{"isEquivalentValid":true,"predicted":"NONE","studentErrorSpan":"","diagnosisZh":"等价正确。"}""",
                    )
                }
        }
        val service = PracticeGradeAndDiagnoseService(transport, BookTokenLedger(dir.resolve("ledger")))
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, kotlinx.coroutines.flow.MutableSharedFlow(), adminDevice = "dev-1")
            practiceGradeRoutes(actor, service = service)
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val http = HttpClient.newHttpClient()
        try {
            val okPayload = buildJsonObject {
                put("bookId", "book-ch20")
                put("questionStem", "Write MSB formula for vaccines.")
                put("studentAnswer", "For positive externalities, MSB = MPB + MEB.")
                put("rubricCriteria", buildJsonArray {
                    add(buildJsonObject {
                        put("cid", "R1")
                        put("slotLabel", "MSB formula")
                        put("maxMarks", 2.0)
                        put("pdfPage", 461)
                        put("quoteAnchor", "marginal social benefit")
                    })
                })
                put("sourcePages", buildJsonArray {
                    add(buildJsonObject {
                        put("pdfPage", 461)
                        put("text", "The marginal social benefit equals marginal private benefit plus marginal external benefit.")
                    })
                })
            }
            val okReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v1/library/practice/grade-and-diagnose"))
                .header("Authorization", "Bearer $token")
                .header("X-Coach-Device", "dev-1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(okPayload.toString()))
                .build()
            val resp = http.send(okReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, resp.statusCode())
            val obj = json.parseToJsonElement(resp.body()).jsonObject
            assertEquals(2.0, obj["totalAwarded"]?.jsonPrimitive?.doubleOrNull)
            assertEquals(0, obj["rewrittenParagraphsEmitted"]?.jsonPrimitive?.intOrNull)
            assertEquals(0, obj["unverifiedPositivePassCount"]?.jsonPrimitive?.intOrNull)
            assertFalse("rewrite" in obj.keys)

            val leakedPayload = buildJsonObject {
                put("bookId", "book-ch20")
                put("questionStem", "From 01-Dec2024真题详解 answer this")
                put("studentAnswer", "test")
            }
            val badReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v1/library/practice/grade-and-diagnose"))
                .header("Authorization", "Bearer $token")
                .header("X-Coach-Device", "dev-1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(leakedPayload.toString()))
                .build()
            val badResp = http.send(badReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(400, badResp.statusCode())
        } finally {
            server.stop(100, 500)
            actor.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun jsonParserRecoversLatexEscapesAndRetriesMalformedGraderReply() = runBlocking {
        var jsonRepairInvoked = false
        val transport = object : BatchAiTransport {
            override suspend fun generateSection(systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int): BatchTransportReply =
                when (systemPrompt) {
                    PracticeGradeAndDiagnoseService.GRADER_SYSTEM -> stubReply("Here is my grading: { malformed json without closing")
                    PracticeGradeAndDiagnoseService.JSON_REPAIR_SYSTEM -> {
                        jsonRepairInvoked = true
                        // Deliberately includes raw LaTeX `\Omega` and trailing comma to test local recovery too!
                        stubReply(
                            """
                            ```json
                            {"items":[{"cid":"Q5-C1","verdict":"FULL","awardedMarks":1.0,"studentQuote":"zero-profit line ZP_H","feedbackNoteZh":"包含\Omega_1与零利润线斜率，见第197页。","pdfPage":197,"quoteAnchor":"zero-profit line",},]}
                            ```
                            """.trimIndent(),
                        )
                    }
                    else -> stubReply("""{"isEquivalentValid":true,"predicted":"NONE","studentErrorSpan":"","diagnosisZh":"正确。"}""")
                }
        }
        val svc = PracticeGradeAndDiagnoseService(transport)
        val resp = svc.process(
            PracticeGradeAndDiagnoseRequest(
                mode = "GRADE_ONLY",
                bookId = "book-q5",
                questionStem = "Explain separating equilibrium.",
                studentAnswer = "The zero-profit line ZP_H is flatter for high-risk individuals.",
                rubricCriteria = listOf(
                    PracticeRubricCriterionInput("Q5-C1", "ZP line", 1.0, 197, "zero-profit line"),
                ),
                sourcePages = listOf(
                    PracticeSourcePageInput(197, "The zero-profit line for high-risk individuals is flatter than for low-risk individuals."),
                ),
                generateVariant = false,
            ),
        )
        assertTrue(jsonRepairInvoked)
        val item = resp.rubricItems.single()
        assertEquals(PracticeRubricVerdict.FULL, item.verdict)
        assertEquals(1.0, item.awardedMarks, 1e-6)
        assertTrue(item.studentQuoteVerified)
        assertTrue(item.anchorVerified)
    }
}
