package com.coach.desktop.books

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SourceRoundTripTest {
    @Test fun duplicateSentenceShowsReasonAndKeepsDraftAfterTurningPage() {
        val root = Files.createTempDirectory("coach-duplicate-source-")
        val pdfFile = syntheticTextbook(root.resolve("synthetic"))
        Loader.loadPDF(pdfFile.toFile()).use { pdf ->
            PDPageContentStream(pdf, pdf.getPage(1), PDPageContentStream.AppendMode.APPEND, true).use { out ->
                out.beginText()
                out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 18f)
                out.newLineAtOffset(50f, 500f)
                out.showText("Chapter 3 Repetition")
                out.newLineAtOffset(0f, -48f)
                out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                out.showText("Chlorophyll is a green pigment that absorbs light in plant cells.")
                out.endText()
            }
            val rewritten = root.resolve("repeated.pdf")
            pdf.save(rewritten.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(root.resolve("repeated.pdf"))
        library.save(imported.copy(readingPage = 3, lessonIndex = 1, learning = true, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            val book = controller.state.value.book!!
            val lesson = book.lessons[book.lessonIndex]
            val quote = lesson.blocks.first { it.page == lesson.firstPage && !it.heading }.text
            assertEquals("Chlorophyll is a green pigment that absorbs light in plant cells.", quote)
            assertEquals(2, Regex(Regex.escape(quote)).findAll(book.pages[lesson.firstPage - 1].text).count())
            val prompt = StudyCoachCourse.practice(book, lesson, QuestionMode.SHORT_ANSWER)!!.prompt
            onNodeWithTag("short-answer").performScrollTo().performTextInput("My unfinished draft")
            waitUntil(timeoutMillis = 20_000) { controller.state.value.answer.text == "My unfinished draft" }
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-image").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(lesson.firstPage, controller.state.value.page)
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("无法确认原句：这一页有多处相同内容。").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("无法确认原句：这一页有多处相同内容。").assertIsDisplayed()
            onNodeWithTag("remote-image").assertContentDescriptionEquals("${book.title} 第 ${lesson.firstPage} 页")
            onNodeWithTag("remote-highlight").assertDoesNotExist()
            onNodeWithText("看文字").performClick()
            assertTrue(onNodeWithTag("reader-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().spanStyles.isEmpty())
            onNodeWithText("无法确认原句：这一页有多处相同内容。").assertIsDisplayed()
            onNodeWithText("看原页").performClick()
            onNodeWithText("下一页").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.page == lesson.firstPage + 1 && !controller.state.value.busy }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription("${book.title} 第 ${lesson.firstPage + 1} 页").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("上一页").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.page == lesson.firstPage && !controller.state.value.busy }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("无法确认原句：这一页有多处相同内容。").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            assertEquals(lesson.id, controller.state.value.book!!.lessons[controller.state.value.book!!.lessonIndex].id)
            assertEquals(PracticeAnswer("My unfinished draft"), controller.state.value.answer)
            onNodeWithText(prompt).assertExists()
            onNodeWithTag("short-answer").performScrollTo().assertTextContains("My unfinished draft")
            assertEquals(3, library.book(book.id).readingPage)
        }
    }
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
            onNodeWithTag("source-anchor-status").assertDoesNotExist()
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
