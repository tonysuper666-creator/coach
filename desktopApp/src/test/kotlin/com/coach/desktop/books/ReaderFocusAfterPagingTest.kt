package com.coach.desktop.books

import androidx.compose.ui.test.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import kotlin.test.*

/**
 * 翻页后阅读器焦点的恢复必须发生在组合所在的 UI 上下文：控制器动作在 IO 上完成，续体可能在后台线程恢复，
 * 在那里直接请求 Compose 焦点会触发 SnapshotStateObserver 的多线程访问错误。这里在同一个组合里连续前翻/后翻，
 * 每次都要求页码如实变化、阅读器重新拿到焦点、草稿仍在，且全程没有 Compose 抛错。
 */
@OptIn(ExperimentalTestApi::class)
class ReaderFocusAfterPagingTest {
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

    @Test fun pagingForwardAndBackKeepsTheReaderFocusedOnTheUiContextAndTheDraft() {
        val (controller, library) = longLessonController()
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            val book = controller.state.value.book!!
            onNodeWithTag("short-answer").performScrollTo().performTextInput("Focus draft")
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }
            repeat(2) {
                onNodeWithText("下一页").performMouseInput { click() }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.page == 2 && !controller.state.value.busy }
                waitUntil(timeoutMillis = 20_000) { runCatching { onNodeWithTag("windows-textbooks").assertIsFocused() }.isSuccess }
                onNodeWithText("上一页").performMouseInput { click() }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.page == 1 && !controller.state.value.busy }
                waitUntil(timeoutMillis = 20_000) { runCatching { onNodeWithTag("windows-textbooks").assertIsFocused() }.isSuccess }
            }
            assertEquals(PracticeAnswer("Focus draft"), controller.state.value.answer)
            assertEquals(book.id, controller.state.value.book!!.id)
        }
    }
}
