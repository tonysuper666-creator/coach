package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.QuestionMode
import com.coach.desktop.books.StudyCoachCourse
import com.coach.desktop.books.TextbookLibrary
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsLearningEntryTest {
    /** Uses exactly runCoach's Windows content, real import/SQLite and the existing SourcePractice. */
    @Test fun enablingLearningInTheWindowsEntryReachesAnAnswerableQuestion() {
        println("WINDOWS_LEARNING_HOST=${System.getProperty("os.name")}")
        val root = Files.createTempDirectory("coach-win-learning-entry-")
        val source = root.resolve("synthetic-astronomy.md")
        Files.writeString(source, """
            # Synthetic astronomy

            Orbit is the path of an object around another body. An orbit can be circular or elliptical.

            Gravity is the attraction between bodies with mass. Gravity keeps an orbiting body near its star.
        """.trimIndent())
        val directory = root.resolve("library")
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { WindowsTextbooksContent(directory) { source } }
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasTestTag("upload-textbook") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasTestTag("enable-learning") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("enable-learning").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("textbook-learning").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            // Old runCoach's Windows branch reached only a reading lesson with mode NONE.
            onNodeWithTag("choice-0").assertIsDisplayed()
            val library = TextbookLibrary(directory)
            val book = library.book(library.catalog().single().id)
            assertTrue(book.learning)
            assertEquals(QuestionMode.CHOICE, book.questionMode)
            val lesson = book.lessons[book.lessonIndex]
            val question = assertNotNull(StudyCoachCourse.practice(book, lesson, book.questionMode))
            assertEquals("Orbit", question.answer)
            assertTrue(question.options.size >= 2)
            assertEquals(1, question.options.count { it == question.answer })
            onNodeWithText(question.prompt).assertIsDisplayed()
            capture("question")
            onNodeWithTag("choice-${question.options.indexOf(question.answer)}").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("与原文一致。").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("原文：${question.reference}").performScrollTo().assertIsDisplayed()
            val saved = library.answer(book.id, lesson.id, QuestionMode.CHOICE)
            assertEquals(question.answer, saved.text)
            assertTrue(saved.revealed)
            capture("feedback")
            println("WINDOWS_LEARNING_TRACE upload:synthetic-md -> enable-learning -> source-choice:answerable -> answer:Orbit -> cited-feedback -> SQLite:same-book/same-lesson")
        }
    }

    private fun ComposeUiTest.capture(name: String) {
        val directory = Path.of("build/screens/windows-learning-entry").toFile().also { it.mkdirs() }
        val output = directory.resolve("$name.png")
        assertTrue(ImageIO.write(onNodeWithTag("windows-textbooks").captureToImage().toAwtImage(), "png", output))
        val image = assertNotNull(ImageIO.read(output))
        assertEquals(1100, image.width)
        assertEquals(800, image.height)
        println("WINDOWS_LEARNING_CAPTURE=${output.name} width=${image.width} height=${image.height}")
    }
}
