package com.coach.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsOwnedBookHostTest {
    @Test fun hostConsumesOnlyAuthorizedLocalBookSupport() {
        val root = Files.createTempDirectory("coach-owned-host-")
        val library = TextbookLibrary(root.resolve("library"))
        val controller = TextbookController(library)
        runBlocking {
            controller.importBook(learningPathTextbook(root, "Synthetic provider host"))
            controller.chooseLearningPath(LearningPath.READ_AND_LEARN)
            controller.enableLearning()
        }
        val book = controller.state.value.book!!
        val block = book.lessons.first().blocks.single { !it.heading }
        var calls = 0
        val python = System.getenv("COACH_SYNTHETIC_BOOK_PYTHON")
        val receipt = root.resolve("cli-receipt.json")
        val live = python?.let {
            val bridge = generateSequence(Path.of("").toAbsolutePath()) { path -> path.parent }
                .map { path -> path.resolve("tools/bridge") }
                .first { path -> Files.exists(path.resolve("owned_book_cli.py")) }
            val script = root.resolve("synthetic_adapter.py")
            Files.writeString(script, """
                import asyncio, json, pathlib, sys
                sys.path.insert(0, sys.argv[1])
                import owned_book_cli, subscription_bridge as bridge
                expected = {"action":"explain", "excerpts":[{"id":0,"text":"Orbit is the path of an object around another body. An orbit can be elliptical."}]}
                original = bridge.run_cli
                async def observed(argv, *args, **kwargs):
                    result = await original(argv, *args, **kwargs)
                    pathlib.Path(sys.argv[2]).write_text(json.dumps({"argv":argv,"exit":result[0]}))
                    return result
                bridge.run_cli = observed
                async def main():
                    request = json.load(sys.stdin)
                    if request != expected:
                        raise ValueError("only the exact synthetic fixture is authorized")
                    response = await owned_book_cli.complete_book(request, policy=lambda candidate: candidate == expected)
                    print(json.dumps(response))
                asyncio.run(main())
            """.trimIndent())
            OwnedBookProcessTransport(listOf(it, script.toString(), bridge.toString(), receipt.toString()))
        }
        val transport = OwnedBookCliTransport {
            calls++
            if (live != null) live.complete(it)
            else """{"answer":"An orbit describes motion around another body.","citations":[0]}"""
        }
        val denied = windowsBookLearningSupport(library, transport)
        val request = BookExplanationRequest(book.id, book.lessons.first().id, book.lessons.first().blocks)
        assertIs<BookLearningReply.Unavailable>(runBlocking { denied.explain(request) })
        assertEquals(0, calls)
        val provider = windowsBookLearningSupport(library, transport, OwnedBookOutboundPolicy { prompt ->
            prompt.action == OwnedBookAction.EXPLAIN && prompt.question == null &&
                prompt.excerpts == listOf(OwnedBookExcerpt(
                    OwnedBookCitation(book.id, block.page, block.start, block.end), block.text))
        })
        var connected by mutableStateOf(false)
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent {
                if (connected) WindowsTextbookHost(controller, support = provider) { null }
                else WindowsTextbookHost(controller) { null }
            }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-unavailable").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, calls)
            runOnIdle { connected = true }
            waitUntil(timeoutMillis = if (live == null) 20_000 else 180_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() || onAllNodesWithTag("book-explanation-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-explanation-text").assertExists()
            if (live == null) onNodeWithTag("book-explanation-text").assertTextContains("An orbit describes motion", substring = true)
            onNodeWithTag("book-source-0").assertTextContains("第 ${block.page} 页", substring = true)
            assertEquals(1, calls)
            if (live != null) {
                val evidence = Files.readString(receipt)
                assertTrue(evidence.contains("\"exit\": 0"))
                println("SYNTHETIC_BOOK_CLI $evidence")
            }
            onNodeWithTag("guided-start-questions").performClick()
            onNodeWithTag("question-settings").performScrollTo().performClick()
            onNodeWithTag("question-mode-SHORT_ANSWER").performClick()
            onNodeWithTag("close-question-settings").performClick()
            onNodeWithTag("short-answer").performScrollTo().performTextInput("Synthetic draft")
            waitUntil(timeoutMillis = 20_000) { controller.state.value.answer.text == "Synthetic draft" }
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && controller.state.value.source != null }
            assertEquals(block.page, controller.state.value.page)
            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING }
            onNodeWithTag("short-answer").performScrollTo().assertTextContains("Synthetic draft")
            onNodeWithTag("open-lesson-source").assertIsFocused()
            assertEquals(book.readingPage, library.book(book.id).readingPage)
            onNodeWithTag("book-explanation-text").assertExists()
            assertEquals(1, calls)
        }
    }
}
