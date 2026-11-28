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
                    assertFalse(userPrompt.contains("synthetic-held-out-stem"))
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
                    put("textbookChapters", buildJsonArray {
                        add(buildJsonObject { put("bookId", "held-out"); put("title", "synthetic-held-out-stem") })
                    })
                    put("materials", buildJsonArray {
                        add(buildJsonObject {
                            put("bookId", "held-out")
                            put("title", "Untitled.pdf")
                            put("role", "PAST_EXAM")
                            put("previewText", "synthetic-held-out-stem")
                        })
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

    @Test
    fun reviewRegressionsGate1Year2026Gate3NestedFieldsGate4EmptyAssertions() = runBlocking {
        val dir = Files.createTempDirectory("coach-review-regressions-")
        try {
            // Regression 1: 2026+ notes & "期末考过" phrasing must trigger Gate 1 scrubbing on server without false-blocking benign exam schedule/revision headers
            assertTrue(CourseQualityGates.isPastExamTitle("2026期末试卷.pdf"))
            assertFalse(CourseQualityGates.isPastExamTitle("2026-Course-Handbook-Syllabus.pdf"))

            val note2026 = "2026年期末考过逆向选择免赔额大题。\n期末考过类似补贴退出题型。\nDec 2026 final exam asked about deductible design.\nExam Date: 2026-12-12 (tentative).\n期末复习重点：厘清混合均衡与分离均衡。"
            val (scrubbed2026, redactions2026) = CourseQualityGates.sanitizeUserNote(note2026)
            assertEquals(3, redactions2026)
            assertFalse(scrubbed2026.contains("2026年期末考过"))
            assertFalse(scrubbed2026.contains("期末考过类似"))
            assertFalse(scrubbed2026.contains("Dec 2026 final exam"))
            assertTrue(scrubbed2026.contains("Exam Date: 2026-12-12 (tentative)."))
            assertTrue(scrubbed2026.contains("期末复习重点：厘清混合均衡与分离均衡。"))

            // Alignment must also scrub 2026+ & 期末考过 notes before passing previewText to transport
            var capturedAlignUserPrompt = ""
            val alignPipe = CourseAlignmentPipeline(
                object : CourseAlignTransport {
                    override suspend fun alignCourse(
                        systemPrompt: String,
                        userPrompt: String,
                        batchKey: String,
                        maxTokens: Int,
                    ): CourseAlignReply {
                        capturedAlignUserPrompt = userPrompt
                        return CourseAlignReply(
                            text = """{"materialUpdates":[],"topics":[]}""",
                            routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                            model = "claude-sonnet-5-5",
                            inputTokens = 100,
                            outputTokens = 50,
                            elapsedMs = 10,
                        )
                    }
                },
            )
            alignPipe.align(
                buildJsonObject {
                    put("courseId", "c-reg")
                    put("courseTitle", "Reg Course")
                    put("materials", buildJsonArray {
                        add(buildJsonObject {
                            put("bookId", "note-2026")
                            put("title", "个人复习笔记")
                            put("format", "md")
                            put("role", "USER_NOTES")
                            put("previewText", note2026)
                        })
                    })
                },
            )
            assertFalse(capturedAlignUserPrompt.contains("2026年期末考过"))
            assertFalse(capturedAlignUserPrompt.contains("期末考过类似"))
            assertFalse(capturedAlignUserPrompt.contains("Dec 2026 final exam"))

            // Regression 2 & 3:
            // - Gate 3 must audit & scrub verbatim >=8-word English & >=12-char Chinese inside keyPoints & discrepancies
            // - Gate 4 must NEVER pass with checkedCount = 0 when mathAssertions is empty and no inline math exists
            val srcEn = "Adverse selection causes healthy buyers to exit pooled health insurance contracts prematurely."
            val srcZh = "当低风险群体认为混合保费高于自身预期赔付时就会率先退出保障池"
            val srcTexts = listOf("$srcEn $srcZh")

            val ledger = BookTokenLedger(dir.resolve("ledger"))
            val cache = CourseTopicPackCache(dir.resolve("cache"))
            val regTransport = object : BatchAiTransport {
                override suspend fun generateSection(
                    systemPrompt: String,
                    userPrompt: String,
                    task: String,
                    batchKey: String,
                    maxTokens: Int,
                ): BatchTransportReply {
                    assertFalse(userPrompt.contains("2026年期末考过"), "Gate 1 must scrub 2026 spoiler before map/reduce prompt")
                    val text = when (task) {
                        "course_topic_map" -> buildJsonObject {
                            put("claims", buildJsonArray {})
                            put("citations", buildJsonArray {
                                add(buildJsonObject {
                                    put("bookId", "s1")
                                    put("page", 1)
                                    put("quote", "Adverse selection causes healthy buyers to exit")
                                })
                            })
                        }.toString()
                        else -> buildJsonObject {
                            put("summary", "Original summary explaining risk selection.")
                            put("authorityNotice", "L1 slides first.")
                            put("examFocus", "Short essay evaluation.")
                            put("keyPoints", buildJsonArray {
                                add(buildJsonObject {
                                    put("title", "Verbatim EN copy: $srcEn")
                                    put("explanation", "讲义指出：$srcZh，导致保障池萎缩。")
                                    put("citations", buildJsonArray {
                                        add(buildJsonObject {
                                            put("bookId", "s1")
                                            put("page", 1)
                                            put("quote", "Adverse selection causes healthy buyers to exit")
                                        })
                                    })
                                })
                            })
                            put("discrepancies", buildJsonArray {
                                add(buildJsonObject {
                                    put("point", "机制对比")
                                    put("authoritativeView", "L1 emphasizes: $srcEn")
                                    put("secondaryView", "L2 describes: $srcZh。")
                                    put("advice", "请聚焦于机制比较而非复述 $srcEn")
                                    put("citations", buildJsonArray {})
                                })
                            })
                            put("practicePrompt", buildJsonObject {
                                put("promptId", "p-reg")
                                put("kind", "ESSAY")
                                put("stem", "Analyze in original prose how deductible variation supports screening.")
                                put("wordTarget", 300)
                            })
                            // Deliberately omit/empty mathAssertions
                            put("mathAssertions", buildJsonArray {})
                        }.toString()
                    }
                    return BatchTransportReply(
                        text = text,
                        routeChannel = CompanionRouteChannel.CODEBUDDY_DEEPSEEK_FLASH,
                        model = "deepseek-v4.1-flash",
                        inputTokens = 200,
                        outputTokens = 150,
                        ttftMs = 15,
                        elapsedMs = 25,
                    )
                }
            }

            val pipe = CourseTopicSynthesisPipeline(regTransport, ledger, cache)
            val pack = pipe.generateTopicPack(
                buildJsonObject {
                    put("courseId", "c-reg")
                    put("topicId", "t-reg-empty-math")
                    put("topicTitle", "Selection")
                    put("sourcesHash", "h-reg")
                    put("blocks", buildJsonArray {
                        add(buildJsonObject {
                            put("bookId", "s1")
                            put("bookTitle", "Slides")
                            put("role", "LECTURE_SLIDES")
                            put("authorityRank", 1)
                            put("page", 1)
                            put("text", "$srcEn $srcZh")
                        })
                        add(buildJsonObject {
                            put("bookId", "n1")
                            put("bookTitle", "Notes")
                            put("role", "USER_NOTES")
                            put("authorityRank", 4)
                            put("page", 1)
                            put("text", note2026)
                        })
                    })
                },
                cacheScope = "owner:reg",
            )

            // Gate 1 verified on 2026 note
            val gates = pack["gates"]!!.jsonObject
            assertEquals(3, gates["gate1SpoilerRedactions"]?.jsonPrimitive?.intOrNull)

            // Gate 3 verified across nested keyPoints & discrepancies
            val kps = pack["keyPoints"]!!.jsonArray
            val diffs = pack["discrepancies"]!!.jsonArray
            val nestedProse = buildList {
                add(pack["summary"]!!.jsonPrimitive.content)
                add(pack["practicePrompt"]!!.jsonObject["stem"]!!.jsonPrimitive.content)
                for (k in kps) {
                    add(k.jsonObject["title"]!!.jsonPrimitive.content)
                    add(k.jsonObject["explanation"]!!.jsonPrimitive.content)
                }
                for (d in diffs) {
                    add(d.jsonObject["point"]!!.jsonPrimitive.content)
                    add(d.jsonObject["authoritativeView"]!!.jsonPrimitive.content)
                    add(d.jsonObject["secondaryView"]!!.jsonPrimitive.content)
                    add(d.jsonObject["advice"]!!.jsonPrimitive.content)
                }
            }
            for (p in nestedProse) {
                assertFalse(p.contains(srcEn), "Verbatim English leak remained in nested field: $p")
                assertFalse(p.contains(srcZh), "Verbatim Chinese leak remained in nested field: $p")
            }
            assertTrue(CourseQualityGates.auditZeroOverlap(nestedProse, srcTexts).passed)
            assertEquals(true, gates["gate3ZeroOverlapPassed"]?.jsonPrimitive?.booleanOrNull)

            // Gate 4 verified: empty mathAssertions NEVER passes with checkedCount=0
            val emptyAudit = CourseQualityGates.auditMathAssertions(emptyList())
            assertFalse(emptyAudit.passed)
            assertEquals(0, emptyAudit.checkedCount)
            assertTrue(emptyAudit.failures.isNotEmpty())
            assertEquals(false, gates["gate4MathPassed"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(0, gates["gate4CheckedCount"]?.jsonPrimitive?.intOrNull)
            assertTrue((gates["gate4Failures"]?.jsonArray?.size ?: 0) > 0)

            // Wrong inline arithmetic in prose without mathAssertions must also trigger Gate 4 repair
            val repairCalls = AtomicInteger(0)
            val inlineMathPipe = CourseTopicSynthesisPipeline(
                object : BatchAiTransport {
                    override suspend fun generateSection(
                        systemPrompt: String,
                        userPrompt: String,
                        task: String,
                        batchKey: String,
                        maxTokens: Int,
                    ): BatchTransportReply {
                        val txt = when (task) {
                            "course_topic_map" -> """{"claims":[],"citations":[]}"""
                            "course_topic_math_repair" -> {
                                repairCalls.incrementAndGet()
                                """{"summary":"Corrected net outlay: 250 * (1 - 5%) = 237.50.","mathAssertions":[{"expression":"250 * (1 - 5%)","expected":"237.50"}]}"""
                            }
                            else -> """{"summary":"Wrong inline calc: 250 * (1 - 5%) = 210.00.","authorityNotice":"L1 first","keyPoints":[],"discrepancies":[],"practicePrompt":{"promptId":"p-m","kind":"ESSAY","stem":"Analyze deductible trade-off in 300 words.","wordTarget":300},"mathAssertions":[]}"""
                        }
                        return BatchTransportReply(txt, CompanionRouteChannel.CODEBUDDY_DEEPSEEK_FLASH, "deepseek-v4.1-flash", 100, 100, 0, 10, 10)
                    }
                },
                ledger,
                cache,
            )
            val repairedInline = inlineMathPipe.generateTopicPack(
                buildJsonObject {
                    put("courseId", "c-reg")
                    put("topicId", "t-reg-inline-math")
                    put("topicTitle", "Math")
                    put("sourcesHash", "h-inline-math")
                    put("blocks", buildJsonArray {
                        add(buildJsonObject {
                            put("bookId", "s1")
                            put("bookTitle", "Slides")
                            put("role", "LECTURE_SLIDES")
                            put("authorityRank", 1)
                            put("page", 1)
                            put("text", "Insurance copay model")
                        })
                    })
                },
                cacheScope = "owner:reg",
            )
            assertEquals(1, repairCalls.get(), "Wrong inline equation must trigger Gate 4 repair")
            val inlineGates = repairedInline["gates"]!!.jsonObject
            assertEquals(true, inlineGates["gate4MathPassed"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(1, inlineGates["gate4CheckedCount"]?.jsonPrimitive?.intOrNull)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun topicSynthesisBoundsMapConcurrencyAndUsesAdaptiveThinkingOutputBudget() = runBlocking {
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)
        val seenMaxTokens = java.util.Collections.synchronizedMap(HashMap<String, Int>())
        val transport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String,
                userPrompt: String,
                task: String,
                batchKey: String,
                maxTokens: Int,
            ): BatchTransportReply {
                seenMaxTokens[task] = maxTokens
                if (task == "course_topic_map") {
                    val mapBlocks = Json.parseToJsonElement(userPrompt).jsonObject["blocks"]!!.jsonArray
                    assertTrue(mapBlocks.all { it.jsonObject["authorityRank"]?.jsonPrimitive?.content == "1" })
                    assertFalse(userPrompt.contains("synthetic-held-out-stem"))
                    val cur = inFlight.incrementAndGet()
                    peakInFlight.accumulateAndGet(cur, ::maxOf)
                    kotlinx.coroutines.delay(40)
                    inFlight.decrementAndGet()
                }
                val text = when (task) {
                    "course_topic_map" -> """{"claims":[],"citations":[]}"""
                    else -> """{"summary":"Concise synthesis.","authorityNotice":"L1 first.","keyPoints":[],"discrepancies":[],"practicePrompt":{"promptId":"p1","kind":"ESSAY","stem":"Explain screening.","wordTarget":300},"mathAssertions":[]}"""
                }
                return BatchTransportReply(text, CompanionRouteChannel.HAIKU_REALTIME, "claude-haiku-5-5", 100, 50, 0, 10, 40)
            }
        }
        val pipe = CourseTopicSynthesisPipeline(transport, maxMapConcurrency = 2)
        val blocks = buildJsonArray {
            add(buildJsonObject {
                put("bookId", "held-out")
                put("bookTitle", "Untitled")
                put("role", "PAST_EXAM")
                put("text", "synthetic-held-out-stem")
            })
            for (i in 1..24) {
                add(buildJsonObject {
                    put("bookId", "bk-1")
                    put("bookTitle", "Core Textbook")
                    put("role", "CORE_TEXTBOOK")
                    put("authorityRank", 2)
                    put("page", i)
                    put("text", "Paragraph $i describing health insurance market equilibrium.")
                })
            }
        }
        pipe.generateTopicPack(
            buildJsonObject {
                put("courseId", "c-conc")
                put("topicId", "t-conc")
                put("topicTitle", "Equilibrium")
                put("sourcesHash", "h-conc")
                put("blocks", blocks)
            },
            cacheScope = "owner:test",
        )
        assertTrue(peakInFlight.get() in 1..2, "Expected bounded concurrency <= 2, got ${peakInFlight.get()}")
        assertTrue((seenMaxTokens["course_topic_map"] ?: 0) >= 6_000)
        assertTrue((seenMaxTokens["course_topic_reduce"] ?: 0) >= 8_000)
    }

    @Test
    fun citationVerifierJoinsLineBreakAndSoftHyphensWithoutRelaxingOtherRules() {
        val rawPage = """
            The synthetic pilot found a pos- itive response in out-
            patient visits when coinsurance fell sharply.
            In addition, the soft\u00ADhyphenated term sig\u00AD
            nificant appeared in the table discussion, and risk-
            averse households bought fuller coverage.
            A cost-effective intervention lowered costs in 2020- 2021.
            Health - income gaps widened, while demand— and supply shifted together.
            The pos itive typo without a hyphen is distinct from one word.
        """.trimIndent().replace("\\u00AD", "\u00AD")
        val blk = CourseMaterialTextBlock("tb:p44:0", "tb", "Synthetic Textbook", "CORE_TEXTBOOK", 1, 44, 0, rawPage)
        val byPage = mapOf(("tb" to 44) to listOf(blk))
        val byBook = mapOf("tb" to listOf(blk))

        fun verify(quote: String) = CourseQualityGates.verifyCitation(
            buildJsonObject {
                put("bookId", "tb")
                put("page", 44)
                put("quote", quote)
            },
            byPage,
            byBook,
        )

        // Positive cases: letter + hyphen + space/newline + letter, soft hyphen (with or without newline),
        // Unicode hyphen U+2010, and compound word wrapped at its hyphen ("risk-\naverse" vs "risk-averse").
        val pSpace = verify("The synthetic pilot found a positive response in outpatient visits when coinsurance fell sharply.")
        assertNotNull(pSpace, "Hyphen + space ('pos- itive') and hyphen + newline ('out-\\npatient') must verify")
        assertEquals(
            "The synthetic pilot found a pos- itive response in out-\npatient visits when coinsurance fell sharply.",
            rawPage.substring(pSpace.startUtf16, pSpace.endUtf16),
        )

        val pSoft = verify("In addition, the softhyphenated term significant appeared in the table discussion")
        assertNotNull(pSoft, "Soft hyphen U+00AD (with or without newline) must verify")
        assertTrue(rawPage.substring(pSoft.startUtf16, pSoft.endUtf16).contains("soft\u00ADhyphenated"))

        val pCompoundWrapped = verify("and risk-averse households bought fuller coverage.")
        assertNotNull(pCompoundWrapped, "Compound word wrapped at hyphen ('risk-\\naverse') must match 'risk-averse'")

        val unicodeHyphenRaw = "The ex\u2010\nperiment tracked clinic visits."
        val unicodeSpan = Utf16AnchorVerifier.findNormalizedUtf16Span(unicodeHyphenRaw, "The experiment tracked clinic visits.")
        assertNotNull(unicodeSpan, "Unicode hyphen U+2010 + newline must join split word")
        assertEquals(0 to unicodeHyphenRaw.length, unicodeSpan)

        // Negative cases: MUST NOT relax any other comparison rules!
        assertNull(
            verify("A costeffective intervention lowered costs in 2020- 2021."),
            "Unspaced compound hyphen ('cost-effective') must NOT match 'costeffective'",
        )
        assertNull(
            verify("A cost-effective intervention lowered costs in 20202021."),
            "Digit range ('2020- 2021') must NOT be joined into '20202021'",
        )
        assertNull(
            verify("Healthincome gaps widened"),
            "Spaced dash ('Health - income') must NOT be joined into 'Healthincome'",
        )
        assertNull(
            verify("while demandand supply shifted together."),
            "Em-dash ('demand— and') must NOT be joined into 'demandand'",
        )
        assertNull(
            verify("The positive typo without a hyphen is distinct from one word."),
            "Space without hyphen ('pos itive') must NOT match 'positive'",
        )
        assertNull(
            verify("The synthetic pilot found a positive response in outpatient visits when coinsurance rose sharply."),
            "Altered word ('fell' -> 'rose') must still be rejected",
        )
    }
}
