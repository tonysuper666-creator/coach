package com.coach.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
