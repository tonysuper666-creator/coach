package com.coach.desktop.reader

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.input.key.*

/** 窗口会话：状态、题目选择与作答都在这里，窗口缩放或出处往返都不清空。 */
class ReaderSession(val fixture: ReaderFixture, initialWidthDp: Int, val remote: ReaderRemote? = null, val drafts: NoteDraftStore? = null) {
    var state by mutableStateOf(ReaderState(DesktopReaderLayouts.forWidth(initialWidthDp), pageCount = fixture.pages.size))
        private set
    var selectedQuestion by mutableIntStateOf(0)
        private set
    val answers: SnapshotStateMap<Int, Int> = mutableStateMapOf()
    var message by mutableStateOf<String?>(null)
        private set
    private var openedFromQuestions = false

    // ---- 远端批注：键盘流程 N（选句）→ 上下选行、回车确认 → 输入 → Ctrl+回车保存 / Esc 放弃 ----
    /** 选句阶段当前行；-1 表示不在选句。 */
    var noteLine by mutableIntStateOf(-1)
        private set
    var noteDraft by mutableStateOf<NoteDraft?>(null)
        private set
    var noteText by mutableStateOf("")
    var noteSaveTick by mutableIntStateOf(0)
        private set
    var highlightNoteId by mutableStateOf<String?>(null)
    val noting: Boolean get() = noteLine >= 0 || noteDraft != null

    private fun currentView(): ReaderPageView? = (remote?.page as? PageState.Ready)?.view

    /** 当前页文字里的非空行（UTF-16 偏移区间），键盘选段的最小单位。 */
    fun pageLines(): List<IntRange> {
        val text = currentView()?.text ?: return emptyList()
        val out = mutableListOf<IntRange>(); var start = 0
        for (line in text.split('\n')) {
            if (line.isNotBlank()) out += start until start + line.length
            start += line.length + 1
        }
        return out
    }

    fun beginNote(preferredOffset: Int = 0) {
        if (remote == null || currentView() == null) { message = "这一页还没加载好，不能写批注"; return }
        val lines = pageLines()
        if (lines.isEmpty()) { message = "这一页没有可选的文字"; return }
        val preferredLine = if (preferredOffset > 0) {
            lines.indexOfFirst { it.last >= preferredOffset }.takeIf { it >= 0 } ?: 0
        } else 0
        noteDraft = null; noteLine = preferredLine
    }

    /**
     * 直接针对阅读器选区开启批注编辑器，严禁回退到第 0 行。
     * 若传入偏移与页内原文存在可核对的精确子串或唯一精确匹配，直接定位并打开；否则给出明确提示并返回 false。
     */
    fun beginNoteForRange(start: Int, end: Int, expectedQuote: String? = null): Boolean {
        val r = remote
        val v = currentView()
        val doc = r?.current
        if (r == null || v == null || doc == null) {
            message = "这一页还没加载好，不能写批注"
            return false
        }
        val resolved = resolveNoteRange(v.text, start, end, expectedQuote)
        if (resolved == null) {
            message = "选中的原文位置无法确认，请重新选择"
            return false
        }
        val (adjStart, adjEnd) = resolved
        if (adjEnd - adjStart > 4_000) {
            message = "选中的原文超过 4000 字，请缩窄范围后再写批注"
            return false
        }
        openDraftForVerifiedRange(r, doc, v, adjStart, adjEnd)
        return true
    }

    private fun resolveNoteRange(pageText: String, start: Int, end: Int, expectedQuote: String?): Pair<Int, Int>? {
        if (start in 0 until end && end <= pageText.length) {
            val slice = pageText.substring(start, end)
            if (expectedQuote == null || slice == expectedQuote) return start to end
            val candidates = listOf(expectedQuote, expectedQuote.trim()).filter { it.isNotEmpty() }.distinct()
            for (needle in candidates) {
                val rel = slice.indexOf(needle)
                if (rel >= 0 && slice.indexOf(needle, rel + 1) < 0) {
                    return (start + rel) to (start + rel + needle.length)
                }
            }
        }
        if (!expectedQuote.isNullOrBlank()) {
            val candidates = listOf(expectedQuote, expectedQuote.trim()).filter { it.isNotEmpty() }.distinct()
            for (needle in candidates) {
                val first = pageText.indexOf(needle)
                if (first >= 0 && pageText.indexOf(needle, first + 1) < 0) {
                    return first to (first + needle.length)
                }
            }
        }
        return null
    }

