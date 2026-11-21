package com.coach.desktop.books

import com.coach.domain.reader.PdfPageFrame
import com.coach.domain.reader.PdfPointRect
import com.coach.domain.reader.ReaderGeometry
import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderResource
import com.coach.domain.reader.ReaderTextGeometry
import com.coach.domain.reader.ReaderTextRange
import com.coach.domain.reader.ReaderTextUnit
import java.io.StringWriter
import java.text.Normalizer
import java.util.IdentityHashMap

internal data class PageLineTypography(
    val page: Int,
    val text: String,
    val rowText: String,
    val fontSizePt: Double,
    val bold: Boolean,
    val yNorm: Double,
)

internal data class FastPdfPageText(
    val text: String,
    val lines: List<PageLineTypography>,
)

/** Geometry uses the existing reader contract; TON-1665 owns its on-disk page model. */
internal data class PdfPageText(
    val frame: PdfPageFrame,
    val unit: ReaderTextUnit,
    /** A run PDFBox rewrote in an unrecognised way is left unboxed, never guessed. */
    val unmappedRanges: List<ReaderTextRange>,
) {
    fun pageLayout(): PageTextLayout = PageTextLayout(
        checkNotNull(unit.resource.pdfPageIndex0) + 1,
        frame.cropBox.right - frame.cropBox.left,
        frame.cropBox.top - frame.cropBox.bottom,
        unit.spans.flatMap { span -> span.geometry.boxes.map { box ->
            PageTextBox(span.range.startUtf16, span.range.endUtf16, box)
        } },
    )
}

/** Reusable single-thread stripper for plain UTF-16 PDF page text without allocating per-glyph/line metadata. */
internal class ReusablePlainPdfTextExtractor {
    private val stripper = BookPdfStripper().apply {
        sortByPosition = true
        paragraphEnd = "\n\n"
    }

    fun extractPage(document: BookPdfDocument, page: Int): String {
        stripper.startPage = page
        stripper.endPage = page
        val raw = StringWriter()
        stripper.writeText(document, raw)
        return TextbookExtraction.clean(raw.toString())
    }
}

/** One extraction pass, with exactly the text and cleaning used by TextbookExtraction. */
internal object PdfPageTextExtraction {
    fun extract(document: BookPdfDocument, page: Int): PdfPageText = extract(document, page, null)

    fun extractPlainText(document: BookPdfDocument, page: Int): String {
        require(page in 1..document.numberOfPages)
        require(document.currentAccessPermission.canExtractContent()) { "这本 PDF 限制了文字提取。" }
        return ReusablePlainPdfTextExtractor().extractPage(document, page)
    }

    /**
     * Fast Tier 1 extraction: emits UTF-16 text identical to [extract] while capturing only
     * compact per-line typography (font size, bold, normalized Y, and Y-row merged text for TOC pages),
     * avoiding 1M+ per-glyph box allocations.
     */
    fun extractPlainTextAndTypography(document: BookPdfDocument, page: Int): FastPdfPageText {
        require(page in 1..document.numberOfPages)
        require(document.currentAccessPermission.canExtractContent()) { "这本 PDF 限制了文字提取。" }
        val source = document.getPage(page - 1)
        val box = source.cropBox
        val sideways = Math.floorMod(source.rotation, 360) in setOf(90, 270)
        val pageHeight = (if (sideways) box.width else box.height).toDouble().coerceAtLeast(1.0)
        return FastTypographyStripper(page, pageHeight).extract(document)
    }

    /** Review only: CFF math ink can extend below the baseline used by BookPdfTextPosition.heightDir.
     * Keep ordinary extraction/offsets unchanged. One raster pixel cushions outline bounds.
     * This is text geometry, not proof that a selected object or its labels are complete.
     */
    fun extractForVisualReview(document: BookPdfDocument, page: Int, rasterScale: Double): PdfPageText {
        require(rasterScale.isFinite() && rasterScale > 0 && rasterScale <= 2)
        return extract(document, page, 1 / rasterScale)
    }

    private fun extract(document: BookPdfDocument, page: Int, inkPadding: Double?): PdfPageText {
        require(page in 1..document.numberOfPages)
        require(document.currentAccessPermission.canExtractContent()) { "这本 PDF 限制了文字提取。" }
        return PositionStripper(page, frame(document, page), inkPadding).extract(document)
    }

