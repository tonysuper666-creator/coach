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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * TON-1659 第 1 项：出处不写块编号，写页码和段号。
 * 同一页两段：两条出处的段号、各自的摘句、点开后的标题和原文，必须逐一对应。
 */
@OptIn(ExperimentalTestApi::class)
class ExplainCitationTest {
    @Test fun twoParagraphsOnOnePageEachNameAndOpenTheirOwnParagraph() {
        val root = Files.createTempDirectory("explain-citation-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("two.md"), TEXT))
        val blocks = StudyCoachCourse.blocks(book.id, book.pages)
        val page = blocks.first { !it.heading }.page
        val paragraphs = blocks.filter { it.page == page && !it.heading }
        assertEquals(listOf("Alpha light.", "Beta water."), paragraphs.map { it.text }, "同一页两段正文")
        val consent = ExplainConsent(library.directory).apply { allow(book.id) }
        val mac = FakeMac(root.resolve("home"), paragraphs)

        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent {
                CoachTheme(false) {
                    Box(Modifier.fillMaxSize().background(CoachColors.light().surface1)) {
                        BookExplainPanel(book, page, consent, mac)
                    }
                }
            }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-button").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag("explain-button")[0].performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-citation").fetchSemanticsNodes().size == 2 }

            // 链接上就是页码 + 段号 + 这一段自己的开头，没有块编号。
            onAllNodesWithTag("explain-citation")[0].assertTextEquals("出处 1 · 第 1 页 · 第 1 段 · “Alpha light.”")
            onAllNodesWithTag("explain-citation")[1].assertTextEquals("出处 2 · 第 1 页 · 第 2 段 · “Beta water.”")
            val shown = onAllNodesWithTag("explain-citation").fetchSemanticsNodes()
                .flatMap { it.config[SemanticsProperties.Text] }.joinToString(" ") { it.text }
            assertFalse(shown.contains(paragraphs[0].id), "出处里不能露出块编号：$shown")

            // 点第 1 条：标题、原文都是第 1 段。
            onAllNodesWithTag("explain-citation")[0].performClick()
            waitForIdle()
            onNodeWithTag("explain-source-title").assertTextEquals("原文 · 第 1 页 · 第 1 段")
            onNodeWithTag("explain-source-text").assertTextEquals("Alpha light.")
            onNodeWithTag("explain-source-close").performClick()
            waitForIdle()

            // 点第 2 条：标题、原文都换到第 2 段，不会还停在上一段。
            onAllNodesWithTag("explain-citation")[1].performClick()
            waitForIdle()
            onNodeWithTag("explain-source-title").assertTextEquals("原文 · 第 1 页 · 第 2 段")
            onNodeWithTag("explain-source-text").assertTextEquals("Beta water.")
        }
        root.toFile().deleteRecursively()
    }

    /** 服务端给两条出处：同页第 1 段和第 2 段。 */
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
