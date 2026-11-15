package com.coach.domain

/**
 * The page text a textbook export actually carries, from its own PDF markers.
 *
 * A page starts at its marker and runs to the next marker: the marker stays inside the text and
 * nothing is trimmed, because that exact string is what the server stores in `source_pages` and
 * every question cites UTF-16 offsets inside it. Any producer or consumer of a marked export must
 * read pages through this one function, or the same file yields two different page contracts and
 * every block citation is refused as a mismatch.
 */
private val textbookPageMarker = Regex("<!--\\s*PDF 第 (\\d+) 页 · 书第 (.*?) 页\\s*-->")

fun textbookPagesFromMarkers(markdown: String): List<TextbookPage> {
    val markers = textbookPageMarker.findAll(markdown).toList()
    val pages = markers.mapIndexed { i, m ->
        TextbookPage(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull(),
            markdown.substring(m.range.first, markers.getOrNull(i + 1)?.range?.first ?: markdown.length))
    }
    require(pages.map { it.pdfPage }.distinct().size == pages.size && pages.all { it.pdfPage > 0 && it.text.isNotBlank() }) {
        "教材里的 PDF 页标记不完整或重复，无法确认页码。"
    }
    return pages
}