    /** Mouse/touch hosts select the same page line as the keyboard picker. */
    fun chooseNoteLine(index: Int) {
        if (noteLine < 0 || index !in pageLines().indices) return
        noteLine = index
        confirmLine()
    }

    /** 服务端已确认保存的文字；新建且从未保存成功为 null。 */
    private var noteBaseline by mutableStateOf<String?>(null)
    private var noteAttempt by mutableStateOf<String?>(null)
    var noteSaving by mutableStateOf(false)
        private set
    /** 采用服务端版本时被换下的本地草稿，可以找回。 */
    var noteStash by mutableStateOf<String?>(null)
        private set
    /** 本次组合里批注保存状态文字（无草稿为 null），供测试读取。 */
    var uiNoteStatus: String? = null

    fun noteStatus(): NoteStatus? {
        val d = noteDraft ?: return null; val r = remote ?: return null
        return NoteStatusRules.of(d.id, noteText, noteBaseline, noteAttempt, noteSaving, r.noteSave)
    }

    // ---- 本机草稿：失败/关闭/重开后不丢；远端 ack 之后才清；绝不自动补发 ----
    /** 草稿存储变化的计数，让界面里的“本页有未保存的草稿”列表跟着刷新。 */
    var draftTick by mutableIntStateOf(0)
        private set

    private fun identityOf(d: NoteDraft): DraftIdentity? {
        val st = drafts ?: return null
        if (st.scope.isBlank()) return null
        return DraftIdentity(st.scope, d.doc, d.version, d.pdfPage, d.start, d.end, d.textSha256)
    }

    private fun clearStored(d: NoteDraft) { identityOf(d)?.let { drafts?.remove(it); draftTick++ } }

    /** 本机草稿是否真的在用：有存储、有可信身份、且最近一次写盘没失败（界面承诺只跟这个走）。 */
    var persistFailed by mutableStateOf(false)
        private set
    fun draftsKept(): Boolean = drafts?.scope?.isNotBlank() == true && !persistFailed

    /**
     * 把还没保存成功的文字存到本机：和已保存文字一致或没有内容则清掉这一份；既有非空批注被清空是一次真实修改（cleared），要留着；
     * 采用服务端版本后被换下的本地文字也要留着。写盘失败只记状态，不抛异常、不丢内存里的文字。
     */
    private fun persistDraft(d: NoteDraft, text: String) {
        val st = drafts ?: return; val id = identityOf(d) ?: return
        val atBaseline = text.isEmpty() || text == noteBaseline
        val fromStash = noteStash != null && atBaseline                  // 采用服务端版本后被换下的本地文字（可能是“清空”）
        val clearing = !fromStash && text.isEmpty() && !noteBaseline.isNullOrEmpty()
        val effective: String? = when {
            fromStash -> noteStash
            clearing -> ""
            atBaseline -> null
            else -> text
        }
        if (effective == null) { clearStored(d); persistFailed = false; return } // 没有需要落盘的未保存文字：不警示
        val base = if (fromStash) stashBase ?: d.baseRevision else d.baseRevision
        persistFailed = !st.put(StoredDraft(id, d.id, d.quote, effective, base, cleared = effective.isEmpty()))
        draftTick++
    }

    /** 编辑代次：每次打开/关闭编辑区都 +1；在途请求的回执只能收尾它自己那一代的编辑器。 */
    private var editGen = 0
    private var stashBase: Long? = null

    /** 采用本机草稿：身份/修订号原样保留（不接管同一行别的批注，不用最新远端修订号覆盖）；缺修订号且服务端已有这条就拒绝猜测。 */
    private fun takeStored(sd: StoredDraft, doc: ReaderDocInfo, v: ReaderPageView): Boolean {
        val r = remote ?: return false
        val serverNote = r.notes.firstOrNull { it.id == sd.noteId }
        val base = sd.baseRevision ?: if (serverNote != null) { message = "这份本机草稿缺少原修订号，不能安全带回"; return false } else 0L
        noteDraft = NoteDraft(sd.noteId, doc.id, doc.version, v.pdfPage, v.textSha256, sd.identity.start, sd.identity.end, sd.quote, sd.note, base)
        noteText = sd.note; noteLine = -1; noteBaseline = serverNote?.note; noteAttempt = null; noteStash = null; stashBase = null; editGen++
        persistFailed = false // 刚从本机读出来的，就是已落盘的
        return true
    }

