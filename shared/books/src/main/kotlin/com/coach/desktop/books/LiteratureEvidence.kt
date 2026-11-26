package com.coach.desktop.books

import java.text.Normalizer
import org.jsoup.parser.Parser

internal data class LiteratureCitation(val author: String?, val year: String?, val sourceLabel: String? = null) {
    companion object {
        fun from(filename: String?, metadataAuthor: String?, title: String): LiteratureCitation {
            val stem = filename.orEmpty().substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').replace('_', ' ')
            val yearPattern = Regex("""(?<!\d)(?:1[5-9]\d\d|20\d\d)(?!\d)""")
            val year = yearPattern.find(stem) ?: yearPattern.find(title)
            val prefix = yearPattern.find(stem)?.let { stem.take(it.range.first) } ?: stem.split(Regex("""\s+[-–—]\s+""")).first()
            val inferred = prefix.trim(' ', '-', '–', '(', '[', ',').takeIf {
                it.length in 2..80 && it.split(' ').size <= 6 && it.any(Char::isLetter) &&
                    (yearPattern.containsMatchIn(stem) || prefix != stem || stem.split(' ').size == 1)
            }
            val author = metadataAuthor?.trim()?.takeIf { it.isNotEmpty() && !it.contains('@') &&
                !Regex("""(?i)^(?:unknown|administrator|user|microsoft.*)$""").matches(it) }
            return LiteratureCitation((author ?: inferred)?.let { Parser.unescapeEntities(it, false) }, year?.value,
                stem.takeIf(String::isNotBlank)?.let { Parser.unescapeEntities(it.take(240), false) })
        }
    }
}

/** Normalized search retains an original UTF-16 range for each searchable character. */
internal class LiteratureReaderText(private val raw: String) {
    private val offsets = ArrayList<Int>()
    private val ends = ArrayList<Int>()
    private val text = buildString {
        var i = 0
        while (i < raw.length) {
            val start = i
            val c = raw[i++]
            if (c == '\u00ad') continue
            if (c == '-' && i < raw.length && raw[i] in "\r\n") {
                var next = i
                while (next < raw.length && raw[next].isWhitespace()) next++
                if (next < raw.length && raw[next].isLowerCase()) { i = next; continue }
            }
            val unit = when {
                c.isWhitespace() -> { while (i < raw.length && raw[i].isWhitespace()) i++; " " }
                c in 'ﬀ'..'ﬆ' -> Normalizer.normalize(c.toString(), Normalizer.Form.NFKC)
                else -> c.toString()
            }
            append(unit)
            repeat(unit.length) { offsets.add(start); ends.add(i) }
        }
    }

    /** A left/right/left sequence in the saved page is a line-interleaved spread.
     * Reject the whole page, even if a short sentence happens to survive intact.
     */
    fun interleaved(regions: List<GuideRegion>): Boolean {
        if (regions.size < 2) return false
        // OCR may fuse adjacent words. Whitespace-insensitive matches are only
        // an ordering veto; the actual quotation still needs a strict reader range.
        val compact = text.filterNot(Char::isWhitespace).lowercase()
        val positions = sortedMapOf<Int, Int>()
        regions.forEachIndexed { column, region ->
            region.text.lineSequence().forEach { line ->
                val words = line.trim().split(Regex("""\s+"""))
                val fragments = if (words.size < 4) listOf(line.trim()) else words.windowed(3, 2).map { it.joinToString(" ") }
                for (fragment in fragments) {
                    if (fragment.count(Char::isLetter) < 7) continue
                    val key = fragment.filterNot(Char::isWhitespace).lowercase()
                    val offset = compact.indexOf(key)
                    if (offset < 0 || compact.indexOf(key, offset + 1) >= 0) continue
                    val previous = positions.put(offset, column)
                    if (previous != null && previous != column) return true
                }
            }
        }
        return positions.values.zipWithNext().any { (left, right) -> right < left }
    }

    fun locate(quote: String): IntRange? {
        val needle = LiteratureReaderText(quote).text.trim()
        if (needle.isEmpty()) return null
        val at = text.indexOf(needle)
        if (at < 0 || text.indexOf(needle, at + 1) >= 0) return null
        return offsets[at] until ends[at + needle.length - 1]
    }
}

internal object LiteratureFolios {
    private val isolated = Regex("""(?i)^(?:page\s+)?(\d{1,4}|[ivxlcdm]{1,6})$""")
    private val inline = Regex("""^(?:(\d{1,3})\s+\p{L}.*|\p{L}.*\s+(\d{1,3}))$""")
    fun visible(text: String): String? {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val edges = lines.take(2) + lines.takeLast(2)
        return edges.firstNotNullOfOrNull { isolated.matchEntire(it)?.groupValues?.get(1) }
            ?: edges.firstNotNullOfOrNull { line ->
                inline.matchEntire(line)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)
            }
    }

    fun resolve(pages: List<BookPage>, labels: Array<String>?): List<BookPage> {
        // A spread has two printed leaves for one PDF page. Its offsets must never
        // be extrapolated as though it were a single leaf.
        val observed = pages.map { page -> page.guideRegions.mapNotNull { it.printedPage }.distinct().singleOrNull()?.toIntOrNull() }
        return pages.mapIndexed { i, page ->
            val regions = page.guideRegions
            val neighbors = ((i - 3).coerceAtLeast(0)..(i + 3).coerceAtMost(pages.lastIndex))
                .filter { it != i && observed[it] != null }
            val predicted = neighbors.map { j -> observed[j]!! + i - j }
            val bracketed = neighbors.any { it < i } && neighbors.any { it > i }
            val consensus = predicted.distinct().singleOrNull()
            val fallback = when {
                consensus == null || consensus <= 0 -> null
                bracketed || labels?.getOrNull(i)?.toIntOrNull() == consensus -> consensus.toString()
                else -> null
            }
            page.copy(guideRegions = regions.map { r ->
                val visible = r.printedPage
                val value = when {
                    regions.size > 1 -> visible
                    visible == null -> fallback
                    predicted.size >= 2 && consensus != null && visible.toIntOrNull() != null && visible.toInt() != consensus -> null
                    else -> visible
                }
                r.copy(printedPage = value)
            })
        }
    }
}
