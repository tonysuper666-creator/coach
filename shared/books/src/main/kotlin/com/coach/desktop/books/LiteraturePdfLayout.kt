package com.coach.desktop.books

import java.io.StringWriter
import java.text.Normalizer
import kotlin.math.abs

internal data class LiteraturePaperMeta(
    val bookId: String,
    val title: String,
    val author: String = "",
    val year: String = "",
)

internal object LiteratureTextNorm {
    const val OCR_SIMILARITY_THRESHOLD = 0.98
    private val ligature = Regex("[\uFB00-\uFB06]")
    private val lineHyphen = Regex("""(?<=\p{L})[-\u2010\u2011]\s*\n\s*(?=\p{Ll})""")
    private val whitespace = Regex("""\s+""")
    private val entity = Regex("""&(?:#(\d{1,7})|#x([0-9a-fA-F]{1,6})|([a-zA-Z][a-zA-Z0-9]{1,15}));""")
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "\u2013", "mdash" to "\u2014", "lsquo" to "\u2018", "rsquo" to "\u2019",
        "ldquo" to "\u201C", "rdquo" to "\u201D", "hellip" to "\u2026", "copy" to "\u00A9",
        "reg" to "\u00AE", "trade" to "\u2122", "times" to "\u00D7", "middot" to "\u00B7",
        "sect" to "\u00A7", "eacute" to "\u00E9", "egrave" to "\u00E8", "aacute" to "\u00E1",
        "agrave" to "\u00E0", "oacute" to "\u00F3", "uacute" to "\u00FA", "uuml" to "\u00FC",
        "ouml" to "\u00F6", "auml" to "\u00E4", "ccedil" to "\u00E7", "ntilde" to "\u00F1",
        "szlig" to "\u00DF",
    )

    fun decodeHtmlEntities(raw: String): String {
        var current = raw
        repeat(2) {
            val next = entity.replace(current) { match ->
                val dec = match.groupValues[1]
                val hex = match.groupValues[2]
                val name = match.groupValues[3]
                when {
                    dec.isNotEmpty() -> dec.toIntOrNull()?.takeIf { it in 0x20..0x10FFFF }
                        ?.let { String(Character.toChars(it)) } ?: match.value
                    hex.isNotEmpty() -> hex.toIntOrNull(16)?.takeIf { it in 0x20..0x10FFFF }
                        ?.let { String(Character.toChars(it)) } ?: match.value
                    else -> namedEntities[name] ?: namedEntities[name.lowercase()] ?: match.value
                }
            }
            if (next == current) return current.replace(whitespace, " ").trim()
            current = next
        }
        return current.replace(whitespace, " ").trim()
    }

    /** Normalize whitespace, line-break hyphens, ligatures, and typographic quotes/dashes for comparison. */
    fun normalize(s: String): String = s
        .replace("\u00AD", "")
        .replace(ligature) { Normalizer.normalize(it.value, Normalizer.Form.NFKC) }
        .replace(lineHyphen, "")
        .replace('\u2018', '\'')
        .replace('\u2019', '\'')
        .replace('\u02BC', '\'')
        .replace('\u201C', '"')
        .replace('\u201D', '"')
        .replace('\u2010', '-')
        .replace('\u2011', '-')
        .replace('\u2012', '-')
        .replace('\u2013', '-')
        .replace(whitespace, " ")
        .trim()

    /**
     * Best normalized Levenshtein similarity between [quote] and any contiguous substring of [ocrPageText].
     * Uses Sellers' semi-global dynamic programming in O(|needle| * |haystack|).
     */
    fun bestContiguousSimilarity(quote: String, ocrPageText: String): Double {
        val needle = normalize(quote)
        val haystack = normalize(ocrPageText)
        val m = needle.length
        val n = haystack.length
        if (m == 0 || n == 0) return 0.0
        if (needle in haystack) return 1.0
        var prevDist = IntArray(n + 1) { 0 }
        var currDist = IntArray(n + 1)
        var prevStart = IntArray(n + 1) { it }
        var currStart = IntArray(n + 1)
        for (i in 1..m) {
            val nc = needle[i - 1]
            currDist[0] = i
            currStart[0] = 0
            for (j in 1..n) {
                val subCost = prevDist[j - 1] + if (nc == haystack[j - 1]) 0 else 1
                val delCost = prevDist[j] + 1
                val insCost = currDist[j - 1] + 1
                if (subCost <= delCost && subCost <= insCost) {
                    currDist[j] = subCost
                    currStart[j] = prevStart[j - 1]
                } else if (delCost <= insCost) {
                    currDist[j] = delCost
                    currStart[j] = prevStart[j]
                } else {
                    currDist[j] = insCost
                    currStart[j] = currStart[j - 1]
                }
            }
            val tmpD = prevDist; prevDist = currDist; currDist = tmpD
            val tmpS = prevStart; prevStart = currStart; currStart = tmpS
        }
        var best = 0.0
        for (j in 1..n) {
            val matchedLen = j - prevStart[j]
            val denom = maxOf(m, matchedLen)
            if (denom > 0) {
                val sim = 1.0 - prevDist[j].toDouble() / denom.toDouble()
                if (sim > best) best = sim
            }
        }
        return best.coerceIn(0.0, 1.0)
    }
}

