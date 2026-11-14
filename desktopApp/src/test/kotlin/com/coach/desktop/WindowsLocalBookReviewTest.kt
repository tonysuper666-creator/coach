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

/** Real host callbacks and on-disk SQLite, using only a generated textbook. */
@OptIn(ExperimentalTestApi::class)
class WindowsLocalBookReviewTest {
    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.nav(label: String) =
        onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))

    private fun ComposeUiTest.capture(name: String) {
        val dir = Path.of("build/screens/local-book-review")
        Files.createDirectories(dir)
        assertTrue(ImageIO.write(onNodeWithTag("windows-host").captureToImage().toAwtImage(),
            "png", dir.resolve("$name.png").toFile()))
    }

    @Test fun wrongAnswerHasRealReviewDestinationAndReopensExactQuestion() {
        val root = Files.createTempDirectory("coach-local-book-review-")
        val source = syntheticTextbook(root.resolve("synthetic"))
        val library = TextbookLibrary(root.resolve("library"))
        val c = TextbookController(library)
        lateinit var book: Textbook
        lateinit var practice: Practice
        lateinit var wrong: String
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { source } }
            ready(c) { it.book == null }
            onNodeWithTag("upload-textbook").performClick()
            ready(c) { it.prompt == BookPrompt.IMPORTED }
            onNodeWithTag("read-only").performClick()
            ready(c) { it.prompt == BookPrompt.AFTER_READING }
            onNodeWithTag("read-only").performClick()
            ready(c) { it.prompt == null }
            onNodeWithTag("next-page").performClick()
            ready(c) { it.page == 2 }
            onNodeWithTag("next-page").performClick()
            ready(c) { it.page == 3 }
            onNodeWithText("返回书架").performClick()
            ready(c) { it.screen == BookScreen.HOME }
            onNodeWithTag("home-learn").performClick()
            ready(c) { it.prompt == BookPrompt.START }
            onNodeWithTag("enable-learning").performClick()
            ready(c) { it.screen == BookScreen.LEARNING }
            book = c.state.value.book!!
            practice = StudyCoachCourse.practice(book, book.lessons[0], QuestionMode.CHOICE)!!
            wrong = practice.options.first { it != practice.answer }
            onNodeWithTag("choice-${practice.options.indexOf(wrong)}").performScrollTo().performClick()
            ready(c) { it.answer == PracticeAnswer(wrong, true) }
            onNodeWithText("已加入复习，可在今天查看原题。").performScrollTo().assertIsDisplayed()
            capture("01-wrong-answer-saved")
            assertEquals(PracticeAnswer(wrong, true), TextbookLibrary(library.directory)
                .answer(book.id, book.lessons[0].id, QuestionMode.CHOICE))

            // Continue learning elsewhere first: review must not change the saved lesson or its mode.
            onNodeWithTag("next-lesson").performScrollTo().performClick()
            ready(c) { it.book?.lessonIndex == 1 }
            nav("今天").performClick()
            onNodeWithTag("host-today-review").assertTextContains("复习 1 题")
            capture("01a-today-review-entry")
            onNodeWithTag("host-today-review").performClick()
            ready(c) { it.screen.name == "REVIEWS" }
            capture("01b-review-list")
            onNodeWithTag("local-review-item-0").performScrollTo().performClick()
            ready(c) { it.screen.name == "REVIEWING" }
            onNodeWithText(practice.prompt).assertExists()
            onNodeWithText("上次作答：$wrong").assertExists()
            onNodeWithTag("choice-${practice.options.indexOf(wrong)}").performScrollTo().performClick()
            ready(c) { it.answer == PracticeAnswer(wrong, true) }
            capture("02-review-same-question")
        }

        // New controller/library/host, each operation opens the same physical SQLite file again.
        val reopenedLibrary = TextbookLibrary(library.directory)
        val reopened = TextbookController(reopenedLibrary)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            ready(reopened) { it.screen.name == "REVIEWING" }
            onNodeWithText(practice.prompt).assertExists()
            onNodeWithText("上次作答：$wrong").assertExists()
            onNodeWithText("你选择：$wrong").assertExists()
            assertEquals(PracticeAnswer(wrong, true), reopened.state.value.answer)
            assertEquals(1, reopenedLibrary.book(book.id).lessonIndex)
            assertEquals(3, reopenedLibrary.book(book.id).readingPage)
            val storedReview = reopenedLibrary.review(book.id, book.lessons[0].id, QuestionMode.CHOICE)
            Files.writeString(Path.of("build/screens/local-book-review/sqlite-readback.txt"),
                "book=" + storedReview.book + "\nlesson=" + storedReview.lesson + "\nprompt=" + storedReview.practice.prompt +
                "\nreference=" + storedReview.practice.reference + "\noriginal=" + storedReview.originalAnswer +
                "\nretry=" + storedReview.answer + "\nsavedLessonIndex=" + reopenedLibrary.book(book.id).lessonIndex +
                "\nreadingBookmark=" + reopenedLibrary.book(book.id).readingPage)
            capture("03-sqlite-reopened-review")

            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            ready(reopened) { it.screen == BookScreen.READER && it.source != null }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().size == 1 }
            onNodeWithTag("remote-highlight").assertContentDescriptionEquals("原句：${practice.reference}")
            assertEquals(1, reopened.state.value.page)
            assertEquals(3, reopenedLibrary.book(book.id).readingPage)
            capture("04-review-original-page")
            onNodeWithText("返回复习").performClick()
            ready(reopened) { it.screen.name == "REVIEWING" }
            onNodeWithText("你选择：$wrong").assertExists()
            onNodeWithText("重新作答").performScrollTo().performClick()
            ready(reopened) { it.answer == PracticeAnswer() }
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(reopened) { it.answer == PracticeAnswer(practice.answer, true) }
            onNodeWithText("这题已复习。").performScrollTo().assertIsDisplayed()
            capture("05-review-completed")
            nav("今天").performClick()
            onNodeWithTag("host-today-review").assertDoesNotExist()
            assertEquals(1, reopenedLibrary.book(book.id).lessonIndex)
            assertEquals(PracticeAnswer(wrong, true), reopenedLibrary.answer(book.id, book.lessons[0].id, QuestionMode.CHOICE))
        }
        println("LOCAL_BOOK_REVIEW_PASS: wrong persisted; today -> review -> same question; SQLite reopen; exact PDF source; retry resolves; original answer and progress retained")
    }
}
