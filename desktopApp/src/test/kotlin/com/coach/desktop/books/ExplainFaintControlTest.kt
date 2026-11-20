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
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * TON-1659 + TON-1766：正常浏览正文时每行不再常驻「讲解这段」，节标题旁保留淡淡的「讲讲这一节」
 *（平时 faint，悬停浮成纸面按钮且不挪位）；从阅读器选进某一段时该段按钮突出显示，
 * 等待、失败与同段重试的排版稳定不跳动。
 */
@OptIn(ExperimentalTestApi::class)
class ExplainFaintControlTest {
    /** 第一段（有正文的那段）的 testTag，每一轮 fixture 都会换。 */
    private var paragraphTag = ""
    private var blockTag = ""
    /** 同一页第 2 段的正文，用来量它有没有被第 1 段的状态行推走。 */
    private var secondTag = ""
    /** 这一轮用的假服务：测试可以核对它收到了哪几次请求。 */
    private lateinit var mac: FakeMac

    @Test fun faintTextSitsOnTheParagraphsFirstBaselineInBothThemes() {
        for (dark in listOf(false, true)) withPanel(dark, selected = false) { dark ->
            val faint = colors(dark).fgTertiary
            assertEquals(0, onAllNodesWithTag("explain-button").fetchSemanticsNodes().size, "正文各行不再挂常驻「讲解这段」")
            val trigger = onNodeWithTag("explain-section-trigger")
            assertEquals(faint, nodeColor(trigger), "默认「讲讲这一节」应是 faint 文字")
            val before = trigger.fetchSemanticsNode().boundsInRoot
            assertEquals(colors(dark).surface1, pixel(before.left + 4f, before.center.y), "默认不该有纸面底（$dark）")

            trigger.performMouseInput { moveTo(center) }
            waitForIdle()
            assertEquals(colors(dark).fgPrimary, nodeColor(trigger), "鼠标悬停时是正常字色")
            assertNotEquals(colors(dark).surface1, pixel(before.left + 4f, before.center.y), "悬停时浮出纸面底")
            assertEquals(before.top, trigger.fetchSemanticsNode().boundsInRoot.top, .5f,
                "鼠标经过不能把这一行挪位")

            trigger.performMouseInput { exit() }
            waitForIdle()
            assertEquals(faint, nodeColor(trigger), "鼠标离开回到 faint")
        }
    }

    @Test fun passageChosenInTheReaderStartsAsAButtonWithoutHover() = withPanel(false, selected = true) { dark ->
        assertEquals(colors(dark).fgPrimary, labelColor(), "从阅读器选进来的这一段，按钮应当已经突出")
        val label = button().fetchSemanticsNode().boundsInRoot
        assertNotEquals(colors(dark).surface1, pixel(label.left + 4f, label.center.y), "选中的段落上纸面底可见")
    }

