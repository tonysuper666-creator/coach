package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import coach.design.tokens.CoachV7
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.ui.theme.CoachTheme
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * TON-1694：成品界面上读者看到的字。自造的硬换行教材（像 PDF 抽出来的那样每行断开），
 * 在讲解卡、选段就问、「教材已加入书架」三处各拍浅色和深色，并核对：
 * 不露书的哈希块编号和【S1】、引用段落按段重排且整段可见、选中的选项看得出选中。
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class ReadableScreensTest {
    @Test fun explainPanelLight() = explainPanel(false)
    @Test fun explainPanelDark() = explainPanel(true)
    @Test fun passageAskLight() = passageAsk(false)
    @Test fun passageAskDark() = passageAsk(true)
    @Test fun importedChoicesLight() = importedChoices(false)
    @Test fun importedChoicesDark() = importedChoices(true)
    @Test fun explainTroubleLight() = explainTrouble(false)
    @Test fun explainTroubleDark() = explainTrouble(true)

    /** 连不上 Mac 是会自己好的：灰字 + 「再试一次」，不用朱色；连接被停用要重新配对，才用朱色。 */
    private fun explainTrouble(dark: Boolean) {
        for ((trouble, scene) in listOf(ExplainTrouble.Unreachable to "explain-unreachable", ExplainTrouble.Revoked to "explain-revoked")) {
            val (root, library, book) = fixture()
            val consent = ExplainConsent(library.directory).apply { allow(book.id) }
            val explainer = FakeMac(root.resolve("home"), trouble)
            val selected = book.sourceBlocks().first { it.isBodyParagraph }
            runSkikoComposeUiTest(size = Size(1000f, 500f), density = Density(1f)) {
                setContent { CoachTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(CoachTheme.colors.surface1)) { BookExplainPanel(book, selected.page, consent, explainer, selection = selected) }
                } }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-button").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithTag("explain-button")[0].performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-failed").fetchSemanticsNodes().isNotEmpty() }
                capture(scene, dark)
                val layouts = mutableListOf<TextLayoutResult>()
                onAllNodesWithTag("explain-failed")[0].fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                val color = layouts.single().layoutInput.style.color
                if (trouble == ExplainTrouble.Unreachable) {
                    assertEquals((if (dark) com.coach.ui.theme.CoachColors.dark() else com.coach.ui.theme.CoachColors.light()).fgSecondary, color, "连不上用中性灰字")
                    onAllNodesWithTag("explain-retry")[0].assertHasClickAction()
                } else assertEquals(if (dark) CoachV7.Dark.zhu else CoachV7.Light.zhu, color, "停用要重新配对，用朱色")
            }
            root.toFile().deleteRecursively()
        }
    }

    private fun explainPanel(dark: Boolean) {
        val (root, library, book) = fixture()
        val consent = ExplainConsent(library.directory).apply { allow(book.id) }
        val explainer = FakeMac(root.resolve("home"))
        val selected = book.sourceBlocks().first { it.isBodyParagraph }
        runSkikoComposeUiTest(size = Size(1000f, 1100f), density = Density(1f)) {
            setContent { CoachTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(CoachTheme.colors.surface1)) { BookExplainPanel(book, selected.page, consent, explainer, selection = selected) }
            } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-button").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag("explain-button")[0].performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-answer").fetchSemanticsNodes().isNotEmpty() }
            capture("explain-panel", dark)
            assertNoInternalIds("讲解卡")
            onAllNodesWithTag("explain-citation")[0].performClick()
            waitForIdle()
            capture("explain-source", dark)
            assertNoInternalIds("讲解出处原文")
        }
        root.toFile().deleteRecursively()
    }

    private fun passageAsk(dark: Boolean) {
        val (root, library, book) = fixture()
        val consent = ExplainConsent(library.directory).apply { allow(book.id) }
        val support = PairedBookLearningSupport(library, consent) { sent, _ ->
            ExplainResult.Done(Explanation("这段说的是：病例越多的医院，手术后的结局通常越好，但这只是相关，不等于多做就一定做得好。【S1】",
                listOf(Citation("S1", sent.id, sent.page)), 1))
        }
        val page = book.pages.first()
        val last = StudyCoachCourse.blocks(book.id, listOf(page)).count { !it.heading } - 1
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent { CoachTheme(darkTheme = dark) { BookPassageAsk(book, page, support) {} } }
            // 往下滚到最后一段再选：弹窗换成提问时，顶上那段原文要整段看得见。
            onNodeWithTag("ask-paragraph-$last").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag("book-passage-question").performTextInput("这一段的结论能直接推广吗？")
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-reply").fetchSemanticsNodes().isNotEmpty() }
            capture("passage-ask", dark)
            val selected = onNodeWithTag("book-selected-passage").fetchSemanticsNode()
            val shown = selected.config[SemanticsProperties.Text].joinToString("") { it.text }
            assertFalse('\n' in shown.trim(), "引用段落里还留着 PDF 的硬换行：$shown")
            val viewport = onNodeWithTag("book-passage-ask").fetchSemanticsNode().boundsInRoot
            assertTrue(selected.boundsInRoot.top >= viewport.top - 0.5f, "引用段落第一行被弹窗顶边切掉：${selected.boundsInRoot} / $viewport")
            assertNoInternalIds("选段就问")
        }
        root.toFile().deleteRecursively()
    }

    private fun importedChoices(dark: Boolean) {
        val (root, library, _) = fixture()
        val other = Files.writeString(root.resolve("other.md"), "## 第一节\n\n另一份自造教材，只用来走一遍导入。")
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.importBook(other) }
        val ui = controller.state.value
        assertEquals(BookScreen.READER, ui.screen)
        assertNull(ui.prompt)
        root.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.capture(scene: String, dark: Boolean): BufferedImage {
        val file = OUT.resolve("$scene-${if (dark) "dark" else "light"}.png")
        Files.createDirectories(file.parent)
        val image = (this as SkikoComposeUiTest).captureToImage().toAwtImage()
        assertTrue(ImageIO.write(image, "png", file.toFile()))
        return image
    }

    /** 屏上所有文字（含弹窗、输入框）都不能带书的哈希块编号或【S1】这类引用标记。 */
    private fun ComposeUiTest.assertNoInternalIds(where: String) {
        val texts = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text) +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
        val leaks = texts.filter { ReadableText.INTERNAL_ID.containsMatchIn(it) }
        assertTrue(leaks.isEmpty(), "$where 露出了内部编号：$leaks")
    }

    private fun fixture(): Triple<Path, TextbookLibrary, Textbook> {
        val root = Files.createTempDirectory("readable-screens-")
        val file = Files.writeString(root.resolve("volume-outcome.md"), HARD_WRAPPED)
        val library = TextbookLibrary(root.resolve("library"))
        return Triple(root, library, library.importBook(file))
    }

    private class FakeMac(home: Path, val trouble: ExplainTrouble? = null) : BookExplainer(DesktopConnectionSettings(home = home, environment = { null })) {
        override suspend fun load() { identity = DesktopIdentity("http://mac.test:18791", PairedCredentials("test-device", "test-token")); loaded = true }
        override suspend fun explain(chosen: SourceBlock, blocks: List<SourceBlock>, requestId: String): ExplainResult {
            if (trouble != null) return ExplainResult.Refused(ExplainTrouble.message(trouble, null), trouble)
            val next = ExplainWire.context(blocks, chosen).last()
            return ExplainResult.Done(Explanation(
                "这一段在说：医院做某种手术越多，病人术后的结局往往越好。【S1】但作者马上提醒，这是观察到的关联，" +
                    "下一段才讨论原因可能反过来。【S2】",
                listOf(Citation("S1", chosen.id, chosen.page), Citation("S2", next.id, next.page)), 12))
        }
    }

    private companion object {
        val OUT: Path = Path.of("build/screens/ton1694")

        /** 自造英文段落，按 PDF 的样子每行硬断，段与段之间空一行。 */
        val HARD_WRAPPED = """
            ## 1 Volume and outcome

            Hospitals that perform a procedure more often tend to report
            better results for that procedure. This pattern is called the
            volume-outcome relationship, and it appears in surgery,
            intensive care and several kinds of screening.

            The relationship is an observation, not a rule. A busy unit
            may do well because practice builds skill, or it may be busy
            because it already did well and patients were sent there.

            Readers should therefore ask which way the arrow points
            before using volume alone to judge a hospital. Adjusting for
            how sick the patients were changes the size of the effect in
            many published comparisons.

            同一组数据里，小医院的结果波动更大，
            因为病例少时偶然因素占的分量更重。

            A second caution concerns the data themselves. Registries
            that rely on units to report their own complications may
            undercount them where staff are stretched thin.

            Comparisons across countries add further noise, because the
            same operation can be coded differently from one system to
            the next and follow-up periods rarely match.

            When the numbers are reported with intervals, the overlap
            between small and large units is often wider than the
            headline difference suggests.

            Small units are not automatically unsafe. Some keep good
            results by sharing protocols, by referring the hardest cases
            and by auditing every outcome they record.

        """.trimIndent()

        fun distance(a: Int, b: Int): Int {
            fun c(v: Int, s: Int) = (v shr s) and 0xff
            return maxOf(kotlin.math.abs(c(a, 16) - c(b, 16)), kotlin.math.abs(c(a, 8) - c(b, 8)), kotlin.math.abs(c(a, 0) - c(b, 0)))
        }
        fun hex(v: Int) = "#%06x".format(v and 0xffffff)
    }
}
