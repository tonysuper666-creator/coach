package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import coach.design.tokens.CoachV7
import com.coach.ui.CoachTheme
import com.coach.ui.pageturn.PageTurnStyle
import java.nio.file.Files
import kotlin.test.*

/** TON-1671：滚动读文字版里，已有高亮的段落滚入视口确有着色；离开视口再滚回仍然着色。合成材料。 */
@OptIn(ExperimentalTestApi::class)
class ScrollHighlightVisibilityTest {
    private val marker = "句0300。"

    /** 视口里含 marker 的那段文字的布局里，marker 范围上有没有 hl 背景。没有可见节点返回 null。 */
    private fun SkikoComposeUiTest.markerHighlighted(): Boolean? {
        val hl = CoachV7.Light.hl
        for (tag in listOf("reader-text", "reader-text-right")) for (node in onAllNodesWithTag(tag).fetchSemanticsNodes()) {
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val text = results.single().layoutInput.text
            val at = text.text.indexOf(marker)
            if (at < 0) continue
            return text.spanStyles.any { it.item.background == hl && it.start <= at && it.end >= at + marker.length }
        }
        return null
    }

    @Test fun anExistingHighlightIsColouredWhenScrolledInAndAgainAfterLeavingAndReturning() {
        val dir = Files.createTempDirectory("scroll-highlight-")
        try {
            val input = dir.resolve("numbered.md")
            Files.writeString(input, "# 阅读位置\n\n" + (1..520).joinToString("") { "句" + it.toString().padStart(4, '0') + "。读者找到这句话，再接着读下一句。" })
            val library = TextbookLibrary(dir.resolve("library"))
            val book = library.importBook(input)
            val page = book.pages.firstOrNull { it.text.contains(marker) }
                ?: error("fixture marker missing; pages=${book.pages.size} first=${book.pages.firstOrNull()?.text?.take(80)}")
            val start = page.text.indexOf(marker)
            val highlights = mapOf(page.number to listOf(start until start + marker.length))
            runSkikoComposeUiTest(size = Size(1440f, 1440f), density = Density(1.5f)) {
                val state = BookPagingState(BookPageAnchor(book.pages.first().number))
                mainClock.autoAdvance = false
                setContent { CoachTheme(false) { androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                    Box(Modifier.fillMaxSize()) {
                        BookPageView(book, state, highlights, onTurn = { state.move(it) },
                            turnStyle = PageTurnStyle.None, readMode = BookReadMode.Scroll)
                    }
                } } }
                mainClock.advanceTimeBy(300); waitForIdle()
                assertNull(markerHighlighted(), "the highlighted sentence is far below: not on screen yet")
                var steps = 0
                while (markerHighlighted() == null && steps < 80) {
                    runOnIdle { state.move(ReaderTurn.Next) }
                    mainClock.advanceTimeBy(400); waitForIdle(); steps++
                }
                assertEquals(true, markerHighlighted(), "the highlight must be coloured when scrolled into view (after $steps screens)")
                // 回到书首（高亮离开视口，列表回收），再一屏屏滚回来
                runOnIdle { state.anchor = state.pages.first().anchor }
                mainClock.advanceTimeBy(800); waitForIdle()
                assertNull(markerHighlighted(), "back at the top the highlight is off screen again")
                steps = 0
                while (markerHighlighted() == null && steps < 80) {
                    runOnIdle { state.move(ReaderTurn.Next) }
                    mainClock.advanceTimeBy(400); waitForIdle(); steps++
                }
                assertEquals(true, markerHighlighted(), "and still coloured after leaving and coming back")
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
