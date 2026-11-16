package com.coach.desktop.books

import java.security.MessageDigest

/** Text provenance only. Nested ranges overlap; they are never permission to hide source text. */
internal data class EpubTextRange(val page: Int, val start: Int, val end: Int)

internal data class EpubElementSource(
    /** Index in the complete OPF spine, including entries with linear="no". */
    val spineIndex: Int,
    val member: String,
    val memberSha256: String,
    /** Element-child indices, starting at the document element (empty path). Matches native_payload.py. */
    val nodePath: List<Int>,
    val namespace: String,
    val localName: String,
    /** Includes generated heading markers/descendant separators, but excludes own outer separators. */
    val ranges: List<EpubTextRange>,
)

internal data class EpubSourceMapping(val status: Status, val elements: List<EpubElementSource>) {
    enum class Status { MAPPED, STALE_TEXT }
}

/** Captured during the existing extractor's traversal, before clean/trim/paginate. No text search. */
internal class EpubTextTrace {
    private data class ElementSpan(val path: List<Int>, val namespace: String, val name: String, val start: Int, val end: Int)
    private val spans = mutableListOf<ElementSpan>()

    fun record(path: List<Int>, namespace: String, name: String, start: Int, end: Int) {
        spans += ElementSpan(path, namespace, name, start, end)
    }

    fun project(raw: String, pages: List<BookPage>, spineIndex: Int, member: String, bytes: ByteArray, pageBase: Int): List<EpubElementSource> {
        val cleaned = TextbookExtraction.clean(raw)
        val trimmed = cleaned.trim()
        val trimStart = cleaned.length - cleaned.trimStart().length
        // Reuse pagination, including empty form-feed sections. Every page consumes the next exact
        // slice, so identical paragraphs/chapters cannot be assigned to an earlier occurrence.
        data class PageSpan(val page: Int, val start: Int, val end: Int)
        val pageSpans = mutableListOf<PageSpan>()
        var cursor = 0
        trimmed.split('\u000c').forEachIndexed { sectionIndex, section ->
            if (sectionIndex > 0) cursor++
            for (page in TextbookExtraction.paginate(section)) {
                pageSpans += PageSpan(pageSpans.size + 1 + pageBase, cursor, cursor + page.text.length)
                cursor += page.text.length
            }
        }
        check(cursor == trimmed.length && pageSpans.size == pages.size)
        check(pageSpans.zip(pages).all { (span, page) -> trimmed.substring(span.start, span.end) == page.text })

        // Only keep requested endpoints, rather than an IntArray for every UTF-16 unit of a chapter.
        var rawCursor = 0
        var normalized = 0
        val positions = spans.flatMap { listOf(it.start, it.end) }.distinct().sorted().associateWith { target ->
            while (rawCursor < target) {
                val c = raw[rawCursor]
                if (c != '\u0000' && !(c == '\n' && rawCursor > 0 && raw[rawCursor - 1] == '\r')) normalized++
                rawCursor++
            }
            (normalized - trimStart).coerceIn(0, trimmed.length)
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return spans.map { span ->
            val start = positions.getValue(span.start)
            val end = positions.getValue(span.end)
            val ranges = mutableListOf<EpubTextRange>()
            var low = 0
            var high = pageSpans.size
            while (low < high) {
                val middle = (low + high) / 2
                if (pageSpans[middle].end <= start) low = middle + 1 else high = middle
            }
            var pageIndex = low
            while (pageIndex < pageSpans.size && pageSpans[pageIndex].start < end) {
                val page = pageSpans[pageIndex++]
                val from = maxOf(start, page.start)
                val to = minOf(end, page.end)
                if (from < to) ranges += EpubTextRange(page.page, from - page.start, to - page.start)
            }
            EpubElementSource(spineIndex, member, hash, span.path, span.namespace, span.name, ranges)
        }
    }
}
