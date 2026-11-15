package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * The passage composer must offer exactly the range the provider accepts, so the reader is never
 * allowed to type a question that can only come back as a provider failure.
 */
@OptIn(ExperimentalTestApi::class)
class WindowsPassageQuestionLimitTest {
    /** Records what the composer actually handed over; it never pretends to be an answer. */
    private class Recorder : BookLearningSupport {
        val questions = mutableListOf<String>()

        override suspend fun explain(request: BookExplanationRequest) =
            BookLearningReply.Unavailable("合成回执：本用例不评价讲解。")

        override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
            // Same guard OwnedBookLearningProvider.question() applies before any transport runs.
            require(BookPassageQuestion.accepts(request.question)) { "Outside the provider's question bound" }
            questions += request.question
            return BookLearningReply.Unavailable("合成回执：只记录请求，尚未接通方案商。")
        }
    }

    @Test fun blankAndOverBoundQuestionsStayOutOfTheProvider() {
        val root = Files.createTempDirectory("coach-passage-limit-")
        val source = learningPathTextbook(root, "Synthetic astronomy")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Recorder()
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openComposer(controller, source, support)
            onNodeWithTag("book-passage-length").assertTextEquals("0 / 500")
            onNodeWithTag("book-passage-send").assertIsNotEnabled()

            onNodeWithTag("book-passage-question").performTextInput("x".repeat(501))
            onNodeWithTag("book-passage-length").assertTextContains("已超过 500 字", substring = true)
            onNodeWithTag("book-passage-length").assertTextContains("删掉 1 字", substring = true)
            onNodeWithTag("book-passage-send").assertIsNotEnabled()
            onNodeWithTag("book-passage-unavailable").assertDoesNotExist()
            assertEquals(0, support.questions.size)

            // The old composer stopped the reader at 1000 characters by silently dropping the rest.
            onNodeWithTag("book-passage-question").performTextReplacement("x".repeat(1200))
            assertEquals(1200, typedQuestion().length)
            onNodeWithTag("book-passage-send").assertIsNotEnabled()
            assertEquals(0, support.questions.size)

            // Bound counts UTF-16 code units, exactly like the provider's String.length check.
            val clef = "\uD834\uDD1E"
            onNodeWithTag("book-passage-question").performTextReplacement(clef.repeat(250))
            onNodeWithTag("book-passage-length").assertTextEquals("500 / 500")
            onNodeWithTag("book-passage-send").assertIsEnabled()
            onNodeWithTag("book-passage-question").performTextReplacement(clef.repeat(251))
            onNodeWithTag("book-passage-length").assertTextContains("删掉 2 字", substring = true)
            onNodeWithTag("book-passage-send").assertIsNotEnabled()
            assertEquals(0, support.questions.size)
        }
    }

    @Test fun fiveHundredCodeUnitsReachTheProviderAndTheDraftSurvives() {
        val root = Files.createTempDirectory("coach-passage-bound-")
        val source = learningPathTextbook(root, "Synthetic astronomy")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Recorder()
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openComposer(controller, source, support)
            val question = "问".repeat(500)
            onNodeWithTag("book-passage-question").performTextInput(question)
            onNodeWithTag("book-passage-length").assertTextEquals("500 / 500")
            onNodeWithTag("book-passage-send").assertIsEnabled()
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { support.questions.size == 1 }
            assertEquals(question, support.questions.single())

            // A failed reply keeps the typed question, the chosen passage and the page it came from.
            onNodeWithTag("book-passage-unavailable").assertExists()
            assertEquals(question, typedQuestion())
            val block = controller.state.value.book!!.lessons[0].blocks.first { !it.heading }
            onNodeWithTag("book-selected-passage").assertTextEquals(block.text)
            assertEquals(1, controller.state.value.page)

            onNodeWithTag("book-passage-question").performTextReplacement("问".repeat(501))
            onNodeWithTag("book-passage-send").assertIsNotEnabled()
            onNodeWithTag("book-passage-length").assertTextContains("删掉 1 字", substring = true)
            assertEquals(1, support.questions.size)
        }
    }

    private fun ComposeUiTest.openComposer(controller: TextbookController, source: Path, support: BookLearningSupport) {
        setContent { TextbookWorkspace(controller, support = support) { source } }
        ready(controller) { it.catalog.isEmpty() }
        onNodeWithTag("upload-textbook").performClick()
        ready(controller) { it.prompt == BookPrompt.IMPORTED }
        onNodeWithTag("read-only").performClick()
        ready(controller) { it.screen == BookScreen.READER && it.prompt == BookPrompt.AFTER_READING }
        onNodeWithTag("read-only").performClick()
        ready(controller) { it.prompt == null }
        onNodeWithTag("ask-book-passage").performClick()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("ask-paragraph-0").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("ask-paragraph-0").performClick()
    }

    private fun ComposeUiTest.typedQuestion(): String =
        onNodeWithTag("book-passage-question").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun ComposeUiTest.ready(controller: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && condition(controller.state.value) }
        assertNull(controller.state.value.error)
        waitForIdle()
    }
}
