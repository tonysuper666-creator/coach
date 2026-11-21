package com.coach.server

import io.ktor.server.application.install
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoursePipelineAndGatesTest {

    @Test
    fun enforcesServerQualityGatesOneThroughFour() {
        // Gate 1: past-exam blocking & note spoiler regex scrubbing
        assertTrue(CourseQualityGates.isPastExamTitle("Midterm-2023-Exam-Paper.pdf"))
        assertFalse(CourseQualityGates.isPastExamTitle("Course-Handbook-Syllabus.pdf"))
        val (cleanNote, redactions) = CourseQualityGates.sanitizeUserNote(
            "2024 年 真题 问了 insurance deductible formula.\nFocus on risk premium intuition.",
        )
        assertEquals(1, redactions)
        assertTrue(cleanNote.contains("Focus on risk premium intuition"))
        assertFalse(cleanNote.contains("2024 年 真题"))

        // Gate 2: UTF-16 anchor + [mm:ss] transcript offset verification
        val slideText = "Lecture 6: Pooled contracts unravel when healthy risks opt out."
        val tsText = "[08:45] Cutler & Reber (1998) tracks tiered health premium switching."
        val slideBlk = CourseMaterialTextBlock("b1:p1:0", "b1", "Week6 Slides", "LECTURE_SLIDES", 1, 1, 0, slideText)
        val tsBlk = CourseMaterialTextBlock("b2:p1:0", "b2", "Week6 Transcript", "TRANSCRIPT", 1, 1, 0, tsText)
        val byBookPage = mapOf(("b1" to 1) to listOf(slideBlk), ("b2" to 1) to listOf(tsBlk))
        val byBook = mapOf("b1" to listOf(slideBlk), "b2" to listOf(tsBlk))

        val citSlide = CourseQualityGates.verifyCitation(
            buildJsonObject {
                put("bookId", "b1")
                put("page", 1)
                put("quote", "Pooled contracts unravel")
            },
            byBookPage,
            byBook,
        )
        assertNotNull(citSlide)
        assertEquals(slideText.indexOf("Pooled contracts unravel"), citSlide.startUtf16)

        val citTranscript = CourseQualityGates.verifyCitation(
            buildJsonObject {
                put("bookId", "b2")
                put("page", 1)
                put("timestampLabel", "08:45")
                put("quote", "wrong quote fallback resolves timestamp")
            },
            byBookPage,
            byBook,
        )
        assertNotNull(citTranscript)
        assertEquals("08:45", citTranscript.timestampLabel)

        val bogus = CourseQualityGates.verifyCitation(
            buildJsonObject {
                put("bookId", "b1")
                put("page", 1)
                put("quote", "Nonexistent hallucinated claim not in text")
            },
            byBookPage,
            byBook,
        )
        assertNull(bogus)

        // Gate 3: English 8-gram and Chinese 12-gram zero overlap
        assertFalse(
            CourseQualityGates.auditZeroOverlap(
                listOf("Question asks whether Pooled contracts unravel when healthy risks opt out of insurance."),
                listOf(slideText),
            ).passed,
        )
        assertTrue(
            CourseQualityGates.auditZeroOverlap(
                listOf("Compare how uniform pricing triggers low-risk departure across competitive pools."),
                listOf(slideText),
            ).passed,
        )

        // Gate 4: BigDecimal 2-decimal math verification
        val good = CourseQualityGates.auditMathAssertions(
            listOf(
                CourseMathAssertion("0.5 * 16 + 0.5 * 36 - 1.99", BigDecimal("24.01")),
                CourseMathAssertion("0.3 * 100 + 0.7 * 24.16", BigDecimal("46.91")),
                CourseMathAssertion("250 * (1 - 5%)", BigDecimal("237.50")),
            ),
        )
        assertTrue(good.passed)
        val bad = CourseQualityGates.auditMathAssertions(
            listOf(CourseMathAssertion("250 * (1 - 5%)", BigDecimal("212.00"))),
        )
        assertFalse(bad.passed)
    }

    @Test
    fun alignmentPipelinePreservesLocksAndTopicSynthesisEnforcesAllGatesWithCaching() = runBlocking {
        val dir = Files.createTempDirectory("coach-course-server-pipe-")
        try {
            val ledger = BookTokenLedger(dir.resolve("ledger"))
            val cache = CourseTopicPackCache(dir.resolve("topic-cache"))

            val alignTransport = object : CourseAlignTransport {
                override suspend fun alignCourse(
                    systemPrompt: String,
                    userPrompt: String,
                    batchKey: String,
                    maxTokens: Int,
                ): CourseAlignReply {
                    assertFalse(userPrompt.contains("2022 Past Exam"), "Gate 1 must drop spoiler notes before prompt")
                    return CourseAlignReply(
                        text = buildJsonObject {
                            put("examProfile", buildJsonObject {
                                put("examDate", "2026-12-18")
                                put("examDateTentative", true)
                                put("examFormat", "MCQ + 300词论述")
                                put("examFormatTentative", true)
                            })
                            put("materialUpdates", buildJsonArray {})
                            put("topics", buildJsonArray {
                                add(buildJsonObject {
                                    put("weekLabel", "第 6 周")
                                    put("weekNum", 6)
                                    put("title", "Adverse Selection Equilibrium")
                                    put("examStatus", "EXAMINABLE")
                                })
                            })
                        }.toString(),
                        routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                        model = "claude-sonnet-5-5",
                        inputTokens = 500,
                        outputTokens = 180,
                        elapsedMs = 45,
                    )
                }
            }

            val alignPipe = CourseAlignmentPipeline(alignTransport, ledger)
            val aligned = alignPipe.align(
                buildJsonObject {
                    put("courseId", "course-econ-1")
                    put("courseTitle", "Applied Microeconomics")
                    put("materials", buildJsonArray {
                        add(buildJsonObject {
                            put("bookId", "past-1")
                            put("title", "Past-Exam-Paper-2022.pdf")
                            put("format", "pdf")
                            put("role", "USER_NOTES")
                            put("previewText", "2022 Past Exam question verbatim")
                        })
                        add(buildJsonObject {
                            put("bookId", "slides-6")
                            put("title", "Week6-Slides.pdf")
                            put("format", "pdf")
                            put("role", "LECTURE_SLIDES")
                            put("weekNum", 6)
                            put("userLocked", true)
                            put("previewText", "Adverse selection lecture notes")
                        })
                    })
                },
            )
            assertEquals(1, aligned["topics"]?.jsonArray?.size)

            val calls = AtomicInteger(0)
            val topicTransport = object : BatchAiTransport {
                override suspend fun generateSection(
                    systemPrompt: String,
                    userPrompt: String,
                    task: String,
                    batchKey: String,
                    maxTokens: Int,
                ): BatchTransportReply {
                    calls.incrementAndGet()
                    val text = when (task) {
                        "course_topic_map" -> buildJsonObject {
                            put("claims", buildJsonArray {
                                add(buildJsonObject {
                                    put("title", "Unraveling")
                                    put("detail", "Pooling contract breakdown")
                                    put("authorityRank", 1)
                                })
                            })
                            put("citations", buildJsonArray {
                                add(buildJsonObject {
                                    put("bookId", "b-slide")
                                    put("page", 1)
                                    put("quote", "Uniform premiums push low risk households out of pool")
                                })
                                add(buildJsonObject {
                                    put("bookId", "b-slide")
                                    put("page", 1)
                                    put("quote", "Bogus non-existent citation dropped by Gate 2")
                                })
                            })
                        }.toString()
                        "course_topic_reduce" -> buildJsonObject {
                            put("summary", "Synthesized overview prioritizing classroom slides.")
                            put("authorityNotice", "L1 slides take priority over L2 textbook examples.")
                            put("examFocus", "Short essay evaluation (tentative)")
                            put("keyPoints", buildJsonArray {
                                add(buildJsonObject {
                                    put("title", "Adverse Selection Mechanism")
                                    put("explanation", "Asymmetric private risk triggers exit spirals.")
                                    put("citations", buildJsonArray {
                                        add(buildJsonObject {
                                            put("bookId", "b-slide")
                                            put("page", 1)
                                            put("quote", "Uniform premiums push low risk households out of pool")
                                        })
                                    })
                                })
                            })
                            put("discrepancies", buildJsonArray {
                                add(buildJsonObject {
                                    put("point", "Scope of empirical case")
                                    put("authoritativeView", "Lecture covers Cutler & Reber (1998) exclusively.")
                                    put("secondaryView", "Textbook lists five extra industry anecdotes.")
                                    put("advice", "Anchor essay argument on Cutler & Reber (1998).")
                                    put("citations", buildJsonArray {})
                                })
                            })
                            put("practicePrompt", buildJsonObject {
                                put("promptId", "p-w6")
                                put("kind", "ESSAY")
                                put("stem", "Contrast pooled and separating insurance contracts using original economic intuition.")
                                put("wordTarget", 300)
                            })
                            // Trigger Gate 4 arithmetic repair once (`250 - 5% = 237.50`, deliberately wrong first)
                            put("mathAssertions", buildJsonArray {
                                add(buildJsonObject {
                                    put("expression", "250 * (1 - 5%)")
                                    put("expected", "210.00")
                                })
                            })
                        }.toString()
                        else -> buildJsonObject {
                            put("mathAssertions", buildJsonArray {
                                add(buildJsonObject {
                                    put("expression", "250 * (1 - 5%)")
                                    put("expected", "237.50")
                                })
                            })
                        }.toString()
                    }
                    return BatchTransportReply(
                        text = text,
                        routeChannel = CompanionRouteChannel.CODEBUDDY_DEEPSEEK_FLASH,
                        model = "deepseek-v4.1-flash",
                        inputTokens = 400,
                        outputTokens = 160,
                        ttftMs = 20,
                        elapsedMs = 30,
                    )
                }
            }

            val topicPipe = CourseTopicSynthesisPipeline(topicTransport, ledger, cache)
            val request = buildJsonObject {
                put("courseId", "course-econ-1")
                put("topicId", "topic-w6")
                put("topicTitle", "Adverse Selection")
                put("sourcesHash", "hash-v1")
                put("examDateTentative", true)
                put("examFormatTentative", true)
                put("blocks", buildJsonArray {
                    add(buildJsonObject {
                        put("bookId", "b-slide")
                        put("bookTitle", "Week 6 Slides")
                        put("role", "LECTURE_SLIDES")
                        put("authorityRank", 1)
                        put("page", 1)
                        put("text", "Lecture note: Uniform premiums push low risk households out of pool quickly.")
                    })
                    add(buildJsonObject {
                        put("bookId", "b-note")
                        put("bookTitle", "Personal Notes")
                        put("role", "USER_NOTES")
                        put("authorityRank", 4)
                        put("page", 1)
                        put("text", "2023 真题 原题考了 pooling equilibrium.")
                    })
                })
            }

            val pack1 = topicPipe.generateTopicPack(request, cacheScope = "owner:test")
            val gates = pack1["gates"]?.jsonObject
            assertNotNull(gates)
            assertEquals(1, gates["gate1SpoilerRedactions"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, gates["gate2VerifiedCitations"]?.jsonPrimitive?.intOrNull)
            assertEquals(true, gates["gate3ZeroOverlapPassed"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(true, gates["gate4MathPassed"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(3, calls.get(), "Map + Reduce + Gate 4 Repair = 3 transport calls")

            // Repeat request with same hash must hit cache cleanly with ZERO extra transport calls
            val pack2 = topicPipe.generateTopicPack(request, cacheScope = "owner:test")
            assertEquals(pack1, pack2)
            assertEquals(3, calls.get(), "Repeated request must hit SQLite topic cache without extra transport call")

            // Vision service <=25p enforcement & SHA-256 deduplication
            val visionCalls = AtomicInteger(0)
            val visionService = CourseVisionService(
                directory = dir.resolve("vision"),
                transport = object : CourseVisionTransport {
                    override suspend fun recognizePage(pageNumber: Int, pngBase64: String, pageSha256: String): CourseVisionReply {
                        visionCalls.incrementAndGet()
                        return CourseVisionReply("Recognized handwritten derivation line $pageNumber")
                    }
                },
                tokenLedger = ledger,
            )
            val sha1 = "1".repeat(64)
            val vReq = buildJsonObject {
                put("bookId", "hand-1")
                put("totalPages", 2)
                put("pages", buildJsonArray {
                    add(buildJsonObject { put("pageNumber", 1); put("pageSha256", sha1); put("pngBase64", "AQID") })
                })
            }
            val vRes1 = visionService.recognizeBatch(vReq)
            assertEquals(0, vRes1["cacheHits"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, vRes1["billedCalls"]?.jsonPrimitive?.intOrNull)
            val vRes2 = visionService.recognizeBatch(vReq)
            assertEquals(1, vRes2["cacheHits"]?.jsonPrimitive?.intOrNull)
            assertEquals(0, vRes2["billedCalls"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, visionCalls.get(), "Second vision call on same pageSha256 must NOT re-bill")

            var rejected26 = false
            try {
                visionService.recognizeBatch(buildJsonObject {
                    put("bookId", "hand-26")
                    put("totalPages", 26)
                    put("pages", buildJsonArray {})
                })
            } catch (e: MockError) {
                rejected26 = true
                assertEquals(400, e.status.value)
            }
            assertTrue(rejected26, "Handwritten PDFs > 25 pages must fail with HTTP 400")

            // Ktor route smoke test for /v1/library/course/*
            val store = com.coach.store.LedgerStore(dir.resolve("core-ledger"))
            val actorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val actor = com.coach.core.LedgerActor(actorScope, store)
            val server = io.ktor.server.engine.embeddedServer(io.ktor.server.netty.Netty, host = "127.0.0.1", port = 0) {
                install(io.ktor.server.application.createApplicationPlugin("CourseTestAuth") {
                    onCall { call -> call.attributes.put(observedDevice, "owner-dev") }
                })
                libraryExplainRoutes(
                    actor = actor,
                    reader = null,
                    courseAlignPipeline = alignPipe,
                    courseTopicPipeline = topicPipe,
                    courseVisionService = visionService,
                )
            }.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().single().port
                val http = java.net.http.HttpClient.newHttpClient()
                val routeResp = http.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/v1/library/course/topic-pack"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(request.toString()))
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, routeResp.statusCode())
                val jsonBody = Json.parseToJsonElement(routeResp.body()).jsonObject
                assertEquals("topic-w6", jsonBody["topicId"]?.jsonPrimitive?.content)
            } finally {
                server.stop(100, 100)
                actorScope.cancel()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
