package com.coach.desktop.books

/** Offsets are UTF-16 indices into both the source and [BookParagraphs.display]. */
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

/** Recognize display blocks without changing source characters or their UTF-16 positions. */
internal fun bookParagraphs(source: String, pdf: Boolean): BookParagraphs {
    data class Line(val start: Int, val end: Int, val text: String, val kind: BookParagraph.Kind?)
    val lines = mutableListOf<Line>()
    var start = 0
    var inCode = false
    while (start < source.length) {
        val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
        val text = source.substring(start, end).trimEnd('\r')
        val trimmed = text.trim()
        val fence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
        val kind = when {
            fence || inCode -> BookParagraph.Kind.SPECIAL
            trimmed.isEmpty() -> null
            text.startsWith("    ") || text.startsWith("\t") -> BookParagraph.Kind.SPECIAL
            Regex("^#{1,6}\\s+\\S").containsMatchIn(trimmed) -> BookParagraph.Kind.HEADING
            isSpecialBookLine(trimmed) -> BookParagraph.Kind.SPECIAL
            else -> BookParagraph.Kind.BODY
        }
        lines += Line(start, end, text, kind)
        if (fence) inCode = !inCode
        start = end + 1
    }

    val display = source.toCharArray()
    val paragraphs = mutableListOf<BookParagraph>()
    var previousKind: BookParagraph.Kind? = null
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.kind == null) {
            // Keep the last separator before the next block, and collapse earlier
            // blank-line characters into the preceding paragraph without moving offsets.
            for (offset in line.start until line.end) display[offset] = '\u200b'
            if (line.start > 0 && source[line.start - 1] == '\n') display[line.start - 1] = '\u200b'
            i++
            continue
        }
        var last = i
        if (pdf && line.kind == BookParagraph.Kind.BODY) {
            while (last + 1 < lines.size && lines[last + 1].kind == BookParagraph.Kind.BODY) {
                val left = lines[last].text.lastOrNull()
                val right = lines[last + 1].text.firstOrNull()
                val replacement = when {
                    left != null && right != null && isHan(left) && isHan(right) -> '\u200b'
                    left != null && right != null && left.isLetter() && right.isLetter() && !isHan(left) && !isHan(right) -> ' '
                    else -> null
                } ?: break
                if (lines[last].end > lines[last].start && source[lines[last].end - 1] == '\r') display[lines[last].end - 1] = '\u200b'
                display[lines[last].end] = replacement
                last++
            }
        }
        val blockEnd = lines[last].end
        val content = source.substring(line.start, blockEnd)
        val han = content.count(::isHan)
        val letters = content.count { it.isLetter() }
        paragraphs += BookParagraph(line.start, blockEnd, line.kind, han > letters - han, previousKind == BookParagraph.Kind.HEADING)
        previousKind = line.kind
        i = last + 1
    }
    return BookParagraphs(String(display), paragraphs)
}

private fun isHan(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

private fun isSpecialBookLine(text: String): Boolean =
    text.startsWith(">") || text.startsWith("|") ||
        Regex("^(?:[-+*]\\s+|\\d+[.)、]\\s*|[（(]\\d+[）)]\\s*)").containsMatchIn(text) ||
        Regex("^(?:图|表)\\s*[0-9一二三四五六七八九十]+[.．:：、\\s]").containsMatchIn(text) ||
        (text.startsWith("$") && text.endsWith("$"))
