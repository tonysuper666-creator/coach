package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.ui.CoachTheme
import com.coach.ui.theme.CoachColors
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * TON-1659 第 1 项：出处不写块编号，写页码和段号。
 * 同一页两段：两条出处的段号、各自的摘句、点开后的标题和原文，必须逐一对应。
 * 页号只在书里真有页码时才算数：PDF 的页号是书里印着的；无页码的文本导入不能拿导入时编的块号冒充页号。
 */
@OptIn(ExperimentalTestApi::class)
class ExplainCitationTest {
    @Test fun printedPageNumbersNameTheParagraphOnItsPage() {
        val root = Files.createTempDirectory("explain-citation-pdf-")
        val file = root.resolve("one-page.pdf")
        PDDocument().use { pdf ->
            val sheet = PDPage(); pdf.addPage(sheet)
            PDPageContentStream(pdf, sheet).use { out ->
                out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                out.newLineAtOffset(50f, 710f); out.showText("Chapter 1 Light")
                out.newLineAtOffset(0f, -110f); out.showText("Alpha light.")
                out.newLineAtOffset(0f, -100f); out.showText("Beta water.")
                out.endText()
            }
            pdf.save(file.toFile())
        }
        val book = TextbookLibrary(root.resolve("library")).importBook(file)
        val paragraphs = StudyCoachCourse.blocks(book.id, book.pages).filter { !it.heading }
        assertEquals(listOf("Alpha light.", "Beta water."), paragraphs.map { it.text }, "PDF 一页两段正文")
        assertEquals(listOf(1, 1), paragraphs.map { it.page })

        assertCitations(root, book, paragraphs,
            links = listOf(
                "出处 1 · 第 1 页 · 第 1 段 · “Alpha light.”",
                "出处 2 · 第 1 页 · 第 2 段 · “Beta water.”",
            ),
            titles = listOf("原文 · 第 1 页 · 第 1 段", "原文 · 第 1 页 · 第 2 段"))
        root.toFile().deleteRecursively()
    }

    @Test fun textWithoutPageNumbersNamesTheParagraphInItsChapter() {
        val root = Files.createTempDirectory("explain-citation-txt-")
        val book = TextbookLibrary(root.resolve("library"))
            .importBook(Files.writeString(root.resolve("two.txt"), TEXT))
        assertEquals("txt", book.format)
        val blocks = StudyCoachCourse.blocks(book.id, book.pages)
        val paragraphs = blocks.filter { !it.heading }
        assertEquals(listOf("Alpha light.", "Beta water."), paragraphs.map { it.text }, "同一章两段正文")
        // 文本导入把章节切块从 1 编号，这个号只用来定位原文，不是书里的页码。
        assertEquals(listOf(1, 1), paragraphs.map { it.page })
        assertEquals("第 1 页（1 Light）", book.pageLabel(paragraphs[0].page), "导入时编的页号")

        assertCitations(root, book, paragraphs,
            links = listOf(
                "出处 1 · 本章第 1 段 · “Alpha light.”",
                "出处 2 · 本章第 2 段 · “Beta water.”",
            ),
            titles = listOf("原文 · 本章第 1 段", "原文 · 本章第 2 段"))
        root.toFile().deleteRecursively()
    }

    /** 先点第 2 段再点第 1 段（读者验收时的顺序），每次的标题和原文都要跟着换。 */
    private fun assertCitations(root: Path, book: Textbook, paragraphs: List<SourceBlock>,
        links: List<String>, titles: List<String>) {
        val consent = ExplainConsent(root.resolve("library")).apply { allow(book.id) }
        val mac = FakeMac(root.resolve("home"), paragraphs)
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent {
                CoachTheme(false) {
                    Box(Modifier.fillMaxSize().background(CoachColors.light().surface1)) {
                        BookExplainPanel(book, paragraphs[0].page, consent, mac)
                    }
                }
            }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-button").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag("explain-button")[0].performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-citation").fetchSemanticsNodes().size == 2 }

            // 链接上就是位置 + 段号 + 这一段自己的开头，没有块编号。
            onAllNodesWithTag("explain-citation")[0].assertTextEquals(links[0])
            onAllNodesWithTag("explain-citation")[1].assertTextEquals(links[1])
            val shown = onAllNodesWithTag("explain-citation").fetchSemanticsNodes()
                .flatMap { it.config[SemanticsProperties.Text] }.joinToString(" ") { it.text }
            assertFalse(shown.contains(paragraphs[0].id), "出处里不能露出块编号：$shown")
            assertFalse(shown.contains(":p"), "出处里不能露出内部定位：$shown")

            // 先点第 2 条：标题、原文都是第 2 段。
            onAllNodesWithTag("explain-citation")[1].performClick()
            waitForIdle()
            onNodeWithTag("explain-source-title").assertTextEquals(titles[1])
            onNodeWithTag("explain-source-text").assertTextEquals("Beta water.")
            onNodeWithTag("explain-source-close").performClick()
            waitForIdle()

            // 再点第 1 条：标题、原文都换到第 1 段，不会还停在上一段。
            onAllNodesWithTag("explain-citation")[0].performClick()
            waitForIdle()
            onNodeWithTag("explain-source-title").assertTextEquals(titles[0])
            onNodeWithTag("explain-source-text").assertTextEquals("Alpha light.")
        }
    }

    /** 服务端给两条出处：同一页（章）第 1 段和第 2 段。 */
    private class FakeMac(home: Path, private val cited: List<SourceBlock>) :
        BookExplainer(DesktopConnectionSettings(home = home, environment = { null })) {
        override suspend fun load() {
            identity = DesktopIdentity("http://mac.test:18791", PairedCredentials("test-device", "test-token"))
            loaded = true
        }
        override suspend fun explain(chosen: SourceBlock, blocks: List<SourceBlock>, requestId: String): ExplainResult =
            ExplainResult.Done(Explanation("第一段说光，第二段说水。【S1】【S2】",
                cited.mapIndexed { index, block -> Citation("S${index + 1}", block.id, block.page) }, 12))
    }

    private companion object {
        /** 章标题下面同一页两段：短到摘句不被截断，弹窗里的原文可以逐字比对。 */
        val TEXT = """
            # 1 Light

            Alpha light.

            Beta water.
        """.trimIndent()
    }
}
