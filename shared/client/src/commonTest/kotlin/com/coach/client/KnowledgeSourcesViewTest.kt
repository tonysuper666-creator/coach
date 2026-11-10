package com.coach.client

import com.coach.domain.*
import com.coach.domain.reader.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class KnowledgeSourcesViewTest {
    private fun outline(seq: Long, point: JsonObject) = Event(seq, "outline-" + seq, "SYNTHETIC", "outline_revised",
        recordedAt = seq, occurredAt = seq, deviceId = "synthetic-device", actor = "SYSTEM",
        payload = json("knowledgePoints" to JsonArray(listOf(point))))
    private val old = json("id" to "K", "name" to "Synthetic topic", "sourceAnchor" to json("docId" to "legacy-book", "pdfPage" to 8, "bookPage" to 4))
    private val a = ClaimSourceAnchor("book", 4, KnowledgeSourceKind.TEXTBOOK, 6, "part-a")
    private val b = ClaimSourceAnchor("slides", 2, KnowledgeSourceKind.SLIDES, claimRef = "part-b")

    @Test fun oldRowStillOpensItsRegisteredSinglePage() {
        val events = listOf(outline(1, old))
        val kp = CourseView(events, project(events)).kp("K")!!
        assertEquals("legacy-book", kp.anchorDoc)
        assertEquals(8, kp.anchorPdfPage)
        assertEquals("教材 p.4", kp.reference)
        assertEquals(SourceAnchor("legacy-book", 8, 4), kp.sourceReferences.legacyAnchors.single())
    }

    @Test fun outlineReplayExposesPerClaimSourcesAndClearsTheOldPrimaryJump() {
        val events = listOf(outline(1, old), outline(2, writeKnowledgeSources(old, listOf(a, b))))
        val replayed = events.map { LedgerJson.decodeFromString<Event>(LedgerJson.encodeToString(it)) }
        val kp = CourseView(replayed, project(replayed)).kp("K")!!
        assertEquals(listOf(a, b), kp.sourceReferences.sourceAnchors)
        assertEquals(listOf(b), assertIs<KnowledgeSourceChoice.Candidates>(kp.sourceReferences.forClaim("part-b")).anchors)
        assertEquals("", kp.anchorDoc, "a multi-source point must not retain its old chapter jump")
        assertEquals(0, kp.anchorPdfPage)
        assertEquals("", kp.reference, "a range from one document must not be presented as all documents' support")
    }

    @Test fun malformedOrExplicitlyClearedNewSourcesCannotReviveThePreviousPage() {
        for (raw in listOf<JsonElement>(JsonArray(emptyList()), JsonPrimitive("bad"), JsonArray(listOf(json("pdfPage" to 3))))) {
            val revised = JsonObject(old + ("sourceAnchors" to raw))
            val events = listOf(outline(1, old), outline(2, revised))
            val kp = CourseView(events, project(events)).kp("K")!!
            assertEquals("", kp.anchorDoc)
            assertIs<KnowledgeSourceChoice.Unconfirmed>(kp.sourceReferences.forClaim("part-a"))
        }
    }
}
