package com.coach.desktop.books

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class BookInBookSearchTest {
    private fun sixPageSearchPdf(dir: Path): Path {
        val file = dir.resolve("six-page-search.pdf")
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val linesByPage = listOf(
                listOf("Chapter 1 Introduction", "Page one introduces the river valley and basic survey notes."),
                listOf("Section 1.2 Field Notes", "Page two records elevation markers along the north bank."),
                listOf("Section 1.3 Early Crossing", "Page three summarizes wooden timber piers built in autumn."),
                listOf("Chapter 2 Copper Bridge", "Page four opens the chapter on structural spans and loads."),
                listOf("Section 2.2 Verification", "Copper Bridge resonance verified on page five."),
                listOf("Section 2.3 Appendix", "Page six closes the field survey with temperature tables."),
            )
            for (lines in linesByPage) {
                val page = PDPage(PDRectangle.LETTER)
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 14f)
                    stream.newLineAtOffset(72f, 700f)
                    for (line in lines) {
                        stream.showText(line)
                        stream.newLineAtOffset(0f, -24f)
                    }
                    stream.endText()
                }
            }
            pdf.save(file.toFile())
        }
        return file
    }

    @Test fun searchBookPagesFindsExactUtf16OffsetsSkipsHtmlCommentsAndRespectsLimit() {
        val pages = listOf(
            BookPage(1, "<!-- PDF 第 1 页 · 书第 1 页 -->\n第一章 引言\nCopper bridge spans the river."),
            BookPage(2, "第二章\nAnother COPPER BRIDGE detail and copper bridge load."),
        )
        val markerSearch = searchBookPages(pages, "PDF 第 1 页")
        assertTrue(markerSearch.hits.isEmpty(), "内部页标记注释不应被当成正文搜索命中")

        val bridgeSearch = searchBookPages(pages, "copper bridge")
        assertFalse(bridgeSearch.truncated)
        assertEquals(3, bridgeSearch.hits.size)
        assertEquals(listOf(1, 2, 2), bridgeSearch.hits.map { it.page })
        for (hit in bridgeSearch.hits) {
            val stored = pages.first { it.number == hit.page }.text
            assertEquals(hit.quote, stored.substring(hit.start, hit.end), "命中偏移必须与原文 UTF-16 逐字一致")
            assertEquals(hit.quote, hit.snippet.substring(hit.snippetMatchStart, hit.snippetMatchEnd))
        }

        val limited = searchBookPages(pages, "copper bridge", limit = 2)
        assertTrue(limited.truncated)
        assertEquals(2, limited.hits.size)
    }

    @Test fun ctrlFSearchJumpsToTargetPageAndPreservesAllFourReadingModesOnRealPdf() {
        val shotDir = Path.of("build/screens/ton1670")
        Files.createDirectories(shotDir)
        data class ModeCase(val name: String, val textView: Boolean, val scrolling: Boolean)
        val cases = listOf(
            ModeCase("original-paged", textView = false, scrolling = false),
            ModeCase("original-scroll", textView = false, scrolling = true),
            ModeCase("text-paged", textView = true, scrolling = false),
            ModeCase("text-scroll", textView = true, scrolling = true),
        )
        for (mode in cases) {
            val root = Files.createTempDirectory("coach-search-${mode.name}-")
            try {
                val pdfPath = sixPageSearchPdf(root)
                val library = TextbookLibrary(root.resolve("library"))
                val controller = TextbookController(library)
                runBlocking {
                    controller.importBook(pdfPath)
                    val id = controller.state.value.book!!.id
                    library.saveTextView(id, mode.textView)
                    library.savePdfScrollMode(id, mode.scrolling)
                    library.saveTextScrollMode(id, mode.scrolling)
                    controller.learn(LearningPath.READ)
                }
                val book = controller.state.value.book!!
                val page5 = book.page(5)!!
                val expectedOffset = page5.text.indexOf("Copper Bridge resonance")
                assertTrue(expectedOffset >= 0, "第 5 页应包含目标原句")

                runSkikoComposeUiTest(size = Size(1440f, 960f), density = Density(1f)) {
                    setContent { WindowsTextbookHost(controller) { null } }
                    waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                    assertEquals(1, controller.state.value.page, "${mode.name}: 初始应在第 1 页")

                    // 按 Ctrl+F 打开书内查找
                    onNodeWithTag("reader-page").performKeyInput {
                        keyDown(Key.CtrlLeft)
                        pressKey(Key.F)
                        keyUp(Key.CtrlLeft)
                    }
                    onNodeWithTag("book-search-panel").assertExists()
                    onNodeWithTag("book-search-input").performTextInput("Copper Bridge resonance")
                    onNodeWithTag("book-search-count").assertTextEquals("共 1 处")
                    onNodeWithTag("book-search-hit-0").assertExists().performClick()

                    waitUntil(timeoutMillis = 10_000) { controller.state.value.page == 5 }
                    assertEquals(5, controller.state.value.page, "${mode.name}: 点击搜索结果后应落到物理第 5 页")
                    onNodeWithTag("book-search-count").assertTextEquals("1 / 1")

                    // 校验读法被完整保留
                    onNodeWithTag("book-original-toggle").assertExists()
                    if (mode.textView) {
                        assertEquals(mode.scrolling, library.textScrollMode(book.id))
                        onAllNodesWithText("Copper Bridge resonance verified on page five.", substring = true)
                            .fetchSemanticsNodes()
                            .let { nodes -> assertTrue(nodes.isNotEmpty(), "${mode.name}: 命中的原句应在正文可见") }
                    } else {
                        assertEquals(mode.scrolling, library.pdfScrollMode(book.id))
                    }
                    if (mode.name == "original-paged" || mode.name == "text-paged") {
                        waitForIdle()
                        ImageIO.write(captureToImage().toAwtImage(), "png", shotDir.resolve("search-${mode.name}.png").toFile())
                    }

                    // Esc 关闭书内查找
                    onNodeWithTag("book-search-input").performKeyInput { pressKey(Key.Escape) }
                    onNodeWithTag("book-search-panel").assertDoesNotExist()
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test fun nextAndPreviousButtonsCycleThroughMultipleMatchesAcrossPages() {
        val root = Files.createTempDirectory("coach-search-cycle-")
        try {
            val sample = root.resolve("cycle.md")
            Files.writeString(sample, """## 第一章 桥梁结构

悬索桥的主要承重结构是主缆。

## 第二章 力学分析

计算主缆张力时需要考虑荷载分布。

## 第三章 工程维护

定期检查主缆防腐涂层。
""")
            val library = TextbookLibrary(root.resolve("library"))
            val controller = TextbookController(library)
            runBlocking {
                controller.importBook(sample)
                controller.learn(LearningPath.READ)
            }
            val hitPages = searchBookPages(controller.state.value.book!!.pages, "主缆").hits.map { it.page }
            assertEquals(3, hitPages.size)

            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent { WindowsTextbookHost(controller) { null } }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                onNodeWithTag("textbook-reader").performKeyInput { pressKey(Key.Tab) }
                waitForIdle()
                onNodeWithTag("book-search-toggle").performClick()
                onNodeWithTag("book-search-input").performTextInput("主缆")
                onNodeWithTag("book-search-count").assertTextEquals("共 3 处")

                onNodeWithTag("book-search-next").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.page == hitPages[0] }
                onNodeWithTag("book-search-count").assertTextEquals("1 / 3")

                onNodeWithTag("book-search-next").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.page == hitPages[1] }
                onNodeWithTag("book-search-count").assertTextEquals("2 / 3")

                onNodeWithTag("book-search-next").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.page == hitPages[2] }
                onNodeWithTag("book-search-count").assertTextEquals("3 / 3")

                // 下一处循环回第 1 处，上一处循环回第 3 处
                onNodeWithTag("book-search-next").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.page == hitPages[0] }
                onNodeWithTag("book-search-count").assertTextEquals("1 / 3")

                onNodeWithTag("book-search-prev").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.page == hitPages[2] }
                onNodeWithTag("book-search-count").assertTextEquals("3 / 3")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
