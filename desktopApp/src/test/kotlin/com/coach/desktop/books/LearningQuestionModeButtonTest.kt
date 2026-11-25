package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 学习页「题型」必须就地改这本书的题型。main 上这个按钮只把 prompt 设成
 * BookPrompt.QUESTIONS，界面已被 2.0 拿掉，点了没有 question-mode-*。
 */
@OptIn(ExperimentalTestApi::class)
class LearningQuestionModeButtonTest {
    @Test fun questionTypeButtonSwitchesChoiceShortAndOffWithoutAPrompt() {
        val root = Files.createTempDirectory("coach-question-type-button-")
        val library = TextbookLibrary(root.resolve("library"))
        val controller = TextbookController(library)
        runBlocking {
            controller.importBook(learningPathTextbook(root, "Synthetic question types"))
            controller.learn(LearningPath.QUESTION_FIRST)
        }
        val opened = controller.state.value
        assertNull(opened.error)
        assertNull(opened.prompt)
        assertEquals(BookScreen.LEARNING, opened.screen)
        assertEquals(QuestionMode.CHOICE, opened.book!!.questionMode)
        val bookId = opened.book.id
        val path = opened.learningPath
        val lesson = opened.book.lessonIndex

        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 60_000) {
                val ui = controller.state.value
                !ui.busy && ui.indexing == null && ui.screen == BookScreen.LEARNING && ui.prompt == null
            }
            onNodeWithTag("question-settings").performScrollTo()
            onNodeWithText("题型：选择题").assertExists()
            assertTrue(onAllNodesWithTag("question-mode-SHORT_ANSWER").fetchSemanticsNodes().isEmpty())

            onNodeWithTag("question-settings").performClick()
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithTag("question-mode-SHORT_ANSWER").fetchSemanticsNodes().isNotEmpty()
            }
            assertNull(controller.state.value.prompt)
            onNodeWithTag("question-settings-menu").assertExists()
            onNodeWithTag("question-mode-CHOICE").assertExists()
            onNodeWithTag("question-mode-NONE").assertExists()

            onNodeWithTag("question-mode-SHORT_ANSWER").performClick()
            waitUntil(timeoutMillis = 20_000) {
                val ui = controller.state.value
                !ui.busy && ui.book?.questionMode == QuestionMode.SHORT_ANSWER && ui.prompt == null
            }
            onNodeWithText("题型：简答题").assertExists()
            onNodeWithTag("short-answer").performScrollTo().assertExists()
            assertEquals(path, controller.state.value.learningPath)
            assertEquals(lesson, controller.state.value.book!!.lessonIndex)

            onNodeWithTag("question-settings").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithTag("question-mode-NONE").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("question-mode-NONE").performClick()
            waitUntil(timeoutMillis = 20_000) {
                val ui = controller.state.value
                !ui.busy && ui.indexing == null && ui.book?.questionMode == QuestionMode.NONE &&
                    ui.prompt == null && ui.screen == BookScreen.LEARNING
            }
            onNodeWithText("题型：关闭").assertExists()
            onNodeWithTag("book-questions-off").assertExists()
            assertEquals(QuestionMode.NONE, library.book(bookId).questionMode)
            assertEquals(path.name, library.learningPath(bookId))
        }
    }
}
