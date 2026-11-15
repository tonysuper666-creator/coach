package com.coach.desktop.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * A question already sent is one outbound request even if its dialog leaves the screen before the
 * answer arrives: closing the panel must not publish a late answer, and returning must not send it
 * a second time under the same authorization.
 */
@OptIn(ExperimentalTestApi::class)
class BookAskInFlightLifecycleTest {
    private val root = Files.createTempDirectory("book-ask-in-flight-")
    private val library = TextbookLibrary(root.resolve("library"))
    private val book = library.importBook(Files.writeString(root.resolve("synthetic.txt"),
        "Chapter 1 Plants\n\nPlants use light.\u000cChapter 2 Gravity\n\nGravity pulls fictional bodies together."))

    private class LatchingTransport(private val gate: CompletableDeferred<Unit>) : OwnedBookCliTransport {
        val outbound = AtomicInteger()
        override suspend fun complete(request: String): String {
            outbound.incrementAndGet()
            gate.await()
            return """{"answer":"One in-flight answer.","citations":[0]}"""
        }
    }

    /** Holds the answer until the test releases it, so the panel really leaves while the ask is open. */
    private class LatchingSupport(private val gate: CompletableDeferred<Unit>) : BookLearningSupport {
        val asks = AtomicInteger()
        override suspend fun explain(request: BookExplanationRequest): BookLearningReply =
            BookLearningReply.Unavailable("No explanation provider")
        override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
            asks.incrementAndGet()
            gate.await()
            return BookLearningReply.Text("Answer for ${request.passage.text}")
        }
    }

    /** Abandoning the panel mid-ask must not leak the answer into the next dialog and must not re-send. */
    @Test fun closingWhileTheAnswerIsInFlightPublishesNothingAndAsksOnce() {
        library.save(book.copy(readingPage = 2, promptSeen = true))
        val gate = CompletableDeferred<Unit>()
        val transport = LatchingTransport(gate)
        val store = OwnedBookConsentStore(library)
        val actual = ConsentedBookLearningSupport(library, store, transport)
        val asks = AtomicInteger()
        val support = object : ConsentingBookLearningSupport by actual {
            override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
                asks.incrementAndGet()
                return actual.ask(request)
            }
        }
        runBlocking { actual.grant(actual.consentOffer(book.id), setOf(OwnedBookAction.ASK)) }
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.read() }
        runComposeUiTest {
            setContent { WindowsTextbookHost(controller, support = support) { null } }
            onNodeWithTag("ask-book-passage").performClick()
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-passage-question").performTextInput("Why does it move?")
            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { transport.outbound.get() == 1 && asks.get() == 1 }
            onNodeWithTag("book-passage-answer").assertDoesNotExist()
            onNodeWithText("回到阅读").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-ask").fetchSemanticsNodes().isEmpty() }
            gate.complete(Unit)
            waitForIdle()
            assertEquals(1, asks.get(), "Closing the panel must not send the same question again")
            assertEquals(1, transport.outbound.get(), "Closing the panel must not produce another outbound")
            onAllNodesWithTag("book-passage-answer").assertCountEquals(0)
            onAllNodesWithTag("book-passage-unavailable").assertCountEquals(0)
            onNodeWithTag("ask-book-passage").performClick()
            waitForIdle()
            assertEquals(1, transport.outbound.get(), "A fresh panel must not replay the abandoned ask")
            onAllNodesWithTag("book-passage-answer").assertCountEquals(0)
            assertEquals(2, library.book(book.id).readingPage)
        }
    }

    /** Returning to a held session continues the same ask instead of starting a second outbound. */
    @Test fun returningWithTheHeldSessionContinuesTheSameAsk() {
        val page = book.pages.first()
        val gate = CompletableDeferred<Unit>()
        val support = LatchingSupport(gate)
        val state = BookPassageAskState(page)
        var visible by mutableStateOf(true)
        runSkikoComposeUiTest(size = Size(900f, 700f), density = Density(1f)) {
            setContent { if (visible) BookPassageAsk(book, state, support, onClose = { visible = false }) }
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-passage-question").performTextInput("Why does it move?")
            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { support.asks.get() == 1 }
            onNodeWithTag("book-passage-answer").assertDoesNotExist()

            runOnIdle { visible = false }
            waitForIdle()
            onNodeWithTag("book-passage-ask").assertDoesNotExist()
            assertEquals(1, support.asks.get(), "Leaving the panel must not start a second ask")

            runOnIdle { visible = true }
            waitForIdle()
            assertEquals(1, support.asks.get(), "Returning to the same authorization must reuse the ask already sent")
            onNodeWithTag("book-passage-question").assertTextContains("Why does it move?")

            gate.complete(Unit)
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-answer").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-passage-answer").assertTextEquals("Answer for ${state.selected?.text}")
            assertEquals(1, support.asks.get())
        }
    }

    /** Revocation while the ask is open drops that answer and adds no outbound. */
    @Test fun revokingWhileTheAnswerIsInFlightDropsItAndSendsNothingNew() {
        library.save(book.copy(readingPage = 2, promptSeen = true))
        val gate = CompletableDeferred<Unit>()
        val transport = LatchingTransport(gate)
        val store = OwnedBookConsentStore(library)
        val actual = ConsentedBookLearningSupport(library, store, transport)
        val asks = AtomicInteger()
        val support = object : ConsentingBookLearningSupport by actual {
            override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
                asks.incrementAndGet()
                return actual.ask(request)
            }
        }
        runBlocking { actual.grant(actual.consentOffer(book.id), setOf(OwnedBookAction.ASK)) }
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.read() }
        runComposeUiTest {
            setContent { WindowsTextbookHost(controller, support = support) { null } }
            onNodeWithTag("ask-book-passage").performClick()
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-passage-question").performTextInput("Why does it move?")
            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { transport.outbound.get() == 1 && asks.get() == 1 }
            runBlocking { actual.revoke(book.id) }
            gate.complete(Unit)
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-passage-answer").assertDoesNotExist()
            onNodeWithTag("book-source-0").assertDoesNotExist()
            // The epoch change may retry through the support, but the withdrawn consent rejects it before any outbound.
            assertEquals(1, transport.outbound.get(), "Revoking an in-flight ask must not reach the provider again")
            assertNull(store.read(book.id))
            assertEquals(2, library.book(book.id).readingPage)
        }
    }
}
