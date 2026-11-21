package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

/** The shelf button must say what it does on an old book that never chose a walk. */
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
            onNodeWithTag("home-learn").assertTextEquals("开始学").performClick()
            ready(controller) { it.prompt == BookPrompt.START }
            assertNull(library.learningPath(book.id), "Opening the chooser must not count as starting")
            onNodeWithTag("learning-path-${path.name}").performClick()
            ready(controller) { it.learningPath == path }
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == destination && it.prompt == null }
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
            val position = if (path == LearningPath.READ) "第 2 页" else "第 2 节"
            onNodeWithTag("home-learn").assertTextEquals("继续${path.label} · $position").performClick()
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
            onNodeWithTag("home-learn").assertTextEquals("开始学")
            onNodeWithTag("home-learn").performClick()
            ready(controller) { it.prompt == BookPrompt.START }
            onNodeWithText("开始学这本教材").assertExists()
            assertNull(library.learningPath(id), "Opening the start action must not record a walk by itself")
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

            fun reopen(id: String) {
                onNodeWithTag("book-$id").performScrollTo().performClick()
                ready(controller) { it.book?.id == id && it.screen == BookScreen.HOME }
            }

            reopen(read)
            onNodeWithTag("home-learn").assertTextEquals("继续看书 · 第 2 页")
            onNodeWithTag("home-learn").performClick()
            ready(controller) { it.screen == BookScreen.READER }
            assertEquals(2, controller.state.value.page)
            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }

            reopen(guided)
            onNodeWithTag("home-learn").assertTextEquals("继续边看边学 · 第 1 节")
            onNodeWithTag("home-learn").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            assertEquals(LearningPath.READ_AND_LEARN, controller.state.value.learningPath)
            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }

            reopen(question)
            onNodeWithTag("home-learn").assertTextEquals("继续做题学 · 第 1 节")
            onNodeWithTag("home-learn").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            assertEquals(LearningPath.QUESTION_FIRST, controller.state.value.learningPath)
        }
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }
}
