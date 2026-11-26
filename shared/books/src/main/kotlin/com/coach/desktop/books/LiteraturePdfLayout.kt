package com.coach.desktop.books

import java.io.StringWriter
import java.text.Normalizer

/** Each region is read down one column of the original PDF, independently of the saved reader text. */
internal data class GuideRegion(val text: String, val printedPage: String?, val sourceOrder: String = text) {
    private val continuous by lazy { normalized(sourceOrder) }
    fun containsSentence(quote: String): Boolean {
        val needle = normalized(quote)
        if (needle.isEmpty()) return false
        var at = continuous.indexOf(needle)
        fun latin(c: Char?) = c != null && c.code < 0x3000 && c.isLetterOrDigit()
        while (at >= 0) {
            val embeddedStart = latin(needle.first()) && latin(continuous.getOrNull(at - 1))
            val embeddedEnd = latin(needle.last()) && latin(continuous.getOrNull(at + needle.length))
            if (!embeddedStart && !embeddedEnd) return true
            at = continuous.indexOf(needle, at + 1)
        }
        return false
    }

    private fun normalized(s: String) = s.replace(ligature) { Normalizer.normalize(it.value, Normalizer.Form.NFKC) }
        .replace(lineHyphen, "").replace(whitespace, " ").trim()

    private companion object {
        val ligature = Regex("[ﬀ-ﬆ]")
        val lineHyphen = Regex("""(?<=\p{L})-\s*\n\s*(?=\p{Ll})""")
        val whitespace = Regex("""\s+""")
    }
}

internal object LiteraturePdfLayout {
    // Find a vertical gap on the rendered sheet. Test for text on the same
    // scanlines on both sides so outer margins and indents do not create columns.
    fun read(image: BookBitmap, ocr: BookOcr, control: BookImportControl, hint: Float?): List<GuideRegion> {
        val step = maxOf(1, image.width / 520)
        val bins = image.width / step
        if (bins < 4) return emptyList()
        val ink = IntArray(bins)
        val rows = mutableListOf<Pair<Int, IntRange>>()
        for (y in 0 until image.height step maxOf(1, image.height / 650)) {
            var left = bins
            var right = -1
            for (i in 0 until bins) {
                val pixel = bookBitmapPixel(image, i * step, y)
                val light = ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
                if (light < 140) { ink[i]++; left = minOf(i, left); right = i }
            }
            if (right > left) rows += y to (left..right)
        }
        val margin = maxOf(3, bins / 14)
        val radius = maxOf(1, bins / 140)
        val cut = (margin until bins - margin).mapNotNull { i ->
            val crossing = rows.filter { (_, span) -> span.first < i - margin && span.last > i + margin }
            val support = crossing.size
            if (hint == null && crossing.zipWithNext().count { (a,b) -> b.first - a.first > image.height / 320 } < 2) return@mapNotNull null
            if (support < 4) return@mapNotNull null
            val density = (i-radius..i+radius).sumOf { ink[it] }.toDouble() / (2 * radius + 1)
            if (density > support * 0.08) return@mapNotNull null
            val left = (0 until i-radius).maxOf { ink[it] }
            val right = (i+radius+1 until bins).maxOf { ink[it] }
            if (left < 2 || right < 2) return@mapNotNull null
            val target = hint ?: 0.5f
            i to (support / (1 + density) / (1 + 4 * kotlin.math.abs(i.toDouble() / bins - target)))
        }.maxByOrNull { it.second }?.first?.times(step)
        // If the physical sheet is a spread or the text positions show two columns,
        // never trust a transcript assembled across the unresolved gutter.
        if (cut == null && (hint != null || image.width > image.height * 1.15f)) return emptyList()
        val limits = listOfNotNull(0, cut, image.width)
        return limits.zipWithNext().mapNotNull { (left, right) ->
            val region = if (cut == null) image else bookGuideCrop(image, left, right - left)
            val raw = try { ocr.recognize(region, control) } finally { if (region !== image) releaseBookBitmap(region) }
            val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
            text.takeIf(String::isNotBlank)?.let { GuideRegion(it, LiteratureFolios.visible(it)) }
        }
    }

    /** Read numeric text at the physical sheet edges, never the PDF page-label dictionary. */
    fun marginFolio(pdf: BookPdfDocument, page: Int): String? {
        val sheet = pdf.getPage(page - 1)
        val height = if (sheet.rotation % 180 == 0) sheet.cropBox.height else sheet.cropBox.width
        val rows = sortedMapOf<Int, MutableList<BookPdfTextPosition>>()
        val stripper = object : BookPdfStripper() {
            override fun writeString(text: String, textPositions: List<BookPdfTextPosition>) {
                for (glyph in textPositions) {
                    val y = glyph.yDirAdj
                    if (y < height * 0.12f || y > height * 0.88f) {
                        rows.getOrPut((y / 3).toInt()) { mutableListOf() }.add(glyph)
                    }
                }
            }
        }.apply { startPage = page; endPage = page; sortByPosition = true }
        stripper.writeText(pdf, StringWriter())
        val lines = rows.values.map { row -> buildString {
            var right = -1f
            for (glyph in row.sortedBy { it.xDirAdj }) {
                if (right >= 0 && glyph.xDirAdj - right > 1f) append(' ')
                append(glyph.unicode.orEmpty())
                right = glyph.xDirAdj + glyph.widthDirAdj
            }
        } }
        return LiteratureFolios.visible(lines.joinToString("\n"))
    }

    /** Geometry only flags ambiguous spreads. All citation text comes from rendered pixels. */
    fun gutterHint(pdf: BookPdfDocument, page: Int): Float? {
        val lines = mutableMapOf<Int, MutableList<Pair<Float, Float>>>()
        val stripper = object : BookPdfStripper() {
            override fun writeString(text: String, textPositions: List<BookPdfTextPosition>) {
                for (glyph in textPositions) if (!glyph.unicode.isNullOrBlank()) {
                    lines.getOrPut((glyph.yDirAdj / 4).toInt()) { mutableListOf() }
                        .add(glyph.xDirAdj to glyph.xDirAdj + glyph.widthDirAdj)
                }
            }
        }.apply { startPage = page; endPage = page; sortByPosition = true }
        stripper.writeText(pdf, StringWriter())
        val sheet = pdf.getPage(page - 1)
        val width = if (sheet.rotation % 180 == 0) sheet.cropBox.width else sheet.cropBox.height
        val gaps = lines.values.map { row -> row.sortedBy { it.first }.zipWithNext().mapNotNull { (a,b) ->
            if (b.first - a.second > width * 0.035f) a.second..b.first else null
        } }
        val votes = (15..85).associateWith { x -> gaps.count { row -> row.any { x * width / 100 in it } } }
        val best = votes.values.maxOrNull() ?: return null
        if (best < maxOf(3, lines.size / 4)) return null
        return votes.filterValues { it == best }.keys.average().toFloat() / 100f
    }
}
