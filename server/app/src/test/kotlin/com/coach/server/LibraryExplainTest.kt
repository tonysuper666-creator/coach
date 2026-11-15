package com.coach.server

import com.coach.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LibraryExplainTest {
    private fun blocks(vararg entries: Pair<String, String>) = JsonArray(entries.map { json("id" to it.first, "text" to it.second) })
    private fun request(selection: String, blocks: JsonArray) = json("requestId" to "ab12", "kind" to "explain", "selection" to selection, "blocks" to blocks)
    private val id = "0123abcd:p12:340"

    @Test fun blocksBecomeAnchorablePagesCarryingTheirIds() {
        val parsed = libraryExplainRequest(request("price elasticity", blocks(id to "Synthetic price   elasticity is a ratio.", "0123abcd:p12:900" to "Next synthetic block.")))
        assertEquals(listOf(id, "0123abcd:p12:900"), parsed.pages.map { it.docId })
        assertEquals(12, parsed.pages.first().pdfPage)
        assertEquals("price elasticity", parsed.quote)
    }

    @Test fun malformedRequestsAreRefused() {
        val ok = blocks(id to "Synthetic text here.")
        for (bad in listOf(
            request("", ok), request("not in the blocks", ok), request("Synthetic", blocks("not-an-id" to "Synthetic")),
            request("Synthetic", blocks(id to "Synthetic", id to "Synthetic")), request("Synthetic", JsonArray(emptyList())),
            request("Synthetic", blocks(*Array(5) { "0123abcd:p1:$it" to "Synthetic" })),
            request("Synthetic", blocks(id to "x".repeat(8_001))),
            json("selection" to "Synthetic"),
        )) assertEquals(HttpStatusCodeBad, assertFailsWith<MockError> { libraryExplainRequest(bad) }.status.value)
    }
    private val HttpStatusCodeBad = 400
}
