package com.coach.desktop.books

import androidx.compose.ui.test.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class TextbookGuessUiTest {
    @Test fun guessingInLessonAndReviewKeepsTheOriginalReviewUntilAnUnmarkedCorrectAnswer() {
        val root = Files.createTempDirectory("coach-guess-ui-")
        val library = TextbookLibrary(root.resolve("library"))
        val otherPath = root.resolve("other.md")
        Files.writeString(otherPath, "# Cells\n\nMitosis is the process by which a cell divides into two cells.\n")
        val other = library.importBook(otherPath)
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

            // The mark is made before picking an option, through the real lesson screen.
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            ready(controller) { it.answer.guessed }
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(controller) { it.answer == PracticeAnswer(practice.answer, revealed = true, guessed = true) }
            val reopened = TextbookLibrary(root.resolve("library"))
            val queued = reopened.reviews().single()
            assertEquals(book.id, queued.book)
            assertEquals(lesson.id, queued.lesson)
            assertEquals(QuestionMode.CHOICE, queued.mode)
            assertTrue(queued.originalGuessed)
            assertFalse(queued.completed)
            assertEquals(PracticeAnswer(), reopened.answer(book.id, book.lessons[1].id, QuestionMode.CHOICE))
            assertEquals(PracticeAnswer(), reopened.answer(book.id, lesson.id, QuestionMode.SHORT_ANSWER))
            assertEquals(PracticeAnswer(), reopened.answer(other.id, other.lessons.first().id, QuestionMode.CHOICE))

            onNodeWithTag("open-local-reviews").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.REVIEWS }
            onNodeWithTag("local-review-item-0").performClick()
            ready(controller) { it.screen == BookScreen.REVIEWING }
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            ready(controller) { it.review?.answer?.guessed == true }
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(controller) { it.review?.answer == PracticeAnswer(practice.answer, revealed = true, guessed = true) }
            assertFalse(TextbookLibrary(root.resolve("library")).reviews().single().completed)
            onAllNodesWithTag("local-review-completed").assertCountEquals(0)

            onNodeWithText("重新作答").performScrollTo().performClick()
            ready(controller) { it.review?.answer == PracticeAnswer() }
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(controller) { it.review?.completed == true }
            assertTrue(TextbookLibrary(root.resolve("library")).reviews().isEmpty())
            onNodeWithTag("local-review-completed").assertExists()
        }
    }

    private fun ComposeUiTest.ready(controller: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && condition(controller.state.value) }
        assertNull(controller.state.value.error)
        waitForIdle()
    }
}
