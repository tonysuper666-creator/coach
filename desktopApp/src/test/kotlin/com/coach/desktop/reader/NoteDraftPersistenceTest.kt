package com.coach.desktop.reader

import com.coach.client.CourseHttp
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * 本机批注草稿持久化：存储合同 + 生产 ReaderSession/ReaderRemote 路径（真实 CourseHttp + 本机回环 HTTP fixture，自造数据）。
 * 草稿正文只出现在断言里，不进日志/证据。
 */
class NoteDraftPersistenceTest {
    private val scope = "local-library:test-A"

    private fun id(scope: String = this.scope, doc: String = "a", version: String = "va1", page: Int = 1, start: Int = 0, end: Int = 16, sha: String = "x") =
        DraftIdentity(scope, doc, version, page, start, end, sha)

    // ---------------- 存储合同 ----------------
    @Test fun storeRoundTripsAcrossNewInstancesAndIsolatesEveryIdentityField() {
        val dir = Files.createTempDirectory("drafts-")
        val a = FileNoteDraftStore(dir, scope)
        val d = StoredDraft(id(), "n1", "Alpha beta gamma", "我的草稿")
        a.put(d)
        val b = FileNoteDraftStore(dir, scope) // 新实例
        assertEquals(d, b.get(id())); assertEquals(listOf(d), b.listFor("a", "va1", 1))
        // 任何一项身份不同都读不到
        for (other in listOf(id(doc = "b"), id(version = "va2"), id(page = 2), id(start = 1), id(end = 17), id(sha = "y"))) assertNull(b.get(other), "$other")
        assertNull(FileNoteDraftStore(dir, "local-library:test-B").get(id("local-library:test-B")), "别的拥有者读不到")
        assertTrue(FileNoteDraftStore(dir, "local-library:test-B").listFor("a", "va1", 1).isEmpty())
        assertTrue(b.listFor("a", "va2", 1).isEmpty()); assertTrue(b.listFor("b", "va1", 1).isEmpty()); assertTrue(b.listFor("a", "va1", 2).isEmpty())
        // 文件名不含文档 id 和正文
        Files.list(dir).use { s -> s.forEach { assertFalse(it.fileName.toString().contains("a-doc") || it.fileName.toString().contains("草稿")) } }
    }

    @Test fun blankScopeDisablesTheStoreAndCorruptOrForeignFilesAreIgnoredNotDeleted() {
        val dir = Files.createTempDirectory("drafts-")
        val off = FileNoteDraftStore(dir, "")
        off.put(StoredDraft(id(""), "n1", "q", "x")); assertNull(off.get(id(""))); assertEquals(0, Files.list(dir).use { it.count() })
        val on = FileNoteDraftStore(dir, scope)
        on.put(StoredDraft(id(), "n1", "q", "草稿"))
        Files.writeString(dir.resolve("broken.json"), "{not json"); Files.writeString(dir.resolve("future.json"), """{"schema":99}""")
        assertEquals(1, on.listFor("a", "va1", 1).size)
        assertEquals(3, Files.list(dir).use { it.count() }, "不认识的文件不删")
        // 文件被改成别的身份：get 校验文件内身份，不当成命中
        val path = Files.list(dir).use { s -> s.toList().first { it.fileName.toString().length > 20 } }
        Files.writeString(path, Files.readString(path).replace("\"doc\":\"a\"", "\"doc\":\"zzz\""))
        assertNull(on.get(id()))
    }

    @Test fun removeOnlyDeletesTheMatchingIdentity() {
        val dir = Files.createTempDirectory("drafts-"); val s = FileNoteDraftStore(dir, scope)
        s.put(StoredDraft(id(start = 0, end = 16), "n1", "q1", "一")); s.put(StoredDraft(id(start = 17, end = 35), "n2", "q2", "二"))
        s.remove(id(start = 0, end = 16))
        assertNull(s.get(id(start = 0, end = 16))); assertEquals("二", s.get(id(start = 17, end = 35))!!.note)
        s.remove(id(doc = "other")) // 没有的身份：什么都不删
        assertEquals(1, s.listFor("a", "va1", 1).size)
    }

