package com.coach.domain.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire format shared with native clients; do not use platform string indices implicitly. */
val ReaderAnchorJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

@Serializable enum class ReaderFormat { PDF, EPUB }

/** documentId survives editions; sha256 identifies the exact original file bytes. */
@Serializable data class DocumentVersion(val documentId: String, val sha256: String, val format: ReaderFormat) {
    init {
        require(documentId.isNotBlank())
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

@Serializable data class TextQuoteSelector(
    val exact: String, val prefix: String = "", val suffix: String = "",
    val type: String = "TextQuoteSelector"
) {
    init { require(exact.isNotBlank()); require(type == "TextQuoteSelector") }
}

/** PDF physical index is zero based. EPUB href identifies a resource, never a screen page. */
@Serializable data class ReaderResource(val pdfPageIndex0: Int? = null, val href: String? = null) {
    init {
        require((pdfPageIndex0 == null) != (href == null))
        require(pdfPageIndex0 == null || pdfPageIndex0 >= 0)
        require(href == null || (href.isNotBlank() && '#' !in href))
    }
}

/** Offsets into THIS version's index unit text, never a native extractor's string: UTF-16, half-open. */
@Serializable data class ReaderTextRange(val startUtf16: Int, val endUtf16: Int) {
    init { require(startUtf16 >= 0 && endUtf16 > startUtf16) }
}

@Serializable data class LocatorLocations(
    val fragments: List<String> = emptyList(), val progression: Double? = null,
    val position: Int? = null, val totalProgression: Double? = null
) {
    init {
        require(progression == null || (progression.isFinite() && progression in 0.0..1.0))
        require(totalProgression == null || (totalProgression.isFinite() && totalProgression in 0.0..1.0))
        require(position == null || position > 0)
    }
}
@Serializable data class LocatorText(val highlight: String, val before: String = "", val after: String = "")
@Serializable data class ReaderLocator(
    val href: String, val type: String = "application/xhtml+xml",
    val locations: LocatorLocations = LocatorLocations(), val text: LocatorText? = null
) {
    init { require(href.isNotBlank() && '#' !in href); require(type.isNotBlank()) }
}

@Serializable data class ReaderAnchor(
    val id: String, val version: DocumentVersion, val resource: ReaderResource,
    val quote: TextQuoteSelector? = null, val originalRange: ReaderTextRange? = null,
    val geometry: ReaderGeometry? = null, val locator: ReaderLocator? = null,
    val schemaVersion: Int = 1
) {
    init {
        require(id.isNotBlank() && schemaVersion == 1)
        require((version.format == ReaderFormat.PDF) == (resource.pdfPageIndex0 != null))
        require(geometry == null || version.format == ReaderFormat.PDF)
        require(locator == null || (version.format == ReaderFormat.EPUB && locator.href == resource.href))
    }
}

/** A span's boxes MUST cover exactly this text range, not its whole paragraph or page. */
@Serializable data class ReaderTextGeometry(val range: ReaderTextRange, val geometry: ReaderGeometry)
@Serializable data class ReaderTextUnit(
    val resource: ReaderResource,
    val text: String?, // null = no usable text layer; empty = a known empty text layer
    val spans: List<ReaderTextGeometry> = emptyList()
) {
    init {
        require(spans.isEmpty() || (resource.pdfPageIndex0 != null && text != null))
        require(spans.all { it.range.endUtf16 <= (text?.length ?: 0) })
        require(spans.map { it.range }.distinct().size == spans.size)
    }
}

/** Import adapters declare completeness; partial indexes can never prove a unique match. */
@Serializable data class ReaderDocumentIndex(
    val version: DocumentVersion, val units: List<ReaderTextUnit>, val complete: Boolean,
    val pdfPageCount: Int? = null, val pageLabels: VerifiedPageLabels? = null
) {
    init {
        require(units.map { it.resource }.distinct().size == units.size)
        if (version.format == ReaderFormat.PDF) {
            require(pdfPageCount != null && pdfPageCount > 0)
            require(units.all { it.resource.pdfPageIndex0 != null && it.resource.pdfPageIndex0 < pdfPageCount })
            require(!complete || units.size == pdfPageCount)
            require(pageLabels == null || (pageLabels.version == version && pageLabels.pdfPageCount == pdfPageCount))
        } else {
            require(pdfPageCount == null && pageLabels == null)
            require(units.all { it.resource.href != null })
        }
    }
}

@Serializable enum class AnchorStatus { RESOLVED, STALE, UNRESOLVED }
@Serializable enum class AnchorReason {
    SAME_VERSION_RANGE, CAPTURE_MISMATCH, UNIQUE_EXACT, UNIQUE_EXACT_CONTEXT, DOCUMENT_MISMATCH, FORMAT_MISMATCH,
    INCOMPLETE_INDEX, NO_TEXT_LAYER, MISSING_QUOTE, QUOTE_NOT_FOUND, CONTEXT_CHANGED, AMBIGUOUS
}
@Serializable enum class AnchorSearchScope { NONE, CAPTURE_RANGE, CAPTURE_RESOURCE, DOCUMENT }
/** Confidence is a reproducible proof category, not an invented probability. */
@Serializable data class AnchorEvidence(
    val exactCandidates: Int = 0, val contextCandidates: Int = 0,
    val searchedUnits: Int = 0, val searchComplete: Boolean = false,
    val normalization: String = "reader-whitespace-v1", val searchScope: AnchorSearchScope = AnchorSearchScope.NONE
)
@Serializable data class ReaderAnchorTarget(
    val version: DocumentVersion, val resource: ReaderResource, val range: ReaderTextRange,
    val geometry: ReaderGeometry? = null, val locator: ReaderLocator? = null
)
@Serializable data class AnchorResolution(
    val anchorId: String, val capturedVersion: DocumentVersion, val targetVersion: DocumentVersion,
    val status: AnchorStatus, val reason: AnchorReason, val evidence: AnchorEvidence,
    val target: ReaderAnchorTarget? = null
) {
    init {
        require((status == AnchorStatus.RESOLVED) == (target != null))
        require(target == null || target.version == targetVersion)
    }
}

/** Note/progress remain attached to the captured edition even when a migration is unresolved. */
@Serializable data class ReaderAnchorEntry(
    val anchor: ReaderAnchor, val note: String = "", val progress: Double? = null, val revision: Long = 0
) {
    init { require(revision >= 0); require(progress == null || (progress.isFinite() && progress in 0.0..1.0)) }
}
