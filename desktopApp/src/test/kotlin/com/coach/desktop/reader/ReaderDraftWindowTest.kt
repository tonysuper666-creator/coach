package com.coach.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.coach.client.CourseHttp
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

/**
 * 真实 ReaderWindow：远端保存被 501 拒绝 → 提示“草稿已留在本机” → Esc 关闭 → 全新会话/窗口/存储实例（等同关书重开）
 * → 界面出现“本页有未保存的草稿” → 点“继续编辑”后输入框里是原来的文字。离屏组件，自造数据，本机回环 HTTP fixture。
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ReaderDraftWindowTest {
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
        fun text(tag: String) = node(tag)?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun typeChars(text: String) {
            for (ch in text) {
                val act = checkNotNull(checkNotNull(node("note-input")) { "no input" }.config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action)
                assertTrue(act(AnnotatedString(ch.toString()))); frames(2)
            }
        }
        fun click(tag: String) {
            val act = checkNotNull(checkNotNull(node(tag)) { "no node $tag" }.config.getOrNull(SemanticsActions.OnClick)?.action)
            act(); frames(8)
        }
        fun close() = scene.close()
    }

    @Test fun refusedDraftSurvivesClosingTheEditorAndReopeningTheWindow() {
        val fake = FakeReaderService(Triple("a", "va1", 10)).apply { bodyText = { _, _ -> "Alpha beta gamma\nDelta epsilon zeta" } }
        val lb = ReaderHttpSourceTest.Loopback(fake)
        lb.override = { r ->
            if (r.method == "POST" && r.rawPath.endsWith("/notes")) Triple(501, """{"error":"本地阅读器暂不支持保存批注"}""".toByteArray(), "application/json") else null
        }
        val dir = Files.createTempDirectory("drafts-"); val scope = "local-library:window-test"
        fun open(): Rig {
            val remote = ReaderRemote(ReaderHttpSource(CourseHttp(lb.base, "dev1", "tok", "c1").reader))
            val session = ReaderSession(ReaderFixture("远端", emptyList(), emptyList()), 1100, remote, FileNoteDraftStore(dir, scope))
            val rig = Rig(session)
            runBlocking { remote.loadCatalog(); session.openRemote("a") }
            rig.until("page ready") { remote.page is PageState.Ready }
            session.focusPane(ReaderPane.Body); rig.frames()
            return rig
        }
        try {
            val first = open()
            try {
                first.key(Key.N); first.key(Key.Enter)
                first.typeChars("我写的批注")
                first.key(Key.Enter, ctrl = true)
                first.until("refusal shown") { first.node("note-failure-reason") != null }
                val shown = first.text("note-failure-reason")!!
                assertTrue(shown.contains("留在本机"), "界面提示必须与实际能力一致（草稿真的留在本机），实际：$shown")
                assertFalse(shown.contains("不会保留"), "有存储时不能再说不会保留，实际：$shown")
                first.key(Key.Escape)                       // 普通关闭编辑区
                first.until("editor closed by Esc") { first.node("note-input") == null }
                first.until("local draft listed") { first.node("local-draft-0") != null }   // 同一窗口里也能找回
            } finally { first.close() }
            // 关书/重开应用：全新会话、窗口、存储实例
            val second = open()
            try {
                second.until("local draft listed after reopen") { second.node("local-drafts") != null && second.node("local-draft-0") != null }
                assertNull(second.node("note-input"))
                val callsBefore = fake.noteCalls.size
                second.click("local-draft-0")               // 用户点“继续编辑”
                second.until("editor back") { second.node("note-input") != null }
                assertEquals("我写的批注", second.session.noteText)
                assertEquals("我写的批注", second.node("note-input")!!.config.getOrNull(SemanticsProperties.EditableText)!!.text, "输入框里就是原来的文字")
                assertEquals(callsBefore, fake.noteCalls.size, "找回不会自动补发远端写入")
            } finally { second.close() }
        } finally { lb.stop() }
    }
}
