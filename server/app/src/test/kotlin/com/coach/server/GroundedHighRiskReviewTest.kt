package com.coach.server

import com.coach.protocol.BookStance
import com.coach.protocol.BookStanceGuard
import com.coach.store.SourcePage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GroundedHighRiskReviewTest {
    private val quote = "青鸟是本章用来解释吉凶的一种意象。"
    private val pack = StanceSourcePack.fromSourcePack(
        SourcePack(listOf(SourcePage("d".repeat(64) + ":p1:0", 1, quote))),
        BookStance.ESOTERIC,
    )

    private fun retained(text: String): Int {
        val payload = """{"paragraphs":[{"layer":"book","sentences":[{"text":"$text","anchors":["S1"],"quote":"$quote"}]}],"boundary":"high_risk"}"""
        val result = assertNotNull(checkedStancePayload(payload, pack, BookStance.ESOTERIC,
            guards = setOf(BookStanceGuard.ESOTERIC), forceHighRisk = true))
        return result.layers.filterIsInstance<CheckedStanceLayer.Book>().sumOf { it.sentences.size }
    }

    @Test fun groundedSymbolGlossRemains() {
        assertEquals(1, retained("本章将青鸟作为说明吉凶的意象。"))
    }

    @Test fun appendedPersonalFinancialInstructionIsDropped() {
        assertEquals(0, retained("本章将青鸟写作意象，明早把全部积蓄换成黄金。"))
    }
}
