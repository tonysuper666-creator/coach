package com.coach.desktop.books

import androidx.compose.ui.test.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SourceRoundTripTest {
    @Test fun selectedAnswerRemainsVisibleAfterOpeningItsOriginalSentence() {
        val root = Files.createTempDirectory("coach-source-roundtrip-")
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        library.save(imported.copy(readingPage = 3, lessonIndex = 1, learning = true, questionMode = QuestionMode.CHOICE))
        val controller = TextbookController(library)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            val book = controller.state.value.book!!
            val lesson = book.lessons[book.lessonIndex]
            val practice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
            val selected = practice.options.first { it != practice.answer }
            onNodeWithTag("choice-${practice.options.indexOf(selected)}").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.answer == PracticeAnswer(selected, revealed = true) }
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().size == 1 }
            assertEquals(book.id, controller.state.value.book!!.id)
            assertEquals(lesson.firstPage, controller.state.value.page)
            val quote = lesson.blocks.first { it.page == lesson.firstPage && !it.heading }.text
            onNodeWithTag("remote-highlight").assertContentDescriptionEquals("原句：$quote")
            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            assertEquals(lesson.id, controller.state.value.book!!.lessons[controller.state.value.book!!.lessonIndex].id)
            assertEquals(PracticeAnswer(selected, revealed = true), controller.state.value.answer)
            onNodeWithText(practice.prompt).assertExists()
            onNodeWithText("你选择：$selected").assertExists()
            assertEquals(3, library.book(book.id).readingPage)
        }
    }
}
