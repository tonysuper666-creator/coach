package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Copied into the test source set only by run_synthetic_owned_book_e2e.py. */
class OwnedBookSyntheticLiveTest {
    @Test fun fullSyntheticChain() = runBlocking {
        val root = Files.createTempDirectory("owned-book-live-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("fictional")))
        val lesson = book.lessons.first()
        val block = lesson.blocks.first { !it.heading }
        val expected = "Photosynthesis is the process by which plants use light to make sugars."
        val question = "Why does the plant need light?"
        assertEquals(expected, block.text)
        val policy = OwnedBookOutboundPolicy { prompt ->
            prompt.excerpts.size == 1 && prompt.excerpts.single().text == expected &&
                prompt.excerpts.single().citation.book == book.id &&
                ((prompt.action == OwnedBookAction.EXPLAIN && prompt.question == null) ||
                    (prompt.action == OwnedBookAction.ASK && prompt.question == question))
        }
        val repo = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("tools/bridge/tests/synthetic_owned_book_relay_client.py")) }
        val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val client = repo.resolve("tools/bridge/tests/synthetic_owned_book_relay_client.py").toString()
        val transport = OwnedBookProcessTransport(listOf(python, client), 120_000)
        val provider = OwnedBookLearningProvider(library, OwnedBookCliExecutor(transport, policy))
        val explain = provider.explain(BookExplanationRequest(book.id, lesson.id, lesson.blocks))
        val ask = provider.ask(BookPassageQuestion(BookPassage.from(book, block), question))
        for ((action, reply) in listOf("explain" to explain, "ask" to ask)) {
            val text = assertIs<BookLearningReply.Text>(reply).text
            assertTrue(text.contains(book.id))
            assertTrue(text.contains("第 ${block.page} 页"))
            assertTrue(text.contains("${block.start}–${block.end}"))
            println("LIVE_RECEIPT action=$action book=${book.id} page=${block.page} range=${block.start}..${block.end} verified=true")
        }
    }
}