    /** 保存被远端真实 ack 之后的统一收尾：只清匹配的这一份；ack 等待期间新输入的文字要重新留下。 */
    private fun finishAck(d: NoteDraft, toSave: NoteDraft, revision: Long) {
        noteStash = null
        noteBaseline = toSave.note
        noteDraft = toSave.copy(baseRevision = revision)
        highlightNoteId = d.id
        clearStored(d)
        if (noteText != noteBaseline) persistDraft(noteDraft!!, noteText) else persistFailed = false // 当前内容已被真实 ack，没有未落盘的文字
    }

    /** 已被关闭/换掉的编辑器收到迟到的 ack：服务端确实收下了这段文字，只清掉与之完全一致的本机草稿，不碰当前编辑器。 */
    private fun lateAck(d: NoteDraft, acknowledgedText: String) {
        val sid = identityOf(d) ?: return
        val cur = drafts?.get(sid) ?: return
        if (cur.noteId == d.id && cur.note == acknowledgedText) clearStored(d)
    }

    /** 当前书/版本/页上本机留着的、还没保存成功的草稿（身份必须全部匹配）。 */
    fun localDrafts(): List<StoredDraft> {
        @Suppress("UNUSED_EXPRESSION") draftTick
        val st = drafts ?: return emptyList()
        val doc = remote?.current ?: return emptyList(); val v = currentView() ?: return emptyList()
        return st.listFor(doc.id, doc.version, v.pdfPage)
    }

    /** 用户选择“继续编辑”：只在文档/版本/页/锚点文字都对得上时才带回，不然拒绝。 */
    fun resumeLocalDraft(sd: StoredDraft): Boolean {
        val r = remote ?: return false; val v = currentView() ?: return false; val doc = r.current ?: return false; val st = drafts ?: return false
        val i = sd.identity
        if (i.scope.isBlank() || i.scope != st.scope || i.doc != doc.id || i.version != doc.version || i.pdfPage != v.pdfPage || i.textSha256 != v.textSha256 ||
            i.start !in 0 until i.end || i.end > v.text.length || v.text.substring(i.start, i.end) != sd.quote) return false
        return takeStored(sd, doc, v)
    }

    /** 用户在列表里明确丢弃某一份本机草稿（例如缺修订号、不能安全带回的旧稿）。 */
    fun discardLocalDraft(sd: StoredDraft) { drafts?.remove(sd.identity); draftTick++ }

    /** 明确“放弃草稿”：连本机存的这一份一起丢。 */
    fun discardDraft() { noteDraft?.let { clearStored(it) }; clearEditor() }

    /** 输入框里的文字变化：随手存到本机，应用被关掉也不丢。 */
    fun editNote(t: String) { noteText = t; noteDraft?.let { persistDraft(it, t) } }

    /** 普通关闭编辑区/关书：未保存的文字留在本机，不等于放弃。 */
    fun cancelNote() { noteDraft?.let { persistDraft(it, noteText) }; clearEditor() }

    private fun clearEditor() { noteLine = -1; noteDraft = null; noteText = ""; noteBaseline = null; noteAttempt = null; noteStash = null; stashBase = null; editGen++; persistFailed = false }

    private fun confirmLine() {
        val r = remote ?: return; val v = currentView() ?: return
        val range = pageLines().getOrNull(noteLine) ?: return
        val doc = r.current ?: return
        openDraftForVerifiedRange(r, doc, v, range.first, range.last + 1)
    }

