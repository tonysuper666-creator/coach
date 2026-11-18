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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    private fun SkikoComposeUiTest.spreadHost(state: BookPagingState, mode: androidx.compose.runtime.MutableState<BookReadMode>) {
        mainClock.autoAdvance = false
        setContent { CoachTheme(false) { androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) { Box(Modifier.fillMaxSize()) {
            BookPageView(book, state, emptyMap(), onTurn = { state.move(it) }, turnStyle = PageTurnStyle.None, readMode = mode.value)
        } } } }
        mainClock.advanceTimeBy(100); waitForIdle()
    }

    private fun SkikoComposeUiTest.tenRounds(state: BookPagingState, mode: androidx.compose.runtime.MutableState<BookReadMode>, baseline: BookPageAnchor) {
        repeat(10) { round ->
            runOnIdle { mode.value = BookReadMode.Scroll }
            mainClock.advanceTimeBy(150); waitForIdle()
            onNodeWithTag("book-scroll-read").assertExists()
            runOnIdle { mode.value = BookReadMode.Paged }
            mainClock.advanceTimeBy(150); waitForIdle()
            assertEquals(baseline, state.anchor, "round $round: back in paging the anchor must equal the baseline")
        }
    }

    @Test fun leftPageOfASpreadKeepsTheExactAnchorOverTenRoundTrips() = runSkikoComposeUiTest(size = Size(2560f, 1440f), density = Density(1.5f)) {
        val state = BookPagingState(BookPageAnchor(41))
        val mode = androidx.compose.runtime.mutableStateOf(BookReadMode.Paged)
        spreadHost(state, mode)
        runOnIdle { state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(100); waitForIdle()
        assertTrue(state.spread)
        val baseline = state.anchor
        assertEquals(state.pages[state.index].anchor, baseline)
        runOnIdle { mode.value = BookReadMode.Scroll }
        mainClock.advanceTimeBy(150); waitForIdle()
        assertEquals(baseline, state.anchor, "entering scroll without scrolling must not move the anchor")
        runOnIdle { mode.value = BookReadMode.Paged }
        mainClock.advanceTimeBy(150); waitForIdle()
        assertEquals(baseline, state.anchor)
        tenRounds(state, mode, baseline)
    }

    @Test fun rightPageClickBecomesTheReadingSpotAndSurvivesTenRoundTrips() = runSkikoComposeUiTest(size = Size(2560f, 1440f), density = Density(1.5f)) {
        val state = BookPagingState(BookPageAnchor(41))
        val mode = androidx.compose.runtime.mutableStateOf(BookReadMode.Paged)
        spreadHost(state, mode)
        runOnIdle { state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(100); waitForIdle()
        val groupLeft = state.index
        val leftAnchor = state.anchor
        onNodeWithTag("book-sheet-1").performTouchInput { click(Offset(width / 2f, height / 2f)) }
        mainClock.advanceTimeBy(100); waitForIdle()
        val baseline = state.anchor
        assertTrue(baseline.offsetUtf16 > leftAnchor.offsetUtf16, "a click on the right page must move the anchor into it")
        assertEquals(groupLeft, state.index, "the spread must not shift when the right page is clicked")
        runOnIdle { mode.value = BookReadMode.Scroll }
        mainClock.advanceTimeBy(150); waitForIdle()
        assertEquals(baseline, state.anchor)
        runOnIdle { mode.value = BookReadMode.Paged }
        mainClock.advanceTimeBy(150); waitForIdle()
        assertEquals(baseline, state.anchor)
        tenRounds(state, mode, baseline)
    }

    @Test fun scrollModeKeepsThePageWhenSwitchingBackAndForthTenTimes() = runSkikoComposeUiTest(size = Size(2560f, 1440f), density = Density(1.5f)) {
        val state = BookPagingState(BookPageAnchor(41))
        val mode = androidx.compose.runtime.mutableStateOf(BookReadMode.Paged)
        spreadHost(state, mode)
        runOnIdle { state.move(ReaderTurn.Next); state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(100); waitForIdle()
        val baseline = state.anchor
        tenRounds(state, mode, baseline)
        runOnIdle { mode.value = BookReadMode.Scroll }
        mainClock.advanceTimeBy(150); waitForIdle()
        val scrollStartIndex = state.index
        onNodeWithTag("book-scroll-read").performTouchInput { swipeUp(startY = height * 0.9f, endY = height * 0.05f, durationMillis = 100) }
        mainClock.advanceTimeBy(3000); waitForIdle()
        assertTrue(state.index > scrollStartIndex, "scrolling down should advance the page: $scrollStartIndex -> ${state.index}")
        val after = state.anchor
        runOnIdle { mode.value = BookReadMode.Paged }
        mainClock.advanceTimeBy(150); waitForIdle()
        assertEquals(after, state.anchor)
        shot("read-mode-paged-after-scroll")
    }

    @Test fun inScrollModeNextAndPreviousScrollOneScreenWithOverlap() = runSkikoComposeUiTest(size = Size(1440f, 1440f), density = Density(1.5f)) {
        val state = BookPagingState(BookPageAnchor(41))
        val mode = androidx.compose.runtime.mutableStateOf(BookReadMode.Scroll)
        spreadHost(state, mode)
        assertTrue(!state.spread)
        val start = state.anchor
        runOnIdle { state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(1500); waitForIdle()
        val one = state.anchor
        assertTrue(one.offsetUtf16 > start.offsetUtf16, "Next in scroll mode must scroll forward: $start -> $one")
        // 一屏约 0.9 个视口：不会像翻页那样直接跳到下一页页首
        assertTrue(state.index <= 1, "one screen must not skip whole pages: index ${state.index}")
        runOnIdle { state.move(ReaderTurn.Previous) }
        mainClock.advanceTimeBy(1500); waitForIdle()
        val back = state.anchor
        assertTrue(kotlin.math.abs(back.offsetUtf16 - start.offsetUtf16) < (one.offsetUtf16 - start.offsetUtf16) / 3 + 80,
            "Previous must return near the start: $start -> $one -> $back")
        repeat(30) { runOnIdle { state.move(ReaderTurn.Next) }; mainClock.advanceTimeBy(400); waitForIdle() }
        assertTrue(state.index == state.pages.lastIndex || state.anchor.offsetUtf16 > one.offsetUtf16, "repeated Next keeps scrolling to the end")
        runOnIdle { mode.value = BookReadMode.Paged }
        mainClock.advanceTimeBy(150); waitForIdle()
        val pagedBefore = state.index
        runOnIdle { state.move(ReaderTurn.Previous) }
        assertTrue(state.index < pagedBefore || pagedBefore == 0, "in paged mode Previous still turns a page")
    }

    @Test fun inScrollModeThePreviousButtonStateFollowsTheRealScrollEdges() = runSkikoComposeUiTest(size = Size(1440f, 1440f), density = Density(1.5f)) {
        val state = BookPagingState(BookPageAnchor(41))
        val mode = androidx.compose.runtime.mutableStateOf(BookReadMode.Scroll)
        spreadHost(state, mode)
        assertFalse(state.canPrevious, "at the very top there is nothing above")
        assertTrue(state.canNext)
        runOnIdle { state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(1500); waitForIdle()
        assertTrue(state.canPrevious, "after scrolling half a screen down the previous button must be enabled")
        repeat(60) { runOnIdle { state.move(ReaderTurn.Next) }; mainClock.advanceTimeBy(300); waitForIdle() }
        assertFalse(state.canNext, "at the very bottom there is nothing below")
        runOnIdle { mode.value = BookReadMode.Paged }
        mainClock.advanceTimeBy(150); waitForIdle()
        assertEquals(state.index > 0, state.canPrevious, "paged mode goes back to page-index rules")
    }
}