    @Test fun unpairedKeepsTheFaintLabelAndNoClick() = withPanel(false, selected = false, paired = false) { _ ->
        val trigger = onNodeWithTag("explain-section-trigger")
        assertNotNull(trigger.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled), "没配对时「讲讲这一节」不可点")
        assertNotEquals(colors(false).fgPrimary, nodeColor(trigger), "不可点时不抢正文")
    }

    /**
     * 选段请求讲解、断连失败、再在同段重试：请求前、等待中、失败后、重试等待中这四个时刻，
     * 按钮所在块的上沿不跳动；失败后「再试一次」还能点且仍是同一个段 ID。
     */
    @Test fun theSecondParagraphStaysPutThroughWaitFailureAndInPlaceRetry() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            val first = CompletableDeferred<ExplainResult>()
            val retry = CompletableDeferred<ExplainResult>()
            withPanel(dark, selected = true, gates = listOf(first, retry)) { _ ->
                val before = top(paragraphTag)
                button().performClick()
                waitForIdle()
                assertFalse(enabled(), "点下去就进等待中")
                val waiting = top(paragraphTag)
                assertEquals(before, waiting, 1f, "等待中正文被推动了（$theme）")

                first.complete(ExplainResult.Refused(ExplainTrouble.UNREACHABLE, ExplainTrouble.Unreachable))
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-retry").fetchSemanticsNodes().isNotEmpty() }
                val failed = top(paragraphTag)
                assertEquals(before, failed, 1f, "失败后正文被推动了（$theme）")
                assertNull(onNodeWithTag("explain-retry").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled),
                    "失败后「再试一次」要还能点（$theme）")

                onNodeWithTag("explain-retry").performClick()
                waitForIdle()
                val retrying = top(paragraphTag)
                assertEquals(before, retrying, 1f, "同段重试时正文被推动了（$theme）")
                assertEquals(2, mac.asked.size, "重试要重新走一次请求（$theme）")
                assertEquals(mac.asked[0].id, mac.asked[1].id, "重试要在当前段发起（$theme）")

                println("D3_POSITION theme=$theme before=$before waiting=$waiting failed=$failed retrying=$retrying " +
                    "sameBlock=${mac.asked[0].id == mac.asked[1].id} retryClickable=true")

                retry.complete(mac.answer())
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-answer").fetchSemanticsNodes().isNotEmpty() }
                val citation = onNodeWithTag("explain-citation").fetchSemanticsNode().config[SemanticsProperties.Text]
                assertTrue(citation.any { it.text.startsWith("出处 1") }, "重试成功后讲解带出处正常出现（$theme）：$citation")
            }
        }
    }

    private fun withPanel(dark: Boolean, selected: Boolean = false, paired: Boolean = true,
        gates: List<CompletableDeferred<ExplainResult>> = emptyList(),
        body: ComposeUiTest.(Boolean) -> Unit) {
        val root = Files.createTempDirectory("explain-faint-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("light.md"), TEXT))
        val blocks = StudyCoachCourse.blocks(book.id, book.pages)
        val page = blocks.first { !it.heading }.page
        val chosen = blocks.first { it.page == page && !it.heading }
        val second = blocks.first { it.page == page && !it.heading && it.id != chosen.id }
        paragraphTag = "explain-paragraph-${chosen.id}"
        blockTag = "explain-block-${chosen.id}"
        secondTag = "explain-paragraph-${second.id}"
        val consent = ExplainConsent(library.directory).apply { allow(book.id) }
        mac = FakeMac(root.resolve("home"), paired, gates)
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent {
                CoachTheme(dark) {
                    Box(Modifier.fillMaxSize().background(colors(dark).surface1)) {
                        BookExplainPanel(book, page, consent, mac, selection = chosen.takeIf { selected })
                    }
                }
            }
            val expectedTag = if (selected) "explain-button" else "explain-section-trigger"
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(expectedTag).fetchSemanticsNodes().isNotEmpty() }
            body(dark)
        }
        root.toFile().deleteRecursively()
    }

    private fun colors(dark: Boolean) = if (dark) CoachColors.dark() else CoachColors.light()

    private fun ComposeUiTest.button() =
        onNode(hasTestTag("explain-button") and hasAnyAncestor(hasTestTag(blockTag)))

    private fun ComposeUiTest.labelColor(): Color = nodeColor(button())

    private fun nodeColor(node: SemanticsNodeInteraction): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
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

    private fun ComposeUiTest.top(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private class FakeMac(home: Path, private val paired: Boolean,
        private val gates: List<CompletableDeferred<ExplainResult>> = emptyList()) :
        BookExplainer(DesktopConnectionSettings(home = home, environment = { null })) {
        /** 每次请求问的段落，按调用顺序；D3 的用例用它核对重试仍是同一段。 */
        val asked = mutableListOf<SourceBlock>()
        override suspend fun load() {
            if (paired) identity = DesktopIdentity("http://mac.test:18791", PairedCredentials("test-device", "test-token"))
            loaded = true
        }
        /** [gates] 按请求顺序给出：测试决定那次请求什么时候回来、以什么结果回来，用来量等待中、失败后和重试时的排版。 */
        override suspend fun explain(chosen: SourceBlock, blocks: List<SourceBlock>, requestId: String): ExplainResult {
            asked += chosen
            return gates.getOrNull(asked.lastIndex)?.await() ?: answer()
        }
        /** 正常讲解的回复，引用指回最后问的那一段。 */
        fun answer() = ExplainResult.Done(Explanation("这一段在说光被叶绿体用掉。【S1】",
            listOf(Citation("S1", asked.last().id, asked.last().page)), 12))
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
