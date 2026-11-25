package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

/**
 * The shelf button must say what it does on an old book that never chose a walk.
 *
 * 2.0 书架首页（TON-1790）把按学法变化的「开始学 / 继续看书 · 第 N 页 / 继续做题学 · 第 N 节」按钮（home-learn）
 * 收成焦点卡上唯一的「继续」（home-read，总是回到读到的那一页）加学法选择（home-mode-trigger，TON-1805）；
 * TON-1784（#570）去掉了「开始学这本教材」提示（BookPrompt.START / enable-learning），进学习只剩控制器 learn()。
 * 所以这里核对的是现在的说法：首页只说「继续」、不说「开始学」，点它不会替书记下学法；
 * 选过的学法和位置照旧按书存住，重开后 learn() 回到同一条路、同一节。
 */
@OptIn(ExperimentalTestApi::class)
class WindowsLegacyStartLabelTest {
    @Test fun sameBookStartsThenContinuesReading() = startThenContinue(LearningPath.READ)

    @Test fun sameBookStartsThenContinuesGuidedLearning() = startThenContinue(LearningPath.READ_AND_LEARN)

    @Test fun sameBookStartsThenContinuesQuestionLearning() = startThenContinue(LearningPath.QUESTION_FIRST)

    private fun startThenContinue(path: LearningPath) = runBlocking {
        val root = Files.createTempDirectory("coach-same-book-start-continue-")
        val directory = root.resolve("library")
        val library = TextbookLibrary(directory)
        val book = library.importBook(learningPathTextbook(root, "Synthetic same book"))
        val controller = TextbookController(TextbookLibrary(directory))
        val destination = if (path == LearningPath.READ) BookScreen.READER else BookScreen.LEARNING

        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { WindowsTextbookHost(controller) { null } }
            ready(controller) { it.book?.id == book.id && it.screen == BookScreen.HOME }
            assertNull(library.learningPath(book.id))
            onNodeWithTag("home-learn").assertDoesNotExist()
            onNodeWithText("开始学").assertDoesNotExist()
            onNodeWithTag("home-read").assertTextEquals("继续").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.prompt == null }
            assertNull(library.learningPath(book.id), "Continuing to read must not count as starting a walk")
            runBlocking { controller.learn(path) }
            ready(controller) { it.screen == destination && it.prompt == null && it.learningPath == path }
        }
        // Retain the same book and database; only its chosen walk and position have changed.
        if (path == LearningPath.READ) controller.page(2) else controller.lesson(1)
        assertNull(controller.state.value.error)
        controller.back()
        assertEquals(path.name, library.learningPath(book.id))

        val reopened = TextbookController(TextbookLibrary(directory))
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            ready(reopened) { it.book?.id == book.id && it.screen == BookScreen.HOME }
            onNodeWithTag("home-learn").assertDoesNotExist()
            onNodeWithTag("home-read").assertTextEquals("继续")
            onNodeWithTag("home-mode-trigger").assertExists()
            // Continue the saved walk: learn() with no path restores the walk this book recorded.
            runBlocking { reopened.learn() }
            ready(reopened) { it.screen == destination }
            assertNull(reopened.state.value.prompt, "Continue must not offer to start the same book again")
            assertEquals(book.id, reopened.state.value.book!!.id)
            assertEquals(path, reopened.state.value.learningPath)
            if (path == LearningPath.READ) assertEquals(2, reopened.state.value.page)
            else assertEquals(1, reopened.state.value.book!!.lessonIndex)
        }
    }

    @Test fun legacyBookWithoutAChosenWalkOffersStartLearningAndStillPrompts() = runBlocking {
        val root = Files.createTempDirectory("coach-legacy-start-label-")
        val directory = root.resolve("library")
        val library = TextbookLibrary(directory)
        val seed = TextbookController(library)
        seed.importBook(learningPathTextbook(root, "Synthetic legacy shelf"))
        val id = seed.state.value.book!!.id
        // The reader detour this issue describes: read once, decline the learning prompt, keep no walk record.
        seed.dismissPrompt(); seed.read(); seed.dismissPrompt(); seed.page(2); seed.back()
        assertNull(library.learningPath(id), "Ordinary reading must not record a walk")
        assertFalse(library.book(id).learning)

        val controller = TextbookController(TextbookLibrary(directory))
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { WindowsTextbookHost(controller) { null } }
            ready(controller) { it.book?.id == id && it.screen == BookScreen.HOME }
            onNodeWithTag("home-learn").assertDoesNotExist()
            onNodeWithText("开始学").assertDoesNotExist()
            onNodeWithTag("home-read").assertTextEquals("继续").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.prompt == null }
            onNodeWithText("开始学这本教材").assertDoesNotExist()
            assertEquals(2, controller.state.value.page, "Continue resumes the page the reading detour stopped on")
            assertNull(library.learningPath(id), "Opening the start action must not record a walk by itself")
            assertFalse(library.book(id).learning)
        }
    }

    @Test fun booksWithAChosenWalkKeepTheirContinueLabelAndLanding() = runBlocking {
        val root = Files.createTempDirectory("coach-chosen-walk-label-")
        val directory = root.resolve("library")
        val library = TextbookLibrary(directory)
        val seed = TextbookController(library)

        seed.importBook(learningPathTextbook(root, "Synthetic read shelf"))
        val read = seed.state.value.book!!.id
        seed.chooseLearningPath(LearningPath.READ); seed.enableLearning(); seed.page(2); seed.back()

        seed.importBook(learningPathTextbook(root, "Synthetic guided shelf", chemistry = true))
        val guided = seed.state.value.book!!.id
        seed.chooseLearningPath(LearningPath.READ_AND_LEARN); seed.enableLearning(); seed.back()

        seed.importBook(learningPathTextbook(root, "Synthetic question shelf", chemistry = true))
        val question = seed.state.value.book!!.id
        seed.chooseLearningPath(LearningPath.QUESTION_FIRST); seed.enableLearning(); seed.back()

        assertEquals("READ", library.learningPath(read))
        assertEquals("READ_AND_LEARN", library.learningPath(guided))
        assertEquals("QUESTION_FIRST", library.learningPath(question))

        val controller = TextbookController(TextbookLibrary(directory))
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { WindowsTextbookHost(controller) { null } }
            ready(controller) { it.book != null && it.screen == BookScreen.HOME }

            // TON-1790：书架上点一本书就打开它（openBook，进阅读器）；回书架后焦点卡换成这本。
            fun reopen(id: String) {
                onNodeWithTag("book-$id").performScrollTo().performClick()
                ready(controller) { it.book?.id == id && it.screen == BookScreen.READER }
                onNodeWithTag("immersive-back").assertTextContains("书架", substring = true).performClick()
                ready(controller) { it.book?.id == id && it.screen == BookScreen.HOME }
            }

            reopen(read)
            onNodeWithTag("home-learn").assertDoesNotExist()
            onNodeWithTag("home-read").assertTextEquals("继续").performClick()
            ready(controller) { it.screen == BookScreen.READER }
            assertEquals(2, controller.state.value.page)
            onNodeWithTag("immersive-back").assertTextContains("书架", substring = true).performClick()
            ready(controller) { it.screen == BookScreen.HOME }

            reopen(guided)
            onNodeWithTag("home-learn").assertDoesNotExist()
            onNodeWithTag("home-read").assertTextEquals("继续")
            runBlocking { controller.learn() }
            ready(controller) { it.screen == BookScreen.LEARNING }
            assertEquals(LearningPath.READ_AND_LEARN, controller.state.value.learningPath)
            assertEquals(0, controller.state.value.book!!.lessonIndex)
            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }

            reopen(question)
            onNodeWithTag("home-learn").assertDoesNotExist()
            onNodeWithTag("home-read").assertTextEquals("继续")
            runBlocking { controller.learn() }
            ready(controller) { it.screen == BookScreen.LEARNING }
            assertEquals(LearningPath.QUESTION_FIRST, controller.state.value.learningPath)
            assertEquals(0, controller.state.value.book!!.lessonIndex)
        }
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }
}
