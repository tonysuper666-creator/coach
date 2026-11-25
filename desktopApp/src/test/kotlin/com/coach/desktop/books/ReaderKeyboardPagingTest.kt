package com.coach.desktop.books

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import kotlin.test.*

/**
 * 本机阅读页只用键盘翻页：←/→、PageUp/PageDown 一页一页翻，Home/End 到首末页；焦点在页码框里时不翻页，
 * 从页码框跳过去之后方向键又归阅读区。五页合成样本，不碰任何真实教材。
 */
@OptIn(ExperimentalTestApi::class)
class ReaderKeyboardPagingTest {
    /** 五页 PDF，每页有各自的正文，页数好数；从书架的「看书」进来，没有出处也没有提示弹窗。 */
    private fun fivePageController(): TextbookController {
        val root = Files.createTempDirectory("coach-key-paging-")
        val file = root.resolve("synthetic.pdf")
        PDDocument().use { pdf ->
            repeat(5) { index ->
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText()
                    out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                    out.newLineAtOffset(50f, 710f)
                    out.showText("Chapter ${index + 1} sample text")
                    out.newLineAtOffset(0f, -20f)
                    out.showText("Page ${index + 1} body for keyboard paging.")
                    out.endText()
                }
            }
            pdf.save(file.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        library.importBook(file)
        return TextbookController(library)
    }

    private fun ComposeUiTest.openReader(controller: TextbookController) {
        setContent { com.coach.desktop.WindowsTextbookHost(controller) { null } }
        waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
        onNodeWithTag("home-read").performScrollTo().performClick()
        waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }
        waitUntil(timeoutMillis = 20_000) { runCatching { onNodeWithTag("reader-page").assertIsFocused() }.isSuccess }
    }

    private fun ComposeUiTest.selectPdfMode(tag: String) {
        if (onAllNodesWithTag("immersive-more-menu").fetchSemanticsNodes().isEmpty()) {
            onNodeWithTag("reader-page").performKeyInput { pressKey(Key.Tab) }
        }
        onNodeWithTag("immersive-more-menu").performClick()
        onNodeWithTag("v7-type-menu").performClick()
        onNodeWithTag(tag).performClick()
        // The type panel closes after choosing a mode. Dismiss its parent overflow menu.
        onNodeWithTag("immersive-more-menu").performMouseInput { click() }
    }

    @Test fun arrowsAndPageKeysTurnPagesAndHomeEndJumpToTheEnds() {
        val controller = fivePageController()
        runSkikoComposeUiTest(size = Size(840f, 900f), density = Density(1f)) {
            openReader(controller)
            selectPdfMode("original-mode-paged")
            onNodeWithTag("original-pdf-paged").assertExists()
            onNodeWithTag("original-pdf-right").assertDoesNotExist()
            waitUntil(timeoutMillis = 20_000) { runCatching { onNodeWithTag("reader-page").assertIsFocused() }.isSuccess }
            fun press(key: Key) = onNodeWithTag("reader-page").performKeyInput { pressKey(key) }
            fun expectPage(number: Int) {
                waitUntil(timeoutMillis = 20_000) { controller.state.value.page == number && !controller.state.value.busy }
                // Controller state is published before Compose installs the next key handler.
                waitForIdle()
            }

            press(Key.DirectionRight); expectPage(2)
            press(Key.PageDown); expectPage(3)
            press(Key.DirectionLeft); expectPage(2)
            press(Key.PageUp); expectPage(1)
            press(Key.MoveEnd); expectPage(5)
            // 末页再按下一页不越界，首页再按上一页也不越界。
            press(Key.DirectionRight)
            waitForIdle()
            assertEquals(5, controller.state.value.page)
            press(Key.MoveHome); expectPage(1)
            press(Key.DirectionLeft)
            waitForIdle()
            assertEquals(1, controller.state.value.page)
            // 翻页把焦点留在阅读区，下一键马上还能用。
            onNodeWithTag("reader-page").assertIsFocused()
        }
    }

    @Test fun pageDownScrollsAScreenAndHomeEndReachTheBookEdgesInScrollMode() {
        val controller = fivePageController()
        runComposeUiTest {
            openReader(controller)
            selectPdfMode("original-mode-scroll")
            onNodeWithTag("original-pdf-scroll").assertExists()
            val reading = onNodeWithTag("reader-page")
            fun press(key: Key) = reading.performKeyInput { pressKey(key) }
            fun imageTop() = onNodeWithTag("remote-image").fetchSemanticsNode().positionInRoot.y
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-image").fetchSemanticsNodes().isNotEmpty() }
            val before = imageTop()

            press(Key.PageDown)
            waitUntil(timeoutMillis = 20_000) { runCatching { imageTop() < before - 100f }.getOrDefault(false) }
            assertEquals(1, controller.state.value.page, "One screen is shorter than this physical PDF page")

            press(Key.MoveEnd)
            waitUntil(timeoutMillis = 20_000) { controller.state.value.page == 5 && !controller.state.value.busy }
            press(Key.MoveHome)
            waitUntil(timeoutMillis = 20_000) { controller.state.value.page == 1 && !controller.state.value.busy }
            reading.assertIsFocused()
        }
    }

    @Test fun thePageFieldTakesTheArrowKeysAndHandsThemBackAfterJumping() {
        val controller = fivePageController()
        runComposeUiTest {
            openReader(controller)
            onNodeWithTag("reader-page").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.G); keyUp(Key.CtrlLeft) }
            waitUntil(timeoutMillis = 20_000) { runCatching { onNodeWithTag("page-jump").assertIsFocused() }.isSuccess }
            onNodeWithTag("page-jump").performKeyInput { pressKey(Key.DirectionRight) }
            onNodeWithTag("page-jump").performKeyInput { pressKey(Key.MoveEnd) }
            waitForIdle()
            assertEquals(1, controller.state.value.page)

            // 从页码框跳到第 3 页之后，方向键回到阅读区。
            onNodeWithTag("page-jump").performTextReplacement("3")
            onNodeWithText("前往").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.page == 3 && !controller.state.value.busy }
            waitUntil(timeoutMillis = 20_000) { runCatching { onNodeWithTag("reader-page").assertIsFocused() }.isSuccess }
            onNodeWithTag("reader-page").performKeyInput { pressKey(Key.DirectionLeft) }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.page == 2 && !controller.state.value.busy }
        }
    }

    @Test fun thePagingNoteIsNoLongerPrintedUnderThePage() {
        val controller = fivePageController()
        runComposeUiTest {
            openReader(controller)
            waitForIdle()
            // 说明句只在页码的悬停提示里，正文下面不再常驻。
            assertTrue(onAllNodesWithText("页码按 PDF 原文件计算。").fetchSemanticsNodes().isEmpty())
        }
    }
}
