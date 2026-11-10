package com.coach.domain.reader

import com.coach.domain.KnowledgePoint
import com.coach.domain.LedgerJson
import com.coach.domain.SourceAnchor
import com.coach.domain.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class KnowledgeSourcesTest {
    private val definition = ClaimSourceAnchor("invented-book", 4, KnowledgeSourceKind.TEXTBOOK, claimRef = "definition", bookPage = 2)
    private val comparison = ClaimSourceAnchor("invented-slides", 7, KnowledgeSourceKind.SLIDES, 9, "comparison", "Compare the two invented cases")

    @Test fun oldSingleAnchorStillDecodesAndDoesNotAcquireClaimEvidence() {
        val raw = """{"id":"K","moduleId":"M","order":1,"title":"Invented point","sourceAnchor":{"docId":"textbook:7","pdfPage":42,"bookPage":11}}"""
        val point = LedgerJson.decodeFromString<KnowledgePoint>(raw)
        assertEquals(SourceAnchor("textbook:7", 42, 11), point.sourceAnchor)
        assertNull(point.sourceAnchors)
        val again = LedgerJson.decodeFromString<KnowledgePoint>(LedgerJson.encodeToString(point))
        assertEquals(point, again)
        val refs = readKnowledgeSources(LedgerJson.parseToJsonElement(raw).jsonObject)
        assertEquals(point.sourceAnchor, refs.legacyAnchors.single())
        assertEquals(KnowledgeSourceChoice.Unconfirmed(KnowledgeSourceProblem.LEGACY_PAGE_ONLY), refs.forClaim("comparison"))
        assertFalse(LedgerJson.encodeToString(KnowledgePoint("K", "M", 1, "Old point")).contains("sourceAnchors"))
    }

    @Test fun typedKnowledgePointRoundTripPreservesDocumentsRangesAndClaimBindings() {
        val point = KnowledgePoint("K", "M", 1, "Invented point", sourceAnchors = listOf(definition, comparison),
            sourceAnchor = SourceAnchor("old-chapter", 1))
        val encoded = LedgerJson.encodeToString(point)
        assertEquals(point, LedgerJson.decodeFromString<KnowledgePoint>(encoded))
        val refs = readKnowledgeSources(LedgerJson.parseToJsonElement(encoded).jsonObject)
        assertEquals(listOf(definition, comparison), refs.sourceAnchors)
        assertEquals(listOf(comparison), assertIs<KnowledgeSourceChoice.Candidates>(refs.forClaim("comparison")).anchors)
        assertEquals(listOf(comparison), assertIs<KnowledgeSourceChoice.Candidates>(refs.forClaim(supportedSubpoint = comparison.supportedSubpoint)).anchors)
        assertIs<KnowledgeSourceChoice.Unconfirmed>(refs.forClaim("definition", comparison.supportedSubpoint))
    }

    @Test fun rawWriterPreservesOldAliasAndUnrelatedFieldsWithoutManufacturingAPrimaryPage() {
        val old = json("id" to "K", "name" to "Invented name", "custom" to json("keep" to true),
            "sourceAnchor" to json("docId" to "chapter", "pdfPage" to 1))
        val written = writeKnowledgeSources(old, listOf(definition, comparison))
        for ((key, value) in old) assertEquals(value, written[key])
        assertEquals(listOf(definition, comparison), readKnowledgeSources(written).sourceAnchors)
        val fresh = writeKnowledgeSources(json("id" to "K"), listOf(definition, comparison))
        assertNull(fresh["sourceAnchor"], "writing a multi-source list must never invent its first page as a primary alias")
    }

    @Test fun explicitEmptyListSurvivesTypedRoundTripAndCannotResurrectLegacyChapter() {
        val point = KnowledgePoint("K", "M", 1, "Invented point", sourceAnchors = emptyList(), sourceAnchor = SourceAnchor("old", 1))
        val encoded = LedgerJson.encodeToString(point)
        assertNotNull(LedgerJson.decodeFromString<KnowledgePoint>(encoded).sourceAnchors)
        val refs = readKnowledgeSources(LedgerJson.parseToJsonElement(encoded).jsonObject)
        assertTrue(refs.structured)
        assertEquals(KnowledgeSourceChoice.Unconfirmed(KnowledgeSourceProblem.CLAIM_NOT_MAPPED), refs.forClaim("definition"))
    }

    @Test fun malformedNewEntriesDoNotFallBackOrSilentlyDisappear() {
        val valid = LedgerJson.encodeToJsonElement(definition).jsonObject
        val invalid = listOf(
            JsonObject(valid - "docId"), JsonObject(valid - "claimRef"),
            JsonObject(valid + ("docId" to JsonPrimitive(""))),
            JsonObject(valid + ("pdfPage" to JsonPrimitive(0))),
            JsonObject(valid + ("pdfPageEnd" to JsonPrimitive(3))),
            JsonObject(valid + ("sourceKind" to JsonPrimitive("guessed"))),
            JsonObject(valid + ("claimRef" to JsonPrimitive(" "))),
        )
        for (bad in invalid) {
            val refs = readKnowledgeSources(json("sourceAnchors" to JsonArray(listOf(valid, bad)),
                "sourceAnchor" to json("docId" to "old", "pdfPage" to 1)))
            assertEquals(KnowledgeSourceProblem.INVALID_ANCHOR, refs.issues.single().problem)
            assertIs<KnowledgeSourceChoice.Unconfirmed>(refs.forClaim("definition"))
        }
        val malformed = readKnowledgeSources(json("sourceAnchors" to json("docId" to "not-an-array")))
        assertEquals(KnowledgeSourceProblem.MALFORMED_SOURCES, malformed.issues.single().problem)
    }

    @Test fun noncontiguousPagesForOneClaimStaySeparateAndNeverBecomeTheEarliestPage() {
        val later = definition.copy(pdfPage = 11, pdfPageEnd = 12)
        val otherDoc = definition.copy(docId = "other-book", pdfPage = 2, pdfPageEnd = 2)
        val refs = readKnowledgeSources(writeKnowledgeSources(json("id" to "K"), listOf(later, otherDoc, definition)))
        assertEquals(listOf(later, otherDoc, definition), assertIs<KnowledgeSourceChoice.Candidates>(refs.forClaim("definition")).anchors)
        assertIs<KnowledgeSourceChoice.Unconfirmed>(refs.forClaim())
        assertIs<KnowledgeSourceChoice.Unconfirmed>(refs.forClaim("unknown-subpoint"))
    }
}
