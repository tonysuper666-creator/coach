package com.coach.ui.pageturn

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.captureLessonEvidence
import com.coach.ui.v7.LocalV7ReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageTurnMathTest {
    @Test fun columnsSwitchAt1400() {
        assertEquals(1, pageTurnColumns(1399f)); assertEquals(2, pageTurnColumns(1400f))
    }
    @Test fun dragProgressForwardAndBack() {
        assertEquals(0.5f, dragProgress(1000f, 550f, true, 900f), 1e-4f)
        assertEquals(0f, dragProgress(1000f, 1200f, true, 900f))
        assertEquals(1f, dragProgress(100f, 5000f, false, 900f))
    }
    @Test fun releaseRules() {
        assertTrue(shouldCompleteTurn(0.5f, 0f)); assertFalse(shouldCompleteTurn(0.49f, 0f))
        assertTrue(shouldCompleteTurn(0.2f, 1200f)); assertFalse(shouldCompleteTurn(0.8f, -1200f))
    }
    @Test fun stepsAndEnds() {
        assertEquals(2, stepPosition(0, 10, 2, true)); assertNull(stepPosition(8, 10, 2, true))
        assertNull(stepPosition(0, 10, 2, false)); assertEquals(0, stepPosition(2, 10, 2, false))
        assertEquals(4, normalizePosition(5, 10, 2)); assertEquals(5, normalizePosition(5, 10, 1))
        assertEquals(9, stepPosition(8, 10, 1, true)); assertNull(stepPosition(9, 10, 1, true))
    }
    @Test fun riffleCapped() {
        assertEquals(0, riffleLeaves(4, 4, 2)); assertEquals(6, riffleLeaves(0, 200, 2)); assertEquals(2, riffleLeaves(0, 4, 2))
    }
    @Test fun leafGeometry() {
        assertEquals(-180f, leafAngle(1f, true, 2)); assertEquals(180f, leafAngle(1f, false, 2)); assertEquals(-90f, leafAngle(1f, true, 1))
        assertFalse(showingBack(0.5f, 2)); assertTrue(showingBack(0.51f, 2)); assertFalse(showingBack(1f, 1))
        assertEquals(1f, castShadowAlpha(0.5f)); assertEquals(0f, castShadowAlpha(0f))
    }
}

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class PageTurnViewTest {
    private fun fakePage(i: Int) = @androidx.compose.runtime.Composable { Box(Modifier.fillMaxSize().padding(48.dp)) { Text("第 ${i + 1} 页\n" + "纸上的字。".repeat(60)) } }

    @Test fun turnsMidAndLandsLightDark() = runSkikoComposeUiTest(size = androidx.compose.ui.geometry.Size(1600f, 900f)) {
        for (style in listOf(PageTurnStyle.Flip, PageTurnStyle.Slide)) for (dark in listOf(false, true)) {
            val sheetC = if (dark) CoachV7.Dark.sheet else CoachV7.Light.sheet
            val inkC = if (dark) CoachV7.Dark.ink else CoachV7.Light.ink
            val state = PageTurnState(20)
            lateinit var scope: CoroutineScope
            mainClock.autoAdvance = false
            setContent {
                scope = rememberCoroutineScope()
                Box(Modifier.fillMaxSize()) {
                    PageTurnView(state, paper = sheetC, style = style, modifier = Modifier.fillMaxSize()) { i ->
                        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides inkC) { fakePage(i)() }
                    }
                }
            }
            val tag = "${style.name.lowercase()}-" + if (dark) "dark" else "light"
            // 2000dp 宽 -> 双页
            runOnIdle { }
            mainClock.advanceTimeBy(64); waitForIdle()
            assertEquals(2, state.columns)
            scope.launch { state.turn(true) }
            mainClock.advanceTimeBy(120)
            waitForIdle()
            assertTrue(state.turning, "mid-turn")
            captureLessonEvidence("page-turn-mid-$tag")
            mainClock.advanceTimeBy(600)
            waitForIdle()
            assertFalse(state.turning)
            assertEquals(state.columns, state.position)
            captureLessonEvidence("page-turn-landed-$tag")
            // 回弹：拖一半不到，endDrag(false)
            scope.launch { if (state.beginDrag(true)) { state.dragTo(0.3f); state.endDrag(false) } }
            mainClock.advanceTimeBy(100); waitForIdle()
            captureLessonEvidence("page-turn-rebound-$tag")
            mainClock.advanceTimeBy(600); waitForIdle()
            assertFalse(state.turning)
            val before = state.position
            scope.launch { state.jumpTo(19) }
            mainClock.advanceTimeBy(50); waitForIdle()
            assertTrue(state.riffle > 0)
            captureLessonEvidence("page-turn-riffle-$tag")
            mainClock.advanceTimeBy(2000); waitForIdle()
            assertEquals(normalizePosition(19, 20, state.columns), state.position)
            assertTrue(state.position > before)
        }
    }

    @Test fun noneStyleSwapsImmediately() = runSkikoComposeUiTest(size = androidx.compose.ui.geometry.Size(1600f, 900f)) {
        val state = PageTurnState(10)
        lateinit var scope: CoroutineScope
        mainClock.autoAdvance = false
        setContent {
            scope = rememberCoroutineScope()
            PageTurnView(state, paper = Color.White, style = PageTurnStyle.None, modifier = Modifier.fillMaxSize()) { Text("p$it") }
        }
        mainClock.advanceTimeBy(64); waitForIdle()
        scope.launch { state.turn(true) }
        mainClock.advanceTimeBy(16); waitForIdle()
        assertFalse(state.turning); assertEquals(state.columns, state.position)
        scope.launch { state.jumpTo(9) }
        mainClock.advanceTimeBy(16); waitForIdle()
        assertEquals(normalizePosition(9, 10, state.columns), state.position)
    }

    @Test fun reducedMotionSwapsImmediately() = runSkikoComposeUiTest(size = androidx.compose.ui.geometry.Size(1600f, 900f)) {
        val state = PageTurnState(10)
        lateinit var scope: CoroutineScope
        mainClock.autoAdvance = false
        setContent {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                PageTurnView(state, paper = Color.White, modifier = Modifier.fillMaxSize()) { Text("p$it") }
            }
        }
        mainClock.advanceTimeBy(64); waitForIdle()
        scope.launch { state.turn(true) }
        mainClock.advanceTimeBy(16); waitForIdle()
        assertFalse(state.turning)
        assertEquals(state.columns, state.position)
    }

    private fun androidx.compose.ui.test.SkikoComposeUiTest.host(state: PageTurnState, reduced: Boolean = false, minDp: androidx.compose.runtime.MutableState<Int>? = null, scopeOut: (CoroutineScope) -> Unit) {
        mainClock.autoAdvance = false
        setContent {
            scopeOut(rememberCoroutineScope())
            CompositionLocalProvider(LocalV7ReducedMotion provides reduced) {
                PageTurnView(state, paper = Color.White, spreadMinDp = minDp?.value ?: 1400, modifier = Modifier.fillMaxSize()) { Text("p$it") }
            }
        }
        mainClock.advanceTimeBy(64); waitForIdle()
    }

    @Test fun layoutChangeCancelsDragAndTurnWorksAfter() = runSkikoComposeUiTest(size = androidx.compose.ui.geometry.Size(1600f, 900f)) {
        val state = PageTurnState(20)
        val minDp = androidx.compose.runtime.mutableStateOf(1400)
        lateinit var scope: CoroutineScope
        host(state, minDp = minDp) { scope = it }
        scope.launch { assertTrue(state.beginDrag(true)); state.dragTo(0.4f) }
        mainClock.advanceTimeBy(32); waitForIdle()
        assertTrue(state.turning)
        minDp.value = 3000
        mainClock.advanceTimeBy(64); waitForIdle()
        assertEquals(1, state.columns)
        assertFalse(state.turning)
        scope.launch { state.turn(true) }
        mainClock.advanceTimeBy(800); waitForIdle()
        assertTrue(state.position > 0)
    }

    @Test fun singleToDoubleMidTurnKeepsEvenPosition() = runSkikoComposeUiTest(size = androidx.compose.ui.geometry.Size(1600f, 900f)) {
        val state = PageTurnState(20)
        val minDp = androidx.compose.runtime.mutableStateOf(3000)
        lateinit var scope: CoroutineScope
        host(state, minDp = minDp) { scope = it }
        assertEquals(1, state.columns)
        scope.launch { state.turn(true) }
        mainClock.advanceTimeBy(100); waitForIdle()
        minDp.value = 1400
        mainClock.advanceTimeBy(800); waitForIdle()
        assertEquals(2, state.columns); assertFalse(state.turning)
        assertEquals(0, state.position % 2)
    }

    @Test fun riffleShowsDifferentPagesAlongTheWay() = runSkikoComposeUiTest(size = androidx.compose.ui.geometry.Size(1600f, 900f)) {
        val state = PageTurnState(40)
        lateinit var scope: CoroutineScope
        host(state) { scope = it }
        scope.launch { state.jumpTo(38) }
        val seen = mutableSetOf<Int>()
        repeat(40) { mainClock.advanceTimeBy(20); waitForIdle(); if (state.riffle > 0) seen += state.shownPosition }
        assertTrue(seen.size >= 3, "riffle should pass distinct pages, saw $seen")
        assertEquals(38, state.position)
    }

    @Test fun reducedMotionTouchSwipeSwapsWithoutAnimating() = runSkikoComposeUiTest(size = androidx.compose.ui.geometry.Size(1600f, 900f)) {
        val state = PageTurnState(20)
        lateinit var scope: CoroutineScope
        host(state, reduced = true) { scope = it }
        var everTurning = false
        onRoot().performTouchInput {
            down(androidx.compose.ui.geometry.Offset(1590f, 100f))
            moveTo(androidx.compose.ui.geometry.Offset(1000f, 100f)); everTurning = everTurning || state.turning
            moveTo(androidx.compose.ui.geometry.Offset(300f, 100f)); everTurning = everTurning || state.turning
            up()
        }
        mainClock.advanceTimeBy(64); waitForIdle()
        assertFalse(everTurning); assertFalse(state.turning)
        assertEquals(2, state.position)
    }
}
