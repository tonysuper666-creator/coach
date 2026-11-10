package com.coach.desktop.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coach.client.ReaderHttp
import com.coach.client.ReaderReply
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * 桌面窗口到现有 ReaderHttp 契约的适配层（catalog / page / image / study / state / saveProgress）。
 *
 * 适配边界：
 *  - ReaderHttp 的构造函数是 internal，桌面只能从 CourseHttp.reader 拿到实例；这里用 [ReaderSource] 注入，
 *    真实接线 = ReaderHttpSource(courseHttp.reader)，测试 = 自造服务响应。适配器本身未单测（需要 MockEngine，build 配置不在本卡）。
 *  - 服务端 pdfPage 从 1 起；桌面 ReaderState 页序从 0 起，只在 [PdfPages] 里转换，不把屏幕页码当书页码（printedPage 单独显示）。
 *  - 缺页数/缺 id/缺版本的目录项丢弃；页响应的 documentId/pdfPage/version 与请求不符一律失败，不猜。
 *  - 未做（待做）：boxes 高亮、批注 saveNote、map 章节树、exportMarkdown；图片只加载与显示，不做缩放对齐。
 */

interface ReaderSource {
    suspend fun catalog(): ReaderReply
    suspend fun page(document: String, pdfPage: Int): ReaderReply
    suspend fun image(document: String, pdfPage: Int, version: String?): ByteArray?
    suspend fun study(document: String, pdfPage: Int): ReaderReply
    suspend fun boxes(document: String, pdfPage: Int, version: String): ReaderReply
    suspend fun saveNote(document: String, body: JsonObject): ReaderReply
    suspend fun state(document: String): ReaderReply
    suspend fun saveProgress(document: String, version: String, pdfPage: Int, baseRevision: Long, mutationId: String): ReaderReply
}

class ReaderHttpSource(private val http: ReaderHttp) : ReaderSource {
    override suspend fun catalog() = http.catalog()
    override suspend fun page(document: String, pdfPage: Int) = http.page(document, pdfPage)
    override suspend fun image(document: String, pdfPage: Int, version: String?) = http.image(document, pdfPage, version)
    override suspend fun study(document: String, pdfPage: Int) = http.study(document, pdfPage)
    override suspend fun boxes(document: String, pdfPage: Int, version: String) = http.boxes(document, pdfPage, version)
    override suspend fun saveNote(document: String, body: JsonObject) = http.saveNote(document, body)
    override suspend fun state(document: String) = http.state(document)
    override suspend fun saveProgress(document: String, version: String, pdfPage: Int, baseRevision: Long, mutationId: String) =
        http.saveProgress(document, version, pdfPage, baseRevision, mutationId)
}

object PdfPages {
    fun toIndex(pdfPage: Int) = pdfPage - 1
    fun toPdf(index: Int) = index + 1
}

sealed interface ReaderFailure {
    val text: String
    data object Unauthorized : ReaderFailure { override val text = "未登录或配对已失效" }
    data object Offline : ReaderFailure { override val text = "连不上服务" }
    data class MissingPage(val pdfPage: Int) : ReaderFailure { override val text = "这一页没找到" }
    data object VersionChanged : ReaderFailure { override val text = "资料版本变了，请重新定位" }
    data class BadResponse(val what: String) : ReaderFailure { override val text = "服务返回的内容不完整：$what" }
    data class Server(val status: Int) : ReaderFailure { override val text = "服务出错（$status）" }
}

private fun failureOf(r: ReaderReply, pdfPage: Int? = null): ReaderFailure = when (r.status) {
    null -> ReaderFailure.Offline
    401 -> ReaderFailure.Unauthorized
    404 -> ReaderFailure.MissingPage(pdfPage ?: 0)
    409 -> ReaderFailure.VersionChanged
    else -> ReaderFailure.Server(r.status ?: 0)
}

data class ReaderDocInfo(val id: String, val title: String, val version: String, val pages: Int)
class ReaderPageView(val document: String, val pdfPage: Int, val printedPage: Int?, val text: String, val textSha256: String, val image: ByteArray?)
data class StudyQuestion(val id: String, val stem: String, val status: String, val anchor: String)
data class StudyView(val questions: List<StudyQuestion>, val unlocated: Int, val available: Boolean)
data class ServerProgress(val pdfPage: Int, val version: String, val revision: Long, val stale: Boolean)

