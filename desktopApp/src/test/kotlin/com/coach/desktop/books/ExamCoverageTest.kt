package com.coach.desktop.books

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExamCoverageTest {
    private val textbook = """
        Demand elasticity: Buyers respond to price.
        Quality weight: A year of life scaled by health.
    """.trimIndent()

    private val pages = listOf(
        ExamTeachingPage("tb", 1, CourseMaterialRole.CORE_TEXTBOOK, "Demand elasticity: Buyers respond to price."),
        ExamTeachingPage("tb", 2, CourseMaterialRole.CORE_TEXTBOOK, "Quality weight: A year of life scaled by health."),
        ExamTeachingPage("slides", 1, CourseMaterialRole.LECTURE_SLIDES, "Week 2 discussion applies DEMAND ELASTICITY in a new example."),
    )

    private val questions = listOf(
        "Point: demand elasticity\nExplain the idea. SECRET_STEM_ONLY",
        "Point: quality weight\nA held-out stem that must not be copied. SECRET_STEM_ONLY",
        "Point: orbital period\nThis name is not in the textbook. SECRET_STEM_ONLY",
    )

    @Test
    fun coverageRisesAfterTextbookPointsAndKeptDrills() {
        val identified = CourseExamPoints.identifyFromPages("course", pages).map { it.title }
        val legacy = CourseExamPoints.identifyFromPages("course", pages)
            .filter { it.sources.distinctBy { source -> source.bookId }.size >= 2 }
            .map { it.title }
        val before = ExamCoverage.evaluate(
            questions,
            identified = legacy,
            bank = listOf(CoverageBankPoint("demand elasticity", choices = 0, essay = false)),
            textbook = textbook,
        )
        val after = ExamCoverage.evaluate(
            questions,
            identified = identified,
            bank = listOf(
                CoverageBankPoint("demand elasticity", choices = 3, essay = false),
                CoverageBankPoint("quality weight", choices = 3, essay = true),
            ),
            textbook = textbook,
        )
        assertEquals(0, before.covered)
        assertEquals(3, before.heldOutPoints)
        assertEquals(2, after.covered)
        assertTrue(after.rate > before.rate)
        assertEquals(
            setOf(
                "demand elasticity" to ExamCoverage.QUESTION_DROPPED,
                "quality weight" to ExamCoverage.POINT_MISSED,
                "orbital period" to ExamCoverage.TEXTBOOK_SILENT,
            ),
            before.uncovered.map { it.name to it.reason }.toSet(),
        )
        assertEquals(listOf("orbital period" to ExamCoverage.TEXTBOOK_SILENT), after.uncovered.map { it.name to it.reason })
        val report = after.toJson().toString()
        assertFalse(report.contains("SECRET_STEM_ONLY"))
        assertFalse(report.contains("held-out stem"))
    }

    @Test
    fun decomposerNamesThePointAndDropsTheStem() {
        val name = HeldOutDecomposer.focus("Explain how Demand Elasticity changes a price. SECRET_STEM_ONLY")
        assertEquals("Demand Elasticity", name)
        assertFalse(name.contains("SECRET"))
        val fromVerb = HeldOutDecomposer.focus("Question 1. Explain Demand Elasticity for a luxury good.")
        assertEquals("Demand Elasticity", fromVerb)
        val taught = ExamCoverage.evaluate(
            listOf("Question 1. Explain Demand Elasticity for a luxury good."),
            identified = listOf("Demand elasticity"),
            bank = listOf(CoverageBankPoint("Demand elasticity", choices = 2, essay = false)),
            textbook = "Demand elasticity: Buyers respond to price.",
        )
        assertEquals(1, taught.covered)
        assertEquals(emptyList(), taught.uncovered)
        val fromDoc = HeldOutDecomposer.questionsFromDocument(
            """
            ## Question 1
            ### 英文原题
            > Explain the idea.
            ### 中文讲解
            The answer key must not become a question.
            ## Question 2
            [原题详解已移除：包含留出集题面或答案。]
            """.trimIndent(),
        )
        assertEquals(listOf("Explain the idea."), fromDoc.map { it.trim().trim('>') }.map { it.trim() })
    }

    @Test
    fun matchesLowercaseStemsAgainstIdentifiedAndTextbookAndReportsUnmatchedAsMissed() {
        val synthTextbook = """
            Simple market: Buyers and sellers trade goods at a clearing price.
            ## Consumer surplus
            The gap between willingness to pay and market price.
            A **deadweight loss** arises when output falls below efficiency.
        """.trimIndent()
        val questions = listOf(
            "1. Consider a simple market where buyers and sellers meet to trade.",
            "2. Consider a simple situation where deadweight loss occurs after a tax.",
            "3. Consider a simple situation where asymmetric signaling changes outcomes.",
        )
        val report = ExamCoverage.evaluate(
            questions = questions,
            identified = listOf("Simple market"),
            bank = listOf(CoverageBankPoint("Simple market", choices = 3, essay = false)),
            textbook = synthTextbook,
        )
        assertEquals(3, report.heldOutPoints)
        assertEquals(1, report.covered)
        assertEquals(listOf("Simple market"), report.coveredPoints)
        assertEquals(
            listOf(
                "deadweight loss" to ExamCoverage.POINT_MISSED,
                "未识别考点 #1" to ExamCoverage.POINT_MISSED,
            ),
            report.uncovered.map { it.name to it.reason },
        )
        assertTrue(report.uncovered.none { it.reason == ExamCoverage.TEXTBOOK_SILENT })
        val json = report.toJson()
        assertEquals("[\"Simple market\"]", json["coveredPoints"].toString())
        assertFalse(json.toString().contains("asymmetric signaling"))
        assertFalse(json.toString().contains("consider simple"))
    }

    @Test
    fun stripsCoverInstructionsDraftFooterPartHeadersAndKeepsSubNumberingTogether() {
        val syntheticExam = """
            <!-- PDF 第 1 页 · 书第 1 页 -->
            SYNTHETIC UNIVERSITY
            ECON99999 Practice Midterm Examination
            Time Allowed: 90 Minutes

            Instructions to Candidates:
            1. Write your student ID on the cover sheet.
            2. Answer ALL questions in Part I and Part II.
            3. Calculators are NOT permitted in this examination.

            <!-- PDF 第 2 页 · 书第 2 页 -->
            ECON99999 Practice Midterm
            Part I: Multiple Choice Questions (20 marks)
            Answer all questions in this part.

            1. Consider a simple market with linear curves.
            1). What is the equilibrium price?
            2). What happens when supply shifts?
            A) Price rises
            B) Price falls
            Page 2 of 4

            <!-- PDF 第 3 页 · 书第 3 页 -->
            ECON99999 Practice Midterm
            2. Explain Demand Elasticity for a luxury good.

            Part II: Short Answer Questions (30 marks)
            3. Discuss Consumer Surplus under a price cap.
            Please Turn Over

            <!-- PDF 第 4 页 · 书第 4 页 -->
            ECON99999 Practice Midterm
            THIS PAGE IS INTENTIONALLY LEFT BLANK
            Space for Rough Work
            End of Examination
            Page 4 of 4
        """.trimIndent()

        val questions = HeldOutDecomposer.questionsFromDocument(syntheticExam)
        assertEquals(3, questions.size)
        assertTrue(questions[0].contains("2). What happens when supply shifts?"))
        assertFalse(questions.any { "Instructions" in it || "student ID" in it || "Part I" in it || "Part II" in it })
        assertFalse(questions.any { "Practice Midterm" in it || "Page 2 of 4" in it || "Rough Work" in it || "INTENTIONALLY LEFT BLANK" in it })

        val synthTextbook = """
            Simple market: A competitive setting with buyers and sellers.
            Demand elasticity: Sensitivity of quantity demanded to price.
            Consumer surplus: Net gain to buyers from trade.
        """.trimIndent()
        val report = ExamCoverage.evaluate(
            questions = questions,
            identified = listOf("Simple market", "Demand elasticity", "Consumer surplus"),
            bank = listOf(
                CoverageBankPoint("Simple market", choices = 2, essay = false),
                CoverageBankPoint("Demand elasticity", choices = 2, essay = false),
                CoverageBankPoint("Consumer surplus", choices = 0, essay = true),
            ),
            textbook = synthTextbook,
        )
        assertEquals(3, report.heldOutPoints)
        assertEquals(3, report.covered)
        assertEquals(listOf("Simple market", "Demand elasticity", "Consumer surplus"), report.coveredPoints)
        assertEquals(emptyList(), report.uncovered)
    }

    @Test
    fun partBoundaryAllowsQuestionNumberRestartWithoutCreatingPartPoint() {
        val exam = """
            Part I: Core Concepts
            1. Explain Demand Elasticity.
            2. Explain Consumer Surplus.

            Part II: Applied Analysis
            1. Discuss Price Ceiling effects.
        """.trimIndent()
        val questions = HeldOutDecomposer.questionsFromDocument(exam)
        assertEquals(3, questions.size)
        val decomposed = HeldOutDecomposer.decomposeQuestions(
            questions,
            identified = listOf("Demand elasticity", "Consumer surplus", "Price ceiling"),
            textbook = "",
        )
        assertEquals(listOf("Demand elasticity", "Consumer surplus", "Price ceiling"), decomposed.map { it.name })
    }

    @Test
    fun concatenatedCoverlessDualPaperSplitsBothPapersWhenNumberingRestartsAtOne() {
        val paperA = """
            1. Consider a simple market where buyers and sellers trade.
            2). What happens to equilibrium price?
            2. Explain Demand Elasticity for a normal good.
            3. Discuss Price Ceiling effects on quantity supplied.
        """.trimIndent()
        val paperB = """
            1. Define Consumer Surplus in competitive markets.
            2. Explain Moral Hazard in health insurance.
            3. Discuss Adverse Selection when risk is hidden.
        """.trimIndent()
        val identified = listOf(
            "Simple market", "Demand elasticity", "Price ceiling",
            "Consumer surplus", "Moral hazard", "Adverse selection",
        )
        val textbook = identified.joinToString("\n") { "$it: Synthetic definition." }
        val bank = identified.map { CoverageBankPoint(it, choices = 2, essay = false) }

        val forward = HeldOutDecomposer.questionsFromDocument("$paperA\n\n$paperB")
        val reversed = HeldOutDecomposer.questionsFromDocument("$paperB\n$paperA")
        assertEquals(6, forward.size)
        assertEquals(6, reversed.size)
        assertTrue(forward[0].contains("2). What happens to equilibrium price?"))

        val forwardReport = ExamCoverage.evaluate(forward, identified, bank, textbook)
        val reversedReport = ExamCoverage.evaluate(reversed, identified, bank, textbook)
        assertEquals(6, forwardReport.heldOutPoints)
        assertEquals(6, forwardReport.covered)
        assertEquals(6, reversedReport.heldOutPoints)
        assertEquals(6, reversedReport.covered)
        assertEquals(identified.toSet(), forwardReport.coveredPoints.toSet())
        assertEquals(identified.toSet(), reversedReport.coveredPoints.toSet())
    }

    @Test
    fun concatenatedFullDualPaperWithNewCoverSplitsBothPapersAndDropsBothCovers() {
        val fullPaperA = """
            SYNTHETIC UNIVERSITY
            ECON99999 Practice Midterm Examination
            Time Allowed: 60 Minutes
            Instructions to Candidates:
            1. Write your student ID on the booklet.
            2. Calculators are not permitted.

            Part I: Microeconomics
            1. Consider a simple market with linear demand.
            2. Explain Demand Elasticity for luxury goods.
            End of Examination
        """.trimIndent()
        val fullPaperB = """
            SYNTHETIC UNIVERSITY
            ECON99999 December 2025 Examination
            Time Allowed: 120 Minutes
            Instructions to Candidates:
            Answer all questions in blue or black ink.

            1. Discuss Moral Hazard in insurance contracts.
            2. Explain Adverse Selection in credit markets.
            3. Define Cost-Effectiveness Analysis in public health.
            Space for Rough Work
        """.trimIndent()
        val identified = listOf(
            "Simple market", "Demand elasticity",
            "Moral hazard", "Adverse selection", "Cost-Effectiveness Analysis",
        )
        val textbook = identified.joinToString("\n") { "$it: Synthetic definition." }
        val bank = identified.map { CoverageBankPoint(it, choices = 2, essay = false) }

        val separateQuestions = HeldOutDecomposer.questionsFromDocument(fullPaperA) +
            HeldOutDecomposer.questionsFromDocument(fullPaperB)
        val combinedQuestions = HeldOutDecomposer.questionsFromDocument("$fullPaperA\n\n$fullPaperB")
        val reversedQuestions = HeldOutDecomposer.questionsFromDocument("$fullPaperB\n\n$fullPaperA")

        assertEquals(5, separateQuestions.size)
        assertEquals(5, combinedQuestions.size)
        assertEquals(5, reversedQuestions.size)
        assertFalse(combinedQuestions.any { "SYNTHETIC UNIVERSITY" in it || "Instructions" in it || "Rough Work" in it || "End of Examination" in it })

        val combinedReport = ExamCoverage.evaluate(combinedQuestions, identified, bank, textbook)
        val reversedReport = ExamCoverage.evaluate(reversedQuestions, identified, bank, textbook)
        assertEquals(5, combinedReport.heldOutPoints)
        assertEquals(5, combinedReport.covered)
        assertEquals(5, reversedReport.heldOutPoints)
        assertEquals(5, reversedReport.covered)
        assertEquals(identified.toSet(), combinedReport.coveredPoints.toSet())
    }

    @Test
    fun stripsEndOfPaperInstructionsAndMatchesSpecificPopulatedBankPoint() {
        val paperWithTailNotes = """
            The University of Test
            ECON99999 December 2025 Examination
            Time Allowed: 2 hours
            Notes for Candidates:
            1. Write your examination number on the front cover.
            2. Calculators are not permitted in this examination.

            1. Discuss Moral Hazard in health insurance contracts.
            2. Explain Risk Aversion in household portfolio choice.

            Examinations Office
            The University of Test 2025
            Notes
            1. Check that this paper has 4 pages.
            2. You must remain seated until the invigilator collects your script.
            3. Write your candidate number on every answer booklet.
            4. Dictionaries are not permitted.
            5. Mobile phones must be switched off.
            6. Place bags at the front of the examination room.
            7. Rough work must be done in the answer booklet.
            8. Do not remove any paper from the hall.
            9. Stop writing immediately when told to stop.
            10. Hand in your separate answer sheet before leaving.
            11. Unfair means will be reported to the Board of Examiners.
            12. Use black ink or blue ballpoint pen only.
            13. Write legibly on both sides of each page.
            14. Read all instructions carefully before leaving.
        """.trimIndent()

        val questions = HeldOutDecomposer.questionsFromDocument(paperWithTailNotes)
        assertEquals(2, questions.size)
        assertFalse(questions.any { "Examinations Office" in it || "University" in it || "candidate number" in it })

        val identified = listOf("Moral", "Moral hazard", "Risk", "Risk aversion")
        val bank = listOf(
            // Shorter 0-question entries appear BEFORE the specific populated points.
            CoverageBankPoint("Moral", choices = 0, essay = false),
            CoverageBankPoint("Risk", choices = 0, essay = false),
            CoverageBankPoint("Moral hazard", choices = 3, essay = false),
            CoverageBankPoint("Risk aversion", choices = 2, essay = true),
        )
        val textbook = """
            Moral hazard: Hidden action after a contract is signed.
            Risk aversion: Preference for a certain payoff over a risky lottery.
        """.trimIndent()

        val report = ExamCoverage.evaluate(questions, identified, bank, textbook)
        assertEquals(2, report.heldOutPoints)
        assertEquals(2, report.covered)
        assertEquals(listOf("Moral hazard", "Risk aversion"), report.coveredPoints)
        assertEquals(emptyList(), report.uncovered)
    }

    @Test
    fun genericPointNamesAndCommonWordsNeverCountAsCovered() {
        val syntheticExam = """
            SYNTHETIC UNIVERSITY
            ECON99999 Synthetic Midterm Examination
            Time Allowed: 60 Minutes

            1. Discuss the empirical findings of the Northern Rural Policy Experiment.
            2. Explain how the Gamma model uses a production function to choose inputs.
            3. Discuss how Care and University factors interact in general practice.
            4. Explain the Policy Experiment in general terms.
            Point: Policy Experiment
            5. Explain Care in administrative notes.
            Point: Care
            6. Explain Demand Elasticity for a normal good.
        """.trimIndent()

        val questions = HeldOutDecomposer.questionsFromDocument(syntheticExam)
        assertEquals(6, questions.size)

        val textbook = """
            Northern Rural Policy Experiment: Regional subsidy trial altering clinic visits.
            Policy Experiment: General overview of regional policy trials.
            Alpha-beta function: Links input hours to total harvest.
            Function: General relationship between two quantities.
            Care: General notes on maintenance.
            University: Campus administration notes.
            Demand elasticity: Buyers respond to price changes.
        """.trimIndent()

        val identified = listOf(
            "Policy Experiment",
            "Alpha-beta function",
            "Function",
            "Care",
            "University",
            "Demand elasticity",
        )
        val bank = listOf(
            CoverageBankPoint("Policy Experiment", choices = 3, essay = true),
            CoverageBankPoint("Alpha-beta function", choices = 3, essay = true),
            CoverageBankPoint("Function", choices = 3, essay = true),
            CoverageBankPoint("Care", choices = 3, essay = true),
            CoverageBankPoint("University", choices = 3, essay = true),
            CoverageBankPoint("Demand elasticity", choices = 3, essay = false),
        )

        val report = ExamCoverage.evaluate(questions, identified, bank, textbook)
        assertEquals(6, report.heldOutPoints)
        assertEquals(1, report.covered)
        assertEquals(listOf("Demand elasticity"), report.coveredPoints)
        assertEquals(
            listOf(
                "Northern Rural Policy Experiment" to ExamCoverage.POINT_MISSED,
                "未识别考点 #1" to ExamCoverage.POINT_MISSED,
                "未识别考点 #2" to ExamCoverage.POINT_MISSED,
                "Policy Experiment" to ExamCoverage.POINT_MISSED,
                "Care" to ExamCoverage.POINT_MISSED,
            ),
            report.uncovered.map { it.name to it.reason },
        )
    }
}
