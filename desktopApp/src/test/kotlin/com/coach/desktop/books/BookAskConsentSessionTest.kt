package com.coach.desktop.books

import androidx.compose.ui.test.*
import com.coach.desktop.WindowsTextbookHost
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** The previously separate consent collector and source-return session must share one reply epoch. */
@OptIn(ExperimentalTestApi::class)
class BookAskConsentSessionTest {
    @Test fun authorizedAnswerSurvivesSourceRemountAndRevokedAnswerDoesNot() {
        val root = Files.createTempDirectory("synthetic-ask-consent-session-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("synthetic.txt"),
            "Chapter 1 Plants\n\nPlants use light.\u000cChapter 2 Gravity\n\nGravity pulls fictional bodies together."))
        library.save(book.copy(readingPage = 2, promptSeen = true))
        val store = OwnedBookConsentStore(library)
        val outbound = AtomicInteger()
        val actual = ConsentedBookLearningSupport(library, store, OwnedBookCliTransport {
            outbound.incrementAndGet()
            """{"answer":"One authorized answer.","citations":[0]}"""
        })
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
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("return-to-passage-question").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("return-to-passage-question").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-answer").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            assertEquals(1, asks.get(), "Returning must reuse the already authorized answer")
            assertEquals(1, outbound.get())
            onNodeWithTag("book-passage-question").assertTextContains("Why does it move?")
            onNodeWithTag("book-source-0").assertIsFocused()
            onNodeWithTag("book-source-0").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("return-to-passage-question").fetchSemanticsNodes().isNotEmpty() }
            runBlocking { actual.revoke(book.id) }
            onNodeWithTag("return-to-passage-question").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-passage-answer").assertDoesNotExist()
            onNodeWithTag("book-source-0").assertDoesNotExist()
            onNodeWithTag("book-passage-question").assertTextContains("Why does it move?")
            assertEquals(1, outbound.get(), "Revocation must not start another CLI request")
            assertNull(store.read(book.id))
            assertEquals(2, library.book(book.id).readingPage)
        }
    }
}