    private fun openDraftForVerifiedRange(r: ReaderRemote, doc: ReaderDocInfo, v: ReaderPageView, start: Int, end: Int) {
        val existing = r.notes.firstOrNull { it.pdfPage == v.pdfPage && it.start == start && it.end == end && r.noteValid(it, v) }
        val quote = v.text.substring(start, end)
        val base = NoteDraft(existing?.id ?: java.util.UUID.randomUUID().toString(), doc.id, doc.version, v.pdfPage, v.textSha256,
            start, end, quote, existing?.note.orEmpty(), existing?.revision ?: 0L)
        val local = identityOf(base)?.let { drafts?.get(it) }?.takeIf { it.quote == quote } // 同一锚点上本机留着的草稿
        if (local != null) { if (!takeStored(local, doc, v)) noteLine = -1; return } // 被拒绝的本机稿：不开基线编辑器，稿原样保留
        editGen++; persistFailed = false // 新打开的编辑器还没有未落盘的新文字
        noteDraft = base
        noteText = existing?.note.orEmpty(); noteLine = -1
        noteBaseline = existing?.note; noteAttempt = null; noteStash = null
    }

    /** Ctrl+回车 / 按钮：交给窗口的协程去保存；离线后再按一次即原样重试。 */
    fun requestNoteSave() {
        val d = noteDraft ?: return
        if (noteSaving) return // 正在保存：不叠加第二次，等回执后再按
        if ((remote?.noteSave as? NoteSaveState.Conflict)?.id == d.id) { message = "这条批注有冲突，先选一种处理方式"; return }
        noteSaveTick++
    }

    suspend fun commitNote() {
        val r = remote ?: return; val d = noteDraft ?: return
        val toSave = d.copy(note = noteText)
        val gen = editGen
        noteSaving = true; noteAttempt = toSave.note
        try { r.saveNote(toSave) } finally { noteSaving = false }
        val st = r.noteSave
        val mine = gen == editGen && noteDraft?.id == d.id           // 这份回执是否属于现在还开着的这个编辑器
        if (st is NoteSaveState.Saved && st.id == d.id) {
            if (mine) finishAck(d, toSave, st.revision) else lateAck(d, toSave.note) // 只把“实际发出去的文字”记为已保存；真实 ack 之后才清除匹配的草稿
        } else if (mine) {
            persistDraft(d, noteText) // 失败/断网/冲突：草稿留在本机
        }
    }

    /** 页加载失败后的“重试”：重新加载同一页（含词框/题目），成功后照常保存进度。 */
    suspend fun retryPage() {
        val r = remote ?: return
        if (r.current == null) return
        val pdf = PdfPages.toPdf(state.position.page)
        r.show(pdf)
        if (r.page is PageState.Ready) r.saveProgressAuto(pdf)
    }

    // ---- 409 之后的显式处理：都只在用户点击时发生，不自动重试 ----
    /** 采用服务端版本：本地草稿放进“可找回”，编辑区换成服务端文字，基线换成服务端 revision。 */
    fun adoptServerVersion() {
        val r = remote ?: return; val d = noteDraft ?: return
        val server = r.adoptServerNote() ?: return
        noteStash = noteText
        stashBase = d.baseRevision // 被换下的本地文字依据的是原修订号
        persistDraft(d, noteText) // 被换下的本地文字先落盘
        noteDraft = d.copy(note = server.note, baseRevision = server.revision)
        noteText = server.note; noteBaseline = server.note; noteAttempt = null
    }

    fun restoreStashedDraft() { noteStash?.let { noteText = it; noteStash = null } }

    /** 用我的版本覆盖：只用刚读到的服务端 revision，且只提交这一次。 */
    suspend fun overwriteWithMine() {
        val r = remote ?: return; val d = noteDraft ?: return
        val toSave = d.copy(note = noteText)
        val gen = editGen
        noteSaving = true; noteAttempt = toSave.note
        try {
            if (r.overwriteWithMine(toSave)) {
                val st = r.noteSave as NoteSaveState.Saved
                if (gen == editGen && noteDraft?.id == d.id) finishAck(d, toSave, st.revision) else lateAck(d, toSave.note)
            }
        } finally { noteSaving = false }
    }

    suspend fun retryConflictRefresh() { remote?.retryNoteConflictRefresh() }

