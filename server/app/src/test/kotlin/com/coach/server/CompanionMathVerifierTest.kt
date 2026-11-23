package com.coach.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for TON-1804 companion math verification (SymPy symbolic & exact rational checks),
 * reject-and-regenerate / fail-closed drop, cross-page exercise deduplication, and zero placeholder leaks.
 * Uses synthetic inputs only (no textbook excerpts).
 */
class CompanionMathVerifierTest {

    @Test
    fun sympySubprocessVerifiesSymbolicIdentitiesEvaluationsInequalitiesAndRatios() {
        val engine = SympySubprocessEngine()
        val resp = engine.runBatch(
            listOf(
                MathCheckSpec(id = "id-ok", kind = "identity", lhs = "(5/2)*(P/2) - P", rhs = "P/4"),
                MathCheckSpec(id = "id-bad", kind = "identity", lhs = "(3/2)*(P/2) - P", rhs = "P/4"),
                MathCheckSpec(id = "eval-ok", kind = "eval", expression = "(3/2)*X", variables = mapOf("X" to "2"), expected = "3"),
                MathCheckSpec(id = "eval-bad", kind = "eval", expression = "(3/2)*X", variables = mapOf("X" to "2"), expected = "6"),
                MathCheckSpec(
                    id = "ineq-ok",
                    kind = "inequality",
                    inequality = "(3/2)*(P + 10)/2 - P >= 0",
                    variable = "P",
                    expectedRelation = "<=",
                    expectedThreshold = "30",
                ),
                MathCheckSpec(
                    id = "ineq-wrong-dir",
                    kind = "inequality",
                    inequality = "(3/2)*(P + 10)/2 - P >= 0",
                    variable = "P",
                    expectedRelation = ">=",
                    expectedThreshold = "30",
                ),
                MathCheckSpec(id = "ratio-bad", kind = "ratio", lhs = "3", rhs = "3/2"),
                MathCheckSpec(id = "ratio-ok", kind = "ratio", lhs = "1.5", rhs = "3/2"),
                MathCheckSpec(id = "pct-paren-ok", kind = "eval", expression = "(100 - 60)%", expected = "2/5"),
                MathCheckSpec(id = "expectation-ok", kind = "identity", lhs = "(5/2)*E[X] - P", rhs = "2.5*E_X - P"),
                MathCheckSpec(id = "num-ineq-ok", kind = "inequality", inequality = "0.6**2 + 0.4**2 > 0.25", variable = "HHI", expectedRelation = ">", expectedThreshold = "0.25"),
                MathCheckSpec(id = "sub-ineq-ok", kind = "inequality", inequality = "r - p*q > 0", variable = "r", variables = mapOf("p" to "0.1", "q" to "100"), expectedRelation = ">", expectedThreshold = "10"),
                MathCheckSpec(id = "security-reject", kind = "eval", expression = "__import__('os').system('id')", expected = "0"),
            ),
        )
        assertEquals("sympy", resp.engine, "SymPy must be active for symbolic verification")
        val byId = resp.results.associateBy { it.id }
        assertTrue(byId.getValue("id-ok").passed)
        assertFalse(byId.getValue("id-bad").passed)
        assertTrue(byId.getValue("eval-ok").passed)
        assertFalse(byId.getValue("eval-bad").passed)
        assertTrue(byId.getValue("ineq-ok").passed)
        assertFalse(byId.getValue("ineq-wrong-dir").passed)
        assertFalse(byId.getValue("ratio-bad").passed)
        assertTrue(byId.getValue("ratio-ok").passed)
        assertTrue(byId.getValue("pct-paren-ok").passed)
        assertTrue(byId.getValue("expectation-ok").passed)
        assertTrue(byId.getValue("num-ineq-ok").passed)
        assertTrue(byId.getValue("sub-ineq-ok").passed)
        assertFalse(byId.getValue("security-reject").passed)
    }

