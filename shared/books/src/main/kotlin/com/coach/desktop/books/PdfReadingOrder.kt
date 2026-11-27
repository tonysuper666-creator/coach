package com.coach.desktop.books

import java.io.IOException
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Adjust PDFBox's article groups before it generates whitespace, typography and UTF-16 offsets. */
internal open class ReadingOrderStripper(private val useReadingOrder: Boolean = true) : BookPdfStripper() {
    override fun writePage() {
        if (!useReadingOrder) {
            super.writePage()
            return
        }
        val page = document.getPage(currentPageNo - 1)
        if (page.rotation == 0) {
            val height = page.cropBox.height
            for (article in charactersByArticle) {
                val margins = marginRows(article, height)
                if (margins.isEmpty()) continue
                val edges = RepeatedPdfMargins.keys(document)
                for ((key, row) in margins) {
                    if (key in edges) article.removeAll(row.toHashSet())
                }
            }
            val article = charactersByArticle.singleOrNull()
            if (article != null) {
                val groups = columnGroups(article, page.cropBox.width)
                if (groups.isNotEmpty()) {
                    charactersByArticle.clear()
                    charactersByArticle.addAll(groups)
                    try {
                        super.writePage()
                    } finally {
                        charactersByArticle.clear()
                        charactersByArticle.add(article)
                    }
                    return
                }
            }
        }
        super.writePage()
    }
}

private data class MarginKey(val baseline: Int, val text: String)

private val folioSuffix = Regex("""\d+\s*/\s*\d+$""")
private val isolatedNumber = Regex("""^\d+$""")

private fun marginRows(positions: List<BookPdfTextPosition>, height: Float): Map<MarginKey, List<BookPdfTextPosition>> {
    val rows = positions.asSequence().filter {
        it.dir == 0f && (it.yDirAdj < height * 0.07f || it.yDirAdj > height * 0.94f)
    }.groupBy { it.yDirAdj.roundToInt() }
    return rows.entries.associate { (baseline, row) ->
        // A folio may be centred between a journal name and a URL. Keep numbers within titles.
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        fun finishField() {
            val text = field.toString().trim()
            if (text.isNotEmpty()) fields += if (isolatedNumber.matches(text)) "#" else text.replace(folioSuffix, "#")
            field.clear()
        }
        var right = Float.NEGATIVE_INFINITY
        for (glyph in row.sortedBy { it.xDirAdj }) {
            if (glyph.xDirAdj - right > maxOf(12f, glyph.heightDir * 2)) finishField()
            field.append(glyph.unicode.orEmpty())
            right = glyph.xDirAdj + glyph.widthDirAdj
        }
        finishField()
        val key = fields.joinToString("|")
        MarginKey(baseline, key) to row
    }
}

private object RepeatedPdfMargins {
    private val cache = WeakHashMap<BookPdfDocument, Set<MarginKey>>()

    fun keys(pdf: BookPdfDocument): Set<MarginKey> {
        synchronized(cache) { cache[pdf]?.let { return it } }
        val counts = mutableMapOf<MarginKey, Int>()
        if (pdf.numberOfPages >= 3) {
            val sampler = object : BookPdfStripper() {
                override fun writePage() {
                    val page = pdf.getPage(currentPageNo - 1)
                    if (page.rotation != 0) return
                    charactersByArticle.flatMap { marginRows(it, page.cropBox.height).keys }.toSet()
                        .forEach { counts[it] = counts.getOrDefault(it, 0) + 1 }
                }
            }
            for (page in 1..minOf(pdf.numberOfPages, 5)) {
                sampler.startPage = page
                sampler.endPage = page
                try {
                    sampler.getText(pdf)
                } catch (_: IOException) {
                    // A damaged sample must not make healthy pages unreadable.
                } catch (_: IllegalArgumentException) {
                    // Invalid page objects can also fail in the PDF decoder.
                }
            }
        }
        val repeated = counts.filterValues { it >= 3 }.keys.toSet()
        synchronized(cache) { cache[pdf] = repeated }
        return repeated
    }
}

