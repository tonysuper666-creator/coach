package com.coach.server

import com.coach.protocol.BookStance
import com.coach.protocol.BookStanceGuard
import com.coach.store.SourcePage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HighRiskBookDecisionReviewTest {
    @Test fun stockQuestionKeepsTheLiteraryReadingOnly() {
        // A fictional emblem and a short, wholly synthetic source for a high-risk stock question.
        val quote = "云舟入港是航程结束的意象。"
        val pack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("c".repeat(64) + ":p1:0", 1, quote))),
            BookStance.KNOWLEDGE,
        )
        val gloss = "书中用云舟入港表示航程结束。"
        val decision = "书中把云舟入港解释为买航运股的信号。"
        val payload = """{"paragraphs":[{"layer":"book","sentences":[{"text":"$gloss","anchors":["S1"],"quote":"$quote"},{"text":"$decision","anchors":["S1"],"quote":"$quote"}]}],"boundary":null}"""
        val result = assertNotNull(checkedStancePayload(payload, pack, BookStance.KNOWLEDGE,
            guards = setOf(BookStanceGuard.ESOTERIC), forceHighRisk = true))
        val kept = result.layers.filterIsInstance<CheckedStanceLayer.Book>().flatMap { it.sentences }
        assertEquals(1, kept.size, "only the literary reading has source support")
        assertEquals(gloss, kept.single().text)
        assertEquals(1, result.droppedSentences)
    }
}
