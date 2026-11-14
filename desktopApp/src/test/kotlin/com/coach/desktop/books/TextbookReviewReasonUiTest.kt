package com.coach.desktop.books

import androidx.compose.ui.test.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class TextbookReviewReasonUiTest {
    @Test fun guessedCorrectAnswerKeepsItsOriginalReasonAfterLessonUnmark() {
        val root = Files.createTempDirectory("coach-review-reason-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        library.save(book.copy(learning = true, questionMode = QuestionMode.CHOICE))
        val lesson = book.lessons.first()
        val practice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
        val controller = TextbookController(library)

        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            ready(controller) { it.book?.id == book.id }
            onNodeWithTag("home-learn").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            ready(controller) { it.answer.guessed }
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(controller) { it.answer.revealed && it.answer.guessed }
            assertTrue(TextbookLibrary(library.directory).reviews().single().originalGuessed)
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            ready(controller) { it.answer.revealed && !it.answer.guessed }
            val saved = TextbookLibrary(library.directory).reviews().single()
            assertTrue(saved.originalGuessed)
            assertEquals(practice.answer, saved.originalAnswer)
            assertFalse(saved.completed)

            onNodeWithTag("open-local-reviews").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.REVIEWS }
            onNodeWithText("原题加入复习时标为猜的").assertExists()
            onNodeWithText("答错、标为猜的题，以及自检时选择还需复习的题都在这里，保留原题和原文。").assertExists()
            onNodeWithText("课时里取消猜答标记，不会自动移出已加入的复习题。").assertExists()
            onNodeWithText("原题作答：${practice.answer}").assertExists()
            onAllNodesWithText("本轮复习标为猜的").assertCountEquals(0)
            onNodeWithTag("local-review-item-0").performClick()
            ready(controller) { it.screen == BookScreen.REVIEWING }
            onNodeWithText("原题作答：${practice.answer}").assertExists()
            onNodeWithText("原题加入复习时标为猜的").assertExists()
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            ready(controller) { it.review?.answer?.guessed == true }
            onNodeWithText("原题加入复习时标为猜的").assertExists()
            onNodeWithText("本轮复习标为猜的").assertExists()
        }
    }

    @Test fun reviewGuessIsDistinctFromTheUnmarkedOriginalAnswer() {
        val root = Files.createTempDirectory("coach-review-current-guess-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        library.save(book.copy(learning = true, questionMode = QuestionMode.CHOICE))
        val lesson = book.lessons.first()
        val practice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
        val wrong = practice.options.first { it != practice.answer }
        val controller = TextbookController(library)

        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            ready(controller) { it.book?.id == book.id }
            onNodeWithTag("home-learn").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("choice-${practice.options.indexOf(wrong)}").performScrollTo().performClick()
            ready(controller) { it.answer.revealed }
            onNodeWithTag("open-local-reviews").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.REVIEWS }
            onNodeWithTag("local-review-item-0").performClick()
            ready(controller) { it.screen == BookScreen.REVIEWING }
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            ready(controller) { it.review?.answer?.guessed == true }
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(controller) { it.review?.answer?.revealed == true }
            assertFalse(TextbookLibrary(library.directory).reviews().single().completed)
            onNodeWithText("原题作答：$wrong").assertExists()
            onAllNodesWithText("原题加入复习时标为猜的").assertCountEquals(0)
            onNodeWithText("本轮复习标为猜的").assertExists()
            onNodeWithText("返回复习列表").performClick()
            ready(controller) { it.screen == BookScreen.REVIEWS }
            onNodeWithText("原题作答：$wrong").assertExists()
            onAllNodesWithText("原题加入复习时标为猜的").assertCountEquals(0)
            onNodeWithText("本轮复习标为猜的").assertExists()
        }
    }

    @Test fun migratedOldLibraryDoesNotInventGuessMarks() {
        val root = Files.createTempDirectory("coach-review-legacy-ui-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        library.save(book.copy(learning = true, questionMode = QuestionMode.CHOICE))
        val lesson = book.lessons.first()
        val practice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
        val wrong = practice.options.first { it != practice.answer }
        library.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer(wrong, revealed = true))
        // Remove the new columns to reproduce a database written before guess tracking existed.
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.execute("ALTER TABLE answers DROP COLUMN guessed")
                sql.execute("ALTER TABLE book_reviews DROP COLUMN wrong_guessed")
                sql.execute("ALTER TABLE book_reviews DROP COLUMN answer_guessed")
            }
        }
        val reopened = TextbookLibrary(library.directory)
        assertFalse(reopened.reviews().single().originalGuessed)
        assertFalse(reopened.reviews().single().answer.guessed)
        val controller = TextbookController(reopened)

        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            ready(controller) { it.book?.id == book.id }
            onNodeWithTag("home-learn").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("open-local-reviews").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.REVIEWS }
            onNodeWithText("原题作答：$wrong").assertExists()
            onAllNodesWithText("原题加入复习时标为猜的").assertCountEquals(0)
            onAllNodesWithText("本轮复习标为猜的").assertCountEquals(0)
        }
    }

    private fun ComposeUiTest.ready(controller: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && condition(controller.state.value) }
        assertNull(controller.state.value.error)
        waitForIdle()
    }
}
