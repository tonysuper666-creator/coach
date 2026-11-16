package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

/** The real guessed control, original host navigation and SQLite; no replacement question/provider. */
@OptIn(ExperimentalTestApi::class)
class UnifiedLocalReviewUiTest {
    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    @Test fun guessedCorrectUsesTheExistingReviewPageAndComesBackWhenDue() {
        val root = Files.createTempDirectory("unified-review-ui-")
        val clock = ReviewTestClock()
        val library = TextbookLibrary(root.resolve("library"), clock)
        val c = TextbookController(library)
        runBlocking {
            c.importBook(syntheticTextbook(root.resolve("synthetic")))
            c.chooseLearningPath(LearningPath.QUESTION_FIRST); c.enableLearning()
        }
        val book = c.state.value.book!!
        val lesson = book.lessons.first()
        val question = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
        val choice = "choice-${question.options.indexOf(question.answer)}"
        runSkikoComposeUiTest(size = Size(1440f, 1000f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { null } }
            ready(c) { it.book != null }
            runBlocking { c.learn() }
            ready(c) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("practice-guessed").performScrollTo().performClick()
            ready(c) { it.answer.guessed && !it.answer.revealed }
            onNodeWithTag(choice).performScrollTo().performClick()
            ready(c) { it.answer.guessed && it.answer.revealed }
            onNodeWithTag("local-review-saved").performScrollTo().assertIsDisplayed()
            assertFalse(library.reviews().single().completed)
            assertFalse(c.state.value.ladder.responses.single().independentCorrect,
                "A guessed correct answer cannot become independent learning evidence")
            assertTrue(library.answer(book.id, lesson.id, QuestionMode.CHOICE).guessed)
            onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText("今天")).performClick()
            onNodeWithTag("host-today-review").assertTextContains("复习 1 题").performClick()
            ready(c) { it.screen == BookScreen.REVIEWS }
            onNodeWithTag("local-review-reasons-0", useUnmergedTree = true).assertTextContains("猜的")
            onNodeWithTag("local-review-item-0").performScrollTo().performClick()
            ready(c) { it.screen == BookScreen.REVIEWING }
            onNodeWithText(question.reference).assertDoesNotExist()
            onNodeWithTag("practice-guessed").performScrollTo().performClick()
            ready(c) { it.answer.guessed }
            onNodeWithTag(choice).performScrollTo().performClick()
            ready(c) { it.review?.history?.size == 2 && it.answer.revealed }
            assertFalse(c.state.value.review!!.completed)
            assertEquals(1, c.state.value.reviews.size)
            onNodeWithText("重新作答").performScrollTo().performClick()
            ready(c) { it.answer == PracticeAnswer() }
            onNodeWithTag(choice).performScrollTo().performClick()
            ready(c) { it.review?.completed == true }
            onNodeWithTag("local-review-completed").performScrollTo().assertIsDisplayed()
            onNodeWithTag("local-review-next-due").performScrollTo().assertIsDisplayed()
            assertEquals(0, c.state.value.reviews.size)
            assertEquals(3, c.state.value.review!!.history.size)
        }
        clock.day++
        val reopened = TextbookController(TextbookLibrary(library.directory, clock))
        runSkikoComposeUiTest(size = Size(1440f, 1000f), density = Density(1f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            ready(reopened) { it.screen == BookScreen.REVIEWING }
            assertEquals(1, reopened.state.value.reviews.size)
            assertContains(reopened.state.value.reviews.single().sources, LocalReviewSource.DUE)
            assertEquals(PracticeAnswer(), reopened.state.value.answer)
            assertEquals(3, reopened.state.value.review!!.history.size)
            assertEquals(question.answer, reopened.state.value.review!!.originalAnswer)
            onNodeWithTag(choice).performScrollTo().assertIsEnabled()
            onNodeWithText(question.reference).assertDoesNotExist()
        }
        println("UNIFIED_REVIEW_UI guessed-correct -> today=1 -> same review -> guessed remains -> independent correct=0 -> next day=1; original/history retained")
    }
}
