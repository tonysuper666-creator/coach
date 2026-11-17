package com.coach.desktop.books

import com.coach.domain.reader.PdfPageFrame
import com.coach.domain.reader.PdfPointRect
import com.coach.domain.reader.ReaderGeometry
import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderResource
import com.coach.domain.reader.ReaderTextGeometry
import com.coach.domain.reader.ReaderTextRange
import com.coach.domain.reader.ReaderTextUnit
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1CFont
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.StringWriter
import java.text.Normalizer
import java.util.IdentityHashMap

/** Geometry uses the existing reader contract; TON-1665 owns its on-disk page model. */
internal data class PdfPageText(
    val frame: PdfPageFrame,
    val unit: ReaderTextUnit,
    /** A run PDFBox rewrote in an unrecognised way is left unboxed, never guessed. */
    val unmappedRanges: List<ReaderTextRange>,
)

/** One extraction pass, with exactly the text and cleaning used by TextbookExtraction. */
internal object PdfPageTextExtraction {
    fun extract(document: PDDocument, page: Int): PdfPageText = extract(document, page, null)

    /** Review only: CFF math ink can extend below the baseline used by TextPosition.heightDir.
     * Keep ordinary extraction/offsets unchanged. One raster pixel cushions outline bounds.
     * This is text geometry, not proof that a selected object or its labels are complete.
     */
    fun extractForVisualReview(document: PDDocument, page: Int, rasterScale: Double): PdfPageText {
        require(rasterScale.isFinite() && rasterScale > 0 && rasterScale <= 2)
        return extract(document, page, 1 / rasterScale)
    }

    private fun extract(document: PDDocument, page: Int, inkPadding: Double?): PdfPageText {
        require(page in 1..document.numberOfPages)
        require(document.currentAccessPermission.canExtractContent()) { "这本 PDF 限制了文字提取。" }
        val source = document.getPage(page - 1)
        fun PDRectangle.points() = PdfPointRect(lowerLeftX.toDouble(), lowerLeftY.toDouble(),
            upperRightX.toDouble(), upperRightY.toDouble())
        val frame = PdfPageFrame(source.mediaBox.points(), source.cropBox.points(),
            Math.floorMod(source.rotation, 360))
        return PositionStripper(page, frame, inkPadding).extract(document)
    }

    private class PositionStripper(private val page: Int, private val frame: PdfPageFrame,
        private val inkPadding: Double?) : PDFTextStripper() {
        private val raw = StringWriter()
        private data class Span(val start: Int, val end: Int, val bounds: ReaderRect)
        private val spans = mutableListOf<Span>()
        private val unmapped = mutableListOf<IntRange>()
        private data class Ink(val unicode: String, val bounds: ReaderRect?)
        private val cffInk = IdentityHashMap<TextPosition, Ink>()

        override fun processTextPosition(position: TextPosition) {
            if (inkPadding != null && position.font is PDType1CFont) {
                cffInk[position] = Ink(position.unicode, cffBounds(position, inkPadding))
            }
            super.processTextPosition(position)
        }

        private fun cffBounds(position: TextPosition, padding: Double): ReaderRect? {
            val font = position.font as PDType1CFont
            if (cffInk.size >= 100_000 || !font.isEmbedded || font.isDamaged || font.isVertical ||
                position.dir != 0f || position.characterCodes.size != 1 ||
                graphicsState.textState.renderingMode != RenderingMode.FILL || graphicsState.softMask != null) return null
            return try {
                val code = position.characterCodes.single()
                if (!font.hasGlyph(code)) return null
                val path = font.getPath(code)
                // Empty whitespace has no ink; retain its existing advance geometry.
                if (path.bounds2D.isEmpty) return if (position.unicode.all { it.isWhitespace() }) legacyBounds(position) else null
                val iterator = path.getPathIterator(null)
                var segments = 0
                while (!iterator.isDone) {
                    if (++segments > 10_000) return null
                    iterator.next()
                }
                val transform = position.textMatrix.createAffineTransform()
                transform.concatenate(font.fontMatrix.createAffineTransform())
                // PDFBox already translates TextPosition's matrix by the CropBox origin.
                val ink = transform.createTransformedShape(path).bounds2D
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

        fun extract(document: PDDocument): PdfPageText {
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

        override fun writeString(text: String, textPositions: List<TextPosition>) {
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

        private fun bounds(position: TextPosition): ReaderRect? {
            if (inkPadding != null && position.font is PDType1CFont) {
                // PDFBox may merge a combining mark into a position after processTextPosition.
                // Its original outline cannot certify the merged run.
                val ink = cffInk[position] ?: return null
                return ink.bounds.takeIf { ink.unicode == position.unicode }
            }
            return legacyBounds(position)
        }

        private fun legacyBounds(position: TextPosition): ReaderRect? {
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

    private fun presentationText(value: String): String = buildString {
        for (c in value) {
            if (c in '\uFB00'..'\uFDFF' || c in '\uFE70'..'\uFEFF') {
                append(Normalizer.normalize(c.toString(), Normalizer.Form.NFKC).trim())
            } else append(c)
        }
    }
}
