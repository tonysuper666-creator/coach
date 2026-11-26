package com.coach.desktop.books

import java.text.Normalizer
import kotlin.math.abs

internal data class FollowSentence(
    val bookId: String,
    val pdfPage: Int,
    val quote: String,
)

internal data class FollowSection(
    val title: String,
    val sentences: List<FollowSentence>,
    val stop: FollowSentence? = null,
)

internal data class FollowPaper(
    val bookId: String,
    val title: String,
    val sections: List<FollowSection>,
    val printedByPdfPage: List<Int?>,
)

/** Printed-page chain for the follow route. PDF labels are used only after a folio on the page has been checked. */
internal object LiteraturePrintedPages {
    private val arabicFolio = Regex("""(?i)^(?:p(?:age)?\.?\s*)?(\d{1,4})$""")

    fun parseFolio(raw: String?): Int? {
        val match = arabicFolio.matchEntire(raw?.trim().orEmpty()) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 1..9999 }
    }

    fun display(pdfPage: Int, printed: Int?): String =
        if (printed != null && printed > 0) "第 $printed 页" else "PDF 第 $pdfPage 页"

    fun resolve(folioOptions: List<List<Int>>, labels: List<Int?>): List<Int?> {
        val count = folioOptions.size
        if (count == 0) return emptyList()
        val trusted = arrayOfNulls<Int>(count)
        fun options(index: Int): List<Int> =
            folioOptions.getOrNull(index).orEmpty().filter { it in 1..9999 }.distinct()
        fun agrees(index: Int, value: Int): Boolean {
            if (options(index - 1).any { it == value - 1 } || options(index + 1).any { it == value + 1 }) return true
            if (options(index - 1).isEmpty() && options(index - 2).any { it == value - 2 }) return true
            if (options(index + 1).isEmpty() && options(index + 2).any { it == value + 2 }) return true
            return false
        }
        for (index in 0 until count) {
            trusted[index] = options(index).firstOrNull { agrees(index, it) }
        }
        var extended = true
        while (extended) {
            extended = false
            for (index in 0 until count) {
                if (trusted[index] != null) continue
                val choices = options(index)
                if (choices.isEmpty()) continue
                val fromLeft = trusted.getOrNull(index - 1)?.let { left -> choices.firstOrNull { it == left + 1 } }
                val fromRight = trusted.getOrNull(index + 1)?.let { right -> choices.firstOrNull { it == right - 1 } }
                val picked = fromLeft ?: fromRight ?: continue
                trusted[index] = picked
                extended = true
            }
        }
        for (index in 1 until count - 1) {
            if (trusted[index] != null) continue
            val left = trusted[index - 1] ?: continue
            val right = trusted[index + 1] ?: continue
            if (right - left != 2) continue
            val expected = left + 1
            val choices = options(index)
            if (choices.isEmpty() || expected in choices) trusted[index] = expected
        }
        if (trusted.all { it == null }) return List(count) { null }
        val labelsDisagree = (0 until count).any { index ->
            val known = trusted[index]
            val label = labels.getOrNull(index)
            known != null && label != null && label != known
        }
        if (labelsDisagree) return trusted.toList()
        val resolved = trusted.copyOf()
        var grew = true
        while (grew) {
            grew = false
            for (index in 0 until count) {
                if (resolved[index] != null) continue
                val label = labels.getOrNull(index)?.takeIf { it in 1..9999 } ?: continue
                val left = resolved.getOrNull(index - 1)
                val right = resolved.getOrNull(index + 1)
                if ((left != null && label == left + 1) || (right != null && label == right - 1)) {
                    resolved[index] = label
                    grew = true
                }
            }
        }
        return resolved.toList()
    }
}

internal object LiteratureSections {
    private val keywordHeading = Regex(
        """(?i)^(?:abstract|introduction|background|discussion|method(?:s|ology)?|results?|conclusions?|summary|引言|导言|摘要|背景|方法|结果|讨论|结论|小结|(?:[IVX]+\.|\d+(?:\.\d+)*[.)])\s+\p{L}.{0,80})$""",
    )
    private val endPunct = Regex("""[.!?。！？][”’"')\]]*$""")
    private val bibliography = Regex("""(?i)^(?:references|bibliography|works cited|endnotes|notes and references|参考文献|尾注)\s*[:：]?$""")
    private val smallWords = setOf("of", "the", "in", "and", "a", "to", "for", "on", "or", "by")
    private val discourse = Regex("""(?i)\b(?:however|therefore|in contrast|in conclusion)\b|然而|因此""")
    private val contrastStart = Regex("""(?i)^(?:however|in contrast)\b|^然而""")
    private val closeStart = Regex("""(?i)^(?:therefore|in conclusion)\b|^因此""")
    private val argumentCue = Regex("""(?i)\b(?:argue|argues|argued|therefore|however|because|evidence|claim|claims)\b|因此|然而|表明""")

