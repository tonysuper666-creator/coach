package com.coach.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

/**
 * Independent empirical check of the two unverified lifecycle items from Builder-C's 22:49 cross-review
 * on fixed head 05ac298462a631c6a26bad341fa52b119c962eda:
 * 1. Real SQLite write lock exceeding busy_timeout=5000ms: whether save failure invalidates the host
 *    rememberCoroutineScope, and whether editing again after unlock persists to SQLite.
 * 2. Window destroy/recreate while the latest visible input is still pending in AnswerDrafts: whether
 *    the last visible input is preserved on reopen.
 */
@OptIn(ExperimentalTestApi::class)
class ArgonGLifecycleReviewTest {
    private data class Fixture(val directory: Path, val library: TextbookLibrary, val a: Textbook, val b: Textbook)

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("coach-pr188-lifecycle-review-")
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

    private fun blockedInSaveAnswer(): Boolean = Thread.getAllStackTraces().values.any { stack ->
        stack.any { it.className == TextbookLibrary::class.java.name && it.methodName == "saveAnswer" }
    }

    private fun ComposeUiTest.waitForBlockedWrite() {
        waitUntil(timeoutMillis = 3_000) { blockedInSaveAnswer() }
    }

    @Test fun sqliteWriteLockExceedingBusyTimeout5000KeepsHostScopeAliveAndSubsequentEditsPersist() {
        val f = fixture()
        val c = TextbookController(f.library)
        val lessonId = f.a.lessons[1].id
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(c) { null } }
            ready(c) { it.catalog.size == 2 && it.book != null }
            onNodeWithTag("book-${f.a.id}").performClick()
            ready(c) { it.book?.id == f.a.id }
            onNodeWithTag("home-learn").performClick()
            ready(c) { it.screen == BookScreen.LEARNING && it.answer.text == "alpha earlier draft" }

            val startedAt = System.currentTimeMillis()
            var timeoutElapsedMs = 0L
            DriverManager.getConnection("jdbc:sqlite:${f.library.directory.resolve("library.sqlite")}").use { blocker ->
                blocker.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                try {
                    onNodeWithTag("short-answer").performScrollTo().performTextReplacement("draft timed out at 5s")
                    waitForBlockedWrite()
                    // Hold the real SQLite write lock past PRAGMA busy_timeout=5000 until saveAnswer fails.
                    waitUntil(timeoutMillis = 12_000) { !blockedInSaveAnswer() && c.state.value.error != null }
                    timeoutElapsedMs = System.currentTimeMillis() - startedAt
                    onNodeWithTag("book-error").assertExists()
                } finally {
                    blocker.createStatement().use { it.execute("ROLLBACK") }
                }
            }
            val errorAfterTimeout = c.state.value.error
            val storedAfterTimeout = f.library.answer(f.a.id, lessonId, QuestionMode.SHORT_ANSWER).text
            println(
                "ITEM1_AFTER_TIMEOUT: elapsedMs=$timeoutElapsedMs; uiError='$errorAfterTimeout'; " +
                    "storedAfterTimeout='$storedAfterTimeout'; controllerAnswer='${c.state.value.answer.text}'"
            )
            assertTrue(timeoutElapsedMs >= 4_800, "Real SQLite busy_timeout=5000 must have elapsed (was ${timeoutElapsedMs}ms)")
            assertEquals("暂时无法读取或保存教材，请确认文件完整、未加密，然后重试。", errorAfterTimeout)
            assertEquals("alpha earlier draft", storedAfterTimeout)

            // Now the lock is released: edit again in the same WindowsTextbookHost instance to verify
            // rememberCoroutineScope was NOT invalidated and subsequent saves persist to SQLite.
            onNodeWithTag("short-answer").performScrollTo().performTextReplacement("draft saved after unlock")
            ready(c) { it.answer.text == "draft saved after unlock" && it.error == null }
            val storedAfterUnlockEdit = f.library.answer(f.a.id, lessonId, QuestionMode.SHORT_ANSWER).text
            println(
                "ITEM1_AFTER_UNLOCK_EDIT: uiError='${c.state.value.error}'; " +
                    "controllerAnswer='${c.state.value.answer.text}'; storedAfterUnlockEdit='$storedAfterUnlockEdit'"
            )
            assertEquals("draft saved after unlock", storedAfterUnlockEdit)
        }
    }

    @Test fun windowDestroyAndRecreateDuringShortWriteLockMustPreserveLatestVisibleDraft() {
        val f = fixture()
        val c = TextbookController(f.library)
        val lessonId = f.a.lessons[1].id
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            var windowOpen by mutableStateOf(true)
            setContent {
                if (windowOpen) {
                    WindowsTextbookHost(c) { null }
                }
            }
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
                    onNodeWithTag("short-answer").performTextInput(" plus final edit before close")
                    onNodeWithTag("short-answer").assertTextContains("first edit plus final edit before close")
                    // Destroy the host window composition while the first write is still in flight and the
                    // second edit (" plus final edit before close") is queued in AnswerDrafts.pending.
                    windowOpen = false
                    waitForIdle()
                } finally {
                    blocker.createStatement().use { it.execute("ROLLBACK") }
                }
            }
            // Wait for the in-flight JDBC call to finish after the short lock is released.
            waitUntil(timeoutMillis = 5_000) { !blockedInSaveAnswer() }
        }

        val reopened = TextbookController(TextbookLibrary(f.library.directory))
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            ready(reopened) { it.catalog.size == 2 && it.book != null }
            onNodeWithTag("book-${f.a.id}").performClick()
            ready(reopened) { it.book?.id == f.a.id }
            onNodeWithTag("home-learn").performClick()
            ready(reopened) { it.screen == BookScreen.LEARNING }
            val stored = f.library.answer(f.a.id, lessonId, QuestionMode.SHORT_ANSWER).text
            val reopenedVisible = reopened.state.value.answer.text
            println(
                "ITEM2_AFTER_WINDOW_RECREATE: visibleBeforeClose='first edit plus final edit before close'; " +
                    "storedOnDisk='$stored'; reopenedUiAnswer='$reopenedVisible'"
            )
            assertEquals(
                "first edit plus final edit before close",
                stored,
                "Closing/destroying the host window must not silently drop the latest visible draft waiting in AnswerDrafts.pending"
            )
        }
    }
}
