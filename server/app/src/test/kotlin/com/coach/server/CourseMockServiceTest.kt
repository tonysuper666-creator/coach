package com.coach.server

import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CourseMockServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun exactRationalEvaluatorVerifiesArithmeticWithoutFloatingPointDrift() {
        val exactThird = ExactRationalEvaluator.verify("(30 - 10) / (1 / 3)", "60.00", decimalPlaces = 2)
        assertTrue(exactThird.validExpression)
        assertEquals("60", exactThird.exactRational)
        assertEquals("60.00", exactThird.verifiedValue)
        assertTrue(exactThird.matchesClaimed)

        val binaryDecimalTrap = ExactRationalEvaluator.verify("(0.1 + 0.2) * 100", "30.00", decimalPlaces = 2)
        assertTrue(binaryDecimalTrap.validExpression)
        assertEquals("30", binaryDecimalTrap.exactRational)
        assertEquals("30.00", binaryDecimalTrap.verifiedValue)
        assertTrue(binaryDecimalTrap.matchesClaimed)

        val driftedClaim = ExactRationalEvaluator.verify("100 / 3", "35.00", decimalPlaces = 2)
        assertTrue(driftedClaim.validExpression)
        assertEquals("100/3", driftedClaim.exactRational)
        assertEquals("33.33", driftedClaim.verifiedValue)
        assertFalse(driftedClaim.matchesClaimed)

        val symbolic = ExactRationalEvaluator.verify("2 * x + 10", "20", decimalPlaces = 2)
        assertFalse(symbolic.validExpression)
    }

    @Test
    fun leakageGuardRejectsPastPapersAndEnforcesZeroOverlap() {
        val leakedReq = CourseMockRequestParser.parse(
            buildJsonObject {
                put("courseId", "course-1")
                put("courseTitle", "Health Economics")
                put("examinableTopics", buildJsonArray {
                    add(buildJsonObject {
                        put("topicId", "t1")
                        put("title", "Moral Hazard")
                        put("sourcePages", buildJsonArray {
                            add(buildJsonObject {
                                put("bookId", "b1")
                                put("bookTitle", "01-Dec2024真题详解")
                                put("pdfPage", 1)
                                put("text", "Moral hazard coinsurance problem.")
                            })
                        })
                    })
                })
            },
        )
        assertFailsWith<MockError> {
            CourseMockLeakageGuard.scanRequest(leakedReq)
        }

        val heldOut = listOf(
            "Consider a monopolist hospital facing linear inverse demand curve P equals one hundred minus two Q.",
            "在完全信息分离均衡中，高风险消费者购买全额保险。",
        )
        val overlapping = CourseMockLeakageGuard.auditOverlap(
            listOf("Suppose a monopolist hospital facing linear inverse demand curve P equals one hundred minus Q."),
            heldOut,
        )
        assertFalse(overlapping.passed)
        assertTrue(overlapping.en8GramOverlaps > 0)

        val overlappingZh = CourseMockLeakageGuard.auditOverlap(
            listOf("根据模型推导，高风险消费者购买全额保险的均衡解如下。"),
            heldOut,
        )
        assertFalse(overlappingZh.passed)
        assertTrue(overlappingZh.zh12CharOverlaps > 0)

        val clean = CourseMockLeakageGuard.auditOverlap(
            listOf("Evaluate how a 25% subsidy on preventive clinics shifts out-of-pocket expenditure and outpatient visits."),
            heldOut,
        )
        assertTrue(clean.passed)
        assertEquals(0, clean.en8GramOverlaps)
        assertEquals(0, clean.zh12CharOverlaps)
    }

    @Test
    fun courseMockServiceCallsSonnetOnceExcludesExcludedTopicsAndRecordsLedger() = runBlocking {
        val dir = Files.createTempDirectory("course-mock-svc-test-")
        try {
            val ledger = BookTokenLedger(dir.resolve("ledger"))
            var plannerCalls = 0
            val plannerPrompts = mutableListOf<String>()
            val plannerTransport = object : MockBlueprintPlannerTransport {
                override suspend fun planBlueprint(
                    systemPrompt: String,
                    userPrompt: String,
                    batchKey: String,
                    maxTokens: Int,
                ): MockPlannerReply {
                    plannerCalls++
                    plannerPrompts += userPrompt
                    return MockPlannerReply(
                        text = buildJsonObject {
                            put("sections", buildJsonArray {
                                add(buildJsonObject {
                                    put("title", "Part I · Short Essays")
                                    put("instructions", "写出定义与政策适用条件。")
                                    put("slots", buildJsonArray {
                                        add(buildJsonObject {
                                            put("slotId", "E1")
                                            put("itemType", "essay")
                                            put("marks", 20.0)
                                            put("topicId", "t-w2")
                                            put("focusConcept", "Grossman depreciation vs age")
                                            put("targetErrorTag", "GRAPH_SHIFT_OR_ROTATION")
                                            put("requiresMathSpec", false)
                                        })
                                    })
                                })
                                add(buildJsonObject {
                                    put("title", "Part II · Analytical Problems")
                                    put("instructions", "分步列出算式并保留两位小数。")
                                    put("slots", buildJsonArray {
                                        add(buildJsonObject {
                                            put("slotId", "A1")
                                            put("itemType", "multi_part_calc")
                                            put("marks", 30.0)
                                            put("topicId", "t-w5")
                                            put("focusConcept", "Actuarially fair premium and utility")
                                            put("targetErrorTag", "ARITHMETIC_OR_ALGEBRA")
                                            put("requiresMathSpec", true)
                                        })
                                    })
                                })
                            })
                        }.toString(),
                    )
                }
            }

            val itemTransport = object : BatchAiTransport {
                override suspend fun generateSection(
                    systemPrompt: String,
                    userPrompt: String,
                    task: String,
                    batchKey: String,
                    maxTokens: Int,
                ): BatchTransportReply {
                    assertEquals(CourseMockLimits.TASK_ITEM, task)
                    val jsonText = if ("Slot: E1" in userPrompt) {
                        buildJsonObject {
                            put("id", "E1")
                            put("type", "essay")
                            put("stem", "Explain why higher wage rate raises optimal health capital in the Grossman model.")
                            put("marks", 20.0)
                            put("source", buildJsonObject {
                                put("docId", "b-slides-w2")
                                put("pdfPage", 12)
                                put("quote", "higher wage increases monetary return to healthy time")
                            })
                            put("rubricCriteria", buildJsonArray {
                                add(buildJsonObject {
                                    put("cid", "E1-C1")
                                    put("slotLabel", "健康时间货币回报提高机制")
                                    put("maxMarks", 20.0)
                                    put("sourceBookId", "b-slides-w2")
                                    put("pdfPage", 12)
                                    put("quoteAnchor", "higher wage increases monetary return to healthy time")
                                })
                            })
                        }.toString()
                    } else {
                        buildJsonObject {
                            put("id", "A1")
                            put("type", "multi_part_calc")
                            put("stem", "An individual with wealth W=100 faces loss L=64 with probability pi=0.25 under full fair coverage.")
                            put("marks", 30.0)
                            put("source", buildJsonObject {
                                put("docId", "b-textbook-w5")
                                put("pdfPage", 188)
                                put("quote", "actuarially fair premium equals expected loss")
                            })
                            put("subQuestions", buildJsonArray {
                                add(buildJsonObject {
                                    put("subId", "a")
                                    put("label", "(a)")
                                    put("prompt", "Compute the actuarially fair full-insurance premium.")
                                    put("kind", "calculation")
                                    put("marks", 15.0)
                                    put("mathSpec", buildJsonObject {
                                        put("expression", "0.25 * 64")
                                        // Intentionally put wrong claimed value to verify ExactRationalEvaluator overrides it!
                                        put("expected", "17.50")
                                        put("decimalPlaces", 2)
                                        put("absTolerance", "0.015")
                                    })
                                })
                                add(buildJsonObject {
                                    put("subId", "b")
                                    put("label", "(b)")
                                    put("prompt", "Compare net wealth with full fair insurance against expected uninsured wealth.")
                                    put("kind", "graph_or_explanation")
                                    put("marks", 15.0)
                                })
                            })
                            put("rubricCriteria", buildJsonArray {
                                add(buildJsonObject {
                                    put("cid", "A1-a")
                                    put("subId", "a")
                                    put("slotLabel", "公平保费等于期望损失计算")
                                    put("maxMarks", 15.0)
                                    put("sourceBookId", "b-textbook-w5")
                                    put("pdfPage", 188)
                                    put("quoteAnchor", "actuarially fair premium equals expected loss")
                                })
                                add(buildJsonObject {
                                    put("cid", "A1-b")
                                    put("subId", "b")
                                    put("slotLabel", "全额投保后各状态净财富无风险")
                                    put("maxMarks", 15.0)
                                    put("sourceBookId", "b-textbook-w5")
                                    put("pdfPage", 188)
                                    put("quoteAnchor", "actuarially fair premium equals expected loss")
                                })
                            })
                        }.toString()
                    }
                    return BatchTransportReply(
                        text = jsonText,
                        routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                        model = CourseMockLimits.PINNED_ITEM_MODEL,
                        inputTokens = 650,
                        outputTokens = 320,
                        ttftMs = 95,
                        elapsedMs = 420,
                    )
                }
            }

            val service = CourseMockService(plannerTransport, itemTransport, ledger)
            val request = buildJsonObject {
                put("courseId", "ecnm-course")
                put("courseTitle", "ECNM10119 Health Economics")
                put("examProfile", buildJsonObject {
                    put("examDate", "2026-12-12")
                    put("examFormat", "Section A Short Essays + Section B Analytical")
                    put("examFormatTentative", true)
                    put("durationMinutes", 120)
                    put("closedBook", true)
                    put("calculatorAllowed", "非编程计算器")
                })
                put("excludedTopicTitles", buildJsonArray { add(JsonPrimitive("Week 9 Guest Lecture Excluded")) })
                put("referenceExclusionTexts", buildJsonArray {
                    add(JsonPrimitive("Past paper unique phrase that must never appear verbatim in newly authored questions."))
                })
                put("examinableTopics", buildJsonArray {
                    add(buildJsonObject {
                        put("topicId", "t-w2")
                        put("weekLabel", "Week 2")
                        put("title", "Grossman Health Capital")
                        put("examStatus", "EXAMINABLE")
                        put("examWeight", 2.0)
                        put("pMastery", 0.40)
                        put("dominantErrorTags", buildJsonArray { add(JsonPrimitive("GRAPH_SHIFT_OR_ROTATION")) })
                        put("sourcePages", buildJsonArray {
                            add(buildJsonObject {
                                put("bookId", "b-slides-w2")
                                put("bookTitle", "Week2-Slides")
                                put("role", "LECTURE_SLIDES")
                                put("authorityRank", 1)
                                put("pdfPage", 12)
                                put("text", "In Grossman model, higher wage increases monetary return to healthy time and raises optimal health stock.")
                            })
                        })
                    })
                    add(buildJsonObject {
                        put("topicId", "t-w5")
                        put("weekLabel", "Week 5")
                        put("title", "Adverse Selection & Insurance")
                        put("examStatus", "EXAMINABLE")
                        put("examWeight", 3.0)
                        put("pMastery", 0.25)
                        put("dominantErrorTags", buildJsonArray { add(JsonPrimitive("ARITHMETIC_OR_ALGEBRA")) })
                        put("sourcePages", buildJsonArray {
                            add(buildJsonObject {
                                put("bookId", "b-textbook-w5")
                                put("bookTitle", "Bhattacharya-Textbook")
                                put("role", "CORE_TEXTBOOK")
                                put("authorityRank", 2)
                                put("pdfPage", 188)
                                put("text", "Under competitive equilibrium, actuarially fair premium equals expected loss pi times L.")
                            })
                        })
                    })
                    add(buildJsonObject {
                        put("topicId", "t-w9-ex")
                        put("weekLabel", "Week 9")
                        put("title", "Excluded Special Seminar")
                        put("examStatus", "EXCLUDED")
                        put("examWeight", 5.0)
                        put("sourcePages", buildJsonArray {
                            add(buildJsonObject {
                                put("bookId", "b-slides-w9")
                                put("bookTitle", "Week9-Slides")
                                put("role", "LECTURE_SLIDES")
                                put("authorityRank", 1)
                                put("pdfPage", 1)
                                put("text", "SECRET_EXCLUDED_TOPIC_MARKER")
                            })
                        })
                    })
                })
            }

            val paper = service.generate(request)
            assertEquals(1, plannerCalls)
            assertFalse("t-w9-ex" in plannerPrompts.single())
            assertTrue(paper["isTentativeFormat"]?.jsonPrimitive?.content == "true")
            assertEquals("course:ecnm-course", paper["bookId"]?.jsonPrimitive?.content)

            val audit = paper["audit"]!!.jsonObject
            assertEquals(1, audit["plannerSonnetCalls"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, audit["calcSubQuestionsChecked"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, audit["calcSubQuestionsAccurate"]?.jsonPrimitive?.intOrNull)
            assertEquals(0, audit["en8GramOverlaps"]?.jsonPrimitive?.intOrNull)
            assertEquals(0, audit["zh12CharOverlaps"]?.jsonPrimitive?.intOrNull)

            // Exact rational evaluator overrides drifted "17.50" with exact "16.00" (16/1)
            val sec2Items = paper["sections"]!!.jsonArray[1].jsonObject["items"]!!.jsonArray
            val a1 = sec2Items.single().jsonObject
            val subA = a1["subQuestions"]!!.jsonArray[0].jsonObject
            val mathSpec = subA["mathSpec"]!!.jsonObject
            assertEquals("16.00", mathSpec["expected"]?.jsonPrimitive?.content)
            assertEquals("16", mathSpec["exactRational"]?.jsonPrimitive?.content)
            val critA = a1["rubricCriteria"]!!.jsonArray[0].jsonObject
            assertEquals("16.00", critA["numericKey"]!!.jsonObject["expected"]?.jsonPrimitive?.content)
            assertTrue(critA["sourceExcerpt"]!!.jsonPrimitive.content.contains("actuarially fair premium equals expected loss"))

            val ledgerSummary = ledger.bookSummary("ecnm-course")
            assertEquals(3, ledgerSummary.calls)
            assertTrue(ledgerSummary.totalCostMicrodollars > 0L)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun ktorCourseMockRoutesEnforcesAuthLeakageAndReturnsPaper() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val dir = Files.createTempDirectory("course-mock-route-test-")
        val store = LedgerStore(dir)
        val token = "course-mock-route-token-32-chars"
        store.enroll("dev-mock-1", token)
        val actor = LedgerActor(this, store)
        val planner = object : MockBlueprintPlannerTransport {
            override suspend fun planBlueprint(systemPrompt: String, userPrompt: String, batchKey: String, maxTokens: Int) =
                MockPlannerReply("""{"sections":[{"title":"Part I","instructions":"Write clearly.","slots":[{"slotId":"Q1","itemType":"essay","marks":20,"topicId":"t1","focusConcept":"Elasticity"}]}]}""")
        }
        val items = object : BatchAiTransport {
            override suspend fun generateSection(systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int) =
                BatchTransportReply(
                    text = """{"id":"Q1","type":"essay","stem":"Explain arc price elasticity for medical care.","marks":20,"source":{"docId":"b1","pdfPage":4,"quote":"arc price elasticity measures responsiveness"},"rubricCriteria":[{"cid":"Q1-C1","slotLabel":"弹性定义","maxMarks":20,"sourceBookId":"b1","pdfPage":4,"quoteAnchor":"arc price elasticity measures responsiveness"}]}""",
                    routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                    model = CourseMockLimits.PINNED_ITEM_MODEL,
                    inputTokens = 300,
                    outputTokens = 150,
                    ttftMs = 90,
                    elapsedMs = 310,
                )
        }
        val svc = CourseMockService(planner, items, BookTokenLedger(dir.resolve("ledger")))
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(), adminDevice = "dev-mock-1")
            courseMockRoutes(actor, service = svc)
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val http = HttpClient.newHttpClient()
        try {
            val validPayload = buildJsonObject {
                put("courseId", "cid-1")
                put("courseTitle", "ECNM10119")
                put("examinableTopics", buildJsonArray {
                    add(buildJsonObject {
                        put("topicId", "t1")
                        put("weekLabel", "Week 1")
                        put("title", "Demand Elasticity")
                        put("sourcePages", buildJsonArray {
                            add(buildJsonObject {
                                put("bookId", "b1")
                                put("bookTitle", "Week1-Slides")
                                put("pdfPage", 4)
                                put("text", "In health demand, arc price elasticity measures responsiveness across two price points.")
                            })
                        })
                    })
                })
            }

            // Missing auth -> 401
            val unauthResp = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v1/library/course/mock/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(validPayload.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, unauthResp.statusCode())

            // Leaked past-paper title -> 409
            val leakPayload = buildJsonObject {
                put("courseId", "cid-1")
                put("courseTitle", "ECNM10119")
                put("examinableTopics", buildJsonArray {
                    add(buildJsonObject {
                        put("topicId", "t1")
                        put("title", "Topic 1")
                        put("sourcePages", buildJsonArray {
                            add(buildJsonObject {
                                put("bookId", "b1")
                                put("bookTitle", "01-Dec2024真题详解")
                                put("pdfPage", 1)
                                put("text", "Past paper question.")
                            })
                        })
                    })
                })
            }
            val leakResp = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v1/library/course/mock/generate"))
                    .header("Authorization", "Bearer $token")
                    .header("X-Coach-Device", "dev-mock-1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(leakPayload.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(409, leakResp.statusCode())

            // Valid authenticated generation -> 200
            val okResp = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v1/library/course/mock/generate"))
                    .header("Authorization", "Bearer $token")
                    .header("X-Coach-Device", "dev-mock-1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(validPayload.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, okResp.statusCode())
            val obj = json.parseToJsonElement(okResp.body()).jsonObject
            assertEquals("course:cid-1", obj["bookId"]?.jsonPrimitive?.content)
            assertEquals("cid-1", obj["courseId"]?.jsonPrimitive?.content)
        } finally {
            server.stop(100, 500)
            actor.close()
            dir.toFile().deleteRecursively()
        }
    }
}
