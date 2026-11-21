package com.coach.desktop.books

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
            explain = { chosen, _ -> asked += chosen.id to chosen.text; reply(chosen) },
        )
    }

    private class Fixture(val root: Path, val directory: Path, val controller: TextbookController, val book: Textbook, val practice: Practice)

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
        val lesson = book.lessons[book.lessonIndex]
        return Fixture(root, directory, controller, book, com.coach.desktop.books.testPracticeFixture(book, lesson, book.questionMode)!!)
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    @Test fun answeringAsksOnceSendsOneBlockThenShowsExplanationAndSummary() {
        val f = fixture()
        val consent = ExplainConsent(f.directory)
        val channel = FakeChannel(consent)
        val gate = channel.gate()
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent { TextbookWorkspace(f.controller, loadOnMount = false, answerExplain = gate) { null } }
            ready(f.controller) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("answer-explain-consent").assertDoesNotExist()
            onNodeWithTag("choice-${f.practice.options.indexOf(f.practice.answer)}").performScrollTo().performClick()
            ready(f.controller) { it.answer.revealed }

            // 答完先问一次，没允许之前一个字都没发出去。
            onNodeWithTag("answer-explain-consent").assertExists()
            onNodeWithTag("book-lesson-explanation").assertDoesNotExist()
            assertTrue(channel.asked.isEmpty(), "consent must come before anything leaves this computer")

            onNodeWithTag("answer-explain-allow").performScrollTo().performClick()
            ready(f.controller) { it.answer.revealed }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, channel.asked.size)
            val lesson = f.book.lessons[f.book.lessonIndex]
            val sent = channel.asked.single()
            assertTrue(sent.second.contains(f.practice.reference), "the tested sentence's block is the one explained")
            // 一次讲解只带题目依据那一段和前后邻段，不把整节课铺出去。
            val wide = (0 until 5).map {
                SourceBlock("${f.book.id}:p1:${it * 10}", 1, it * 10, it * 10 + 8, false, "block $it")
            }
            assertEquals(listOf("block 1", "block 2", "block 3"), ExplainWire.context(wide, wide[2]).map { it.text })
            onNodeWithText("这一段讲的是物体绕行另一个天体的路径。", substring = true).assertExists()
            onNodeWithText(lesson.text).assertDoesNotExist()

            // 小结卡写的是真的发生过的事，并且能接着走。
            onNodeWithTag("book-summary-card").assertExists()
            onNodeWithTag("book-summary-detail").assertTextContains("和原文一致", substring = true)
            onNodeWithTag("next-lesson").performScrollTo().performClick()
            ready(f.controller) { it.book?.lessonIndex == 1 }
            onNodeWithTag("book-summary-card").assertDoesNotExist()
        }
    }

    @Test fun reopeningResumesTheSameWalkWithTheSummaryAndNoSecondConsent() {
        val f = fixture()
        val consent = ExplainConsent(f.directory)
        val channel = FakeChannel(consent)
        var gate = channel.gate()
        var controller by mutableStateOf(f.controller)
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent { key(controller) { TextbookWorkspace(controller, loadOnMount = false, answerExplain = gate) { null } } }
            ready(controller) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("choice-${f.practice.options.indexOf(f.practice.answer)}").performScrollTo().performClick()
            ready(controller) { it.answer.revealed }
            onNodeWithTag("answer-explain-allow").performScrollTo().performClick()
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
            onNodeWithTag("book-summary-detail").assertTextContains("和原文一致", substring = true)
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, channel.asked.size)
            onNodeWithText(f.book.lessons[1].text).assertDoesNotExist()
        }
    }

    @Test fun unpairedComputerSendsNothingAndPointsAtTheExistingPairing() {
        val f = fixture()
        val channel = FakeChannel(ExplainConsent(f.directory)).apply { paired = false }
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent { TextbookWorkspace(f.controller, loadOnMount = false, answerExplain = channel.gate()) { null } }
            ready(f.controller) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("choice-${f.practice.options.indexOf(f.practice.answer)}").performScrollTo().performClick()
            ready(f.controller) { it.answer.revealed }
            onNodeWithTag("answer-explain-unpaired").assertExists()
            onNodeWithTag("answer-explain-pair").assertExists()
            onNodeWithTag("answer-explain-consent").assertDoesNotExist()
            onNodeWithTag("book-lesson-explanation").assertDoesNotExist()
            assertTrue(channel.asked.isEmpty())
            onNodeWithTag("book-summary-card").assertExists()
        }
    }

    @Test fun skippingSendsNothingAndCanBeUndone() {
        val f = fixture()
        val channel = FakeChannel(ExplainConsent(f.directory))
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent { TextbookWorkspace(f.controller, loadOnMount = false, answerExplain = channel.gate()) { null } }
            ready(f.controller) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("answer-explain-consent").assertDoesNotExist()
            onNodeWithTag("choice-${f.practice.options.indexOf(f.practice.answer)}").performScrollTo().performClick()
            ready(f.controller) { it.answer.revealed }
            onNodeWithTag("answer-explain-skip").performScrollTo().performClick()
            onNodeWithTag("answer-explain-skipped").assertExists()
            onNodeWithTag("book-lesson-explanation").assertDoesNotExist()
            assertTrue(channel.asked.isEmpty())
            onNodeWithTag("book-summary-card").assertExists()
            onNodeWithTag("answer-explain-resume").performScrollTo().performClick()
            onNodeWithTag("answer-explain-consent").assertExists()
            assertTrue(channel.asked.isEmpty())
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