sealed interface PageState {
    data object Idle : PageState
    data class Loading(val pdfPage: Int) : PageState
    data class Ready(val view: ReaderPageView) : PageState
    data class Failed(val failure: ReaderFailure) : PageState
}

sealed interface StudyState {
    data object Idle : StudyState
    data object Loading : StudyState
    data class Ready(val view: StudyView) : StudyState
    data class Failed(val failure: ReaderFailure) : StudyState
}

sealed interface SaveState {
    data object Clean : SaveState
    data class Saved(val pdfPage: Int) : SaveState
    /** 传输失败：草稿与同一 mutationId 保留，可原样重试。 */
    data class Offline(val pdfPage: Int) : SaveState
    data class Failed(val failure: ReaderFailure) : SaveState
    /** 409：本机草稿页保留，不覆盖；remote 为另一台设备的进度（可能为空），versionChanged 表示资料版本也变了。 */
    data class Conflict(val localPage: Int, val remote: ServerProgress?, val versionChanged: Boolean, val refreshFailed: Boolean = false) : SaveState
}

data class WordBox(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val text: String)
data class PageBoxes(val width: Double, val height: Double, val words: List<WordBox>)

sealed interface BoxesState {
    data object Idle : BoxesState
    data object Loading : BoxesState
    data class Ready(val boxes: PageBoxes) : BoxesState
    data class Failed(val failure: ReaderFailure) : BoxesState
}

sealed interface Located {
    data class Found(val rects: List<WordBox>) : Located
    data object NoTextLayer : Located
    data object NotFound : Located
    data object Ambiguous : Located
}

/** 与 shared/ui 的 locateQuote 同一规则：引文按空白切词，必须在词框里恰好出现一次连续序列，不做模糊猜测。 */
object QuoteLocator {
    fun locate(boxes: PageBoxes, quote: String): Located {
        if (boxes.words.isEmpty()) return Located.NoTextLayer
        val tokens = quote.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > boxes.words.size) return Located.NotFound
        val starts = (0..boxes.words.size - tokens.size).filter { i -> tokens.indices.all { boxes.words[i + it].text == tokens[it] } }
        if (starts.isEmpty()) return Located.NotFound
        if (starts.size > 1) return Located.Ambiguous
        val run = boxes.words.subList(starts.single(), starts.single() + tokens.size)
        val lines = mutableListOf<WordBox>()
        for (w in run) {
            val last = lines.lastOrNull()
            val overlap = last?.let { minOf(it.y1, w.y1) - maxOf(it.y0, w.y0) } ?: 0.0
            if (last != null && overlap > 0.5 * minOf(last.y1 - last.y0, w.y1 - w.y0))
                lines[lines.lastIndex] = WordBox(minOf(last.x0, w.x0), minOf(last.y0, w.y0), maxOf(last.x1, w.x1), maxOf(last.y1, w.y1), "")
            else lines += w.copy(text = "")
        }
        return Located.Found(lines)
    }
}

/** 服务端 state.notes 里的一条批注（UTF-16 偏移）。 */
data class NoteInfo(
    val id: String, val version: String, val pdfPage: Int, val start: Int, val end: Int, val quote: String, val note: String,
    val textSha256: String, val revision: Long, val stale: Boolean,
)

/** 一条正在编辑/保存的批注草稿；baseRevision 新建为 0，已有批注为其当前 revision。 */
data class NoteDraft(
    val id: String, val doc: String, val version: String, val pdfPage: Int, val textSha256: String,
    val start: Int, val end: Int, val quote: String, val note: String, val baseRevision: Long,
)