    fun restore(document: BookPdfDocument, page: BookPage, layout: PageTextLayout): ReaderTextUnit? {
        val frame = frame(document, page.number)
        if (layout.page != page.number || layout.pageWidth != frame.cropBox.right - frame.cropBox.left ||
            layout.pageHeight != frame.cropBox.top - frame.cropBox.bottom) return null
        BookIllustrationAnchors.requireValidTextLayouts(mapOf(page.number to page.text), listOf(layout))
        val spans = layout.boxes.groupBy { ReaderTextRange(it.startOffset, it.endOffset) }.map { (range, boxes) ->
            ReaderTextGeometry(range, ReaderGeometry(frame, boxes.map(PageTextBox::bounds)))
        }
        return ReaderTextUnit(ReaderResource(pdfPageIndex0 = page.number - 1), page.text, spans)
    }

    private fun frame(document: BookPdfDocument, page: Int): PdfPageFrame {
        val source = document.getPage(page - 1)
        fun BookPdfRectangle.points() = PdfPointRect(lowerLeftX.toDouble(), lowerLeftY.toDouble(),
            upperRightX.toDouble(), upperRightY.toDouble())
        return PdfPageFrame(source.mediaBox.points(), source.cropBox.points(), Math.floorMod(source.rotation, 360))
    }

    private class PositionStripper(private val page: Int, private val frame: PdfPageFrame,
        private val inkPadding: Double?) : BookPdfStripper() {
        private val raw = StringWriter()
        private data class Span(val start: Int, val end: Int, val bounds: ReaderRect)
        private val spans = mutableListOf<Span>()
        private val unmapped = mutableListOf<IntRange>()
        private data class Ink(val unicode: String, val bounds: ReaderRect?)
        private val cffInk = IdentityHashMap<BookPdfTextPosition, Ink>()

        override fun processTextPosition(position: BookPdfTextPosition) {
            if (inkPadding != null && position.font is BookPdfCffFont) {
                cffInk[position] = Ink(position.unicode, cffBounds(position, inkPadding))
            }
            super.processTextPosition(position)
        }

        private fun cffBounds(position: BookPdfTextPosition, padding: Double): ReaderRect? {
            val font = position.font as BookPdfCffFont
            if (cffInk.size >= 100_000 || !font.isEmbedded || font.isDamaged || font.isVertical ||
                position.dir != 0f || position.characterCodes.size != 1 ||
                graphicsState.textState.renderingMode != BookPdfRenderingMode.FILL || graphicsState.softMask != null) return null
            return try {
                val code = position.characterCodes.single()
                if (!bookCffHasGlyph(font, code)) return null
                val ink = measureBookCffInk(font, code, position)
                if (ink == null) return if (position.unicode.all { it.isWhitespace() }) legacyBounds(position) else null
                val width = frame.cropBox.right - frame.cropBox.left
                val height = frame.cropBox.top - frame.cropBox.bottom
                val left = ink.minX - padding; val right = ink.maxX + padding
                val top = height - ink.maxY - padding; val bottom = height - ink.minY + padding
                if (listOf(left, top, right, bottom, width, height).any { !it.isFinite() } ||
                    width <= 0 || height <= 0 || left < 0 || top < 0 || right > width || bottom > height ||
                    left >= right || top >= bottom) null
                else ReaderRect(left / width, top / height, (right - left) / width, (bottom - top) / height)
            } catch (_: java.io.IOException) { null }
            catch (_: IllegalArgumentException) { null }
        }

        init {
            startPage = page
            endPage = page
            sortByPosition = true
            paragraphEnd = "\n\n"
        }

        fun extract(document: BookPdfDocument): PdfPageText {
            writeText(document, raw)
            val text = raw.toString()
            // Boundary map includes generated spaces/newlines, CRLF folding and removed NULs.
            val offsets = IntArray(text.length + 1)
            var cleanedLength = 0
            for (i in text.indices) {
                if (text[i] != '\u0000' && !(text[i] == '\n' && i > 0 && text[i - 1] == '\r')) cleanedLength++
                offsets[i + 1] = cleanedLength
            }
            val clean = TextbookExtraction.clean(text)
            val geometry = spans.mapNotNull { span ->
                val start = offsets[span.start]
                val end = offsets[span.end]
                if (start == end) null else ReaderTextGeometry(ReaderTextRange(start, end),
                    ReaderGeometry(frame, listOf(span.bounds)))
            }
            return PdfPageText(frame, ReaderTextUnit(ReaderResource(pdfPageIndex0 = page - 1), clean, geometry),
                unmapped.mapNotNull { range ->
                    val start = offsets[range.first]
                    val end = offsets[range.last + 1]
                    if (start == end) null else ReaderTextRange(start, end)
                })
        }

        override fun writeString(text: String, textPositions: List<BookPdfTextPosition>) {
            val start = raw.buffer.length
            super.writeString(text, textPositions)
            // PDFBox expands presentation ligatures but deliberately preserves full-width CJK,
            // micro signs, etc. Applying NFKC to the whole word would move source offsets.
            val glyphs = textPositions.map { position -> presentationText(position.unicode) to position }
            if (glyphs.joinToString("") { it.first } != text) {
                if (text.isNotEmpty()) unmapped += start until start + text.length
                return
            }
            var offset = start
            for ((unicode, position) in glyphs) {
                if (unicode.isNotEmpty()) {
                    val bounds = bounds(position)
                    if (bounds != null) spans += Span(offset, offset + unicode.length, bounds)
                    else unmapped += offset until offset + unicode.length
                }
                offset += unicode.length
            }
        }

        private fun bounds(position: BookPdfTextPosition): ReaderRect? {
            if (inkPadding != null && position.font is BookPdfCffFont) {
                // PDFBox may merge a combining mark into a position after processTextPosition.
                // Its original outline cannot certify the merged run.
                val ink = cffInk[position] ?: return null
                return ink.bounds.takeIf { ink.unicode == position.unicode }
            }
            return legacyBounds(position)
        }

        private fun legacyBounds(position: BookPdfTextPosition): ReaderRect? {
            val width = frame.cropBox.right - frame.cropBox.left
            val height = frame.cropBox.top - frame.cropBox.bottom
            val direction = position.dir.toInt()
            val sideways = direction == 90 || direction == 270
            val w = if (sideways) height else width
            val h = if (sideways) width else height
            val x0 = position.xDirAdj.toDouble().coerceIn(0.0, w)
            val y0 = (position.yDirAdj - position.heightDir).toDouble().coerceIn(0.0, h)
            val x1 = (position.xDirAdj + position.widthDirAdj).toDouble().coerceIn(0.0, w)
            val y1 = position.yDirAdj.toDouble().coerceIn(0.0, h)
            if (x1 <= x0 || y1 <= y0) return null
            val x = x0 / w; val y = y0 / h
            val bw = (x1 - x0) / w; val bh = (y1 - y0) / h
            // Undo TEXT direction, not page rotation. ReaderGeometry applies page rotation once.
            return when (direction) {
                90 -> ReaderRect(y, maxOf(0.0, 1 - x - bw), bh, bw)
                180 -> ReaderRect(maxOf(0.0, 1 - x - bw), maxOf(0.0, 1 - y - bh), bw, bh)
                270 -> ReaderRect(maxOf(0.0, 1 - y - bh), x, bh, bw)
                else -> ReaderRect(x, y, bw, bh)
            }
        }
    }

