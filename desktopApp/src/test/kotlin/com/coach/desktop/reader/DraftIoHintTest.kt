package com.coach.desktop.reader

import com.coach.client.CourseHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

/**
 * “本机草稿没能写入磁盘，关闭后会丢”的提示（persistFailed/draftsKept）：故障消失后、当前内容真实 ack 或重开已保存编辑器后必须消失；
 * 仍未落盘的新文字必须继续警示；A 的迟到 ack 不能清掉 B 的失败提示。合成写盘失败，生产 ReaderSession/ReaderRemote + 回环 HTTP fixture。
 */
class DraftIoHintTest {
    /** 可按需让 put 失败的存储（合成写盘故障），其余委托给真实 FileNoteDraftStore。 */
    private class FlakyStore(private val real: NoteDraftStore) : NoteDraftStore {
        @Volatile var failPuts = false
        override val scope get() = real.scope
        override fun put(draft: StoredDraft): Boolean = if (failPuts) false else real.put(draft)
        override fun get(identity: DraftIdentity) = real.get(identity)
        override fun listFor(doc: String, version: String, pdfPage: Int) = real.listFor(doc, version, pdfPage)
        override fun remove(identity: DraftIdentity) = real.remove(identity)
    }

    private class Env(val lb: ReaderHttpSourceTest.Loopback) {
        val store = FlakyStore(FileNoteDraftStore(Files.createTempDirectory("drafts-"), "local-library:io-hint"))
        var refuse = false
        fun session(): Pair<ReaderSession, ReaderRemote> {
            val remote = ReaderRemote(ReaderHttpSource(CourseHttp(lb.base, "dev1", "tok", "c1").reader))
            val s = ReaderSession(ReaderFixture("远端", emptyList(), emptyList()), 1100, remote, store)
            runBlocking { remote.loadCatalog(); s.openRemote("a"); remote.show(1) }
            return s to remote
        }
    }

    private fun withEnv(block: (Env) -> Unit) {
        val fake = FakeReaderService(Triple("a", "va1", 10)).apply { bodyText = { _, _ -> "Alpha beta gamma\nDelta epsilon zeta" } }
        val lb = ReaderHttpSourceTest.Loopback(fake)
        val env = Env(lb)
        lb.override = { r ->
            if (env.refuse && r.method == "POST" && r.rawPath.endsWith("/notes")) Triple(501, """{"error":"不支持"}""".toByteArray(), "application/json") else null
        }
        try { block(env) } finally { lb.stop() }
    }

    private fun openLine(s: ReaderSession, line: Int) { s.beginNote(); repeat(line) { s.onKey(KeyInput(ReaderKey.Down)) }; s.onKey(KeyInput(ReaderKey.Enter)) }

    @Test fun theWarningDisappearsOnceTheFaultIsGoneAndTheCurrentContentIsReallyAcknowledged() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0)
        env.store.failPuts = true
        s.editNote("写不进磁盘的文字")
        assertTrue(s.persistFailed); assertFalse(s.draftsKept())
        s.editNote("写不进磁盘的文字 再改"); assertTrue(s.persistFailed, "故障还在、新文字仍未落盘：必须继续警示")
        env.store.failPuts = false                                   // 故障消失
        runBlocking { s.commitNote() }                               // 远端真实 ack（内容就是当前文字）
        assertEquals(NoteStatus.Saved, s.noteStatus())
        assertFalse(s.persistFailed, "当前内容已被真实 ack，没有任何未落盘的文字，旧提示必须消失"); assertTrue(s.draftsKept())
    }

    @Test fun theWarningStaysWhileNewTextIsStillNotOnDiskAndClearsWhenSomethingSucceedsToBePersisted() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0)
        env.store.failPuts = true; s.editNote("x"); assertTrue(s.persistFailed)
        env.store.failPuts = false; s.editNote("xy")                  // 这一份成功落盘
        assertFalse(s.persistFailed); assertTrue(s.draftsKept())
        env.store.failPuts = true; runBlocking { env.refuse = true; s.editNote("xyz") }; assertTrue(s.persistFailed, "新文字再次写失败：重新警示")
    }

    @Test fun reopeningAnAlreadySavedEditorClearsTheOldWarning() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0); s.editNote("已保存的文字"); runBlocking { s.commitNote() }
        env.store.failPuts = true; s.editNote("已保存的文字+"); assertTrue(s.persistFailed)
        s.cancelNote()                                               // 关闭
        env.store.failPuts = false
        val (s2, _) = env.session()
        openLine(s2, 0)                                              // 重新打开已保存的批注
        assertEquals("已保存的文字", s2.noteText); assertFalse(s2.persistFailed, "重开已保存的编辑器：没有未落盘的新文字，不该再警示"); assertTrue(s2.draftsKept())
    }

    @Test fun aLateAckFromAnEarlierEditorNeverClearsTheCurrentEditorsFailureWarning() = withEnv { env ->
        val (s, _) = env.session()
        openLine(s, 0); s.editNote("A")
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        env.fake().noteHold = entered to gate
        runBlocking {
            val job = launch(Dispatchers.IO) { s.commitNote() }          // A 的请求在途
            entered.await()
            s.cancelNote(); openLine(s, 0)                               // 关闭后重新打开（B 编辑器）
            env.store.failPuts = true; s.editNote("B")                   // B 写盘失败
            assertTrue(s.persistFailed)
            env.fake().noteHold = null; gate.complete(Unit); job.join()  // A 迟到的 ack
            assertTrue(s.persistFailed, "A 的迟到 ack 不能清掉 B 的失败提示"); assertEquals("B", s.noteText)
        }
    }

    private fun Env.fake() = lb.fake
}
