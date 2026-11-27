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

class LiteratureEpubNoteTextTest {
    @Test fun dropsParagraphsMatchingEpubTypedEndnotes() {
        val dir = java.nio.file.Files.createTempDirectory("epub-notes")
        val file = dir.resolve("t.epub")
        val note = "Annius Verius was his grandfather's name, and there is no verb in this section connected with the word from."
        val xhtml = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <section epub:type="endnotes" role="doc-endnotes"><h2>Endnotes</h2><ol><li id="note-1" epub:type="endnote"><p>$note <a href="b.xhtml#r1">↩︎</a></p></li></ol></section></body></html>"""
        java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(file)).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("text/endnotes.xhtml")); z.write(xhtml.toByteArray()); z.closeEntry()
        }
        val notes = LiteratureEpubText.noteTexts(file)
        assertEquals(1, notes.size)
        val page = "The body sentence stays in the guide.\n\n$note ↩︎\n\n${note.take(60)}"
        val text = LiteratureEpubText.clean(page, notes).joinToString("\n\n") { it.text }
        assertEquals("The body sentence stays in the guide.", text)
    }
}
