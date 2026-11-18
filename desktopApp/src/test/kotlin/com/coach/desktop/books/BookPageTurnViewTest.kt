package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.ui.CoachTheme
import com.coach.ui.pageturn.PageTurnStyle
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** TON-1671：书页接上共用翻页组件后，双页对开的翻转/滑动在真实分页上能翻、落准、可拖。合成文字，不含教材。 */
@OptIn(ExperimentalTestApi::class)
class BookPageTurnViewTest {
    private val book = Textbook("isolated-turn", "阅读与证据", "md", listOf(BookPage(41,
        "# 第三章 阅读与证据\n\n" + "读者回到原文检查证据，再把结论接回已有经验。😀𠮷".repeat(500))))

    private fun SkikoComposeUiTest.shot(name: String) {
        val dir = File(System.getProperty("coach.turnShots") ?: "build/screens/ton1671").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    private fun run(style: PageTurnStyle, dark: Boolean) = runSkikoComposeUiTest(size = Size(2560f, 1440f), density = Density(1.5f)) {
        val state = BookPagingState(BookPageAnchor(41))
        mainClock.autoAdvance = false
        setContent { CoachTheme(dark) { androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) { Box(Modifier.fillMaxSize()) {
            BookPageView(book, state, emptyMap(), onTurn = { state.move(it) }, turnStyle = style)
        } } } }
        mainClock.advanceTimeBy(100); waitForIdle()
        assertTrue(state.spread, "2560x1440 should open as a spread")
        assertTrue(state.pages.size > 6)
        val tag = "${style.name.lowercase()}-${if (dark) "dark" else "light"}"
        shot("turn-start-$tag")
        runOnIdle { state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(120); waitForIdle()
        shot("turn-mid-$tag")
        mainClock.advanceTimeBy(600); waitForIdle()
        assertEquals(2, state.index)
        onNodeWithTag("book-sheet-0").assertExists(); onNodeWithTag("book-sheet-1").assertExists()
        shot("turn-landed-$tag")
        // 键盘跳到章末（快翻）后落在最后一页
        runOnIdle { state.move(ReaderTurn.Last) }
        mainClock.advanceTimeBy(1500); waitForIdle()
        assertEquals(state.pages.lastIndex, state.index)
        // 拖页角：从右下角往左拖过半，松手翻回上一页组合（向后）
        val before = state.index
        onNodeWithTag("book-page-turn").performTouchInput {
            val w = width.toFloat(); val h = height.toFloat()
            down(Offset(w * 0.03f, h - 60f))
            moveTo(Offset(w * 0.3f, h - 60f)); moveTo(Offset(w * 0.6f, h - 60f))
            up()
        }
        mainClock.advanceTimeBy(800); waitForIdle()
        assertTrue(state.index < before, "dragging the left corner right should turn back: $before -> ${state.index}")
        shot("turn-dragged-$tag")
    }

    @Test fun flipLight() = run(PageTurnStyle.Flip, false)
    @Test fun flipDark() = run(PageTurnStyle.Flip, true)
    @Test fun slideLight() = run(PageTurnStyle.Slide, false)
}
