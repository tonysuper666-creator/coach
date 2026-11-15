package com.coach.desktop.books

import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class OwnedBookLearningProviderTest {
    private class Fixture {
        val root = Files.createTempDirectory("owned-book-provider-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lesson = book.lessons.first()
        val block = lesson.blocks.first { !it.heading }
        val passage = BookPassage.from(book, block)
        val explain = BookExplanationRequest(book.id, lesson.id, lesson.blocks)
        val ask = BookPassageQuestion(passage, "Why does the plant need light?")
        fun provider(executor: OwnedBookExecutor) = OwnedBookLearningProvider(library, executor)
        val successful = OwnedBookExecutor { prompt ->
            OwnedBookAnswer("Light supplies energy for the plant to make stored food.", listOf(prompt.excerpts.first().citation))
        }
    }

    @Test fun explanationAndQuestionKeepActionsAndReturnVerifiedSource() = runBlocking {
        val f = Fixture()
        val seen = mutableListOf<OwnedBookPrompt>()
        val provider = f.provider(OwnedBookExecutor { prompt ->
            seen += prompt
            f.successful.answer(prompt)
        })
        val explanation = assertIs<BookLearningReply.Text>(provider.explain(f.explain))
        val reply = assertIs<BookLearningReply.Text>(provider.ask(f.ask))
        assertEquals(listOf(OwnedBookAction.EXPLAIN, OwnedBookAction.ASK), seen.map { it.action })
        assertNull(seen.first().question)
        assertEquals(f.ask.question, seen.last().question)
        for (text in listOf(explanation.text, reply.text)) {
            assertTrue(text.contains(f.book.id))
            assertTrue(text.contains("第 ${f.block.page} 页"))
            assertTrue(text.contains("${f.block.start}–${f.block.end}"))
        }
    }

    @Test fun invalidInputNeverReachesExecutor() = runBlocking {
        val f = Fixture()
        var calls = 0
        val provider = f.provider(OwnedBookExecutor { calls++; f.successful.answer(it) })
        val p = f.passage
        val badRequests = listOf(
            f.ask.copy(question = " "), f.ask.copy(question = "x".repeat(501)),
            f.ask.copy(passage = p.copy(book = "0".repeat(64))),
            f.ask.copy(passage = p.copy(page = p.page + 1)),
            f.ask.copy(passage = p.copy(start = p.start + 1)),
            f.ask.copy(passage = p.copy(end = p.end + 1)),
            f.ask.copy(passage = p.copy(text = "forged quote")),
        )
        badRequests.forEach { assertIs<BookLearningReply.Unavailable>(provider.ask(it)) }
        assertIs<BookLearningReply.Unavailable>(provider.explain(f.explain.copy(lesson = "stale")))
        assertIs<BookLearningReply.Unavailable>(provider.explain(f.explain.copy(blocks = listOf(f.block.copy(text = "stale")))))
        assertEquals(0, calls)
    }

    @Test fun responsesNeedAnExactOwnedCitationAndSubstantiveText() = runBlocking {
        val f = Fixture()
        val good = OwnedBookCitation(f.book.id, f.block.page, f.block.start, f.block.end)
        val bad = listOf(emptyList(), listOf(good.copy(book = "0".repeat(64))),
            listOf(good.copy(page = good.page + 1)), listOf(good.copy(start = good.start + 1)))
        for (citations in bad) {
            val provider = f.provider(OwnedBookExecutor { OwnedBookAnswer("An explanation.", citations) })
            assertIs<BookLearningReply.Unavailable>(provider.ask(f.ask))
        }
        for (text in listOf("", "Unavailable", f.passage.text)) {
            val provider = f.provider(OwnedBookExecutor { OwnedBookAnswer(text, listOf(good)) })
            assertIs<BookLearningReply.Unavailable>(provider.ask(f.ask))
        }
        assertIs<BookLearningReply.Unavailable>(f.provider(OwnedBookExecutor { error("failed") }).ask(f.ask))
        assertFailsWith<CancellationException> {
            f.provider(OwnedBookExecutor { throw CancellationException("cancelled") }).ask(f.ask)
        }
        Unit
    }

    @Test fun changedOriginalOrExtractedPageCannotProduceAnAnswer() = runBlocking {
        val original = Fixture()
        Files.writeString(original.library.directory.resolve("${original.book.id}.pdf"), "changed")
        assertIs<BookLearningReply.Unavailable>(original.provider(original.successful).ask(original.ask))

        val stale = Fixture()
        val provider = stale.provider(OwnedBookExecutor { prompt ->
            DriverManager.getConnection("jdbc:sqlite:${stale.library.directory.resolve("library.sqlite")}").use { db ->
                db.prepareStatement("UPDATE pages SET text=? WHERE book=? AND page=?").use {
                    it.setString(1, "changed after request"); it.setString(2, stale.book.id); it.setInt(3, stale.passage.page)
                    it.executeUpdate()
                }
            }
            stale.successful.answer(prompt)
        })
        assertIs<BookLearningReply.Unavailable>(provider.ask(stale.ask))
        Unit
    }
}
