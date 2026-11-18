package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
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
 * TON-1659 第 3 项：每段的「讲解这段」平时只是首行右边一行 faint 小字，和正文第一行同一基线；
 * 鼠标扫到这一段、或这一段就是从阅读器选进来的，才浮成纸面按钮。两种状态的盒子一样大，段落不因鼠标挪位。
 */
@OptIn(ExperimentalTestApi::class)
class ExplainFaintControlTest {
    /** 第一段（有正文的那段）的 testTag，每一轮 fixture 都会换。 */
    private var paragraphTag = ""
    private var blockTag = ""

    @Test fun faintTextSitsOnTheParagraphsFirstBaselineInBothThemes() {
        for (dark in listOf(false, true)) withPanel(dark) { dark ->
            val faint = colors(dark).fgTertiary
            assertEquals(faint, labelColor(), "默认应是 faint 文字，不是纸面按钮")
            val paragraph = onNodeWithTag(paragraphTag).fetchSemanticsNode().boundsInRoot
            val label = button().fetchSemanticsNode().boundsInRoot
            assertEquals(firstBaseline(onNodeWithTag(paragraphTag).fetchSemanticsNode()), firstBaseline(button().fetchSemanticsNode()), 1.5f, "和段落首行基线对齐")
            assertTrue(label.top >= paragraph.top - 2f && label.top < paragraph.top + 20f,
                "「讲解这段」应贴着第一行，而不是掉到段落下面：label=${label.top} paragraph=${paragraph.top}")
            assertEquals(colors(dark).surface1, pixel(label.left + 4f, label.center.y), "默认不该有纸面底（$dark）")

            onNodeWithTag(paragraphTag).performMouseInput { moveTo(center) }
            waitForIdle()
            assertEquals(colors(dark).fgPrimary, labelColor(), "鼠标在这一段上时是正常字色")
            assertNotEquals(colors(dark).surface1, pixel(label.left + 4f, label.center.y), "悬停时浮出纸面底")
            assertEquals(label.top, button().fetchSemanticsNode().boundsInRoot.top, .5f,
                "鼠标经过不能把这一行挪位")
            assertEquals(paragraph.top, onNodeWithTag(paragraphTag).fetchSemanticsNode().boundsInRoot.top, .5f)

            onNodeWithTag(paragraphTag).performMouseInput { exit() }
            waitForIdle()
            assertEquals(faint, labelColor(), "鼠标离开回到 faint")
        }
    }

    @Test fun passageChosenInTheReaderStartsAsAButtonWithoutHover() = withPanel(false, selected = true) { dark ->
        assertEquals(colors(dark).fgPrimary, labelColor(), "从阅读器选进来的这一段，按钮应当已经突出")
        val label = button().fetchSemanticsNode().boundsInRoot
        assertNotEquals(colors(dark).surface1, pixel(label.left + 4f, label.center.y), "选中的段落上纸面底可见")
    }

    @Test fun unpairedKeepsTheFaintLabelAndNoClick() = withPanel(false, paired = false) { _ ->
        assertFalse(enabled(), "没配对时讲解这段不可点")
        assertNotEquals(colors(false).fgPrimary, labelColor(), "不可点时不抢正文")
    }

    private fun withPanel(dark: Boolean, selected: Boolean = false, paired: Boolean = true,
        body: ComposeUiTest.(Boolean) -> Unit) {
        val root = Files.createTempDirectory("explain-faint-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("light.md"), TEXT))
        val blocks = StudyCoachCourse.blocks(book.id, book.pages)
        val page = blocks.first { !it.heading }.page
        val chosen = blocks.first { it.page == page && !it.heading }
        paragraphTag = "explain-paragraph-${chosen.id}"
        blockTag = "explain-block-${chosen.id}"
        val consent = ExplainConsent(library.directory).apply { allow(book.id) }
        val mac = FakeMac(root.resolve("home"), paired)
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent {
                CoachTheme(dark) {
                    Box(Modifier.fillMaxSize().background(colors(dark).surface1)) {
                        BookExplainPanel(book, page, consent, mac, selection = chosen.takeIf { selected })
                    }
                }
            }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-button").fetchSemanticsNodes().isNotEmpty() }
            body(dark)
        }
        root.toFile().deleteRecursively()
    }

    private fun colors(dark: Boolean) = if (dark) CoachColors.dark() else CoachColors.light()

    private fun ComposeUiTest.button() =
        onNode(hasTestTag("explain-button") and hasAnyAncestor(hasTestTag(blockTag)))

    private fun ComposeUiTest.labelColor(): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        button().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single().layoutInput.style.color
    }

    /** 一段文字真正画在屏幕上的第一条基线：节点顶 + 上下内边距 + 排版里的 firstBaseline。 */
    private fun firstBaseline(node: SemanticsNode): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        val inset = (node.boundsInRoot.height - layout.size.height) / 2f
        return node.boundsInRoot.top + inset + layout.firstBaseline
    }

    private fun ComposeUiTest.pixel(x: Float, y: Float) = onRoot().captureToImage().toPixelMap()[x.toInt(), y.toInt()]

    private fun ComposeUiTest.enabled() =
        button().fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null

    private class FakeMac(home: Path, private val paired: Boolean) :
        BookExplainer(DesktopConnectionSettings(home = home, environment = { null })) {
        override suspend fun load() {
            if (paired) identity = DesktopIdentity("http://mac.test:18791", PairedCredentials("test-device", "test-token"))
            loaded = true
        }
        override suspend fun explain(chosen: SourceBlock, blocks: List<SourceBlock>, requestId: String): ExplainResult =
            ExplainResult.Done(Explanation("这一段在说光被叶绿体用掉。【S1】",
                listOf(Citation("S1", chosen.id, chosen.page)), 12))
    }

    private companion object {
        /** 一段够长的自造英文正文，窄面板里会折成好几行，才看得出按钮贴的是第一行。 */
        val TEXT = """
            # 1 Light

            Plants use light to make sugars through photosynthesis, a process that happens inside
            chloroplasts and turns carbon dioxide and water into glucose while releasing oxygen into
            the air. The reaction is not instant: it depends on how much light reaches the leaf, how
            warm the air is, and whether the plant has enough water to keep its pores open. A leaf in
            deep shade can still make sugars, but slowly.

            A second paragraph gives the page more than one 「讲解这段」, so the test can tell the
            first paragraph's label apart from the second one.
        """.trimIndent()
    }
}
