package com.coach.desktop.regression

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest as runScheduledSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.books.BookExplainer
import com.coach.desktop.books.BookScreen
import com.coach.desktop.books.Citation
import com.coach.desktop.books.ExplainResult
import com.coach.desktop.books.ExplainSectionContext
import com.coach.desktop.books.Explanation
import com.coach.desktop.books.FollowUpTurn
import com.coach.desktop.books.LibraryUi
import com.coach.desktop.books.LocalReadingAutoGuide
import com.coach.desktop.books.SourceBlock
import com.coach.desktop.books.Textbook
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.TextbookReader
import com.coach.desktop.books.UnconnectedBookLearningSupport
import com.coach.ui.CoachTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TON-1860：沉浸文字模式里，鼠标横向拖选不得翻页，选中的必须是拖到的那一行；
 * 64 页以上的长书划词之后，页码不得因重排跳走。
 * 合成句子，不含教材原文。断言只看页码和选中文字，不依赖翻页手势的内部开关。
 */
@OptIn(ExperimentalTestApi::class)
class R1860ImmersiveDragSelectionTest {
    @Test fun upperLeftLineDragAt2160x1350KeepsThePageAndSelectsThatSentence() =
        upperLeftLineDrag(Size(2160f, 1350f))

    @Test fun upperLeftLineDragAt1128x828KeepsThePageAndSelectsThatSentence() =
        upperLeftLineDrag(Size(1128f, 828f))

