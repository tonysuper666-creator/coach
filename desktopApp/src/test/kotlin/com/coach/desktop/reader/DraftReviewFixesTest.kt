package com.coach.desktop.reader

import com.coach.client.CourseHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * 关键复核（Expert）指出的恢复/清理反例：真实 CourseHttp + 本机回环 HTTP fixture + 生产 ReaderSession/ReaderRemote；自造数据。
 * 只用 63a2e2d0 时就存在的公开 API，便于对旧实现做旧红。
 */
class DraftReviewFixesTest {
    private val scope = "local-library:review"

    private class Env(val lb: ReaderHttpSourceTest.Loopback, val dir: Path, val scope: String) {
        var refuse = false
        val fake get() = lb.fake
        fun session(): Pair<ReaderSession, ReaderRemote> {
            val remote = ReaderRemote(ReaderHttpSource(CourseHttp(lb.base, "dev1", "tok", "c1").reader))
            val s = ReaderSession(ReaderFixture("远端", emptyList(), emptyList()), 1100, remote, FileNoteDraftStore(dir, scope))
            runBlocking { remote.loadCatalog(); s.openRemote("a"); remote.show(1) }
            return s to remote
        }
        fun stored() = FileNoteDraftStore(dir, scope).listFor("a", "va1", 1)
    }

    private fun withEnv(block: (Env) -> Unit) {
        val fake = FakeReaderService(Triple("a", "va1", 10)).apply { bodyText = { _, _ -> "Alpha beta gamma\nDelta epsilon zeta" } }
        val lb = ReaderHttpSourceTest.Loopback(fake)
        val env = Env(lb, Files.createTempDirectory("drafts-"), scope)
        lb.override = { r ->
            if (env.refuse && r.method == "POST" && r.rawPath.endsWith("/notes")) Triple(501, """{"error":"本地阅读器暂不支持保存批注"}""".toByteArray(), "application/json") else null
        }
        try { block(env) } finally { lb.stop() }
    }

    private fun openLine(s: ReaderSession, line: Int) { s.beginNote(); repeat(line) { s.onKey(KeyInput(ReaderKey.Down)) }; s.onKey(KeyInput(ReaderKey.Enter)) }

