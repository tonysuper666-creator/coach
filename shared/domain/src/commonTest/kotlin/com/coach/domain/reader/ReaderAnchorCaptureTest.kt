package com.coach.domain.reader

import kotlin.test.*

/** Regression assertions from Architect's P1-P4 probes, plus capture boundary cases. */
class ReaderAnchorCaptureTest {
    private val version = DocumentVersion("synthetic-book", "a".repeat(64), ReaderFormat.PDF)
    private fun page(n: Int) = ReaderResource(pdfPageIndex0 = n)
    private fun pdf(vararg texts: String?) = ReaderDocumentIndex(version,
        texts.mapIndexed { n, text -> ReaderTextUnit(page(n), text) }, true, texts.size)

    @Test fun p1MismatchedNativePrefixNeverRelocatesWithinTheSameFile() {
        val index = pdf("Defiﬁned. Water boils. End.", "Defined. Water boils. Other.")
        val wrong = ReaderAnchor("p1", version, page(0), TextQuoteSelector("Water boils.", "Defined. "), ReaderTextRange(10, 22))
        val failed = ReaderAnchorResolver.resolve(wrong, index)
        assertEquals(AnchorStatus.UNRESOLVED, failed.status)
        assertEquals(AnchorReason.CAPTURE_MISMATCH, failed.reason)
        assertNull(failed.target)
        val captured = ReaderAnchorResolver.capture(index, page(0), ReaderTextRange(10, 22), "canonical-p1")
        assertEquals("Defiﬁned. ", captured.quote?.prefix)
        assertEquals(page(0), ReaderAnchorResolver.resolve(captured, index).target?.resource)
    }

    @Test fun p2ScannedCoverDoesNotBlockACapturedTextPage() {
        val index = pdf(null, "Water boils. End.")
        val source = ReaderAnchorResolver.capture(index, page(1), ReaderTextRange(0, 12), "p2")
        for (available in listOf(index, index.copy(complete = false))) {
            val result = ReaderAnchorResolver.resolve(source, available)
            assertEquals(AnchorReason.SAME_VERSION_RANGE, result.reason)
            assertEquals(page(1), result.target?.resource)
            assertEquals(AnchorSearchScope.CAPTURE_RANGE, result.evidence.searchScope)
            assertFalse(result.evidence.searchComplete) // does not claim full-document uniqueness
        }
        assertEquals(AnchorReason.NO_TEXT_LAYER, ReaderAnchorResolver.resolve(source.copy(resource = page(0)), index).reason)
    }

    @Test fun p3CapturedRangeDistinguishesRepeatedSentencesOnTheSameOrOtherPages() {
        val index = pdf("如图所示。", "如图所示。如图所示。")
        val source = ReaderAnchorResolver.capture(index, page(1), ReaderTextRange(5, 10), "p3")
        val result = ReaderAnchorResolver.resolve(source, index)
        assertEquals(AnchorReason.SAME_VERSION_RANGE, result.reason)
        assertEquals(page(1), result.target?.resource)
        assertEquals(ReaderTextRange(5, 10), result.target?.range)
        assertEquals(AnchorReason.AMBIGUOUS, ReaderAnchorResolver.resolve(source.copy(originalRange = null,
            quote = TextQuoteSelector("如图所示。")), index).reason)
    }

    @Test fun p4CrossPagePrefixIsRejectedAndSharedCaptureUsesOnlyLocalContext() {
        val index = pdf("Text at end of page.", "Water boils. More.")
        val wrong = ReaderAnchor("p4", version, page(1), TextQuoteSelector("Water boils.", "end of page. "), ReaderTextRange(0, 12))
        assertEquals(AnchorReason.CAPTURE_MISMATCH, ReaderAnchorResolver.resolve(wrong, index).reason)
        assertNull(ReaderAnchorResolver.resolve(wrong, index).target)
        val captured = ReaderAnchorResolver.capture(index, page(1), ReaderTextRange(0, 12), "canonical-p4")
        assertEquals("", captured.quote?.prefix)
        assertEquals(" More.", captured.quote?.suffix)
        assertEquals(AnchorReason.SAME_VERSION_RANGE, ReaderAnchorResolver.resolve(captured, index).reason)
    }

    @Test fun legacyAnchorWithoutRangeCannotFindItsQuoteOnAnotherPageOrEpubResource() {
        val source = ReaderAnchor("legacy", version, page(0), TextQuoteSelector("Water boils."))
        val result = ReaderAnchorResolver.resolve(source, pdf("Other sentence.", "Water boils."))
        assertEquals(AnchorReason.CAPTURE_MISMATCH, result.reason)
        assertNull(result.target)
        val epub = version.copy(format = ReaderFormat.EPUB)
        val one = ReaderResource(href = "one.xhtml"); val two = ReaderResource(href = "two.xhtml")
        val index = ReaderDocumentIndex(epub, listOf(ReaderTextUnit(one, "Other sentence."), ReaderTextUnit(two, "Water boils.")), true)
        assertNull(ReaderAnchorResolver.resolve(source.copy(version = epub, resource = one), index).target)
        val onItsPage = ReaderAnchorResolver.resolve(source.copy(resource = page(1)), pdf(null, "Water boils."))
        assertEquals(page(1), onItsPage.target?.resource)
        assertEquals(AnchorSearchScope.CAPTURE_RESOURCE, onItsPage.evidence.searchScope)
    }

    @Test fun invalidOrChangedCapturedRangeFailsEvenIfAnotherOccurrenceMatches() {
        val index = pdf("Start. Water boils.", "Water boils.")
        for (range in listOf(ReaderTextRange(0, 12), ReaderTextRange(100, 112))) {
            val source = ReaderAnchor("bad-range", version, page(0), TextQuoteSelector("Water boils."), range)
            val result = ReaderAnchorResolver.resolve(source, index)
            assertEquals(AnchorReason.CAPTURE_MISMATCH, result.reason)
            assertNull(result.target)
        }
        val missing = ReaderAnchor("missing", version, page(2), TextQuoteSelector("Water boils."), ReaderTextRange(0, 12))
        assertEquals(AnchorReason.CAPTURE_MISMATCH, ReaderAnchorResolver.resolve(missing, index).reason)
    }

    @Test fun captureRejectsUnavailableTextBlankSelectionsAndSplitSurrogates() {
        val index = pdf(null, "😀 Water boils. ")
        assertFailsWith<IllegalArgumentException> { ReaderAnchorResolver.capture(index, page(0), ReaderTextRange(0, 1)) }
        assertFailsWith<IllegalArgumentException> { ReaderAnchorResolver.capture(index, page(2), ReaderTextRange(0, 1)) }
        assertFailsWith<IllegalArgumentException> { ReaderAnchorResolver.capture(index, page(1), ReaderTextRange(0, 100)) }
        assertFailsWith<IllegalArgumentException> { ReaderAnchorResolver.capture(index, page(1), ReaderTextRange(1, 14)) }
        assertFailsWith<IllegalArgumentException> { ReaderAnchorResolver.capture(index, page(1), ReaderTextRange(2, 3)) }
        val text = "😀" + "a".repeat(63) + "target." + "b".repeat(63) + "😀"
        val source = ReaderAnchorResolver.capture(pdf(text), page(0), ReaderTextRange(65, 72), "surrogate-context")
        assertTrue(requireNotNull(source.quote).prefix.startsWith("😀"))
        assertTrue(source.quote.suffix.endsWith("😀"))
    }
}
