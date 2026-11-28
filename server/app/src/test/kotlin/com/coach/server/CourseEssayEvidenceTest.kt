package com.coach.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CourseEssayEvidenceTest {
    private val passage = """
        • Using the Workshop Budget
        FIGURE 2.2 Workshop tools: A view of the room.
        The workshop budget framework shows how a fixed allocation
        limits the combinations of supplies the team can buy.
        The workshop budget framework also explains why
    """.trimIndent()
    private val page = CourseEssayFramework.Page("manual", "Manual", "CORE_TEXTBOOK", 19, passage, 1)
    private val point = CourseEssayFramework.extract("Workshop budget", listOf(page)).single()

    @Test
    fun lineWrapsAreKeptInTheAnchorAndRemovedFromTheStatement() {
        assertEquals("workshop budget framework", point.name)
        assertEquals("manual", point.bookId)
        assertEquals(19, point.page)
        assertTrue(page.text.contains(point.quoteAnchor))
        assertTrue(point.quoteAnchor.contains('\n'))
        assertEquals(
            "The workshop budget framework shows how a fixed allocation limits the combinations of supplies the team can buy.",
            point.statement,
        )
    }

    @Test
    fun abbreviatedFrameworkNameProducesVerbatimEvidence() {
        val answer = "Our workshop\nbudget covers one drill or two saws."
        val row = CourseEssayFramework.judge(listOf(point), answer).single()
        assertEquals(CourseEssayFramework.Usage.USED, row.usage)
        assertEquals("workshop\nbudget", row.studentQuote)
    }

    @Test
    fun blindEvidenceSearchAcceptsAnActualParaphraseAndRejectsInventedEvidence() {
        val answer = "With only twelve dollars we can get the drill or both saws, but never all three."
        val unmatched = CourseEssayFramework.judge(listOf(point), answer)
        assertEquals(CourseEssayFramework.Usage.MISSING, unmatched.single().usage)
        val proof = "we can get the drill or both saws, but never all three"
        fun response(quote: String) = """{"items":[{"id":"${point.id}","correct":true}],
            "evidence":[{"index":0,"quote":"$quote","correct":true,"gap":"和材料一致"}]}"""
        val result = CourseEssayFramework.parseVotes(response(proof), unmatched, answer)
        assertEquals(proof, result.getValue(point.id).studentQuote)
        assertTrue(CourseEssayFramework.parseVotes(response("a different sentence"), unmatched, answer).isEmpty())
    }
}
