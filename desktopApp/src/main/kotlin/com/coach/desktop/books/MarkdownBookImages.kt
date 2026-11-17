package com.coach.desktop.books

import java.nio.file.Path

/** Supplements the unchanged Markdown page text with local image assets. */
internal object MarkdownBookImages {
    private val image = Regex("!\\[([^]\\r\\n]*)]\\(\\s*(?:<([^>\\r\\n]+)>|([^\\s)]+))(?:\\s+[\"']([^\"']*)[\"'])?\\s*\\)")
    private val fence = Regex("^ {0,3}(`{3,}|~{3,}).*$")

    fun attach(raw: String, book: ExtractedBook, directory: Path?, control: BookImportControl): ExtractedBook {
        val placements = mutableListOf<FormatBookImages.Placement>()
        var total = 0L
        var codeFence: String? = null
        var start = 0
        for (line in raw.splitToSequence('\n')) {
            control.check()
            val marker = fence.matchEntire(line)?.groupValues?.get(1)
            if (codeFence != null) {
                if (marker != null && marker.first() == codeFence.first() && marker.length >= codeFence.length) codeFence = null
            } else if (marker != null) {
                codeFence = marker
            } else {
                for (match in image.findAll(line)) {
                    val at = match.range.first
                    // Escaped markup and inline code are not image references.
                    var slash = at - 1
                    while (slash >= 0 && line[slash] == '\\') slash--
                    if ((at - slash - 1) % 2 != 0 || inInlineCode(line, at)) continue
                    val source = match.groupValues[2].ifEmpty { match.groupValues[3] }
                    val bytes = FormatBookImages.htmlAsset(source.replace(" ", "%20"), directory) ?: continue
                    require(placements.size < FormatBookImages.MAX_IMAGES) { "这份材料插图过多，请分册导入。" }
                    total += bytes.size
                    require(total <= FormatBookImages.MAX_TOTAL) { "这份材料插图超过 64 MB，请分册导入。" }
                    val caption = match.groupValues[4].ifBlank { match.groupValues[1] }.takeIf(String::isNotBlank)
                    placements += FormatBookImages.Placement(start + at, bytes, caption)
                }
            }
            start += line.length + 1
        }
        return book.copy(illustrations = book.illustrations + FormatBookImages.attach(raw, book.pages, placements))
    }

    private fun inInlineCode(line: String, until: Int): Boolean {
        var delimiter = 0
        var index = 0
        while (index < until) {
            if (line[index] != '`') { index++; continue }
            val begin = index
            while (index < until && line[index] == '`') index++
            val count = index - begin
            if (delimiter == 0) delimiter = count else if (delimiter == count) delimiter = 0
        }
        return delimiter != 0
    }
}
