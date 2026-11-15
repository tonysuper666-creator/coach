package com.coach.desktop.books

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class OwnedBookConsentTest {
    internal class Fixture {
        val root = Files.createTempDirectory("synthetic-book-consent-")
        val library = TextbookLibrary(root.resolve("library"))
        fun book(name: String, text: String) = library.importBook(Files.writeString(root.resolve("$name.txt"), text))
        val a = book("Synthetic A", "Photosynthesis is the process by which plants use light to make sugars.")
        val b = book("Synthetic B", "A fictional moon circles a fictional planet every seven days.")
        val newA = book("Synthetic A revised", "Photosynthesis is the process by which plants use light to make sugars. This is a revised edition.")
        val store = OwnedBookConsentStore(library)
        var calls = 0
        val transport = OwnedBookCliTransport { calls++; """{"answer":"Light supplies energy for making stored food.","citations":[0]}""" }
        val support = ConsentedBookLearningSupport(library, store, transport)
        fun explain(book: Textbook = a) = book.lessons.first().let { BookExplanationRequest(book.id, it.id, it.blocks) }
        fun ask(book: Textbook = a) = BookPassageQuestion(BookPassage.from(book, book.lessons.first().blocks.first { !it.heading }), "Why does the plant need light?")
    }

    @Test fun actualEntryFactoryConnectsAndMissingCliGivesExactReason() = runBlocking {
        val f = Fixture()
        val available = localWindowsBookLearningSupport(f.library, f.root.resolve("synthetic-cli"))
        assertIs<ConsentingBookLearningSupport>(available)
        assertNotSame<BookLearningSupport>(UnconnectedBookLearningSupport, available)
        assertNotNull(assertIs<BookLearningReply.Unavailable>(available.explain(f.explain())).needsConsent)
        val missing = localWindowsBookLearningSupport(f.library, null)
        assertEquals(LocalCodexProblem.MISSING.message, assertIs<BookLearningReply.Unavailable>(missing.explain(f.explain())).message)
    }

    @Test fun grantIsPerHashActionProviderModelAndNoticeAndPersistsSeparately() = runBlocking {
        val f = Fixture()
        assertNotNull(assertIs<BookLearningReply.Unavailable>(f.support.explain(f.explain())).needsConsent)
        assertEquals(0, f.calls)
        val offer = f.support.consentOffer(f.a.id)
        f.support.grant(offer, setOf(OwnedBookAction.EXPLAIN))
        assertIs<BookLearningReply.Text>(f.support.explain(f.explain()))
        assertEquals(1, f.calls)
        for (request in listOf(f.explain(f.b), f.explain(f.newA)))
            assertNotNull(assertIs<BookLearningReply.Unavailable>(f.support.explain(request)).needsConsent)
        assertNotNull(assertIs<BookLearningReply.Unavailable>(f.support.ask(f.ask())).needsConsent)
        assertEquals(1, f.calls)
        val reopened = OwnedBookConsentStore(f.library)
        assertEquals(setOf(OwnedBookAction.EXPLAIN), reopened.read(f.a.id)!!.actions)
        val excerpt = OwnedBookExcerpt(OwnedBookCitation(f.a.id, 1, 0, 4), "test")
        val prompt = OwnedBookPrompt(OwnedBookAction.EXPLAIN, null, listOf(excerpt))
        assertTrue(ConsentedOwnedBookOutbound(reopened).allows(prompt))
        assertFalse(ConsentedOwnedBookOutbound(reopened).allows(prompt.copy(excerpts = emptyList())))
        assertFalse(ConsentedOwnedBookOutbound(reopened).allows(prompt.copy(excerpts = listOf(excerpt, excerpt.copy(citation = excerpt.citation.copy(book = f.b.id))))))
        val current = OwnedBookConsentIdentity()
        for (identity in listOf(current.copy(provider = "other"), current.copy(model = "other"), current.copy(notice = "v2"))) {
            assertFalse(ConsentedOwnedBookOutbound(reopened, identity).allows(prompt))
            val changed = ConsentedBookLearningSupport(f.library, reopened, f.transport, identity)
            assertNotNull(assertIs<BookLearningReply.Unavailable>(changed.explain(f.explain())).needsConsent)
        }
        assertEquals(1, f.calls)
    }

    @Test fun declineDoesNotRepeatedlyOfferAndRevokeDeletesAndStopsFurtherTransport() = runBlocking {
        val f = Fixture()
        assertTrue(f.support.consentOffer(f.a.id).offerAutomatically)
        f.support.decline(f.a.id)
        assertFalse(f.support.consentOffer(f.a.id).offerAutomatically)
        assertFalse(OwnedBookConsentStore(f.library).offer(f.a.id, OwnedBookConsentIdentity()).offerAutomatically)
        f.support.grant(f.support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
        assertIs<BookLearningReply.Text>(f.support.ask(f.ask()))
        f.support.revoke(f.a.id)
        assertNull(f.store.read(f.a.id))
        val count = f.calls
        assertNotNull(assertIs<BookLearningReply.Unavailable>(f.support.ask(f.ask())).needsConsent)
        assertEquals(count, f.calls)
        assertFalse(Files.exists(f.library.directory.resolve("book-consents/${f.a.id}.json")))
    }

    @Test fun revokeCancelsInFlightAndEvenUncooperativeLateAnswerIsDiscarded() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        val lateTransport = OwnedBookCliTransport {
            f.calls++; entered.complete(Unit)
            try { awaitCancellation() } finally {
                stopped = true
                withContext(NonCancellable) { delay(20) }
            }
        }
        val support = ConsentedBookLearningSupport(f.library, f.store, lateTransport)
        support.grant(support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
        val result = async { support.explain(f.explain()) }
        withTimeout(3000) { entered.await(); support.revoke(f.a.id) }
        assertTrue(stopped)
        assertNotNull(assertIs<BookLearningReply.Unavailable>(result.await()).needsConsent)
        assertIs<BookLearningReply.Unavailable>(support.explain(f.explain()))
        assertEquals(1, f.calls)
        // Revoke during a transport which suppresses cancellation and produces a late response.
        val suppressing = ConsentedBookLearningSupport(f.library, f.store, OwnedBookCliTransport {
            withContext(NonCancellable) { f.store.revoke(f.a.id); f.transport.complete(it) }
        })
        suppressing.grant(suppressing.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
        assertIs<BookLearningReply.Unavailable>(suppressing.explain(f.explain()))
        Unit
    }

    @Test fun removedBookAndMalformedRecordsFailClosed() = runBlocking {
        val f = Fixture()
        f.support.grant(f.support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
        Files.writeString(f.library.directory.resolve("book-consents/${f.a.id}.json"), "{broken")
        assertIs<BookLearningReply.Unavailable>(f.support.explain(f.explain()))
        assertEquals(0, f.calls)
        f.support.grant(f.support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
        Files.delete(f.library.directory.resolve("${f.a.id}.${f.a.format}"))
        assertNull(f.store.read(f.a.id))
        assertFalse(Files.exists(f.library.directory.resolve("book-consents/${f.a.id}.json")))
        f.support.forgetBook(f.b.id)
    }

    @Test fun typedFailuresSurviveProvidersGenericCatch() = runBlocking {
        val f = Fixture()
        for (problem in LocalCodexProblem.entries) {
            val support = ConsentedBookLearningSupport(f.library, f.store, OwnedBookCliTransport { throw LocalCodexFailure(problem) })
            support.grant(support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
            assertEquals(problem.message, assertIs<BookLearningReply.Unavailable>(support.explain(f.explain())).message)
        }
        assertEquals(LocalCodexProblem.MODEL, classifyCodexFailure("The model is not supported"))
        assertEquals(LocalCodexProblem.LOGIN, classifyCodexFailure("401 unauthorized"))
        assertEquals(LocalCodexProblem.FAILED, classifyCodexFailure("synthetic failure"))
    }
}
