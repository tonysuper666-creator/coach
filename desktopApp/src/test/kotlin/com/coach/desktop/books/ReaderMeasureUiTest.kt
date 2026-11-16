package com.coach.desktop.books

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.desktop.WindowsTextbookHost
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * 第 12 刀：阅读器正文栏按 V7 `measure: 33em` 限宽并在纸面内居中。
 * 用合成的一整段长文（导入时按文字块切成多页）核对：两种常见屏和窄窗口、Aa 小/中/大下
 * 栏宽与居中、滚到底能读到最后一个字、翻页回到顶部，以及出处打开后落在被引的那一块。
 */
@OptIn(ExperimentalTestApi::class)
class ReaderMeasureUiTest {
    private data class Window(val name: String, val width: Float, val height: Float, val density: Float)

    // 1920×1080、2560×1440 按 150% 缩放（与 V7 对照截图同口径），再加一个比正文栏还窄的窗口。
    private val windows = listOf(
        Window("1920x1080", 1920f, 1080f, 1.5f),
        Window("2560x1440", 2560f, 1440f, 1.5f),
        Window("narrow", 900f, 720f, 1.5f),
    )

    private fun longParagraphBook(root: Path): Path {
        val sentences = StringBuilder()
        var i = 1
        while (sentences.length < 9_000) sentences.append("第${i++}句：这是一整段合成长文，用来核对正文栏收窄以后仍然能从头读到尾。")
        sentences.append("全段最后一句在这里结束。")
        return root.resolve("long-paragraph.md").also { Files.writeString(it, "# 长段落合成样例\n\n$sentences\n") }
    }

