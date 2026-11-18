package com.coach.desktop.books

import com.coach.domain.reader.ReaderTextRange
import java.nio.ByteBuffer

/** Pixel bottoms refer to the saved crop. Row zero is the printed header.
 * Source ranges are half-open offsets in the immutable text of the physical page. */
internal data class ImportedTableRow(val bottomPx: Int, val source: ReaderTextRange)
internal data class ImportedTable(val rows: List<ImportedTableRow>, val bodyFontPx: Float, val tableFontPx: Float) {
    fun layout() = BookTableRows(rows.first().bottomPx,
        rows.drop(1).map { BookTableRow(it.bottomPx, it.source.endUtf16) }, bodyFontPx, tableFontPx)

    fun valid(height: Int, text: String? = null): Boolean {
        if (rows.size !in 3..MAX_ROWS || rows.last().bottomPx != height ||
            !bodyFontPx.isFinite() || bodyFontPx <= 0 || !tableFontPx.isFinite() || tableFontPx <= 0) return false
        var bottom = 0
        var end = 0
        for (row in rows) {
            val range = row.source
            if (row.bottomPx <= bottom || range.startUtf16 < end || range.endUtf16 <= range.startUtf16) return false
            if (text != null && (!BookIllustrationAnchors.isValidAnchor(text, range.startUtf16) ||
                    !BookIllustrationAnchors.isValidAnchor(text, range.endUtf16))) return false
            bottom = row.bottomPx
            end = range.endUtf16
        }
        return true
    }

    fun encode(): ByteArray = ByteBuffer.allocate(12 + 12 * rows.size).apply {
        putInt(1)
        putFloat(bodyFontPx)
        putFloat(tableFontPx)
        for (row in rows) {
            putInt(row.bottomPx)
            putInt(row.source.startUtf16)
            putInt(row.source.endUtf16)
        }
    }.array()

    companion object {
        const val MAX_ROWS = 256

        fun decode(bytes: ByteArray?, height: Int): ImportedTable? {
            if (bytes == null || bytes.size !in 48..(12 + 12 * MAX_ROWS) || bytes.size % 12 != 0) return null
            val buffer = ByteBuffer.wrap(bytes)
            if (buffer.int != 1) return null
            val body = buffer.float
            val cell = buffer.float
            val rows = mutableListOf<ImportedTableRow>()
            while (buffer.hasRemaining()) {
                val bottom = buffer.int
                val start = buffer.int
                val end = buffer.int
                if (start < 0 || end <= start) return null
                rows += ImportedTableRow(bottom, ReaderTextRange(start, end))
            }
            return ImportedTable(rows, body, cell).takeIf { it.valid(height) }
        }
    }
}