    // ---------------- 生产会话路径 ----------------
    private class Env(val lb: ReaderHttpSourceTest.Loopback, val dir: Path, val scope: String) {
        var refuse = true
        fun session(store: NoteDraftStore? = FileNoteDraftStore(dir, scope)): Pair<ReaderSession, ReaderRemote> {
            val remote = ReaderRemote(ReaderHttpSource(CourseHttp(lb.base, "dev1", "tok", "c1").reader))
            val s = ReaderSession(ReaderFixture("远端", emptyList(), emptyList()), 1100, remote, store)
            runBlocking { remote.loadCatalog(); s.openRemote("a"); remote.show(1) }
            return s to remote
        }
    }

    private fun withEnv(block: (Env) -> Unit) {
        val fake = FakeReaderService(Triple("a", "va1", 10), Triple("b", "vb1", 5)).apply { bodyText = { _, _ -> "Alpha beta gamma\nDelta epsilon zeta" } }
        val lb = ReaderHttpSourceTest.Loopback(fake)
        val env = Env(lb, Files.createTempDirectory("drafts-"), "local-library:test-A")
        lb.override = { r ->
            if (env.refuse && r.method == "POST" && r.rawPath.endsWith("/notes")) Triple(501, """{"error":"本地阅读器暂不支持保存批注"}""".toByteArray(), "application/json") else null
        }
        try { block(env) } finally { lb.stop() }
    }

    private fun openLine(s: ReaderSession, line: Int) {
        s.beginNote(); repeat(line) { s.onKey(KeyInput(ReaderKey.Down)) }; s.onKey(KeyInput(ReaderKey.Enter))
    }

    @Test fun failedSaveKeepsTheDraftAcrossCloseEditorNewSessionAndNewStoreInstance() = withEnv { env ->
        val (s, r) = env.session()
        openLine(s, 0); s.editNote("草稿A")
        runBlocking { s.commitNote() }
        assertEquals(NoteStatus.Failed, s.noteStatus())
        val sent = env.lb.fake.noteCalls.size
        // 失败后本机已有这份草稿（新存储实例读盘）
        val d = r.current!!
        val onDisk = FileNoteDraftStore(env.dir, env.scope).listFor(d.id, d.version, 1)
        assertEquals(listOf("草稿A"), onDisk.map { it.note }); assertEquals(s.noteDraft!!.id, onDisk.single().noteId)
        s.cancelNote(); assertNull(s.noteDraft) // 关闭编辑区：不等于放弃
        assertEquals(1, FileNoteDraftStore(env.dir, env.scope).listFor(d.id, d.version, 1).size, "普通关闭不能偷偷等于放弃")
        // 关书/重开应用：全新的会话、远端、存储实例
        val (s2, r2) = env.session(FileNoteDraftStore(env.dir, env.scope))
        val found = s2.localDrafts().single()
        assertEquals("草稿A", found.note)
        assertTrue(s2.resumeLocalDraft(found))
        assertEquals("草稿A", s2.noteText); assertEquals(NoteStatus.Dirty, s2.noteStatus(), "找回的是还没保存的草稿")
        assertEquals(found.noteId, s2.noteDraft!!.id); assertEquals(d.version, s2.noteDraft!!.version)
        assertEquals(sent, env.lb.fake.noteCalls.size, "找回/重开不会自动补发远端写入")
        assertTrue(r2.notes.isEmpty())
    }

