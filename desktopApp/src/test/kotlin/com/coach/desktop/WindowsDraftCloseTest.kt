package com.coach.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsDraftCloseTest {
    @Test fun closeAfterWriteTimeoutKeepsDraftAndRetrySavesBeforeClosing() {
        val root = Files.createTempDirectory("coach-close-draft-")
        val source = root.resolve("synthetic.txt")
        Files.writeString(source, "Chapter 1\n\nA synthetic textbook example with enough material to learn.")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(source).copy(learning = true, promptSeen = true, questionMode = QuestionMode.SHORT_ANSWER)
        library.save(book)
        val lesson = book.lessons.first().id
        library.saveAnswer(book.id, lesson, book.questionMode, PracticeAnswer("earlier draft"))
        val controller = TextbookController(library)
        val expected = "last input survives retry"
        var windowOpen by mutableStateOf(true)
        lateinit var guard: TextbookCloseGuard

        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent {
                if (windowOpen) {
                    val drafts = rememberAnswerDrafts(controller)
                    val scope = rememberCoroutineScope()
                    guard = remember { TextbookCloseGuard(drafts, scope) { windowOpen = false } }
                    Box(Modifier.fillMaxSize()) {
                        WindowsTextbookHost(controller, drafts) { null }
                        Button(guard::requestClose, Modifier.align(Alignment.TopEnd).testTag("request-close")) { Text("close") }
                    }
                    TextbookCloseWarning(guard)
                }
            }
            waitUntil(timeoutMillis = 15_000) { controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == BookScreen.LEARNING }
            DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { blocker ->
                blocker.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                try {
                    onNodeWithTag("short-answer").performScrollTo().performTextReplacement(expected)
                    onNodeWithTag("request-close").performClick()
                    waitUntil(timeoutMillis = 12_000) { guard.failed }
                    assertTrue(windowOpen)
                    onNodeWithTag("short-answer").assertTextContains(expected)
                    onNodeWithTag("retry-close").assertExists()
                    assertEquals("earlier draft", library.answer(book.id, lesson, book.questionMode).text)
                    println("CLOSE_TIMEOUT: windowOpen=$windowOpen; visible='$expected'; disk='${library.answer(book.id, lesson, book.questionMode).text}'")
                } finally {
                    blocker.createStatement().use { it.execute("ROLLBACK") }
                }
            }
            onNodeWithTag("retry-close").performClick()
            waitUntil(timeoutMillis = 10_000) { !windowOpen }
            assertEquals(expected, library.answer(book.id, lesson, book.questionMode).text)
            println("CLOSE_RETRY: windowOpen=$windowOpen; disk='${library.answer(book.id, lesson, book.questionMode).text}'")
        }
        val reopened = TextbookController(TextbookLibrary(library.directory))
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            waitUntil(timeoutMillis = 15_000) { reopened.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 15_000) { reopened.state.value.screen == BookScreen.LEARNING }
            onNodeWithTag("short-answer").performScrollTo().assertTextContains(expected)
            println("CLOSE_REOPEN: visible='${reopened.state.value.answer.text}'")
        }
    }
}
