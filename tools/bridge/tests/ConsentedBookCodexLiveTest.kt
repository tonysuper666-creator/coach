package com.coach.desktop.books

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Explicit synthetic-only live test, copied into the test source set by its runner. No private library. */
class ConsentedBookCodexLiveTest {
    @Test fun syntheticUploaderConsentLocalCodexAndRevoke() = runBlocking {
        val root = Files.createTempDirectory("synthetic-consented-codex-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("Fictional plant.txt"),
            "Photosynthesis is the process by which plants use light to make sugars."))
        val lesson = book.lessons.first()
        val block = lesson.blocks.first { !it.heading }
        val executable = assertNotNull(discoverLocalCodex())
        var spawns = 0
        val transport = LocalCodexBookTransport(executable, receipt = { argv, exit ->
            spawns++
            println("SYNTHETIC_CLI argv=" + argv.mapIndexed { i, arg -> if (i > 0 && argv[i - 1] == "-o") "<temporary-output>" else arg } + " exit=$exit")
        })
        val support = ConsentedBookLearningSupport(library, OwnedBookConsentStore(library), transport)
        val explanation = BookExplanationRequest(book.id, lesson.id, lesson.blocks)
        val question = BookPassageQuestion(BookPassage.from(book, block), "Why does the plant need light?")
        assertNotNull(assertIs<BookLearningReply.Unavailable>(support.explain(explanation)).needsConsent)
        assertEquals(0, spawns)
        // The uploader of this exact fictional fixture explicitly grants this book's two actions.
        support.grant(support.consentOffer(book.id), setOf(OwnedBookAction.EXPLAIN, OwnedBookAction.ASK))
        for ((action, reply) in listOf("explain" to support.explain(explanation), "ask" to support.ask(question))) {
            val text = assertIs<BookLearningReply.Text>(reply).text
            assertTrue(text.contains(book.id))
            assertTrue(text.contains("第 ${block.page} 页，字符 ${block.start}–${block.end}"))
            println("SYNTHETIC_VERIFIED action=$action book=${book.id} page=${block.page} range=${block.start}..${block.end}")
        }
        assertEquals(4, spawns) // login status + exec for each action
        support.revoke(book.id)
        val before = spawns
        assertNotNull(assertIs<BookLearningReply.Unavailable>(support.ask(question)).needsConsent)
        assertEquals(before, spawns)
        println("SYNTHETIC_REVOKED subsequent_spawns=0")
    }
}