    @Test fun selectingTheSameLineAgainLoadsTheLocalDraftAndClosingUnsavedTextPersistsIt() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 1); s.editNote("只是写了没保存"); s.cancelNote()   // 没点保存，直接关闭
        val (s2, _) = env.session()
        openLine(s2, 1)
        assertEquals("只是写了没保存", s2.noteText, "同一行再打开自动带回本机草稿")
        // 没改动的空编辑区关闭不产生草稿
        val (s3, _) = env.session(); openLine(s3, 0); s3.cancelNote()
        assertEquals(1, s3.localDrafts().size)
    }

    @Test fun serverAckClearsOnlyTheMatchingDraftAndNeverAutoResends() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0); s.editNote("第一行草稿"); runBlocking { s.commitNote() }; s.cancelNote()
        openLine(s, 1); s.editNote("第二行草稿"); runBlocking { s.commitNote() }; s.cancelNote()
        assertEquals(2, s.localDrafts().size)
        env.refuse = false // 远端恢复
        val (s2, r2) = env.session()
        val first = s2.localDrafts().first { it.note == "第一行草稿" }
        val callsBefore = env.lb.fake.noteCalls.size
        assertTrue(s2.resumeLocalDraft(first)); assertEquals(callsBefore, env.lb.fake.noteCalls.size, "找回本身不发请求")
        runBlocking { s2.commitNote() } // 用户显式保存，真实 ack
        assertIs<NoteSaveState.Saved>(r2.noteSave)
        assertEquals(listOf("第二行草稿"), s2.localDrafts().map { it.note }, "只清掉被 ack 的那一份，另一份还在")
        assertEquals(1, r2.notes.size)
    }

    @Test fun explicitDiscardDropsThisDraftOnlyAndOrdinaryCloseKeepsIt() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0); s.editNote("甲"); runBlocking { s.commitNote() }; s.cancelNote()
        openLine(s, 1); s.editNote("乙"); runBlocking { s.commitNote() }
        s.discardDraft() // 明确放弃正在编辑的这一份
        assertNull(s.noteDraft)
        assertEquals(listOf("甲"), FileNoteDraftStore(env.dir, env.scope).listFor("a", "va1", 1).map { it.note })
    }

    @Test fun otherDocumentVersionOwnerOrPageCannotReadOrResumeTheDraft() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0); s.editNote("别串"); runBlocking { s.commitNote() }; s.cancelNote()
        val stored = s.localDrafts().single()
        // 别的拥有者（scope）
        val (other, _) = env.session(FileNoteDraftStore(env.dir, "local-library:test-B"))
        assertTrue(other.localDrafts().isEmpty())
        // 没有可靠身份（空 scope）：存储停用
        val (blank, _) = env.session(FileNoteDraftStore(env.dir, ""))
        assertTrue(blank.localDrafts().isEmpty())
        // 别的文档
        env.lb.fake.docs.getValue("b")
        val remote = ReaderRemote(ReaderHttpSource(CourseHttp(env.lb.base, "dev1", "tok", "c1").reader))
        val sb = ReaderSession(ReaderFixture("远端", emptyList(), emptyList()), 1100, remote, FileNoteDraftStore(env.dir, env.scope))
        runBlocking { remote.loadCatalog(); sb.openRemote("b"); remote.show(1) }
        assertTrue(sb.localDrafts().isEmpty()); assertFalse(sb.resumeLocalDraft(stored), "别的文档不能强行带回")
        // 换版
        env.lb.fake.docs.getValue("a").version = "va2"
        val (s2, _) = env.session()
        assertTrue(s2.localDrafts().isEmpty()); assertFalse(s2.resumeLocalDraft(stored), "旧版本草稿不能写进新版本")
    }

    @Test fun oldSnapshotsStillOpenAndNoStoreMeansTheOldHonestBehaviour() = withEnv { env ->
        val fixtureSession = ReaderSession(ReaderFixture.synthetic(), 1100)
        assertTrue(fixtureSession.restore("v=1\npage=2\nsel=0\n", 1100), "不含草稿的旧快照照常能打开")
        val (s, _) = env.session(null)
        openLine(s, 0); s.editNote("没有存储"); runBlocking { s.commitNote() }; s.cancelNote()
        assertTrue(s.localDrafts().isEmpty(), "没有存储就是没有存储，不假装保留")
    }
}