sealed interface NoteSaveState {
    data object Idle : NoteSaveState
    data class Saved(val id: String, val revision: Long) : NoteSaveState
    /** 传输失败：草稿与同一 mutationId 保留，可原样重试。 */
    data class Offline(val id: String) : NoteSaveState
    /** 409：草稿保留在本机，不自动覆盖。 */
    /** server 为 409 后重新读到的服务端版本（读不到为 null）；refreshFailed 表示读取失败；versionChanged 表示资料版本也变了。 */
    data class Conflict(val id: String, val server: NoteInfo? = null, val refreshFailed: Boolean = false, val versionChanged: Boolean = false) : NoteSaveState
    data class Failed(val failure: ReaderFailure) : NoteSaveState
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

/**
 * 一个窗口对应一个 ReaderRemote。切书、快速翻页、慢响应：每次导航递增 [nav]，await 回来后只在仍是当前书当前导航时才落地，
 * 迟到的旧响应被丢弃，不会把旧书页或旧题串到新书。
 */
class ReaderRemote(private val source: ReaderSource, private val newMutationId: () -> String = { "m" + java.util.UUID.randomUUID().toString().replace("-", "").take(24) }) {
    var documents by mutableStateOf<List<ReaderDocInfo>>(emptyList()); private set
    var current by mutableStateOf<ReaderDocInfo?>(null); private set
    var page by mutableStateOf<PageState>(PageState.Idle); private set
    var study by mutableStateOf<StudyState>(StudyState.Idle); private set
    var save by mutableStateOf<SaveState>(SaveState.Clean); private set
    var failure by mutableStateOf<ReaderFailure?>(null); private set
    var boxes by mutableStateOf<BoxesState>(BoxesState.Idle); private set
    var notes by mutableStateOf<List<NoteInfo>>(emptyList()); private set
    var noteSave by mutableStateOf<NoteSaveState>(NoteSaveState.Idle); private set
    val studyQuestions: List<StudyQuestion> get() = (study as? StudyState.Ready)?.view?.questions.orEmpty()
    private val noteLock = Mutex()
    private class PendingNote(val key: String, val mutationId: String)
    private var pendingNote: PendingNote? = null

    private var nav = 0
    private var revision = 0L
    private val saveLock = Mutex()
    private class Pending(val doc: String, val pdfPage: Int, val mutationId: String)
    private var pending: Pending? = null

    suspend fun loadCatalog() {
        val r = source.catalog()
        val docs = (r.body?.get("documents") as? JsonArray)
        when {
            !r.ok -> { failure = failureOf(r); documents = emptyList() }
            docs == null -> { failure = ReaderFailure.BadResponse("目录"); documents = emptyList() }
            else -> {
                failure = null
                documents = docs.mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val id = o.str("id").orEmpty(); val title = o.str("title").orEmpty(); val version = o.str("version").orEmpty()
                    val pages = o.int("pages") ?: return@mapNotNull null
                    if (id.isBlank() || title.isBlank() || version.isBlank() || pages < 1) null else ReaderDocInfo(id, title, version, pages)
                }
            }
        }
    }

    /** 打开一本书并读出服务端进度；返回要显示的 pdfPage（进度不可用或已过期则第 1 页），失败返回 null 并置 [failure]。 */
    suspend fun open(documentId: String): Int? {
        val my = ++nav
        current = null; page = PageState.Idle; study = StudyState.Idle; save = SaveState.Clean; pending = null; revision = 0L
        boxes = BoxesState.Idle; notes = emptyList(); noteSave = NoteSaveState.Idle; pendingNote = null
        val doc = documents.firstOrNull { it.id == documentId }
        if (doc == null) { failure = ReaderFailure.BadResponse("没有这本书"); return null }
        val r = source.state(doc.id)
        if (my != nav) return null
        if (!r.ok) { failure = failureOf(r); return null }
        val body = r.body
        val remoteDoc = body?.get("document") as? JsonObject
        if (remoteDoc == null || remoteDoc.str("id") != doc.id) { failure = ReaderFailure.BadResponse("进度"); return null }
        if (remoteDoc.str("version") != doc.version) { failure = ReaderFailure.VersionChanged; return null }
        val progress = parseProgress(body["progress"])
        revision = progress?.revision ?: 0L
        notes = parseNotes(body["notes"])
        failure = null
        current = doc
        return progress?.takeIf { !it.stale && it.version == doc.version && it.pdfPage in 1..doc.pages }?.pdfPage ?: 1
    }

    private fun parseProgress(e: kotlinx.serialization.json.JsonElement?): ServerProgress? {
        val o = e as? JsonObject ?: return null
        val page = o.int("pdfPage") ?: return null
        val version = o.str("version") ?: return null
        val rev = o.long("revision") ?: return null
        val stale = (o["stale"] as? JsonPrimitive)?.booleanOrNull ?: true
        return ServerProgress(page, version, rev, stale)
    }

