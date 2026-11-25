package com.coach.desktop.books

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * 做题学答完之后的这一段：先问一次允许，再只把题目依据那一段发出去，讲完给一张小结卡。
 * 这里全部用本地假通道，网络与 Mac 都不参与。
 */
@OptIn(ExperimentalTestApi::class)
class AnswerExplainCardTest {
    private class FakeChannel(private val consent: ExplainConsent) {
        var paired = true
        val asked = mutableListOf<Pair<String, String>>()
        var reply: (SourceBlock) -> ExplainResult = { chosen ->
            ExplainResult.Done(Explanation("这一段讲的是物体绕行另一个天体的路径。【c1】",
                listOf(Citation("c1", chosen.id, chosen.page)), 12))
        }

        fun gate() = AnswerExplain(
            consent = consent, load = {}, ready = { true }, paired = { paired },
            explain = { chosen, blocks ->
                assertEquals(listOf(chosen), blocks)
                asked += chosen.id to chosen.text
                reply(chosen)
            },
        )
    }

    private class Fixture(val root: Path, val directory: Path, val controller: TextbookController, val book: Textbook)

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("coach-answer-explain-")
        val directory = root.resolve("library")
        val controller = TextbookController(TextbookLibrary(directory))
        runBlocking {
            controller.importBook(learningPathTextbook(root, "Synthetic orbits"))
            controller.chooseLearningPath(LearningPath.QUESTION_FIRST)
            controller.enableLearning()
        }
        val book = controller.state.value.book!!
        return Fixture(root, directory, controller, book)
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    @Test fun answeringAsksOnceSendsOneBlockThenShowsExplanationAndSummary() {
        val f = fixture()
        val settings = ReadingSettings(f.directory).apply { chooseAiExplain(false) }
        val channel = FakeChannel(ExplainConsent(f.directory))
        val gate = channel.gate()
        runBlocking { f.controller.mode(QuestionMode.SHORT_ANSWER) }
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent {
                CompositionLocalProvider(LocalReadingAiExplain provides settings.aiExplain) {
                    TextbookWorkspace(f.controller, loadOnMount = false, answerExplain = gate) { null }
                }
            }
            ready(f.controller) { it.screen == BookScreen.LEARNING }
            answerOfflineQuestion(f.controller)
            onNodeWithText("已在设置中关闭伴读讲解与提问。").assertExists()
            assertTrue(channel.asked.isEmpty())
            runOnIdle { settings.chooseAiExplain(true) }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
            val chosen = f.book.lessons.first().blocks.first { it.isBodyParagraph }
            assertEquals(listOf(chosen.id to chosen.text), channel.asked)
            onNodeWithText("这一段讲的是物体绕行另一个天体的路径。", substring = true).assertExists()
            onNodeWithTag("book-summary-card").assertExists()
            onNodeWithTag("book-summary-detail").assertTextContains("你写下了 4 个字", substring = true)
            onNodeWithTag("next-lesson").performScrollTo().performClick()
            ready(f.controller) { it.book?.lessonIndex == 1 }
            onNodeWithTag("book-summary-card").assertDoesNotExist()
        }
    }

    private fun ComposeUiTest.answerOfflineQuestion(controller: TextbookController) {
        onNodeWithTag("short-answer").performScrollTo().performTextInput("合成答案")
        ready(controller) { it.answer.text == "合成答案" }
        onNodeWithTag("reveal-reference").performScrollTo().performClick()
        ready(controller) { it.answer.revealed }
    }

    @Test fun reopeningResumesTheSameWalkWithTheSummaryAndNoSecondConsent() {
        val f = fixture()
        val consent = ExplainConsent(f.directory)
        val channel = FakeChannel(consent)
        runBlocking { f.controller.mode(QuestionMode.SHORT_ANSWER) }
        var gate = channel.gate()
        var controller by mutableStateOf(f.controller)
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent { key(controller) { TextbookWorkspace(controller, loadOnMount = false, answerExplain = gate) { null } } }
            ready(controller) { it.screen == BookScreen.LEARNING }
            answerOfflineQuestion(controller)
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-summary-card").assertExists()

            // 重开窗口：走法、节、作答都回来，小结卡还在，允许过就不再问。
            val reopened = TextbookController(TextbookLibrary(f.directory))
            runBlocking { reopened.load(); reopened.learn() }
            runOnIdle { controller = reopened; gate = channel.gate() }
            ready(reopened) { it.screen == BookScreen.LEARNING && it.answer.revealed }
            assertEquals(LearningPath.QUESTION_FIRST, reopened.state.value.learningPath)
            onNodeWithTag("answer-explain-consent").assertDoesNotExist()
            onNodeWithTag("book-summary-card").assertExists()
            assertEquals("合成答案", reopened.state.value.answer.text)
            onNodeWithTag("book-summary-detail").assertTextContains("你写下了 4 个字", substring = true)
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
            val chosen = f.book.lessons.first().blocks.first { it.isBodyParagraph }
            assertEquals(listOf(chosen.id to chosen.text), channel.asked)
            onNodeWithText(f.book.lessons[1].text).assertDoesNotExist()
        }
    }

    @Test fun unpairedComputerSendsNothingAndPointsAtTheExistingPairing() {
        val f = fixture()
        runBlocking { f.controller.mode(QuestionMode.SHORT_ANSWER) }
        val channel = FakeChannel(ExplainConsent(f.directory)).apply { paired = false }
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent { TextbookWorkspace(f.controller, loadOnMount = false, answerExplain = channel.gate()) { null } }
            ready(f.controller) { it.screen == BookScreen.LEARNING }
            // TON-1784 keeps offline self-check available while multiple choice requires a service.
            onNodeWithTag("short-answer").performScrollTo().performTextInput("合成答案")
            ready(f.controller) { it.answer.text == "合成答案" }
            onNodeWithTag("reveal-reference").performScrollTo().performClick()
            ready(f.controller) { it.answer.revealed }
            onNodeWithTag("answer-explain-unpaired").assertExists()
            onNodeWithTag("answer-explain-pair").assertExists()
            onNodeWithTag("answer-explain-consent").assertDoesNotExist()
            onNodeWithTag("book-lesson-explanation").assertDoesNotExist()
            assertTrue(channel.asked.isEmpty())
            onNodeWithTag("book-summary-card").assertExists()
        }
    }

    @Test fun shortAnswerSummaryCountsWhatWasWritten() {
        val f = fixture()
        val channel = FakeChannel(ExplainConsent(f.directory)).apply { paired = false }
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent { TextbookWorkspace(f.controller, loadOnMount = false, answerExplain = channel.gate()) { null } }
            ready(f.controller) { it.screen == BookScreen.LEARNING }
            runOnIdle { runBlocking { f.controller.mode(QuestionMode.SHORT_ANSWER) } }
            ready(f.controller) { it.book?.questionMode == QuestionMode.SHORT_ANSWER }
            onNodeWithTag("short-answer").performScrollTo().performTextInput("行星绕着恒星走。")
            ready(f.controller) { it.answer.text.isNotBlank() }
            onNodeWithTag("reveal-reference").performScrollTo().performClick()
            ready(f.controller) { it.answer.revealed }
            onNodeWithTag("book-summary-card").assertExists()
            onNodeWithTag("book-summary-detail").assertTextContains("你写下了 8 个字", substring = true)
            onNodeWithTag("book-summary-detail").assertTextContains("还没标记是否理解", substring = true)
            assertTrue(channel.asked.isEmpty())
        }
    }
}
