package com.coach.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

/** 窗口 + 适配层 + 自造服务：真实画布、真实键鼠事件。服务是 FakeReaderService，不是 18791。 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ReaderRemoteWindowTest {
    private class Rig(val session: ReaderSession, widthDp: Int, heightDp: Int = 700) {
        var t = 0L
        val scene = ImageComposeScene(widthDp, heightDp, Density(1f)) { ReaderWindow(session) }
        init { frames(8) }
        fun frames(n: Int = 4) { repeat(n) { t += 16_000_000L; scene.render(t).close(); Thread.sleep(2) } }
        fun key(k: Key) {
            scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp)); frames(8)
        }
        fun wheel(dy: Float, xDp: Int = 400, yDp: Int = 300) {
            val pos = Offset(xDp.toFloat(), yDp.toFloat())
            scene.sendPointerEvent(PointerEventType.Move, pos)
            scene.sendPointerEvent(PointerEventType.Scroll, pos, scrollDelta = Offset(0f, dy)); frames(8)
        }
        fun shot(name: String) {
            val dir = File("build/reader-shots").also { it.mkdirs() }
            t += 16_000_000L
            scene.render(t).use { img -> img.encodeToData()!!.use { File(dir, "$name.png").writeBytes(it.bytes) } }
        }
        fun close() = scene.close()
    }

    private fun rig(s: FakeReaderService, w: Int): Rig {
        val remote = ReaderRemote(s)
        val session = ReaderSession(ReaderFixture("远端", emptyList(), emptyList()), w, remote)
        return Rig(session, w)
    }

    private fun service() = FakeReaderService(Triple("a", "va1", 10), Triple("b", "vb1", 6))
    private fun Rig.text() = (session.remote!!.page as? PageState.Ready)?.view?.text

    @Test fun windowRestoresServerPageThenKeysPageAndSavesProgress() {
        val s = service(); s.docs.getValue("a").apply { progressPage = 3; revision = 1 }
        val r = rig(s, 1440)
        try {
            runBlocking { session(r).remote!!.loadCatalog(); session(r).openRemote("a") }
            r.frames(10)
            assertEquals("【a】第3页自造正文", r.text())
            assertEquals(2, r.session.state.position.page, "pdfPage 3 → 页序 2")
            r.session.focusPane(ReaderPane.Body); r.frames()
            r.key(Key.PageDown)
            assertEquals("【a】第4页自造正文", r.text())
            assertEquals(4, s.docs.getValue("a").progressPage, "翻页后服务端进度随之保存")
            r.shot("remote-wide-a")
        } finally { r.close() }
    }

    @Test fun switchingBooksDropsOldPageAnswersAndQuestions() {
        val s = service()
        val r = rig(s, 1440)
        try {
            runBlocking { r.session.remote!!.loadCatalog(); r.session.openRemote("a") }
            r.frames(10); r.session.focusPane(ReaderPane.Body); r.frames(); r.key(Key.PageDown); r.key(Key.PageDown)
            assertEquals("【a】第3页自造正文", r.text())
            runBlocking { r.session.openRemote("b") }
            r.frames(12)
            assertEquals("【b】第1页自造正文", r.text())
            assertEquals(6, r.session.state.pageCount)
            val study = (r.session.remote!!.study as StudyState.Ready).view.questions.single().stem
            assertEquals("【b】第1页的题", study)
            assertTrue(s.pageCalls.none { it.first == "b" && it.second > 1 }, "B 书没有被请求过 A 书的页码")
            r.shot("remote-wide-b")
        } finally { r.close() }
    }

    @Test fun conflictBannerKeepsDraftAndWheelClearsStaleMessage() {
        val s = service()
        val r = rig(s, 700)
        try {
            runBlocking { r.session.remote!!.loadCatalog(); r.session.openRemote("a") }
            r.frames(10); r.session.focusPane(ReaderPane.Body); r.frames()
            s.otherDeviceSaves("a", 9)
            r.wheel(1f, xDp = 200)
            assertEquals(1, r.session.state.position.page)
            val c = r.session.remote!!.save as SaveState.Conflict
            assertEquals(2, c.localPage); assertEquals(9, c.remote?.pdfPage)
            assertEquals(9, s.docs.getValue("a").progressPage, "不覆盖另一台设备的进度")
            assertEquals("【a】第2页自造正文", r.text(), "本机仍在读第2页")
            r.shot("remote-700-conflict")
        } finally { r.close() }
    }

    @Test fun staleSourceMessageIsClearedByWheel() {
        val r = Rig(ReaderSession(ReaderFixture.synthetic(), 1440), 1440)
        try {
            r.session.focusPane(ReaderPane.Related); r.frames()
            r.key(Key.DirectionDown); r.key(Key.DirectionDown); r.key(Key.Enter)
            assertEquals("这一页没找到出处", r.session.message)
            r.wheel(1f)
            assertNull(r.session.message, "滚轮翻页后旧提示不应残留")
        } finally { r.close() }
    }

    @Test fun offlineAndUnauthorizedShowExplicitFailureOnCanvas() {
        val s = service()
        val r = rig(s, 1440)
        try {
            runBlocking { r.session.remote!!.loadCatalog(); r.session.openRemote("a") }
            r.frames(10); r.session.focusPane(ReaderPane.Body); r.frames()
            s.pageStatus = 401; r.key(Key.PageDown)
            assertEquals(PageState.Failed(ReaderFailure.Unauthorized), r.session.remote!!.page)
            assertEquals(1, r.session.state.position.page, "失败时位置保持用户翻到的页，不回退不猜")
            s.pageStatus = 200; s.saveStatus = null; r.key(Key.PageDown)
            assertEquals(SaveState.Offline(3), r.session.remote!!.save)
            r.shot("remote-offline")
        } finally { r.close() }
    }

    private fun session(r: Rig) = r.session
}
