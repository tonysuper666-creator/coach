package com.coach.desktop.reader

import com.coach.desktop.books.TextbookLibrary
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

/**
 * A textbook export names pages by their real PDF page, and an export may start anywhere (here at
 * page 5). The reader still has to open that page: it addresses physical pages, not list slots.
 */
class LocalBookPhysicalPageTest {
    private val markdown = "<!-- PDF 第 5 页 · 书第 1 页 -->\n# Chapter 1 Light\n\n" +
        "Photosynthesis is the process by which plants use light to make sugars.\n\n" +
        "<!-- PDF 第 6 页 · 书第 2 页 -->\n# Chapter 2 Cells\n\n" +
        "Chlorophyll is a green pigment that absorbs light in plant cells.\n"

    private fun library() = Files.createTempDirectory("physical-page-entry-").also { root ->
        Files.write(root.resolve("chapter.md"), markdown.toByteArray())
    }

    @Test fun importStartsReadingOnARealPage() {
        val root = library()
        try {
            val book = TextbookLibrary(root.resolve("library")).importBook(root.resolve("chapter.md"))
            assertEquals(listOf(5, 6), book.pages.map { it.number })
            assertEquals(5, book.readingPage, "reading resumes on a real page, not a list position")
            // A stored page this book no longer has must not be shown as if it were page 1.
            assertEquals(5, TextbookLibrary(root.resolve("library")).book(book.id).readingPage)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun physicalPageFiveOpensThroughTheReaderEntry() = runBlocking {
        val root = library()
        try {
            val book = TextbookLibrary(root.resolve("library")).importBook(root.resolve("chapter.md"))
            val remote = ReaderRemote(LocalBooksReaderSource(TextbookLibrary(root.resolve("library"))))
            val session = ReaderSession(ReaderFixture(book.title, emptyList(), emptyList()), 1200, remote)
            remote.loadCatalog()
            session.openRemote(book.id, 5)
            assertEquals(6, remote.current?.pages, "the reader must be able to address page 5")
            remote.show(5)
            val view = assertIs<PageState.Ready>(remote.page).view
            assertEquals(5, view.pdfPage)
            assertEquals(5, PdfPages.toPdf(session.state.position.page))
            assertTrue(view.text.contains("Photosynthesis is the process"))
            println("READER marker export: pdfPage=${view.pdfPage} pages=${remote.current?.pages}")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun pagesTheExportDoesNotContainStayRefused() = runBlocking {
        val root = library()
        try {
            val book = TextbookLibrary(root.resolve("library")).importBook(root.resolve("chapter.md"))
            val remote = ReaderRemote(LocalBooksReaderSource(TextbookLibrary(root.resolve("library"))))
            val session = ReaderSession(ReaderFixture(book.title, emptyList(), emptyList()), 1200, remote)
            remote.loadCatalog()
            session.openRemote(book.id, 5)
            for (missing in listOf(1, 4, 7)) {
                remote.show(missing)
                assertIs<PageState.Failed>(remote.page, "page $missing is not in this export")
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
