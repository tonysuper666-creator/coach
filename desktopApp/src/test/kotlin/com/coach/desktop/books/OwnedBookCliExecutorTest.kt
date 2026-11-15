package com.coach.desktop.books

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class OwnedBookCliExecutorTest {
    @Test fun policyDeniesBeforeTransportAndExecutorReturnsValidatedStructuredCitations() = runBlocking {
        val excerpt = OwnedBookExcerpt(OwnedBookCitation("a".repeat(64), 2, 4, 15), "Test source")
        val prompt = OwnedBookPrompt(OwnedBookAction.ASK, "Why?", listOf(excerpt))
        var calls = 0
        val transport = OwnedBookCliTransport { request ->
            calls++
            assertEquals("ask", Json.parseToJsonElement(request).jsonObject["action"].toString().trim('"'))
            """{"answer":"It follows from the source.","citations":[0]}"""
        }
        assertFailsWith<IllegalArgumentException> { OwnedBookCliExecutor(transport).answer(prompt) }
        assertEquals(0, calls)
        val executor = OwnedBookCliExecutor(transport, OwnedBookOutboundPolicy { true })
        assertEquals(OwnedBookAnswer("It follows from the source.", listOf(excerpt.citation)), executor.answer(prompt))
        assertEquals(1, calls)
    }

    @Test fun malformedCitationsAndTransportFailureCannotBecomeAnswers() = runBlocking {
        val prompt = OwnedBookPrompt(OwnedBookAction.EXPLAIN, null,
            listOf(OwnedBookExcerpt(OwnedBookCitation("a".repeat(64), 1, 0, 4), "text")))
        val policy = OwnedBookOutboundPolicy { true }
        for (response in listOf("", "{}", """{"answer":"ok","citations":[]}""",
            """{"answer":"ok","citations":[1]}""", """{"answer":"ok","citations":["0"]}""",
            """{"answer":"ok","citations":[0],"extra":1}""")) {
            assertFails { OwnedBookCliExecutor(OwnedBookCliTransport { response }, policy).answer(prompt) }
        }
        assertFailsWith<CancellationException> {
            OwnedBookCliExecutor(OwnedBookCliTransport { throw CancellationException("cancel") }, policy).answer(prompt)
        }
        Unit
    }

    @Test fun providerRejectsWhitespaceOnlyTwoBlockEcho() = runBlocking {
        val root = Files.createTempDirectory("owned-echo-")
        val source = root.resolve("synthetic.txt")
        Files.writeString(source, "Chapter 1 Lamps\n\nA lamp powers a small pump.\n\nThe pump circulates water.")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(source)
        val lesson = book.lessons.first()
        assertEquals(2, lesson.blocks.count { !it.heading })
        val provider = OwnedBookLearningProvider(library, OwnedBookExecutor { prompt ->
            OwnedBookAnswer(prompt.excerpts.joinToString("\n") { it.text }, listOf(prompt.excerpts.first().citation))
        })
        assertIs<BookLearningReply.Unavailable>(provider.explain(BookExplanationRequest(book.id, lesson.id, lesson.blocks)))
        Unit
    }

    @Test fun providerRejectsInvisibleFormatCharacterCopiesButNotPunctuationVariants() = runBlocking {
        val root = Files.createTempDirectory("owned-format-echo-")
        val source = root.resolve("synthetic.txt")
        Files.writeString(source, "Chapter 1 Lamps\n\nA lamp powers a small pump.\n\nThe pump circulates water.")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(source)
        val lesson = book.lessons.first()
        val request = BookExplanationRequest(book.id, lesson.id, lesson.blocks)
        var alter: (String) -> String = { it }
        val provider = OwnedBookLearningProvider(library, OwnedBookExecutor { prompt ->
            val copied = prompt.excerpts.joinToString("\n") { it.text }
            OwnedBookAnswer(alter(copied), listOf(prompt.excerpts.first().citation))
        })
        for (format in listOf("\u200b", "\u200c", "\u200d", "\ufeff")) {
            alter = { it.toList().joinToString(format) }
            assertIs<BookLearningReply.Unavailable>(provider.explain(request))
        }
        alter = { "\u200b" }
        assertIs<BookLearningReply.Unavailable>(provider.explain(request))
        // Document the heuristic's remaining limitation, not a quality guarantee.
        alter = { it.replace('.', '。') }
        assertIs<BookLearningReply.Text>(provider.explain(request))
        alter = { "$it。" }
        assertIs<BookLearningReply.Text>(provider.explain(request))
        Unit
    }

    @Test fun structuredTransportPassesThroughProviderAndInvalidIndexFailsClosed() = runBlocking {
        val root = Files.createTempDirectory("owned-structured-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lesson = book.lessons.first()
        val block = lesson.blocks.first { !it.heading }
        val request = BookPassageQuestion(BookPassage.from(book, block), "Why is light useful?")
        var response = """{"answer":"Light is the energy input that supports sugar production.","citations":[0]}"""
        val executor = OwnedBookCliExecutor(OwnedBookCliTransport { response }, OwnedBookOutboundPolicy { true })
        val provider = OwnedBookLearningProvider(library, executor)
        val good = assertIs<BookLearningReply.Text>(provider.ask(request))
        assertTrue(good.text.contains(book.id))
        assertTrue(good.text.contains("第 ${block.page} 页"))
        response = """{"answer":"Forged reference.","citations":[1]}"""
        assertIs<BookLearningReply.Unavailable>(provider.ask(request))
        Unit
    }
}
