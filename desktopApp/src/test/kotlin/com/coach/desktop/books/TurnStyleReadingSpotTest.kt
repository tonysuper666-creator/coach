package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
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

    /** 双页翻页读：左右两页的正文都在，并且没有滚动控件。 */
    private fun SkikoComposeUiTest.assertSpread(when_: String) {
        assertTrue(onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty(), "$when_: left page body must be visible")
        assertTrue(onAllNodesWithTag("reader-text-right").fetchSemanticsNodes().isNotEmpty(), "$when_: right page body must be visible")
        onNodeWithTag("book-scroll-read").assertDoesNotExist()
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
            library.importBook(input)
            val controller = TextbookController(library)
            runSkikoComposeUiTest(size = Size(2560f, 1440f), density = Density(1.5f)) {
                setContent { WindowsTextbookHost(controller) { null } }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.initialized && !controller.state.value.busy }
                onNodeWithTag("home-read").performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty() }
                // The immersive reader starts in Flip. Other styles still require an explicit selector.
                if (label != "翻转") selectStyle(label)
                mainClock.autoAdvance = false
                assertSpread("initially")
                val start = visibleSentences()
                assertTrue(start.size > 10, "wide window shows a spread: ${start.size} sentences")
                onNodeWithTag("next-page").performClick()
                mainClock.advanceTimeBy(16); waitForIdle()
                val afterOneFrame = visibleSentences()
                if (label == "无") assertNotEquals(start.first(), afterOneFrame.first(), "None swaps the pages within one frame")
                mainClock.advanceTimeBy(900); waitForIdle()
                val turned = visibleSentences()
                assertTrue(turned.first() > start.last() || turned.first() != start.first(), "$label: the next spread is shown: ${start.first()} -> ${turned.first()}")
                mainClock.autoAdvance = true
                val first = turned.first(); val later = turned[turned.size / 4] // 架构裁定：不操作时读位是左页首字，只保证左页的句子
                toggleReaderMode()
                mainClock.advanceTimeBy(900); waitForIdle()
                onNodeWithTag("book-scroll-read").assertExists()
                val scrolling = visibleSentences()
                assertTrue(first in scrolling && later in scrolling, "$label: same sentences in scroll mode: $first/$later in $scrolling")
                toggleReaderMode()
                mainClock.advanceTimeBy(900); waitForIdle()
                onNodeWithTag("book-scroll-read").assertDoesNotExist() // 切回了翻页读：滚动控件已消失
                assertSpread("after switching back")
                val back = visibleSentences()
                assertTrue(first in back && later in back, "$label: and back in the spread: $first/$later in $back")
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun flip() = check("翻转")
    @Test fun slide() = check("滑动")
    @Test fun none() = check("无")
}
