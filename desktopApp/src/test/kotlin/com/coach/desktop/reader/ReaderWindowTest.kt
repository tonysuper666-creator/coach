package com.coach.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.*

/** 用 ImageComposeScene 驱动真实组件：键盘事件进组件、缩放窗口、截图。材料全是自造样张。 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ReaderWindowTest {
    private class Rig(val session: ReaderSession, widthDp: Int, heightDp: Int, val density: Density) {
        var t = 0L
        val scene = ImageComposeScene((widthDp * density.density).toInt(), (heightDp * density.density).toInt(), density) { ReaderWindow(session) }
        init { frames(6) }
        fun frames(n: Int = 3) { repeat(n) { t += 16_000_000L; scene.render(t).close() } }
        fun key(k: Key) {
            scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp))
            frames()
        }
        fun wheel(dy: Float, ctrl: Boolean = false, xDp: Int = 400, yDp: Int = 300) {
            val pos = Offset(xDp * density.density, yDp * density.density)
            scene.sendPointerEvent(PointerEventType.Move, pos)
            scene.sendPointerEvent(PointerEventType.Scroll, pos, scrollDelta = Offset(0f, dy), keyboardModifiers = PointerKeyboardModifiers(isCtrlPressed = ctrl))
            frames()
        }
        fun resizeDp(w: Int, h: Int) { scene.constraints = Constraints.fixed((w * density.density).toInt(), (h * density.density).toInt()); frames(6) }
        fun shot(name: String) {
            val dir = File("build/reader-shots").also { it.mkdirs() }
            t += 16_000_000L
            scene.render(t).use { img -> img.encodeToData()!!.use { File(dir, "$name.png").writeBytes(it.bytes) } }
        }
        fun close() = scene.close()
    }

    private fun rig(w: Int, h: Int = 700, d: Density = Density(1f)) = Rig(ReaderSession(ReaderFixture.synthetic(), w), w, h, d)

    @Test fun wideWindowShowsThreePanesAndSpacePages() {
        val r = rig(1440)
        try {
            assertEquals(listOf(ReaderPane.Shelf, ReaderPane.Body, ReaderPane.Related), r.session.renderedPanes)
            r.session.focusPane(ReaderPane.Body); r.frames()
            r.key(Key.Spacebar); r.key(Key.DirectionRight)
            assertEquals(2, r.session.state.position.page)
            r.key(Key.MoveEnd); assertEquals(7, r.session.state.position.page)
            r.shot("wide-1440")
        } finally { r.close() }
    }

    @Test fun questionToSourceAndBackKeepsAnswerSelectionAndFocus() {
        val r = rig(1440)
        try {
            r.session.focusPane(ReaderPane.Related); r.frames()
            r.key(Key.Two)
            r.key(Key.DirectionDown)
            r.key(Key.Enter)
            assertEquals(5, r.session.state.position.page)
            assertEquals("p5-s0", r.session.state.flashAnchor)
            assertEquals(ReaderPane.Body, r.session.state.focus)
            r.shot("provenance-wide")
            r.key(Key.Escape)
            assertEquals(0, r.session.state.position.page)
            assertEquals(ReaderPane.Related, r.session.state.focus)
            assertEquals(1, r.session.selectedQuestion)
            assertEquals(1, r.session.answers[0])
        } finally { r.close() }
    }

    @Test fun narrowWindowCollapsesAndReturnLandsOnQuestions() {
        val r = rig(520)
        try {
            assertEquals(listOf(ReaderPane.Body), r.session.renderedPanes)
            r.session.focusPane(ReaderPane.Related); r.frames()
            assertEquals(listOf(ReaderPane.Related), r.session.renderedPanes)
            r.key(Key.Enter)
            assertEquals(2, r.session.state.position.page)
            assertEquals(listOf(ReaderPane.Body), r.session.renderedPanes)
            r.shot("provenance-narrow")
            r.key(Key.Escape)
            assertEquals(listOf(ReaderPane.Related), r.session.renderedPanes)
            assertEquals(0, r.session.state.position.page)
        } finally { r.close() }
    }

    @Test fun resizingAcrossBreakpointsKeepsPositionAnswersAndFocus() {
        val r = rig(1440)
        try {
            r.session.focusPane(ReaderPane.Body); r.frames()
            r.key(Key.PageDown); r.key(Key.PageDown)
            r.session.focusPane(ReaderPane.Related); r.frames(); r.key(Key.One)
            for (w in listOf(1100, 1099, 700, 699, 520, 1440)) {
                r.resizeDp(w, 700)
                assertEquals(2, r.session.state.position.page, "width $w")
                assertEquals(0, r.session.answers[0], "width $w")
                assertEquals(ReaderPane.Related, r.session.state.focus, "width $w")
            }
            r.resizeDp(700, 700)
            assertEquals(listOf(ReaderPane.Body, ReaderPane.Related), r.session.renderedPanes)
            r.shot("medium-700")
        } finally { r.close() }
    }

    @Test fun missingSourceIsStatedNotGuessed() {
        val r = rig(1440)
        try {
            r.session.focusPane(ReaderPane.Related); r.frames()
            r.key(Key.DirectionDown); r.key(Key.DirectionDown); r.key(Key.Enter)
            assertEquals(0, r.session.state.position.page)
            assertTrue(r.session.state.provenanceStack.isEmpty())
            assertEquals("这一页没找到出处", r.session.message)
        } finally { r.close() }
    }

    @Test fun unknownPageCountDoesNotPage() {
        val s = ReaderSession(ReaderFixture("空样张", emptyList(), emptyList()), 1440)
        val r = Rig(s, 1440, 700, Density(1f))
        try {
            r.session.focusPane(ReaderPane.Body); r.frames()
            r.key(Key.PageDown)
            assertEquals(0, s.state.position.page)
            assertEquals(0, s.state.pageCount)
        } finally { r.close() }
    }

    @Test fun tabLeavesPaneInsteadOfTrappingFocus() {
        val r = rig(1440)
        try {
            r.session.focusPane(ReaderPane.Related); r.frames()
            assertFalse(r.session.onKey(KeyInput(ReaderKey.Tab)), "最后一栏 Tab 应放行给系统")
            r.session.focusPane(ReaderPane.Shelf); r.frames()
            assertTrue(r.session.onKey(KeyInput(ReaderKey.Tab)))
            assertEquals(ReaderPane.Body, r.session.state.focus)
        } finally { r.close() }
    }

    @Test fun highDensityAndLargeFontStillRenderAndKeepState() {
        val r = rig(900, 700, Density(2f, 1.5f))
        try {
            assertEquals(listOf(ReaderPane.Body, ReaderPane.Related), r.session.renderedPanes)
            r.session.focusPane(ReaderPane.Body); r.frames()
            r.key(Key.DirectionRight)
            assertEquals(1, r.session.state.position.page)
            r.shot("density2-font1.5-900")
        } finally { r.close() }
    }

    @Test fun snapshotKeepsAnswersSelectionFocusAndPageAcrossRecreatedSession() {
        val a = rig(1440)
        val text: String
        try {
            a.session.focusPane(ReaderPane.Related); a.frames()
            a.key(Key.Two); a.key(Key.DirectionDown); a.key(Key.Enter)
            assertEquals(5, a.session.state.position.page)
            text = a.session.snapshot()
        } finally { a.close() }
        // 旧行为（只存阅读状态）不含作答与选题；新快照带上。
        val oldText = text.lines().takeWhile { !it.startsWith("sel=") }.joinToString("\n")
        val b0 = ReaderSession(ReaderFixture.synthetic(), 1440)
        assertTrue(b0.restore(oldText, 1440))
        assertTrue(b0.answers.isEmpty()); assertEquals(0, b0.selectedQuestion)
        val b = rig(1440)
        try {
            assertTrue(b.session.restore(text, 1440)); b.frames()
            assertEquals(mapOf(0 to 1), b.session.answers.toMap())
            assertEquals(1, b.session.selectedQuestion)
            assertEquals(5, b.session.state.position.page)
            b.key(Key.Escape)
            assertEquals(0, b.session.state.position.page)
            assertEquals(ReaderPane.Related, b.session.state.focus)
        } finally { b.close() }
    }

    @Test fun badSnapshotValuesAreDroppedNotGuessed() {
        val s = ReaderSession(ReaderFixture.synthetic(), 1440)
        assertTrue(s.restore("v=1\npage=2\nsel=9\nans=0:7\nans=5:0\nans=x\nans=1:2\n", 1440))
        assertEquals(0, s.selectedQuestion)
        assertEquals(mapOf(1 to 2), s.answers.toMap())
        assertTrue(s.restore("v=1\npage=2\n", 1440)); assertTrue(s.answers.isEmpty())
        val unknown = ReaderSession(ReaderFixture("空", emptyList(), emptyList()), 1440)
        assertTrue(unknown.restore("v=1\npage=7\nback=3|x\n", 1440))
        assertEquals(0, unknown.state.position.page); assertTrue(unknown.state.provenanceStack.isEmpty())
    }

    @Test fun wheelPagesBodyAndCtrlWheelZoomsWithinBounds() {
        val r = rig(1440)
        try {
            r.wheel(1f); r.wheel(1f)
            assertEquals(2, r.session.state.position.page)
            r.wheel(-1f); assertEquals(1, r.session.state.position.page)
            r.wheel(-1f); r.wheel(-1f); assertEquals(0, r.session.state.position.page)
            r.wheel(-1f, ctrl = true); assertEquals(110, r.session.state.zoomPercent)
            repeat(40) { r.wheel(-1f, ctrl = true) }; assertEquals(300, r.session.state.zoomPercent)
            repeat(40) { r.wheel(1f, ctrl = true) }; assertEquals(50, r.session.state.zoomPercent)
            assertEquals(0, r.session.state.position.page, "Ctrl 滚轮不翻页")
            r.session.focusPane(ReaderPane.Related); r.frames(); r.wheel(1f)
            assertEquals(ReaderPane.Related, r.session.state.focus)
        } finally { r.close() }
    }

    @Test fun realSevenHundredWideCanvasAndWheelAfterResizing() {
        val r = rig(700)
        try {
            assertEquals(listOf(ReaderPane.Body, ReaderPane.Related), r.session.renderedPanes)
            r.wheel(1f, xDp = 200); assertEquals(1, r.session.state.position.page)
            r.shot("real-700-canvas")
            r.resizeDp(699, 700); assertEquals(listOf(ReaderPane.Body), r.session.renderedPanes)
            assertEquals(1, r.session.state.position.page)
            r.resizeDp(700, 700); assertEquals(1, r.session.state.position.page)
        } finally { r.close() }
    }

    @Test fun snapshotRestoresAfterWindowRecreated() {
        val a = rig(1440)
        val text: String
        try {
            a.session.focusPane(ReaderPane.Body); a.frames(); a.key(Key.PageDown); a.key(Key.PageDown)
            text = a.session.snapshot()
        } finally { a.close() }
        val b = rig(520)
        try {
            assertTrue(b.session.restore(text, 520)); b.frames()
            assertEquals(2, b.session.state.position.page)
        } finally { b.close() }
    }
}
