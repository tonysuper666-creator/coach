package com.coach.desktop.reader

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** boxes / 原句定位 / saveNote 的适配层行为。服务是 FakeReaderService（形状已逐字段对照 ReaderLibrary / ReaderRoutes），不是 18791。 */
class ReaderRemoteNotesTest {
    private val multiLine = "Alpha beta gamma\nDelta epsilon zeta\nEta theta iota"
    private fun service() = FakeReaderService(Triple("a", "va1", 10), Triple("b", "vb1", 6)).apply {
        bodyText = { _, _ -> multiLine }; anchorFor = { _, _ -> "Delta epsilon" }
    }

    private suspend fun opened(s: FakeReaderService = service(), page: Int = 1): ReaderRemote =
        ReaderRemote(s).also { it.loadCatalog(); it.open("a"); it.show(page) }

    private fun ReaderRemote.view() = (page as PageState.Ready).view
    private fun ReaderRemote.draft(line: Int, text: String, base: Long = 0L, id: String = "n1"): NoteDraft {
        val v = view(); val lines = v.text.split('\n'); var start = 0
        for (i in 0 until line) start += lines[i].length + 1
        val end = start + lines[line].length
        return NoteDraft(id, v.document, current!!.version, v.pdfPage, v.textSha256, start, end, v.text.substring(start, end), text, base)
    }

    @Test fun boxesLoadWithThePageAndLocateTheRegisteredSentence() = runBlocking<Unit> {
        val r = opened()
        val boxes = (r.boxes as BoxesState.Ready).boxes
        assertEquals(9, boxes.words.size)
        val found = QuoteLocator.locate(boxes, "Delta epsilon") as Located.Found
        assertEquals(1, found.rects.size); assertEquals(30.0, found.rects.single().y0)
        assertEquals(Located.NotFound, QuoteLocator.locate(boxes, "Delta zeta"))
        assertEquals("Delta epsilon", r.studyQuestions.single().anchor)
    }

    @Test fun ambiguousAndNoTextLayerAreStatedNotGuessed() = runBlocking<Unit> {
        val s = service(); s.bodyText = { _, _ -> "same same" }
        assertEquals(Located.Ambiguous, QuoteLocator.locate((opened(s).boxes as BoxesState.Ready).boxes, "same"))
        s.bodyText = { _, _ -> "" }
        assertEquals(Located.NoTextLayer, QuoteLocator.locate((opened(s).boxes as BoxesState.Ready).boxes, "x"))
    }

    @Test fun invalidBoxesAreRejectedExplicitly() = runBlocking<Unit> {
        val s = service(); val r = opened(s)
        fun failed() = assertIs<BoxesState.Failed>(r.boxes)
        s.boxesPatch = { JsonObject(it + ("version" to JsonPrimitive("other"))) }; r.show(1); failed()
        s.boxesPatch = { JsonObject(it + ("pdfPage" to JsonPrimitive(9))) }; r.show(1); failed()
        s.boxesPatch = { JsonObject(it - "words") }; r.show(1); failed()
        s.boxesPatch = { JsonObject(it + ("width" to JsonPrimitive(5.0))) }; r.show(1); failed() // 词框超出页宽
        s.boxesPatch = { it }; s.boxesStatus = 409; r.show(1); failed()
        s.boxesStatus = null; r.show(1); assertEquals(BoxesState.Failed(ReaderFailure.Offline), r.boxes)
        assertIs<PageState.Ready>(r.page, "词框失败不影响页文字")
    }

    @Test fun lateBoxesFromThePreviousBookAreDropped() = runBlocking<Unit> {
        val s = service(); s.bodyText = { d, p -> "${d.id}word page$p" }
        val r = ReaderRemote(s); r.loadCatalog(); r.open("a")
        val (entered, gate) = s.holdBoxes("a", 2)
        val slow = launch { r.show(2) }
        entered.await()
        r.open("b"); r.show(1)
        gate.complete(Unit); slow.join()
        assertEquals("bword", (r.boxes as BoxesState.Ready).boxes.words.first().text)
        assertEquals("bword page1", r.view().text)
    }

