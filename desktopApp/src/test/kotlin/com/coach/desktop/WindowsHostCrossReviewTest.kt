package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

/** Independent fixed-head PR188 review. Uses disposable books, never the learner's library. */
@OptIn(ExperimentalTestApi::class)
class WindowsHostCrossReviewTest {
    private data class Fixture(val directory: Path, val library: TextbookLibrary, val a: Textbook, val b: Textbook)

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("coach-pr188-cross-review-")
        val library = TextbookLibrary(root.resolve("library"))
        fun book(name: String, page: Int, lesson: Int, draft: String): Textbook {
            val source = root.resolve("$name.txt")
            Files.writeString(source, (1..3).joinToString("\u000c") {
                "Chapter $it $name\n\n$name concept $it is an independent explanation with an example for this chapter."
            })
            val imported = library.importBook(source)
            val enabled = imported.copy(readingPage = page, lessonIndex = lesson, learning = true,
                promptSeen = true, questionMode = QuestionMode.SHORT_ANSWER)
            library.save(enabled)
            library.saveAnswer(enabled.id, enabled.lessons[lesson].id, enabled.questionMode, PracticeAnswer(draft))
            return enabled
        }
        return Fixture(root, library, book("Alpha", 2, 1, "alpha earlier draft"),
            book("Beta", 3, 0, "beta earlier draft"))
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 15_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.nav(label: String) =
        onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))

    /** Waits for a background write to land without turning a missing write into a timeout stack trace. */
    private fun ComposeUiTest.awaitTrue(timeoutMillis: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            waitForIdle()
            if (condition()) return
            Thread.sleep(20)
        }
    }

    private fun blockedIn(method: String) = Thread.getAllStackTraces().values.any { stack ->
        stack.any { it.className == TextbookLibrary::class.java.name && it.methodName == method }
    }

    private fun ComposeUiTest.waitForBlockedWrite(method: String = "saveAnswer") {
        waitUntil(timeoutMillis = 3_000) { blockedIn(method) }
    }

    @Test fun twoBooksKeepIndependentUnsubmittedDraftsBookmarksAndSelectionAcrossContinueAndReload() {
        val f = fixture()
        val c = TextbookController(f.library)
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(c) { null } }
            ready(c) { it.catalog.size == 2 && it.book != null }
            onNodeWithTag("book-${f.a.id}").performClick()
            ready(c) { it.book?.id == f.a.id && it.screen == BookScreen.HOME }
            onNodeWithTag("home-learn").performClick()
            ready(c) { it.screen == BookScreen.LEARNING && it.answer.text == "alpha earlier draft" }
            onNodeWithTag("short-answer").performScrollTo().performTextReplacement("alpha revised but not submitted")
            ready(c) { it.answer.text == "alpha revised but not submitted" }
            assertFalse(c.state.value.answer.revealed)
            nav("今天").performClick()
            onNodeWithTag("host-today-book").assertExists()
            onNodeWithText("《Alpha》读到第 2 页，学习停在第 2 / 3 节").assertExists()
            onNodeWithTag("host-today-learn").performClick()
            ready(c) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("short-answer").performScrollTo().assertTextContains("alpha revised but not submitted")
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            ready(c) { it.source != null && it.page == 2 }
            onNodeWithTag("next-page").performClick()
            ready(c) { it.source != null && it.page == 3 }
            nav("课程").performClick()
            onNodeWithTag("host-course-read").performClick()
            ready(c) { it.screen == BookScreen.READER && it.source == null && it.page == 2 }
            onNodeWithText("返回书架").performClick()
            ready(c) { it.screen == BookScreen.HOME }
            onNodeWithTag("book-${f.b.id}").performClick()
            ready(c) { it.book?.id == f.b.id }
            nav("课程").performClick()
            onNodeWithText("Beta").assertExists()
            onNodeWithTag("host-course-learn").performClick()
            ready(c) { it.screen == BookScreen.LEARNING && it.answer.text == "beta earlier draft" }
            onNodeWithTag("short-answer").performScrollTo().assertTextContains("beta earlier draft")
            onNodeWithTag("short-answer").performTextReplacement("beta revised but not submitted")
            ready(c) { it.answer.text == "beta revised but not submitted" }
            nav("今天").performClick()
            onNodeWithText("《Beta》读到第 3 页，学习停在第 1 / 3 节").assertExists()
            onNodeWithTag("host-today-read").performClick()
            ready(c) { it.book?.id == f.b.id && it.page == 3 && it.screen == BookScreen.READER }
            onNodeWithText("返回书架").performClick()
            ready(c) { it.screen == BookScreen.HOME }
            onNodeWithTag("book-${f.a.id}").performClick()
            ready(c) { it.book?.id == f.a.id }
            nav("今天").performClick()
            onNodeWithTag("host-today-learn").performClick()
            ready(c) { it.screen == BookScreen.LEARNING && it.answer.text == "alpha revised but not submitted" }
            assertEquals(1, c.state.value.book!!.lessonIndex)
            onNodeWithTag("short-answer").performScrollTo().assertTextContains("alpha revised but not submitted")
        }
        val reopened = TextbookController(TextbookLibrary(f.library.directory))
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            ready(reopened) { it.catalog.size == 2 && it.book != null }
            assertEquals(f.a.id, reopened.state.value.book!!.id)
            nav("课程").performClick()
            onNodeWithTag("host-course-learn").performClick()
            ready(reopened) { it.screen == BookScreen.LEARNING && it.answer.text == "alpha revised but not submitted" }
            onNodeWithTag("short-answer").performScrollTo().assertTextContains("alpha revised but not submitted")
        }
        assertEquals(2, f.library.book(f.a.id).readingPage)
        assertEquals(3, f.library.book(f.b.id).readingPage)
        assertEquals("beta revised but not submitted", f.library.answer(f.b.id, f.b.lessons[0].id, QuestionMode.SHORT_ANSWER).text)
        println("PASS: Alpha/Beta remain separate through both continue entries, source-page detour, and a new host/controller.")
    }

    @Test fun leavingBookshelfDuringAWriteMustPreserveTheLatestVisibleDraft() {
        val f = fixture()
        val c = TextbookController(f.library)
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(c) { null } }
            ready(c) { it.catalog.size == 2 && it.book != null }
            onNodeWithTag("book-${f.a.id}").performClick()
            ready(c) { it.book?.id == f.a.id }
            onNodeWithTag("home-learn").performClick()
            ready(c) { it.screen == BookScreen.LEARNING && it.answer.text == "alpha earlier draft" }

            // A real SQLite write lock simulates a short competing writer / slow write.
            // No production method is stubbed; the UI receives ordinary typing and navigation.
            DriverManager.getConnection("jdbc:sqlite:${f.library.directory.resolve("library.sqlite")}").use { blocker ->
                blocker.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                try {
                    onNodeWithTag("short-answer").performScrollTo().performTextReplacement("first edit")
                    waitForBlockedWrite()
                    onNodeWithTag("short-answer").performTextInput(" plus final edit")
                    onNodeWithTag("short-answer").assertTextContains("first edit plus final edit")
                    nav("今天").performClick()
                    onNodeWithTag("host-today-book").assertExists()
                } finally {
                    blocker.createStatement().use { it.execute("ROLLBACK") }
                }
            }
            // The host's continue callback waits for any remaining controller action.
            onNodeWithTag("host-today-learn").performClick()
            ready(c) { it.screen == BookScreen.LEARNING && it.answer.text != "alpha earlier draft" }
            val stored = f.library.answer(f.a.id, f.a.lessons[1].id, QuestionMode.SHORT_ANSWER).text
            println("Latest visible draft='first edit plus final edit'; resumed='${c.state.value.answer.text}'; stored='$stored'")
            assertEquals("first edit plus final edit", stored, "Leaving a tab must retain all visible unsubmitted input")
            onNodeWithTag("short-answer").performScrollTo().assertTextContains("first edit plus final edit")
        }
    }

    @Test fun aDraftWaitingOnTheWriteLockStaysWithTheLessonItWasTypedIn() {
        val f = fixture()
        val c = TextbookController(f.library)
        val typed = f.a.lessons[1].id
        val next = f.a.lessons[2].id
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(c) { null } }
            ready(c) { it.catalog.size == 2 && it.book != null }
            onNodeWithTag("book-${f.a.id}").performClick()
            ready(c) { it.book?.id == f.a.id }
            onNodeWithTag("home-learn").performClick()
            ready(c) { it.screen == BookScreen.LEARNING && it.answer.text == "alpha earlier draft" }
            DriverManager.getConnection("jdbc:sqlite:${f.library.directory.resolve("library.sqlite")}").use { blocker ->
                blocker.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                try {
                    onNodeWithTag("short-answer").performScrollTo().performTextReplacement("first edit")
                    waitForBlockedWrite()
                    onNodeWithTag("short-answer").performTextInput(" plus final edit")
                    onNodeWithTag("short-answer").assertTextContains("first edit plus final edit")
                    // The learner moves to the next lesson while the first write is still waiting for the lock.
                    onNodeWithTag("next-lesson").performClick()
                } finally {
                    blocker.createStatement().use { it.execute("ROLLBACK") }
                }
            }
            waitUntil(timeoutMillis = 15_000) { c.state.value.book?.lessonIndex == 2 }
            awaitTrue { f.library.answer(f.a.id, typed, QuestionMode.SHORT_ANSWER).text == "first edit plus final edit" }
            assertEquals("first edit plus final edit", f.library.answer(f.a.id, typed, QuestionMode.SHORT_ANSWER).text,
                "The draft belongs to the lesson it was typed in")
            assertEquals("", f.library.answer(f.a.id, next, QuestionMode.SHORT_ANSWER).text,
                "A delayed write must not leak into the lesson the learner moved to")
            assertEquals("", c.state.value.answer.text)
            println("BOUND PASS: lesson 1 keeps 'first edit plus final edit'; lesson 2 stays empty after the move.")
        }
    }

    /**
     * Builder-C's example, opposite order to the guard above: 下一节 is clicked first, nothing is pending so
     * `flush()` returns at once and the lesson switch itself waits on SQLite. The lesson-2 field is still the
     * visible one and is not disabled while busy, so the learner's last sentence is typed during that wait.
     */
    @Test fun aKeystrokeTypedWhileTheLessonSwitchWaitsStaysWithTheVisibleLesson() {
        val f = fixture()
        val c = TextbookController(f.library)
        val typed = f.a.lessons[1].id
        val next = f.a.lessons[2].id
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(c) { null } }
            ready(c) { it.catalog.size == 2 && it.book != null }
            onNodeWithTag("book-${f.a.id}").performClick()
            ready(c) { it.book?.id == f.a.id }
            onNodeWithTag("home-learn").performClick()
            ready(c) { it.screen == BookScreen.LEARNING && it.answer.text == "alpha earlier draft" }
            DriverManager.getConnection("jdbc:sqlite:${f.library.directory.resolve("library.sqlite")}").use { blocker ->
                blocker.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                try {
                    onNodeWithTag("next-lesson").performScrollTo().performClick()
                    waitForBlockedWrite("save")
                    assertEquals(1, c.state.value.book!!.lessonIndex, "lesson 2 is still the visible lesson")
                    onNodeWithTag("short-answer").performScrollTo().performTextReplacement("lesson two final words")
                    waitForIdle()
                } finally {
                    blocker.createStatement().use { it.execute("ROLLBACK") }
                }
            }
            waitUntil(timeoutMillis = 15_000) { c.state.value.book?.lessonIndex == 2 && !c.state.value.busy }
            awaitTrue { f.library.answer(f.a.id, typed, QuestionMode.SHORT_ANSWER).text == "lesson two final words" }
            val typedStored = f.library.answer(f.a.id, typed, QuestionMode.SHORT_ANSWER).text
            val nextStored = f.library.answer(f.a.id, next, QuestionMode.SHORT_ANSWER).text
            println("VISIBLE-LESSON PASS: lesson 2 stored='$typedStored'; lesson 3 stored='$nextStored'; visible on 3/3='${c.state.value.answer.text}'")
            assertEquals("lesson two final words", typedStored, "The keystroke belongs to the lesson it was typed in")
            assertEquals("", nextStored, "A keystroke typed on lesson 2 must not be written into lesson 3")
            assertEquals("", c.state.value.answer.text, "The open lesson's answer box must not echo another lesson's draft")
        }
    }

    @Test fun theSameBlockedWritesKeepTheCompleteDraftWhenTheBookshelfStaysMounted() {
        val f = fixture()
        val c = TextbookController(f.library)
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(c) { null } }
            ready(c) { it.catalog.size == 2 && it.book != null }
            onNodeWithTag("book-${f.a.id}").performClick()
            ready(c) { it.book?.id == f.a.id }
            onNodeWithTag("home-learn").performClick()
            ready(c) { it.screen == BookScreen.LEARNING && it.answer.text == "alpha earlier draft" }
            DriverManager.getConnection("jdbc:sqlite:${f.library.directory.resolve("library.sqlite")}").use { blocker ->
                blocker.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                try {
                    onNodeWithTag("short-answer").performScrollTo().performTextReplacement("first edit")
                    waitForBlockedWrite()
                    onNodeWithTag("short-answer").performTextInput(" plus final edit")
                    onNodeWithTag("short-answer").assertTextContains("first edit plus final edit")
                } finally {
                    blocker.createStatement().use { it.execute("ROLLBACK") }
                }
            }
            ready(c) { it.answer.text == "first edit plus final edit" }
            assertEquals("first edit plus final edit", f.library.answer(f.a.id, f.a.lessons[1].id, QuestionMode.SHORT_ANSWER).text)
            println("CONTROL PASS: the same two edits and SQLite contention preserve the complete draft when the workspace stays mounted.")
        }
    }
}
