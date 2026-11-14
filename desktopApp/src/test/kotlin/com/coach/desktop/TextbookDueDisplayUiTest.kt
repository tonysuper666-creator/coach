package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class TextbookDueDisplayUiTest {
    private fun ComposeUiTest.ready(c: TextbookController, predicate: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && predicate(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.today(c: TextbookController) {
        ready(c) { it.book != null }
        onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText("今天")).performClick()
        onNodeWithTag("host-today-review").assertTextContains("复习 1 题").performClick()
        ready(c) { it.screen == BookScreen.REVIEWS }
    }

    private fun ComposeUiTest.capture(name: String) {
        val dir = Path.of("build/screens/due-display")
        Files.createDirectories(dir)
        assertTrue(ImageIO.write(onNodeWithTag("windows-host").captureToImage().toAwtImage(), "png", dir.resolve(name).toFile()))
    }

    @Test fun dueWrongGuessShareTodayAndSurviveNewHost() {
        val root = Files.createTempDirectory("due-host-")
        var day = 21000L
        val library = TextbookLibrary(root.resolve("library")) { day }
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        library.save(book.copy(learning = true, questionMode = QuestionMode.CHOICE))
        val lesson = book.lessons.first()
        val practice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
        val wrong = practice.options.first { it != practice.answer }
        library.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer(wrong, true))
        day++
        library.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer(practice.answer, true, guessed = true))
        assertEquals(1, library.reviews().size)
        val date = LocalDate.ofEpochDay(day).toString()
        val c = TextbookController(library)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { null } }
            today(c)
            onNodeWithText("到期日：$date").assertExists()
            onNodeWithText("上次作答：$wrong").assertExists()
            capture("01-list.png")
            onNodeWithTag("local-review-item-0").performClick()
            ready(c) { it.screen == BookScreen.REVIEWING }
            onNodeWithText("到期日：$date").assertExists()
            onNodeWithText(practice.prompt).assertExists()
            onNodeWithText("上次作答：$wrong").assertExists()
            capture("02-question.png")
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            ready(c) { it.answer.guessed }
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(c) { it.answer.revealed && it.answer.guessed }
            waitUntil(timeoutMillis = 20_000) { library.reviews().single().answer.revealed }
            assertFalse(library.reviews().single().completed)
        }
        val reopenedLibrary = TextbookLibrary(library.directory) { day }
        assertEquals(1, reopenedLibrary.reviews().size)
        assertEquals(wrong, reopenedLibrary.reviews().single().originalAnswer)
        assertEquals(practice, reopenedLibrary.reviews().single().practice)
        assertTrue(reopenedLibrary.reviews().single().answer.guessed)
        assertFalse(reopenedLibrary.reviews().single().completed)
        val nextDate = LocalDate.ofEpochDay(reopenedLibrary.reviewDueDay(reopenedLibrary.reviews().single())!!).toString()
        val reopened = TextbookController(reopenedLibrary)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            ready(reopened) { it.screen == BookScreen.REVIEWING }
            onNodeWithText("到期日：$nextDate").assertExists()
            onNodeWithText("上次作答：$wrong").assertExists()
            onNodeWithText(practice.prompt).assertExists()
            capture("03-reopened.png")
            today(reopened)
            onNodeWithText("到期日：$nextDate").assertExists()
            assertEquals(1, reopenedLibrary.reviews().size)
        }
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql -> sql.executeQuery("SELECT count(*) FROM book_reviews").use { rows ->
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1))
            } }
        }
    }

    @Test fun migratedReviewWithoutDueDaySaysSoInListAndQuestion() {
        val root = Files.createTempDirectory("due-legacy-host-")
        val library = TextbookLibrary(root.resolve("library")) { 21000L }
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lesson = book.lessons.first()
        val practice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
        val wrong = practice.options.first { it != practice.answer }
        library.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer(wrong, true))
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                listOf("due_day", "interval", "ease", "anchor").forEach { sql.execute("ALTER TABLE book_reviews DROP COLUMN $it") }
            }
        }
        val migrated = TextbookLibrary(library.directory) { 21000L }
        assertNull(migrated.reviewDueDay(migrated.reviews().single()))
        val c = TextbookController(migrated)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { null } }
            today(c)
            onNodeWithText("暂无到期日").assertExists()
            onNodeWithText("上次作答：$wrong").assertExists()
            capture("04-legacy-list.png")
            onNodeWithTag("local-review-item-0").performClick()
            ready(c) { it.screen == BookScreen.REVIEWING }
            onNodeWithText("暂无到期日").assertExists()
            onNodeWithText("上次作答：$wrong").assertExists()
            capture("05-legacy-question.png")
        }
    }
}
