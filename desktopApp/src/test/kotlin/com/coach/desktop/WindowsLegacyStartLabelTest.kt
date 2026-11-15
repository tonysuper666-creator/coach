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
        seed.chooseLearningPath(LearningPath.READ_AND_LEARN); seed.enableLearning()

        seed.importBook(learningPathTextbook(root, "Synthetic question shelf", chemistry = true))
        val question = seed.state.value.book!!.id
        seed.chooseLearningPath(LearningPath.QUESTION_FIRST); seed.enableLearning()

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
