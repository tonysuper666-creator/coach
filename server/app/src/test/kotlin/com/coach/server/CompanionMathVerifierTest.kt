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

    @Test
    fun pipelineRejectsWrongCoefficientRegeneratesAndDropsUnfixableItems() = runBlocking {
        val dir = Files.createTempDirectory("coach-math-verify-")
        val ledger = BookTokenLedger(dir)
        val bookId = "d".repeat(64)
        val tasksSeen = CopyOnWriteArrayList<String>()

        val stubTransport = object : BatchAiTransport {
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

        val pipeline = SectionConcurrentCompanionPipeline(
            transport = stubTransport,
            ledger = ledger,
            mathVerifier = CompanionMathVerifier(enableLlmAudit = true, maxRegenAttempts = 2),
        )
        val pack = pipeline.generateChapter(
            ChapterCompanionRequest(
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
            ),
        )

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
}