    @Test
    fun deterministicChecksCatchStackedFractionMultiplierErrorAndChineseMultiplierContradiction() {
        // Synthetic source mimicking a stacked fraction split across lines + prose "50% more than"
        val blocks = listOf(
            SectionBlockInput(
                id = "syn:p10:0",
                pdfPage = 10,
                startUtf16 = 0,
                text = "VB = 3\nQj + M\nj= 2\n1\nA buyer values a unit at 50% more than a seller.",
            ),
            SectionBlockInput(
                id = "syn:p18:0",
                pdfPage = 18,
                startUtf16 = 0,
                text = "Suppose instead buyers value a unit 150% more than sellers.",
            ),
        )
        val cues = SourcePageMathExtractor.extract(blocks)
        assertEquals(2, cues.multipliers.size)
        assertTrue(cues.multipliers.any { it.pdfPage == 10 && it.exactFactor == Rational.of(3, 2) })
        assertTrue(cues.multipliers.any { it.pdfPage == 18 && it.exactFactor == Rational.of(5, 2) })
        assertTrue(cues.stackedFractionWarnings.isNotEmpty())

        // Wrong coefficient 3 ("三倍") on p.10 where source says "50% more" (3/2)
        val badCoeffFailures = DeterministicMathChecks.checkDeterministicConsistency(
            itemText = "买家效用对质量的加权系数为 3，即买家估值是卖家的三倍。",
            sourceCues = cues,
            anchorPage = 10,
        )
        assertTrue(badCoeffFailures.any { it.contains("source_multiplier_mismatch") }, "Must catch dropped denominator 3 vs 3/2: $badCoeffFailures")

        // Prose says "1.5倍" while formula uses (5/2) (= 2.5x)
        val badMultiplierFailures = DeterministicMathChecks.checkDeterministicConsistency(
            itemText = "买家估值为卖家的1.5倍，代入得 ΔV = (5/2)*(P/2) - P = P/4。",
            sourceCues = cues,
            anchorPage = 18,
        )
        assertTrue(
            badMultiplierFailures.any { it.contains("chinese_multiplier_formula_contradiction") },
            "Must catch 1.5倍 vs 5/2 contradiction: $badMultiplierFailures",
        )

        // Corrected text with 3/2 (1.5倍) passes cleanly
        val cleanFailures = DeterministicMathChecks.checkDeterministicConsistency(
            itemText = "买家效用对质量的加权系数为 3/2（即 1.5倍），对质量 Q=2 贡献为 (3/2)*2 = 3。",
            sourceCues = cues,
            anchorPage = 10,
        )
        assertTrue(cleanFailures.isEmpty(), "Clean 3/2 text must pass, got $cleanFailures")

        // Unicode minus sign U+2212 in MCQ option must match negative computed value
        assertTrue(
            DeterministicMathChecks.optionMatchesComputedValue("不愿意上市，因为 P − X1 = −10 小于 0", "-10", "-10"),
            "Unicode minus sign −10 must match -10",
        )
        assertFalse(
            DeterministicMathChecks.optionMatchesComputedValue("6", "3", "3"),
            "Wrong numeric option 6 must not match 3",
        )
    }

