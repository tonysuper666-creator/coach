package com.coach.desktop.books

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class PairedBookLearningSupportTest {
    @Test fun lessonCleansKnownAndUnknownMarksWithoutChangingSourcesOrOrdinaryText(): Unit = runBlocking {
        val root = Files.createTempDirectory("paired-lesson-marks-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
            val lesson = book.lessons.first()
            val block = lesson.blocks.first { !it.heading }
            val request = BookExplanationRequest(book.id, lesson.id, lesson.blocks)
            ExplainConsent(library.directory).allow(book.id)
            for ((input, expected) in listOf(
                "Plants need light.【S1】" to "Plants need light.",
                "Plants need light [S1, S9] and water.［S42］" to "Plants need light and water.",
                "Plants need light and water." to "Plants need light and water.",
            )) {
                val support = PairedBookLearningSupport(library, sendLesson = { _, _ ->
                    ExplainResult.Done(Explanation(input, listOf(Citation("S1", block.id, block.page)), 1))
                })
                val reply = assertIs<BookLearningReply.Text>(support.explain(request))
                assertEquals(expected, reply.text)
                assertEquals(listOf(BookPassage.from(book, block)), reply.sources)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun wholeLessonUsesOneConsentedSendAndMapsLastBlockCitation(): Unit = runBlocking {
        val root = Files.createTempDirectory("paired-lesson-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val file = root.resolve("self-authored.txt")
            Files.writeString(file, (1..9).joinToString("\n\n") { "Self authored paragraph $it explains a different plant process." })
            val book = library.importBook(file)
            val lesson = book.lessons.first()
            val body = lesson.blocks.filterNot { it.heading }
            assertTrue(body.size > 6)
            val request = BookExplanationRequest(book.id, lesson.id, lesson.blocks)
            var calls = 0
            val support = PairedBookLearningSupport(library, sendLesson = { blocks, pages ->
                calls++
                assertEquals(body, blocks); assertEquals(book.pages.size, pages)
                val last = blocks.last()
                ExplainResult.Done(Explanation("Plants use several connected processes.【S9】",
                    listOf(Citation("S9", last.id, last.page)), 1))
            })
            assertIs<BookLearningReply.Unavailable>(support.explain(request))
            assertEquals(0, calls)
            support.allow(book.id)
            val answer = assertIs<BookLearningReply.Text>(support.explain(request))
            assertEquals("Plants use several connected processes.", answer.text)
            assertEquals(listOf(BookPassage.from(book, body.last())), answer.sources)
            assertEquals(1, calls)
            for (bad in listOf(request.copy(blocks = request.blocks.take(1)), request.copy(lesson = "forged"),
                request.copy(blocks = request.blocks.reversed()))) assertIs<BookLearningReply.Unavailable>(support.explain(bad))
            assertEquals(1, calls)
            val wrong = PairedBookLearningSupport(library, sendLesson = { _, _ ->
                ExplainResult.Done(Explanation("Wrong citation.", listOf(Citation("S10", "outside:p1:0", 1)), 1))
            })
            assertIs<BookLearningReply.Unavailable>(wrong.explain(request))
            val copy = PairedBookLearningSupport(library, sendLesson = { _, _ ->
                ExplainResult.Done(Explanation(body.first().text + "【S1】",
                    listOf(Citation("S1", body.first().id, body.first().page)), 1))
            })
            assertIs<BookLearningReply.Unavailable>(copy.explain(request), "An anchor cannot turn copied source into an explanation")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun lessonRetainsTransportFailureAndCancelsWithoutSwallowingCancellation(): Unit = runBlocking {
        val root = Files.createTempDirectory("paired-lesson-cancel-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
            val lesson = book.lessons.first()
            val request = BookExplanationRequest(book.id, lesson.id, lesson.blocks)
            ExplainConsent(library.directory).allow(book.id)
            val message = "讲解服务暂时不可用，请稍后再试。"
            val busy = PairedBookLearningSupport(library, sendLesson = { _, _ ->
                ExplainResult.Refused(message, ExplainTrouble.Busy)
            })
            assertEquals(BookLearningReply.Unavailable(message, ExplainTrouble.Busy), busy.explain(request))
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val pending = PairedBookLearningSupport(library, sendLesson = { _, _ ->
                entered.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            })
            val job = async { pending.explain(request) }
            entered.await(); job.cancel()
            assertFailsWith<CancellationException> { job.await() }
            stopped.await()
            val changed = PairedBookLearningSupport(library, sendLesson = { blocks, _ ->
                Files.writeString(library.directory.resolve("${book.id}.pdf"), "changed")
                ExplainResult.Done(Explanation("Plant processes are linked.", listOf(Citation("S1", blocks.first().id, blocks.first().page)), 1))
            })
            assertIs<BookLearningReply.Unavailable>(changed.explain(request))
        } finally { root.toFile().deleteRecursively() }
    }

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
