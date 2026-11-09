package com.coach.domain.reader

/** Same-file capture identity is fixed; only a different edition may relocate it. */
object ReaderAnchorResolver {
    /** Capture only index-owned text and context. The caller may supply its own persistent ID. */
    fun capture(index: ReaderDocumentIndex, resource: ReaderResource, range: ReaderTextRange,
        id: String = kotlin.random.Random.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    ): ReaderAnchor {
        val unit = requireNotNull(index.units.singleOrNull { it.resource == resource }) { "Unknown capture resource" }
        val text = requireNotNull(unit.text) { "Capture resource has no usable text layer" }
        require(validRange(text, range)) { "Capture range is outside index text or splits a surrogate pair" }
        var before = maxOf(0, range.startUtf16 - 64)
        var after = range.endUtf16 + minOf(64, text.length - range.endUtf16)
        if (!boundary(text, before)) before--
        if (!boundary(text, after)) after++
        val quote = TextQuoteSelector(text.substring(range.startUtf16, range.endUtf16),
            text.substring(before, range.startUtf16), text.substring(range.endUtf16, after))
        val locator = resource.href?.let { ReaderLocator(it, text = LocatorText(quote.exact, quote.prefix, quote.suffix)) }
        val anchor = ReaderAnchor(id, index.version, resource, quote, range,
            unit.spans.singleOrNull { it.range == range }?.geometry, locator)
        val result = resolve(anchor, index)
        require(result.reason == AnchorReason.SAME_VERSION_RANGE && result.target?.resource == resource && result.target?.range == range) {
            "Capture failed to resolve to its original index range"
        }
        return anchor
    }

    fun resolve(anchor: ReaderAnchor, index: ReaderDocumentIndex): AnchorResolution {
        var evidence = AnchorEvidence()
        fun failed(reason: AnchorReason) = AnchorResolution(anchor.id, anchor.version, index.version,
            if (anchor.version == index.version) AnchorStatus.UNRESOLVED else AnchorStatus.STALE, reason, evidence)
        if (anchor.version.documentId != index.version.documentId) return failed(AnchorReason.DOCUMENT_MISMATCH)
        if (anchor.version.format != index.version.format) return failed(AnchorReason.FORMAT_MISMATCH)
        val quote = anchor.quote ?: return failed(AnchorReason.MISSING_QUOTE)
        val exact = normalize(quote.exact).text
        val prefix = normalize(quote.prefix).text; val suffix = normalize(quote.suffix).text
        val sameVersion = anchor.version == index.version
        val units = if (sameVersion) {
            val unit = index.units.singleOrNull { it.resource == anchor.resource }
            val scope = if (anchor.originalRange != null) AnchorSearchScope.CAPTURE_RANGE else AnchorSearchScope.CAPTURE_RESOURCE
            evidence = AnchorEvidence(searchedUnits = if (unit == null) 0 else 1, searchScope = scope)
            if (unit == null) return failed(AnchorReason.CAPTURE_MISMATCH)
            val text = unit.text ?: return failed(AnchorReason.NO_TEXT_LAYER)
            val range = anchor.originalRange
            if (range != null) {
                if (!validRange(text, range)) return failed(AnchorReason.CAPTURE_MISMATCH)
                val exactMatches = normalize(text.substring(range.startUtf16, range.endUtf16)).text == exact
                val contextMatches = normalize(text.substring(0, range.startUtf16)).text.endsWith(prefix) &&
                    normalize(text.substring(range.endUtf16)).text.startsWith(suffix)
                evidence = evidence.copy(exactCandidates = if (exactMatches) 1 else 0,
                    contextCandidates = if (exactMatches && contextMatches) 1 else 0)
                if (!exactMatches || !contextMatches) return failed(AnchorReason.CAPTURE_MISMATCH)
                return resolved(anchor, index, unit, range, AnchorReason.SAME_VERSION_RANGE, evidence)
            }
            listOf(unit) // A legacy anchor without offsets may search only its captured resource.
        } else {
            if (!index.complete) return failed(AnchorReason.INCOMPLETE_INDEX)
            // In a different edition, a scanned resource could conceal another copy.
            if (index.units.any { it.text == null }) return failed(AnchorReason.NO_TEXT_LAYER)
            index.units
        }
        val candidates = mutableListOf<Pair<ReaderTextUnit, ReaderTextRange>>()
        var exactCount = 0
        for (unit in units) {
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
        evidence = AnchorEvidence(exactCount, candidates.size, units.size, !sameVersion,
            searchScope = if (sameVersion) AnchorSearchScope.CAPTURE_RESOURCE else AnchorSearchScope.DOCUMENT)
        if (exactCount == 0) return failed(if (sameVersion) AnchorReason.CAPTURE_MISMATCH else AnchorReason.QUOTE_NOT_FOUND)
        if (candidates.isEmpty()) return failed(AnchorReason.CONTEXT_CHANGED)
        if (candidates.size != 1) return failed(AnchorReason.AMBIGUOUS)
        val (unit, range) = candidates.single()
        return resolved(anchor, index, unit, range,
            if (prefix.isNotEmpty() || suffix.isNotEmpty()) AnchorReason.UNIQUE_EXACT_CONTEXT else AnchorReason.UNIQUE_EXACT, evidence)
    }

    private fun resolved(anchor: ReaderAnchor, index: ReaderDocumentIndex, unit: ReaderTextUnit,
        range: ReaderTextRange, reason: AnchorReason, evidence: AnchorEvidence
    ): AnchorResolution {
        val quote = requireNotNull(anchor.quote)
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
            reason, evidence, target)
    }

    private fun boundary(text: String, at: Int) = at == 0 || at == text.length ||
        !(text[at - 1].isHighSurrogate() && text[at].isLowSurrogate())
    private fun validRange(text: String, range: ReaderTextRange) = range.endUtf16 <= text.length &&
        boundary(text, range.startUtf16) && boundary(text, range.endUtf16)

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
