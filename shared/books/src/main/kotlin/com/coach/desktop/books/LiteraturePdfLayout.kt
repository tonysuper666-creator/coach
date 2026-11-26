package com.coach.desktop.books

import java.io.StringWriter
import java.text.Normalizer
import kotlin.math.abs

/** Each region is read down one column of the original PDF, independently of the saved reader text. */
internal data class GuideRegion(val text: String, val printedPage: String?, val sourceOrder: String = text) {
    private val continuous by lazy { normalized(sourceOrder) }
    fun containsSentence(quote: String) = normalized(quote) in continuous

    private fun normalized(s: String) = s.replace(ligature) { Normalizer.normalize(it.value, Normalizer.Form.NFKC) }
        .replace(lineHyphen, "").replace(whitespace, " ").trim()

    private companion object {
        val ligature = Regex("[ﬀ-ﬆ]")
        val lineHyphen = Regex("""(?<=\p{L})-\s*\n\s*(?=\p{Ll})""")
        val whitespace = Regex("""\s+""")
    }
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
                    if (token.isNotBlank()) words += Word(token.toString(), start, end, y, height)
                    token.clear()
                }
                for (glyph in textPositions) {
                    val x = glyph.xDirAdj
                    if (glyph.unicode.isNullOrBlank()) { flush(); continue }
                    if (token.isNotEmpty() && x - end > glyph.widthOfSpace.coerceAtLeast(1f) * 0.6f) flush()
                    if (token.isEmpty()) { start = x; y = glyph.yDirAdj; height = glyph.heightDir }
                    token.append(glyph.unicode)
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
        // A gutter separates runs on many of the same baselines. Headers spanning the page may cross it.
        // Measure vertical whitespace as well as aligned baselines: marginal notes and facing
        // pages often have different line spacing. Occasional running titles can span the gutter.
        val bodyHeight = words.map { it.height }.sorted()[words.size / 2]
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
            val text = buildString {
                var previousY: Float? = null
                for (line in lines) {
                    val y = line.first().y
                    val size = line.map { it.height }.sorted()[line.size / 2]
                    if (size < bodyHeight * 0.80f) { append("\n\n"); continue }
                    if (previousY != null && y - previousY > bodyHeight * 2.7f) append('\n')
                    line.forEachIndexed { i, w ->
                        if (i > 0 && (w.x - line[i - 1].right > size * 0.10f ||
                                w.text.first().isLetterOrDigit() && line[i - 1].text.last().isLetterOrDigit() &&
                                w.x > line[i - 1].right + 0.5f)) append(' ')
                        append(if (w.text.any { it in '\uFB00'..'\uFB06' }) Normalizer.normalize(w.text, Normalizer.Form.NFKC) else w.text)
                    }
                    append('\n')
                    previousY = y
                }
            }.trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            // Ask PDFBox for the original region a second time. It resolves its own word
            // and line breaks; a string manufactured by our sampling layout cannot pass alone.
            val reference = object : BookPdfStripper() {
                override fun processTextPosition(position: BookPdfTextPosition) {
                    val center = position.xDirAdj + position.widthDirAdj / 2
                    if (gutter == null || (center < gutter) == (index == 0)) super.processTextPosition(position)
                }
            }.apply { startPage = page; endPage = page; sortByPosition = true }.getText(pdf)
            GuideRegion(text, label, reference)
        }
    }
}
