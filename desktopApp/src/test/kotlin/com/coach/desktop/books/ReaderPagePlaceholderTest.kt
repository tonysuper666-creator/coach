package com.coach.desktop.books

import androidx.compose.ui.test.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * 页图加载先用同尺寸的纸占位（`pgPaper` 底、`line` 细边），图到了原地换掉。
 * 这里核三件事：占位用的宽高比怎么挑（这一页的几何优先，上一页的比例只兜底）；
 * 横竖混排时横页铺出来的纸和真图是不是同一个高度；A4 书页上「占位」和「真图」是不是同一个宽高比，
 * 也就是图到了会不会把下面的按钮顶下去。样本都是自造的合成 PDF，不碰真实教材。
 */
@OptIn(ExperimentalTestApi::class)
class ReaderPagePlaceholderTest {
    @Test fun thisPagesOwnPaperBeatsTheLastPagesRatio() {
        // 版心按 600 宽算：竖页那张纸要 849 高，横页的真图只有 424 高。
        // 旧顺序先看「这本书上一张图的比例」，横页会先铺 849 的纸、图到了再塌到 424；
        // 现在这一页自己有几何就铺自己这张纸，一开始就是 424。
        val portrait = 1f / 1.4142f
        val landscape = 842.0 / 595.0
        assertEquals(849f, 600f / portrait, 1f)
        assertEquals(424f, 600f / landscape.toFloat(), 1f)
        assertEquals(424f, 600f / readerPaperAspect(842.0, 595.0, portrait), 1f)
        // 反过来也一样：上一页是横的，竖页不会跟着铺横纸。
        assertEquals(849f, 600f / readerPaperAspect(595.0, 842.0, landscape.toFloat()), 1f)
        // 这一页量不到几何时，才轮到这本书上一张图的比例。
        assertEquals(0.72f, readerPaperAspect(null, null, 0.72f))
        // 高度是 0 的坏数据不当真，仍旧退到上一张的比例。
        assertEquals(0.72f, readerPaperAspect(600.0, 0.0, 0.72f))
        // 两样都没有就按 A4 铺，先给一张像纸的框。
        assertEquals(portrait, readerPaperAspect(null, null, null))
    }

    @Test fun aLandscapePageRendersIntoThePaperItsGeometryPromised() {
        val root = Files.createTempDirectory("coach-paper-mixed-")
        val file = mixedOrientationBook(root)
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(file)
        val image = library.renderPdf(book, 2)
        val real = image.width.toFloat() / image.height
        assertTrue(real > 1f, "第 2 页是横页，真图应该是横的，实际 $real")
        // 阅读器给横页铺的纸：这一页的 cropBox 是 842x595，上一页留下的是竖页的比例。
        // 纸的比例要等于真图的比例，图到了才不用换框。
        assertEquals(real, readerPaperAspect(842.0, 595.0, 1f / 1.4142f), 0.01f)
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
            // 占位纸要跟真图同比例，否则图到的时候下面的按钮会被顶下去。
            assertEquals(readerPaperAspect(null, null, null), image.width.toFloat() / image.height, 0.005f)
        }
    }

    @Test fun aMixedBookShowsTheLandscapePageOnLandscapePaper() {
        val root = Files.createTempDirectory("coach-paper-mixed-ui-")
        val file = mixedOrientationBook(root)
        val library = TextbookLibrary(root.resolve("library"))
        library.importBook(file)
        val controller = TextbookController(library)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-read").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }
            // 从竖页翻到横页：纸得是这一页的，不是上一页留下来的那张。
            waitUntil(timeoutMillis = 20_000) {
                runCatching { onNodeWithTag("next-page").assertIsEnabled() }.isSuccess
            }
            onNodeWithTag("next-page").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.page == 2 }
            waitUntil(timeoutMillis = 20_000) {
                runCatching { onNodeWithTag("remote-image").fetchSemanticsNode() }.isSuccess
            }
            val image = onNodeWithTag("remote-image").fetchSemanticsNode().size
            val aspect = image.width.toFloat() / image.height
            assertTrue(aspect > 1f, "第 2 页是横页，应该铺横纸，实际 $aspect")
            assertEquals(842f / 595f, aspect, 0.01f)
        }
    }

    /** 一本两页的书：第 1 页 A4 竖的，第 2 页 842x595 横的。 */
    private fun mixedOrientationBook(root: Path): Path {
        val file = root.resolve("mixed.pdf")
        PDDocument().use { pdf ->
            listOf(PDRectangle.A4 to "portrait page", PDRectangle(842f, 595f) to "landscape page")
                .forEachIndexed { index, (size, label) ->
                    val page = PDPage(size)
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { out ->
                        out.beginText()
                        out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                        out.newLineAtOffset(60f, 60f)
                        out.showText("$label ${index + 1}")
                        out.endText()
                    }
                }
            pdf.save(file.toFile())
        }
        return file
    }
}
