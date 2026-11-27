package com.coach.desktop.books

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterOpenSourceTest {
    @Test
    fun longChapterSamplesBothEndsAndSendsOriginalPageCoordinates() {
        val prefix = "🌊 Chapter heading\r\n\r\n"
        val body = "The lamp pulses.\r\nA dial records it. ".repeat(30)
        val page = prefix + body
        val blocks = (1..60).map { index ->
            SourceBlock("block-$index", index, prefix.length, page.length, false, body.replace("\r\n", "\n"))
        }
        val wire = ChapterOpenJson.buildGenerateRequest("book", "chapter", blocks) { page }
        val chosen = wire.getValue("blocks").jsonArray.map { it.jsonObject }
        assertEquals(12, chosen.size)
        assertEquals("1", chosen.first().getValue("page").jsonPrimitive.content)
        assertEquals("60", chosen.last().getValue("page").jsonPrimitive.content)
        assertTrue(chosen.sumOf { it.getValue("text").jsonPrimitive.content.length } <= 8_000)
        for (block in chosen) {
            val start = block.getValue("startUtf16").jsonPrimitive.content.toInt()
            assertEquals(prefix.length, start)
            assertTrue(page.startsWith(block.getValue("text").jsonPrimitive.content, start))
        }
    }

    @Test
    fun retryGradesOnlyTheSelectedSentenceWhenTwoPointsShareAnAnchor() {
        val quote = "The dial moves"
        val page = "$quote slowly when hot. $quote quickly when cold."
        val first = ChapterOpenPoint("P1", "Heat slows the dial", "b1", 12, quote, 0, quote.length)
        val start = page.lastIndexOf(quote)
        val second = first.copy(id = "P2", claim = "Cold speeds it up", startUtf16 = start, endUtf16 = start + quote.length)
        val request = ChapterOpenJson.buildGradeRequest("book", "chapter", ChapterOpenQuestion("Why?", listOf(first, second)),
            second.claim, listOf(second.id)) { page }
        val criterion = request.getValue("rubricCriteria").jsonArray.single().jsonObject
        assertEquals("P2", criterion.getValue("cid").jsonPrimitive.content)
        assertEquals("$quote quickly when cold.", criterion.getValue("sourceExcerpt").jsonPrimitive.content)
        assertEquals(criterion["sourceExcerpt"], request.getValue("sourcePages").jsonArray.single().jsonObject["text"])
    }

    @Test
    fun savedBlockRelativeQuoteInSecondParagraphResolvesOnOriginalPage() {
        val prefix = "🌊 lamp pulses.\r\n\r\n"
        val raw = "The dial turns.\r\nA lamp pulses."
        val quote = "turns.\nA lamp"
        val block = SourceBlock("b2", 63, prefix.length, (prefix + raw).length, false, raw.replace("\r\n", "\n"))
        val local = block.text.indexOf(quote)
        val question = ChapterOpenQuestion("Why?", listOf(ChapterOpenPoint("P1", "Explain the signal", "b2", 63,
            quote, local, local + quote.length)))
        val page = prefix + raw
        val fixed = ChapterOpenJson.run { question.withPageOffsets(listOf(block)) { page } }
        val point = fixed.points.single()
        assertEquals(page.indexOf("turns."), point.startUtf16)
        assertEquals("turns.\r\nA lamp", page.substring(point.startUtf16, point.endUtf16))
        assertEquals(page.substring(point.startUtf16, point.endUtf16), point.quoteAnchor)
        assertEquals(fixed, ChapterOpenJson.run { fixed.withPageOffsets(listOf(block)) { page } })
    }
}
