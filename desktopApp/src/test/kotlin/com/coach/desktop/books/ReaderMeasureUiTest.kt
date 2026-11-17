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
 * 栏宽与居中、逐页重建到最后一个字、翻页从版心开始，以及出处打开后落在被引的那一块。
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

    private fun ComposeUiTest.visible() = listOf("reader-text", "reader-text-right").flatMap {
        onAllNodesWithTag(it).fetchSemanticsNodes()
    }

    private fun ComposeUiTest.assertColumn(scale: Float, label: String, density: Float): Boolean {
        val spread = onAllNodesWithTag("book-sheet-1").fetchSemanticsNodes().isNotEmpty()
        var narrow = false
        visible().forEachIndexed { side, text ->
            val sheet = onNodeWithTag("book-sheet-$side").fetchSemanticsNode().boundsInRoot
            val available = sheet.width - (if (spread) 112f else 128f) * density
            val measure = CoachV7.Size.body.value * 33f * scale * density
            narrow = narrow || available < measure - 2f
            assertEquals(minOf(measure, available), text.size.width.toFloat(), 2f, "$label: 33em inside actual sheet")
            assertEquals(sheet.left + (if (spread && side == 1) 48f else 64f) * density,
                text.positionInRoot.x, 2f, "$label: inner/outer margins")
            val layouts = mutableListOf<TextLayoutResult>()
            text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val laid = layouts.single()
            assertFalse(laid.hasVisualOverflow, "$label: no clipped text")
            assertEquals(laid.layoutInput.text.length, laid.getLineEnd(laid.lineCount - 1))
            assertTrue(text.positionInRoot.y >= sheet.top + 52f * density - 1f)
            assertTrue(text.positionInRoot.y + laid.getLineBottom(laid.lineCount - 1) <= sheet.bottom - 64f * density + 2f,
                "$label: last line fully above footer")
        }
        return narrow
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
                for ((label, scale) in listOf("小" to .9f, "大" to 1.15f, "中" to 1f)) {
                    typeSize(label)
                    while (isEnabled().matches(onNodeWithText("上一页").fetchSemanticsNode())) {
                        onNodeWithText("上一页").performClick(); waitForIdle()
                    }
                    val rebuilt = StringBuilder()
                    val seen = mutableSetOf<List<BookPageSlice>>()
                    var lastText = ""
                    while (true) {
                        val nodes = visible()
                        val first = nodes.first().config[BookPageSources].first()
                        ready(controller) { it.page == first.importPage && it.book?.readingPage == first.importPage }
                        nodes.forEach { node ->
                            val sources = node.config[BookPageSources]
                            assertTrue(seen.add(sources), "visit each laid-out page once")
                            val original = sources.joinToString("") {
                                val stored = imported.page(it.importPage)!!.text
                                assertFalse(it.startUtf16 > 0 && Character.isLowSurrogate(stored[it.startUtf16]))
                                stored.substring(it.startUtf16, it.endUtf16)
                            }
                            assertEquals(original, node.config[SemanticsProperties.Text].single().text)
                            rebuilt.append(original)
                        }
                        narrowSeen = assertColumn(scale, window.name + label, window.density) || narrowSeen
                        lastText = nodes.first().config[SemanticsProperties.Text].single().text
                        if (!isEnabled().matches(onNodeWithText("下一页").fetchSemanticsNode())) break
                        onNodeWithText("下一页").performClick(); waitForIdle()
                    }
                    assertEquals(pages.joinToString(""), rebuilt.toString(), "${window.name} Aa $label: lossless UTF16 traversal")
                    assertTrue(rebuilt.trimEnd().endsWith("全段最后一句在这里结束。"))
                    onNodeWithText("下一页").assertIsNotEnabled()
                    onNodeWithText("上一页").performClick(); waitForIdle()
                    assertNotEquals(lastText, visible().first().config[SemanticsProperties.Text].single().text)
                    val previous = visible().first().config[BookPageSources].first()
                    ready(controller) { it.page == previous.importPage && it.book?.readingPage == previous.importPage }
                    assertEquals(previous.importPage, TextbookLibrary(library.directory).book(imported.id).readingPage)
                    assertColumn(scale, "reverse turn", window.density)
                    println("PASS measure ${window.name} Aa=$label: ${seen.size} laid-out pages, lossless text, last line, reverse turn, source bookmark")
                }
            }
            if (window.name == "narrow") assertTrue(narrowSeen)
        }
    }

    @Test fun sourceOnALongPageOpensAtTheCitedBlockWithItsOffsets() {
        val root = Files.createTempDirectory("reader-measure-source-")
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(longParagraphBook(root))
        library.save(imported.copy(learning = true, readingPage = 1, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.learn(LearningPath.READ_AND_LEARN) }
        val cited = StudyCoachCourse.blocks(imported.id, imported.pages).filter { it.page == 2 && !it.heading }.maxBy { it.start }
        assertTrue(cited.start > 0)
        val support = object : BookLearningSupport {
            override suspend fun explain(request: BookExplanationRequest): BookLearningReply =
                BookLearningReply.Text("A synthetic explanation", listOf(BookPassage.from(library.book(request.book), cited)))
            override suspend fun ask(request: BookPassageQuestion) = BookLearningReply.Unavailable("unused")
        }
        runSkikoComposeUiTest(size = Size(2560f, 1440f), density = Density(1.5f)) {
            setContent { TextbookWorkspace(controller, loadOnMount = false, support = support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.READER }
            fun checkAnchor(scale: Float) {
                val node = visible().first { n -> n.config[BookPageSources].any {
                    it.importPage == cited.page && cited.start in it.startUtf16 until it.endUtf16
                } }
                var local = 0
                for (source in node.config[BookPageSources]) {
                    if (source.importPage == cited.page && cited.start in source.startUtf16 until source.endUtf16) {
                        val offset = local + cited.start - source.startUtf16
                        val marked = node.config[SemanticsProperties.Text].single()
                        assertTrue(marked.spanStyles.any { it.item.background != androidx.compose.ui.graphics.Color.Unspecified && offset in it.start until it.end })
                        val layouts = mutableListOf<TextLayoutResult>()
                        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                        val laid = layouts.single()
                        val y = node.positionInRoot.y + laid.getLineTop(laid.getLineForOffset(offset))
                        val page = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
                        assertTrue(y >= page.top && y < page.bottom, "citation start visible after reflow")
                    }
                    local += source.endUtf16 - source.startUtf16
                }
                assertColumn(scale, "source", 1.5f)
            }
            checkAnchor(1f)
            typeSize("大"); checkAnchor(1.15f)
            typeSize("中"); checkAnchor(1f)
            val covered = mutableSetOf<Int>()
            while (true) {
                visible().forEach { node ->
                    var local = 0
                    for (source in node.config[BookPageSources]) {
                        val a = maxOf(source.startUtf16, cited.start)
                        val b = minOf(source.endUtf16, cited.end)
                        if (source.importPage == cited.page && a < b) {
                            val marked = node.config[SemanticsProperties.Text].single().spanStyles.filter {
                                it.item.background != androidx.compose.ui.graphics.Color.Unspecified
                            }
                            for (offset in a until b) {
                                val at = local + offset - source.startUtf16
                                assertTrue(marked.any { at in it.start until it.end }, "source offset $offset highlighted")
                                covered += offset
                            }
                        }
                        local += source.endUtf16 - source.startUtf16
                    }
                }
                if (covered.size == cited.end - cited.start) break
                onNodeWithText("下一页").assertIsEnabled().performClick(); waitForIdle()
            }
            assertEquals((cited.start until cited.end).toSet(), covered)
            assertEquals(1, library.book(imported.id).readingPage, "source navigation keeps reading bookmark")
            assertEquals(imported.pages, library.book(imported.id).pages)
        }
        println("PASS measure source: page=2, mark=${cited.start}..${cited.end}, reflow anchor visible; full highlight coverage")
    }
}
