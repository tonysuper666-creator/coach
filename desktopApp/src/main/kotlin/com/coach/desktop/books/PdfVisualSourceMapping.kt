package com.coach.desktop.books

import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderTextRange

internal enum class PdfVisualMappingStatus { MAPPED, DISCONTIGUOUS, AMBIGUOUS, NO_TEXT, STALE_TEXT, UNSAFE_GEOMETRY }

/** Exact source spans for a crop, using the production stripper's canonical UTF-16 offsets.
 * This describes text coverage only: crop visibility and reading order still need verification
 * by the extracting caller before it supplies a replacement interval to BookIllustration.
 */
internal data class PdfVisualSourceMapping(
    val status: PdfVisualMappingStatus,
    val ranges: List<ReaderTextRange> = emptyList(),
) {
    val singleRange: ReaderTextRange? get() = ranges.singleOrNull()?.takeIf { status == PdfVisualMappingStatus.MAPPED }

    companion object {
        fun map(source: PdfPageText, canonicalText: String, region: ReaderRect): PdfVisualSourceMapping {
            if (source.unit.text != canonicalText) return PdfVisualSourceMapping(PdfVisualMappingStatus.STALE_TEXT)
            // The current production geometry can clamp glyphs to CropBox. Do not certify
            // rotated pages or pages with missing glyph coverage for source replacement.
            if (source.frame.rotationClockwise != 0 || source.unmappedRanges.isNotEmpty() ||
                source.unit.spans.any { it.geometry.frame != source.frame }) {
                return PdfVisualSourceMapping(PdfVisualMappingStatus.UNSAFE_GEOMETRY)
            }
            fun intersects(a: ReaderRect, b: ReaderRect) =
                a.x < b.x + b.width && b.x < a.x + a.width &&
                    a.y < b.y + b.height && b.y < a.y + a.height
            fun contains(a: ReaderRect, b: ReaderRect) =
                a.x <= b.x && a.y <= b.y && a.x + a.width >= b.x + b.width && a.y + a.height >= b.y + b.height
            val selected = mutableListOf<ReaderTextRange>()
            val outside = mutableListOf<ReaderTextRange>()
            var ambiguous = false
            for (glyph in source.unit.spans) {
                val hit = glyph.geometry.boxes.any { intersects(region, it) }
                if (!hit) { outside += glyph.range; continue }
                val range = glyph.range
                if (!glyph.geometry.boxes.all { contains(region, it) } ||
                    range.endUtf16 > canonicalText.length ||
                    !BookIllustrationAnchors.isValidAnchor(canonicalText, range.startUtf16) ||
                    !BookIllustrationAnchors.isValidAnchor(canonicalText, range.endUtf16)) {
                    ambiguous = true
                } else selected += range
            }
            // One source run with geometry both inside and outside cannot be hidden safely.
            if (selected.any { s -> outside.any { it.startUtf16 < s.endUtf16 && s.startUtf16 < it.endUtf16 } }) ambiguous = true
            val joined = mutableListOf<ReaderTextRange>()
            for (range in selected.sortedBy { it.startUtf16 }) {
                val previous = joined.lastOrNull()
                if (previous != null && (range.startUtf16 <= previous.endUtf16 ||
                        canonicalText.substring(previous.endUtf16, range.startUtf16).all { it.isWhitespace() })) {
                    joined[joined.lastIndex] = ReaderTextRange(previous.startUtf16, maxOf(previous.endUtf16, range.endUtf16))
                } else joined += range
            }
            return PdfVisualSourceMapping(when {
                ambiguous -> PdfVisualMappingStatus.AMBIGUOUS
                joined.isEmpty() -> PdfVisualMappingStatus.NO_TEXT
                joined.size > 1 -> PdfVisualMappingStatus.DISCONTIGUOUS
                else -> PdfVisualMappingStatus.MAPPED
            }, joined)
        }
    }
}