internal object LiteratureMetadata {
    private val junkTitle = Regex("""(?i)^(?:untitled|document\d*|microsoft word.*|acrobat.*|scan\d*|page\s*\d+|doi[:/\s].*|\d+)$""")
    private val nonTitleLine = Regex(
        """(?i)\b(?:isbn|issn|doi|copyright|all rights reserved|fair dealing|fair use|coursepack|course pack|course reading|university library|digitisation|digitization|licen[cs]e|downloaded from|stable url|published by|permission of)\b|https?://|©""",
    )
    private val junkAuthor = Regex("""(?i)^(?:admin(?:istrator)?|user|unknown|owner|author|jstor|windows user|macbook|hp|dell|lenovo|default|system|pdf|scanner)$""")
    private val coursePrefix = Regex("""(?i)^(?:(?:[A-Z]{2,6}\d{3,6}|W\d{1,2}|week\s*\d{1,2}|reading\s*\d{1,2}|\d{1,2})[\s._\-–—:]+)+""")
    private val yearToken = Regex("""(?<!\d)(1[5-9]\d{2}|20\d{2})(?!\d)""")
    private val jstorAuthor = Regex("""(?im)^Author(?:\(s\))?\s*[:：]\s*(.+)$""")
    private val jstorSourceYear = Regex("""(?im)^(?:Source|Published|Publication Date|Date|Year)\s*[:：].*?(?<!\d)(1[5-9]\d{2}|20\d{2})(?!\d)""")
    private val coverAuthor = Regex("""(?im)^(?:Author(?:\(s\))?|By)\s*[:：]\s*(.+)$""")
    private val byLine = Regex("""(?m)^\s*(?:By|BY)\s+([A-Z][\p{L}.'\-]+(?:\s+(?:da|de|del|van|von|la|di|[A-Z][\p{L}.'\-]+)){1,4})\s*$""")
    private val filenameAuthorYear = Regex("""^([A-Za-z\u00C0-\u024F][\p{L}.'\- ]{1,48}?)\s*(?:\(|,\s*|\s+)(1[5-9]\d{2}|20\d{2})\)?(?:\s*[-–—:,]\s*(.+))?$""")
    private val filenameYearAuthor = Regex("""^(1[5-9]\d{2}|20\d{2})\s*[-–—_\s]+([A-Za-z\u00C0-\u024F][\p{L}.'\- ]{1,48}?)(?:\s*[-–—:,]\s*(.+))?$""")
    private val filenameDashAuthor = Regex("""^([A-Za-z\u00C0-\u024F][\p{L}.'\- ]{1,36}?)\s+[-–—]\s+(.+)$""")
    private val parenYear = Regex("""\((?:[^()]{0,30},?\s*)?(1[5-9]\d{2}|20\d{2})\)""")

