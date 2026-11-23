package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** Real single-column host callbacks and on-disk SQLite, using only a generated textbook. */
@OptIn(ExperimentalTestApi::class)
class WindowsLocalBookReviewTest {
    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

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
            ready(c) { it.initialized && it.book == null }
            // Single-file import goes straight into READER with zero BookPrompt dialogs.
            onNodeWithTag("upload-textbook").performClick()
            ready(c) { it.screen == BookScreen.READER && it.prompt == null && it.book != null }
            assertNull(c.state.value.prompt)
            runBlocking { c.page(3) }
            ready(c) { it.page == 3 }
            runBlocking { c.back() }
            ready(c) { it.screen == BookScreen.HOME }

            book = c.state.value.book!!
            practice = testPracticeFixture(book, book.lessons[0], QuestionMode.CHOICE)!!
            wrong = practice.options.first { it != practice.answer }
            runBlocking {
                c.recordSectionQuizAnswer(book.lessons[0], 0, practice, PracticeAnswer(wrong, true))
                c.lesson(1)
                c.back()
            }
            ready(c) { it.screen == BookScreen.HOME && it.reviews.size == 1 }
            assertEquals(PracticeAnswer(wrong, true), TextbookLibrary(library.directory)
                .answer(book.id, book.lessons[0].id, QuestionMode.CHOICE))

            onNodeWithTag("home-review-start").assertTextContains("开始复习")
            capture("01a-today-review-entry")
            onNodeWithTag("home-review-start").performClick()
            ready(c) { it.screen == BookScreen.REVIEWS }
            capture("01b-review-list")
            onNodeWithTag("local-review-summary").assertTextContains("今日 1 题")
            onNodeWithText("1 天、3 天、7 天", substring = true).assertDoesNotExist()
            onNodeWithTag("local-review-item-0").performScrollTo().performClick()
            ready(c) { it.screen == BookScreen.REVIEWING }
            onNodeWithText(practice.prompt).assertExists()
            onNodeWithText("上次作答：$wrong").assertExists()
            onNodeWithTag("choice-${practice.options.indexOf(wrong)}").performScrollTo().performClick()
            ready(c) { it.answer == PracticeAnswer(wrong, true) }
            onNodeWithTag("local-review-position").assertTextContains("1 / 1")
            capture("02-review-same-question")
        }

        // New controller/library/host, each operation opens the same physical SQLite file again.
        val reopenedLibrary = TextbookLibrary(library.directory)
        val reopened = TextbookController(reopenedLibrary)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            ready(reopened) { it.screen == BookScreen.REVIEWING }
            onNodeWithTag("local-review-position").assertTextContains("1 / 1")
            onNodeWithText(practice.prompt).assertExists()
            onNodeWithText("上次作答：$wrong").assertExists()
            onNodeWithText("你选择：$wrong").assertExists()
            assertEquals(PracticeAnswer(wrong, true), reopened.state.value.answer)
            assertEquals(1, reopenedLibrary.book(book.id).lessonIndex)
            assertEquals(3, reopenedLibrary.book(book.id).readingPage)
            capture("03-sqlite-reopened-review")

            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            ready(reopened) { it.screen == BookScreen.READER && it.source != null }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().size == 1 }
            onNodeWithTag("remote-highlight").assertContentDescriptionEquals("原句：${practice.reference}")
            assertEquals(1, reopened.state.value.page)
            assertEquals(3, reopenedLibrary.book(book.id).readingPage)
            capture("04-review-original-page")
            runBlocking { reopened.back() }
            ready(reopened) { it.screen == BookScreen.REVIEWING }
            onNodeWithText("你选择：$wrong").assertExists()
            onNodeWithText("重新作答").performScrollTo().performClick()
            ready(reopened) { it.answer == PracticeAnswer() }
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(reopened) { it.answer == PracticeAnswer(practice.answer, true) }
            onNodeWithText("这题已复习。").performScrollTo().assertIsDisplayed()
            capture("05-review-completed")
            onNodeWithTag("local-review-next").performScrollTo().performClick()
            ready(reopened) { it.screen == BookScreen.REVIEWS }
            onNodeWithTag("local-review-done").assertTextContains("今日复习已完成。")
            capture("06-today-review-done")
            runBlocking { reopened.back() }
            ready(reopened) { it.screen == BookScreen.HOME }
            onNodeWithTag("home-review-done").assertTextContains("还没有要复习的。做错的题会在明天出现。")
            onNodeWithTag("home-review-start").assertDoesNotExist()
            assertEquals(1, reopenedLibrary.book(book.id).lessonIndex)
            assertEquals(PracticeAnswer(wrong, true), reopenedLibrary.answer(book.id, book.lessons[0].id, QuestionMode.CHOICE))
        }
        println("LOCAL_BOOK_REVIEW_PASS: direct import -> home review -> SQLite reopen preserves 1/1 -> exact PDF source -> retry resolves -> done")
    }
}
