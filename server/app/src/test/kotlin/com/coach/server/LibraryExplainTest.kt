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
        assertEquals(listOf(id), parsed.pages.map { it.docId })
        assertEquals("price elasticity", parsed.pages.single().text)
        assertTrue(parsed.scope.permits(id, 12, true))
        assertFalse(parsed.scope.permits(id, 13, true))
        assertFalse(parsed.scope.permits("0123abcd:p12:900", 12, true))
        assertFalse(parsed.scope.permits(id, 12, false))
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
            request("Synthetic", blocks("0123abcd:p0:0" to "Synthetic")),
            request("Synthetic", blocks(id to "Synthetic", "other:p1:0" to "Synthetic")),
            request("Synthetic", blocks("sealed:p1:0" to "Synthetic")),
            json("selection" to "Synthetic"),
        )) assertEquals(HttpStatusCodeBad, assertFailsWith<MockError> { libraryExplainRequest(bad) }.status.value)
    }
    @Test fun callerVisibilityAndScopeCannotPromoteNeighboursOrOtherPages() {
        val input = request("Synthetic selected", blocks(
            id to "Unread prefix. Synthetic selected. Unread suffix.",
            "0123abcd:p13:0" to "Synthetic not yet read.",
        )) + mapOf("scope" to json("allowsInternalReference" to true), "learnerVisible" to JsonPrimitive(true))
        val parsed = libraryExplainRequest(JsonObject(input))
        assertEquals("Synthetic selected", parsed.pages.single().text)
        assertFalse(parsed.scope.permits("0123abcd:p13:0", 13, true))
        val pack = SourcePack.prepare("", parsed.pages)
        assertTrue(pack.containsSelection(parsed.quote))
        assertFalse(pack.render().contains("Unread"))
        assertFalse(pack.render().contains("not yet read"))
        assertNotNull(checkedReaderPayload("""{"paragraphs":[{"text":"有依据的解释。","anchors":["S1"]}]}""", pack))
    }

    private val HttpStatusCodeBad = 400
}