    fun extract(
        pdf: BookPdfDocument?,
        bookId: String,
        storedTitle: String,
        sourceFilename: String?,
        firstPagesText: List<String>,
    ): LiteraturePaperMeta {
        val info = runCatching { pdf?.documentInformation }.getOrNull()
        val pdfTitle = info?.title?.let(LiteratureTextNorm::decodeHtmlEntities)?.takeIf { isUsefulTitle(it) }
        val pdfAuthor = info?.author?.let(LiteratureTextNorm::decodeHtmlEntities)?.takeIf { isUsefulAuthor(it) }
        val pdfSubjectYear = listOfNotNull(info?.subject, info?.keywords, info?.title)
            .firstNotNullOfOrNull { yearToken.find(it)?.groupValues?.get(1) }

        val fileStem = (sourceFilename ?: storedTitle)
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""(?i)\.pdf$"""), "")
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        val strippedStem = fileStem.replace(coursePrefix, "").trim().ifEmpty { fileStem }

        var fileAuthor: String? = null
        var fileYear: String? = null
        var fileTitle: String? = null
        filenameAuthorYear.matchEntire(strippedStem)?.let { m ->
            fileAuthor = m.groupValues[1].trim().takeIf { isUsefulAuthor(it) }
            fileYear = m.groupValues[2]
            fileTitle = m.groupValues[3].trim().takeIf { isUsefulTitle(it) }
        } ?: filenameYearAuthor.matchEntire(strippedStem)?.let { m ->
            fileYear = m.groupValues[1]
            fileAuthor = m.groupValues[2].trim().takeIf { isUsefulAuthor(it) }
            fileTitle = m.groupValues[3].trim().takeIf { isUsefulTitle(it) }
        } ?: filenameDashAuthor.matchEntire(strippedStem)?.let { m ->
            val left = m.groupValues[1].trim()
            val right = m.groupValues[2].trim()
            if (left.split(' ').size in 1..4 && isUsefulAuthor(left)) {
                fileAuthor = left
                fileYear = yearToken.find(right)?.groupValues?.get(1)
                fileTitle = right.replace(parenYear, "").trim().takeIf { isUsefulTitle(it) }
            }
        }
        if (fileYear == null) fileYear = yearToken.find(strippedStem)?.groupValues?.get(1)

        val page1 = firstPagesText.firstOrNull().orEmpty()
        val frontText = firstPagesText.take(2).joinToString("\n")
        val pageAuthor = jstorAuthor.find(page1)?.groupValues?.get(1)
            ?.let(LiteratureTextNorm::decodeHtmlEntities)?.takeIf { isUsefulAuthor(it) }
            ?: coverAuthor.find(page1)?.groupValues?.get(1)
                ?.let(LiteratureTextNorm::decodeHtmlEntities)?.takeIf { isUsefulAuthor(it) }
            ?: byLine.find(frontText.take(1500))?.groupValues?.get(1)
                ?.let(LiteratureTextNorm::decodeHtmlEntities)?.takeIf { isUsefulAuthor(it) }

        val pageTitle = extractJstorOrCoverTitle(page1)?.let(LiteratureTextNorm::decodeHtmlEntities)
            ?.takeIf { isUsefulTitle(it) }

        val pageYear = jstorSourceYear.find(page1)?.groupValues?.get(1)
            ?: parenYear.find(frontText.take(1800))?.groupValues?.get(1)

        val decodedStored = LiteratureTextNorm.decodeHtmlEntities(storedTitle)
        val finalTitle = (pdfTitle ?: pageTitle ?: fileTitle ?: decodedStored.ifBlank { strippedStem }).take(180)
        val finalAuthor = (pageAuthor ?: pdfAuthor ?: fileAuthor).orEmpty().take(80)
        val finalYear = (pageYear ?: fileYear ?: pdfSubjectYear).orEmpty().take(10)
        return LiteraturePaperMeta(bookId, finalTitle, finalAuthor, finalYear)
    }

    private fun extractJstorOrCoverTitle(page1: String): String? {
        val titlePrefix = Regex("""(?im)^(?:Article\s+)?Title\s*[:：]\s*(.+)$""")
        titlePrefix.find(page1)?.groupValues?.get(1)?.trim()?.takeIf { isUsefulTitle(it) }?.let { return it }
        val lines = page1.lines().map(String::trim).filter(String::isNotEmpty)
        val authorIdx = lines.indexOfFirst { jstorAuthor.matches(it) }
        if (authorIdx > 0) {
            val candidate = lines[authorIdx - 1]
            if (candidate.length in 6..180 && isUsefulTitle(candidate)) return candidate
        }
        return null
    }

    private fun isUsefulTitle(s: String): Boolean =
        s.length in 3..220 && !junkTitle.matches(s) && !nonTitleLine.containsMatchIn(s) && !s.endsWith(".pdf", true)

    private fun isUsefulAuthor(s: String): Boolean {
        val clean = s.trim()
        if (clean.length !in 2..80) return false
        if (junkAuthor.matches(clean)) return false
        if (Regex("""(?i)^(?:HIST|ECON|COMP|MATH|WEEK|W\d|CH\d|CHAPTER|MODULE|LECTURE)\d*""").matches(clean)) return false
        return clean.any(Char::isLetter)
    }
}

/** Each region is read down one column of the original PDF, independently of the saved reader text. */
internal data class GuideRegion(
    val text: String,
    val printedPage: String? = null,
    val sourceOrder: String = text,
    val wideGapRanges: List<IntRange> = emptyList(),
) {
    private val continuous by lazy { LiteratureTextNorm.normalize(sourceOrder) }
    fun containsSentence(quote: String) = LiteratureTextNorm.normalize(quote) in continuous
    fun overlapsWideGap(start: Int, end: Int): Boolean = wideGapRanges.any { r -> r.first < end && start <= r.last }
}

internal object LiteraturePdfLayout {
    private data class Word(val text: String, val x: Float, val right: Float, val y: Float, val height: Float)
    private data class Row(val y: Float, val words: MutableList<Word> = mutableListOf())
    private val folio = Regex("""(?i)^(?:p(?:age)?\.?\s*)?(\d{1,4}|[ivxlcdm]{1,6})$""")

    fun extract(pdf: BookPdfDocument, page: Int): List<GuideRegion> {
        val words = mutableListOf<Word>()
        val stripper = object : BookPdfStripper() {
            override fun writeString(text: String, textPositions: List<BookPdfTextPosition>) {
                var start = 0f
                var end = 0f
                var y = 0f
                var height = 0f
                val token = StringBuilder()
                fun flush() {
                    if (token.isNotEmpty() && token.any { !it.isWhitespace() }) {
                        words += Word(token.toString(), start, end, y, height)
                    }
                    token.clear()
                }
                for (glyph in textPositions) {
                    val u = glyph.unicode
                    if (u.isNullOrEmpty() || u.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) {
                        flush()
                        continue
                    }
                    val x = glyph.xDirAdj
                    val h = glyph.heightDir.takeIf { it > 0f } ?: glyph.fontSizeInPt.coerceAtLeast(1f)
                    if (token.isNotEmpty() && x - end > glyph.widthOfSpace.coerceAtLeast(1f) * 0.6f) flush()
                    if (token.isEmpty()) {
                        start = x
                        y = glyph.yDirAdj
                        height = h
                    } else if (h > height) {
                        height = h
                    }
                    token.append(u)
                    end = x + glyph.widthDirAdj
                }
                flush()
            }
        }
        stripper.startPage = page
        stripper.endPage = page
        stripper.sortByPosition = true
        stripper.writeText(pdf, StringWriter())
        if (words.isEmpty()) return emptyList()
        val rect = pdf.getPage(page - 1).cropBox
        val width = if (pdf.getPage(page - 1).rotation % 180 == 0) rect.width else rect.height
        val rows = mutableListOf<Row>()
        for (w in words.sortedBy { it.y }) {
            val row = rows.lastOrNull()?.takeIf { abs(it.y - w.y) <= maxOf(1.5f, w.height * 0.35f) }
                ?: Row(w.y).also(rows::add)
            row.words += w
        }
        rows.forEach { it.words.sortBy(Word::x) }
        val positiveHeights = words.map { it.height }.filter { it > 0f }.sorted()
        val bodyHeight = if (positiveHeights.isNotEmpty()) positiveHeights[positiveHeights.size / 2] else 10f
        // A gutter separates runs on many of the same baselines. Headers spanning the page may cross it.
        val gutter = (12..88).map { width * it / 100f }.mapNotNull { cut ->
            val halfGap = maxOf(6f, width * 0.012f)
            val left = words.filter { it.right <= cut - halfGap }
            val right = words.filter { it.x >= cut + halfGap }
            if (left.size < 3 || right.size < 3) return@mapNotNull null
            val overlapTop = maxOf(left.minOf { it.y }, right.minOf { it.y })
            val overlapBottom = minOf(left.maxOf { it.y }, right.maxOf { it.y })
            if (overlapBottom - overlapTop < bodyHeight * 2) return@mapNotNull null
            val overlapping = rows.filter { it.y in (overlapTop - bodyHeight)..(overlapBottom + bodyHeight) }
            val support = minOf(overlapping.count { row -> row.words.any { it.right < cut } },
                overlapping.count { row -> row.words.any { it.x > cut } })
            val crosses = overlapping.count { row -> row.words.any { it.right > cut - halfGap && it.x < cut + halfGap } }
            if (support < 3 || support <= crosses * 5) return@mapNotNull null
            val clearance = overlapping.map { row ->
                row.words.minOf { maxOf(it.x - cut, cut - it.right) }.coerceIn(0f, width * 0.12f)
            }.average()
            cut to (clearance * (support - crosses))
        }.maxByOrNull { it.second }?.first
        val columns = if (gutter == null) listOf(words) else words.partition { (it.x + it.right) / 2 < gutter }.toList()
        return columns.mapIndexedNotNull { index, column ->
            val members = column.toHashSet()
            val lines = rows.mapNotNull { row -> row.words.filter { it in members }.takeIf { it.isNotEmpty() } }
            if (lines.isEmpty()) return@mapIndexedNotNull null
            val edgeLines = (lines.take(2) + lines.takeLast(2))
            val label = edgeLines.firstNotNullOfOrNull { line ->
                folio.matchEntire(line.joinToString(" ") { it.text })?.groupValues?.get(1)
            } ?: edgeLines.firstNotNullOfOrNull { line ->
                val candidates = listOf(line.first() to line.getOrNull(1), line.last() to line.getOrNull(line.lastIndex - 1))
                candidates.firstOrNull { (w, neighbor) ->
                    folio.matches(w.text) && neighbor != null &&
                        maxOf(neighbor.x - w.right, w.x - neighbor.right) > bodyHeight * 2
                }?.first?.text
            }
            val wideGapRanges = mutableListOf<IntRange>()
            val text = buildString {
                var previousY: Float? = null
                for (line in lines) {
                    val y = line.first().y
                    val size = line.map { it.height }.sorted()[line.size / 2]
                    if (size < bodyHeight * 0.80f) {
                        if (isNotEmpty()) append("\n\n")
                        continue
                    }
                    if (previousY != null && y - previousY > bodyHeight * 2.7f && isNotEmpty()) append('\n')
                    val hasWideGap = line.zipWithNext().any { (a, b) -> b.x - a.right > bodyHeight * 1.5f }
                    val lineStart = length
                    line.forEachIndexed { i, w ->
                        if (i > 0 && (w.x - line[i - 1].right > size * 0.10f ||
                                w.text.first().isLetterOrDigit() && line[i - 1].text.last().isLetterOrDigit() &&
                                w.x > line[i - 1].right + 0.5f)) append(' ')
                        append(if (w.text.any { it in '\uFB00'..'\uFB06' }) Normalizer.normalize(w.text, Normalizer.Form.NFKC) else w.text)
                    }
                    val lineEnd = length
                    if (hasWideGap && lineEnd > lineStart) wideGapRanges += lineStart until lineEnd
                    append('\n')
                    previousY = y
                }
            }.trimEnd()
            if (text.isBlank()) return@mapIndexedNotNull null
            val reference = object : BookPdfStripper() {
                override fun processTextPosition(position: BookPdfTextPosition) {
                    val center = position.xDirAdj + position.widthDirAdj / 2
                    if (gutter == null || (center < gutter) == (index == 0)) super.processTextPosition(position)
                }
            }.apply { startPage = page; endPage = page; sortByPosition = true }.getText(pdf)
            GuideRegion(text, label, reference, wideGapRanges)
        }
    }
}
