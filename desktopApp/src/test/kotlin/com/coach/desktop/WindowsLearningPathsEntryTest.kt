package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** This class also compiles on main before the three-route implementation, for a behavioral red. */
@OptIn(ExperimentalTestApi::class)
class WindowsLearningPathsEntryTest {
    @Test fun sameBookOffersThreeWalksSeparateFromQuestionTypes() = entry { controller, _ ->
        onNodeWithText("返回书架").performClick()
        ready(controller) { it.screen == BookScreen.HOME }
        onNodeWithTag("home-read").assertIsDisplayed()
        onNodeWithTag("home-guided").assertIsDisplayed()
        onNodeWithTag("home-question").assertIsDisplayed()
        onNodeWithTag("home-learn").assertIsDisplayed()
        capture("three-entries")
    }

    @Test fun answeringNeverOpensRawSourceUntilRequestedAndReturnKeepsTheAnswer() = entry { controller, library ->
        val book = controller.state.value.book!!
        val lesson = book.lessons[book.lessonIndex]
        val practice = StudyCoachCourse.practice(book, lesson, book.questionMode)!!
        onNodeWithText(lesson.text).assertDoesNotExist()
        onNodeWithText("原文：${practice.reference}").assertDoesNotExist()
        onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
        ready(controller) { it.answer.revealed }
        onNodeWithText("与原文一致。").assertExists()
        onNodeWithText("原文：${practice.reference}").assertDoesNotExist()
        onNodeWithText(lesson.text).assertDoesNotExist()
        onNodeWithTag("textbook-reader").assertDoesNotExist()
        capture("questions-after-answer")
        onNodeWithTag("open-practice-source").performScrollTo().performClick()
        ready(controller) { it.screen == BookScreen.READER && it.source != null }
        onNodeWithTag("reader-text").assertExists()
        onNodeWithText(practice.reference, substring = true).assertExists()
        capture("source-after-click")
        onNodeWithText("返回学习").performClick()
        ready(controller) { it.screen == BookScreen.LEARNING }
        onNodeWithText("原文：${practice.reference}").assertDoesNotExist()
        onNodeWithText(lesson.text).assertDoesNotExist()
        onNodeWithTag("open-practice-source").assertIsFocused()
        assertEquals(PracticeAnswer(practice.answer, true), library.answer(book.id, lesson.id, QuestionMode.CHOICE))
    }

    private fun entry(block: ComposeUiTest.(TextbookController, TextbookLibrary) -> Unit) {
        val root = Files.createTempDirectory("coach-three-walks-entry-")
        val source = root.resolve("synthetic-astronomy.md")
        Files.writeString(source, """
            # Synthetic astronomy

            Orbit is the path of an object around another body. Gravity keeps a body near its star.

            Rotation is the turning of an object around its own axis. A day follows one rotation.
        """.trimIndent())
        val library = TextbookLibrary(root.resolve("library"))
        val controller = TextbookController(library)
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { WindowsTextbookHost(controller) { source } }
            ready(controller) { it.catalog.isEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.prompt == BookPrompt.IMPORTED }
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            block(controller, library)
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
        val decoded = assertNotNull(ImageIO.read(file))
        assertEquals(1100, decoded.width); assertEquals(800, decoded.height)
    }
}
