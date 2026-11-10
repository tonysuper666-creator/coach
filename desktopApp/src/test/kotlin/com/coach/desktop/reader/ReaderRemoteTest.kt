package com.coach.desktop.reader

import com.coach.client.ReaderReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/** 自造服务响应（不是 18791，也不是任何真实教材）：按 ReaderRoutes/ReaderLibrary 的真实 JSON 形状构造。 */
class FakeReaderService(vararg docs: Triple<String, String, Int>) : ReaderSource {
    class Doc(val id: String, var version: String, val pages: Int, val offset: Int? = null) {
        var progressPage: Int? = null; var revision = 0L
    }
    val docs = docs.associate { (id, version, pages) -> id to Doc(id, version, pages, offset = 2) }
    val pageCalls = mutableListOf<Pair<String, Int>>()
    val saveCalls = mutableListOf<List<Any>>()
    private val seenMutations = mutableMapOf<String, Long>()
    private val held = mutableMapOf<Pair<String, Int>, Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>()
    var catalogStatus: Int? = 200
    var pageStatus: Int? = 200
    var saveStatus: Int? = 200
    var stateStatus: Int? = 200
    var pageBodyPatch: (JsonObject) -> JsonObject = { it }
    var staleProgress = false
    var catalogExtra: List<JsonObject> = emptyList()
    /** 页文字（默认单行；笔记/词框用例换成多行英文词，词框按它逐词生成）。 */
    var bodyText: (Doc, Int) -> String = { d, p -> "【${d.id}】第${p}页自造正文" }
    var anchorFor: (Doc, Int) -> String = { _, _ -> "" }
    var boxesStatus: Int? = 200
    var boxesPatch: (JsonObject) -> JsonObject = { it }
    var withImage = false
    val noteCalls = mutableListOf<JsonObject>()
    var noteStatus: Int? = 200
    private val notes = mutableMapOf<String, MutableMap<String, JsonObject>>()
    private var noteSeq = 100L
    private val heldBoxes = mutableMapOf<Pair<String, Int>, Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>()

    fun holdBoxes(doc: String, page: Int): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> =
        (CompletableDeferred<Unit>() to CompletableDeferred<Unit>()).also { heldBoxes[doc to page] = it }

    fun hold(doc: String, page: Int): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> =
        (CompletableDeferred<Unit>() to CompletableDeferred<Unit>()).also { held[doc to page] = it }

    private fun ok(b: JsonObject) = ReaderReply(200, b)
    private fun view(d: Doc) = buildJsonObject { put("id", d.id); put("title", "自造样书 ${d.id}"); put("version", d.version); put("pages", d.pages) }

    override suspend fun catalog(): ReaderReply {
        catalogStatus?.takeIf { it != 200 }?.let { return ReaderReply(it, null) }
        if (catalogStatus == null) return ReaderReply(null, null)
        return ok(buildJsonObject { put("documents", JsonArray(docs.values.map { view(it) } + catalogExtra)) })
    }

