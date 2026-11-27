package com.coach.desktop.books

/**
 * The same citation gate as the one-page literature guide (TON-1712 first cut):
 * the ref must land on that paper, the stored offsets must still equal the local sentence,
 * the sentence must be body text, and an independent OCR pass must match at similarity >= 0.98.
 * The model is not allowed to supply the sentence text.
 */
internal object LiteratureCitationCheck {
    fun check(
        pool: List<GuideExcerpt>,
        ref: Int,
        expectedBookId: String?,
        ocr: LiteratureOcrVerifier,
    ): Pair<GuideExcerpt?, String> {
        if (ref !in pool.indices) return null to "ref_out_of_range"
        val excerpt = pool[ref]
        if (expectedBookId != null && excerpt.book.id != expectedBookId) return null to "ref_wrong_book"
        val anchor = excerpt.anchor
        val pageText = excerpt.book.page(anchor.pdfPage)?.text
        if (pageText == null || anchor.startUtf16 < 0 || anchor.endUtf16 > pageText.length ||
            pageText.substring(anchor.startUtf16, anchor.endUtf16) != anchor.quoteAnchor
        ) {
            return null to "offset_mismatch"
        }
        if (LiteraturePassages.isFootnoteSentence(anchor.quoteAnchor) ||
            LiteraturePassages.followsFootnoteMarker(pageText, anchor.startUtf16)
        ) return null to LiteraturePassages.FOOTNOTE_REJECTION
        if (!LiteraturePassages.bodySentence(anchor.quoteAnchor)) return null to "not_body_sentence"
        val ocrText = try {
            ocr.ocrPageText(excerpt.book, anchor.pdfPage)
        } catch (e: Exception) {
            return null to "ocr_unavailable"
        }
        var similarity = LiteratureTextNorm.bestContiguousSimilarity(anchor.quoteAnchor, ocrText)
        if (similarity < LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD) {
            val crop = runCatching { ocr.ocrQuoteCropText(excerpt.book, anchor.pdfPage, anchor.quoteAnchor) }.getOrNull()
            if (!crop.isNullOrBlank()) {
                similarity = maxOf(similarity, LiteratureTextNorm.bestContiguousSimilarity(anchor.quoteAnchor, crop))
            }
        }
        return if (similarity >= LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD) {
            excerpt to "ok"
        } else {
            null to "ocr_similarity_low"
        }
    }
}
