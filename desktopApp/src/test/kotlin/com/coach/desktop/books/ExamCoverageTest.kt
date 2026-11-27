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
}
