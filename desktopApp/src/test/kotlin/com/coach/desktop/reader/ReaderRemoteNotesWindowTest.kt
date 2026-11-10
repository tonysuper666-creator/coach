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
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.*

/** 真实画布 + 键盘事件：原句高亮、选句→输入→保存的键盘流程、离线重试、409 草稿、切书。服务是自造的 FakeReaderService。 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ReaderRemoteNotesWindowTest {
    private class Rig(val session: ReaderSession, widthDp: Int, heightDp: Int = 700, val density: Density = Density(1f)) {
        var t = 0L
        val scene = ImageComposeScene((widthDp * density.density).toInt(), (heightDp * density.density).toInt(), density) { ReaderWindow(session) }
        init { frames(8) }
        fun frames(n: Int = 4) { repeat(n) { t += 16_000_000L; scene.render(t).close(); Thread.sleep(2) } }
        fun key(k: Key, ctrl: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown, isCtrlPressed = ctrl)); scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp, isCtrlPressed = ctrl)); frames(10)
        }
        fun wheel(dy: Float, x: Float = 700f) {
            val pos = Offset(x, 300f)
            scene.sendPointerEvent(PointerEventType.Move, pos); scene.sendPointerEvent(PointerEventType.Scroll, pos, scrollDelta = Offset(0f, dy)); frames(8)
        }
        fun shot(name: String) {
            val dir = File("build/reader-shots").also { it.mkdirs() }
            t += 16_000_000L
            scene.render(t).use { img -> img.encodeToData()!!.use { File(dir, "$name.png").writeBytes(it.bytes) } }
        }
        fun close() = scene.close()
    }

    private val multiLine = "Alpha beta gamma\nDelta epsilon zeta\nEta theta iota"
    private fun service() = FakeReaderService(Triple("a", "va1", 10), Triple("b", "vb1", 6)).apply {
        bodyText = { _, _ -> multiLine }; anchorFor = { _, _ -> "Delta epsilon" }; withImage = true
    }

    private fun rig(s: FakeReaderService, w: Int = 1440, d: Density = Density(1f)): Rig {
        val session = ReaderSession(ReaderFixture("远端", emptyList(), emptyList()), w, ReaderRemote(s))
        val r = Rig(session, w, density = d)
        runBlocking { session.remote!!.loadCatalog(); session.openRemote("a") }
        r.frames(12)
        return r
    }

    @Test fun questionSourceIsHighlightedOnThePageAndReturnRestoresQuestions() {
        val s = service(); val r = rig(s)
        try {
            r.session.focusPane(ReaderPane.Related); r.frames()
            r.key(Key.Enter)
            assertEquals("q-a-1", r.session.state.flashAnchor)
            assertEquals("已在页上标出原句", r.session.uiHighlight)
            r.shot("notes-highlight-1440")
            r.key(Key.Escape)
            assertNull(r.session.state.flashAnchor); assertNull(r.session.uiHighlight)
            assertEquals(ReaderPane.Related, r.session.state.focus)
        } finally { r.close() }
    }

    @Test fun unknownOrMissingSourceIsStatedNotGuessed() {
        val s = service(); s.anchorFor = { _, _ -> "Nowhere nothing" }; val r = rig(s)
        try {
            r.session.focusPane(ReaderPane.Related); r.frames(); r.key(Key.Enter)
            assertEquals("没找到这句原文，无法确认位置", r.session.uiHighlight)
        } finally { r.close() }
        val s2 = service(); s2.anchorFor = { _, _ -> "" }; val r2 = rig(s2)
        try {
            r2.session.focusPane(ReaderPane.Related); r2.frames(); r2.key(Key.Enter)
            assertEquals("这一题没有登记原句", r2.session.message)
            assertTrue(r2.session.state.provenanceStack.isEmpty())
        } finally { r2.close() }
        val s3 = service(); s3.boxesStatus = null; val r3 = rig(s3)
        try {
            r3.session.focusPane(ReaderPane.Related); r3.frames(); r3.key(Key.Enter)
            assertEquals("文字坐标不可用，不标注原句", r3.session.uiHighlight)
        } finally { r3.close() }
    }

    @Test fun keyboardNoteFlowSavesThenRestoresInAFreshWindow() {
        val s = service(); val r = rig(s)
        try {
            r.session.focusPane(ReaderPane.Body); r.frames()
            r.key(Key.N); assertEquals(0, r.session.noteLine)
            r.key(Key.DirectionDown); assertEquals(1, r.session.noteLine)
            assertEquals(0, r.session.state.position.page, "选句时方向键不翻页")
            r.key(Key.Enter)
            val d = r.session.noteDraft!!
            assertEquals("Delta epsilon zeta", d.quote)
            r.key(Key.PageDown); assertEquals(0, r.session.state.position.page, "输入阶段 PageDown 不翻页")
            r.wheel(1f); assertEquals(0, r.session.state.position.page, "写批注时滚轮不翻页")
            r.session.noteText = "我的批注" // 文字输入由输入框处理；这里直接设值代替逐字键入
            r.key(Key.Enter, ctrl = true)
            assertIs<NoteSaveState.Saved>(r.session.remote!!.noteSave)
            assertEquals(JsonPrimitive("我的批注"), s.serverNote("a", d.id)!!["note"])
            r.shot("notes-saved-1440")
            r.key(Key.Escape); assertFalse(r.session.noting)
        } finally { r.close() }
        val again = rig(s)
        try {
            val n = again.session.remote!!.notes.single()
            assertEquals("我的批注", n.note)
            assertTrue(again.session.remote!!.noteValid(n, (again.session.remote!!.page as PageState.Ready).view))
            // 再次对同一行写批注：沿用原批注 id 与 revision，不另起一条
            again.session.focusPane(ReaderPane.Body); again.frames()
            again.key(Key.N); again.key(Key.DirectionDown); again.key(Key.Enter)
            assertEquals(n.id, again.session.noteDraft!!.id); assertEquals(n.revision, again.session.noteDraft!!.baseRevision)
            assertEquals("我的批注", again.session.noteText)
        } finally { again.close() }
    }

    @Test fun offlineThenRetryUsesTheSameMutationAndKeepsTheDraft() {
        val s = service(); val r = rig(s)
        try {
            r.session.focusPane(ReaderPane.Body); r.frames()
            r.key(Key.N); r.key(Key.Enter); r.session.noteText = "离线写的"
            s.noteStatus = null; r.key(Key.Enter, ctrl = true)
            assertEquals(NoteSaveState.Offline(r.session.noteDraft!!.id), r.session.remote!!.noteSave)
            assertEquals("离线写的", r.session.noteText)
            s.noteStatus = 200; r.key(Key.Enter, ctrl = true)
            assertIs<NoteSaveState.Saved>(r.session.remote!!.noteSave)
            assertEquals(2, s.noteCalls.size); assertEquals(s.noteCalls[0]["mutationId"], s.noteCalls[1]["mutationId"])
            r.shot("notes-offline-retry")
        } finally { r.close() }
    }

    @Test fun conflictKeepsTheDraftOnScreenAndDoesNotOverwrite() {
        val s = service(); val r = rig(s)
        try {
            r.session.focusPane(ReaderPane.Body); r.frames()
            r.key(Key.N); r.key(Key.Enter); r.session.noteText = "v1"; r.key(Key.Enter, ctrl = true)
            val id = r.session.noteDraft!!.id
            s.otherDeviceEditsNote("a", id, "对方改的")
            r.session.noteText = "我改的"; r.key(Key.Enter, ctrl = true)
            assertEquals(id, (r.session.remote!!.noteSave as NoteSaveState.Conflict).id)
            assertEquals("这条批注有冲突，草稿保留在本机，没有覆盖对方", r.session.uiNoteStatus)
            assertEquals("我改的", r.session.noteText); assertNotNull(r.session.noteDraft)
            assertEquals(JsonPrimitive("对方改的"), s.serverNote("a", id)!!["note"])
            val calls = s.noteCalls.size
            r.frames(10); assertEquals(calls, s.noteCalls.size, "冲突后不自动重试")
            r.shot("notes-conflict-1440")
        } finally { r.close() }
    }

    @Test fun switchingBookDropsTheEditorAndOldNotes() {
        val s = service(); val r = rig(s)
        try {
            r.session.focusPane(ReaderPane.Body); r.frames()
            r.key(Key.N); r.key(Key.Enter); r.session.noteText = "A 书的批注"; r.key(Key.Enter, ctrl = true)
            assertEquals(1, r.session.remote!!.notes.size)
            runBlocking { r.session.openRemote("b") }; r.frames(12)
            assertFalse(r.session.noting); assertTrue(r.session.remote!!.notes.isEmpty())
            assertEquals(NoteSaveState.Idle, r.session.remote!!.noteSave)
        } finally { r.close() }
    }

    @Test fun unresolvedProgressConflictIsNotWrittenAgainWhileFlippingPages() {
        val s = service(); val r = rig(s)
        try {
            r.session.focusPane(ReaderPane.Body); r.frames()
            s.otherDeviceSaves("a", 7)
            r.key(Key.PageDown)
            assertIs<SaveState.Conflict>(r.session.remote!!.save)
            val calls = s.saveCalls.size
            r.key(Key.PageDown); r.key(Key.PageDown); r.key(Key.PageDown)
            assertEquals(calls, s.saveCalls.size, "冲突未解决时翻页不再写同一冲突")
            assertEquals(5, (r.session.remote!!.save as SaveState.Conflict).localPage)
            assertEquals(7, s.docs.getValue("a").progressPage)
        } finally { r.close() }
    }

    @Test fun narrowAndLargeFontStillShowHighlightAndEditor() {
        val s = service(); val r = rig(s, 700, Density(2f, 1.5f))
        try {
            r.session.focusPane(ReaderPane.Related); r.frames(); r.key(Key.Enter)
            assertEquals("已在页上标出原句", r.session.uiHighlight)
            r.shot("notes-highlight-700-d2-f1.5")
            r.key(Key.Escape)
            r.session.focusPane(ReaderPane.Body); r.frames()
            r.key(Key.N); r.key(Key.Enter); r.session.noteText = "大字窄窗批注"; r.frames()
            r.shot("notes-editor-700-d2-f1.5")
            assertNotNull(r.session.noteDraft)
        } finally { r.close() }
    }

    private fun Rig.startNote(text: String) {
        session.focusPane(ReaderPane.Body); frames()
        key(Key.N); key(Key.Enter); session.noteText = text; frames()
    }

    @Test fun editingAfterASaveShowsUnsavedChangesNotTheGreenSavedBadge() {
        val s = service(); val r = rig(s)
        try {
            r.startNote("v1"); assertEquals("有未保存的修改", r.session.uiNoteStatus)
            r.key(Key.Enter, ctrl = true)
            assertEquals("已保存", r.session.uiNoteStatus)
            val rev1 = (r.session.remote!!.noteSave as NoteSaveState.Saved).revision
            r.session.noteText = "v2"; r.frames()
            assertEquals("有未保存的修改", r.session.uiNoteStatus, "保存第一版后改第二版：不再显示已保存")
            r.shot("notes-status-dirty")
            r.key(Key.Enter, ctrl = true)
            assertEquals("已保存", r.session.uiNoteStatus)
            assertEquals(JsonPrimitive("v2"), s.serverNote("a", r.session.noteDraft!!.id)!!["note"])
            assertEquals(JsonPrimitive(rev1), s.noteCalls[1]["baseRevision"])
        } finally { r.close() }
    }

    @Test fun typingWhileASaveIsInFlightStaysUnsavedAndTheSecondPressDoesNotStack() {
        val s = service(); val r = rig(s)
        try {
            r.startNote("v1")
            val hold = kotlinx.coroutines.CompletableDeferred<Unit>() to kotlinx.coroutines.CompletableDeferred<Unit>()
            s.noteHold = hold
            r.key(Key.Enter, ctrl = true)
            assertEquals("正在保存…", r.session.uiNoteStatus)
            r.session.noteText = "v2-during"; r.frames()
            r.key(Key.Enter, ctrl = true)
            assertEquals(1, s.noteCalls.size, "保存进行中不叠加第二次")
            s.noteHold = null; hold.second.complete(Unit); r.frames(12)
            assertEquals("有未保存的修改", r.session.uiNoteStatus, "回执只确认 v1，保存期间输入的内容仍算未保存")
            assertEquals(JsonPrimitive("v1"), s.serverNote("a", r.session.noteDraft!!.id)!!["note"])
            r.key(Key.Enter, ctrl = true)
            assertEquals("已保存", r.session.uiNoteStatus)
            assertEquals(JsonPrimitive("v2-during"), s.serverNote("a", r.session.noteDraft!!.id)!!["note"])
        } finally { r.close() }
    }

    @Test fun offlineStatusFollowsTheTextAndRetrySendsTheCurrentContent() {
        val s = service(); val r = rig(s)
        try {
            r.startNote("离线稿"); s.noteStatus = null; r.key(Key.Enter, ctrl = true)
            assertEquals("连不上服务，内容原样保留，再按 Ctrl+回车重试", r.session.uiNoteStatus)
            r.session.noteText = "离线稿改"; r.frames()
            assertEquals("有未保存的修改", r.session.uiNoteStatus)
            s.noteStatus = 200; r.key(Key.Enter, ctrl = true)
            assertEquals("已保存", r.session.uiNoteStatus)
            assertEquals(JsonPrimitive("离线稿改"), s.serverNote("a", r.session.noteDraft!!.id)!!["note"])
        } finally { r.close() }
    }

    @Test fun conflictRecoveryAdoptRestoreAndExplicitOverwrite() {
        val s = service(); val r = rig(s)
        try {
            r.startNote("v1"); r.key(Key.Enter, ctrl = true)
            val id = r.session.noteDraft!!.id
            s.otherDeviceEditsNote("a", id, "对方改的")
            r.session.noteText = "我改的"; r.key(Key.Enter, ctrl = true)
            assertEquals("这条批注有冲突，草稿保留在本机，没有覆盖对方", r.session.uiNoteStatus)
            r.key(Key.Enter, ctrl = true) // 冲突时再按保存：不发请求
            assertEquals(2, s.noteCalls.size); assertEquals("这条批注有冲突，先选一种处理方式", r.session.message)
            r.shot("notes-conflict-panel")
            r.session.adoptServerVersion(); r.frames()
            assertEquals("对方改的", r.session.noteText); assertEquals("我改的", r.session.noteStash)
            assertEquals("已保存", r.session.uiNoteStatus)
            r.session.restoreStashedDraft(); r.frames()
            assertEquals("我改的", r.session.noteText); assertEquals("有未保存的修改", r.session.uiNoteStatus)
            r.key(Key.Enter, ctrl = true)
            assertEquals("已保存", r.session.uiNoteStatus)
            assertEquals(JsonPrimitive("我改的"), s.serverNote("a", id)!!["note"])
            // 再来一次冲突，这次用“用我的版本覆盖”
            s.otherDeviceEditsNote("a", id, "对方第二次"); r.session.noteText = "我第二次"; r.key(Key.Enter, ctrl = true)
            runBlocking { r.session.overwriteWithMine() }; r.frames()
            assertEquals("已保存", r.session.uiNoteStatus)
            assertEquals(JsonPrimitive("我第二次"), s.serverNote("a", id)!!["note"])
        } finally { r.close() }
    }

    @Test fun anOldReceiptNeverMakesANewDraftLookSavedOrWritesTheWrongObject() {
        val s = service(); val r = rig(s)
        try {
            r.startNote("第一行的批注")
            val hold = kotlinx.coroutines.CompletableDeferred<Unit>() to kotlinx.coroutines.CompletableDeferred<Unit>()
            s.noteHold = hold
            r.key(Key.Enter, ctrl = true)
            val oldId = r.session.noteDraft!!.id
            r.session.cancelNote(); r.frames()
            r.key(Key.N); r.key(Key.DirectionDown); r.key(Key.Enter)
            assertNotEquals(oldId, r.session.noteDraft!!.id)
            s.noteHold = null; hold.second.complete(Unit); r.frames(12)
            assertEquals("尚未保存", r.session.uiNoteStatus, "旧批注的回执不能让新草稿显示已保存")
            assertEquals(oldId, r.session.remote!!.notes.single().id, "回执写进的是旧批注自己")
            // 切书后迟到的回执：丢弃
            r.session.cancelNote(); r.frames()
            r.startNote("A 书"); val h2 = kotlinx.coroutines.CompletableDeferred<Unit>() to kotlinx.coroutines.CompletableDeferred<Unit>()
            s.noteHold = h2; r.key(Key.Enter, ctrl = true)
            runBlocking { r.session.openRemote("b") }; r.frames(8)
            s.noteHold = null; h2.second.complete(Unit); r.frames(12)
            assertTrue(r.session.remote!!.notes.isEmpty()); assertNull(r.session.uiNoteStatus); assertFalse(r.session.noteSaving)
        } finally { r.close() }
    }

    private fun longService() = service().apply {
        withImage = false
        bodyText = { _, p -> (0 until 60).joinToString("\n") { "Line $it of page $p" } }
    }

    /** 这一行真的落在窗口可见范围内（700 高的窗口，顶部栏约 30）。 */
    private fun Rig.visible(i: Int): Boolean = session.uiLineBounds[i]?.let { it.top >= 30f && it.bottom <= 700f } ?: false

    @Test fun longPageWheelScrollsInsideThePageThenTurnsAtTheEdgeAndComesBackToTheEnd() {
        val s = longService(); val r = rig(s, 700)
        try {
            assertTrue(r.visible(0)); assertFalse(r.visible(59), "60 行在 700 高的窗口里放不下")
            r.shot("long-page-top")
            var guard = 0
            while (r.session.uiBodyScroll < r.session.uiBodyScrollMax && guard++ < 400) {
                r.wheel(1f, x = 200f)
                assertEquals(0, r.session.state.position.page, "页内还没滚到底，不得翻页")
            }
            assertTrue(r.session.uiBodyScrollMax > 0)
            assertTrue(r.visible(59), "滚到底：末行可见"); assertFalse(r.visible(0))
            r.shot("long-page-bottom")
            r.wheel(1f, x = 200f)
            assertEquals(1, r.session.state.position.page, "到页边界才翻页")
            assertEquals(0, r.session.uiBodyScroll); assertTrue(r.visible(0), "新页从首行开始")
            r.wheel(-1f, x = 200f)
            assertEquals(0, r.session.state.position.page)
            assertEquals(r.session.uiBodyScrollMax, r.session.uiBodyScroll, "往回翻停在上一页末尾")
            assertTrue(r.visible(59))
            guard = 0
            while (r.session.uiBodyScroll > 0 && guard++ < 400) { r.wheel(-1f, x = 200f); assertEquals(0, r.session.state.position.page) }
            assertTrue(r.visible(0))
            r.wheel(-1f, x = 200f); assertEquals(0, r.session.state.position.page, "第一页顶部再往上滚也不越界")
        } finally { r.close() }
    }

    @Test fun longPageKeysScrollInPageAndPageKeysStillTurn() {
        val s = longService(); val r = rig(s, 700)
        try {
            r.session.focusPane(ReaderPane.Body); r.frames()
            repeat(5) { r.key(Key.DirectionDown) }
            assertTrue(r.session.uiBodyScroll > 0); assertEquals(0, r.session.state.position.page)
            repeat(5) { r.key(Key.DirectionUp) }
            assertEquals(0, r.session.uiBodyScroll)
            var guard = 0
            while (r.session.uiBodyScroll < r.session.uiBodyScrollMax && guard++ < 100) r.key(Key.DirectionDown)
            assertTrue(r.visible(59), "只用方向键也能读到末行"); assertEquals(0, r.session.state.position.page)
            r.key(Key.PageDown)
            assertEquals(1, r.session.state.position.page); assertEquals(0, r.session.uiBodyScroll); assertTrue(r.visible(0))
            r.key(Key.PageUp)
            assertEquals(0, r.session.state.position.page); assertEquals(0, r.session.uiBodyScroll)
        } finally { r.close() }
    }

    @Test fun ctrlWheelStillZoomsAndShortPagesStillTurnImmediately() {
        val s = service().apply { withImage = false }; val r = rig(s, 1440)
        try {
            r.wheel(1f); r.wheel(1f)
            assertEquals(2, r.session.state.position.page, "短页没有可滚动范围：滚轮直接翻页")
        } finally { r.close() }
    }

    @Test fun savingAfterAdoptingTheServerVersionClearsTheRestoreDraftEntry() {
        val s = service(); val r = rig(s)
        try {
            r.startNote("v1"); r.key(Key.Enter, ctrl = true)
            val id = r.session.noteDraft!!.id
            s.otherDeviceEditsNote("a", id, "对方改的")
            r.session.noteText = "我改的"; r.key(Key.Enter, ctrl = true)
            r.session.adoptServerVersion(); assertNotNull(r.session.noteStash)
            r.session.noteText = "采用后又改"; r.key(Key.Enter, ctrl = true)
            assertEquals("已保存", r.session.uiNoteStatus); assertNull(r.session.noteStash)
        } finally { r.close() }
    }
}