    override suspend fun page(document: String, pdfPage: Int): ReaderReply {
        pageCalls += document to pdfPage
        held[document to pdfPage]?.let { (entered, gate) -> entered.complete(Unit); gate.await() }
        if (pageStatus == null) return ReaderReply(null, null)
        if (pageStatus != 200) return ReaderReply(pageStatus, null)
        val d = docs[document]; if (d == null || pdfPage !in 1..d.pages) return ReaderReply(404, null)
        return ok(pageBodyPatch(buildJsonObject {
            put("documentId", d.id); put("version", d.version); put("pdfPage", pdfPage)
            put("printedPage", (pdfPage - 2).takeIf { it > 0 }?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            put("text", bodyText(d, pdfPage)); put("textSha256", "x")
        }))
    }

    override suspend fun image(document: String, pdfPage: Int, version: String?): ByteArray? {
        if (!withImage) return null
        val img = java.awt.image.BufferedImage(400, 300, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = java.awt.Color.WHITE; g.fillRect(0, 0, 400, 300); g.dispose()
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
    }

    /** 形状对照 ReaderLibrary.parseBoxes：{documentId,version,pdfPage,width,height,words[{x0,y0,x1,y1,text}],textLayer}。 */
    override suspend fun boxes(document: String, pdfPage: Int, version: String): ReaderReply {
        heldBoxes[document to pdfPage]?.let { (entered, gate) -> entered.complete(Unit); gate.await() }
        if (boxesStatus == null) return ReaderReply(null, null)
        if (boxesStatus != 200) return ReaderReply(boxesStatus, null)
        val d = docs[document] ?: return ReaderReply(404, null)
        if (version != d.version) return ReaderReply(409, null)
        if (pdfPage !in 1..d.pages) return ReaderReply(404, null)
        val words = mutableListOf<JsonObject>()
        bodyText(d, pdfPage).split('\n').forEachIndexed { li, line ->
            line.split(' ').filter { it.isNotEmpty() }.forEachIndexed { wi, w ->
                words += buildJsonObject { put("x0", 10.0 + 40 * wi); put("y0", 10.0 + 20 * li); put("x1", 10.0 + 40 * wi + 35); put("y1", 10.0 + 20 * li + 15); put("text", w) }
            }
        }
        return ok(boxesPatch(buildJsonObject {
            put("documentId", d.id); put("version", d.version); put("pdfPage", pdfPage); put("width", 400.0); put("height", 300.0)
            put("words", JsonArray(words)); put("textLayer", words.isNotEmpty())
        }))
    }

    /** 形状对照 ReaderLedger.save(note=true)：回执 {revision,savedAt,replayed}；409 条件 = 版本不符 / baseRevision != 该批注最新修订。 */
    var noteHold: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
    override suspend fun saveNote(document: String, body: JsonObject): ReaderReply {
        noteCalls += body
        noteHold?.let { (entered, gate) -> entered.complete(Unit); gate.await() }
        if (noteStatus == null) return ReaderReply(null, null)
        if (noteStatus != 200) return ReaderReply(noteStatus, null)
        val d = docs[document] ?: return ReaderReply(404, null)
        fun str(k: String) = (body[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val mutation = str("mutationId")
        seenMutations[mutation]?.let { return ok(buildJsonObject { put("revision", it); put("replayed", true) }) }
        val id = str("id"); val store = notes.getOrPut(d.id) { mutableMapOf() }
        val last = store[id]?.let { (it["revision"] as kotlinx.serialization.json.JsonPrimitive).content.toLong() } ?: 0L
        val base = str("baseRevision").toLong()
        if (str("version") != d.version || base != last) return ReaderReply(409, buildJsonObject { put("error", "conflict") })
        noteSeq += 1
        store[id] = JsonObject(body + ("revision" to kotlinx.serialization.json.JsonPrimitive(noteSeq)) + ("stale" to kotlinx.serialization.json.JsonPrimitive(false)) + ("documentId" to kotlinx.serialization.json.JsonPrimitive(d.id)))
        seenMutations[mutation] = noteSeq
        return ok(buildJsonObject { put("revision", noteSeq); put("replayed", false) })
    }

    /** 另一台设备改了这条批注。 */
    fun otherDeviceEditsNote(doc: String, id: String, text: String) {
        val store = notes.getValue(doc); val n = store.getValue(id); noteSeq += 1
        store[id] = JsonObject(n + ("note" to kotlinx.serialization.json.JsonPrimitive(text)) + ("revision" to kotlinx.serialization.json.JsonPrimitive(noteSeq)))
    }
    fun serverNote(doc: String, id: String): JsonObject? = notes[doc]?.get(id)

    override suspend fun study(document: String, pdfPage: Int): ReaderReply {
        val d = docs[document] ?: return ReaderReply(404, null)
        return ok(buildJsonObject {
            put("documentId", d.id); put("pdfPage", pdfPage)
            put("facts", JsonArray(emptyList()))
            put("questions", JsonArray(listOf(buildJsonObject { put("id", "q-${d.id}-$pdfPage"); put("stem", "【${d.id}】第${pdfPage}页的题"); put("status", "new"); put("anchor", anchorFor(d, pdfPage)) })))
            put("unlocated", 0)
        })
    }

    override suspend fun state(document: String): ReaderReply {
        if (stateStatus != 200) return ReaderReply(stateStatus, null)
        val d = docs[document] ?: return ReaderReply(404, null)
        return ok(buildJsonObject {
            put("document", view(d))
            put("progress", d.progressPage?.let { p ->
                buildJsonObject { put("documentId", d.id); put("version", d.version); put("pdfPage", p); put("revision", d.revision); put("stale", staleProgress) }
            } ?: JsonNull)
            put("notes", JsonArray((notes[d.id]?.values ?: emptyList<JsonObject>()).map { n -> JsonObject(n + ("stale" to kotlinx.serialization.json.JsonPrimitive((n["version"] as kotlinx.serialization.json.JsonPrimitive).content != d.version))) }))
        })
    }

    override suspend fun saveProgress(document: String, version: String, pdfPage: Int, baseRevision: Long, mutationId: String): ReaderReply {
        saveCalls += listOf(document, version, pdfPage, baseRevision, mutationId)
        if (saveStatus == null) return ReaderReply(null, null)
        if (saveStatus != 200) return ReaderReply(saveStatus, null)
        val d = docs[document] ?: return ReaderReply(404, null)
        seenMutations[mutationId]?.let { return ok(buildJsonObject { put("revision", it); put("replayed", true) }) }
        if (version != d.version || baseRevision != d.revision) return ReaderReply(409, buildJsonObject { put("error", "conflict") })
        d.revision += 1; d.progressPage = pdfPage; seenMutations[mutationId] = d.revision
        return ok(buildJsonObject { put("revision", d.revision); put("replayed", false) })
    }

    /** 另一台设备保存了进度。 */
    fun otherDeviceSaves(doc: String, page: Int) { docs.getValue(doc).let { it.revision += 1; it.progressPage = page } }
}

class ReaderRemoteTest {
    private fun service() = FakeReaderService(Triple("a", "va1", 10), Triple("b", "vb1", 6))
    private suspend fun ready(s: FakeReaderService = service()): Pair<FakeReaderService, ReaderRemote> =
        s to ReaderRemote(s).also { it.loadCatalog() }

    private fun ReaderRemote.text() = (page as? PageState.Ready)?.view?.text

    @Test fun catalogKeepsOnlyCompleteDocumentsAndFailsExplicitly() = runBlocking<Unit> {
        val s = service()
        s.catalogExtra = listOf(
            buildJsonObject { put("id", "nopages"); put("title", "缺页数"); put("version", "v") },
            buildJsonObject { put("id", "nover"); put("title", "缺版本"); put("pages", 3) },
        )
        val r = ReaderRemote(s); r.loadCatalog()
        assertEquals(listOf("a", "b"), r.documents.map { it.id })
        s.catalogStatus = 401; r.loadCatalog(); assertEquals(ReaderFailure.Unauthorized, r.failure); assertTrue(r.documents.isEmpty())
        s.catalogStatus = null; r.loadCatalog(); assertEquals(ReaderFailure.Offline, r.failure)
    }

    @Test fun openRestoresServerProgressPageAndFallsBackToFirst() = runBlocking<Unit> {
        val (s, r) = ready()
        assertEquals(1, r.open("a"))
        s.docs.getValue("a").apply { progressPage = 4; revision = 3 }
        assertEquals(4, r.open("a"))
        s.staleProgress = true; assertEquals(1, r.open("a"))
        s.staleProgress = false; s.docs.getValue("a").progressPage = 99; assertEquals(1, r.open("a"))
        assertNull(r.open("nope")); assertIs<ReaderFailure.BadResponse>(r.failure)
    }

    @Test fun pdfPagesAreOneBasedAndConvertedInOnePlace() {
        assertEquals(0, PdfPages.toIndex(1)); assertEquals(1, PdfPages.toPdf(0)); assertEquals(7, PdfPages.toPdf(PdfPages.toIndex(7)))
    }

    @Test fun twoBooksNeverShareContent() = runBlocking<Unit> {
        val (_, r) = ready()
        r.open("a"); r.show(2); assertEquals("【a】第2页自造正文", r.text())
        r.open("b")
        assertEquals(PageState.Idle, r.page); assertEquals(StudyState.Idle, r.study)
        r.show(1); assertEquals("【b】第1页自造正文", r.text())
        assertEquals("【b】第1页的题", ((r.study as StudyState.Ready).view.questions.single().stem))
    }

    @Test fun lateResponseFromPreviousBookIsDropped() = runBlocking<Unit> {
        val (s, r) = ready()
        r.open("a")
        val (entered, gate) = s.hold("a", 2)
        val slow = launch { r.show(2) }
        entered.await()
        r.open("b"); r.show(1)
        gate.complete(Unit); slow.join()
        assertEquals("【b】第1页自造正文", r.text(), "迟到的 A 书第2页不得覆盖 B 书")
        assertEquals("b", r.current?.id)
        assertEquals("【b】第1页的题", (r.study as StudyState.Ready).view.questions.single().stem)
    }

    @Test fun fastFlippingLatestPageWins() = runBlocking<Unit> {
        val (s, r) = ready()
        r.open("a")
        val (entered, gate) = s.hold("a", 2)
        val slow = launch { r.show(2) }
        entered.await()
        r.show(3)
        gate.complete(Unit); slow.join()
        assertEquals("【a】第3页自造正文", r.text())
    }

    @Test fun unknownPageIsNotRequestedOrGuessed() = runBlocking<Unit> {
        val (s, r) = ready()
        r.open("a")
        r.show(0); assertEquals(PageState.Failed(ReaderFailure.MissingPage(0)), r.page)
        r.show(11); assertEquals(PageState.Failed(ReaderFailure.MissingPage(11)), r.page)
        assertTrue(s.pageCalls.isEmpty())
    }

    @Test fun failuresAreExplicit() = runBlocking<Unit> {
        val (s, r) = ready()
        r.open("a")
        s.pageStatus = 401; r.show(1); assertEquals(PageState.Failed(ReaderFailure.Unauthorized), r.page)
        s.pageStatus = 404; r.show(1); assertIs<ReaderFailure.MissingPage>((r.page as PageState.Failed).failure)
        s.pageStatus = 409; r.show(1); assertEquals(PageState.Failed(ReaderFailure.VersionChanged), r.page)
        s.pageStatus = null; r.show(1); assertEquals(PageState.Failed(ReaderFailure.Offline), r.page)
        s.pageStatus = 200
        s.pageBodyPatch = { b -> JsonObject(b + ("version" to kotlinx.serialization.json.JsonPrimitive("other"))) }
        r.show(1); assertEquals(PageState.Failed(ReaderFailure.VersionChanged), r.page)
        s.pageBodyPatch = { b -> JsonObject(b - "text") }
        r.show(1); assertIs<ReaderFailure.BadResponse>((r.page as PageState.Failed).failure)
        s.pageBodyPatch = { b -> JsonObject(b + ("pdfPage" to kotlinx.serialization.json.JsonPrimitive(9))) }
        r.show(1); assertIs<ReaderFailure.BadResponse>((r.page as PageState.Failed).failure)
        s.pageBodyPatch = { it }
        // 目录与进度里的资料版本不一致：打开即失败，不猜
        s.docs.getValue("a").version = "va2"
        assertNull(r.open("a")); assertEquals(ReaderFailure.VersionChanged, r.failure)
    }

    @Test fun saveThenRestoreInNewSession() = runBlocking<Unit> {
        val (s, r) = ready()
        r.open("a"); r.show(4); r.saveProgress(4)
        assertEquals(SaveState.Saved(4), r.save)
        val (_, r2) = ready(s)
        assertEquals(4, r2.open("a"))
        r2.saveProgress(7); assertEquals(SaveState.Saved(7), r2.save) // 修订号随回执推进，连续保存不自冲突
    }

    @Test fun conflictKeepsLocalDraftAndNeverOverwritesBlindly() = runBlocking<Unit> {
        val (s, r) = ready()
        r.open("a")
        s.otherDeviceSaves("a", 7)
        r.saveProgress(5)
        val c = r.save as SaveState.Conflict
        assertEquals(5, c.localPage); assertEquals(7, c.remote?.pdfPage); assertFalse(c.versionChanged)
        assertEquals(7, s.docs.getValue("a").progressPage, "409 后服务端不被覆盖")
        r.keepLocalAfterConflict()
        assertEquals(SaveState.Saved(5), r.save); assertEquals(5, s.docs.getValue("a").progressPage)
        // 另一条路：用另一台的位置
        s.otherDeviceSaves("a", 8); r.saveProgress(6)
        assertIs<SaveState.Conflict>(r.save)
        assertEquals(8, r.useRemoteAfterConflict()); assertEquals(SaveState.Saved(8), r.save)
    }

    @Test fun versionChangeConflictDoesNotRetry() = runBlocking<Unit> {
        val (s, r) = ready()
        r.open("a")
        s.docs.getValue("a").version = "va2"
        r.saveProgress(3)
        val c = r.save as SaveState.Conflict
        assertTrue(c.versionChanged)
        val before = s.saveCalls.size
        r.keepLocalAfterConflict(); assertEquals(before, s.saveCalls.size, "版本变了不得重试保存")
        assertNull(r.useRemoteAfterConflict())
    }

    @Test fun offlineSaveRetriesWithSameMutationId() = runBlocking<Unit> {
        val (s, r) = ready()
        r.open("a")
        s.saveStatus = null; r.saveProgress(3); assertEquals(SaveState.Offline(3), r.save)
        s.saveStatus = 200; r.saveProgress(3); assertEquals(SaveState.Saved(3), r.save)
        assertEquals(2, s.saveCalls.size); assertEquals(s.saveCalls[0][4], s.saveCalls[1][4], "重试必须沿用同一 mutationId")
        r.saveProgress(4); assertNotEquals(s.saveCalls[1][4], s.saveCalls[2][4])
        s.saveStatus = 401; r.saveProgress(5); assertEquals(SaveState.Failed(ReaderFailure.Unauthorized), r.save)
    }

    @Test fun savingForAnotherBookIsDroppedWhenSwitched() = runBlocking<Unit> {
        val (s, r) = ready()
        r.open("a"); r.saveProgress(2)
        r.open("b")
        assertEquals(SaveState.Clean, r.save)
        assertEquals(2, s.docs.getValue("a").progressPage); assertNull(s.docs.getValue("b").progressPage)
    }

    @Test fun keepLocalWithUnreachableStateRetriesThenFailsVisiblyWithoutOverwriting() = runBlocking<Unit> {
        val (s, r) = ready()
        r.open("a")
        s.otherDeviceSaves("a", 7)
        s.stateStatus = null
        r.saveProgress(5)
        assertEquals(SaveState.Conflict(5, null, false), r.save)
        val saves = s.saveCalls.size
        r.keepLocalAfterConflict()
        assertEquals(SaveState.Conflict(5, null, false, refreshFailed = true), r.save, "仍失败要有可见提示，不静默")
        assertEquals(saves, s.saveCalls.size); assertEquals(7, s.docs.getValue("a").progressPage)
        s.stateStatus = 200
        r.keepLocalAfterConflict()
        assertEquals(SaveState.Saved(5), r.save); assertEquals(5, s.docs.getValue("a").progressPage)
    }
}
