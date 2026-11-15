package com.coach.desktop.books

import java.nio.file.Files
import kotlin.test.*

class BookSourceTargetTest {
    private val root = Files.createTempDirectory("book-source-target-")
    private val library = TextbookLibrary(root.resolve("library"))
    private val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
    private val resolver = BookSourceResolver(library)
    private val block = book.lessons.flatMap { it.blocks }.filter { !it.heading }.last()   // a later page
    private val ok = OwnedBookCitation(book.id, block.page, block.start, block.end)

    @Test fun validCitationResolvesToThePhysicalPageAndExactQuote() {
        assertTrue(block.page > 1)
        val r = assertIs<BookSourceResolution.Resolved>(resolver.resolve(ok, block.text))
        assertEquals(block.page, r.target.pdfPage)
        assertEquals(block.text, r.target.quote)
        assertEquals(book.pages[block.page - 1].text.substring(block.start, block.end), r.target.quote)
        assertFalse(r.target.label.contains(book.id))
        assertTrue(resolver.stillValid(r.target))
    }

    @Test fun wrongPageBookRangeAndQuoteAreRefused() {
        fun refused(c: OwnedBookCitation, q: String? = null) = assertIs<BookSourceResolution.Refused>(resolver.resolve(c, q)).reason
        assertEquals(BookSourceRefusal.MALFORMED_BOOK, refused(ok.copy(book = "x")))
        assertEquals(BookSourceRefusal.BOOK_MISSING, refused(ok.copy(book = "0".repeat(64))))
        assertEquals(BookSourceRefusal.PAGE_MISSING, refused(ok.copy(page = 99)))
        assertEquals(BookSourceRefusal.RANGE_INVALID, refused(ok.copy(end = 100000)))
        assertEquals(BookSourceRefusal.RANGE_INVALID, refused(ok.copy(start = ok.end)))
        assertEquals(BookSourceRefusal.QUOTE_MISMATCH, refused(ok, "forged"))
        // Same offsets on another physical page must not be accepted as this quote.
        assertEquals(BookSourceRefusal.QUOTE_MISMATCH, refused(ok.copy(page = 1), block.text))
    }

    @Test fun changedFileIsRefusedAndOpenTargetBecomesStale() {
        val target = assertIs<BookSourceResolution.Resolved>(resolver.resolve(ok)).target
        Files.write(library.directory.resolve("${book.id}.${book.format}"), byteArrayOf(1, 2, 3))
        assertEquals(BookSourceRefusal.FILE_CHANGED, assertIs<BookSourceResolution.Refused>(resolver.resolve(ok)).reason)
        assertFalse(resolver.stillValid(target))
    }


    @Test fun surrogatePairBoundariesAreRefusedAndWholeCharacterAccepted() {
        val text = "A synthetic sentence: A\uD83D\uDE00B."   // high surrogate at 23, low at 24
        val page = block.page
        java.sql.DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.prepareStatement("UPDATE pages SET text=? WHERE book=? AND page=?").use { it.setString(1, text); it.setString(2, book.id); it.setInt(3, page); assertEquals(1, it.executeUpdate()) }
        }
        fun c(s: Int, e: Int) = OwnedBookCitation(book.id, page, s, e)
        assertEquals(BookSourceRefusal.RANGE_INVALID, assertIs<BookSourceResolution.Refused>(resolver.resolve(c(23, 24))).reason) // ends mid-pair
        assertEquals(BookSourceRefusal.RANGE_INVALID, assertIs<BookSourceResolution.Refused>(resolver.resolve(c(24, 26))).reason) // starts mid-pair
        val whole = assertIs<BookSourceResolution.Resolved>(resolver.resolve(c(23, 25), "\uD83D\uDE00"))
        assertEquals("\uD83D\uDE00", whole.target.quote)
        assertEquals("A\uD83D\uDE00B.", assertIs<BookSourceResolution.Resolved>(resolver.resolve(c(22, 27))).target.quote.let { it })
    }
}
