package com.coach.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import com.coach.desktop.books.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsTextbooksTest {
    @Test fun developmentLibraryIsExplicitAndIndependentOfProductionPairing() {
        val home = Files.createTempDirectory("coach-books-home-")
        assertFailsWith<IllegalArgumentException> { windowsLibraryDirectory({ null }, { null }, home) }
        val isolated = home.resolve("isolated")
        assertEquals(isolated, windowsLibraryDirectory({ if (it == "COACH_LIBRARY_DIR") isolated.toString() else null }, { null }, home))
        assertEquals(home.resolve("Coach/books"), windowsLibraryDirectory({ null }, { if (it == "jpackage.app-path") "Coach.exe" else null }, home))
        assertFailsWith<IllegalArgumentException> {
            windowsLibraryDirectory({ if (it == "COACH_LIBRARY_DIR") home.resolve("Coach/books").toString() else null }, { null }, home)
        }
    }

    /** Offscreen Compose actions only: no native file dialog, desktop input or existing user books. */
    @Test fun uploadReadDeclineLearningThenLearnAndOpenTheCorrectOriginalPage() {
        val root = Files.createTempDirectory("coach-windows-book-ui-")
        val source = syntheticTextbook(root.resolve("friend"))
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { source } }
            ready(controller) { it.catalog.isEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.prompt == BookPrompt.IMPORTED }
            onNodeWithTag("read-only").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.prompt == BookPrompt.AFTER_READING }
            onNodeWithText("要开启 learning 吗？").assertExists()
            onNodeWithTag("read-only").performClick()
            ready(controller) { it.prompt == null && it.book?.promptSeen == true }
            assertFalse(controller.state.value.book!!.learning)
            onNodeWithTag("next-page").performClick()
            ready(controller) { it.page == 2 }
            capture("reader")
            onNodeWithText("返回书桌").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            capture("home")
            onNodeWithTag("home-learn").performClick()
            ready(controller) { it.prompt == BookPrompt.START }
            onNodeWithTag("question-mode-CHOICE").performClick()
            ready(controller) { it.book?.questionMode == QuestionMode.CHOICE }
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.prompt == null }
            capture("learning")
            val learning = controller.state.value.book!!
            val practice = StudyCoachCourse.practice(learning, learning.lessons[0], QuestionMode.CHOICE)!!
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(controller) { it.answer.text == practice.answer && it.answer.revealed }
            onNodeWithText("与原文一致。").assertExists()
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.READER && it.source != null }
            assertEquals(1, controller.state.value.page)
            assertEquals(2, controller.state.value.book!!.readingPage)
            onNodeWithText("返回学习").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.source == null }
            assertEquals(0, controller.state.value.book!!.lessonIndex)
            onNodeWithTag("question-settings").performClick()
            ready(controller) { it.prompt == BookPrompt.QUESTIONS }
            onNodeWithTag("question-mode-SHORT_ANSWER").performClick()
            ready(controller) { it.book?.questionMode == QuestionMode.SHORT_ANSWER }
            onNodeWithTag("close-question-settings").performClick()
            ready(controller) { it.prompt == null }
            onNodeWithTag("short-answer").performScrollTo().performTextInput("Plants use light to make sugar.")
            ready(controller) { it.answer.text == "Plants use light to make sugar." }
            onNodeWithTag("reveal-reference").performScrollTo().performClick()
            ready(controller) { it.answer.revealed }
            onNodeWithText("这是对照原文的自检，不会自动判定掌握。").assertExists()
            onNodeWithTag("next-lesson").performScrollTo().performClick()
            ready(controller) { it.book?.lessonIndex == 1 }
        }
        val reopened = TextbookLibrary(root.resolve("library")).book(controller.state.value.book!!.id)
        assertEquals(1, reopened.lessonIndex)
        assertEquals(2, reopened.readingPage)
        assertEquals(QuestionMode.SHORT_ANSWER, reopened.questionMode)
    }

    @Test fun directlyLearningWithNoQuestionsNeedsNeitherCourseConfigurationNorPairing() {
        val root = Files.createTempDirectory("coach-direct-book-ui-")
        val source = syntheticTextbook(root.resolve("friend"))
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { source } }
            ready(controller) { it.catalog.isEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.prompt == BookPrompt.IMPORTED }
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            assertEquals(QuestionMode.NONE, controller.state.value.book!!.questionMode)
            onAllNodesWithTag("choice-0").assertCountEquals(0)
            onAllNodesWithTag("short-answer").assertCountEquals(0)
            onNodeWithTag("next-lesson").performScrollTo().performClick()
            ready(controller) { it.book?.lessonIndex == 1 }
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.READER }
            assertEquals(2, controller.state.value.page)
        }
    }

    private fun ComposeUiTest.ready(controller: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && condition(controller.state.value) }
        assertNull(controller.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.capture(name: String) {
        val output = File("build/screens/windows-textbooks").apply { mkdirs() }
        ImageIO.write(onNodeWithTag("windows-textbooks").captureToImage().toAwtImage(), "png", File(output, "$name.png"))
    }
}
