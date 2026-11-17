package com.coach.desktop.books

import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderTextRange
import com.coach.domain.reader.ReaderTextUnit

/** Pure page-local selection: paging, spread layout and scrolling only transform pointer coordinates. */
internal object PdfTextSelection {
    fun boxesForRange(unit: ReaderTextUnit, range: ReaderTextRange): List<ReaderRect> = unit.spans
        .filter { it.range.startUtf16 < range.endUtf16 && it.range.endUtf16 > range.startUtf16 }
        .flatMap { it.geometry.displayBoxes() }

    /** A citation must retain the imported text and cover every visible character, not a nearby run. */
    fun boxesForVerifiedRange(unit: ReaderTextUnit, text: String, range: ReaderTextRange): List<ReaderRect> {
        if (unit.text != text || range.endUtf16 > text.length) return emptyList()
        var covered = range.startUtf16
        for (span in unit.spans.sortedBy { it.range.startUtf16 }) {
            if (span.range.endUtf16 <= covered || span.range.startUtf16 >= range.endUtf16) continue
            val gapEnd = minOf(span.range.startUtf16, range.endUtf16)
            if ((covered until gapEnd).any { !text[it].isWhitespace() }) return emptyList()
            covered = maxOf(covered, minOf(span.range.endUtf16, range.endUtf16))
        }
        if ((covered until range.endUtf16).any { !text[it].isWhitespace() }) return emptyList()
        return boxesForRange(unit, range)
    }

    /** Endpoints include the touched glyph, including all UTF-16 units of a surrogate pair/ligature. */
    fun rangeBetweenPoints(unit: ReaderTextUnit, x0: Double, y0: Double, x1: Double, y1: Double): ReaderTextRange? {
        require(listOf(x0, y0, x1, y1).all { it.isFinite() })
        fun nearest(x: Double, y: Double) = unit.spans.minByOrNull { span ->
            val frame = span.geometry.frame
            val sideways = frame.rotationClockwise == 90 || frame.rotationClockwise == 270
            val width = frame.cropBox.right - frame.cropBox.left
            val height = frame.cropBox.top - frame.cropBox.bottom
            span.geometry.displayBoxes().minOf { box ->
                // Measure distance in page points, so a landscape page does not stretch hit areas.
                val dx = maxOf(box.x - x, 0.0, x - box.x - box.width) * if (sideways) height else width
                val dy = maxOf(box.y - y, 0.0, y - box.y - box.height) * if (sideways) width else height
                dx * dx + dy * dy
            }
        }
        val start = nearest(x0, y0)?.range ?: return null
        val end = nearest(x1, y1)?.range ?: return null
        return ReaderTextRange(minOf(start.startUtf16, end.startUtf16), maxOf(start.endUtf16, end.endUtf16))
    }
}