    private data class HeadingMark(val pdfPage: Int, val offset: Int, val title: String)

    fun build(book: Textbook, candidates: List<GuideExcerpt>): List<FollowSection> {
        if (candidates.isEmpty()) return emptyList()
        val headed = assignHeadings(book, candidates)
        if (headed != null) return headed
        return argumentStages(book.id, candidates)
    }

    fun pickStop(sentences: List<FollowSentence>, accepts: (FollowSentence) -> Boolean): FollowSentence? {
        val ranked = sentences.withIndex().sortedWith(
            compareByDescending<IndexedValue<FollowSentence>> { score(it.value.quote) }.thenBy { it.index },
        )
        return ranked.firstOrNull { LiteraturePassages.bodySentence(it.value.quote) && accepts(it.value) }?.value
    }

    private fun score(quote: String): Int {
        var value = 0
        if (argumentCue.containsMatchIn(quote)) value += 10
        if (quote.length in 40..220) value += 5
        return value
    }

    private fun assignHeadings(book: Textbook, candidates: List<GuideExcerpt>): List<FollowSection>? {
        val marks = headingMarks(book, runningHeaders(book))
        if (marks.isEmpty()) return null
        val titles = mutableListOf<String>()
        val buckets = mutableListOf<MutableList<FollowSentence>>()
        var preface: MutableList<FollowSentence>? = null
        var current: MutableList<FollowSentence>? = null
        var markIndex = 0
        for (candidate in candidates) {
            val page = candidate.anchor.pdfPage
            val offset = candidate.anchor.startUtf16
            while (markIndex < marks.size && !after(marks[markIndex], page, offset)) {
                val bucket = mutableListOf<FollowSentence>()
                titles += marks[markIndex].title
                buckets += bucket
                current = bucket
                markIndex++
            }
            val sentence = FollowSentence(book.id, page, candidate.anchor.quoteAnchor)
            val active = current
            if (active == null) {
                val opening = preface ?: mutableListOf<FollowSentence>().also { preface = it }
                opening += sentence
            } else {
                active += sentence
            }
        }
        val headingSections = titles.zip(buckets).filter { it.second.isNotEmpty() }
        if (headingSections.isEmpty()) return null
        val sections = mutableListOf<FollowSection>()
        val opening = preface
        if (!opening.isNullOrEmpty()) sections += FollowSection("开篇", opening)
        headingSections.forEach { (title, sentences) -> sections += FollowSection(title, sentences) }
        return sections
    }

    private fun after(mark: HeadingMark, page: Int, offset: Int): Boolean =
        mark.pdfPage > page || (mark.pdfPage == page && mark.offset > offset)

    private fun headingMarks(book: Textbook, headers: Set<String>): List<HeadingMark> {
        val marks = mutableListOf<HeadingMark>()
        for (page in book.pages) {
            var base = 0
            for (region in page.guideRegions) {
                var offset = 0
                for (raw in region.text.lines()) {
                    val line = raw.trim()
                    if (isBodyHeading(line, headers)) marks += HeadingMark(page.number, base + offset, line)
                    offset += raw.length + 1
                }
                base += region.text.length + 2
            }
        }
        return marks
    }

    private fun isBodyHeading(line: String, headers: Set<String>): Boolean {
        if (line.length !in 4..80 || endPunct.containsMatchIn(line)) return false
        if (bibliography.matches(line)) return false
        if (LiteraturePrintedPages.parseFolio(line) != null) return false
        if (LiteraturePassages.isBibliographicFootnote(line)) return false
        if (headerKey(line) in headers) return false
        return keywordHeading.matches(line) || isTitleCase(line)
    }