    @Test fun noteSavesAndRestoresInANewSession() = runBlocking<Unit> {
        val s = service(); val r = opened(s)
        r.saveNote(r.draft(1, "第一条批注"))
        assertIs<NoteSaveState.Saved>(r.noteSave)
        val sent = s.noteCalls.single()
        assertEquals(listOf("id", "version", "pdfPage", "textSha256", "start", "end", "quote", "note", "deleted", "baseRevision", "mutationId").sorted(), sent.keys.toList().sorted())
        assertEquals(JsonPrimitive("Delta epsilon zeta"), sent["quote"])
        val r2 = opened(s)
        val n = r2.notes.single()
        assertEquals("第一条批注", n.note); assertTrue(r2.noteValid(n, r2.view()))
        s.bodyText = { _, _ -> "Alpha beta gamm!\nDelta epsilon zeta\nEta theta iota" }
        r2.show(1); assertTrue(r2.noteValid(n, r2.view()), "偏移处文字与引文一致时仍有效")
        s.bodyText = { _, _ -> "Totally different\ntext here\nEta theta iota" }
        r2.show(1); assertFalse(r2.noteValid(n, r2.view()), "原文变了：位置待重新确认，不猜")
    }

    @Test fun offlineNoteRetriesWithTheSameMutationThenNewEditGetsNewOne() = runBlocking<Unit> {
        val s = service(); val r = opened(s)
        val d = r.draft(0, "草稿")
        s.noteStatus = null; r.saveNote(d); assertEquals(NoteSaveState.Offline("n1"), r.noteSave)
        s.noteStatus = 200; r.saveNote(d); assertIs<NoteSaveState.Saved>(r.noteSave)
        assertEquals(2, s.noteCalls.size); assertEquals(s.noteCalls[0]["mutationId"], s.noteCalls[1]["mutationId"])
        assertEquals(1, r.notes.size)
        val rev = (r.noteSave as NoteSaveState.Saved).revision
        r.saveNote(d.copy(note = "改过", baseRevision = rev)); assertIs<NoteSaveState.Saved>(r.noteSave)
        assertNotEquals(s.noteCalls[1]["mutationId"], s.noteCalls[2]["mutationId"])
        assertEquals("改过", r.notes.single().note)
    }

    @Test fun noteConflictKeepsTheDraftAndNeverOverwritesOrRepeats() = runBlocking<Unit> {
        val s = service(); val r = opened(s)
        r.saveNote(r.draft(1, "v1"))
        val rev = (r.noteSave as NoteSaveState.Saved).revision
        s.otherDeviceEditsNote("a", "n1", "对方改的")
        val mine = r.draft(1, "我的改动", base = rev)
        r.saveNote(mine)
        assertEquals(NoteSaveState.Conflict("n1"), r.noteSave)
        assertEquals(JsonPrimitive("对方改的"), s.serverNote("a", "n1")!!["note"], "服务端不被盲目覆盖")
        assertEquals("v1", r.notes.single().note, "本地列表也不把冲突草稿当成已保存")
        assertEquals(2, s.noteCalls.size, "冲突后不自动重试")
    }

    @Test fun noteReceiptAfterBookSwitchIsDropped() = runBlocking<Unit> {
        val s = service(); val r = opened(s)
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>() to kotlinx.coroutines.CompletableDeferred<Unit>()
        s.noteHold = hold
        val d = r.draft(0, "慢保存")
        val slow = launch { r.saveNote(d) }
        hold.first.await()
        r.open("b"); r.show(1)
        hold.second.complete(Unit); slow.join()
        assertTrue(r.notes.isEmpty(), "旧书的回执不得写进新书的批注")
        assertEquals(NoteSaveState.Idle, r.noteSave)
    }

    @Test fun versionChangedNoteIsConflictNotSilentlySaved() = runBlocking<Unit> {
        val s = service(); val r = opened(s)
        s.docs.getValue("a").version = "va2"
        r.saveNote(r.draft(0, "x"))
        assertEquals(NoteSaveState.Conflict("n1"), r.noteSave)
        assertTrue(s.docs.getValue("a").let { s.serverNote("a", "n1") } == null)
    }

    @Test fun unresolvedProgressConflictIsNotRewrittenOnEveryPage() = runBlocking<Unit> {
        val s = service(); val r = opened(s)
        s.otherDeviceSaves("a", 7)
        r.saveProgressAuto(2)
        assertIs<SaveState.Conflict>(r.save)
        val calls = s.saveCalls.size; val states = 0
        r.saveProgressAuto(3); r.saveProgressAuto(4); r.saveProgressAuto(5)
        assertEquals(calls, s.saveCalls.size, "冲突未解决时翻页不再写同一冲突")
        assertEquals(5, (r.save as SaveState.Conflict).localPage, "草稿页跟着当前页")
        assertEquals(7, s.docs.getValue("a").progressPage)
        assertEquals(0, states)
    }
}
