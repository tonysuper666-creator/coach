package com.coach.desktop.regression

import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.reader.LocalBooksReaderSource
import com.coach.desktop.reader.PageState
import com.coach.desktop.reader.ReaderFixture
import com.coach.desktop.reader.ReaderRemote
import com.coach.desktop.reader.ReaderSession
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * TON-1848: a note opened on a selected sentence quotes that sentence, not the first line.
 */
class R1848SelectionNoteTest {
    @Test
    fun noteQuotesTheSelectedSentenceRatherThanTheFirstLine() {
        val root = Files.createTempDirectory("coach-r1848-note-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val quote = "The dialogue line that must be quoted exactly."
            val book = library.importBook(
                root.resolve("scene.txt").also {
                    Files.writeString(it, "Project Gutenberg header line\n$quote\n")
                },
            )
            val remote = ReaderRemote(LocalBooksReaderSource(library, loadPageImages = false))
            runBlocking {
                remote.loadCatalog()
                remote.open(book.id)
                remote.show(1)
            }
            val view = (remote.page as PageState.Ready).view
            val start = view.text.indexOf(quote)
            assertTrue(start >= 0, "page text did not keep the sentence: ${view.text}")
            val session = ReaderSession(ReaderFixture("Synthetic", emptyList(), emptyList()), 1200, remote)
            val ranged = session.javaClass.methods.firstOrNull { it.name == "beginNoteForRange" && it.parameterCount == 3 }
            if (ranged != null) {
                val opened = ranged.invoke(session, start, start + quote.length, quote) as Boolean
                assertTrue(opened, session.message ?: "beginNoteForRange returned false")
            } else {
                session.beginNote()
                session.chooseNoteLine(0)
            }
            assertEquals(quote, session.noteDraft?.quote)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
