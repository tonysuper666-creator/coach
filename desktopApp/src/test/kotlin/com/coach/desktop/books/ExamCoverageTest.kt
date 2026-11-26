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
}
