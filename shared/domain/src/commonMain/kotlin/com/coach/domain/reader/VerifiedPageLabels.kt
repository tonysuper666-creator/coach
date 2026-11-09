package com.coach.domain.reader

import kotlinx.serialization.Serializable

@Serializable data class VerifiedPageLabel(val pdfPageIndex0: Int, val label: String) {
    init { require(pdfPageIndex0 >= 0 && pdfPageIndex0 < Int.MAX_VALUE); require(label.isNotBlank()) }
    val pdfPage1: Int get() = pdfPageIndex0 + 1
}
@Serializable enum class PageLookupStatus { FOUND, UNKNOWN, AMBIGUOUS, OUT_OF_RANGE, REVERSED_RANGE }
@Serializable data class PageLookup(val status: PageLookupStatus, val pages: List<VerifiedPageLabel> = emptyList())

/** Only labels actually checked for THIS file. Missing entries stay unknown; labels are opaque strings. */
@Serializable data class VerifiedPageLabels(
    val version: DocumentVersion, val pdfPageCount: Int, val entries: List<VerifiedPageLabel>,
    val verificationId: String
) {
    init {
        require(version.format == ReaderFormat.PDF && pdfPageCount > 0 && verificationId.isNotBlank())
        require(entries.all { it.pdfPageIndex0 < pdfPageCount })
        require(entries.map { it.pdfPageIndex0 }.distinct().size == entries.size)
    }
    fun forPdfIndex0(index: Int): PageLookup {
        if (index !in 0 until pdfPageCount) return PageLookup(PageLookupStatus.OUT_OF_RANGE)
        val page = entries.singleOrNull { it.pdfPageIndex0 == index }
        return if (page == null) PageLookup(PageLookupStatus.UNKNOWN) else PageLookup(PageLookupStatus.FOUND, listOf(page))
    }
    fun forPdfPage1(page: Int): PageLookup = if (page <= 0) PageLookup(PageLookupStatus.OUT_OF_RANGE) else forPdfIndex0(page - 1)
    fun forLabel(label: String): PageLookup {
        val matches = entries.filter { it.label == label }.sortedBy { it.pdfPageIndex0 }
        return PageLookup(when (matches.size) { 0 -> PageLookupStatus.UNKNOWN; 1 -> PageLookupStatus.FOUND; else -> PageLookupStatus.AMBIGUOUS }, matches)
    }
    /** Inclusive physical range between two uniquely mapped labels. Never fill missing labels by arithmetic. */
    fun betweenLabels(first: String, last: String): PageLookup {
        val start = forLabel(first); val end = forLabel(last)
        if (start.status != PageLookupStatus.FOUND) return start
        if (end.status != PageLookupStatus.FOUND) return end
        val a = start.pages.single().pdfPageIndex0; val b = end.pages.single().pdfPageIndex0
        if (a > b) return PageLookup(PageLookupStatus.REVERSED_RANGE)
        val pages = entries.filter { it.pdfPageIndex0 in a..b }.sortedBy { it.pdfPageIndex0 }
        return PageLookup(if (pages.size == b - a + 1) PageLookupStatus.FOUND else PageLookupStatus.UNKNOWN, pages)
    }
}