    @Test fun restoredDraftKeepsItsOriginalBaseRevisionSoARemoteEditIsAConflictOnBothRestorePaths() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0); s.editNote("v1"); runBlocking { s.commitNote() }                // 远端 ack：n1 / r1
        val id = s.noteDraft!!.id
        env.refuse = true
        s.editNote("v1 本地改"); runBlocking { s.commitNote() }                          // 本地保存失败，草稿基于 r1
        s.cancelNote()
        env.refuse = false
        env.fake.otherDeviceEditsNote("a", id, "对方改的")                                // 另一台设备把 n1 改到 r2
        // 路径 A：本页“继续编辑”
        val (a, ra) = env.session()
        assertTrue(a.resumeLocalDraft(a.localDrafts().single()))
        runBlocking { a.commitNote() }
        assertEquals(NoteStatus.Conflict, a.noteStatus(), "不能覆盖未见过的远端修改"); assertIs<NoteSaveState.Conflict>(ra.noteSave)
        // 路径 B：同一行再次选中
        val (b, rb) = env.session()
        openLine(b, 0); assertEquals("v1 本地改", b.noteText)
        runBlocking { b.commitNote() }
        assertEquals(NoteStatus.Conflict, b.noteStatus()); assertIs<NoteSaveState.Conflict>(rb.noteSave)
        assertEquals("对方改的", (env.fake.serverNote("a", id)!!["note"] as kotlinx.serialization.json.JsonPrimitive).content, "服务端内容没被覆盖")
    }

    @Test fun restoredDraftKeepsItsOwnNoteIdAndNeverTakesOverTheNeighbouringRemoteNote() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0); s.editNote("原"); runBlocking { s.commitNote() }; val n1 = s.noteDraft!!.id; s.cancelNote()
        env.refuse = true
        val (s2, _) = env.session()
        openLine(s2, 0); s2.saveAsNewNote(); val n2 = s2.noteDraft!!.id; assertNotEquals(n1, n2)
        runBlocking { s2.commitNote() }                                                      // 另存为新批注，501 失败
        s2.cancelNote()
        assertEquals(n2, env.stored().single().noteId)
        val (a, _) = env.session()
        assertTrue(a.resumeLocalDraft(a.localDrafts().single()))
        assertEquals(n2, a.noteDraft!!.id, "路径 A：保留原 id，不接管旧批注 n1"); assertEquals(0L, a.noteDraft!!.baseRevision)
        val (b, _) = env.session()
        openLine(b, 0)
        assertEquals(n2, b.noteDraft!!.id, "路径 B：同上"); assertEquals(0L, b.noteDraft!!.baseRevision)
    }

    @Test fun overwriteAckKeepsTextTypedWhileTheRequestWasInFlight() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0); s.editNote("v1"); runBlocking { s.commitNote() }; val id = s.noteDraft!!.id
        env.fake.otherDeviceEditsNote("a", id, "对方改的")
        s.editNote("我的A"); runBlocking { s.commitNote() }
        assertEquals(NoteStatus.Conflict, s.noteStatus())
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        env.fake.noteHold = entered to gate
        runBlocking {
            val job = launch(Dispatchers.IO) { s.overwriteWithMine() }
            entered.await()
            s.editNote("B 是等待期间新输入的")        // 覆盖请求 A 还没回执
            env.fake.noteHold = null; gate.complete(Unit); job.join()
        }
        assertEquals("我的A", (env.fake.serverNote("a", id)!!["note"] as kotlinx.serialization.json.JsonPrimitive).content, "A 已被远端接受")
        assertEquals(listOf("B 是等待期间新输入的"), env.stored().map { it.note }, "A 的 ack 不能把新输入 B 的草稿删掉")
        assertEquals(NoteStatus.Dirty, s.noteStatus())
    }

    @Test fun clearingAnExistingNoteIsARealUncommittedEditAndSurvivesCloseAndReopen() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0); s.editNote("原文"); runBlocking { s.commitNote() }; s.cancelNote()
        val (s2, _) = env.session()
        openLine(s2, 0); assertEquals("原文", s2.noteText)
        s2.editNote(""); assertEquals(NoteStatus.Dirty, s2.noteStatus())
        s2.cancelNote()
        val d = env.stored().single(); assertEquals("", d.note)
        val (s3, _) = env.session()
        assertTrue(s3.resumeLocalDraft(s3.localDrafts().single()))
        assertEquals("", s3.noteText, "恢复出来的必须是清空后的修改，不是旧文字"); assertEquals(NoteStatus.Dirty, s3.noteStatus())
        // 新的空白草稿不是草稿
        val (s4, _) = env.session(); openLine(s4, 1); s4.cancelNote()
        assertEquals(1, env.stored().size)
    }

    private fun stripBaseRevision(dir: Path) = Files.list(dir).use { st -> st.toList() }.filter { it.toString().endsWith(".json") }
        .forEach { Files.writeString(it, Files.readString(it).replace(Regex(",\"baseRevision\":-?[0-9]+"), "")) }

    @Test fun aRejectedOldSchemaDraftIsNotDeletedByOpeningAndClosingTheBaselineEditor() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0); s.editNote("v1"); runBlocking { s.commitNote() }
        env.refuse = true; s.editNote("v1 本地改"); runBlocking { s.commitNote() }; s.cancelNote()
        stripBaseRevision(env.dir)                                    // 旧 schema：没有 baseRevision
        assertEquals(1, env.stored().size)
        val (s2, _) = env.session()
        assertFalse(s2.resumeLocalDraft(s2.localDrafts().single()))
        openLine(s2, 0)                                               // 同一行 N/Enter：被拒绝的稿不能被基线编辑器吃掉
        s2.cancelNote()                                               // Esc
        assertEquals(listOf("v1 本地改"), env.stored().map { it.note }, "没有 ack、没有显式放弃，本地稿必须还在")
        val (s3, _) = env.session(); openLine(s3, 0); s3.cancelNote()
        assertEquals(1, env.stored().size)
    }

    @Test fun adoptingTheServerVersionAfterClearingKeepsTheClearedEditWithItsOriginalIdentityAndRevision() = withEnv { env ->
        val (s, r) = env.session()
        openLine(s, 0); s.editNote("原文"); runBlocking { s.commitNote() }
        val id = s.noteDraft!!.id; val r1 = s.noteDraft!!.baseRevision
        env.fake.otherDeviceEditsNote("a", id, "对方改的")                // 远端到 r2，这个会话还不知道
        s.editNote(""); runBlocking { s.commitNote() }                // 把既有批注清空 → 409
        assertEquals(NoteStatus.Conflict, s.noteStatus())
        s.adoptServerVersion()                                        // 采用服务端版本
        s.cancelNote()                                                // Esc
        val d = env.stored().single(); assertEquals("", d.note); assertEquals(id, d.noteId); assertEquals(r1, d.baseRevision)
        val (s2, _) = env.session()
        assertTrue(s2.resumeLocalDraft(s2.localDrafts().single()))
        assertEquals("", s2.noteText); assertEquals(id, s2.noteDraft!!.id); assertEquals(r1, s2.noteDraft!!.baseRevision)
        assertEquals(NoteStatus.Dirty, s2.noteStatus())
    }

    @Test fun aLateAckFromAClosedEditorCannotTakeOverTheEditorThatReplacedIt() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0); s.editNote("A")
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        env.fake.noteHold = entered to gate
        runBlocking {
            val job = launch(Dispatchers.IO) { s.commitNote() }          // A 的请求在途
            entered.await()
            s.cancelNote(); openLine(s, 0); s.editNote("B")              // 关闭后在同一锚点重新打开并编辑 B
            val bBase = s.noteDraft!!.baseRevision
            env.fake.noteHold = null; gate.complete(Unit); job.join()    // A 迟到的 ack
            assertEquals("B", s.noteText); assertEquals(bBase, s.noteDraft!!.baseRevision, "旧 ack 不能改写当前编辑器的 baseRevision")
            assertEquals(listOf("B"), env.stored().map { it.note }, "B 的草稿还在")
            runBlocking { s.commitNote() }                                   // B 用自己看到的修订号保存：服务端已有 A，必须冲突
            assertEquals(NoteStatus.Conflict, s.noteStatus())
        }
    }
}
