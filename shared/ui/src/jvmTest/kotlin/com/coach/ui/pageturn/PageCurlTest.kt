package com.coach.ui.pageturn

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.captureLessonEvidence
import com.coach.ui.v7.LocalV7ReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PageCurlMathTest {
    private val w = 600f
    private val h = 800f

    private fun area(poly: List<Offset>): Float {
        var s = 0f
        for (i in poly.indices) { val a = poly[i]; val b = poly[(i + 1) % poly.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2f
    }

    private fun near(a: Offset, b: Offset, eps: Float = 0.05f) =
        assertTrue((a - b).getDistance() < eps, "expected $b, got $a")

    @Test fun progressFromCorner() {
        assertEquals(0f, curlProgressAt(w, w)); assertEquals(1f, curlProgressAt(-w, w)); assertEquals(0.5f, curlProgressAt(0f, w))
        assertEquals(0f, curlDragProgress(-w, w, forward = false)); assertEquals(1f, curlDragProgress(w, w, forward = false))
    }

    @Test fun pathStartsAtCornerAndLandsMirrored() {
        near(curlPointAt(0f, w, h, top = false), Offset(w, h))
        near(curlPointAt(1f, w, h, top = false), Offset(-w, h))
        near(curlPointAt(1f, w, h, top = true), Offset(-w, 0f))
        // 自动翻页中途页角抬起，离开下沿
        assertTrue(curlPointAt(0.5f, w, h, top = false).y < h - 1f)
    }

    @Test fun releaseContinuesFromWhereTheHandLetGo() {
        val anchor = Offset(0.2f, 0.7f)
        val pa = curlProgressAt(anchor.x * w, w)
        near(curlPointAt(pa, w, h, top = false, anchor = anchor), constrainCurlPoint(Offset(anchor.x * w, anchor.y * h), w, h, false))
        near(curlPointAt(0f, w, h, top = false, anchor = anchor), Offset(w, h))
        near(curlPointAt(1f, w, h, top = false, anchor = anchor), Offset(-w, h))
    }

    @Test fun paperStaysPinnedToSpine() {
        val q = constrainCurlPoint(Offset(-3000f, -2000f), w, h, top = false)
        assertTrue((q - Offset(0f, h)).getDistance() <= w + 0.01f)
        assertTrue((q - Offset(0f, 0f)).getDistance() <= kotlin.math.hypot(w, h) + 0.01f)
        val inside = Offset(300f, 700f)
        assertEquals(inside, constrainCurlPoint(inside, w, h, top = false))
    }

    @Test fun foldKeepsPaperArea() {
        val flat = curlFold(Offset(w, h), w, h, top = false)
        assertEquals(w * h, area(flat.front), 1f); assertTrue(flat.flap.isEmpty()); assertEquals(0f, flat.depth)
        for (p in listOf(0.1f, 0.35f, 0.5f, 0.8f)) {
            val g = curlFold(curlPointAt(p, w, h, top = false), w, h, top = false)
            assertEquals(w * h, area(g.front) + area(g.removed), 1f, "p=$p")
            assertEquals(area(g.removed), area(g.flap), 1f, "p=$p")
            // 掀起的部分翻到折线另一侧
            for (v in g.flap) assertTrue((v.x - g.mid.x) * g.normal.x + (v.y - g.mid.y) * g.normal.y <= 0.01f)
        }
        val landed = curlFold(Offset(-w, h), w, h, top = false)
        assertEquals(0f, area(landed.front), 1f)
        assertEquals(w * h, area(landed.flap), 1f)
        assertTrue(landed.flap.all { it.x <= 0.01f && it.x >= -w - 0.01f })
    }

    @Test fun backFaceIsUprightOnSpreadAndMirroredOnSinglePage() {
        val g = curlFold(curlPointAt(0.4f, w, h, top = false), w, h, top = false)
        val spread = curlBackMatrix(g.mid, g.normal, w, otherPage = true)
        val single = curlBackMatrix(g.mid, g.normal, w, otherPage = false)
        for (x in g.removed) {
            // 纸上 x 点的背面，在下一页上是左右对称的位置；翻过来后落在 x 沿折线的镜像处
            near(spread.map(Offset(w - x.x, x.y)), reflectAcross(x, g.mid, g.normal), 0.1f)
            near(single.map(x), reflectAcross(x, g.mid, g.normal), 0.1f)
        }
        fun det(m: androidx.compose.ui.graphics.Matrix) = m.values[0] * m.values[5] - m.values[4] * m.values[1]
        assertEquals(1f, det(spread), 1e-3f) // 两次反射 = 转动，字是正的
        assertEquals(-1f, det(single), 1e-3f)
        // 翻完：下一张的左页正好落在左边那一格
        val end = curlFold(Offset(-w, h), w, h, top = false)
        near(curlBackMatrix(end.mid, end.normal, w, otherPage = true).map(Offset(10f, 20f)), Offset(10f - w, 20f))
    }
}

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class PageCurlViewTest {
    private fun fakePage(i: Int) = @androidx.compose.runtime.Composable {
        Box(Modifier.fillMaxSize().padding(48.dp)) { Text("第 ${i + 1} 页\n" + "纸上的字。".repeat(60)) }
    }

    private fun SkikoComposeUiTest.host(state: PageTurnState, dark: Boolean, spreadMinDp: Int = 1400, reduced: Boolean = false, style: PageTurnStyle = PageTurnStyle.Curl, scopeOut: (CoroutineScope) -> Unit) {
        val sheetC = if (dark) CoachV7.Dark.sheet else CoachV7.Light.sheet
        val inkC = if (dark) CoachV7.Dark.ink else CoachV7.Light.ink
        mainClock.autoAdvance = false
        setContent {
            scopeOut(rememberCoroutineScope())
            CompositionLocalProvider(LocalV7ReducedMotion provides reduced) {
                PageTurnView(state, paper = sheetC, style = style, spreadMinDp = spreadMinDp, modifier = Modifier.fillMaxSize()) { i ->
                    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides inkC) { fakePage(i)() }
                }
            }
        }
        mainClock.advanceTimeBy(64); waitForIdle()
    }

    @Test fun curlTurnsBothWaysLightDark() = runSkikoComposeUiTest(size = Size(1600f, 900f)) {
        for (dark in listOf(false, true)) {
            val tag = if (dark) "dark" else "light"
            val state = PageTurnState(20)
            lateinit var scope: CoroutineScope
            host(state, dark) { scope = it }
            assertEquals(2, state.columns)
            scope.launch { state.turn(true) }
            mainClock.advanceTimeBy(130); waitForIdle()
            assertTrue(state.turning, "mid-turn")
            captureLessonEvidence("page-curl-mid-$tag")
            mainClock.advanceTimeBy(600); waitForIdle()
            assertFalse(state.turning); assertEquals(2, state.position)
            captureLessonEvidence("page-curl-landed-$tag")
            // 拖着下角到页面中间，松手不到一半：回弹
            scope.launch {
                if (state.beginDrag(true)) {
                    state.dragCurl(curlDragProgress(0.35f * 800f, 800f, true), Offset(0.35f, 0.82f), top = false)
                }
            }
            mainClock.advanceTimeBy(32); waitForIdle()
            assertTrue(state.turning)
            captureLessonEvidence("page-curl-drag-$tag")
            scope.launch { state.endDrag(false) }
            mainClock.advanceTimeBy(600); waitForIdle()
            assertFalse(state.turning); assertEquals(2, state.position)
            // 往回翻：左页从书口卷回右边
            scope.launch { state.turn(false) }
            mainClock.advanceTimeBy(130); waitForIdle()
            assertTrue(state.turning)
            captureLessonEvidence("page-curl-back-$tag")
            mainClock.advanceTimeBy(600); waitForIdle()
            assertEquals(0, state.position)
        }
    }

    @Test fun curlSinglePageLightDark() = runSkikoComposeUiTest(size = Size(900f, 900f)) {
        for (dark in listOf(false, true)) {
            val state = PageTurnState(10)
            lateinit var scope: CoroutineScope
            host(state, dark) { scope = it }
            assertEquals(1, state.columns)
            scope.launch { state.turn(true) }
            mainClock.advanceTimeBy(130); waitForIdle()
            assertTrue(state.turning)
            captureLessonEvidence("page-curl-single-mid-" + if (dark) "dark" else "light")
            mainClock.advanceTimeBy(600); waitForIdle()
            assertEquals(1, state.position)
            scope.launch { state.turn(false) }
            mainClock.advanceTimeBy(700); waitForIdle()
            assertEquals(0, state.position)
        }
    }

    @Test fun mouseDragFromCornerLiftsAndTurns() = runSkikoComposeUiTest(size = Size(1600f, 900f)) {
        val state = PageTurnState(20)
        lateinit var scope: CoroutineScope
        host(state, dark = false) { scope = it }
        onRoot().performMouseInput {
            moveTo(Offset(1590f, 890f)); press()
            for (x in listOf(1500f, 1300f, 1000f, 700f, 300f)) moveTo(Offset(x, 700f))
            release()
        }
        mainClock.advanceTimeBy(800); waitForIdle()
        // 页角跟着手走过（拖动时记下的页角位置还在）
        assertTrue(state.curlAnchor != null, "drag should move the corner")
        assertFalse(state.curlTop)
        assertFalse(state.turning)
        assertEquals(2, state.position)
        // 慢慢拖一小段就松手：回弹，不翻（拖得快会按甩出去算，翻过去）
        onRoot().performMouseInput {
            moveTo(Offset(1590f, 10f)); press()
            for (x in listOf(1560f, 1520f, 1480f, 1440f, 1400f)) { advanceEventTime(250); moveTo(Offset(x, 60f)) }
            advanceEventTime(250); moveTo(Offset(1400f, 60f))
            release()
        }
        mainClock.advanceTimeBy(800); waitForIdle()
        assertFalse(state.turning)
        assertEquals(2, state.position)
        assertTrue(state.curlTop)
    }

    @Test fun reducedMotionCurlSwapsDirectly() = runSkikoComposeUiTest(size = Size(1600f, 900f)) {
        val state = PageTurnState(10)
        lateinit var scope: CoroutineScope
        host(state, dark = false, reduced = true) { scope = it }
        scope.launch { state.turn(true) }
        mainClock.advanceTimeBy(16); waitForIdle()
        assertFalse(state.turning); assertEquals(2, state.position)
        var everTurning = false
        onRoot().performMouseInput {
            moveTo(Offset(1590f, 890f)); press()
            moveTo(Offset(1200f, 800f)); everTurning = everTurning || state.turning
            moveTo(Offset(300f, 800f)); everTurning = everTurning || state.turning
            release()
        }
        mainClock.advanceTimeBy(64); waitForIdle()
        assertFalse(everTurning)
        assertEquals(4, state.position)
    }

    /**
     * 卷页一帧的耗时（翻转同测作对照）：2560×1440 @150% 和 1366×768 @100% 各翻 200 帧，每帧含重组、布局、画到离屏画面再读回。
     * 读回本身比上屏慢，所以这是上限；数字打到日志里，不设门槛（机器不同差别大）。
     */
    @Test fun curlFrameTimeP95() {
        for (style in listOf(PageTurnStyle.Curl, PageTurnStyle.Flip)) for ((size, dens) in listOf(Size(2560f, 1440f) to 1.5f, Size(1366f, 768f) to 1f)) {
            runSkikoComposeUiTest(size = size, density = Density(dens)) {
                val state = PageTurnState(400)
                lateinit var scope: CoroutineScope
                host(state, dark = false, spreadMinDp = if (dens > 1f) 1400 else 1200, style = style) { scope = it }
                val frames = ArrayList<Double>(200)
                repeat(220) { i ->
                    if (!state.turning) scope.launch { state.turn(true) }
                    val t0 = System.nanoTime()
                    mainClock.advanceTimeBy(16); waitForIdle()
                    onRoot().captureToImage()
                    val ms = (System.nanoTime() - t0) / 1e6
                    if (i >= 20) frames += ms // 前 20 帧是预热
                }
                frames.sort()
                val p95 = frames[(frames.size * 95 / 100) - 1]
                println("CURL_FRAME style=${style.name} size=${size.width.toInt()}x${size.height.toInt()}@${(dens * 100).toInt()}%% columns=${state.columns} n=${frames.size} p50=%.2fms p95=%.2fms max=%.2fms"
                    .format(frames[frames.size / 2], p95, frames.last()))
                assertTrue(state.position > 0)
            }
        }
    }
}
