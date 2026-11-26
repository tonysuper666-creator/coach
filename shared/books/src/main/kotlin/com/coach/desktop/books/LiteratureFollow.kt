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

/** Shown on the reader when a follow-route quote does not remap onto that page. */
internal const val LITERATURE_QUOTE_MISS = "无法确认原句：这一页对不上这句原文，所以没有高亮。"

/** Printed-page chain for the follow route. PDF labels are used only after a folio on the page has been checked. */
internal object LiteraturePrintedPages {
    private val arabicFolio = Regex("""(?i)^(?:p(?:age)?\.?\s*)?(\d{1,4})$""")
    private val edgeToken = Regex("""(?i)^(?:p(?:age)?\.?\s*)?(\d{1,4})$""")

    fun parseFolio(raw: String?): Int? {
        val match = arabicFolio.matchEntire(raw?.trim().orEmpty()) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 1..9999 }
    }

    /** Leading or trailing page digits on the first and last lines. A year glued to a header is not a folio. */
    fun headerCandidates(text: String): List<Int> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return emptyList()
        val edges = (lines.take(2) + lines.takeLast(2)).distinct()
        val found = mutableListOf<Int>()
        for (line in edges) {
            val tokens = line.split(Regex("""\s+""")).filter(String::isNotEmpty)
            if (tokens.isEmpty()) continue
            considerToken(tokens.first(), tokens.size == 1)?.let { found += it }
            if (tokens.size > 1) considerToken(tokens.last(), alone = false)?.let { found += it }
            if (tokens.size == 1) parseFolio(line)?.let { found += it }
        }
        return found.distinct()
    }

    private fun considerToken(token: String, alone: Boolean): Int? {
        val match = edgeToken.matchEntire(token.trim().trim { it == ',' || it == '.' || it == ';' || it == ':' }) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        if (value !in 1..9999) return null
        if (!alone && value in 1500..2099) return null
        return value
    }

    fun display(pdfPage: Int, printed: Int?): String =
        if (printed != null && printed > 0) "第 $printed 页" else "PDF 第 $pdfPage 页"

    fun resolve(folioOptions: List<List<Int>>, labels: List<Int?>): List<Int?> {
        val count = folioOptions.size
        if (count == 0) return emptyList()
        fun options(index: Int): List<Int> =
            folioOptions.getOrNull(index).orEmpty().filter { it in 1..9999 }.distinct()
        val hits = mutableListOf<Pair<Int, Int>>()
        for (index in 0 until count) for (value in options(index)) hits += index to value
        val chain = longestIncreasingChain(hits)
        val trusted = arrayOfNulls<Int>(count)
        val blocked = BooleanArray(count)
        if (chain != null) {
            val anchorIndex = chain.first().first
            val anchorValue = chain.first().second
            val lastIndex = chain.last().first
            for (index in anchorIndex..lastIndex) {
                val expected = anchorValue + (index - anchorIndex)
                val choices = options(index)
                when {
                    expected in choices -> trusted[index] = expected
                    choices.isEmpty() -> trusted[index] = expected
                    else -> blocked[index] = true
                }
            }
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
                if (resolved[index] != null || blocked[index]) continue
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

    /** Longest run where the printed number rises by exactly one per PDF page. One bad page does not drop the run. */
    private fun longestIncreasingChain(hits: List<Pair<Int, Int>>): List<Pair<Int, Int>>? {
        var best: List<Pair<Int, Int>> = emptyList()
        var bestSpan = -1
        for ((anchorIndex, anchorValue) in hits) {
            val chain = hits.mapNotNull { (index, value) ->
                if (value - anchorValue == index - anchorIndex) index to value else null
            }.distinctBy { it.first }.sortedBy { it.first }
            if (chain.size < 2) continue
            val span = chain.last().first - chain.first().first
            if (chain.size > best.size || (chain.size == best.size && span > bestSpan)) {
                best = chain
                bestSpan = span
            }
        }
        return best.takeIf { it.size >= 2 }
    }
}

internal object LiteratureSections {
    private val keywordHeading = Regex(
        """(?i)^(?:abstract|introduction|background|discussion|method(?:s|ology)?|results?|conclusions?|summary|引言|导言|摘要|背景|方法|结果|讨论|结论|小结|结语|(?:[IVX]+\.|\d+(?:\.\d+)*[.)])\s+\p{L}.{0,80})$""",
    )
    private val numberedPrefix = Regex("""(?i)^(?:[IVX]+\.|\d+(?:\.\d+)*[.)])\s+""")
    private val abstractLine = Regex("""(?i)^(?:abstract|summary|摘要|概要)\s*[:：]?$""")
    private val endPunct = Regex("""[.!?。！？][”’"')\]]*$""")
    private val bibliography = Regex("""(?i)^(?:references|bibliography|works cited|endnotes|notes and references|参考文献|尾注)\s*[:：]?$""")
    private val notesBoundary = Regex(
        """(?i)^(?:endnotes?|footnotes?|notes(?:\s+to\s+(?:pages?|chapters?|the\s+text))?|参考文献|尾注|注释)\s*[:：]?$""",
    )
    private val caption = Regex("""(?i)^(?:fig(?:ure)?s?|plates?|tables?|illus(?:tration)?s?|captions?|图|表|插图)\b""")
    private val catalog = Regex(
        """(?i)published in print|oxford dictionary|dictionary of national biography|this version|stable url|©|(?:1[5-9]\d{2}|20\d{2})\s*[–—-]\s*(?:1[5-9]\d{2}|20\d{2})""",
    )
    private val attachedNote = Regex("""\p{L}\d{1,3}\b|[\u00B2\u00B3\u00B9\u2070-\u2079]|\[\d{1,3}]|\(\d{1,3}\)""")
    private val trailingDigits = Regex("""\d{1,3}$""")
    private val smallWords = setOf("of", "the", "in", "and", "a", "to", "for", "on", "or", "by")
    private val discourse = Regex("""(?i)\b(?:however|therefore|in contrast|in conclusion)\b|然而|因此""")
    private val contrastStart = Regex("""(?i)^(?:however|in contrast)\b|^然而""")
    private val closeStart = Regex("""(?i)^(?:therefore|in conclusion)\b|^因此""")
    private val argumentCue = Regex("""(?i)\b(?:argue|argues|argued|therefore|however|because|evidence|claim|claims)\b|因此|然而|表明""")

    private data class HeadingMark(val pdfPage: Int, val offset: Int, val title: String)

    fun build(book: Textbook, candidates: List<GuideExcerpt>): List<FollowSection> {
        if (candidates.isEmpty()) return emptyList()
        val notes = notesStart(book)
        val usable = candidates.filter { excerpt ->
            beforeNotes(excerpt.anchor.pdfPage, excerpt.anchor.startUtf16, notes) && !isPageContinuation(book, excerpt, candidates)
        }
        if (usable.isEmpty()) return emptyList()
        val marks = headingMarks(book, runningHeaders(book)).filter { beforeNotes(it.pdfPage, it.offset, notes) }
        val bodyMarks = marks.filter { !abstractLine.matches(it.title) }
        if (bodyMarks.size >= 2) {
            val headed = assignHeadings(book.id, usable, marks)
            if (headed != null) return headed
        }
        val abstract = marks.firstOrNull { abstractLine.matches(it.title) }
        if (abstract != null && bodyMarks.isEmpty()) {
            val abs = abstractSentences(usable, abstract)
            val absAt = abs.map { it.anchor.pdfPage to it.anchor.startUtf16 }.toSet()
            val rest = usable.filter { (it.anchor.pdfPage to it.anchor.startUtf16) !in absAt }
            val sections = mutableListOf<FollowSection>()
            if (abs.isNotEmpty()) {
                sections += FollowSection(abstract.title, abs.map { FollowSentence(book.id, it.anchor.pdfPage, it.anchor.quoteAnchor) })
            }
            if (rest.isNotEmpty()) sections += argumentStages(book.id, rest)
            if (sections.isNotEmpty()) return sections
        }
        return argumentStages(book.id, usable)
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

    private fun assignHeadings(bookId: String, candidates: List<GuideExcerpt>, marks: List<HeadingMark>): List<FollowSection>? {
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
            val sentence = FollowSentence(bookId, page, candidate.anchor.quoteAnchor)
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
        if (line.isEmpty() || endPunct.containsMatchIn(line)) return false
        if (bibliography.matches(line) || notesBoundary.matches(line)) return false
        if (LiteraturePrintedPages.parseFolio(line) != null) return false
        if (LiteraturePassages.isBibliographicFootnote(line)) return false
        if (headerKey(line) in headers || collapsedHeaderKey(line) in headers) return false
        if (isOcrGarbage(line) || caption.containsMatchIn(line) || catalog.containsMatchIn(line)) return false
        if (hasNoteMarker(line) || line.contains(',') || line.contains(';') || line.contains('；') || line.contains('，')) return false
        val han = line.count { it.code in 0x3400..0x9fff }
        if (han >= 2 && line.length <= 20 && line.none { it.isDigit() }) return true
        if (line.length !in 4..80) return false
        if (keywordHeading.matches(line)) {
            if (numberedPrefix.containsMatchIn(line)) return titleLikeRemainder(line)
            return true
        }
        return isTitleCase(line) && line.split(Regex("""\s+""")).count { it.isNotEmpty() } in 1..8
    }

    private fun titleLikeRemainder(line: String): Boolean {
        val rest = numberedPrefix.replace(line, "").trim()
        if (rest.length !in 2..60 || hasNoteMarker(rest) || rest.any { it == '.' || it == '!' || it == '?' }) return false
        return isTitleCase(rest) && rest.split(Regex("""\s+""")).count { it.isNotEmpty() } <= 8
    }

    private fun hasNoteMarker(line: String): Boolean =
        attachedNote.containsMatchIn(line) || trailingDigits.containsMatchIn(line)

    private fun isOcrGarbage(line: String): Boolean {
        val tokens = line.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (tokens.size >= 4 && tokens.count { it.length == 1 } * 2 >= tokens.size) return true
        val letters = line.count { it.isLetter() }
        return letters * 5 < line.length * 3
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
                (lines.take(2) + lines.takeLast(2)).flatMap { line ->
                    listOf(headerKey(line), collapsedHeaderKey(line))
                }.filter { it.length >= 4 }
            }.toSet()
        }
        val counts = mutableMapOf<String, Int>()
        for (keys in perPage) for (key in keys) counts[key] = (counts[key] ?: 0) + 1
        val pages = perPage.size
        return counts.filter { (_, count) -> count >= 2 && (count >= 3 || count * 2 >= pages) }.keys
    }

    private fun headerKey(line: String): String =
        line.lowercase().replace(Regex("""\d+"""), "").replace(Regex("""\s+"""), " ").trim()

    /** OCR sometimes splits a running header into single letters. Collapse that form so it still counts. */
    private fun collapsedHeaderKey(line: String): String {
        val parts = headerKey(line).split(" ").filter { it.isNotEmpty() }
        if (parts.size < 4 || parts.count { it.length == 1 } * 2 < parts.size) return ""
        return parts.joinToString("")
    }

    private data class PageOffset(val page: Int, val offset: Int)

    private fun notesStart(book: Textbook): PageOffset? {
        val cutoff = book.pages.size / 3
        for (page in book.pages) {
            if (page.number <= cutoff) continue
            var base = 0
            for (region in page.guideRegions) {
                var offset = 0
                for (raw in region.text.lines()) {
                    if (notesBoundary.matches(raw.trim())) return PageOffset(page.number, base + offset)
                    offset += raw.length + 1
                }
                base += region.text.length + 2
            }
        }
        return null
    }

    private fun beforeNotes(page: Int, offset: Int, notes: PageOffset?): Boolean {
        if (notes == null) return true
        return page < notes.page || (page == notes.page && offset < notes.offset)
    }

    private fun isPageContinuation(book: Textbook, excerpt: GuideExcerpt, candidates: List<GuideExcerpt>): Boolean {
        val page = excerpt.anchor.pdfPage
        if (page <= 1) return false
        val first = candidates.filter { it.anchor.pdfPage == page }.minOfOrNull { it.anchor.startUtf16 } ?: return false
        if (excerpt.anchor.startUtf16 != first || excerpt.anchor.startUtf16 > 220) return false
        val prev = book.pages.firstOrNull { it.number == page - 1 } ?: return false
        val last = prev.guideRegions.asSequence()
            .flatMap { it.text.lineSequence() }
            .map(String::trim)
            .lastOrNull { it.isNotEmpty() }
            ?: return false
        if (last.length < 40) return false
        val end = last.last()
        return end != '.' && end != '!' && end != '?' && end != '。' && end != '！' && end != '？'
    }

    private fun abstractSentences(candidates: List<GuideExcerpt>, mark: HeadingMark): List<GuideExcerpt> {
        val after = candidates.filter { excerpt ->
            val page = excerpt.anchor.pdfPage
            page > mark.pdfPage || (page == mark.pdfPage && excerpt.anchor.startUtf16 >= mark.offset)
        }
        val samePage = after.takeWhile { it.anchor.pdfPage == mark.pdfPage }
        return samePage.take(8)
    }

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
