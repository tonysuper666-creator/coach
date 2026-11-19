package com.coach.desktop.books

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.coach.ui.CoachTheme
import java.nio.file.Files
import kotlin.test.*

/**
 * TON-1671：宽屏双页下，翻转 / 滑动 / 无 三种翻页设置都能翻页，并且切到滚动读再切回后，同一句仍在视口。
 * 走真实的 TextbookReader 底栏按钮；合成带编号文本。
 */
@OptIn(ExperimentalTestApi::class)
class TurnStyleReadingSpotTest {
    private fun SkikoComposeUiTest.visibleSentences(): List<String> {
        val viewport = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
        return listOf("reader-text", "reader-text-right").flatMap { tag ->
            onAllNodesWithTag(tag).fetchSemanticsNodes().flatMap { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                val layout = results.single()
                Regex("句[0-9]{4}。").findAll(layout.layoutInput.text.text).mapNotNull { m ->
                    val line = layout.getLineForOffset(m.range.first)
                    val top = node.positionInRoot.y + layout.getLineTop(line)
                    val bottom = node.positionInRoot.y + layout.getLineBottom(line)
                    if (top >= viewport.top && bottom <= viewport.bottom) m.value else null
                }.toList()
            }
        }
    }

    private fun SkikoComposeUiTest.selectStyle(label: String) {
        repeat(3) {
            if (onAllNodes(hasTestTag("turn-style") and hasText(label)).fetchSemanticsNodes().isNotEmpty()) return
            onNodeWithTag("turn-style").performClick()
            waitForIdle()
        }
        error("could not select turn style $label")
    }

    private fun check(label: String) {
        val dir = Files.createTempDirectory("turn-style-spot")
        try {
            val library = TextbookLibrary(dir.resolve("library"))
            val input = dir.resolve("numbered.md")
            Files.writeString(input, "# 阅读位置\n\n" + (1..520).joinToString("") { "句" + it.toString().padStart(4, '0') + "。读者找到这句话，再接着读下一句。" })
            val book = library.importBook(input)
            var ui by mutableStateOf(LibraryUi(book = book, screen = BookScreen.READER, page = book.pages.first().number))
            runSkikoComposeUiTest(size = Size(2560f, 1440f), density = Density(1.5f)) {
                val focus = FocusRequester()
                setContent { CoachTheme(false) {
                    TextbookReader(ui, library, UnconnectedBookLearningSupport, null, focus,
                        onNoteSession = {}, onPage = { ui = ui.copy(page = it) }, onBack = {}, onLearn = {})
                } }
                waitForIdle()
                selectStyle(label)
                mainClock.autoAdvance = false
                val start = visibleSentences()
                assertTrue(start.size > 10, "wide window shows a spread: ${start.size} sentences")
                onNodeWithText("下一页").performClick()
                mainClock.advanceTimeBy(16); waitForIdle()
                val afterOneFrame = visibleSentences()
                if (label == "无") assertNotEquals(start.first(), afterOneFrame.first(), "None swaps the pages within one frame")
                mainClock.advanceTimeBy(900); waitForIdle()
                val turned = visibleSentences()
                assertTrue(turned.first() > start.last() || turned.first() != start.first(), "$label: the next spread is shown: ${start.first()} -> ${turned.first()}")
                val first = turned.first(); val later = turned[turned.size / 4] // 架构裁定：不操作时读位是左页首字，只保证左页的句子
                onNodeWithTag("read-mode").performClick()
                mainClock.advanceTimeBy(900); waitForIdle()
                onNodeWithTag("book-scroll-read").assertExists()
                val scrolling = visibleSentences()
                assertTrue(first in scrolling && later in scrolling, "$label: same sentences in scroll mode: $first/$later in $scrolling")
                onNodeWithTag("read-mode").performClick()
                mainClock.advanceTimeBy(900); waitForIdle()
                val back = visibleSentences()
                assertTrue(first in back && later in back, "$label: and back in the spread: $first/$later in $back")
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun flip() = check("翻转")
    @Test fun slide() = check("滑动")
    @Test fun none() = check("无")
}
