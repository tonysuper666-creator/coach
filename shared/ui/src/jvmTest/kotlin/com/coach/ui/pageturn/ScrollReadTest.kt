package com.coach.ui.pageturn

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.captureLessonEvidence
import com.coach.ui.v7.LocalV7ReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrollReadMathTest {
    @Test fun fractionRoundTrip() {
        assertEquals(0.25f, scrollFraction(175, 700), 1e-4f)
        assertEquals(175, scrollOffsetFor(0.25f, 700))
        assertEquals(0f, scrollFraction(10, 0))
        assertEquals(1f, scrollFraction(900, 700))
    }
    @Test fun screenStepKeepsOverlap() { assertEquals(810f, screenScrollPx(900)) }
    @Test fun positionConversions() {
        assertEquals(ScrollReadPosition(6, 0f), PageTurnState(20, 6).toScrollPosition())
        assertEquals(6, ScrollReadPosition(7, 0.4f).toPageTurnPosition(20, 2))
        assertEquals(7, ScrollReadPosition(7, 0.4f).toPageTurnPosition(20, 2, align = false))
        assertEquals(7, ScrollReadPosition(7).toPageTurnPosition(20, 1))
        assertTrue(ScrollReadPosition(3, 0.50f).sameSpot(ScrollReadPosition(3, 0.51f)))
        assertFalse(ScrollReadPosition(3, 0.5f).sameSpot(ScrollReadPosition(4, 0.5f)))
    }
}

@OptIn(ExperimentalTestApi::class)
class ScrollReadViewTest {
    private fun SkikoComposeUiTest.host(state: ScrollReadState, dark: Boolean = false, reduced: Boolean = false, out: (CoroutineScope) -> Unit) {
        mainClock.autoAdvance = false
        val sheet = if (dark) CoachV7.Dark.sheet else CoachV7.Light.sheet
        val desk = if (dark) CoachV7.Dark.desk else CoachV7.Light.desk
        val ink = if (dark) CoachV7.Dark.ink else CoachV7.Light.ink
        setContent {
            out(rememberCoroutineScope())
            CompositionLocalProvider(LocalV7ReducedMotion provides reduced, androidx.compose.material3.LocalContentColor provides ink) {
                ScrollReadView(state, desk = desk, paper = sheet, modifier = Modifier.fillMaxSize(), scrollbar = { ReadScrollbarDesktop(it) }) { i ->
                    Box(Modifier.height(700.dp).padding(40.dp)) { Text("第 ${i + 1} 页\n" + "纸上的字。".repeat(40)) }
                }
            }
        }
        mainClock.advanceTimeBy(64); waitForIdle()
    }

    private val size = Size(1200f, 900f)

    @Test fun scrollingPastAPageEdgeContinuesIntoTheNextPage() = runSkikoComposeUiTest(size = size) {
        val state = ScrollReadState(20)
        lateinit var scope: CoroutineScope
        host(state) { scope = it }
        assertEquals(0, state.position.page)
        scope.launch { state.list.scrollBy(500f) }
        mainClock.advanceTimeBy(32); waitForIdle()
        assertEquals(0, state.position.page)
        assertTrue(state.visiblePages.last >= 1, "page 2 must already be visible: ${state.visiblePages}")
        captureLessonEvidence("scroll-read-across-edge-light", 1200, 900)
        scope.launch { state.list.scrollBy(400f) }
        mainClock.advanceTimeBy(32); waitForIdle()
        assertEquals(1, state.position.page)
        // 滚回去：回到第 1 页，进度接近原处
        scope.launch { state.list.scrollBy(-400f) }
        mainClock.advanceTimeBy(32); waitForIdle()
        assertEquals(0, state.position.page)
    }

    @Test fun darkAcrossEdgeShot() = runSkikoComposeUiTest(size = size) {
        val state = ScrollReadState(20)
        lateinit var scope: CoroutineScope
        host(state, dark = true) { scope = it }
        scope.launch { state.list.scrollBy(500f) }
        mainClock.advanceTimeBy(32); waitForIdle()
        captureLessonEvidence("scroll-read-across-edge-dark", 1200, 900)
    }

    @Test fun positionSurvivesSwitchingBetweenPagingAndScrolling() = runSkikoComposeUiTest(size = size) {
        val state = ScrollReadState(20)
        lateinit var scope: CoroutineScope
        host(state) { scope = it }
        scope.launch { state.scrollTo(ScrollReadPosition(5, 0.5f)) }
        mainClock.advanceTimeBy(64); waitForIdle()
        val here = state.position
        assertEquals(5, here.page)
        assertTrue(here.sameSpot(ScrollReadPosition(5, 0.5f), 0.03f), "got $here")
        // 切到翻页（双页取偶数左页），再切回滚动：页不丢
        val turnAt = here.toPageTurnPosition(20, 2)
        assertEquals(4, turnAt)
        scope.launch { state.scrollTo(ScrollReadPosition(turnAt)) }
        mainClock.advanceTimeBy(64); waitForIdle()
        assertEquals(4, state.position.page)
        // 翻页切过来时的初始位置（构造时带进度）
        val s2 = ScrollReadState(20, ScrollReadPosition(8, 0.4f))
        lateinit var scope2: CoroutineScope
        host(s2) { scope2 = it }
        mainClock.advanceTimeBy(64); waitForIdle()
        assertEquals(8, s2.position.page)
        assertTrue(s2.position.fraction in 0.37f..0.43f, "initial fraction restored, got ${s2.position}")
    }

