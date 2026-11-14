package com.coach.desktop.reader

import com.coach.client.CourseHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/**
 * 远端保存被明确拒绝（本地阅读器的 501）时的行为，走真实 CourseHttp 传输 + 本机回环 HTTP 故障 fixture + 生产的 ReaderSession/ReaderRemote 路径：
 * 界面状态必须是"未保存"并带上服务端给出的原因（不是笼统的"服务出错（501）"，更不是成功），请求必须属于同一 doc/version，草稿原样保留，服务端数据不变。
 * 数据全是自造的；不连生产、不用真实教材。
 */
class ReaderRefusedSaveTest {
    private val progressRefusal = "本地阅读进度由书架保存，暂不支持此写入方式"
    private val noteRefusal = "本地阅读器暂不支持保存批注，草稿仍保留"

    private fun fake() = FakeReaderService(Triple("a", "va1", 10)).apply {
        bodyText = { _, _ -> "Alpha beta gamma\nDelta epsilon zeta" }
    }

    private fun withRefusingServer(block: suspend (ReaderHttpSourceTest.Loopback, ReaderSession, ReaderRemote) -> Unit) {
        val lb = ReaderHttpSourceTest.Loopback(fake())
        lb.override = { r ->
            when {
                r.method == "POST" && r.rawPath.endsWith("/progress") -> Triple(501, """{"error":"$progressRefusal"}""".toByteArray(), "application/json")
                r.method == "POST" && r.rawPath.endsWith("/notes") -> Triple(501, """{"error":"$noteRefusal"}""".toByteArray(), "application/json")
                else -> null
            }
        }
        try {
            val remote = ReaderRemote(ReaderHttpSource(CourseHttp(lb.base, "dev1", "tok", "c1").reader))
            val session = ReaderSession(ReaderFixture("远端", emptyList(), emptyList()), 1100, remote)
            runBlocking { remote.loadCatalog(); session.openRemote("a"); remote.show(1); block(lb, session, remote) }
        } finally { lb.stop() }
    }

    @Test fun refusedProgressSaveIsShownAsNotSavedWithTheServersReasonAndTheSameDocVersion() = withRefusingServer { lb, _, r ->
        r.saveProgress(2)
        val failed = r.save as SaveState.Failed
        assertTrue(failed.failure.text.contains(progressRefusal), "界面要看到服务端明确给出的原因，实际：${failed.failure.text}")
        assertNotEquals(SaveState.Saved(2), r.save)
        val req = lb.seen.last { it.method == "POST" && it.rawPath.endsWith("/progress") }
        val body = Json.parseToJsonElement(req.body).jsonObject
        assertEquals("/v1/courses/c1/reader/a/progress", req.rawPath)
        assertEquals(JsonPrimitive("va1"), body["version"]); assertEquals(JsonPrimitive(2), body["pdfPage"])
        assertNull(lb.fake.docs.getValue("a").progressPage, "拒绝后服务端进度不变")
        // 再自动保存一次仍是未保存，不会在重试里变成成功
        r.saveProgressAuto(3); assertIs<SaveState.Failed>(r.save)
    }

    @Test fun refusedNoteSaveKeepsTheSameDraftAndNeverCountsAsSaved() = withRefusingServer { lb, s, r ->
        s.beginNote(); assertTrue(s.noting)
        s.onKey(KeyInput(ReaderKey.Enter)) // 确认第一行
        val draft = checkNotNull(s.noteDraft)
        s.noteText = "我的草稿"
        s.commitNote()
        val failed = r.noteSave as NoteSaveState.Failed
        assertTrue(failed.failure.text.contains(noteRefusal), "实际：${failed.failure.text}")
        assertEquals(NoteStatus.Failed, s.noteStatus(), "界面状态必须是没保存")
        assertEquals(draft.id, s.noteDraft!!.id); assertEquals("va1", s.noteDraft!!.version); assertEquals("我的草稿", s.noteText)
        assertTrue(r.notes.isEmpty(), "没有保存成功的批注不能出现在列表里")
        assertNull(lb.fake.serverNote("a", draft.id))
        val req = lb.seen.last { it.method == "POST" && it.rawPath.endsWith("/notes") }
        val body = Json.parseToJsonElement(req.body).jsonObject
        assertEquals(JsonPrimitive("va1"), body["version"]); assertEquals(JsonPrimitive(draft.id), body["id"]); assertEquals(JsonPrimitive("我的草稿"), body["note"])
        // 501 = 来源明确不支持保存：要告诉用户关闭编辑区后草稿不会保留
        assertTrue(NoteStatusRules.failureText(failed.failure).contains("关闭编辑区后"), "501 要明说草稿关闭后不保留")
    }

    @Test fun transportFailureAndVersionChangeKeepTheOriginalDraftAndNeverWriteItToAnotherVersion() {
        val f = fake()
        val lb = ReaderHttpSourceTest.Loopback(f)
        try {
            val remote = ReaderRemote(ReaderHttpSource(CourseHttp(lb.base, "dev1", "tok", "c1").reader))
            val s = ReaderSession(ReaderFixture("远端", emptyList(), emptyList()), 1100, remote)
            runBlocking {
                remote.loadCatalog(); s.openRemote("a"); remote.show(1)
                s.beginNote(); s.onKey(KeyInput(ReaderKey.Enter)); s.noteText = "断网时写的草稿"
                val draft = s.noteDraft!!
                f.noteStatus = null // 连接失败
                s.commitNote()
                assertEquals(NoteStatus.Offline, s.noteStatus()); assertEquals("断网时写的草稿", s.noteText)
                // 资料换版：服务端版本变了，再保存只能冲突，草稿不会被改版本写回
                f.noteStatus = 200; f.docs.getValue("a").version = "va2"
                s.commitNote()
                assertEquals(NoteStatus.Conflict, s.noteStatus())
                assertEquals("va1", s.noteDraft!!.version, "草稿仍是原版本")
                assertNull(f.serverNote("a", draft.id), "没有把旧版本草稿写成新版本的批注")
                assertTrue(f.noteCalls.all { (it["version"] as JsonPrimitive).content == "va1" }, "所有请求都是原版本")
            }
        } finally { lb.stop() }
    }
}