    @Test fun longBookSelectionDoesNotJumpThePage() {
        val root = Files.createTempDirectory("coach-r1860-long-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(root.resolve("long.md").also { Files.writeString(it, longBook()) })
            assertTrue(book.pages.size > 64, "fixture needs background layout: ${book.pages.size} import pages")
            val anchorPage = book.pages.first { ANCHOR_SENTENCE in it.text }.number
            val explainer = RecordingExplainer(root.resolve("home"), library)
            // 排版跑在 Dispatchers.Default 上，v1 的 Unconfined 调度器会在同一轮等待里把结果刷回来。
            // 这里用 StandardTestDispatcher：选区触发重排后，先停在「正在排版…」那一帧再读。
            runScheduledSkikoComposeUiTest(size = Size(1128f, 828f), density = Density(1f)) {
                try {
                    openReader(library, book, page = anchorPage, explainer = explainer)
                    waitUntil(timeoutMillis = 90_000) {
                        onAllNodesWithTag("book-page-loading").fetchSemanticsNodes().isEmpty() &&
                            onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty() &&
                            showsImportPage(anchorPage)
                    }
                    waitForIdle()
                    val pageBefore = pageLabel()
                    assertTrue(ANCHOR_SENTENCE in visibleText(), "anchor sentence must be on screen before the drag")
                    mainClock.autoAdvance = false
                    runWithoutImplicitWait { dragAcrossVisibleText(corner = false) }
                    var sawLoading = false
                    var sawToolbar = false
                    val labels = mutableListOf(pageBefore)
                    val bodies = mutableListOf<String>()
                    for (frame in 0 until 8) {
                        mainClock.advanceTimeByFrame()
                        val loading = runWithoutImplicitWait {
                            onAllNodesWithTag("book-page-loading").fetchSemanticsNodes().isNotEmpty()
                        }
                        val text = runWithoutImplicitWait { visibleText() }
                        val page = runWithoutImplicitWait { pageLabel() }
                        val toolbar = runWithoutImplicitWait {
                            onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isNotEmpty()
                        }
                        labels += page
                        bodies += if (ANCHOR_SENTENCE in text) "sentence" else text.take(40)
                        if (loading || text == "LOADING" || text == "MISSING") sawLoading = true
                        if (toolbar) sawToolbar = true
                        if (sawLoading && sawToolbar) break
                    }
                    val problems = mutableListOf<String>()
                    if (!sawToolbar) problems += "word drag did not select text, so reflow was not exercised"
                    if (sawLoading) {
                        problems += "selection reflowed the long book onto 正在排版 (pages ${labels.distinct()}; text ${bodies.distinct()})"
                    }
                    if (labels.any { it != pageBefore }) {
                        problems += "page jumped after selection: $pageBefore -> ${labels.distinct()}"
                    }
                    if (problems.isEmpty()) {
                        mainClock.autoAdvance = true
                        waitForIdle()
                        val pageAfter = pageLabel()
                        if (pageAfter != pageBefore) problems += "page changed after reflow settled: $pageBefore -> $pageAfter"
                        if (ANCHOR_SENTENCE !in visibleText()) problems += "anchor sentence left the screen after selection"
                        if (onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isEmpty()) {
                            problems += "selection did not stay on the anchor sentence"
                        } else {
                            onNodeWithTag("selection-action-explain").performClick()
                            waitUntil(timeoutMillis = 10_000) { explainer.selections.isNotEmpty() }
                            val selected = explainer.selections.last()
                            if (selected.page != anchorPage) problems += "selection landed on page ${selected.page}, not $anchorPage"
                        }
                    }
                    assertTrue(problems.isEmpty(), problems.joinToString("; "))
                } finally {
                    mainClock.autoAdvance = true
                    runCatching { waitForIdle() }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun upperLeftLineDrag(size: Size) {
        val root = Files.createTempDirectory("coach-r1860-corner-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(root.resolve("corner.md").also { Files.writeString(it, cornerBook()) })
            val cornerPage = book.pages.first { CORNER_SENTENCE in it.text }.number
            assertTrue(cornerPage > 1, "corner sentence must not sit on the first import page")
            val explainer = RecordingExplainer(root.resolve("home"), library)
            runSkikoComposeUiTest(size = size, density = Density(1f)) {
                openReader(library, book, page = cornerPage, explainer = explainer)
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodesWithTag("reader-page-number").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                val shown = visibleText()
                assertTrue(CORNER_SENTENCE in shown, "${size.width.toInt()}x${size.height.toInt()} fixture missed the corner sentence: ${shown.take(120)}")
                val pageBefore = pageLabel()
                assertTrue(showsImportPage(cornerPage), "opened on the wrong page: $pageBefore")
                dragAcrossVisibleText(corner = true)
                mainClock.advanceTimeBy(800)
                waitForIdle()
                Thread.sleep(400)
                waitForIdle()
                val problems = mutableListOf<String>()
                val pageAfter = pageLabel()
                if (pageAfter != pageBefore) problems += "page changed ${pageBefore} -> $pageAfter"
                if (onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isEmpty()) {
                    problems += "drag did not select the upper-left line"
                } else {
                    onNodeWithTag("selection-action-explain").performClick()
                    waitUntil(timeoutMillis = 10_000) { explainer.selections.isNotEmpty() }
                    val selected = explainer.selections.last().text
                    if (CORNER_SENTENCE !in selected) problems += "selected text was not that line: ${selected.take(160)}"
                    if (explainer.selections.last().page != cornerPage) problems += "selection page was ${explainer.selections.last().page}"
                }
                assertTrue(problems.isEmpty(), "${size.width.toInt()}x${size.height.toInt()}: " + problems.joinToString("; "))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun ComposeUiTest.openReader(
        library: TextbookLibrary,
        book: Textbook,
        page: Int,
        explainer: RecordingExplainer,
    ) {
        setContent {
            var activePage by mutableStateOf(page)
            val focus = remember { FocusRequester() }
            CoachTheme(dark = false) {
                CompositionLocalProvider(LocalReadingAutoGuide provides false) {
                    Box(Modifier.fillMaxSize()) {
                        TextbookReader(
                            ui = LibraryUi(library.catalog(), book, screen = BookScreen.READER, page = activePage),
                            library = library,
                            support = UnconnectedBookLearningSupport,
                            practiceSource = null,
                            pageFocus = focus,
                            onNoteSession = {},
                            onPage = { activePage = it },
                            onBack = {},
                            onLearn = {},
                            explainProvider = explainer,
                        )
                    }
                }
            }
        }
    }

    /** [corner] true：拖正文最上靠左那一行，起点落在页角翻页区。false：在正文中部横向拖，避开页角。 */
    private fun ComposeUiTest.dragAcrossVisibleText(corner: Boolean) {
        val text = onNodeWithTag("reader-text").fetchSemanticsNode().boundsInRoot
        val sheet = onNodeWithTag("book-sheet-0").fetchSemanticsNode().boundsInRoot
        val turn = onNodeWithTag("book-page-turn").fetchSemanticsNode().boundsInRoot
        val spread = onAllNodesWithTag("book-sheet-1").fetchSemanticsNodes().isNotEmpty()
        val edge = turn.width * (if (spread) 0.08f else 0.14f)
        val cornerBottom = turn.top + turn.height * 0.18f
        val localY: Float
        val localStart: Float
        val localEnd: Float
        if (corner) {
            // 起拖点落在左内边距里、正文上方那一行的高度上，不压在字形上。
            val rootY = text.top + 8f
            val rootStart = sheet.left + 2f
            val rootEnd = text.right - 4f
            assertTrue(rootStart < text.left - 1f, "inset start x=$rootStart must sit left of the glyphs at ${text.left}")
            assertTrue(rootY < cornerBottom, "upper line y=$rootY is outside the top 18% (corner ends at $cornerBottom)")
            assertTrue(rootStart - turn.left < edge, "drag start x=${rootStart - turn.left} is outside the ${if (spread) "8%" else "14%"} edge ($edge)")
            localY = rootY - sheet.top
            localStart = rootStart - sheet.left
            localEnd = rootEnd - sheet.left
        } else {
            // 纵向落在正文中段，避开纸面顶部可能属于上一导入页的短标题。
            val rootY = text.top + text.height * 0.45f
            var rootStart = text.left + text.width * 0.42f
            if (rootStart - turn.left < edge + 16f) rootStart = turn.left + edge + 24f
            val rootEnd = (rootStart + 160f).coerceAtMost(text.right - 4f)
            assertTrue(rootStart - turn.left > edge, "word drag still starts inside the page-turn edge")
            localY = rootY - sheet.top
            localStart = rootStart - sheet.left
            localEnd = rootEnd - sheet.left
        }
        assertTrue(localEnd > localStart + 40f, "drag is too short to select a sentence")
        onNodeWithTag("book-sheet-0").performMouseInput {
            moveTo(Offset(localStart, localY))
            press()
            moveTo(Offset((localStart + localEnd) / 2f, localY))
            moveTo(Offset(localEnd, localY))
            release()
        }
    }

    /** 「第 4」不能误匹配「第 41」。 */
    private fun ComposeUiTest.showsImportPage(page: Int): Boolean {
        val label = pageLabel()
        return label.contains("第 $page /") || label.contains("第 $page–") || label.contains("第 $page 页")
    }

    private fun ComposeUiTest.pageLabel(): String {
        if (onAllNodesWithTag("reader-page-number", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) return "MISSING"
        return nodeText("reader-page-number")
    }

    private fun ComposeUiTest.visibleText(): String {
        if (onAllNodesWithTag("reader-text", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) return "LOADING"
        return nodeText("reader-text")
    }

    private fun ComposeUiTest.nodeText(tag: String): String =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { node ->
            val own = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            if (own.isNotEmpty()) own
            else onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text }.orEmpty()
        }

    private class RecordingExplainer(home: java.nio.file.Path, library: TextbookLibrary) :
        BookExplainer(DesktopConnectionSettings(home = home, environment = { null }), library) {
        val selections = mutableListOf<SourceBlock>()

        init {
            identity = DesktopIdentity("http://127.0.0.1:1", PairedCredentials("dev-r1860", "tok-r1860"))
            loaded = true
        }

        override suspend fun load() {
            loaded = true
        }

        override suspend fun explainSelection(
            kind: String,
            chosen: SourceBlock,
            context: ExplainSectionContext?,
            question: String?,
            history: List<FollowUpTurn>,
            requestId: String,
        ): ExplainResult {
            selections += chosen
            return ExplainResult.Done(
                Explanation(
                    text = "合成讲解，第 ${chosen.page} 页。",
                    citations = listOf(Citation("S1", chosen.id, chosen.page)),
                    elapsedMs = 1L,
                ),
            )
        }
    }

    private fun cornerBook(): String = buildString {
        appendLine("# Opening chapter")
        appendLine()
        repeat(200) { appendLine("Opening filler line $it keeps earlier sheets before the corner sentence.") }
        appendLine()
        appendLine("# $CORNER_SENTENCE")
        appendLine()
        appendLine("Body under the corner line stays on the same sheet and must not become the selection.")
    }

    private fun longBook(): String = buildString {
        for (n in 1..70) {
            appendLine("# Chapter $n")
            appendLine()
            if (n == 40) {
                appendLine("# $ANCHOR_SENTENCE")
                repeat(12) { appendLine("$ANCHOR_SENTENCE filler $it keeps this chapter on its own sheet.") }
            } else {
                appendLine("Import page $n keeps its own sentence.")
                repeat(8) { appendLine("Padding $it for import page $n so the chapter is not empty.") }
            }
            appendLine()
        }
    }

    private companion object {
        const val CORNER_SENTENCE = "Corner sentence stays on this sheet."
        const val ANCHOR_SENTENCE = "Page forty anchor sentence stays put across this wide upper line of the sheet."
    }
}