    /** 另存为新批注：新 id、baseRevision 0；原批注与服务端版本都不动。 */
    fun saveAsNewNote() {
        val d = noteDraft ?: return
        noteDraft = d.copy(id = java.util.UUID.randomUUID().toString(), baseRevision = 0L)
        noteBaseline = null; noteAttempt = null; noteStash = null
        noteSaveTick++
    }

    /** 题目的“这句在哪考过”：study 题目自带登记原句；没有就明说，不猜。 */
    fun openRemoteProvenance() {
        val r = remote ?: return
        val q = r.studyQuestions.getOrNull(selectedQuestion) ?: return
        if (q.anchor.isBlank()) { message = "这一题没有登记原句"; return }
        message = null
        openedFromQuestions = state.focus == ReaderPane.Related
        state = state.openProvenance(q.id, state.position.page)
    }

    /** 导航版本计数器：每次打开文档、翻页或直达时递增，保证即使同书同页重开也能触发加载 Effect。 */
    var navGeneration by mutableIntStateOf(0)
        private set

    /** 宿主提供的返回回调与按钮文案（如返回书桌/返回学习）。 */
    var onBack by mutableStateOf<(() -> Unit)?>(null)
    var backLabel by mutableStateOf<String?>(null)

    /** 本次组合实际渲染的栏，供测试读取。 */
    var renderedPanes: List<ReaderPane> = emptyList()

    /** 本次组合里原句高亮的状态文字（无引文时为 null），供测试读取。 */
    var uiHighlight: String? = null

    /** 本次组合里正文的滚动位置（远端正文），供测试读取：滚轮翻页后应停在起始行。 */
    val uiBodyScroll: Int get() = bodyScroll?.value ?: -1
    val uiBodyScrollMax: Int get() = bodyScroll?.maxValue ?: -1

    /** 远端正文的滚动状态（由 RemoteBody 登记）；滚轮/方向键据此在页内滚动。 */
    var bodyScroll by mutableStateOf<androidx.compose.foundation.ScrollState?>(null)
    /** 往回翻到上一页时，新页应停在末尾（而不是顶部）。 */
    var scrollToEndOnArrive by mutableStateOf(false)
    /** 每一行正文在窗口里的位置，供测试判断首尾行是否真的可见。 */
    val uiLineBounds = HashMap<Int, androidx.compose.ui.geometry.Rect>()

    fun bodyCanScroll(deltaY: Float): Boolean {
        val sc = bodyScroll ?: return false
        if (remote == null || deltaY == 0f) return false
        return if (noting) sc.maxValue > 0 else if (deltaY > 0) sc.value < sc.maxValue else sc.value > 0
    }

    /** 方向键在页内滚动一小段（不翻页，也不抢焦点）。 */
    fun scrollBody(down: Boolean) { bodyScroll?.dispatchRawDelta(if (down) 80f else -80f) }

    /** 当前窗口实际显示的栏：焦点在被收拢的栏时，该栏单独占满窗口，正文状态原样保留。 */
    fun shownPanes(): List<ReaderPane> = if (state.focus in state.layout.visible) state.layout.visible else listOf(state.focus)

    fun resize(widthDp: Int) {
        val l = DesktopReaderLayouts.forWidth(widthDp)
        if (l != state.layout) state = state.copy(layout = l)
    }

    fun focusPane(pane: ReaderPane) {
        if (state.focus != pane) state = state.copy(focus = pane)
    }

    fun answer(option: Int) {
        val q = fixture.questions.getOrNull(selectedQuestion) ?: return
        if (option in q.options.indices) answers[selectedQuestion] = option
    }

    fun selectRemoteQuestion(index: Int) {
        if (index in (remote?.studyQuestions?.indices ?: IntRange.EMPTY)) selectedQuestion = index
    }

    fun openProvenanceOfSelected() {
        val q = fixture.questions.getOrNull(selectedQuestion) ?: return
        val page = fixture.pageOfAnchor(q.anchorId)
        if (page < 0) { message = "这一页没找到出处"; return }
        message = null
        openedFromQuestions = state.focus == ReaderPane.Related
        state = state.openProvenance(q.anchorId, page)
    }

    fun returnFromProvenance() {
        if (state.provenanceStack.isEmpty()) return
        state = state.returnFromProvenance()
        // 从题目点出处来的，返回后回到题目栏（窄窗口里该栏被收拢，单独显示）。
        if (openedFromQuestions) { focusPane(ReaderPane.Related); openedFromQuestions = false }
    }

