package com.coach.desktop.books

import java.net.URI
import java.nio.file.Path

/** Read inline image destinations without rewriting the Markdown used for citations. */
internal object MarkdownBookImages {
    private data class Image(val end: Int, val destination: String, val caption: String)

    fun attach(raw: String, book: ExtractedBook, directory: Path, control: BookImportControl = BookImportControl()): ExtractedBook {
        val placements = mutableListOf<FormatBookImages.Placement>()
        var total = 0L
        var index = 0
        var fence = ""
        while (index < raw.length) {
            control.check()
            if (index == 0 || raw[index - 1] == '\n') {
                val end = raw.indexOf('\n', index).let { if (it < 0) raw.length else it }
                val line = raw.substring(index, end).removeSuffix("\r")
                val trimmed = line.trimStart(' ')
                val indent = line.length - trimmed.length
                val run = trimmed.takeWhile { it == '`' || it == '~' }
                val marker = run.takeWhile { it == run.firstOrNull() }
                if (indent <= 3 && marker.length >= 3) {
                    if (fence.isEmpty()) fence = marker
                    else if (marker.first() == fence.first() && marker.length >= fence.length &&
                        trimmed.drop(marker.length).isBlank()) fence = ""
                    index = minOf(end + 1, raw.length)
                    continue
                }
                if (fence.isNotEmpty() || indent >= 4 || line.startsWith('\t')) {
                    index = minOf(end + 1, raw.length)
                    continue
                }
            }
            when {
                raw[index] == '\\' -> index = minOf(index + 2, raw.length)
                raw[index] == '`' -> {
                    val size = backticks(raw, index)
                    var close = index + size
                    while (close < raw.length) {
                        close = raw.indexOf('`', close)
                        if (close < 0) break
                        val run = backticks(raw, close)
                        if (run == size) break
                        close += run
                    }
                    index = if (close >= 0 && close < raw.length) close + size else index + size
                }
                raw.startsWith("![", index) -> {
                    val image = parse(raw, index)
                    if (image == null) { index++; continue }
                    // Preserve existing percent escapes while accepting spaces in <destinations>.
                    val destination = image.destination.replace(" ", "%20")
                    val uri = runCatching { URI(destination) }.getOrNull()
                    if (uri != null && !uri.isAbsolute && uri.rawAuthority == null) {
                        FormatBookImages.htmlAsset(destination, directory)?.let { bytes ->
                            total += bytes.size
                            require(placements.size < FormatBookImages.MAX_IMAGES && total <= FormatBookImages.MAX_TOTAL) {
                                "这份材料插图过多或超过 64 MB，请分册导入。"
                            }
                            placements += FormatBookImages.Placement(index, bytes, image.caption)
                        }
                    }
                    index = image.end
                }
                else -> index++
            }
        }
        return book.copy(illustrations = project(raw, book.pages, placements))
    }

    private fun backticks(text: String, start: Int): Int {
        var end = start
        while (text.getOrNull(end) == '`') end++
        return end - start
    }

    private fun project(raw: String, pages: List<BookPage>, placements: List<FormatBookImages.Placement>): List<ExtractedIllustration> {
        if (placements.isEmpty()) return emptyList()
        data class Span(val page: BookPage, val start: Int, val end: Int)
        val cleaned = TextbookExtraction.clean(raw)
        var cursor = 0
        val spans = pages.map { page ->
            val text = TextbookExtraction.clean(page.text)
            val start = cleaned.indexOf(text, cursor)
            check(start >= 0) { "无法确认插图对应的正文位置。" }
            cursor = start + text.length
            Span(page, start, cursor)
        }
        var rawCursor = 0
        var normalized = 0
        // Placements are in source order. Page text may preserve CRLF in marked books.
        return placements.map { placement ->
            while (rawCursor < placement.offset) {
                val c = raw[rawCursor]
                if (c != '\u0000' && !(c == '\n' && rawCursor > 0 && raw[rawCursor - 1] == '\r')) normalized++
                rawCursor++
            }
            val span = spans.firstOrNull { normalized < it.end } ?: spans.last()
            val local = (normalized - span.start).coerceIn(0, span.end - span.start)
            var index = 0
            var count = 0
            while (index < span.page.text.length && count < local) {
                val c = span.page.text[index]
                if (c != '\u0000' && !(c == '\n' && index > 0 && span.page.text[index - 1] == '\r')) count++
                index++
            }
            if (index > 0 && span.page.text.getOrNull(index) == '\n' && span.page.text[index - 1] == '\r') index++
            FormatBookImages.image(placement.bytes, span.page.number,
                BookIllustrationAnchors.snapToValidUtf16Boundary(span.page.text, index), placement.caption)
        }
    }

    private fun parse(text: String, start: Int): Image? {
        var i = start + 2
        val label = StringBuilder()
        var depth = 1
        while (i < text.length && depth > 0) {
            val c = text[i++]
            if (c == '\\' && i < text.length && text[i] in punctuation) label.append(text[i++])
            else {
                if (c == '[') depth++
                if (c == ']') depth--
                if (depth > 0) label.append(c)
            }
        }
        if (depth != 0 || i >= text.length || text[i++] != '(') return null
        while (i < text.length && text[i].isWhitespace()) i++
        val destination = StringBuilder()
        val angled = i < text.length && text[i] == '<'
        if (angled) i++
        depth = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && text[i + 1] in punctuation) {
                destination.append(text[i + 1]); i += 2; continue
            }
            if (angled) {
                if (c == '>') { i++; break }
                if (c == '\n' || c == '\r' || c == '<') return null
            } else {
                if ((c == ')' && depth == 0) || c.isWhitespace()) break
                if (c == '(') { if (++depth > 32) return null }
                if (c == ')') depth--
            }
            destination.append(c); i++
        }
        if (angled && (i == 0 || text.getOrNull(i - 1) != '>')) return null
        if (depth != 0 || destination.isEmpty()) return null
        val whitespace = i < text.length && text[i].isWhitespace()
        while (i < text.length && text[i].isWhitespace()) i++
        var caption = label.toString()
        if (whitespace && i < text.length && text[i] in "\"'(") {
            val end = if (text[i] == '(') ')' else text[i]
            i++
            val title = StringBuilder()
            while (i < text.length && text[i] != end) {
                if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in punctuation) i++
                title.append(text[i++])
            }
            if (i >= text.length) return null
            i++
            if (title.isNotBlank()) caption = title.toString()
            while (i < text.length && text[i].isWhitespace()) i++
        }
        if (text.getOrNull(i) != ')') return null
        return Image(i + 1, destination.toString(), caption)
    }

    private const val punctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
}
