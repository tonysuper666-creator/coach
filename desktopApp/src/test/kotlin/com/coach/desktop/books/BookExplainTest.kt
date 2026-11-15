package com.coach.desktop.books

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class BookExplainTest {
    private val book = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private fun block(page: Int, start: Int, text: String, heading: Boolean = false) = SourceBlock("$book:p$page:$start", page, start, start + text.length, heading, text)
    private val blocks = listOf(block(3, 0, "Chapter heading", true), block(3, 20, "Synthetic first paragraph."), block(3, 60, "Synthetic second paragraph."), block(4, 0, "Synthetic third paragraph."))

    @Test fun requestCarriesTheChosenBlockAndItsNeighboursWithBlockIds() {
        val chosen = blocks[1]
        val context = ExplainWire.context(blocks, chosen)
        assertEquals(listOf(blocks[1].id, blocks[2].id), context.map { it.id }, "A heading is not context")
        val request = ExplainWire.request("ab12", chosen, context)
        assertEquals("Synthetic first paragraph.", request["selection"]!!.jsonPrimitive.content)
        assertEquals(listOf(blocks[1].id, blocks[2].id), request["blocks"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(listOf(blocks[1].id, blocks[2].id, blocks[3].id), ExplainWire.context(blocks, blocks[2]).map { it.id })
    }

    @Test fun replyNeedsAVerifiedBlockReferenceToBeAnExplanation() {
        val sent = ExplainWire.context(blocks, blocks[1])
        val good = buildJsonObject {
            put("text", "这是讲解。【S1】"); put("elapsedMs", 1234)
            put("sources", buildJsonArray { add(buildJsonObject { put("anchor", "S1"); put("docId", blocks[1].id); put("pdfPage", 3) }) })
        }
        val done = assertIs<ExplainResult.Done>(ExplainWire.parse(good, sent)).explanation
        assertEquals(blocks[1].id, done.citations.single().blockId)
        assertEquals(3, done.citations.single().page)
        val refused = buildJsonObject { put("text", "追问未完成，暂时无法确认。"); put("sources", JsonArray(emptyList())) }
        assertIs<ExplainResult.Refused>(ExplainWire.parse(refused, sent))
        val foreign = buildJsonObject {
            put("text", "编造。【S1】")
            put("sources", buildJsonArray { add(buildJsonObject { put("anchor", "S1"); put("docId", "$book:p9:9"); put("pdfPage", 9) }) })
        }
        assertIs<ExplainResult.Refused>(ExplainWire.parse(foreign, sent), "A block that was never sent cannot be cited")
    }

    @Test fun consentIsPerBookAndNothingIsAllowedByDefault() {
        val consent = ExplainConsent(Files.createTempDirectory("coach-consent-"))
        assertFalse(consent.allowed(book))
        consent.allow(book)
        assertTrue(consent.allowed(book))
        assertFalse(consent.allowed("another"))
        consent.allow("../escape")
        assertFalse(consent.allowed("../escape"))
    }
}
