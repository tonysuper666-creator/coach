package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Opt-in: the uploader clicks consent for this exact synthetic book, through the normal host UI. */
@OptIn(ExperimentalTestApi::class)
class BookConsentUiCodexLiveTest {
    @Test fun syntheticUploaderClicksDeclineReopenGrantAskAndRevoke() {
        val root = Files.createTempDirectory("synthetic-consent-buttons-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("Fictional plant.txt"),
            "Photosynthesis is the process by which plants use light to make sugars."))
        library.save(book.copy(learning = true, questionMode = QuestionMode.NONE))
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.learn(LearningPath.READ_AND_LEARN) }
        val spawns = AtomicInteger()
        val transport = LocalCodexBookTransport(assertNotNull(discoverLocalCodex()), receipt = { argv, exit ->
            spawns.incrementAndGet()
            val sanitized = argv.mapIndexed { i, value ->
                when { i == 0 -> "<local-codex>"; argv[i - 1] == "-o" -> "<temporary-output>"; else -> value }
            }
            println("SYNTHETIC_UI_CLI argv=$sanitized exit=$exit")
        })
        val store = OwnedBookConsentStore(library)
        val support = ConsentedBookLearningSupport(library, store, transport)
        runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
            setContent { WindowsTextbookHost(controller, support = support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-decline").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-consent-decline").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-reopen").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, spawns.get())
            println("SYNTHETIC_UI_DECLINED spawns=0")
            onNodeWithTag("book-consent-reopen").performScrollTo().performClick()
            assertEquals(0, spawns.get())
            onNodeWithTag("book-consent-grant").performScrollTo().performClick()
            waitUntil(timeoutMillis = 180_000) {
                onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() ||
                    onAllNodesWithTag("book-explanation-unavailable").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("book-explanation-text").assertExists()
            onNodeWithTag("book-source-0").assertExists()
            assertEquals(2, spawns.get())
            println("SYNTHETIC_UI_EXPLAIN spawns=2 book=${book.id}")
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }
            onNodeWithTag("ask-book-passage").performClick()
            onNodeWithTag("ask-paragraph-0").performScrollTo().performClick()
            onNodeWithTag("book-passage-question").performScrollTo().performTextInput("Why does the plant need light?")
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 180_000) {
                onAllNodesWithTag("book-passage-answer").fetchSemanticsNodes().isNotEmpty() ||
                    onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("book-passage-answer").assertExists()
            assertEquals(4, spawns.get())
            onNodeWithTag("book-consent-revoke").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-consent-reopen").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-passage-answer").assertDoesNotExist()
            assertNull(store.read(book.id))
            onNodeWithTag("book-passage-send").assertIsNotEnabled()
            onNodeWithText("回到阅读").performClick()
            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            onNodeWithTag("book-explanation-text").assertDoesNotExist()
            assertEquals(4, spawns.get())
            println("SYNTHETIC_UI_REVOKED subsequent_spawns=0 displayed_answers=0")
        }
    }
}