/** A continuous empty central gutter with multiple prose lines on both sides is evidence of
 * two columns. A line crossing the gutter ends the band (e.g. a full-width abstract or caption). */
private fun columnGroups(positions: List<BookPdfTextPosition>, width: Float): List<MutableList<BookPdfTextPosition>> {
    if (positions.size < 160 || width <= 0f || positions.any { it.dir != 0f }) return emptyList()
    val clearance = width * 0.006f
    val rows = mutableListOf<MutableList<BookPdfTextPosition>>()
    for (glyph in positions.sortedBy { it.yDirAdj }) {
        val row = rows.lastOrNull()
        if (row == null || abs(glyph.yDirAdj - row.first().yDirAdj) > 1.5f) rows.add(mutableListOf(glyph))
        else row.add(glyph)
    }
    val centered = splitBands(rows, width / 2, clearance)
    if (centered.isNotEmpty()) return centered

    // A journal's narrow metadata column can sit far from the centre. Only try a
    // second cut if there is a page-wide strip of whitespace between printed glyphs.
    val bins = 500
    val occupied = BooleanArray(bins + 1)
    for (glyph in positions) {
        val first = ((glyph.xDirAdj - clearance) * bins / width).roundToInt().coerceIn(0, bins)
        val last = ((glyph.xDirAdj + glyph.widthDirAdj + clearance) * bins / width).roundToInt().coerceIn(0, bins)
        for (i in first..last) occupied[i] = true
    }
    val cut = (bins / 5..bins * 4 / 5).filter { !occupied[it] }.minByOrNull { abs(it - bins / 2) } ?: return emptyList()
    return splitBands(rows, width * cut / bins, clearance, continuousGutter = true)
}

private fun splitBands(
    rows: List<List<BookPdfTextPosition>>, cut: Float, clearance: Float, continuousGutter: Boolean = false,
): List<MutableList<BookPdfTextPosition>> {
    val groups = mutableListOf<MutableList<BookPdfTextPosition>>()
    val band = mutableListOf<List<BookPdfTextPosition>>()
    var ordinary = mutableListOf<BookPdfTextPosition>()
    var split = false
    fun flushBand() {
        val left = mutableListOf<BookPdfTextPosition>()
        val right = mutableListOf<BookPdfTextPosition>()
        val leftLines = mutableListOf<Float>()
        val rightLines = mutableListOf<Float>()
        for (row in band) {
            val (a, b) = row.partition { it.xDirAdj < cut }
            left.addAll(a)
            right.addAll(b)
            if (a.count { it.unicode.orEmpty().any(Char::isLetter) } >= 16) leftLines.add(row.first().yDirAdj)
            if (b.count { it.unicode.orEmpty().any(Char::isLetter) } >= 16) rightLines.add(row.first().yDirAdj)
        }
        // A full-width figure can leave just three or four prose lines in each column.
        val minOverlap = if (continuousGutter) 16f else 20f
        if (leftLines.size >= 3 && rightLines.size >= 3 &&
            minOf(leftLines.last(), rightLines.last()) - maxOf(leftLines.first(), rightLines.first()) >= minOverlap) {
            if (ordinary.isNotEmpty()) groups.add(ordinary)
            groups.add(left)
            groups.add(right)
            ordinary = mutableListOf()
            split = true
        } else band.forEach(ordinary::addAll)
        band.clear()
    }
    for (row in rows) {
        if (row.any { it.xDirAdj < cut + clearance && it.xDirAdj + it.widthDirAdj > cut - clearance }) {
            if (band.isNotEmpty()) flushBand()
            ordinary.addAll(row)
        } else band.add(row)
    }
    flushBand()
    if (!split) return emptyList()
    if (ordinary.isNotEmpty()) groups.add(ordinary)
    return groups
}
