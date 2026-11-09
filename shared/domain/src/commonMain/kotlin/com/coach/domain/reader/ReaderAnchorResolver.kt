package com.coach.domain.reader

/** Deterministic, local-only resolver. Old page numbers/boxes never break a tie. */
object ReaderAnchorResolver {
    fun resolve(anchor: ReaderAnchor, index: ReaderDocumentIndex): AnchorResolution {
        var evidence = AnchorEvidence()
        fun failed(reason: AnchorReason) = AnchorResolution(anchor.id, anchor.version, index.version,
            if (anchor.version == index.version) AnchorStatus.UNRESOLVED else AnchorStatus.STALE, reason, evidence)
        if (anchor.version.documentId != index.version.documentId) return failed(AnchorReason.DOCUMENT_MISMATCH)
        if (anchor.version.format != index.version.format) return failed(AnchorReason.FORMAT_MISMATCH)
        val quote = anchor.quote ?: return failed(AnchorReason.MISSING_QUOTE)
        if (!index.complete) return failed(AnchorReason.INCOMPLETE_INDEX)
        // A scanned/omitted resource could contain another copy, so uniqueness is not proven.
        if (index.units.any { it.text == null }) return failed(AnchorReason.NO_TEXT_LAYER)
        val exact = normalize(quote.exact).text
        val prefix = normalize(quote.prefix).text; val suffix = normalize(quote.suffix).text
        val candidates = mutableListOf<Pair<ReaderTextUnit, ReaderTextRange>>()
        var exactCount = 0
        for (unit in index.units) {
            val normalized = normalize(requireNotNull(unit.text))
            var at = normalized.text.indexOf(exact)
            while (at >= 0) {
                exactCount++
                val end = at + exact.length
                if (at >= prefix.length && normalized.text.startsWith(prefix, at - prefix.length) && normalized.text.startsWith(suffix, end)) {
                    candidates += unit to ReaderTextRange(normalized.starts[at], normalized.ends[end - 1])
                }
                at = normalized.text.indexOf(exact, at + 1) // includes overlapping occurrences
            }
        }
        evidence = AnchorEvidence(exactCount, candidates.size, index.units.size, true)
        if (exactCount == 0) return failed(AnchorReason.QUOTE_NOT_FOUND)
        if (candidates.isEmpty()) return failed(AnchorReason.CONTEXT_CHANGED)
        if (candidates.size != 1) return failed(AnchorReason.AMBIGUOUS)
        val (unit, range) = candidates.single()
        val geometry = unit.spans.singleOrNull { it.range == range }?.geometry
            ?: anchor.geometry?.takeIf { anchor.version == index.version && anchor.resource == unit.resource && anchor.originalRange == range }
        val locator = unit.resource.href?.let { href ->
            // Old fragments/progression are valid only in the exact same file/resource.
            val old = anchor.locator?.takeIf { anchor.version == index.version && it.href == href }
            ReaderLocator(href, old?.type ?: "application/xhtml+xml", old?.locations ?: LocatorLocations(),
                LocatorText(quote.exact, quote.prefix, quote.suffix))
        }
        val target = ReaderAnchorTarget(index.version, unit.resource, range, geometry, locator)
        return AnchorResolution(anchor.id, anchor.version, index.version, AnchorStatus.RESOLVED,
            if (prefix.isNotEmpty() || suffix.isNotEmpty()) AnchorReason.UNIQUE_EXACT_CONTEXT else AnchorReason.UNIQUE_EXACT,
            evidence, target)
    }

    private data class Normalized(val text: String, val starts: List<Int>, val ends: List<Int>)
    /** Collapse U+0009..000D, U+0020 and U+00A0; preserve edges/case/punctuation/all other code units. */
    private fun normalize(raw: String): Normalized {
        val text = StringBuilder(); val starts = mutableListOf<Int>(); val ends = mutableListOf<Int>()
        var wasSpace = false
        raw.forEachIndexed { i, ch ->
            val space = ch in '\t'..'\r' || ch == ' ' || ch == '\u00a0'
            if (space && wasSpace) ends[ends.lastIndex] = i + 1
            else { text.append(if (space) ' ' else ch); starts += i; ends += i + 1 }
            wasSpace = space
        }
        return Normalized(text.toString(), starts, ends)
    }
}