    private fun buildMathAuditRepairStubTransport(
        tasksSeen: MutableList<String>,
        friendCallsSeen: MutableList<Triple<String, String?, String?>>? = null,
    ): BatchAiTransport = object : BatchAiTransport {
        override suspend fun generateSectionForFriend(
            systemPrompt: String,
            userPrompt: String,
            task: String,
            batchKey: String,
            maxTokens: Int,
            friend: String?,
            device: String?,
        ): BatchTransportReply {
            friendCallsSeen?.add(Triple(task, friend, device))
            return generateSection(systemPrompt, userPrompt, task, batchKey, maxTokens)
        }

        override suspend fun generateSection(
            systemPrompt: String,
            userPrompt: String,
            task: String,
            batchKey: String,
            maxTokens: Int,
        ): BatchTransportReply {
            tasksSeen += task
            val text = when (task) {
                "BOOK_SECTION_BATCH" -> buildJsonObject {
                    put("coreQuestion", "信息不对称如何导致市场萎缩？")
                    put("keyPoints", buildJsonArray {
                        add(buildJsonObject {
                            put("summary", "买家估值比卖家高出百分之五十。")
                            put("pdfPage", 10)
                            put("quoteAnchor", "values a unit at 50%")
                        })
                    })
                    put("jargonGlosses", buildJsonArray {})
                    put("pretest", buildJsonArray {})
                    put("derivations", buildJsonArray {
                        // Derivation 0: WRONG coefficient 3 instead of 3/2 -> will be rejected & repaired
                        add(buildJsonObject {
                            put("title", "买卖双方效用函数")
                            put("setup", "质量为 Q，其他商品为 M。")
                            put("steps", buildJsonArray {
                                add(JsonPrimitive("买家效用加权系数为 3，估值是卖家三倍。"))
                            })
                            put("graphCheck", "核对系数。")
                            put("pitfall", "勿忽略系数 3。")
                            put("pdfPage", 10)
                            put("quoteAnchor", "values a unit at 50%")
                        })
                        // Derivation 1: Unfixable bad math -> repair will still fail -> MUST BE DROPPED
                        add(buildJsonObject {
                            put("title", "错误临界值推导")
                            put("setup", "测试不可修复项。")
                            put("steps", buildJsonArray {
                                add(JsonPrimitive("令 2 + 2 = 5，导出临界值 99。"))
                            })
                            put("graphCheck", "无。")
                            put("pitfall", "无。")
                            put("pdfPage", 10)
                            put("quoteAnchor", "values a unit at 50%")
                        })
                    })
                    put("optionalPractice", buildJsonArray {
                        // Practice 0: WRONG calculation 3*2=6 -> will be rejected & repaired to 3/2 * 2 = 3
                        add(buildJsonObject {
                            put("stem", "若质量 Q = 2，按买家效用系数计算其效用贡献是多少？")
                            put("options", buildJsonArray {
                                add(JsonPrimitive("2"))
                                add(JsonPrimitive("3"))
                                add(JsonPrimitive("5"))
                                add(JsonPrimitive("6"))
                            })
                            put("correctOption", "6")
                            put("explanation", "买家系数为 3，因此 3 * 2 = 6。")
                            put("kind", "MCQ")
                            put("pdfPage", 10)
                            put("quoteAnchor", "values a unit at 50%")
                        })
                    })
                }.toString()

                "BOOK_MATH_VERIFY" -> {
                    // Independent auditor checks whether the prompt contains the repaired 3/2 text or broken 3 text
                    val isRepaired = userPrompt.contains("3/2") && !userPrompt.contains("系数为 3，")
                    buildJsonObject {
                        put("itemAudits", buildJsonArray {
                            if (userPrompt.contains("sec-1:drv:0:0")) {
                                add(buildJsonObject {
                                    put("itemId", "sec-1:drv:0:0")
                                    put("sourceMatches", isRepaired)
                                    put("sourceMismatchReason", if (isRepaired) "" else "Source says 50% more -> coefficient is 3/2, not 3")
                                    put("sympyChecks", buildJsonArray {
                                        add(buildJsonObject {
                                            put("kind", "ratio")
                                            put("lhs", if (isRepaired) "3/2" else "3")
                                            put("rhs", "3/2")
                                        })
                                    })
                                    put("mcqComputedAnswer", "")
                                })
                            }
                            if (userPrompt.contains("sec-1:drv:0:1")) {
                                add(buildJsonObject {
                                    put("itemId", "sec-1:drv:0:1")
                                    put("sourceMatches", false)
                                    put("sourceMismatchReason", "Arithmetic 2+2=5 is invalid")
                                    put("sympyChecks", buildJsonArray {
                                        add(buildJsonObject {
                                            put("kind", "eval")
                                            put("expression", "2 + 2")
                                            put("expected", "5")
                                        })
                                    })
                                    put("mcqComputedAnswer", "")
                                })
                            }
                            if (userPrompt.contains("sec-1:prac:0:0")) {
                                add(buildJsonObject {
                                    put("itemId", "sec-1:prac:0:0")
                                    put("sourceMatches", isRepaired)
                                    put("sourceMismatchReason", if (isRepaired) "" else "Coefficient must be 3/2, so (3/2)*2 = 3")
                                    put("sympyChecks", buildJsonArray {
                                        add(buildJsonObject {
                                            put("kind", "eval")
                                            put("expression", "(3/2)*Q")
                                            put("variables", buildJsonObject { put("Q", "2") })
                                            put("expected", "3")
                                        })
                                    })
                                    put("mcqComputedAnswer", "3")
                                })
                            }
                        })
                    }.toString()
                }

                "BOOK_MATH_REPAIR" -> buildJsonObject {
                    put("repairedItems", buildJsonArray {
                        // Fix Derivation 0 properly with 3/2
                        add(buildJsonObject {
                            put("itemId", "sec-1:drv:0:0")
                            put("title", "买卖双方效用函数")
                            put("setup", "质量为 Q，其他商品为 M。")
                            put("steps", buildJsonArray {
                                add(JsonPrimitive("买家效用加权系数为 3/2（即 1.5倍），对同质量商品估值高出 50%。"))
                            })
                            put("graphCheck", "确认买家系数 3/2。")
                            put("pitfall", "勿漏掉分母 2。")
                            put("pdfPage", 10)
                            put("quoteAnchor", "values a unit at 50%")
                            put("mathChecks", buildJsonArray {
                                add(buildJsonObject { put("kind", "ratio"); put("lhs", "3/2"); put("rhs", "1.5") })
                            })
                        })
                        // Derivation 1 still returns broken math -> must be DROPPED after maxRegenAttempts
                        add(buildJsonObject {
                            put("itemId", "sec-1:drv:0:1")
                            put("title", "错误临界值推导")
                            put("setup", "测试不可修复项。")
                            put("steps", buildJsonArray {
                                add(JsonPrimitive("仍有错误：2 + 2 = 5。"))
                            })
                            put("graphCheck", "无。")
                            put("pitfall", "无。")
                            put("pdfPage", 10)
                            put("quoteAnchor", "values a unit at 50%")
                        })
                        // Fix Practice 0 properly to (3/2) * 2 = 3
                        add(buildJsonObject {
                            put("itemId", "sec-1:prac:0:0")
                            put("stem", "若质量 Q = 2，按买家效用系数 3/2 计算其效用贡献是多少？")
                            put("options", buildJsonArray {
                                add(JsonPrimitive("2"))
                                add(JsonPrimitive("3"))
                                add(JsonPrimitive("5"))
                                add(JsonPrimitive("6"))
                            })
                            put("correctOption", "3")
                            put("explanation", "买家效用系数为 3/2，因此 (3/2) * 2 = 3。")
                            put("quizKind", "MCQ")
                            put("pdfPage", 10)
                            put("quoteAnchor", "values a unit at 50%")
                            put("mathChecks", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "eval")
                                    put("expression", "(3/2)*Q")
                                    put("variables", buildJsonObject { put("Q", "2") })
                                    put("expected", "3")
                                })
                            })
                        })
                    })
                }.toString()

                else -> "{}"
            }
            return BatchTransportReply(
                text = text,
                routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                model = "claude-haiku-5-5",
                inputTokens = 500,
                outputTokens = 300,
                ttftMs = 40,
                elapsedMs = 80,
            )
        }
    }

    private fun syntheticMathChapterRequest(bookId: String): ChapterCompanionRequest = ChapterCompanionRequest(
        bookId = bookId,
        chapterId = "ch-syn",
        chapterTitle = "Synthetic Market",
        sections = listOf(
            SectionBatchInput(
                sectionId = "sec-1",
                title = "1.1 Utility setup",
                blocks = listOf(
                    SectionBlockInput(
                        id = "$bookId:p10:0",
                        pdfPage = 10,
                        startUtf16 = 0,
                        text = "VB = 3\nQj + M\nj= 2\n1\nA buyer values a unit at 50% more than a seller.",
                    ),
                ),
            ),
        ),
    )

    @Test
    fun pipelineRejectsWrongCoefficientRegeneratesAndDropsUnfixableItems() = runBlocking {
        val dir = Files.createTempDirectory("coach-math-verify-")
        val ledger = BookTokenLedger(dir)
        val bookId = "d".repeat(64)
        val tasksSeen = CopyOnWriteArrayList<String>()

        val pipeline = SectionConcurrentCompanionPipeline(
            transport = buildMathAuditRepairStubTransport(tasksSeen),
            ledger = ledger,
            mathVerifier = CompanionMathVerifier(enableLlmAudit = true, maxRegenAttempts = 2),
        )
        val pack = pipeline.generateChapter(syntheticMathChapterRequest(bookId))

        assertTrue("BOOK_MATH_VERIFY" in tasksSeen)
        assertTrue("BOOK_MATH_REPAIR" in tasksSeen)
        // Derivation 0 repaired & kept; Derivation 1 failed repair and DROPPED!
        assertEquals(1, pack.derivations.size, "Unfixable derivation must be dropped, repaired derivation kept")
        assertTrue(pack.derivations.single().steps.single().contains("3/2"))
        // Practice 0 repaired from 6 -> 3
        assertEquals(1, pack.optionalPractice.size)
        assertEquals("3", pack.optionalPractice.single().correctOption)
        // Audit stats accurately reflect 3 checked, 3 initially failed/rejected, 2 regenerated & passed, 1 dropped
        assertEquals(3, pack.stats.mathAudit.checkedItems)
        assertEquals(3, pack.stats.mathAudit.initialFailedItems)
        assertEquals(3, pack.stats.mathAudit.rejectedItems)
        assertEquals(2, pack.stats.mathAudit.regeneratedPassedItems)
        assertEquals(1, pack.stats.mathAudit.finalDroppedItems)
        assertEquals("sympy", pack.stats.mathAudit.engine)
    }

    @Test
    fun friendMathVerifyAndRepairSkipOwnerLedgerWhileOwnerRecordsAll() = runBlocking {
        val bookId = "c".repeat(64)
        val request = syntheticMathChapterRequest(bookId)

        // Friend run with LLM math audit + repair enabled: 0 entries written to owner's book_token_ledger,
        // and all transport calls (batch, math verify, math repair) preserve friend/device scope.
        val friendLedger = BookTokenLedger(Files.createTempDirectory("coach-friend-math-ledger-"))
        val friendTasks = CopyOnWriteArrayList<String>()
        val friendScopedCalls = CopyOnWriteArrayList<Triple<String, String?, String?>>()
        val friendPack = SectionConcurrentCompanionPipeline(
            transport = buildMathAuditRepairStubTransport(friendTasks, friendScopedCalls),
            ledger = friendLedger,
            mathVerifier = CompanionMathVerifier(enableLlmAudit = true, maxRegenAttempts = 2),
        ).generateChapter(request, friend = "alice", device = "alice-dev")

        assertEquals(1, friendPack.derivations.size)
        assertTrue("BOOK_SECTION_BATCH" in friendTasks)
        assertTrue("BOOK_MATH_VERIFY" in friendTasks)
        assertTrue("BOOK_MATH_REPAIR" in friendTasks)
        assertTrue(friendScopedCalls.isNotEmpty())
        assertTrue(friendScopedCalls.all { (_, f, d) -> f == "alice" && d == "alice-dev" })
        assertTrue(
            friendLedger.entriesForBook(bookId).isEmpty(),
            "Friend math verify/repair must write 0 rows to owner book_token_ledger",
        )
        assertEquals(0, friendLedger.bookSummary(bookId).calls)

        // Owner run: batch + BOOK_MATH_VERIFY + BOOK_MATH_REPAIR are all recorded normally in owner's ledger.
        val ownerLedger = BookTokenLedger(Files.createTempDirectory("coach-owner-math-ledger-"))
        val ownerTasks = CopyOnWriteArrayList<String>()
        val ownerPack = SectionConcurrentCompanionPipeline(
            transport = buildMathAuditRepairStubTransport(ownerTasks),
            ledger = ownerLedger,
            mathVerifier = CompanionMathVerifier(enableLlmAudit = true, maxRegenAttempts = 2),
        ).generateChapter(request)

        assertEquals(1, ownerPack.derivations.size)
        val ownerEntries = ownerLedger.entriesForBook(bookId)
        val recordedTasks = ownerEntries.map { it.task }.toSet()
        assertTrue("BOOK_SECTION_BATCH" in recordedTasks)
        assertTrue("BOOK_MATH_VERIFY" in recordedTasks)
        assertTrue("BOOK_MATH_REPAIR" in recordedTasks)
        assertEquals(ownerEntries.size, ownerLedger.bookSummary(bookId).calls)
        assertTrue(ownerEntries.size >= 4)
    }

    @Test
    fun crossPageExerciseDeduplicationAndZeroPlaceholderOverlapScrubbing() {
        val h1 = ExerciseProgressiveHint(
            id = "ex:0:9",
            sectionId = "sec-ex",
            problemLabel = "习题 10",
            questionSummary = "三角分布下分析市场是否崩溃。",
            hint1Direction = "提示 1：三角分布不同于均匀分布。",
            hint2Method = "提示 2：先写出条件期望。",
            hint3Check = "核对结论：检查是否存在成交区间。",
            anchor = VerifiedSourceAnchor(188, "b:p188:100", 100, 120, "triangular distribution"),
        )
        val h2 = ExerciseProgressiveHint(
            id = "ex:1:0",
            sectionId = "sec-ex",
            problemLabel = "Exercise 10",
            questionSummary = "续页：判断在给定三角分布下是否会有任何车辆成交并给出完整推导。",
            hint1Direction = "提示 1：分段写出三角分布密度函数 f(x) 和分布函数 F(x)，再比较买家期望估值与价格。",
            hint2Method = "提示 2：用条件期望公式求截断均值并检验买家参与条件。",
            hint3Check = "核对结论：比较截断期望与候选价格 P。",
            anchor = VerifiedSourceAnchor(189, "b:p189:50", 50, 70, "Will any cars sell"),
        )
        val h3 = ExerciseProgressiveHint(
            id = "ex:1:1",
            sectionId = "sec-ex",
            problemLabel = "习题 13a",
            questionSummary = "求一手市场上市车辆区间。",
            hint1Direction = "提示 1：比较价格与质量。",
            hint2Method = "提示 2：写出卖家条件。",
            hint3Check = "核对结论：X <= P1。",
            anchor = null,
        )
        val (deduped, removed) = CompanionItemDeduplicator.deduplicateExerciseHints(listOf(h1, h2, h3))
        assertEquals(1, removed)
        assertEquals(2, deduped.size)
        assertEquals("10", CompanionItemDeduplicator.canonicalExerciseKey(deduped[0].problemLabel))
        assertEquals(188, deduped[0].anchor?.pdfPage, "Must keep earliest page anchor across split pages")
        assertTrue(deduped[0].hint1Direction.contains("分段写出"), "Must keep richer merged hint")

        // Overlap scrubbing must NEVER inject 〔依原文公式〕 or （简述） placeholders (including capitalized English)!
        val source = "Now assume that Neither Buyers Nor Sellers Have Information About Specific Quality Levels in this synthetic exercise. 本段测试中文连续十二个以上字完全照抄来源原文的情形验证。"
        val leakyEn = "关注题目中 'Neither Buyers Nor Sellers Have Information About Specific Quality Levels' 这句话的含义。"
        val scrubbedEn = VerbatimOverlapGuard.scrubOverlaps(leakyEn, source)
        assertTrue(VerbatimOverlapGuard.audit(listOf(scrubbedEn), source).passed, "Must pass en 8-gram == 0")
        assertFalse(PlaceholderLeakGuard.hasLeak(scrubbedEn), "Must never emit placeholder tokens: $scrubbedEn")
        assertFalse(scrubbedEn.contains("依原文公式"), "Must never emit 依原文公式: $scrubbedEn")

        val leakyZh = "提示里提到本段测试中文连续十二个以上字完全照抄来源原文的情形。"
        val scrubbedZh = VerbatimOverlapGuard.scrubOverlaps(leakyZh, source)
        assertTrue(VerbatimOverlapGuard.audit(listOf(scrubbedZh), source).passed, "Must pass zh 12-gram == 0")
        assertFalse(PlaceholderLeakGuard.hasLeak(scrubbedZh), "Must never emit placeholder tokens: $scrubbedZh")
        assertFalse(scrubbedZh.contains("（简述）"), "Must never emit （简述）: $scrubbedZh")

        // Pure symbolic math formula must NOT be corrupted by overlap scrubbing
        val mathSrc = "Expected utility is E[U(I)]p = pU(IS) + (1-p)U(IH)."
        val mathStep = "写出期望效用 E[U(I)]p = pU(IS) + (1-p)U(IH)。"
        assertEquals(mathStep, VerbatimOverlapGuard.scrubOverlaps(mathStep, mathSrc))
    }

    @Test
    fun deterministicGateCatchesFormulaOnlyWrongCoefficientWithoutChineseMultiplierWording() = runBlocking {
        val blocks = listOf(
            SectionBlockInput(
                id = "syn:p175:0",
                pdfPage = 175,
                startUtf16 = 0,
                text = "UB = 3\nXj + M\nj= 2\n1\nA buyer values a unit at 50% more than a seller.",
            ),
            SectionBlockInput(
                id = "syn:p184:0",
                pdfPage = 184,
                startUtf16 = 0,
                text = "Suppose instead buyers value a unit 150% more than sellers.",
            ),
        )
        val cues = SourcePageMathExtractor.extract(blocks)

        // ZERO Chinese "系数为/N倍" words — pure formula coefficient 3 instead of 3/2
        for (badFormula in listOf(
            "U_B = 3*X_j + M",
            "U_B = 3X_j + M",
            "ΔU_B = 3*E[X] - P",
            "ΔU_B = 3*(P/2) - P",
            "ΔU_B = 3(P/2) - P",
            "U_B = 3 · 25 = 75",
        )) {
            val fails = DeterministicMathChecks.checkDeterministicConsistency(
                itemText = badFormula,
                sourceCues = cues,
                anchorPage = 175,
            )
            assertTrue(
                fails.any { it.startsWith("formula_coefficient_mismatch(p.175)") },
                "Must catch formula-only wrong coefficient in '$badFormula', got $fails",
            )
        }

        // Wrong coefficient inside declared mathChecks only (even if prose has no formula!)
        val checkOnlyFails = DeterministicMathChecks.checkDeterministicConsistency(
            itemText = "检验买家期望估值。",
            sourceCues = cues,
            anchorPage = 175,
            declaredChecks = listOf(
                MathCheckSpec(kind = "eval", expression = "3*X", variables = mapOf("X" to "25"), expected = "75"),
            ),
        )
        assertTrue(
            checkOnlyFails.any { it.startsWith("formula_coefficient_mismatch(p.175)") },
            "Must catch wrong coefficient in declared mathChecks, got $checkOnlyFails",
        )

        // Wrong coefficient 5 (dropped /2) AND mistranslated 1.5 (3/2) on p.184 where source ONLY says "150% more" (5/2)
        val bad5Fails = DeterministicMathChecks.checkDeterministicConsistency(
            itemText = "ΔU_B = 5*(P/2) - P",
            sourceCues = cues,
            anchorPage = 184,
        )
        assertTrue(
            bad5Fails.any { it.startsWith("formula_coefficient_mismatch(p.184)") },
            "Must catch dropped /2 integer 5 on 150% more page, got $bad5Fails",
        )
        val bad15Fails = DeterministicMathChecks.checkDeterministicConsistency(
            itemText = "U_B = 1.5*X_j + M",
            sourceCues = cues,
            anchorPage = 184,
        )
        assertTrue(
            bad15Fails.any { it.startsWith("formula_coefficient_mistranslation(p.184)") },
            "Must catch 1.5*X on 150%-more-only page, got $bad15Fails",
        )

        // Valid formulas with 3/2, 1.5, or 3*X_j/2 MUST pass cleanly
        for (goodFormula in listOf(
            "U_B = (3/2)*X_j + M",
            "U_B = 3*X_j/2 + M",
            "ΔU_B = (3/2)*E[X] - P",
            "ΔU_B = 1.5*(P/2) - P",
        )) {
            val ok = DeterministicMathChecks.checkDeterministicConsistency(
                itemText = goodFormula,
                sourceCues = cues,
                anchorPage = 175,
            )
            assertTrue(ok.isEmpty(), "Valid 3/2 formula '$goodFormula' must pass, got $ok")
        }

        // End-to-end: formula-only wrong coefficient MUST be rejected & DROPPED when repair does not fix it
        val dir = Files.createTempDirectory("coach-formula-drop-")
        val ledger = BookTokenLedger(dir)
        val bookId = "e".repeat(64)
        val transport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String,
                userPrompt: String,
                task: String,
                batchKey: String,
                maxTokens: Int,
            ): BatchTransportReply {
                val text = when (task) {
                    "BOOK_SECTION_BATCH" -> buildJsonObject {
                        put("coreQuestion", "测试公式系数拦截。")
                        put("keyPoints", buildJsonArray {})
                        put("jargonGlosses", buildJsonArray {})
                        put("pretest", buildJsonArray {})
                        put("derivations", buildJsonArray {
                            add(buildJsonObject {
                                put("title", "买家估值推导")
                                put("setup", "质量为 X_j，其他消费为 M。")
                                put("steps", buildJsonArray {
                                    add(JsonPrimitive("写出买家效用：U_B = 3*X_j + M。"))
                                    add(JsonPrimitive("令 P = 50, E[X] = 25，则 ΔU_B = 3*E[X] - P = 25。"))
                                })
                                put("graphCheck", "核对斜率。")
                                put("pitfall", "注意期望质量。")
                                put("pdfPage", 175)
                                put("quoteAnchor", "values a unit at 50%")
                            })
                        })
                        put("optionalPractice", buildJsonArray {})
                    }.toString()
                    // Even if LLM auditor mistakenly returns sourceMatches=true, deterministic formula gate MUST reject!
                    "BOOK_MATH_VERIFY" -> buildJsonObject {
                        put("itemAudits", buildJsonArray {
                            add(buildJsonObject {
                                put("itemId", "sec-8-2:drv:0:0")
                                put("sourceMatches", true)
                                put("sourceMismatchReason", "")
                                put("sympyChecks", buildJsonArray {})
                                put("mcqComputedAnswer", "")
                            })
                        })
                    }.toString()
                    // Repair keeps the broken formula -> MUST be dropped fail-closed!
                    "BOOK_MATH_REPAIR" -> buildJsonObject {
                        put("repairedItems", buildJsonArray {
                            add(buildJsonObject {
                                put("itemId", "sec-8-2:drv:0:0")
                                put("title", "买家估值推导")
                                put("setup", "质量为 X_j。")
                                put("steps", buildJsonArray { add(JsonPrimitive("仍写 U_B = 3*X_j + M。")) })
                                put("graphCheck", "核对斜率。")
                                put("pitfall", "注意。")
                                put("pdfPage", 175)
                                put("quoteAnchor", "values a unit at 50%")
                            })
                        })
                    }.toString()
                    else -> "{}"
                }
                return BatchTransportReply(
                    text = text,
                    routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                    model = "claude-haiku-5-5",
                    inputTokens = 100,
                    outputTokens = 100,
                    ttftMs = 10,
                    elapsedMs = 20,
                )
            }
        }
        val pack = SectionConcurrentCompanionPipeline(
            transport = transport,
            ledger = ledger,
            mathVerifier = CompanionMathVerifier(enableLlmAudit = true, maxRegenAttempts = 1),
        ).generateChapter(
            ChapterCompanionRequest(
                bookId = bookId,
                chapterId = "ch-formula",
                chapterTitle = "Formula Check",
                sections = listOf(SectionBatchInput(sectionId = "sec-8-2", title = "8.2 Model", blocks = listOf(blocks[0]))),
            ),
        )
        assertTrue(pack.derivations.isEmpty(), "Formula with wrong coefficient 3 vs 3/2 MUST be dropped fail-closed")
        assertEquals(1, pack.stats.mathAudit.finalDroppedItems)
    }

    @Test
    fun auditFailureOrOmittedItemFailsClosedAndDropsUnauditedItems() = runBlocking {
        val block = SectionBlockInput(
            id = "f".repeat(64) + ":p20:0",
            pdfPage = 20,
            startUtf16 = 0,
            text = "Expected value E = 0.5*100 + 0.5*200 = 150.",
        )

        // Case 1: BOOK_MATH_VERIFY throws an exception -> ALL math items MUST be dropped fail-closed!
        val throwingTransport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String,
                userPrompt: String,
                task: String,
                batchKey: String,
                maxTokens: Int,
            ): BatchTransportReply {
                if (task == "BOOK_MATH_VERIFY") error("simulated_audit_timeout")
                val text = when (task) {
                    "BOOK_SECTION_BATCH" -> buildJsonObject {
                        put("coreQuestion", "测试审计失败关门。")
                        put("keyPoints", buildJsonArray {})
                        put("jargonGlosses", buildJsonArray {})
                        put("pretest", buildJsonArray {})
                        put("derivations", buildJsonArray {
                            add(buildJsonObject {
                                put("title", "期望值计算")
                                put("setup", "两状态等概率。")
                                put("steps", buildJsonArray { add(JsonPrimitive("E = 0.5*100 + 0.5*200 = 150。")) })
                                put("graphCheck", "无。")
                                put("pitfall", "无。")
                                put("pdfPage", 20)
                                put("quoteAnchor", "Expected value E")
                            })
                        })
                        put("optionalPractice", buildJsonArray {})
                    }.toString()
                    "BOOK_MATH_REPAIR" -> buildJsonObject { put("repairedItems", buildJsonArray {}) }.toString()
                    else -> "{}"
                }
                return BatchTransportReply(
                    text = text,
                    routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                    model = "claude-haiku-5-5",
                    inputTokens = 100,
                    outputTokens = 100,
                    ttftMs = 10,
                    elapsedMs = 20,
                )
            }
        }
        val packFail = SectionConcurrentCompanionPipeline(
            transport = throwingTransport,
            ledger = BookTokenLedger(Files.createTempDirectory("coach-audit-throw-")),
            mathVerifier = CompanionMathVerifier(enableLlmAudit = true, maxRegenAttempts = 1),
        ).generateChapter(
            ChapterCompanionRequest(
                bookId = "f".repeat(64),
                chapterId = "ch-throw",
                chapterTitle = "Audit Fail Closed",
                sections = listOf(SectionBatchInput(sectionId = "sec-1", title = "1.1", blocks = listOf(block))),
            ),
        )
        assertTrue(packFail.derivations.isEmpty(), "When BOOK_MATH_VERIFY throws, unaudited derivation MUST be dropped fail-closed")
        assertEquals(1, packFail.stats.mathAudit.finalDroppedItems)
        assertTrue(packFail.stats.mathAudit.failureSummaries.any { it.contains("audit_failed:") })

        // Case 2: BOOK_MATH_VERIFY returns itemAudits for drv:0:0 ONLY and OMITS drv:0:1 -> drv:0:0 passes, drv:0:1 is dropped!
        val omitTransport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String,
                userPrompt: String,
                task: String,
                batchKey: String,
                maxTokens: Int,
            ): BatchTransportReply {
                val text = when (task) {
                    "BOOK_SECTION_BATCH" -> buildJsonObject {
                        put("coreQuestion", "测试审计漏项关门。")
                        put("keyPoints", buildJsonArray {})
                        put("jargonGlosses", buildJsonArray {})
                        put("pretest", buildJsonArray {})
                        put("derivations", buildJsonArray {
                            add(buildJsonObject {
                                put("title", "已审计项")
                                put("setup", "等概率。")
                                put("steps", buildJsonArray { add(JsonPrimitive("0.5*100 + 0.5*200 = 150。")) })
                                put("graphCheck", "无。")
                                put("pitfall", "无。")
                                put("pdfPage", 20)
                                put("quoteAnchor", "Expected value E")
                            })
                            add(buildJsonObject {
                                put("title", "被审计漏掉的项")
                                put("setup", "等概率。")
                                put("steps", buildJsonArray { add(JsonPrimitive("0.5*100 + 0.5*200 = 150。")) })
                                put("graphCheck", "无。")
                                put("pitfall", "无。")
                                put("pdfPage", 20)
                                put("quoteAnchor", "Expected value E")
                            })
                        })
                        put("optionalPractice", buildJsonArray {})
                    }.toString()
                    "BOOK_MATH_VERIFY" -> buildJsonObject {
                        put("itemAudits", buildJsonArray {
                            // Only audits sec-1:drv:0:0; intentionally omits sec-1:drv:0:1!
                            if (userPrompt.contains("sec-1:drv:0:0")) {
                                add(buildJsonObject {
                                    put("itemId", "sec-1:drv:0:0")
                                    put("sourceMatches", true)
                                    put("sourceMismatchReason", "")
                                    put("sympyChecks", buildJsonArray {
                                        add(buildJsonObject {
                                            put("kind", "eval")
                                            put("expression", "0.5*100 + 0.5*200")
                                            put("expected", "150")
                                        })
                                    })
                                    put("mcqComputedAnswer", "")
                                })
                            }
                        })
                    }.toString()
                    "BOOK_MATH_REPAIR" -> buildJsonObject { put("repairedItems", buildJsonArray {}) }.toString()
                    else -> "{}"
                }
                return BatchTransportReply(
                    text = text,
                    routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                    model = "claude-haiku-5-5",
                    inputTokens = 100,
                    outputTokens = 100,
                    ttftMs = 10,
                    elapsedMs = 20,
                )
            }
        }
        val packOmit = SectionConcurrentCompanionPipeline(
            transport = omitTransport,
            ledger = BookTokenLedger(Files.createTempDirectory("coach-audit-omit-")),
            mathVerifier = CompanionMathVerifier(enableLlmAudit = true, maxRegenAttempts = 1),
        ).generateChapter(
            ChapterCompanionRequest(
                bookId = "f".repeat(64),
                chapterId = "ch-omit",
                chapterTitle = "Audit Omit Closed",
                sections = listOf(SectionBatchInput(sectionId = "sec-1", title = "1.1", blocks = listOf(block))),
            ),
        )
        assertEquals(1, packOmit.derivations.size, "Only audited item may enter pack; omitted item MUST be dropped")
        assertEquals("sec-1:drv:0:0", packOmit.derivations.single().id)
        assertEquals(1, packOmit.stats.mathAudit.finalDroppedItems)
        assertTrue(packOmit.stats.mathAudit.failureSummaries.any { it.contains("audit_omitted_item:sec-1:drv:0:1") })
    }

    @Test
    fun mathCheckSpecNormalizesSchemaAliasesAndPercentOptionMatching() {
        val ratioSpec = MathCheckSpec.fromJson(
            buildJsonObject {
                put("kind", "ratio")
                put("numerator", "1650")
                put("denominator", "2750")
                put("expected", "3/5")
            },
            defaultId = "r1",
        )!!
        val ineqSpec1 = MathCheckSpec.fromJson(
            buildJsonObject {
                put("kind", "inequality")
                put("lhs", "0.38")
                put("op", ">")
                put("rhs", "0.29")
            },
            defaultId = "i1",
        )!!
        val ineqSpec2 = MathCheckSpec.fromJson(
            buildJsonObject {
                put("kind", "inequality")
                put("expression", "r - p*q")
                put("variables", buildJsonObject { put("p", "0.2"); put("r", "12"); put("q", "50") })
                put("relation", ">")
                put("rhs", "0")
            },
            defaultId = "i2",
        )!!
        val solveSpec = MathCheckSpec.fromJson(
            buildJsonObject {
                put("kind", "solve")
                put("equation", "15/2 - P/4 >= 0")
                put("variable", "P")
                put("expected", "P <= 30")
            },
            defaultId = "s1",
        )!!
        val resp = SympySubprocessEngine().runBatch(listOf(ratioSpec, ineqSpec1, ineqSpec2, solveSpec))
        assertEquals("sympy", resp.engine)
        assertTrue(resp.results.all { it.passed }, "All normalized aliases must pass SymPy: ${resp.results}")

        // 25% option must match computed 25 (percentage points) AND 0.25 (decimal)
        assertTrue(DeterministicMathChecks.optionMatchesComputedValue("25%", "25", "25"))
        assertTrue(DeterministicMathChecks.optionMatchesComputedValue("25%", "1/4", "0.25"))
        assertFalse(DeterministicMathChecks.optionMatchesComputedValue("25%", "40", "40"))
    }
}