    private fun isTitleCase(line: String): Boolean {
        val words = line.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.size !in 1..12) return false
        return words.all { word ->
            word.lowercase() in smallWords || word.first().isUpperCase() || word.first().code in 0x3400..0x9fff
        }
    }

    private fun runningHeaders(book: Textbook): Set<String> {
        val perPage = book.pages.map { page ->
            page.guideRegions.flatMap { region ->
                val lines = region.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                (lines.take(2) + lines.takeLast(2)).map(::headerKey).filter { it.length >= 4 }
            }.toSet()
        }
        val counts = mutableMapOf<String, Int>()
        for (keys in perPage) for (key in keys) counts[key] = (counts[key] ?: 0) + 1
        val pages = perPage.size
        return counts.filter { (_, count) -> count >= 2 && (count >= 3 || count * 2 >= pages) }.keys
    }

    private fun headerKey(line: String): String =
        line.lowercase().replace(Regex("""\d+"""), "").replace(Regex("""\s+"""), " ").trim()

    private fun argumentStages(bookId: String, candidates: List<GuideExcerpt>): List<FollowSection> {
        val sentences = candidates.map { FollowSentence(bookId, it.anchor.pdfPage, it.anchor.quoteAnchor) }
        val count = sentences.size
        val target = if (count < 3) 1 else (count / 4).coerceIn(3, 8)
        if (target <= 1) return listOf(FollowSection(stageName(0, sentences.first().quote), sentences))
        val window = maxOf(1, count / (target * 3))
        val bounds = ArrayList<Int>(target + 1)
        bounds += 0
        for (step in 1 until target) {
            val previous = bounds.last() + 1
            val latest = count - (target - step)
            val ideal = (step * count / target).coerceIn(previous, latest)
            val lo = maxOf(previous, ideal - window)
            val hi = minOf(latest, ideal + window)
            bounds += chooseCut(sentences, ideal, lo, hi)
        }
        bounds += count
        return bounds.zipWithNext().mapIndexed { index, (start, end) ->
            val slice = sentences.subList(start, end)
            FollowSection(stageName(index, slice.first().quote), slice)
        }
    }

    private fun chooseCut(sentences: List<FollowSentence>, ideal: Int, lo: Int, hi: Int): Int {
        val range = lo..hi
        val markers = range.filter { discourse.containsMatchIn(sentences[it].quote) }
        if (markers.isNotEmpty()) return markers.minBy { abs(it - ideal) }
        val interior = range.filter { it > 0 && sentences[it].pdfPage == sentences[it - 1].pdfPage }
        if (interior.isNotEmpty()) return interior.minBy { abs(it - ideal) }
        return ideal.coerceIn(lo, hi)
    }

    private fun stageName(index: Int, first: String): String {
        val text = first.trim()
        return when {
            index == 0 -> "提出问题"
            contrastStart.containsMatchIn(text) -> "转折"
            closeStart.containsMatchIn(text) -> "收束"
            else -> "第 ${index + 1} 段"
        }
    }
}

/**
 * Remap a guide quote onto the reader page text.
 * Only an exact normalized hit is accepted. The fuzzy citation window would highlight a different sentence.
 */
internal object LiteratureQuoteMap {
    private val ligature = Regex("[\uFB00-\uFB06]")
    private val lineHyphen = Regex("""(?<=\p{L})[-\u2010\u2011]\s+(?=\p{Ll})""")
    private val spacedDash = Regex("""\s*-\s*""")

    private data class Piece(val ch: Char, val start: Int, val end: Int)

    fun normalize(raw: String): String = pieces(raw).joinToString("") { it.ch.toString() }

    fun toPassage(bookId: String, readerPage: BookPage, quote: String): BookPassage? {
        if (quote.isEmpty()) return null
        val text = readerPage.text
        val exactAt = text.indexOf(quote)
        if (exactAt >= 0) return BookPassage(bookId, readerPage.number, exactAt, exactAt + quote.length, quote)
        val pagePieces = pieces(text)
        val quoteNorm = normalize(quote)
        if (quoteNorm.isEmpty() || pagePieces.isEmpty()) return null
        val pageNorm = pagePieces.joinToString("") { it.ch.toString() }
        var from = 0
        while (from <= pageNorm.length - quoteNorm.length) {
            val at = pageNorm.indexOf(quoteNorm, from)
            if (at < 0) return null
            val start = pagePieces[at].start
            val end = pagePieces[at + quoteNorm.length - 1].end
            if (start in 0 until end && end <= text.length) {
                val slice = text.substring(start, end)
                if (normalize(slice) == quoteNorm) return BookPassage(bookId, readerPage.number, start, end, slice)
            }
            from = at + 1
        }
        return null
    }