    /** 显示一页。未知页码（不在 1..pages）不发请求、不猜；返回时若已切书/已有新导航则丢弃结果。 */
    suspend fun show(pdfPage: Int) {
        val doc = current ?: return
        val my = ++nav
        if (pdfPage !in 1..doc.pages) { page = PageState.Failed(ReaderFailure.MissingPage(pdfPage)); study = StudyState.Idle; return }
        page = PageState.Loading(pdfPage); study = StudyState.Loading; boxes = BoxesState.Loading
        coroutineScope {
            val studyJob = async { source.study(doc.id, pdfPage) }
            val imageJob = async { source.image(doc.id, pdfPage, doc.version) }
            val boxesJob = async { source.boxes(doc.id, pdfPage, doc.version) }
            val reply = source.page(doc.id, pdfPage)
            val studyReply = studyJob.await()
            val image = imageJob.await()
            val boxesReply = boxesJob.await()
            if (my != nav || current?.id != doc.id) return@coroutineScope
            page = when {
                !reply.ok -> PageState.Failed(failureOf(reply, pdfPage))
                else -> {
                    val b = reply.body
                    when {
                        b == null || b.str("text") == null -> PageState.Failed(ReaderFailure.BadResponse("页文字"))
                        b.str("documentId") != doc.id || b.int("pdfPage") != pdfPage -> PageState.Failed(ReaderFailure.BadResponse("页与请求不符"))
                        b.str("version") != doc.version -> PageState.Failed(ReaderFailure.VersionChanged)
                        else -> PageState.Ready(ReaderPageView(doc.id, pdfPage, b.int("printedPage"), b.str("text")!!, b.str("textSha256").orEmpty(), image))
                    }
                }
            }
            study = parseStudy(studyReply, doc.id, pdfPage)
            boxes = parseBoxes(boxesReply, doc.version, pdfPage)
        }
    }

