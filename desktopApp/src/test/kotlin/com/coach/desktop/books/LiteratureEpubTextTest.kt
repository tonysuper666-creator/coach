package com.coach.desktop.books

import kotlin.test.*

class LiteratureEpubTextTest {
    @Test fun dropsEndnoteParagraphsAndBraceNotes() {
        val page = listOf(
            "# Book I",
            "The greatest improvement in the productive powers of labour {see Note 4} was the effect of the division of labour.[3]",
            "1 Smith here follows Mandeville. Ibid.",
            "{Editor: the manuscript reads 'work'.}",
            "[2] See Hume, Essays, p. 12.",
        ).joinToString("\n\n")
        val text = LiteratureEpubText.clean(page).joinToString("\n\n") { it.text }
        assertTrue("division of labour." in text)
        assertFalse("{" in text || "}" in text)
        assertFalse("Mandeville" in text)
        assertFalse("Hume" in text)
        assertFalse("[3]" in text)
    }

    @Test fun notesSectionIsDroppedToEndOfPage() {
        val page = "Real body sentence here.\n\n## Notes\n\nPlain text that is only a note body."
        val text = LiteratureEpubText.clean(page).joinToString("\n\n") { it.text }
        assertEquals("Real body sentence here.", text)
    }

    @Test fun bodyParagraphStartingWithYearLikeNumberStays() {
        val p = "1776 was the year in which the book appeared, and its argument has been disputed ever since by many readers across several generations of scholars and critics alike, " +
            "so that no short note could describe it; the discussion fills the whole paragraph with long-form prose of the ordinary kind found in the body text of any classic."
        assertEquals(1, LiteratureEpubText.clean(p).size)
    }
}
