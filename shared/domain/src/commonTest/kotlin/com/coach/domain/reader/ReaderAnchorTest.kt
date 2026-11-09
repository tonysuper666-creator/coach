package com.coach.domain.reader

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class ReaderAnchorTest {
    private val v1 = DocumentVersion("synthetic-book", "a".repeat(64), ReaderFormat.PDF)
    private val v2 = v1.copy(sha256 = "b".repeat(64))
    private val page = ReaderResource(pdfPageIndex0 = 0)
    private val frame = PdfPageFrame(PdfPointRect(0.0, 0.0, 600.0, 800.0))
    private val boxes = ReaderGeometry(frame, listOf(ReaderRect(0.1, 0.2, 0.3, 0.1)))
    private val anchor = ReaderAnchor("sentence-1", v1, page, TextQuoteSelector("The moon is round.", "Before. ", " After."),
        ReaderTextRange(8, 26), boxes)
    private val original = "Before. The moon is round. After."
    private fun pdf(version: DocumentVersion, vararg texts: String?) = ReaderDocumentIndex(version,
        texts.mapIndexed { i, text -> ReaderTextUnit(ReaderResource(pdfPageIndex0 = i), text) }, true, texts.size)

    @Test fun uniqueMigrationUsesNewPageAndNeverOldBoxes() {
        val result = ReaderAnchorResolver.resolve(anchor, pdf(v2, "New title", "New chapter", original))
        assertEquals(AnchorStatus.RESOLVED, result.status)
        assertEquals(AnchorReason.UNIQUE_EXACT_CONTEXT, result.reason)
        assertEquals(1, result.evidence.exactCandidates)
        assertEquals(ReaderResource(pdfPageIndex0 = 2), result.target?.resource)
        assertEquals(ReaderTextRange(8, 26), result.target?.range)
        assertNull(result.target?.geometry)
        assertEquals(boxes, ReaderAnchorResolver.resolve(anchor, pdf(v1, original)).target?.geometry)
        val currentBoxes = boxes.copy(boxes = listOf(ReaderRect(0.2, 0.6, 0.4, 0.1)))
        val index = pdf(v2, original).let { it.copy(units = listOf(it.units.single().copy(
            spans = listOf(ReaderTextGeometry(ReaderTextRange(8, 26), currentBoxes))))) }
        assertEquals(currentBoxes, ReaderAnchorResolver.resolve(anchor, index).target?.geometry)
    }

    @Test fun contextCanDisambiguateButOldPageCannotBreakATie() {
        val other = "Elsewhere. The moon is round. Finish."
        val resolved = ReaderAnchorResolver.resolve(anchor, pdf(v2, other, original))
        assertEquals(2, resolved.evidence.exactCandidates)
        assertEquals(1, resolved.evidence.contextCandidates)
        assertEquals(1, resolved.target?.resource?.pdfPageIndex0)
        for (version in listOf(v1, v2)) {
            val result = ReaderAnchorResolver.resolve(anchor, pdf(version, original, original))
            assertEquals(AnchorReason.AMBIGUOUS, result.reason)
            assertEquals(if (version == v1) AnchorStatus.UNRESOLVED else AnchorStatus.STALE, result.status)
            assertNull(result.target)
        }
        val noContext = anchor.copy(quote = TextQuoteSelector("The moon is round."))
        assertEquals(AnchorReason.AMBIGUOUS, ReaderAnchorResolver.resolve(noContext, pdf(v2, other, original)).reason)
    }

    @Test fun deletedChangedScannedOrPartialTextCannotHighlight() {
        val cases = listOf(
            pdf(v2, "Before. The moon was round. After.") to AnchorReason.QUOTE_NOT_FOUND,
            pdf(v2, "Different. The moon is round. Context.") to AnchorReason.CONTEXT_CHANGED,
            pdf(v2, null) to AnchorReason.NO_TEXT_LAYER,
            pdf(v2, original, null) to AnchorReason.NO_TEXT_LAYER,
            pdf(v2, original).copy(complete = false) to AnchorReason.INCOMPLETE_INDEX,
            pdf(v2.copy(documentId = "another-book"), original) to AnchorReason.DOCUMENT_MISMATCH
        )
        for ((index, reason) in cases) {
            val result = ReaderAnchorResolver.resolve(anchor, index)
            assertEquals(reason, result.reason)
            assertEquals(AnchorStatus.STALE, result.status)
            assertNull(result.target)
        }
        assertEquals(AnchorReason.MISSING_QUOTE, ReaderAnchorResolver.resolve(anchor.copy(quote = null), pdf(v1, original)).reason)
        val overlap = anchor.copy(quote = TextQuoteSelector("aba"))
        assertEquals(2, ReaderAnchorResolver.resolve(overlap, pdf(v2, "ababa")).evidence.exactCandidates)
    }

    @Test fun whitespaceNormalizationMapsBackToOriginalUtf16IncludingEmoji() {
        val raw = "😀\nPrefix\t the \n sentence.\u00a0Suffix"
        val source = anchor.copy(quote = TextQuoteSelector("the sentence.", "Prefix ", " Suffix"))
        val result = ReaderAnchorResolver.resolve(source, pdf(v2, raw))
        val range = assertNotNull(result.target).range
        assertEquals(raw.indexOf("the"), range.startUtf16)
        assertEquals(raw.indexOf(".\u00a0") + 1, range.endUtf16)
        assertEquals("the \n sentence.", raw.substring(range.startUtf16, range.endUtf16))
        assertEquals(AnchorReason.QUOTE_NOT_FOUND, ReaderAnchorResolver.resolve(source, pdf(v2, raw.uppercase())).reason)
    }

    @Test fun epubReflowKeepsIdentityAndMigrationDropsOldFragments() {
        val epub = v1.copy(format = ReaderFormat.EPUB)
        val quote = TextQuoteSelector("Quiet rivers flow.", "Here. ", " End.")
        val oldLocator = ReaderLocator("chapters/one.xhtml", locations = LocatorLocations(listOf("sentence-9"), 0.5, 4, 0.2),
            text = LocatorText(quote.exact, quote.prefix, quote.suffix))
        val source = ReaderAnchor("epub-sentence", epub, ReaderResource(href = oldLocator.href), quote, locator = oldLocator)
        val unit = ReaderTextUnit(source.resource, "Here. Quiet rivers flow. End.")
        val index = ReaderDocumentIndex(epub, listOf(unit), true)
        val initial = ReaderAnchorResolver.resolve(source, index)
        assertEquals(oldLocator, initial.target?.locator)
        // Font/viewport pagination is deliberately absent from the persisted model.
        assertEquals(initial, ReaderAnchorResolver.resolve(source, ReaderAnchorJson.decodeFromString(ReaderAnchorJson.encodeToString(index))))
        val moved = index.copy(version = epub.copy(sha256 = "c".repeat(64)), units = listOf(unit.copy(resource = ReaderResource(href = "parts/new.xhtml"))))
        val target = assertNotNull(ReaderAnchorResolver.resolve(source, moved).target)
        assertEquals("parts/new.xhtml", target.locator?.href)
        assertEquals(LocatorLocations(), target.locator?.locations)
        assertEquals(quote.exact, target.locator?.text?.highlight)
        assertEquals(source, ReaderAnchorJson.decodeFromString<ReaderAnchor>(ReaderAnchorJson.encodeToString(source)))
    }

    @Test fun verifiedPageMapHandlesRomanMissingRepeatedAndInclusiveRanges() {
        val map = VerifiedPageLabels(v1, 7, listOf(VerifiedPageLabel(0, "iv"), VerifiedPageLabel(2, "1"),
            VerifiedPageLabel(3, "3"), VerifiedPageLabel(4, "4"), VerifiedPageLabel(6, "1")), "synthetic-checked-map")
        assertEquals(1, map.forLabel("iv").pages.single().pdfPage1)
        assertEquals(PageLookupStatus.UNKNOWN, map.forLabel("2").status)
        assertEquals(PageLookupStatus.UNKNOWN, map.forPdfIndex0(1).status)
        assertEquals(PageLookupStatus.AMBIGUOUS, map.forLabel("1").status)
        assertEquals(PageLookupStatus.OUT_OF_RANGE, map.forPdfPage1(0).status)
        assertEquals(PageLookupStatus.OUT_OF_RANGE, map.forPdfIndex0(7).status)
        assertEquals(listOf("3", "4"), map.betweenLabels("3", "4").pages.map { it.label })
        assertEquals(PageLookupStatus.REVERSED_RANGE, map.betweenLabels("4", "iv").status)
        assertEquals(PageLookupStatus.UNKNOWN, map.betweenLabels("iv", "3").status)
        assertEquals(map, ReaderAnchorJson.decodeFromString<VerifiedPageLabels>(ReaderAnchorJson.encodeToString(map)))
    }

    @Test fun pdfCoordinatesRespectCropOriginAndAllRotations() {
        val cropped = PdfPageFrame(PdfPointRect(-10.0, -20.0, 610.0, 820.0), PdfPointRect(0.0, 0.0, 600.0, 800.0))
        val normalized = assertNotNull(cropped.normalize(PdfPointRect(60.0, 560.0, 240.0, 640.0)))
        assertRect(ReaderRect(0.1, 0.2, 0.3, 0.1), normalized)
        assertNull(cropped.normalize(PdfPointRect(700.0, 0.0, 710.0, 10.0)))
        val expected = listOf(ReaderRect(0.1, 0.2, 0.3, 0.1), ReaderRect(0.7, 0.1, 0.1, 0.3),
            ReaderRect(0.6, 0.7, 0.3, 0.1), ReaderRect(0.2, 0.6, 0.1, 0.3))
        listOf(0, 90, 180, 270).forEachIndexed { i, rotation ->
            assertRect(expected[i], ReaderGeometry(cropped.copy(rotationClockwise = rotation), listOf(normalized)).displayBoxes().single())
        }
        val trimmed = boxes.displayBoxes(ReaderRect(0.2, 0.1, 0.5, 0.5)).single()
        assertRect(ReaderRect(0.0, 0.2, 0.4, 0.2), trimmed)
        assertTrue(boxes.displayBoxes(ReaderRect(0.8, 0.8, 0.1, 0.1)).isEmpty())
    }

    @Test fun malformedWireValuesAreRejected() {
        assertFailsWith<IllegalArgumentException> { v1.copy(sha256 = "edition-2") }
        assertFailsWith<IllegalArgumentException> { ReaderResource(pdfPageIndex0 = 1, href = "chapter.xhtml") }
        assertFailsWith<IllegalArgumentException> { ReaderLocator("chapter.xhtml#screen=3") }
        assertFailsWith<IllegalArgumentException> { ReaderRect(Double.NaN, 0.0, 0.2, 0.2) }
        assertFailsWith<IllegalArgumentException> { PdfPageFrame(frame.mediaBox, rotationClockwise = 45) }
        assertFailsWith<IllegalArgumentException> { anchor.copy(schemaVersion = 2) }
        assertFailsWith<IllegalArgumentException> { ReaderDocumentIndex(v1, listOf(ReaderTextUnit(page, original)), true, 3) }
        val wire = ReaderAnchorJson.encodeToString(anchor)
        assertEquals(anchor, ReaderAnchorJson.decodeFromString<ReaderAnchor>(wire.dropLast(1) + ",\"futureOptional\":true}"))
        assertFailsWith<IllegalArgumentException> { ReaderAnchorJson.decodeFromString<ReaderAnchor>(wire.replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
    }

    private fun assertRect(expected: ReaderRect, actual: ReaderRect) {
        assertEquals(expected.x, actual.x, 1e-9); assertEquals(expected.y, actual.y, 1e-9)
        assertEquals(expected.width, actual.width, 1e-9); assertEquals(expected.height, actual.height, 1e-9)
    }
}
