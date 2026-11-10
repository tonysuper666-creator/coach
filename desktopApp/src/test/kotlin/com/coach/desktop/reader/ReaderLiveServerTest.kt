package com.coach.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import com.coach.client.CourseHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assume.assumeTrue
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * 真实服务消费：启动合并后 main 构建出的真实 Ktor 进程（随机回环端口、临时私有合成 PDF 资料库、隔离 user.home），
 * 用桌面 reader 适配器（ReaderHttpSource → 真实 CourseHttp）与离屏真实组件走 catalog/page/image/boxes/study/state/progress/notes，
 * 再撤销令牌，确认服务端拒绝（401）且本地未确认的页位置/批注草稿还在。
 *
 * 默认跳过。运行前先构建服务端并设环境变量：
 *   python desktopApp/src/test/kotlin/com/coach/desktop/reader/live/reader_live_server.py --build
 *   COACH_READER_LIVE=1 gradlew.bat :desktopApp:test --tests "com.coach.desktop.reader.ReaderLiveServerTest"
 * 资料全是脚本里造的合成 PDF；不连 18791/8790/8791/3790，不读任何私人数据。
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ReaderLiveServerTest {
    private class Live(val process: Process, val out: BufferedReader, val port: Int, val course: String, val deviceId: String, val token: String) {
        fun http(token: String = this.token) = CourseHttp("http://127.0.0.1:$port", deviceId, token, course)
        fun source(token: String = this.token) = ReaderHttpSource(http(token).reader)
        fun revoke() {
            process.outputStream.write("revoke\n".toByteArray()); process.outputStream.flush()
            val line = out.readLine(); assertEquals("REVOKED", line)
        }
        fun stop() {
            runCatching { process.outputStream.write("stop\n".toByteArray()); process.outputStream.flush(); process.outputStream.close() }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                ProcessBuilder("taskkill", "/PID", process.pid().toString(), "/T", "/F").start().waitFor()
                process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS)
            }
        }
    }

    private fun repoRoot(): File {
        var d = File("").absoluteFile
        while (!File(d, "tools/ios-contract/run.py").exists()) d = d.parentFile ?: error("repo root not found")
        return d
    }

    private fun withLive(block: suspend (Live) -> Unit) {
        assumeTrue("设 COACH_READER_LIVE=1 才连真实服务进程", System.getenv("COACH_READER_LIVE") == "1")
        val root = repoRoot()
        val script = File(root, "desktopApp/src/test/kotlin/com/coach/desktop/reader/live/reader_live_server.py")
        val pb = ProcessBuilder("python", script.path).directory(root).redirectErrorStream(false)
        pb.redirectError(File(root, "desktopApp/build/reader-live-launcher.err"))
        val env = pb.environment(); env.keys.removeAll { it.startsWith("COACH_") || it.startsWith("BRIDGE_") }
        val process = pb.start()
        val out = BufferedReader(InputStreamReader(process.inputStream))
        var live: Live? = null
        try {
            val line = out.readLine() ?: error("launcher exited: " + File(root, "desktopApp/build/reader-live-launcher.err").takeIf { it.exists() }?.readText())
            check(line.startsWith("READY ")) { "launcher said: $line" }
            val j = Json.parseToJsonElement(line.removePrefix("READY ")).jsonObject
            live = Live(process, out, j["port"]!!.jsonPrimitive.content.toInt(), j["course"]!!.jsonPrimitive.content,
                j["deviceId"]!!.jsonPrimitive.content, j["token"]!!.jsonPrimitive.content)
            assertTrue(live.port !in setOf(8790, 8791, 18791, 3790))
            runBlocking { block(live) }
        } finally {
            live?.stop() ?: run { process.destroyForcibly() }
        }
    }

    private class Rig(val session: ReaderSession, widthDp: Int, heightDp: Int = 700) {
        var t = 0L
        val scene = ImageComposeScene(widthDp, heightDp, Density(1f)) { ReaderWindow(session) }
        fun frames(n: Int = 3) { repeat(n) { t += 16_000_000L; scene.render(t).close(); Thread.sleep(5) } }
        fun until(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + timeoutMs
            while (!cond()) { check(System.currentTimeMillis() < end) { "timeout: $what" }; frames(2); Thread.sleep(20) }
        }
        fun node(tag: String): androidx.compose.ui.semantics.SemanticsNode? {
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode): androidx.compose.ui.semantics.SemanticsNode? =
                if (n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == tag) n else n.children.firstNotNullOfOrNull { walk(it) }
            return scene.semanticsOwners.firstNotNullOfOrNull { walk(it.unmergedRootSemanticsNode) }
        }
        /** 逐字输入：每个字符走输入框自己的 InsertTextAtCursor 语义动作（无障碍/UI 测试输入文字的同一条路径），不是给状态赋值。 */
        fun typeChars(text: String) {
            for (ch in text) {
                val n = checkNotNull(node("note-input")) { "输入框不在语义树里" }
                val act = checkNotNull(n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.InsertTextAtCursor)?.action) { "输入框不支持插入文字" }
                assertTrue(act(androidx.compose.ui.text.AnnotatedString(ch.toString())), "输入 '$ch' 被拒绝")
                frames(2)
            }
        }
        fun key(k: Key, ctrl: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown, isCtrlPressed = ctrl)); scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp, isCtrlPressed = ctrl)); frames(6)
        }
        fun shot(name: String) {
            val dir = File("build/reader-shots").also { it.mkdirs() }
            t += 16_000_000L
            scene.render(t).use { img -> img.encodeToData()!!.use { File(dir, "$name.png").writeBytes(it.bytes) } }
        }
        fun close() = scene.close()
    }

    private fun session(live: Live, token: String = live.token) =
        ReaderSession(ReaderFixture("真实服务", emptyList(), emptyList()), 1100, ReaderRemote(live.source(token)))

    @Test fun realServerCatalogPageImageBoxesStudyStateAndProgressRoundTrip() = withLive { live ->
        val r = ReaderRemote(live.source())
        r.loadCatalog()
        assertEquals(listOf("orchard-read", "orchard-text"), r.documents.map { it.id }.sorted(), "只有本课程的两份合成资料，别的课程的不出现")
        assertTrue(r.documents.all { it.pages == 2 })
        assertEquals(1, r.open("orchard-text"), "没有进度：从第 1 页开始")
        r.show(1)
        val view = (r.page as PageState.Ready).view
        assertTrue(view.text.contains("Synthetic pears PDF 1"), "页文字来自真实 Poppler 提取")
        assertEquals(64, view.textSha256.length)
        assertEquals(0x89.toByte(), view.image!![0]); assertEquals('P'.code.toByte(), view.image!![1])
        val boxes = (r.boxes as BoxesState.Ready).boxes
        assertTrue(boxes.words.any { it.text == "Synthetic" })
        assertIs<Located.Found>(QuoteLocator.locate(boxes, "Synthetic pears"))
        assertIs<StudyState.Ready>(r.study)
        r.saveProgress(2); assertEquals(SaveState.Saved(2), r.save)
        val again = ReaderRemote(live.source()); again.loadCatalog()
        assertEquals(2, again.open("orchard-text"), "重新加载（新连接）恢复到上次保存的页")
        val state = live.source().state("orchard-text").body!!
        assertEquals(2, state["progress"]!!.jsonObject["pdfPage"]!!.jsonPrimitive.content.toInt(), "服务端存储回读")
        // 未知页：客户端不发请求；服务端对越界页也明确 404
        r.show(3); assertEquals(PageState.Failed(ReaderFailure.MissingPage(3)), r.page)
        assertEquals(404, live.source().page("orchard-text", 3).status)
    }

    @Test fun realServerRejectsBadTokenStaleVersionAndStaleRevision() = withLive { live ->
        val bad = ReaderRemote(live.source("not-the-token")); bad.loadCatalog()
        assertEquals(ReaderFailure.Unauthorized, bad.failure)
        val src = live.source(); val stale = "0".repeat(64)
        assertEquals(409, src.boxes("orchard-text", 1, stale).status)
        assertEquals(409, src.saveProgress("orchard-text", stale, 1, 0L, "m-stale-version").status)
        // 两端写同一进度：第二个用旧修订号 → 409，服务端保持第一个人的位置
        val a = ReaderRemote(live.source()); a.loadCatalog(); a.open("orchard-text")
        val b = ReaderRemote(live.source()); b.loadCatalog(); b.open("orchard-text")
        a.saveProgress(1); assertEquals(SaveState.Saved(1), a.save)
        b.saveProgress(2)
        val c = b.save as SaveState.Conflict
        assertEquals(2, c.localPage); assertEquals(1, c.remote?.pdfPage); assertFalse(c.versionChanged)
        assertEquals(1, live.source().state("orchard-text").body!!["progress"]!!.jsonObject["pdfPage"]!!.jsonPrimitive.content.toInt())
        b.keepLocalAfterConflict(); assertEquals(SaveState.Saved(2), b.save)
    }

    @Test fun realServerNoteSaveRestoreAndStaleDetection() = withLive { live ->
        val r = ReaderRemote(live.source()); r.loadCatalog(); r.open("orchard-read"); r.show(1)
        val v = (r.page as PageState.Ready).view
        val d = NoteDraft("live-note-1", "orchard-read", r.current!!.version, 1, v.textSha256, 0, 9, v.text.substring(0, 9), "合成批注", 0L)
        r.saveNote(d); assertIs<NoteSaveState.Saved>(r.noteSave)
        val again = ReaderRemote(live.source()); again.loadCatalog(); again.open("orchard-read"); again.show(1)
        val n = again.notes.single()
        assertEquals("合成批注", n.note); assertTrue(again.noteValid(n, (again.page as PageState.Ready).view))
        val rev = (r.noteSave as NoteSaveState.Saved).revision
        // 旧修订号再写同一条批注 → 409，草稿保留，服务端内容不变
        r.saveNote(d.copy(note = "旧修订", baseRevision = 0L)); assertIs<NoteSaveState.Conflict>(r.noteSave)
        val fresh = ReaderRemote(live.source()); fresh.loadCatalog(); fresh.open("orchard-read")
        assertEquals("合成批注", fresh.notes.single().note); assertEquals(rev, fresh.notes.single().revision)
    }

    @Test fun realWindowFlipsPagesSavesRestoresAndKeepsLocalStateAfterTokenRevocation() = withLive { live ->
        val s = session(live)
        val rig = Rig(s, 1100)
        try {
            rig.frames(4)
            s.remote!!.loadCatalog(); s.openRemote("orchard-text"); rig.until("first page") { s.remote.page is PageState.Ready }
            s.focusPane(ReaderPane.Body); rig.frames()
            rig.key(Key.PageDown)
            rig.until("page 2 + saved") { (s.remote.page as? PageState.Ready)?.view?.pdfPage == 2 && s.remote.save == SaveState.Saved(2) }
            assertTrue((s.remote.page as PageState.Ready).view.text.contains("Synthetic pears PDF 2"))
            assertEquals(2, live.source().state("orchard-text").body!!["progress"]!!.jsonObject["pdfPage"]!!.jsonPrimitive.content.toInt(), "翻页后服务端已保存")
            rig.shot("live-real-server-page2")
            // 关掉重开：新会话新连接，回到第 2 页
            val reopened = session(live)
            runBlocking { reopened.remote!!.loadCatalog(); reopened.openRemote("orchard-text") }
            assertEquals(1, reopened.state.position.page, "pdfPage 2 → 页序 1")
            // 写一条本地未确认的批注草稿，然后撤销令牌
            rig.key(Key.PageUp); rig.until("page 1") { (s.remote.page as? PageState.Ready)?.view?.pdfPage == 1 }
            // 真实输入：选行 → 输入框逐字键入（InsertTextAtCursor 语义动作）→ 退格（键盘事件）→ Ctrl+回车保存 → 服务端回读
            rig.key(Key.N); rig.key(Key.Enter); assertEquals("", s.noteText)
            rig.typeChars("真实键入abc"); assertEquals("真实键入abc", s.noteText)
            rig.key(Key.Backspace); assertEquals("真实键入ab", s.noteText)
            rig.key(Key.Enter, ctrl = true)
            rig.until("note saved") { s.remote.noteSave is NoteSaveState.Saved }
            assertEquals("已保存", s.uiNoteStatus)
            suspend fun serverNotes() = live.source().state("orchard-text").body!!["notes"] as JsonArray
            assertEquals("真实键入ab", serverNotes().single().jsonObject["note"]!!.jsonPrimitive.content, "服务端回读到键入的内容")
            // 关闭编辑区（Esc）→ 翻页再翻回 → 重开新会话：批注都还在，键盘仍可用
            rig.key(Key.Escape); assertFalse(s.noting)
            rig.key(Key.PageDown); rig.until("p2") { (s.remote.page as? PageState.Ready)?.view?.pdfPage == 2 }
            rig.key(Key.PageUp); rig.until("p1 again") { (s.remote.page as? PageState.Ready)?.view?.pdfPage == 1 }
            val reopened2 = session(live)
            runBlocking { reopened2.remote!!.loadCatalog(); reopened2.openRemote("orchard-text") }
            assertEquals("真实键入ab", reopened2.remote!!.notes.single().note)
            // 再编辑同一行（沿用原批注 id/修订）：键入 X，此时撤销令牌，保存必须被拒，本地键入的草稿与页位置保留
            rig.key(Key.N); rig.key(Key.Enter)
            assertEquals("真实键入ab", s.noteText); rig.typeChars("X"); assertEquals("真实键入abX", s.noteText)
            assertEquals("有未保存的修改", s.uiNoteStatus)
            live.revoke()
            rig.key(Key.Enter, ctrl = true)
            rig.until("note rejected") { s.remote.noteSave is NoteSaveState.Failed }
            assertEquals(NoteSaveState.Failed(ReaderFailure.Unauthorized), s.remote.noteSave)
            assertEquals("真实键入abX", s.noteText); assertNotNull(s.noteDraft, "草稿还在，没有丢")
            assertEquals("没有保存", s.uiNoteStatus)
            // 撤销后翻页：页内容拿不到、位置保存被拒，但本地的页位置照常跟着用户走
            rig.key(Key.Escape); assertFalse(s.noting); rig.frames()
            s.focusPane(ReaderPane.Body); rig.frames(); rig.key(Key.PageDown)
            rig.until("page failed") { s.remote.page is PageState.Failed }
            assertEquals(PageState.Failed(ReaderFailure.Unauthorized), s.remote.page)
            assertEquals(1, s.state.position.page, "本地位置保持")
            assertEquals(401, live.source().catalog().status); assertEquals(401, live.source().state("orchard-text").status)
            assertEquals(401, live.source().saveProgress("orchard-text", s.remote.current!!.version, 2, 0L, "m-after-revoke").status)
            rig.shot("live-real-server-revoked")
        } finally { rig.close() }
    }
}
