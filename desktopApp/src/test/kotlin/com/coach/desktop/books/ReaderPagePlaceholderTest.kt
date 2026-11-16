package com.coach.desktop.books

import androidx.compose.ui.test.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import kotlin.test.*

/**
 * 页图加载先用同尺寸的纸占位（`pgPaper` 底、`line` 细边），图到了原地换掉。
 * 这里核两件事：占位用的宽高比怎么挑；A4 书页上「占位」和「真图」是不是同一个宽高比，
 * 也就是图到了会不会把下面的按钮顶下去。样本是自造的 A4 合成 PDF，不碰真实教材。
 */
@OptIn(ExperimentalTestApi::class)
class ReaderPagePlaceholderTest {
    @Test fun theLastImageOnThisBookWins() {
        // 这本书量到过的比最准：别的都不看，直接用它。
        assertEquals(0.72f, readerPaperAspect(0.72f, 600.0, 800.0))
        // 没有量过的比就用文字层的页面尺寸。
        assertEquals(0.75f, readerPaperAspect(null, 600.0, 800.0), 0.0001f)
        // 两样都没有就按 A4 铺，先给一张像纸的框。
        assertEquals(1f / 1.4142f, readerPaperAspect(null, null, null))
        // 高度是 0 的坏数据不当真，仍旧退回 A4。
        assertEquals(1f / 1.4142f, readerPaperAspect(null, 600.0, 0.0))
    }

    @Test fun a4PageArrivesAtTheAspectThePaperWasLaidOutWith() {
        val root = Files.createTempDirectory("coach-paper-")
        val file = root.resolve("a4.pdf")
        PDDocument().use { pdf ->
            repeat(2) { index ->
                val page = PDPage(PDRectangle.A4)
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText()
                    out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                    out.newLineAtOffset(60f, 780f)
                    out.showText("A4 page ${index + 1}")
                    out.endText()
                }
            }
            pdf.save(file.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        library.importBook(file)
        val controller = TextbookController(library)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-read").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }
            waitUntil(timeoutMillis = 20_000) {
                runCatching { onNodeWithTag("remote-image").fetchSemanticsNode() }.isSuccess
            }
            val image = onNodeWithTag("remote-image").fetchSemanticsNode().size
            // 占位纸按 readerPaperAspect(null, null, null) 铺；A4 图进来还落在这个比例里，所以不跳。
            assertEquals(readerPaperAspect(null, null, null), image.width.toFloat() / image.height, 0.005f)
        }
    }
}
