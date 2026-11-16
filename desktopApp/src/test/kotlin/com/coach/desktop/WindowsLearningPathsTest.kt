package com.coach.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsLearningPathsTest {
    @Test fun threeWalksThroughTheActualHostRestorePerBookWithoutLosingAnswers() {
        val root = Files.createTempDirectory("coach-three-walks-host-")
        val aFile = learningPathTextbook(root, "Synthetic astronomy")
        val bFile = learningPathTextbook(root, "Synthetic chemistry", chemistry = true)
        val directory = root.resolve("library")
        val library = TextbookLibrary(directory)
        var nextFile = aFile
        var controller by mutableStateOf(TextbookController(library))
        // 讲解通道给一个确定不联网的实现：这台机器的配对与 Mac 都不参与这条测试。
        val gate = AnswerExplain(ExplainConsent(directory), load = {}, ready = { true }, paired = { false },
            explain = { _, _ -> ExplainResult.Refused("测试里不配对") })
        // 选段问答有独立的配对通道：隔离它的默认凭据目录，绝不借用机器上的配对。
        assertTrue(System.getenv("COACH_CLIENT_DIR").isNullOrBlank())
        assertTrue(System.getenv("COACH_TOKEN_FILE").isNullOrBlank())
        val previousHome = System.getProperty("user.home")
        System.setProperty("user.home", root.resolve("unpaired-home").toString())
        try {
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { key(controller) { WindowsTextbookHost(controller, answerExplain = gate) { nextFile } } }
            ready(controller) { it.catalog.isEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.prompt == BookPrompt.IMPORTED }
            onNodeWithTag("learning-path-READ").performClick()
            ready(controller) { it.learningPath == LearningPath.READ }
            onNodeWithTag("question-mode-CHOICE").assertDoesNotExist()
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.prompt == null }
            val a = controller.state.value.book!!
            onNodeWithTag("choice-0").assertDoesNotExist()
            onNodeWithTag("book-lesson-explanation").assertDoesNotExist()
            onNodeWithTag("ask-book-passage").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("ask-paragraph-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-passage-question").performTextInput("Why does this synthetic body stay in orbit?")
            onNodeWithTag("book-passage-send").performClick()
            onNodeWithTag("ask-consent-allow").assertExists()
            onNodeWithTag("book-passage-unavailable").assertDoesNotExist()
            onNodeWithTag("ask-consent-allow").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-selected-passage").assertTextContains(a.lessons[0].text)
            onNodeWithText("回到阅读").performClick()
            onNodeWithText("下一页").performClick()
            ready(controller) { it.page == 2 }
            capture("read-route")
            assertEquals(PracticeAnswer(), library.answer(a.id, a.lessons[0].id, QuestionMode.CHOICE))
            runOnIdle { controller = TextbookController(TextbookLibrary(directory)) }
            ready(controller) { it.book?.id == a.id && it.screen == BookScreen.HOME }
            onNodeWithTag("home-learn").performClick()
            ready(controller) { it.screen == BookScreen.READER }
            assertEquals(2, controller.state.value.page)
            assertEquals(LearningPath.READ, controller.state.value.learningPath)
            onNodeWithTag("question-settings").assertDoesNotExist()

            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            onNodeWithTag("home-guided").performClick()
            ready(controller) { it.prompt == BookPrompt.START }
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.learningPath == LearningPath.READ_AND_LEARN }
            onNodeWithTag("choice-0").assertDoesNotExist()
            onNodeWithTag("book-lesson-explanation").assertExists()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(a.lessons[0].text).assertDoesNotExist()
            capture("guided-before-questions")
            onNodeWithTag("guided-start-questions").performClick()
            ready(controller) { it.questionsOpen }
            onNodeWithTag("choice-0").assertExists()
            onNodeWithTag("question-settings").performScrollTo().performClick()
            ready(controller) { it.prompt == BookPrompt.QUESTIONS }
            onNodeWithTag("question-mode-SHORT_ANSWER").performClick()
            ready(controller) { it.book?.questionMode == QuestionMode.SHORT_ANSWER }
            onNodeWithTag("close-question-settings").performClick()
            ready(controller) { it.prompt == null }
            onNodeWithTag("short-answer").performScrollTo().performTextInput("My retained guided draft")
            ready(controller) { it.answer.text == "My retained guided draft" }
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.READER && it.source != null }
            onNodeWithText("返回学习").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.questionsOpen }
            onNodeWithTag("short-answer").assertTextContains("My retained guided draft")
            onNodeWithTag("open-practice-source").assertIsFocused()
            assertEquals(2, library.book(a.id).readingPage)

            // The ordinary read buttons are a detour, not a new walk selection.
            fun nav(label: String) = onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))
            for ((destination, prefix) in listOf("今天" to "host-today", "课程" to "host-course")) {
                nav(destination).performClick()
                onNodeWithTag("$prefix-read").performClick()
                ready(controller) { it.screen == BookScreen.READER && it.source == null && it.page == 2 }
                assertEquals("READ_AND_LEARN", library.learningPath(a.id))
                nav(destination).performClick()
                onNodeWithTag("$prefix-learn").performClick()
                ready(controller) { it.screen == BookScreen.LEARNING && it.answer.text == "My retained guided draft" }
                assertEquals(LearningPath.READ_AND_LEARN, controller.state.value.learningPath)
                val resumed = controller.state.value.book!!
                assertEquals(a.id, resumed.id)
                assertEquals(0, resumed.lessonIndex)
                assertEquals(a.lessons[0].id, resumed.lessons[resumed.lessonIndex].id)
                onNodeWithTag("short-answer").performScrollTo().assertTextContains("My retained guided draft")
                capture("$prefix-learning-after-read")
            }
            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }

            nextFile = bFile
            onNodeWithTag("upload-textbook").performScrollTo().performClick()
            ready(controller) { it.prompt == BookPrompt.IMPORTED && it.book?.id != a.id }
            onNodeWithTag("question-mode-NONE").performClick()
            ready(controller) { it.book?.questionMode == QuestionMode.NONE }
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            assertEquals(LearningPath.QUESTION_FIRST, controller.state.value.learningPath)
            onNodeWithTag("book-questions-off").assertExists()
            onNodeWithTag("choice-0").assertDoesNotExist()
            val b = controller.state.value.book!!
            onNodeWithText(b.lessons[0].text).assertDoesNotExist()
            onNodeWithTag("question-settings").performScrollTo().performClick()
            ready(controller) { it.prompt == BookPrompt.QUESTIONS }
            onNodeWithTag("question-mode-CHOICE").performClick()
            ready(controller) { it.book?.questionMode == QuestionMode.CHOICE }
            onNodeWithTag("close-question-settings").performClick()
            ready(controller) { it.prompt == null }
            assertEquals(LearningPath.QUESTION_FIRST, controller.state.value.learningPath)
            onNodeWithTag("book-lesson-explanation").assertDoesNotExist()
            val q0 = StudyCoachCourse.practice(b, b.lessons[0], QuestionMode.CHOICE)!!
            onNodeWithTag("choice-${q0.options.indexOf(q0.answer)}").performScrollTo().performClick()
            ready(controller) { it.answer.revealed }
            // 答完就讲：没配对时指路而不发原文，题下给小结卡接着走。
            onNodeWithTag("answer-explain-unpaired").assertExists()
            onNodeWithTag("answer-explain-consent").assertDoesNotExist()
            onNodeWithTag("book-lesson-explanation").assertDoesNotExist()
            onNodeWithTag("book-summary-card").assertExists()
            onNodeWithTag("book-summary-detail").assertTextContains("和原文一致", substring = true)
            onNodeWithText("原文：${q0.reference}").assertDoesNotExist()
            onNodeWithText(b.lessons[0].text).assertDoesNotExist()
            capture("question-route-after-answer")
            onNodeWithTag("next-lesson").performScrollTo().performClick()
            ready(controller) { it.book?.lessonIndex == 1 }
            val currentB = controller.state.value.book!!
            val q1 = StudyCoachCourse.practice(currentB, currentB.lessons[1], QuestionMode.CHOICE)!!
            onNodeWithTag("choice-${q1.options.indexOf(q1.answer)}").performScrollTo().performClick()
            ready(controller) { it.answer.revealed }
            assertEquals(PracticeAnswer(q1.answer, true), library.answer(b.id, currentB.lessons[1].id, QuestionMode.CHOICE))

            runOnIdle { controller = TextbookController(TextbookLibrary(directory)) }
            ready(controller) { it.book?.id == b.id && it.screen == BookScreen.HOME }
            onNodeWithTag("home-learn").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            assertEquals(LearningPath.QUESTION_FIRST, controller.state.value.learningPath)
            assertEquals(1, controller.state.value.book!!.lessonIndex)
            assertEquals(PracticeAnswer(q1.answer, true), controller.state.value.answer)
            onNodeWithText(currentB.lessons[1].text).assertDoesNotExist()
            capture("restored-question-route")
            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            onNodeWithTag("book-${a.id}").performScrollTo().performClick()
            ready(controller) { it.book?.id == a.id }
            onNodeWithTag("home-learn").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            assertEquals(LearningPath.READ_AND_LEARN, controller.state.value.learningPath)
            assertEquals(QuestionMode.SHORT_ANSWER, controller.state.value.book!!.questionMode)
            onNodeWithTag("short-answer").assertTextContains("My retained guided draft")
            assertEquals(2, controller.state.value.book!!.readingPage)
            capture("restored-guided-route")
            println("LEARNING_PATH_TRACE two-books: READ/page2/reopen -> GUIDED/explanation-unavailable/explicit-questions/retained-draft -> QUESTION_FIRST/NONE/CHOICE/lesson2-answer -> new-host:each-book-own-walk/page/lesson/answer; raw-source-only-after-click")
        }
        } finally {
            if (previousHome == null) System.clearProperty("user.home") else System.setProperty("user.home", previousHome)
        }
    }

    @Test fun rawSourceEchoIsNotDisplayedAsAnExplanation() = runBlocking {
        val root = Files.createTempDirectory("coach-source-not-explanation-")
        val c = TextbookController(TextbookLibrary(root.resolve("library")))
        c.importBook(learningPathTextbook(root, "Synthetic echo boundary"))
        c.chooseLearningPath(LearningPath.READ_AND_LEARN); c.enableLearning()
        val book = c.state.value.book!!
        val body = book.lessons[0].text
        val echo = object : BookLearningSupport {
            override suspend fun explain(request: BookExplanationRequest) = BookLearningReply.Text(body)
            override suspend fun ask(request: BookPassageQuestion) = BookLearningReply.Unavailable("No answer provider")
        }
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { TextbookWorkspace(c, support = echo) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-explanation-text").assertDoesNotExist()
            onNodeWithText(body).assertDoesNotExist()
            onNodeWithTag("choice-0").assertDoesNotExist()
        }
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.capture(name: String) {
        val dir = Path.of("build/screens/learning-paths").toFile().also { it.mkdirs() }
        val file = dir.resolve("$name.png")
        assertTrue(ImageIO.write(onNodeWithTag("windows-host").captureToImage().toAwtImage(), "png", file))
        val image = assertNotNull(ImageIO.read(file))
        assertEquals(1100, image.width); assertEquals(800, image.height)
    }
}
