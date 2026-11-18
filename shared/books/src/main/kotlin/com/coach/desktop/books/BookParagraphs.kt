package com.coach.desktop.books

/** All ranges are unchanged UTF-16 offsets into the stored page. End is exclusive. */
internal data class BookParagraph(
    val start: Int,
    val end: Int,
    val kind: Kind,
    val chinese: Boolean,
    val afterHeading: Boolean,
) {
    enum class Kind { BODY, HEADING, SPECIAL }

    val indentEm: Int get() = when {
        kind != Kind.BODY -> 0
        chinese -> 2
        afterHeading -> 0
        else -> 1
    }
}

internal data class BookParagraphs(val display: String, val paragraphs: List<BookParagraph>)

/** Recognize blocks without editing stored text; display-only whitespace keeps every source offset. */
internal fun bookParagraphs(source: String, pdf: Boolean): BookParagraphs {
    data class Line(val start: Int, val end: Int, val text: String, val type: String?)
    val lines = mutableListOf<Line>()
    var start = 0
    var fence: String? = null
    var math = false
    while (start < source.length) {
        val newline = source.indexOf('\n', start).let { if (it < 0) source.length else it }
        val end = if (newline > start && source[newline - 1] == '\r') newline - 1 else newline
        val text = source.substring(start, end)
        val trimmed = text.trim()
        val marker = Regex("^(`{3,}|~{3,})").find(trimmed)?.value
        val type = when {
            fence != null || marker != null -> "code"
            math || trimmed == "$$" -> "math"
            trimmed.isEmpty() -> null
            text.startsWith("    ") || text.startsWith("\t") -> "code"
            Regex("^#{1,6}\\s+\\S").containsMatchIn(trimmed) -> "heading"
            trimmed.startsWith(">") -> "quote"
            trimmed.startsWith("|") -> "table"
            Regex("^(?:[-+*]\\s+|\\d+[.)、]\\s+|[（(]\\d+[）)]\\s*)").containsMatchIn(trimmed) -> "list"
            Regex("^(?:图|表)\\s*[0-9一二三四五六七八九十]+[.．:：、\\s]").containsMatchIn(trimmed) -> "caption"
            trimmed.startsWith("$") && trimmed.endsWith("$") -> "math"
            else -> "body"
        }
        lines += Line(start, end, text, type)
        if (marker != null) {
            if (fence == null) fence = marker
            else if (marker.first() == fence.first() && marker.length >= fence.length) fence = null
        }
        if (fence == null && trimmed == "$$") math = !math
        start = newline + 1
    }

    val display = source.toCharArray()
    val paragraphs = mutableListOf<BookParagraph>()
    var previousKind: BookParagraph.Kind? = null
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.type == null) { i++; continue }
        val kind = when (line.type) {
            "body" -> BookParagraph.Kind.BODY
            "heading" -> BookParagraph.Kind.HEADING
            else -> BookParagraph.Kind.SPECIAL
        }
        var last = i
        if (kind == BookParagraph.Kind.SPECIAL) {
            // Preserve all line breaks inside a list, table, quotation, formula or code block.
            while (last + 1 < lines.size && lines[last + 1].type == line.type) last++
        } else if (pdf && kind == BookParagraph.Kind.BODY) {
            while (last + 1 < lines.size && lines[last + 1].type == "body") {
                val left = lines[last].text.codePoints().toArray().lastOrNull()
                val right = lines[last + 1].text.codePoints().toArray().firstOrNull()
                val replacement = when {
                    left != null && right != null && isHan(left) && isHan(right) -> '\u200b'
                    left != null && right != null && Character.isLetter(left) && Character.isLetter(right) && !isHan(left) && !isHan(right) -> ' '
                    else -> null
                } ?: break
                // CRLF occupies two source offsets, both retained.
                for (offset in lines[last].end until lines[last + 1].start) display[offset] = '\u200b'
                display[lines[last + 1].start - 1] = replacement
                last++
            }
        }
        val content = source.substring(line.start, lines[last].end).codePoints().toArray()
        val han = content.count(::isHan)
        val letters = content.count { Character.isLetter(it) }
        paragraphs += BookParagraph(line.start, lines[last].end, kind, han > letters - han,
            previousKind == BookParagraph.Kind.HEADING)
        previousKind = kind
        i = last + 1
    }
    // ParagraphStyle creates the break itself. A literal LF at its boundary creates an
    // extra empty line in Skia; collapse separators, retaining every source offset.
    var offset = 0
    for (paragraph in paragraphs) {
        for (index in offset until paragraph.start) display[index] = '\u200b'
        offset = paragraph.end
    }
    for (index in offset until display.size) display[index] = '\u200b'
    return BookParagraphs(String(display), paragraphs)
}

private fun isHan(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
