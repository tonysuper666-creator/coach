package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import java.nio.file.Files
import java.io.File
import javax.imageio.ImageIO
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BookConsentUiTest {
    private fun SkikoComposeUiTest.capture(name: String, tag: String = "windows-host") {
        val directory = File("build/screens/book-consent").apply { mkdirs() }
        check(ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(), "png", File(directory, "$name.png")))
    }

    private class Fixture(complete: (suspend (String) -> String)? = null) {
        val root = Files.createTempDirectory("synthetic-consent-ui-")
        val source = Files.writeString(root.resolve("Synthetic plants.txt"),
            "Photosynthesis is the process by which plants use light to make sugars.\n\nRoots absorb water and minerals from soil.")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(source)
        val store = OwnedBookConsentStore(library)
        val calls = AtomicInteger()
        val support = ConsentedBookLearningSupport(library, store, OwnedBookCliTransport { input ->
            calls.incrementAndGet()
            complete?.invoke(input) ?: """{"answer":"Light supplies energy for making stored food.","citations":[0]}"""
        })
        val controller = TextbookController(library)
        init {
            library.save(book.copy(learning = true, questionMode = QuestionMode.SHORT_ANSWER))
            runBlocking { controller.load(); controller.learn(LearningPath.READ_AND_LEARN) }
        }
    }

    @Test fun decliningSurvivesNavigationAndOnlyAffirmativeReopenAllowsBothActions() {
        val f = Fixture()
        runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
            setContent { WindowsTextbookHost(f.controller, support = f.support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-notice").fetchSemanticsNodes().isNotEmpty() }
            capture("notice")
            assertEquals(0, f.calls.get())
            onNodeWithTag("book-consent-decline").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { !f.support.consentOffer(f.book.id).offerAutomatically }
            onNodeWithTag("book-consent-notice").assertDoesNotExist()
            onNodeWithTag("guided-start-questions").performScrollTo().performClick()
            onNodeWithTag("short-answer").performScrollTo().performTextInput("Synthetic draft remains usable")
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { f.controller.state.value.screen == BookScreen.READER && !f.controller.state.value.busy }
            onNodeWithTag("ask-book-passage").performClick()
            onNodeWithTag("ask-paragraph-0").performScrollTo().performClick()
            onNodeWithTag("book-passage-question").performScrollTo().performTextInput("Why is light needed?")
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-consent-notice").assertDoesNotExist()
            assertEquals(0, f.calls.get())
            onNodeWithText("回到阅读").performClick()
            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { f.controller.state.value.screen == BookScreen.LEARNING && !f.controller.state.value.busy }
            onNodeWithTag("short-answer").performScrollTo().assertTextContains("Synthetic draft remains usable")
            onNodeWithTag("book-consent-reopen").performScrollTo().performClick()
            onNodeWithTag("book-consent-notice").assertExists()
            assertEquals(0, f.calls.get(), "Opening the notice is not consent")
            onNodeWithTag("book-consent-grant").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, f.calls.get())
            waitForIdle()
            assertEquals(1, f.calls.get(), "The same consent click must remain one request after idle")
            assertEquals(setOf(OwnedBookAction.EXPLAIN, OwnedBookAction.ASK), f.store.read(f.book.id)!!.actions)
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { f.controller.state.value.screen == BookScreen.READER && !f.controller.state.value.busy }
            onNodeWithTag("ask-book-passage").performClick()
            onNodeWithTag("ask-paragraph-0").performScrollTo().performClick()
            onNodeWithTag("book-passage-question").performScrollTo().performTextInput("What does light supply?")
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-answer").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, f.calls.get())
            waitForIdle()
            assertEquals(2, f.calls.get(), "The passage send must remain one request after idle")
            onNodeWithTag("book-consent-revoke").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-reopen").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-passage-answer").assertDoesNotExist()
            capture("withdrawn", "book-passage-ask")
            assertNull(f.store.read(f.book.id))
            assertEquals(2, f.calls.get())
            onNodeWithText("回到阅读").performClick()
            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { f.controller.state.value.screen == BookScreen.LEARNING && !f.controller.state.value.busy }
            onNodeWithTag("book-explanation-text").assertDoesNotExist()
            assertEquals(2, f.calls.get(), "Returning must not reuse the revoked answer or spawn another request")
        }
    }

    @Test fun revokeFromAnotherViewInvalidatesTheHeldLessonReply() {
        val f = Fixture()
        runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
            setContent { WindowsTextbookHost(f.controller, support = f.support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-grant").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-consent-grant").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { f.controller.state.value.screen == BookScreen.READER && !f.controller.state.value.busy }
            runBlocking { f.support.revoke(f.book.id) }
            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { f.controller.state.value.screen == BookScreen.LEARNING && !f.controller.state.value.busy }
            onNodeWithTag("book-explanation-text").assertDoesNotExist()
            assertEquals(1, f.calls.get())
        }
    }

    @Test fun withdrawingDuringAnUncooperativeAnswerHidesItImmediatelyAndDiscardsItsLateReply() {
        val release = CompletableDeferred<Unit>()
        val f = Fixture { withContext(NonCancellable) { release.await() }; """{"answer":"Late synthetic answer.","citations":[0]}""" }
        try {
            runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
                setContent { WindowsTextbookHost(f.controller, support = f.support) { null } }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-grant").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("book-consent-grant").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { f.calls.get() == 1 }
                onNodeWithTag("book-consent-revoke").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { f.store.read(f.book.id) == null }
                onNodeWithTag("book-explanation-text").assertDoesNotExist()
                release.complete(Unit)
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-reopen").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Late synthetic answer.").assertDoesNotExist()
                assertEquals(1, f.calls.get())
            }
        } finally { release.complete(Unit) }
    }

    @Test fun removingAndReimportingKeepsStudyRecordsButForgetsConsentAndDecline() {
        val f = Fixture()
        val lesson = f.book.lessons.first()
        f.library.saveAnswer(f.book.id, lesson.id, QuestionMode.SHORT_ANSWER,
            PracticeAnswer("Synthetic history", revealed = true, understood = false))
        runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
            setContent { WindowsTextbookHost(f.controller, support = f.support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-decline").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-consent-decline").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { !f.support.consentOffer(f.book.id).offerAutomatically }
            onNodeWithText("返回书架").performClick()
            waitUntil(timeoutMillis = 20_000) { f.controller.state.value.screen == BookScreen.HOME && !f.controller.state.value.busy }
            onNodeWithTag("remove-book").performScrollTo().performClick()
            onNodeWithTag("confirm-remove-book").performClick()
            waitUntil(timeoutMillis = 20_000) { f.controller.state.value.book == null && !f.controller.state.value.busy }
            assertTrue(f.library.catalog().isEmpty())
            assertTrue(f.library.reviews().isEmpty())
            assertTrue(f.support.consentOffer(f.book.id).offerAutomatically)
            assertNull(f.store.read(f.book.id))
            assertTrue(TextbookLibrary(f.library.directory).catalog().isEmpty(), "Reopening the library must handle removed reviews")
            val restored = f.library.importBook(f.source)
            assertEquals(f.book.id, restored.id)
            assertEquals("Synthetic history", f.library.answer(restored.id, lesson.id, QuestionMode.SHORT_ANSWER).text)
            assertEquals(1, f.library.reviews().size)
            assertNull(f.store.read(restored.id))
            assertEquals(0, f.calls.get())
        }
    }

    @Test fun oneApprovalIsOneOutboundRequestEveryTime() {
        val f = Fixture()
        runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
            setContent { WindowsTextbookHost(f.controller, support = f.support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-grant").fetchSemanticsNodes().isNotEmpty() }
            repeat(10) { round ->
                if (round > 0) {
                    onNodeWithTag("book-consent-reopen").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-grant").fetchSemanticsNodes().isNotEmpty() }
                }
                val before = f.store.changes.value
                onNodeWithTag("book-consent-grant").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(before + 1, f.store.changes.value, "One approval must be one revision for observers")
                assertEquals(round + 1, f.calls.get(), "One approval must be exactly one outbound request")
                assertEquals(setOf(OwnedBookAction.EXPLAIN, OwnedBookAction.ASK), f.store.read(f.book.id)!!.actions)
                onNodeWithTag("book-consent-revoke").performScrollTo().performClick()
                // The reopen button appears while the close is still running, but it stays disabled and a
                // click then is silently swallowed; wait for it to be clickable, not merely present.
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodes(hasTestTag("book-consent-reopen") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                }
            }
            assertEquals(10, f.calls.get())
        }
    }

    @Test fun closingTheExplanationIsRememberedAcrossReentryAndOnlyReopenOffersAgain() {
        val f = Fixture()
        runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
            setContent { WindowsTextbookHost(f.controller, support = f.support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-grant").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-consent-grant").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-consent-revoke").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) {
                onAllNodes(hasTestTag("book-consent-reopen") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(f.support.consentOffer(f.book.id).offerAutomatically, "Closing must be remembered")
            assertEquals(1, f.calls.get())
            onNodeWithText("返回书架").performClick()
            waitUntil(timeoutMillis = 20_000) { f.controller.state.value.screen == BookScreen.HOME && !f.controller.state.value.busy }
            onNodeWithTag("home-guided").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { f.controller.state.value.screen == BookScreen.LEARNING && !f.controller.state.value.busy }
            waitUntil(timeoutMillis = 20_000) {
                onAllNodes(hasTestTag("book-consent-reopen") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("book-consent-notice").assertDoesNotExist()
            assertEquals(1, f.calls.get(), "Reentering after closing must not send anything")
            onNodeWithTag("book-consent-reopen").performScrollTo().performClick()
            onNodeWithTag("book-consent-notice").assertExists()
            assertEquals(1, f.calls.get(), "Opening the notice is not consent")
        }
    }

    @Test fun removalWithoutACliStillForgetsAnEarlierGrantAndRestoresTheOriginalFormat() = runBlocking {
        val f = Fixture()
        f.support.grant(f.support.consentOffer(f.book.id), setOf(OwnedBookAction.EXPLAIN, OwnedBookAction.ASK))
        val record = f.library.directory.resolve("book-consents/${f.book.id}.json")
        assertTrue(Files.exists(record))
        f.controller.removeBook(f.book.id, null)
        assertFalse(Files.exists(record), "Deleting while Codex is missing must still remove old consent")
        assertTrue(f.library.catalog().isEmpty())
        val renamed = Files.copy(f.source, f.root.resolve("Synthetic plants.md"))
        val restored = f.library.importBook(renamed)
        assertEquals("txt", restored.format)
        assertTrue(Files.exists(f.library.directory.resolve("${restored.id}.txt")))
        assertEquals(f.book.id, restored.id)
        assertNull(f.store.read(restored.id))
    }

    @OptIn(InternalCoroutinesApi::class)
    @Test fun delayedConsentNotificationsDoNotResendAnExplanationAfterGrant() {
        val f = Fixture()
        // A StateFlow value can already be current while a slow collector still sees an older emission.
        val support = object : ConsentingBookLearningSupport by f.support {
            override val changes = object : StateFlow<Long> by f.support.changes {
                override suspend fun collect(collector: FlowCollector<Long>): Nothing =
                    f.support.changes.collect(object : FlowCollector<Long> {
                        override suspend fun emit(value: Long) {
                            delay(100)
                            collector.emit(value)
                        }
                    })
            }
        }
        runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
            setContent { WindowsTextbookHost(f.controller, support = support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-grant").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-consent-grant").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
            repeat(4) { mainClock.advanceTimeBy(100); waitForIdle() }
            onNodeWithTag("book-explanation-text").assertExists()
            assertEquals(1, f.calls.get(), "Late grant epochs must not resend the same excerpt")
        }
    }

    @Test fun anUnfinishedGrantDoesNotStartExplanationEvenAfterTheRecordIsWritten() {
        val f = Fixture()
        val written = CompletableDeferred<Unit>()
        val finishGrant = CompletableDeferred<Unit>()
        val support = object : ConsentingBookLearningSupport by f.support {
            override suspend fun grant(offer: NeedsConsent, actions: Set<OwnedBookAction>) {
                f.support.grant(offer, actions)
                written.complete(Unit)
                finishGrant.await()
            }
        }
        try {
            runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
                setContent { WindowsTextbookHost(f.controller, support = support) { null } }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-grant").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("book-consent-grant").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { written.isCompleted }
                repeat(4) { mainClock.advanceTimeBy(100); waitForIdle() }
                assertEquals(0, f.calls.get(), "A stored record must not start a request before grant finishes")
                finishGrant.complete(Unit)
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                assertEquals(1, f.calls.get())
            }
        } finally { finishGrant.complete(Unit) }
    }
}
