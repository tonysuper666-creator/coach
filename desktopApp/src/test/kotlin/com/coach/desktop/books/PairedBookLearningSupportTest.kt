package com.coach.desktop.books

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PairedBookLearningSupportTest {
    @Test fun consentVerifiedPassageQuestionAndCitationReachPairedTransport() = runBlocking {
        val root = Files.createTempDirectory("paired-passage-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val block = book.lessons.first().blocks.first { !it.heading }
        val request = BookPassageQuestion(BookPassage.from(book, block), "Why does the plant need light?")
        var calls = 0
        val support = PairedBookLearningSupport(library) { sent, question ->
            calls++
            assertEquals(block, sent)
            val wire = ExplainWire.question("ab12", sent, question)
            assertEquals("ask", wire["kind"]!!.toString().trim('"'))
            ExplainResult.Done(Explanation("Light supplies energy for the plant.【S1】", listOf(Citation("S1", sent.id, sent.page)), 1))
        }
        assertTrue(support.consentRequired(book.id))
        assertIs<BookLearningReply.Unavailable>(support.ask(request))
        assertEquals(0, calls)
        support.allow(book.id)
        assertFalse(support.consentRequired(book.id))
        val answer = assertIs<BookLearningReply.Text>(support.ask(request))
        assertEquals(listOf(request.passage), answer.sources)
        for (bad in listOf(request.copy(question = "x".repeat(501)),
            request.copy(passage = request.passage.copy(page = 999)),
            request.copy(passage = request.passage.copy(text = "forged")))) {
            assertIs<BookLearningReply.Unavailable>(support.ask(bad))
        }
        assertEquals(1, calls)
        val foreign = PairedBookLearningSupport(library) { sent, _ ->
            ExplainResult.Done(Explanation("Wrong page.【S1】", listOf(Citation("S1", sent.id, sent.page + 1)), 1))
        }
        assertIs<BookLearningReply.Unavailable>(foreign.ask(request))
        assertFailsWith<IllegalArgumentException> { ExplainWire.question("ab12", block, " ") }
        Unit
    }
}
