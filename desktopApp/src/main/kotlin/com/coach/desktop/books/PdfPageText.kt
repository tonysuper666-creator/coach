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
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.StringWriter
import java.text.Normalizer

/** Geometry uses the existing reader contract; TON-1665 owns its on-disk page model. */
internal data class PdfPageText(
    val frame: PdfPageFrame,
    val unit: ReaderTextUnit,
    /** A run PDFBox rewrote in an unrecognised way is left unboxed, never guessed. */
    val unmappedRanges: List<ReaderTextRange>,
)

/** One extraction pass, with exactly the text and cleaning used by TextbookExtraction. */
internal object PdfPageTextExtraction {
    fun extract(document: PDDocument, page: Int): PdfPageText {
        require(page in 1..document.numberOfPages)
        require(document.currentAccessPermission.canExtractContent()) { "这本 PDF 限制了文字提取。" }
        val source = document.getPage(page - 1)
        fun PDRectangle.points() = PdfPointRect(lowerLeftX.toDouble(), lowerLeftY.toDouble(),
            upperRightX.toDouble(), upperRightY.toDouble())
        val frame = PdfPageFrame(source.mediaBox.points(), source.cropBox.points(),
            Math.floorMod(source.rotation, 360))
        return PositionStripper(page, frame).extract(document)
    }

    private class PositionStripper(private val page: Int, private val frame: PdfPageFrame) : PDFTextStripper() {
        private val raw = StringWriter()
        private data class Span(val start: Int, val end: Int, val bounds: ReaderRect)
        private val spans = mutableListOf<Span>()
        private val unmapped = mutableListOf<IntRange>()

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
