package com.coach.desktop.reader

import com.coach.client.CourseHttp
import com.coach.desktop.books.TextbookLibrary
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

/** 来源闸门、写盘失败状态、旧 schema（无 baseRevision）——依赖本轮新增的 API。 */
class DraftReviewApiTest {
    @Test fun draftsAreOnlyEnabledForARealLocalLibraryAndNeverForHttpOrSyntheticSources() {
        val dirA = Files.createTempDirectory("lib-a-"); val dirB = Files.createTempDirectory("lib-b-")
        val local = LocalBooksReaderSource(TextbookLibrary(dirA))
        val storeA = ReaderDraftScope.forSource(local, dirA)!!
        assertTrue(storeA.scope.startsWith("local-library:"))
        assertNotEquals(storeA.scope, ReaderDraftScope.forSource(LocalBooksReaderSource(TextbookLibrary(dirB)), dirB)!!.scope, "不同书库不同身份")
        assertNull(ReaderDraftScope.forSource(local, null), "没有真实书库目录就不启用")
        assertNull(ReaderDraftScope.forSource(SyntheticReaderSource(), dirA), "合成来源不启用")
        val http = ReaderHttpSource(CourseHttp("http://127.0.0.1:9", "d", "t", "c1").reader)
        assertNull(ReaderDraftScope.forSource(http, dirA), "HTTP 来源没有可信 owner/课程/来源身份，不能拿本地路径代替")
    }

    private fun env(): Triple<ReaderHttpSourceTest.Loopback, ReaderSession, ReaderRemote> {
        val fake = FakeReaderService(Triple("a", "va1", 10)).apply { bodyText = { _, _ -> "Alpha beta gamma\nDelta epsilon zeta" } }
        val lb = ReaderHttpSourceTest.Loopback(fake)
        lb.override = { r -> if (r.method == "POST" && r.rawPath.endsWith("/notes")) Triple(501, """{"error":"不支持"}""".toByteArray(), "application/json") else null }
        val remote = ReaderRemote(ReaderHttpSource(CourseHttp(lb.base, "dev1", "tok", "c1").reader))
        val blocker = Files.createTempFile("blocker", ".x") // 普通文件占住了存储目录
        val s = ReaderSession(ReaderFixture("远端", emptyList(), emptyList()), 1100, remote, FileNoteDraftStore(blocker, "local-library:io"))
        runBlocking { remote.loadCatalog(); s.openRemote("a"); remote.show(1) }
        return Triple(lb, s, remote)
    }

    @Test fun aFailedDiskWriteIsReportedHonestlyKeepsTheTextAndNeverThrowsIntoTyping() {
        val (lb, s, r) = env()
        try {
            s.beginNote(); s.onKey(KeyInput(ReaderKey.Enter))
            assertTrue(s.draftsKept())                    // 还没尝试写盘
            s.editNote("还在内存里的文字")                    // 写盘会失败：不能抛异常
            assertEquals("还在内存里的文字", s.noteText); assertTrue(s.persistFailed); assertFalse(s.draftsKept())
            runBlocking { s.commitNote() }
            val failed = (r.noteSave as NoteSaveState.Failed).failure
            assertTrue(NoteStatusRules.failureText(failed, s.draftsKept()).contains("不会保留"), "写盘失败时不能承诺留在本机")
            assertEquals("还在内存里的文字", s.noteText)
        } finally { lb.stop() }
    }

    @Test fun oldSchemaDraftsWithoutBaseRevisionAreNotGuessedIntoAnExistingRemoteNoteButNewOnesStillWork() {
        val fake = FakeReaderService(Triple("a", "va1", 10)).apply { bodyText = { _, _ -> "Alpha beta gamma\nDelta epsilon zeta" } }
        val lb = ReaderHttpSourceTest.Loopback(fake)
        var refuse = false
        lb.override = { r -> if (refuse && r.method == "POST" && r.rawPath.endsWith("/notes")) Triple(501, """{"error":"x"}""".toByteArray(), "application/json") else null }
        val dir = Files.createTempDirectory("drafts-"); val scope = "local-library:old"
        fun session(): Pair<ReaderSession, ReaderRemote> {
            val remote = ReaderRemote(ReaderHttpSource(CourseHttp(lb.base, "dev1", "tok", "c1").reader))
            val s = ReaderSession(ReaderFixture("远端", emptyList(), emptyList()), 1100, remote, FileNoteDraftStore(dir, scope))
            runBlocking { remote.loadCatalog(); s.openRemote("a"); remote.show(1) }
            return s to remote
        }
        fun stripBaseRevision() = Files.list(dir).use { st -> st.toList() }.filter { it.toString().endsWith(".json") }
            .forEach { Files.writeString(it, Files.readString(it).replace(Regex(",\"baseRevision\":-?[0-9]+"), "")) }
        try {
            // 既有远端批注 n1 上的草稿（旧 schema：没有 baseRevision）
            val (s, _) = session(); s.beginNote(); s.onKey(KeyInput(ReaderKey.Enter)); s.editNote("v1"); runBlocking { s.commitNote() }
            refuse = true; s.editNote("v1 改"); runBlocking { s.commitNote() }; s.cancelNote(); stripBaseRevision()
            val (a, _) = session()
            assertNull(FileNoteDraftStore(dir, scope).listFor("a", "va1", 1).single().baseRevision)
            assertFalse(a.resumeLocalDraft(a.localDrafts().single()), "服务端已有这条批注：缺修订号不能猜"); assertNull(a.noteDraft)
            // 全新的（服务端没有这个 id）旧 schema 草稿仍可带回，按新建处理
            val (b, _) = session(); b.beginNote(); repeat(1) { b.onKey(KeyInput(ReaderKey.Down)) }; b.onKey(KeyInput(ReaderKey.Enter)); b.editNote("全新的"); b.cancelNote(); stripBaseRevision()
            val fresh = FileNoteDraftStore(dir, scope).listFor("a", "va1", 1).first { it.note == "全新的" }
            val (c, _) = session()
            assertTrue(c.resumeLocalDraft(fresh)); assertEquals(0L, c.noteDraft!!.baseRevision)
        } finally { lb.stop() }
    }
}