    /** 保存：阅读状态 + 选题 + 未提交作答 + 出处返回来源。缺值恢复时按默认，不猜。 */
    fun snapshot(): String = buildString {
        append(state.encode())
        appendLine("sel=$selectedQuestion")
        appendLine("from=${if (openedFromQuestions) 1 else 0}")
        answers.toSortedMap().forEach { (q, o) -> appendLine("ans=$q:$o") }
    }

    fun restore(text: String?, widthDp: Int): Boolean {
        var r = ReaderState.restore(text, widthDp, fixture.pages.size) ?: return false
        val kv = text!!.lines().filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
        // 页数未知：不信任快照里的页码与出处栈，回到第 0 页。
        if (fixture.pages.isEmpty()) r = r.copy(position = ReaderPosition(0), provenanceStack = emptyList(), flashAnchor = null)
        // 焦点按保存值还原（收拢的栏也算有效焦点）。
        kv.firstOrNull { it.first == "focus" }?.second
            ?.let { n -> ReaderPane.entries.firstOrNull { it.name == n } }?.let { r = r.copy(focus = it) }
        val sel = kv.firstOrNull { it.first == "sel" }?.second?.toIntOrNull()
        val restored = kv.filter { it.first == "ans" }.mapNotNull {
            val q = it.second.substringBefore(':', "").toIntOrNull() ?: return@mapNotNull null
            val o = it.second.substringAfter(':', "").toIntOrNull() ?: return@mapNotNull null
            if (fixture.questions.getOrNull(q)?.options?.indices?.contains(o) == true) q to o else null
        }
        state = r
        selectedQuestion = sel?.takeIf { it in fixture.questions.indices } ?: 0
        answers.clear(); answers.putAll(restored)
        openedFromQuestions = kv.firstOrNull { it.first == "from" }?.second == "1" && r.provenanceStack.isNotEmpty()
        message = null
        return true
    }

    /** 远端文档打开后：页数与起始页来自服务（pdfPage 从 1 起，这里统一转成 0 起页序）；作答与选题随书清空。 */
    fun attachDocument(info: ReaderDocInfo, startPdfPage: Int) {
        state = ReaderState(state.layout, state.focus, ReaderPosition(PdfPages.toIndex(startPdfPage)), info.pages, state.zoomPercent)
        answers.clear(); selectedQuestion = 0; message = null; openedFromQuestions = false
    }

    /** 切到另一本书：旧书页、题目、作答不带过去。失败时保持无书状态，失败原因在 remote.failure。可指定初始目标页码与锚点。 */
    suspend fun openRemote(documentId: String, targetPdfPage: Int? = null, anchor: String? = null) {
        val r = remote ?: return
        cancelNote(); highlightNoteId = null
        navGeneration++
        val defaultStart = r.open(documentId)
        val doc = r.current
        if (defaultStart == null || doc == null) {
            // 失败：不留旧书的页数和位置。
            state = state.copy(pageCount = 0, position = ReaderPosition(0), provenanceStack = emptyList(), flashAnchor = null)
            answers.clear(); selectedQuestion = 0
            return
        }
        val start = (targetPdfPage ?: defaultStart).coerceIn(1, doc.pages)
        attachDocument(doc, start)
        if (!anchor.isNullOrBlank()) {
            state = state.copy(flashAnchor = anchor)
        }
    }

    /** 学到某一处要能按位置打开对应页（指定资料 ID、PDF 页码与可选锚点）。 */
    suspend fun openRemoteAt(documentId: String, targetPdfPage: Int, anchor: String? = null) {
        openRemote(documentId, targetPdfPage = targetPdfPage, anchor = anchor)
    }

    /** 翻到上一页（按页翻）。 */
    fun prevPage() {
        if (state.position.page > 0) {
            navGeneration++
            state = state.apply(ReaderAction.PrevPage)
        }
    }

    /** 翻到下一页（按页翻）。 */
    fun nextPage() {
        if (state.pageCount > 0 && state.position.page < state.pageCount - 1) {
            navGeneration++
            state = state.apply(ReaderAction.NextPage)
        }
    }

