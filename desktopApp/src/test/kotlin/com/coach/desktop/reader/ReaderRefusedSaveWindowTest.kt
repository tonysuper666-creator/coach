package com.coach.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.coach.client.CourseHttp
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * 保存被服务端明确拒绝（501）时，**真实 ReaderWindow 组件**里的批注编辑区必须显示：服务端给的原因 + “关闭编辑区后这条草稿不会保留”。
 * 走生产 ReaderSession/ReaderRemote/真实 CourseHttp + 本机回环 HTTP fixture；键入经输入框自己的 InsertTextAtCursor 语义动作，
 * 保存经真实 Ctrl+回车键事件触发；断言读的是组件语义树里 `note-failure-reason` 节点的实际文本（离屏 ImageComposeScene，非真实窗口/键鼠/GPU）。
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ReaderRefusedSaveWindowTest {
    private val noteRefusal = "本地阅读器暂不支持保存批注，草稿仍保留"

    private class Rig(val session: ReaderSession) {
        var t = 0L
        val scene = ImageComposeScene(1100, 700, Density(1f)) { ReaderWindow(session) }
        init { frames(8) }
        fun frames(n: Int = 4) { repeat(n) { t += 16_000_000L; scene.render(t).close(); Thread.sleep(3) } }
        fun until(what: String, ms: Long = 20_000, c: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (!c()) { assertTrue(System.currentTimeMillis() <= end, "timeout: $what"); frames(2); Thread.sleep(20) }
        }
        fun key(k: Key, ctrl: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown, isCtrlPressed = ctrl)); scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp, isCtrlPressed = ctrl)); frames(8)
        }
        fun node(tag: String): SemanticsNode? {
            fun walk(n: SemanticsNode): SemanticsNode? =
                if (n.config.getOrNull(SemanticsProperties.TestTag) == tag) n else n.children.firstNotNullOfOrNull { walk(it) }
            return scene.semanticsOwners.firstNotNullOfOrNull { walk(it.unmergedRootSemanticsNode) }
        }
        fun typeChars(text: String) {
            for (ch in text) {
                val act = checkNotNull(checkNotNull(node("note-input")) { "no input" }.config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action)
                assertTrue(act(AnnotatedString(ch.toString()))); frames(2)
            }
        }
        fun close() = scene.close()
    }

    @Test fun refusedNoteSaveShowsTheServersReasonAndTheDraftBoundaryInTheRealWindow() {
        val fake = FakeReaderService(Triple("a", "va1", 10)).apply { bodyText = { _, _ -> "Alpha beta gamma\nDelta epsilon zeta" } }
        val lb = ReaderHttpSourceTest.Loopback(fake)
        lb.override = { r ->
            if (r.method == "POST" && r.rawPath.endsWith("/notes")) Triple(501, """{"error":"$noteRefusal"}""".toByteArray(), "application/json") else null
        }
        val remote = ReaderRemote(ReaderHttpSource(CourseHttp(lb.base, "dev1", "tok", "c1").reader))
        val session = ReaderSession(ReaderFixture("远端", emptyList(), emptyList()), 1100, remote)
        val rig = Rig(session)
        try {
            runBlocking { remote.loadCatalog(); session.openRemote("a") }
            rig.until("page ready") { remote.page is PageState.Ready }
            session.focusPane(ReaderPane.Body); rig.frames()
            rig.key(Key.N); rig.key(Key.Enter) // 选第一行 → 打开编辑区
            assertNotNull(session.noteDraft); assertNotNull(rig.node("note-input"))
            rig.typeChars("我的批注")
            assertNull(rig.node("note-failure-reason"), "保存之前不应有失败原因")
            rig.key(Key.Enter, ctrl = true)                      // 真实 Ctrl+回车 → 真实 HTTP POST /notes → 501
            rig.until("failure reason shown in the component") { rig.node("note-failure-reason") != null }
            val shown = rig.node("note-failure-reason")!!.config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text }
            assertTrue(shown.contains(noteRefusal), "界面要显示服务端原因，实际显示：$shown")
            assertTrue(shown.contains("关闭编辑区后这条草稿不会保留"), "界面要说清草稿保留边界，实际显示：$shown")
            // 同一次失败回执：状态是 Failed(501)，草稿原样还在，服务端没有这条批注
            assertIs<ReaderFailure.Server>((remote.noteSave as NoteSaveState.Failed).failure)
            assertEquals("我的批注", session.noteText); assertTrue(remote.notes.isEmpty())
            assertTrue(lb.seen.any { it.method == "POST" && it.rawPath.endsWith("/notes") }, "请求确实发到了回环服务")
        } finally { rig.close(); lb.stop() }
    }
}
