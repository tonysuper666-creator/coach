package com.coach.desktop.books

import com.coach.domain.reader.PdfPageFrame
import com.coach.domain.reader.PdfPointRect
import com.coach.domain.reader.ReaderGeometry
import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderResource
import com.coach.domain.reader.ReaderTextGeometry
import com.coach.domain.reader.ReaderTextRange
import com.coach.domain.reader.ReaderTextUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginalPdfWordSelectionTest {
    @Test fun aGlyphSelectsTheWordAtItsOwnOffset() {
        val text = "Repeat and repeat"
        val geometry = PdfSelectionGeometry(page(text), text)
        assertEquals(ReaderTextRange(0, 6), geometry.wordAt(ReaderTextRange(0, 1)))
        assertEquals(ReaderTextRange(11, 17), geometry.wordAt(ReaderTextRange(13, 14)))
        assertEquals(ReaderTextRange(11, 17), geometry.wordAt(ReaderTextRange(16, 17)))
    }

    @Test fun mismatchedOrOutOfBoundsHitsDoNotSelectText() {
        val unit = page("Repeat")
        assertNull(PdfSelectionGeometry(unit, "Reword").wordAt(ReaderTextRange(1, 2)))
        assertNull(PdfSelectionGeometry(unit, "Repeat").wordAt(ReaderTextRange(6, 7)))
    }

    private fun page(text: String): ReaderTextUnit {
        val frame = PdfPageFrame(PdfPointRect(0.0, 0.0, 200.0, 100.0))
        val spans = text.indices.filterNot { text[it].isWhitespace() }.map { index ->
            ReaderTextGeometry(ReaderTextRange(index, index + 1),
                ReaderGeometry(frame, listOf(ReaderRect(index * .04, .1, .04, .1))))
        }
        return ReaderTextUnit(ReaderResource(pdfPageIndex0 = 0), text, spans)
    }
}