    /** 直接翻到指定页（从 1 起的 pdfPage）。 */
    fun goToPage(pdfPage: Int, anchor: String? = null) {
        val maxPages = remote?.current?.pages ?: state.pageCount
        if (maxPages <= 0) return
        val clamped = pdfPage.coerceIn(1, maxPages)
        navGeneration++
        state = state.goTo(PdfPages.toIndex(clamped)).let { s ->
            if (anchor != null) s.copy(flashAnchor = anchor) else s
        }
    }

    /** 用户选择“用另一台的位置”。 */
    fun useRemotePosition() {
        val p = remote?.useRemoteAfterConflict() ?: return
        state = state.goTo(PdfPages.toIndex(p))
    }

    /** 鼠标滚轮：正文上滚轮翻页，Ctrl+滚轮缩放；缩放受 50%–300% 限制。 */
    fun onWheel(deltaY: Float, ctrlOrCmd: Boolean) {
        if (deltaY == 0f || noting) return // 写批注时不翻页，避免草稿被带到别的页
        message = null
        if (!ctrlOrCmd && remote != null) scrollToEndOnArrive = deltaY < 0
        state = if (ctrlOrCmd) state.apply(if (deltaY < 0) ReaderAction.ZoomIn else ReaderAction.ZoomOut)
        else state.apply(if (deltaY > 0) ReaderAction.NextPage else ReaderAction.PrevPage)
    }

    /** 返回 true 表示键已被消费；false 交还系统，焦点不会被困住。 */
    fun onKey(input: KeyInput, digit: Int? = null): Boolean {
        message = null
        val s = state
        if (noteLine >= 0) { // 选句阶段：吞掉所有键，不翻页
            when (input.key) {
                ReaderKey.Down -> noteLine = minOf(noteLine + 1, pageLines().lastIndex)
                ReaderKey.Up -> noteLine = maxOf(noteLine - 1, 0)
                ReaderKey.Enter -> confirmLine()
                ReaderKey.Escape -> cancelNote()
                else -> {}
            }
            return true
        }
        if (noteDraft != null) { // 输入阶段：文字归输入框；Esc 放弃，Ctrl+回车保存
            if (input.key == ReaderKey.Escape) { cancelNote(); return true }
            if (input.ctrlOrCmd && input.key == ReaderKey.Enter) { requestNoteSave(); return true }
            return false
        }
        if (s.focus == ReaderPane.Related && !input.ctrlOrCmd && !input.alt) {
            val count = remote?.studyQuestions?.size ?: fixture.questions.size
            when {
                input.key == ReaderKey.Down && selectedQuestion < count - 1 -> { selectedQuestion++; return true }
                input.key == ReaderKey.Up && selectedQuestion > 0 -> { selectedQuestion--; return true }
                remote == null && digit != null && digit in 1..9 -> { answer(digit - 1); return true }
                input.key == ReaderKey.Enter -> { if (remote != null) openRemoteProvenance() else openProvenanceOfSelected(); return true }
            }
        }
        if (s.focus !in s.layout.visible) {
            // 被收拢的栏单独显示：Esc/Back 回出处，其它键归系统。
            if (input.key == ReaderKey.Escape || input.key == ReaderKey.Back) {
                if (s.provenanceStack.isNotEmpty()) { returnFromProvenance(); return true }
                focusPane(ReaderPane.Body); return true
            }
            return false
        }
        val action = ReaderKeys.map(input, s) ?: return false
        when (action) {
            ReaderAction.ReturnFromProvenance -> returnFromProvenance()
            ReaderAction.NewNote -> beginNote()
            ReaderAction.OpenProvenance -> {
                val a = s.selectedAnchor ?: return true
                val page = fixture.pageOfAnchor(a)
                if (page < 0) message = "这一页没找到出处" else state = s.openProvenance(a, page)
            }
            ReaderAction.ScrollDown -> scrollBody(true)
            ReaderAction.ScrollUp -> scrollBody(false)
            ReaderAction.NextPage, ReaderAction.PrevPage -> { scrollToEndOnArrive = false; state = s.apply(action) }
            else -> state = s.apply(action)
        }
        return true
    }
}
