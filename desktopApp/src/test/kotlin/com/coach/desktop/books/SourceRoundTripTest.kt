package com.coach.desktop.books

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.input.key.Key
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SourceRoundTripTest {
    private fun longLessonController(): Pair<TextbookController, TextbookLibrary> {
        val root = Files.createTempDirectory("coach-long-lesson-")
        val file = root.resolve("synthetic.pdf")
        PDDocument().use { pdf ->
            repeat(2) { pageNumber ->
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                    out.newLineAtOffset(50f, 710f)
                    out.showText(if (pageNumber == 0) "Chapter 1 Plants" else "Chapter 2 Cells")
                    out.newLineAtOffset(0f, -20f)
                    if (pageNumber == 0) {
                        out.showText("Photosynthesis is the process by which plants use light to make sugars.")
                        repeat(32) { index ->
                            out.newLineAtOffset(0f, -18f)
                            out.showText("Plants use light and water to grow in this example number ${index + 1}.")
                        }
                    } else out.showText("Chlorophyll is a pigment found in plant cells.")
                    out.endText()
                }
            }
            pdf.save(file.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(file)
        library.save(imported.copy(readingPage = 2, learning = true, questionMode = QuestionMode.SHORT_ANSWER))
        return TextbookController(library) to library
    }

    @Test fun longLessonRestoresScrollFocusAndDraftAfterSource() {
        val (controller, library) = longLessonController()
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            val book = controller.state.value.book!!
            val lesson = book.lessons[book.lessonIndex]
            val prompt = StudyCoachCourse.practice(book, lesson, QuestionMode.SHORT_ANSWER)!!.prompt
            onNodeWithTag("short-answer").performScrollTo().performTextInput("My unfinished draft")
            waitUntil(timeoutMillis = 20_000) { controller.state.value.answer.text == "My unfinished draft" }
            onNodeWithTag("open-practice-source").performScrollTo()
            val before = onNodeWithTag("textbook-learning").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue(before > 0f, "fixture must scroll")
            onNodeWithTag("open-practice-source").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }
            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            val after = onNodeWithTag("textbook-learning").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals(before, after, 1f, "return must preserve scroll position")
            onNodeWithTag("open-practice-source").assertIsFocused()
            onNodeWithText(prompt).assertExists()
            onNodeWithTag("short-answer").assertTextContains("My unfinished draft")
            assertEquals(PracticeAnswer("My unfinished draft"), controller.state.value.answer)
            assertEquals(lesson.id, controller.state.value.book!!.lessons[controller.state.value.book!!.lessonIndex].id)
            assertEquals(2, library.book(book.id).readingPage)
        }
    }

    @Test fun escapeReturnsOnlyFromSourceToLearning() {
        val (controller, library) = longLessonController()
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            val book = controller.state.value.book!!
            onNodeWithTag("short-answer").performScrollTo().performTextInput("Draft for escape")
            waitUntil(timeoutMillis = 20_000) { controller.state.value.answer.text == "Draft for escape" }
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }
            onNodeWithTag("windows-textbooks").performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            assertEquals(PracticeAnswer("Draft for escape"), controller.state.value.answer)
            assertEquals(2, library.book(book.id).readingPage)
            onNodeWithTag("windows-textbooks").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertEquals(BookScreen.LEARNING, controller.state.value.screen)
        }
    }
    @Test fun questionOnSecondPageOpensItsActualSentenceAndKeepsAnswer() {
        val root = Files.createTempDirectory("coach-cross-page-source-")
        val pdfFile = root.resolve("synthetic.pdf")
        PDDocument().use { pdf ->
            for ((heading, body) in listOf(
                "Chapter 1 Plants" to "Plants grow quickly near bright windows in spring.",
                "" to "Photosynthesis is the process by which plants use light to make sugars.",
                "Chapter 2 Cells" to "Chlorophyll is a pigment found in plant cells."
            )) {
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                    out.newLineAtOffset(50f, 710f)
                    if (heading.isNotEmpty()) { out.showText(heading); out.newLineAtOffset(0f, -48f) }
                    out.showText(body); out.endText()
                }
            }
            pdf.save(pdfFile.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(pdfFile)
        library.save(imported.copy(readingPage = 3, learning = true, questionMode = QuestionMode.CHOICE))
        val controller = TextbookController(library)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            val book = controller.state.value.book!!
            val lesson = book.lessons[book.lessonIndex]
            val practice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
            assertEquals(1, lesson.firstPage)
            assertEquals("Photosynthesis is the process by which plants use light to make sugars.", practice.reference)
            val selected = practice.options.first { it != practice.answer }
            onNodeWithTag("choice-${practice.options.indexOf(selected)}").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.answer == PracticeAnswer(selected, revealed = true) }
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }
            assertEquals(2, controller.state.value.page)
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().size == 1 }
            onNodeWithTag("remote-highlight").assertContentDescriptionEquals("原句：${practice.reference}")
            onNodeWithText("上一页").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.page == 1 && !controller.state.value.busy }
            onNodeWithText("无法确认原句：本题依据在第 2 页。").assertIsDisplayed()
            onNodeWithTag("remote-highlight").assertDoesNotExist()
            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            assertEquals(PracticeAnswer(selected, revealed = true), controller.state.value.answer)
            onNodeWithText(practice.prompt).assertExists()
            assertEquals(3, library.book(book.id).readingPage)
        }
    }
    @Test fun repeatedQuestionReferenceAcrossPagesDoesNotGuessAHighlight() {
        val root = Files.createTempDirectory("coach-cross-page-duplicate-")
        val file = root.resolve("synthetic.pdf")
        val reference = "Photosynthesis is the process by which plants use light to make sugars."
        PDDocument().use { pdf ->
            for (number in 1..2) {
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                    out.newLineAtOffset(50f, 710f)
                    if (number == 1) { out.showText("Chapter 1 Plants"); out.newLineAtOffset(0f, -48f) }
                    out.showText(reference); out.endText()
                }
            }
            pdf.save(file.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(file)
        library.save(imported.copy(learning = true, questionMode = QuestionMode.CHOICE))
        val controller = TextbookController(library)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-image").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("无法确认原句：本题依据出现多次。").assertIsDisplayed()
            onNodeWithTag("remote-highlight").assertDoesNotExist()
            onNodeWithText("下一页").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.page == 2 && !controller.state.value.busy }
            onNodeWithText("无法确认原句：本题依据出现多次。").assertIsDisplayed()
            onNodeWithTag("remote-highlight").assertDoesNotExist()
            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            assertEquals(PracticeAnswer(), controller.state.value.answer)
        }
    }
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