    private class FastTypographyStripper(
        private val page: Int,
        private val pageHeight: Double,
    ) : BookPdfStripper() {
        private val raw = StringWriter()
        private data class Segment(
            val text: String,
            val xStart: Double,
            val xEnd: Double,
            val y: Double,
            val height: Double,
            val fontSizePt: Double,
            val bold: Boolean,
        )
        private val segments = mutableListOf<Segment>()
        private val boldFontCache = IdentityHashMap<Any, Boolean>()

        init {
            startPage = page
            endPage = page
            sortByPosition = true
            paragraphEnd = "\n\n"
        }

        private fun isFontBold(pos: BookPdfTextPosition): Boolean {
            val font = pos.font ?: return false
            return boldFontCache.getOrPut(font) {
                val desc = font.fontDescriptor ?: return@getOrPut false
                val name = desc.fontName.orEmpty()
                desc.isForceBold || desc.fontWeight >= 600f ||
                    name.contains("Bold", ignoreCase = true) ||
                    name.contains("Black", ignoreCase = true) ||
                    name.contains("Heavy", ignoreCase = true) ||
                    name.contains("Demi", ignoreCase = true) ||
                    name.contains("Semibold", ignoreCase = true)
            }
        }

        fun extract(document: BookPdfDocument): FastPdfPageText {
            writeText(document, raw)
            val clean = TextbookExtraction.clean(raw.toString())
            val lines = buildTypographyLines()
            return FastPdfPageText(clean, lines)
        }

        override fun writeString(text: String, textPositions: List<BookPdfTextPosition>) {
            super.writeString(text, textPositions)
            val trimmed = text.trim()
            if (trimmed.isEmpty() || textPositions.isEmpty()) return
            var minX = Double.MAX_VALUE
            var maxX = Double.MIN_VALUE
            var ySum = 0.0
            var hSum = 0.0
            var ptSum = 0.0
            var ptCount = 0
            var boldChars = 0
            var visibleChars = 0
            for (pos in textPositions) {
                val x0 = pos.xDirAdj.toDouble()
                val x1 = x0 + pos.widthDirAdj.toDouble()
                if (x0 < minX) minX = x0
                if (x1 > maxX) maxX = x1
                ySum += pos.yDirAdj.toDouble()
                hSum += pos.heightDir.toDouble()
                val pt = pos.fontSizeInPt.toDouble().let { if (it.isFinite() && it > 0.0) it else pos.heightDir.toDouble() }
                if (pt.isFinite() && pt > 0.0) {
                    ptSum += pt
                    ptCount++
                }
                val u = pos.unicode
                if (!u.isNullOrBlank()) {
                    visibleChars++
                    if (isFontBold(pos)) boldChars++
                }
            }
            val n = textPositions.size.coerceAtLeast(1)
            val avgY = ySum / n
            val avgH = (hSum / n).coerceAtLeast(4.0)
            val avgPt = if (ptCount > 0) ptSum / ptCount else avgH
            val isBold = visibleChars > 0 && boldChars * 2 >= visibleChars
            segments += Segment(trimmed, minX, maxX, avgY, avgH, avgPt, isBold)
        }

        private fun buildTypographyLines(): List<PageLineTypography> {
            if (segments.isEmpty()) return emptyList()
            val sorted = segments.sortedWith(compareBy<Segment> { it.y }.thenBy { it.xStart })
            val rows = mutableListOf<MutableList<Segment>>()
            for (seg in sorted) {
                val current = rows.lastOrNull()
                if (current == null) {
                    rows += mutableListOf(seg)
                } else {
                    val rowY = current.first().y
                    val tol = maxOf(3.0, current.first().height * 0.45)
                    if (kotlin.math.abs(seg.y - rowY) <= tol) {
                        current += seg
                    } else {
                        rows += mutableListOf(seg)
                    }
                }
            }
            return rows.flatMap { row ->
                row.sortBy { it.xStart }
                val rowText = buildString {
                    var prevEnd = Double.NaN
                    for (seg in row) {
                        if (isNotEmpty()) {
                            if (prevEnd.isNaN() || seg.xStart - prevEnd > 1.5) append(' ')
                        }
                        append(seg.text)
                        prevEnd = seg.xEnd
                    }
                }.trim()
                val yNorm = (row.first().y / pageHeight).coerceIn(0.0, 1.0)
                // Split into column-local clusters when horizontal gap exceeds ~2.5x line height so
                // multi-column pages keep headings in right columns intact while preserving full rowText for TOC parsing.
                val clusters = mutableListOf<MutableList<Segment>>()
                for (seg in row) {
                    val last = clusters.lastOrNull()
                    if (last == null) {
                        clusters += mutableListOf(seg)
                    } else {
                        val prev = last.last()
                        val gap = seg.xStart - prev.xEnd
                        if (gap <= maxOf(28.0, prev.height * 2.5)) {
                            last += seg
                        } else {
                            clusters += mutableListOf(seg)
                        }
                    }
                }
                clusters.mapIndexed { idx, cluster ->
                    val primaryText = cluster.joinToString(" ") { it.text }.trim()
                    val fontPt = cluster.maxOf { it.fontSizePt }
                    val bold = cluster.any { it.bold }
                    PageLineTypography(
                        page = page,
                        text = primaryText,
                        rowText = if (idx == 0) rowText else "",
                        fontSizePt = fontPt,
                        bold = bold,
                        yNorm = yNorm,
                    )
                }
            }
        }
    }

    private fun presentationText(value: String): String = buildString {
        for (c in value) {
            if (c in '\uFB00'..'\uFDFF' || c in '\uFE70'..'\uFEFF') {
                append(Normalizer.normalize(c.toString(), Normalizer.Form.NFKC).trim())
            } else append(c)
        }
    }
}
