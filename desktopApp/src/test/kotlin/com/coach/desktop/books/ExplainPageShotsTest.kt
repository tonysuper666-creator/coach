package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * TON-1766：一份带目录的 PDF，目录页和正文页各拍一张讲解面板，浅色、深色各一遍，放 `build/screens/ton-1766/`。
 * 目录页不给「讲解这一页」也不给「讲解这段」，只有一句灰字；正文页顶上一个「讲解这一页」，点了才讲。
 * 连上了不写「已连上」。PDF 是测试里现造的，文字全是自拟；讲解走本地假通道，不联网。
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class ExplainPageShotsTest {
    private val out: Path = Path.of("build/screens/ton-1766")

    @Test fun tocPageAndBodyPageInLightAndDark() {
        for (dark in listOf(false, true)) walk(dark)
    }

    private fun walk(dark: Boolean) {
        val root = Files.createTempDirectory("explain-page-shots-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(samplePdf(root.resolve("guide.pdf")))
        val blocks = StudyCoachCourse.blocks(book.id, book.pages)
        val consent = ExplainConsent(library.directory).apply { allow(book.id) }
        val mac = FakeMac(root.resolve("home"))
        var page by mutableStateOf(1)
        runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(1.5f)) {
            setContent {
                com.coach.ui.CoachTheme(dark) {
                    val colors = com.coach.ui.theme.CoachTheme.colors
                    Surface(Modifier.fillMaxSize().testTag("windows-host"), color = colors.bgCanvas) {
                        com.coach.ui.v7.Desk(crumb = book.title, showNavigation = false, hasMargin = false, status = "第 $page 页 · 讲解") {
                            Box(Modifier.fillMaxSize().padding(20.dp).background(colors.surface1)) {
                                BookExplainPanel(book, page, consent, mac)
                            }
                        }
                    }
                }
            }
            // 目录页：没有任何讲解入口，只有一句灰字。
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-page-note").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("explain-page-note").assertTextEquals(BookPageKind.Toc.note!!)
            onNodeWithTag("explain-page").assertDoesNotExist()
            onNodeWithTag("explain-button").assertDoesNotExist()
            assertTrue(onAllNodesWithText("已连上", substring = true).fetchSemanticsNodes().isEmpty())
            shot("toc-page", dark)

            // 正文页：顶上「讲解这一页」，标题不挂入口，每段正文各有「讲解这段」。没点就不发请求。
            page = 2; waitForIdle()
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasTestTag("explain-page") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val body = blocks.filter { it.page == 2 && !it.heading }
            assertEquals(3, body.size, "正文页三段")
            assertEquals(3, onAllNodesWithTag("explain-button").fetchSemanticsNodes().size)
            assertEquals(0, mac.lessons)
            shot("body-page", dark)

            onNodeWithTag("explain-page").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-answer").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, mac.lessons)
            assertEquals(body.map { it.id }, mac.sent.map { it.id }, "整页讲解只发这一页的正文")
            waitForIdle()
            shot("body-page-explained", dark)
        }
        root.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.shot(scene: String, dark: Boolean) {
        val file = out.resolve(if (dark) "dark" else "light").resolve("$scene.png")
        Files.createDirectories(file.parent)
        val image = onNodeWithTag("windows-host").captureToImage().toAwtImage()
        assertTrue(ImageIO.write(image, "png", file.toFile()))
    }

    private class FakeMac(home: Path) : BookExplainer(DesktopConnectionSettings(home = home, environment = { null })) {
        var lessons = 0
        var sent: List<SourceBlock> = emptyList()
        override suspend fun load() {
            identity = DesktopIdentity("http://mac.test:18791", PairedCredentials("test-device", "test-token"))
            loaded = true
        }
        override suspend fun explainLesson(blocks: List<SourceBlock>, bookPages: Int, requestId: String): ExplainResult {
            lessons++; sent = blocks
            return ExplainResult.Done(Explanation(
                "主旨：叶子靠光把水和二氧化碳变成糖，这一页讲的是这件事在哪里发生、靠什么发生。【S1】\n" +
                    "脉络：先说叶子的形状为什么薄而宽，再说叶绿体，最后说气孔怎样换气。【S2】【S3】\n" +
                    "难点：气孔白天开、热天中午会关，关了就进不来二氧化碳，所以光再强也长不快。【S3】",
                blocks.take(3).mapIndexed { index, block -> Citation("S${index + 1}", block.id, block.page) }, 12))
        }
    }

    private companion object {
        val TOC = listOf(
            "Contents",
            "1 Light and leaves .................... 2",
            "1.1 Why leaves are thin ............... 2",
            "1.2 Inside the green parts ............ 3",
            "2 Water on the move ................... 5",
            "2.1 Roots and soil .................... 6",
        )
        val BODY = listOf(
            listOf("Chapter 1 Light and leaves"),
            listOf("A leaf is thin and wide so that most of its cells sit close to the light.",
                "Sunlight that falls on the upper side reaches the working cells after passing",
                "through only a few layers, and very little of it is wasted on the way in."),
            listOf("Inside those cells are small green bodies that catch the light and use it",
                "to join water and carbon dioxide into sugar. The sugar is moved away to the",
                "stem and roots, where the plant stores it or burns it for growth."),
            listOf("Air comes in through tiny pores on the underside of the leaf. On a hot",
                "afternoon the pores close to save water, so even strong light cannot speed",
                "the work up until the evening air is cool enough for them to open again."),
        )

        /** 两页：第 1 页目录，每条之间空一行；第 2 页一个章标题、三段正文。 */
        fun samplePdf(file: Path): Path {
            PDDocument().use { pdf ->
                fun page(paragraphs: List<List<String>>) {
                    val sheet = PDPage(); pdf.addPage(sheet)
                    PDPageContentStream(pdf, sheet).use { out ->
                        out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                        out.newLineAtOffset(60f, 720f)
                        for ((index, lines) in paragraphs.withIndex()) {
                            if (index > 0) out.newLineAtOffset(0f, -40f)
                            for ((row, line) in lines.withIndex()) {
                                if (row > 0) out.newLineAtOffset(0f, -14f)
                                out.showText(line)
                            }
                        }
                        out.endText()
                    }
                }
                page(TOC.map { listOf(it) })
                page(BODY)
                pdf.save(file.toFile())
            }
            return file
        }
    }
}