    private fun parseNotes(e: kotlinx.serialization.json.JsonElement?): List<NoteInfo> = (e as? JsonArray).orEmpty().mapNotNull { v ->
        val o = v as? JsonObject ?: return@mapNotNull null
        NoteInfo(
            o.str("id") ?: return@mapNotNull null, o.str("version") ?: return@mapNotNull null, o.int("pdfPage") ?: return@mapNotNull null,
            o.int("start") ?: return@mapNotNull null, o.int("end") ?: return@mapNotNull null, o.str("quote") ?: return@mapNotNull null,
            o.str("note").orEmpty(), o.str("textSha256") ?: return@mapNotNull null, o.long("revision") ?: return@mapNotNull null,
            (o["stale"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }

    /** 只接受版本、页码都对得上且坐标在页内的词框；其余一律当作不可用，不画高亮。 */
    private fun parseBoxes(r: ReaderReply, version: String, pdfPage: Int): BoxesState {
        if (!r.ok) return BoxesState.Failed(failureOf(r, pdfPage))
        val bad = BoxesState.Failed(ReaderFailure.BadResponse("词框"))
        val b = r.body ?: return bad
        fun d(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull()
        if (b.str("version") != version || b.int("pdfPage") != pdfPage) return BoxesState.Failed(ReaderFailure.BadResponse("词框与页不符"))
        val w = d(b, "width")?.takeIf { it > 0 } ?: return bad
        val h = d(b, "height")?.takeIf { it > 0 } ?: return bad
        val words = (b["words"] as? JsonArray ?: return bad).map { e ->
            val o = e as? JsonObject ?: return bad
            val box = WordBox(d(o, "x0") ?: return bad, d(o, "y0") ?: return bad, d(o, "x1") ?: return bad, d(o, "y1") ?: return bad, o.str("text") ?: return bad)
            if (box.x0 < 0 || box.y0 < 0 || box.x1 > w + 0.5 || box.y1 > h + 0.5 || box.x1 <= box.x0 || box.y1 <= box.y0) return bad
            box
        }
        return BoxesState.Ready(PageBoxes(w, h, words))
    }

    /** 保存一条批注。重试同内容沿用同一 mutationId；409 保留草稿不自动覆盖；切书后的回执丢弃。 */
    suspend fun saveNote(d: NoteDraft) = noteLock.withLock {
        val doc = current ?: return@withLock
        if (d.doc != doc.id) return@withLock
        if (d.version != doc.version) { noteSave = NoteSaveState.Failed(ReaderFailure.VersionChanged); return@withLock }
        if (d.pdfPage !in 1..doc.pages || d.end <= d.start || d.end - d.start > 4000 || d.note.length > 8000) {
            noteSave = NoteSaveState.Failed(ReaderFailure.BadResponse("批注内容")); return@withLock
        }
        val key = "${d.id}|${d.note}|${d.baseRevision}|${d.start}|${d.end}"
        val mutation = pendingNote?.takeIf { it.key == key }?.mutationId ?: newMutationId()
        pendingNote = PendingNote(key, mutation)
        val body = buildJsonObject {
            put("id", d.id); put("version", d.version); put("pdfPage", d.pdfPage); put("textSha256", d.textSha256)
            put("start", d.start); put("end", d.end); put("quote", d.quote); put("note", d.note); put("deleted", false)
            put("baseRevision", d.baseRevision); put("mutationId", mutation)
        }
        val r = source.saveNote(doc.id, body)
        if (current?.id != doc.id) return@withLock
        when {
            r.ok -> {
                val rev = r.body?.long("revision")
                if (rev == null) noteSave = NoteSaveState.Failed(ReaderFailure.BadResponse("保存回执"))
                else {
                    pendingNote = null
                    notes = notes.filterNot { it.id == d.id } + NoteInfo(d.id, d.version, d.pdfPage, d.start, d.end, d.quote, d.note, d.textSha256, rev, false)
                    noteSave = NoteSaveState.Saved(d.id, rev)
                }
            }
            r.status == null -> noteSave = NoteSaveState.Offline(d.id)
            r.status == 409 -> { pendingNote = null; noteSave = NoteSaveState.Conflict(d.id); refreshNoteConflict(doc, d.id) }
            else -> { pendingNote = null; noteSave = NoteSaveState.Failed(failureOf(r, d.pdfPage)) }
        }
    }

    private suspend fun refreshNoteConflict(doc: ReaderDocInfo, id: String) {
        val r = source.state(doc.id)
        if (current?.id != doc.id) return
        val cur = noteSave as? NoteSaveState.Conflict ?: return
        val body = r.body
        if (!r.ok || body == null) { noteSave = cur.copy(refreshFailed = true); return }
        val versionChanged = (body["document"] as? JsonObject)?.str("version") != doc.version
        noteSave = cur.copy(server = parseNotes(body["notes"]).firstOrNull { it.id == id }, refreshFailed = false, versionChanged = versionChanged)
    }

    /** 用户点“重新获取服务端版本”。 */
    suspend fun retryNoteConflictRefresh() {
        val c = noteSave as? NoteSaveState.Conflict ?: return
        current?.let { refreshNoteConflict(it, c.id) }
    }

    /** 用户选择“采用服务端版本”：本地批注列表换成服务端那条，返回它；没有可信的服务端版本则返回 null。 */
    fun adoptServerNote(): NoteInfo? {
        val c = noteSave as? NoteSaveState.Conflict ?: return null
        val server = c.server?.takeIf { !c.versionChanged && it.version == current?.version } ?: return null
        notes = notes.filterNot { it.id == server.id } + server
        noteSave = NoteSaveState.Idle
        return server
    }

    /**
     * 用户明确选择“用我的版本覆盖”：只有刚读到的、可信的最新 revision 才作为 baseRevision；读不到、版本变了、
     * 服务端已看不到这条批注（已删除时 state 不返回）时一律不提交。只提交这一次，409 再来仍回到冲突状态，不循环。
     */
    suspend fun overwriteWithMine(d: NoteDraft): Boolean {
        val c = noteSave as? NoteSaveState.Conflict ?: return false
        val server = c.server?.takeIf { !c.versionChanged && it.id == d.id && it.version == current?.version } ?: return false
        saveNote(d.copy(baseRevision = server.revision))
        return noteSave is NoteSaveState.Saved
    }

    /** 批注定位是否仍有效：同版本、同页、文字哈希一致且引文与偏移处文字相同；否则位置待重新确认。 */
    fun noteValid(n: NoteInfo, view: ReaderPageView): Boolean =
        !n.stale && n.version == current?.version && n.pdfPage == view.pdfPage && n.textSha256 == view.textSha256 &&
            n.start in 0 until n.end && n.end <= view.text.length && view.text.substring(n.start, n.end) == n.quote

    /** 冲突未解决时翻页不再静默重复保存：只把草稿页更新为当前页。 */
    suspend fun saveProgressAuto(pdfPage: Int) {
        val c = save as? SaveState.Conflict
        if (c != null) { save = c.copy(localPage = pdfPage); return }
        saveProgress(pdfPage)
    }

    private fun parseStudy(r: ReaderReply, doc: String, pdfPage: Int): StudyState {
        if (!r.ok) return StudyState.Failed(failureOf(r, pdfPage))
        val b = r.body ?: return StudyState.Failed(ReaderFailure.BadResponse("题目"))
        if (b.str("documentId") != doc || b.int("pdfPage") != pdfPage) return StudyState.Failed(ReaderFailure.BadResponse("题目与页不符"))
        val qs = (b["questions"] as? JsonArray ?: return StudyState.Failed(ReaderFailure.BadResponse("题目"))).mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            StudyQuestion(id, o.str("stem").orEmpty(), o.str("status").orEmpty(), o.str("anchor").orEmpty())
        }
        return StudyState.Ready(StudyView(qs, b.int("unlocated") ?: 0, (b["available"] as? JsonPrimitive)?.booleanOrNull ?: true))
    }

    /** 保存当前页为进度。同页同书传输失败后重试沿用同一 mutationId；409 保留本机草稿，不盲目覆盖。 */
    suspend fun saveProgress(pdfPage: Int) = saveLock.withLock {
        val doc = current ?: return@withLock
        if (pdfPage !in 1..doc.pages) return@withLock
        val mutation = pending?.takeIf { it.doc == doc.id && it.pdfPage == pdfPage }?.mutationId ?: newMutationId()
        pending = Pending(doc.id, pdfPage, mutation)
        val r = source.saveProgress(doc.id, doc.version, pdfPage, revision, mutation)
        if (current?.id != doc.id) return@withLock
        when {
            r.ok -> {
                val rev = r.body?.long("revision")
                if (rev == null) save = SaveState.Failed(ReaderFailure.BadResponse("保存回执")) else { revision = rev; pending = null; save = SaveState.Saved(pdfPage) }
            }
            r.status == null -> save = SaveState.Offline(pdfPage)
            r.status == 409 -> { pending = null; save = SaveState.Conflict(pdfPage, null, false); refreshConflict(doc, pdfPage) }
            else -> { pending = null; save = SaveState.Failed(failureOf(r, pdfPage)) }
        }
    }

    private suspend fun refreshConflict(doc: ReaderDocInfo, localPage: Int) {
        val r = source.state(doc.id)
        if (current?.id != doc.id) return
        val body = r.body
        if (!r.ok || body == null) return // 草稿仍保留，远端未知
        val versionChanged = (body["document"] as? JsonObject)?.str("version") != doc.version
        save = SaveState.Conflict(localPage, parseProgress(body["progress"]), versionChanged)
    }

    /** 用户明确选择“保留本机位置”：以最新修订号重新保存；资料版本变了则不保存。 */
    suspend fun keepLocalAfterConflict() {
        val c = save as? SaveState.Conflict ?: return
        if (c.versionChanged) return
        var remote = c.remote
        if (remote == null) {
            // 409 后拉取远端进度失败过：先重试一次；仍失败就明确提示，不静默空转，也不盲目覆盖。
            current?.let { refreshConflict(it, c.localPage) }
            val again = save as? SaveState.Conflict ?: return
            remote = again.remote
            if (remote == null) { save = again.copy(refreshFailed = true); return }
        }
        revision = remote.revision
        saveProgress(c.localPage)
    }

    /** 用户明确选择“用另一台的位置”：返回要跳转的 pdfPage 并清除冲突；本机草稿随之放弃。 */
    fun useRemoteAfterConflict(): Int? {
        val c = save as? SaveState.Conflict ?: return null
        val r = c.remote?.takeIf { !c.versionChanged && it.pdfPage in 1..(current?.pages ?: 0) } ?: return null
        revision = r.revision; save = SaveState.Saved(r.pdfPage)
        return r.pdfPage
    }
}