    private fun pieces(raw: String): List<Piece> {
        val expanded = ArrayList<Piece>(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch == '\u00AD') {
                index++
                continue
            }
            if (ligature.matches(ch.toString())) {
                Normalizer.normalize(ch.toString(), Normalizer.Form.NFKC).forEach { expanded += Piece(it, index, index + 1) }
            } else {
                expanded += Piece(ch, index, index + 1)
            }
            index++
        }
        return trimPieces(collapseDashes(dropSpaceBeforePunct(collapseWhitespace(dropLineHyphen(expanded)))))
    }

    private fun dropLineHyphen(input: List<Piece>): List<Piece> {
        if (input.isEmpty()) return input
        val text = String(CharArray(input.size) { input[it].ch })
        val drop = BooleanArray(input.size)
        for (match in lineHyphen.findAll(text)) {
            for (index in match.range) drop[index] = true
        }
        val kept = ArrayList<Piece>(input.size)
        for (index in input.indices) {
            if (!drop[index]) kept += Piece(mapPunct(input[index].ch), input[index].start, input[index].end)
        }
        return kept
    }

    private fun mapPunct(ch: Char): Char = when (ch) {
        '\u017F' -> 's'
        '\u2018', '\u2019', '\u02BC' -> '\''
        '\u201C', '\u201D' -> '"'
        '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015' -> '-'
        '\uFF0C' -> ','
        '\uFF0E' -> '.'
        '\uFF1B' -> ';'
        '\uFF1A' -> ':'
        '\uFF08' -> '('
        '\uFF09' -> ')'
        else -> ch
    }

    private fun isAsciiWs(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\u000B' || ch == '\u000C' || ch == '\r'

    private fun collapseWhitespace(input: List<Piece>): List<Piece> {
        val out = ArrayList<Piece>(input.size)
        var index = 0
        while (index < input.size) {
            if (isAsciiWs(input[index].ch)) {
                val start = input[index].start
                var end = input[index].end
                index++
                while (index < input.size && isAsciiWs(input[index].ch)) {
                    end = input[index].end
                    index++
                }
                out += Piece(' ', start, end)
            } else {
                out += input[index]
                index++
            }
        }
        return out
    }

    private fun dropSpaceBeforePunct(input: List<Piece>): List<Piece> {
        if (input.isEmpty()) return input
        val out = ArrayList<Piece>(input.size)
        for (index in input.indices) {
            val piece = input[index]
            val next = input.getOrNull(index + 1)
            if (piece.ch == ' ' && next != null && next.ch in ",.;:!?") continue
            out += piece
        }
        return out
    }

    private fun collapseDashes(input: List<Piece>): List<Piece> {
        if (input.isEmpty()) return input
        val text = String(CharArray(input.size) { input[it].ch })
        val out = ArrayList<Piece>(input.size)
        var index = 0
        for (match in spacedDash.findAll(text)) {
            while (index < match.range.first) {
                out += input[index]
                index++
            }
            out += Piece('-', input[match.range.first].start, input[match.range.last].end)
            index = match.range.last + 1
        }
        while (index < input.size) {
            out += input[index]
            index++
        }
        return out
    }

    private fun trimPieces(input: List<Piece>): List<Piece> {
        var start = 0
        var end = input.size
        while (start < end && input[start].ch.isWhitespace()) start++
        while (end > start && input[end - 1].ch.isWhitespace()) end--
        return if (start == 0 && end == input.size) input else input.subList(start, end)
    }
}

internal object LiteratureFollow {
    fun loadPapers(library: TextbookLibrary, bookIds: List<String>): List<FollowPaper> {
        val papers = mutableListOf<FollowPaper>()
        for (bookId in bookIds) {
            try {
                papers += load(library, bookId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // One unreadable paper does not hide the others.
            }
        }
        return papers
    }

    fun load(library: TextbookLibrary, bookId: String): FollowPaper {
        val literature = library.literatureBook(bookId)
        val sections = LiteratureSections.build(literature, LiteraturePassages.extractCandidates(literature))
        return FollowPaper(literature.id, literature.title, sections, library.literaturePageNumbers(bookId))
    }

    fun fillStops(library: TextbookLibrary, papers: List<FollowPaper>): List<FollowPaper> =
        papers.map { paper ->
            paper.copy(sections = paper.sections.map { section ->
                val stop = runCatching { verifiedStop(library, paper.bookId, section.sentences) }.getOrNull()
                section.copy(stop = stop)
            })
        }

    fun verifiedStop(library: TextbookLibrary, bookId: String, sentences: List<FollowSentence>): FollowSentence? =
        LiteratureSections.pickStop(sentences) { sentence -> acceptsOcr(library, bookId, sentence) }

    private fun acceptsOcr(library: TextbookLibrary, bookId: String, sentence: FollowSentence): Boolean {
        val page = runCatching { library.ocrPdfPageForGuide(bookId, sentence.pdfPage) }.getOrNull()
        if (page != null &&
            LiteratureTextNorm.bestContiguousSimilarity(sentence.quote, page) >= LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD
        ) {
            return true
        }
        val crop = runCatching { library.ocrPdfQuoteCropForGuide(bookId, sentence.pdfPage, sentence.quote) }.getOrNull()
            ?: return false
        return LiteratureTextNorm.bestContiguousSimilarity(sentence.quote, crop) >= LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD
    }
}