    @Test fun reducedMotionScreenScrollJumpsAtOnce() = runSkikoComposeUiTest(size = size) {
        val state = ScrollReadState(20)
        lateinit var scope: CoroutineScope
        host(state, reduced = true) { scope = it }
        scope.launch { state.scrollScreen(true) }
        mainClock.advanceTimeBy(16); waitForIdle()
        // 一屏 = 900 * 0.9 = 810px，页高 700dp，所以已进入第 2 页
        assertEquals(1, state.position.page)
    }

    @Test fun animatedScreenScrollSettlesOnTheSamePlaceAsInstant() = runSkikoComposeUiTest(size = size) {
        val state = ScrollReadState(20)
        lateinit var scope: CoroutineScope
        host(state) { scope = it }
        scope.launch { state.scrollScreen(true) }
        mainClock.advanceTimeBy(1200); waitForIdle()
        assertEquals(1, state.position.page)
    }

    private fun SkikoComposeUiTest.tall(state: ScrollReadState, pageDp: Int, out: (CoroutineScope) -> Unit) {
        mainClock.autoAdvance = false
        setContent {
            out(rememberCoroutineScope())
            ScrollReadView(state, desk = androidx.compose.ui.graphics.Color.LightGray, paper = androidx.compose.ui.graphics.Color.White, modifier = Modifier.fillMaxSize(), scrollbar = { ReadScrollbarDesktop(it) }) { i ->
                Box(Modifier.height(pageDp.dp)) { Text("page $i") }
            }
        }
        mainClock.advanceTimeBy(64); waitForIdle()
    }

    @Test fun endLandsOnTheRealBottomNotJustTheLastPageStart() = runSkikoComposeUiTest(size = Size(1200f, 900f)) {
        val state = ScrollReadState(3)
        lateinit var scope: CoroutineScope
        tall(state, 1800) { scope = it }
        scope.launch { state.scrollToEnd() }
        mainClock.advanceTimeBy(64); waitForIdle()
        assertEquals(2, state.position.page)
        assertFalse(state.canScrollForward, "End must reach the true bottom")
        assertEquals(1f, state.overallProgress)
    }

    @Test fun oneLongPageScrolledToTheBottomReportsFullProgressAndShowsTheScrollbar() = runSkikoComposeUiTest(size = Size(1200f, 900f)) {
        val state = ScrollReadState(1)
        lateinit var scope: CoroutineScope
        tall(state, 1800) { scope = it }
        assertEquals(0f, state.overallProgress)
        scope.launch { state.list.scrollBy(5000f) }
        mainClock.advanceTimeBy(64); waitForIdle()
        assertFalse(state.canScrollForward)
        assertEquals(1f, state.overallProgress)
        onNodeWithTag("scroll-read-scrollbar").assertExists()
    }

    @Test fun thumbLengthDiffersBetweenAShortAndALongBook() = runSkikoComposeUiTest(size = Size(1200f, 900f)) {
        val short = ScrollReadState(3); val long = ScrollReadState(60)
        var shortSize = 0.0; var longSize = 0.0; var viewport = 0.0
        mainClock.autoAdvance = false
        setContent {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                Box(Modifier.height(450.dp)) {
                    ScrollReadView(short, androidx.compose.ui.graphics.Color.LightGray, androidx.compose.ui.graphics.Color.White, Modifier.fillMaxSize(),
                        scrollbar = { st -> androidx.compose.foundation.rememberScrollbarAdapter(st.list).let { shortSize = it.contentSize; viewport = it.viewportSize }; ReadScrollbarDesktop(st) }) { Box(Modifier.height(900.dp)) }
                }
                Box(Modifier.height(450.dp)) {
                    ScrollReadView(long, androidx.compose.ui.graphics.Color.LightGray, androidx.compose.ui.graphics.Color.White, Modifier.fillMaxSize(),
                        scrollbar = { st -> androidx.compose.foundation.rememberScrollbarAdapter(st.list).let { longSize = it.contentSize }; ReadScrollbarDesktop(st) }) { Box(Modifier.height(900.dp)) }
                }
            }
        }
        mainClock.advanceTimeBy(64); waitForIdle()
        onAllNodesWithTag("scroll-read-scrollbar").assertCountEquals(2)
        assertTrue(longSize > shortSize * 5, "content size should reflect the book length: short=$shortSize long=$longSize")
        // 滑块长度 = 视口 / 内容：长书的滑块明显更短
        assertTrue(viewport / longSize < viewport / shortSize)
    }

    @Test fun draggingTheThumbScrollsThePage() = runSkikoComposeUiTest(size = Size(1200f, 900f)) {
        val state = ScrollReadState(10)
        lateinit var scope: CoroutineScope
        tall(state, 900) { scope = it }
        assertEquals(0, state.position.page)
        onNodeWithTag("scroll-read-scrollbar").performMouseInput {
            moveTo(androidx.compose.ui.geometry.Offset(width / 2f, 20f))
            press()
            moveTo(androidx.compose.ui.geometry.Offset(width / 2f, 500f))
            release()
        }
        mainClock.advanceTimeBy(100); waitForIdle()
        assertTrue(state.position.page >= 3, "thumb drag should scroll, at ${state.position}")
    }

    @Test fun scrollbarShotsLightAndDark() {
        for (dark in listOf(false, true)) runSkikoComposeUiTest(size = Size(1200f, 900f)) {
            val state = ScrollReadState(20)
            lateinit var scope: CoroutineScope
            host(state, dark = dark) { scope = it }
            scope.launch { state.list.scrollBy(900f) }
            mainClock.advanceTimeBy(64); waitForIdle()
            captureLessonEvidence("scroll-read-scrollbar-" + (if (dark) "dark" else "light"), 1200, 900)
        }
    }
}
