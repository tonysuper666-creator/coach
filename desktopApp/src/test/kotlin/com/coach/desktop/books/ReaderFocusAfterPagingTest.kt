package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking
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
 * 每次都要求页码如实变化、阅读区重新拿到键盘焦点、草稿仍在，且全程没有 Compose 抛错。
 *
 * 焦点核的是阅读区本身（`reader-page`）：翻页键要在阅读区里才收得到，焦点留在外壳上方向键就收不到。
 * 外壳的 Esc / 批注键走预览路径，焦点在子节点时照样先经过外壳，不受影响。
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
            // 2.0 宽窗原页并排两页、文字一屏多栏，逐页点「上一页 / 下一页」要在一屏一页的竖窄窗里测（SinglePageReaderWindow）。
            setContent { Box(SinglePageReaderWindow) { TextbookWorkspace(controller) { null } } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            runBlocking { controller.learn() }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            val book = controller.state.value.book!!
            onNodeWithTag("short-answer").performScrollTo().performTextInput("Focus draft")
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }
            repeat(2) {
                turnUntil(controller, "next-page", click = { performMouseInput { click() } }) { it.page == 2 }
                waitUntil(timeoutMillis = 20_000) { runCatching { onNodeWithTag("reader-page").assertIsFocused() }.isSuccess }
                turnUntil(controller, "previous-page", click = { performMouseInput { click() } }) { it.page == 1 }
                waitUntil(timeoutMillis = 20_000) { runCatching { onNodeWithTag("reader-page").assertIsFocused() }.isSuccess }
            }
            assertEquals(PracticeAnswer("Focus draft"), controller.state.value.answer)
            assertEquals(book.id, controller.state.value.book!!.id)
        }
    }
}