    private fun ComposeUiTest.ready(controller: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && condition(controller.state.value) }
        assertNull(controller.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag("reader-text").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    private fun ComposeUiTest.scrollValue(): Pair<Float, Float> =
        onNodeWithTag("reader-scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].let { it.value() to it.maxValue() }

    private fun ComposeUiTest.readerText(): String =
        onNodeWithTag("reader-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().text

    /** 栏宽 = min(33em × Aa, 纸面可用宽)，栏中线与纸面中线重合。 */
    private fun ComposeUiTest.assertColumn(scale: Float, label: String, density: Float): Boolean {
        val page = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
        val text = onNodeWithTag("reader-text").fetchSemanticsNode()
        val measure = CoachV7.Size.body.value * 33f * scale * density
        val available = page.width - 2 * 22.dp.value * density
        assertEquals(minOf(measure, available), text.size.width.toFloat(), 1.5f, "$label：正文栏宽")
        assertEquals(page.center.x, text.positionInRoot.x + text.size.width / 2f, 1.5f, "$label：正文栏在纸面内居中")
        return available < measure
    }

    /** 滚到底：排版没有截断，最后一行就是原文最后一个字，并且能露在纸面里。 */
    private fun ComposeUiTest.assertReadsToEnd(label: String, density: Float) {
        val laid = layout()
        assertFalse(laid.hasVisualOverflow, "$label：正文没有被裁")
        assertEquals(laid.layoutInput.text.length, laid.getLineEnd(laid.lineCount - 1), "$label：最后一行排到原文末尾")
        onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_000_000f) }
        waitForIdle()
        val (value, max) = scrollValue()
        assertTrue(max > 0f, "$label：长段落超过一屏，需要滚动")
        assertEquals(max, value, 0.5f, "$label：能滚到底")
        val page = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
        val text = onNodeWithTag("reader-text").fetchSemanticsNode()
        val bottom = text.positionInRoot.y + text.size.height
        assertTrue(bottom <= page.bottom - 22.dp.value * density + 1f && bottom > page.top, "$label：最后一行露在纸面里（$bottom / $page）")
    }

    private fun ComposeUiTest.typeSize(label: String) {
        onNodeWithTag("v7-type-menu").performClick(); waitForIdle()
        onNodeWithTag("v7-type-$label").performClick(); waitForIdle()
    }

    @Test fun longParagraphReadsInFullAtEveryTypeSizeAndWindowAndPagesTurnFromTheTop() {
        for (window in windows) {
            val root = Files.createTempDirectory("reader-measure-${window.name}-")
            val library = TextbookLibrary(root.resolve("library"))
            val imported = library.importBook(longParagraphBook(root))
            // 导入时按固定文字块分页：一整段长文切成多页，页码与栏宽无关。
            assertEquals(listOf(1, 2, 3), imported.pages.map { it.number }, "合成长段落切成三页")
            library.save(imported.copy(promptSeen = true))
            val pages = imported.pages.map { it.text }
            val controller = TextbookController(library)
            var narrowSeen = false
            runSkikoComposeUiTest(size = Size(window.width, window.height), density = Density(window.density)) {
                setContent { WindowsTextbookHost(controller) { null } }
                ready(controller) { it.book?.id == imported.id }
                onNodeWithTag("home-read").performClick()
                ready(controller) { it.screen == BookScreen.READER && it.prompt == null && it.page == 1 }
                assertEquals(pages[0], readerText(), "${window.name}：第 1 页原文一字不少")

                for ((label, scale) in listOf("小" to .9f, "大" to 1.15f, "中" to 1f)) {
                    typeSize(label)
                    narrowSeen = assertColumn(scale, "${window.name} Aa $label", window.density) || narrowSeen
                    assertReadsToEnd("${window.name} Aa $label", window.density)
                    assertEquals(pages[0], readerText(), "${window.name} Aa $label：原文不变")
                }

                // 翻页：下一页从顶部读起，原文对得上已存页；滚到底同样读得完。
                onNodeWithTag("next-page").performClick()
                ready(controller) { it.page == 2 && it.book?.readingPage == 2 }
                assertEquals(pages[1], readerText(), "${window.name}：第 2 页原文")
                assertEquals(0f, scrollValue().first, "${window.name}：换页回到顶部")
                if (!narrowSeen) {
                    // 宽窗口里栏外两侧也属于这张纸：鼠标停在左侧空白处滚轮照样能滚。
                    val page = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
                    onNodeWithTag("reader-page").performMouseInput {
                        moveTo(Offset(12f * window.density, page.height / 2f)); scroll(6f)
                    }
                    waitForIdle()
                    assertTrue(scrollValue().first > 0f, "${window.name}：栏外滚轮也能滚动正文")
                }
                assertReadsToEnd("${window.name} 第 2 页", window.density)
                onNodeWithTag("next-page").performClick()
                ready(controller) { it.page == 3 && it.book?.readingPage == 3 }
                assertEquals(pages[2], readerText(), "${window.name}：第 3 页原文")
                assertTrue(readerText().trimEnd().endsWith("全段最后一句在这里结束。"))
                assertEquals(0f, scrollValue().first, "${window.name}：再次换页回到顶部")
                onNodeWithTag("next-page").assertIsNotEnabled()
                onNodeWithTag("previous-page").performClick()
                ready(controller) { it.page == 2 && it.book?.readingPage == 2 }
                assertEquals(pages[1], readerText())
                assertEquals(0f, scrollValue().first, "${window.name}：往回翻也从顶部读起")
            }
            assertEquals(2, TextbookLibrary(library.directory).book(imported.id).readingPage, "${window.name}：书签按原页码保存")
            if (window.name == "narrow") assertTrue(narrowSeen, "窄窗口里正文栏让位给可用宽度")
            else assertFalse(narrowSeen, "${window.name}：可用宽度够，正文栏按 33em 收住")
            println("PASS measure ${window.name}: pages=3, Aa 小/大/中 reads to end, turns reset to top, narrow=$narrowSeen")
        }
    }

    @Test fun sourceOnALongPageOpensAtTheCitedBlockWithItsOffsets() {
        val root = Files.createTempDirectory("reader-measure-source-")
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(longParagraphBook(root))
        library.save(imported.copy(learning = true, readingPage = 1, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.learn(LearningPath.READ_AND_LEARN) }
        // 第 2 页一整段被切成两块，引第二块：它在这一页的后半，首屏看不到。
        val cited = StudyCoachCourse.blocks(imported.id, imported.pages).filter { it.page == 2 && !it.heading }.sortedBy { it.start }.last()
        assertTrue(cited.start > 0, "被引的是第 2 页后半的那一块")
        val support = object : BookLearningSupport {
            override suspend fun explain(request: BookExplanationRequest): BookLearningReply =
                BookLearningReply.Text("A synthetic explanation", listOf(BookPassage.from(library.book(request.book), cited)))
            override suspend fun ask(request: BookPassageQuestion) = BookLearningReply.Unavailable("unused")
        }
        runSkikoComposeUiTest(size = Size(2560f, 1440f), density = Density(1.5f)) {
            setContent { TextbookWorkspace(controller, loadOnMount = false, support = support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.READER && it.page == 2 }
            val marked = onNodeWithTag("reader-text").fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertEquals(imported.pages[1].text, marked.text, "出处页原文一字不少")
            val span = marked.spanStyles.single { it.item.background != androidx.compose.ui.graphics.Color.Unspecified }
            assertEquals(cited.start to cited.end, span.start to span.end, "标记落在被引那一块的原偏移上")
            assertEquals(cited.text, marked.text.substring(span.start, span.end))
            assertColumn(1f, "出处页", 1.5f)
            // 打开出处就滚到被引那一块：标记的第一行在纸面可见范围内。
            val laid = layout()
            val page = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
            val text = onNodeWithTag("reader-text").fetchSemanticsNode()
            val markTop = text.positionInRoot.y + laid.getLineTop(laid.getLineForOffset(span.start))
            assertTrue(scrollValue().first > 0f, "出处在首屏以下，阅读器已滚过去")
            assertTrue(markTop >= page.top - 1f && markTop < page.bottom, "被引那一块的第一行露在纸面里（$markTop / $page）")
            // 改字号后偏移不动。
            typeSize("大")
            val enlarged = onNodeWithTag("reader-text").fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertEquals(marked.text, enlarged.text)
            assertEquals(marked.spanStyles.map { it.start to it.end }, enlarged.spanStyles.map { it.start to it.end }, "Aa 大：标记偏移不变")
            assertColumn(1.15f, "出处页 Aa 大", 1.5f)
            typeSize("中")
        }
        println("PASS measure source: page=2, mark=${cited.start}..${cited.end}, scrolled into view")
    }
}
