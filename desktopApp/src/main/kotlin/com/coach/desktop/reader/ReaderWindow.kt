package com.coach.desktop.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed as ptrCtrl
import androidx.compose.ui.input.pointer.isMetaPressed as ptrMeta
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 桌面阅读窗口切片（独立于共享主界面）。
 *
 * 材料全部是明确标注的自造样张（ReaderFixture），不连书库、不连同步、不代表任何真实原句命中。
 * 唯一需要的真实接线（本文件不做）：Main.kt 里在 runCoach() 的窗口内容处改为
 *   ReaderWindow(session)，session 的页数/题目/锚点解析换成共享 reader 契约（1424/1426 合并后）。
 *
 * 适配边界：
 *  - anchorId→页码由 [ReaderFixture.pageOfAnchor] 提供，真实实现须来自锚点底座；解析不到则提示“这一页没找到出处”，不猜页。
 *  - pageCount==0 视为页数未知：不翻页、不显示页码，不把屏幕页码当书页码。
 *  - 题目栏的键位（上下选题、数字选项、Enter 查出处）在本窗口层处理，ReaderKeys 只管正文栏与通用键。
 */

data class FixtureSentence(val anchorId: String, val text: String)
data class FixtureQuestion(val stem: String, val options: List<String>, val anchorId: String)

/** 自造样张：页与句，题与出处锚点。pages 为空表示页数未知。 */
data class ReaderFixture(
    val title: String,
    val pages: List<List<FixtureSentence>>,
    val questions: List<FixtureQuestion>,
) {
    fun pageOfAnchor(anchorId: String): Int = pages.indexOfFirst { p -> p.any { it.anchorId == anchorId } }

    companion object {
        fun synthetic(pageCount: Int = 8): ReaderFixture {
            val pages = List(pageCount) { p -> List(3) { s -> FixtureSentence("p$p-s$s", "样张第${p + 1}段第${s + 1}句：这是自造的示例文字，不来自任何教材。") } }
            val qs = listOf(
                FixtureQuestion("样张题一：第3页第2句说了什么？", listOf("甲", "乙", "丙"), "p2-s1"),
                FixtureQuestion("样张题二：第6页第1句说了什么？", listOf("甲", "乙", "丙"), "p5-s0"),
                FixtureQuestion("样张题三：出处缺失的题", listOf("甲", "乙", "丙"), "missing-anchor"),
            )
            return ReaderFixture("自造样张（非教材）", pages, qs)
        }
    }
}

/** 窗口会话：状态、题目选择与作答都在这里，窗口缩放或出处往返都不清空。 */
class ReaderSession(val fixture: ReaderFixture, initialWidthDp: Int, val remote: ReaderRemote? = null) {
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

    fun beginNote() {
        if (remote == null || currentView() == null) { message = "这一页还没加载好，不能写批注"; return }
        if (pageLines().isEmpty()) { message = "这一页没有可选的文字"; return }
        noteDraft = null; noteLine = 0
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

    fun cancelNote() { noteLine = -1; noteDraft = null; noteText = ""; noteBaseline = null; noteAttempt = null; noteStash = null }

    private fun confirmLine() {
        val r = remote ?: return; val v = currentView() ?: return
        val range = pageLines().getOrNull(noteLine) ?: return
        val end = range.last + 1
        val existing = r.notes.firstOrNull { it.pdfPage == v.pdfPage && it.start == range.first && it.end == end && r.noteValid(it, v) }
        val doc = r.current ?: return
        noteDraft = NoteDraft(existing?.id ?: java.util.UUID.randomUUID().toString(), doc.id, doc.version, v.pdfPage, v.textSha256,
            range.first, end, v.text.substring(range.first, end), existing?.note.orEmpty(), existing?.revision ?: 0L)
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
        noteSaving = true; noteAttempt = toSave.note
        try { r.saveNote(toSave) } finally { noteSaving = false }
        val st = r.noteSave
        if (st is NoteSaveState.Saved && st.id == d.id && noteDraft?.id == d.id) {
            noteStash = null // 保存成功后不再挂着旧草稿的“找回”入口
            noteBaseline = toSave.note // 只把“实际发出去的文字”记为已保存；保存期间继续输入的内容仍算未保存
            noteDraft = toSave.copy(baseRevision = st.revision)
            highlightNoteId = d.id // 刚保存的批注立刻在页上标出（若能定位）
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
        noteDraft = d.copy(note = server.note, baseRevision = server.revision)
        noteText = server.note; noteBaseline = server.note; noteAttempt = null
    }

    fun restoreStashedDraft() { noteStash?.let { noteText = it; noteStash = null } }

    /** 用我的版本覆盖：只用刚读到的服务端 revision，且只提交这一次。 */
    suspend fun overwriteWithMine() {
        val r = remote ?: return; val d = noteDraft ?: return
        val toSave = d.copy(note = noteText)
        noteSaving = true; noteAttempt = toSave.note
        try {
            if (r.overwriteWithMine(toSave)) {
                val st = r.noteSave as NoteSaveState.Saved
                noteBaseline = toSave.note; noteDraft = toSave.copy(baseRevision = st.revision); noteStash = null; highlightNoteId = d.id
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

internal fun androidx.compose.ui.input.key.KeyEvent.toReaderInput(): Pair<KeyInput, Int?>? {
    if (type != KeyEventType.KeyDown) return null
    val k = when (key) {
        Key.DirectionLeft -> ReaderKey.Left
        Key.DirectionRight -> ReaderKey.Right
        Key.DirectionUp -> ReaderKey.Up
        Key.DirectionDown -> ReaderKey.Down
        Key.PageUp -> ReaderKey.PageUp
        Key.PageDown -> ReaderKey.PageDown
        Key.MoveHome -> ReaderKey.Home
        Key.MoveEnd -> ReaderKey.End
        Key.Spacebar -> ReaderKey.Space
        Key.Tab -> ReaderKey.Tab
        Key.Escape -> ReaderKey.Escape
        Key.Enter, Key.NumPadEnter -> ReaderKey.Enter
        Key.Back -> ReaderKey.Back
        Key.N -> ReaderKey.NewNote
        else -> ReaderKey.Other
    }
    val digit = when (key) {
        Key.One -> 1; Key.Two -> 2; Key.Three -> 3; Key.Four -> 4; else -> null
    }
    return KeyInput(k, isShiftPressed, isCtrlPressed || isMetaPressed, isAltPressed) to digit
}

private val Paper = Color(0xFFFAF8F3)
private val Ink = Color(0xFF222222)
private val Accent = Color(0xFF2F5D8A)
private val Flash = Color(0xFFFFE9A8)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReaderWindow(session: ReaderSession, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().background(Paper)) {
        val widthDp = maxWidth.value.toInt()
        LaunchedEffect(widthDp) { session.resize(widthDp) }
        val remote = session.remote
        if (remote != null) {
            LaunchedEffect(remote) { remote.loadCatalog() }
            LaunchedEffect(session.noteSaveTick) { if (session.noteSaveTick > 0) session.commitNote() }
            // 切页/切书：只留最后一次导航；重开同书同页依 navGeneration 刷新；页成功后再保存进度，保存失败不影响阅读。
            LaunchedEffect(remote.current?.id, session.state.position.page, session.navGeneration) {
                if (remote.current != null) {
                    val pdf = PdfPages.toPdf(session.state.position.page)
                    remote.show(pdf)
                    if (remote.page is PageState.Ready) remote.saveProgressAuto(pdf)
                }
            }
        }
        val state = session.state
        val shown = session.shownPanes()
        SideEffect { session.renderedPanes = shown }
        val requesters = remember { ReaderPane.entries.associateWith { FocusRequester() } }
        LaunchedEffect(state.focus, shown) { runCatching { requesters.getValue(state.focus).requestFocus() } }
        // 批注编辑区消失（保存后按 Esc / 放弃）时，焦点回到所在栏，键盘不会因为输入框被移除而失效。
        LaunchedEffect(session.noting) { if (!session.noting) runCatching { requesters.getValue(session.state.focus).requestFocus() } }

        Column(
            Modifier.fillMaxSize().testTag("reader-root").onPreviewKeyEvent { e ->
                val (input, digit) = e.toReaderInput() ?: return@onPreviewKeyEvent false
                session.onKey(input, digit)
            },
        ) {
            ReaderBar(session, shown)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (pane in shown) {
                    val weight = when (pane) { ReaderPane.Shelf -> 0.22f; ReaderPane.Body -> 0.5f; ReaderPane.Related -> 0.28f }
                    val focused = state.focus == pane
                    Box(
                        Modifier.weight(if (shown.size == 1) 1f else weight).fillMaxHeight().testTag("pane-${pane.name}")
                            .border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Accent else Color(0xFFD8D4CA)))
                            .focusRequester(requesters.getValue(pane))
                            .onFocusChanged { if (it.isFocused) session.focusPane(pane) }
                            .clickable { requesters.getValue(pane).requestFocus() }
                            .then(
                                if (pane == ReaderPane.Body) Modifier.onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { ev ->
                                    val c = ev.changes.first()
                                    val ctrl = ev.keyboardModifiers.ptrCtrl || ev.keyboardModifiers.ptrMeta
                                    // 页内还能滚（或正在写批注）就交给下面的滚动区；只有到页边界才翻页。Ctrl+滚轮始终是缩放。
                                    if (!ctrl && session.bodyCanScroll(c.scrollDelta.y)) return@onPointerEvent
                                    session.onWheel(c.scrollDelta.y, ctrl)
                                    c.consume()
                                } else Modifier,
                            )
                            .padding(12.dp),
                    ) {
                        when (pane) {
                            ReaderPane.Shelf -> ShelfPane(session)
                            ReaderPane.Body -> {
                                BodyPane(session)
                                session.bodyScroll?.let { sc ->
                                    androidx.compose.foundation.VerticalScrollbar(
                                        androidx.compose.foundation.rememberScrollbarAdapter(sc),
                                        Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight().testTag("body-scrollbar"),
                                    )
                                }
                            }
                            ReaderPane.Related -> RelatedPane(session)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderBar(session: ReaderSession, shown: List<ReaderPane>) {
    val state = session.state
    Row(Modifier.fillMaxWidth().background(Color(0xFFEFEBE0)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        // 窄窗口下被收拢的栏用文字按钮切换；宽窗口下按钮只表示当前焦点所在栏。
        for (pane in ReaderPane.entries) {
            val label = when (pane) { ReaderPane.Shelf -> "书架"; ReaderPane.Body -> "正文"; ReaderPane.Related -> "题目" }
            Text(
                label,
                Modifier.testTag("bar-${pane.name}").clickable { session.focusPane(pane) }.padding(end = 16.dp),
                color = if (state.focus == pane) Accent else Ink,
                fontWeight = if (state.focus == pane) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
            )
        }
        val remotePage = (session.remote?.page as? PageState.Ready)?.view
        val pos = when {
            session.remote != null && state.pageCount <= 0 -> "未打开文档"
            session.remote != null -> "PDF第${PdfPages.toPdf(state.position.page)}页 / 共${state.pageCount}页" + (remotePage?.printedPage?.let { "（印刷第${it}页）" } ?: "")
            state.pageCount > 0 -> "第${state.position.page + 1}页 / 共${state.pageCount}页（样张页）"
            else -> "页数未知"
        }
        Text(pos, Modifier.testTag("bar-position"), fontSize = 12.sp, color = Color(0xFF666666))
        if (state.pageCount > 0) {
            val curPage = PdfPages.toPdf(state.position.page)
            Text("$curPage / ${state.pageCount}", Modifier.testTag("reader-page-number").padding(start = 8.dp), fontSize = 12.sp, color = Color(0xFF666666))
            val canPrev = state.position.page > 0
            val canNext = state.position.page < state.pageCount - 1
            Text(
                "上一页",
                Modifier.testTag("bar-prev-page").testTag("previous-page").clickable(enabled = canPrev) { session.prevPage() }.padding(start = 12.dp),
                color = if (canPrev) Accent else Color(0xFF999999),
                fontSize = 12.sp,
            )
            Text(
                "下一页",
                Modifier.testTag("bar-next-page").testTag("next-page").clickable(enabled = canNext) { session.nextPage() }.padding(start = 8.dp),
                color = if (canNext) Accent else Color(0xFF999999),
                fontSize = 12.sp,
            )
        }
        session.onBack?.let { back ->
            Text(
                session.backLabel ?: "返回",
                Modifier.testTag("reader-back").clickable { back() }.padding(start = 16.dp),
                color = Accent,
                fontSize = 14.sp,
            )
        }
        if (state.provenanceStack.isNotEmpty()) {
            Text("  返回题目", Modifier.testTag("bar-return").clickable { session.returnFromProvenance() }, color = Accent, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ShelfPane(session: ReaderSession) {
    val remote = session.remote
    if (remote != null) {
        val scope = rememberCoroutineScope()
        Column {
            Text("书架", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            remote.failure?.let { Text(it.text, Modifier.testTag("shelf-failure").padding(top = 6.dp), color = Color(0xFFA33B2D), fontSize = 12.sp) }
            for (d in remote.documents) {
                Text(
                    d.title,
                    Modifier.testTag("doc-${d.id}").fillMaxWidth().clickable { scope.launch { session.openRemote(d.id) } }.padding(top = 6.dp),
                    fontSize = 13.sp,
                    color = if (remote.current?.id == d.id) Accent else Ink,
                    fontWeight = if (remote.current?.id == d.id) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        return
    }
    Column {
        Text("书架", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(session.fixture.title, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun BodyPane(session: ReaderSession) {
    val state = session.state
    if (session.remote != null) { RemoteBody(session, session.remote); return }
    val page = session.fixture.pages.getOrNull(state.position.page)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (page == null) {
            Text("页数未知，无法显示正文", fontSize = 14.sp)
            return@Column
        }
        val size = (16 * state.zoomPercent / 100f).sp
        for (s in page) {
            val flashing = state.flashAnchor == s.anchorId
            Text(
                s.text,
                Modifier.testTag("sentence-${s.anchorId}").fillMaxWidth().then(if (flashing) Modifier.background(Flash) else Modifier).padding(vertical = 4.dp),
                fontSize = size,
                color = Ink,
            )
        }
        session.message?.let { Text(it, Modifier.testTag("message").padding(top = 8.dp), color = Color(0xFFA33B2D), fontSize = 13.sp) }
    }
}

@Composable
private fun RelatedPane(session: ReaderSession) {
    if (session.remote != null) { RemoteStudy(session, session.remote); return }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("题目", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        session.fixture.questions.forEachIndexed { i, q ->
            val selected = session.selectedQuestion == i
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).then(if (selected) Modifier.background(Color(0xFFE6EEF6)) else Modifier).testTag("question-$i")) {
                Text(q.stem, fontSize = 13.sp)
                val chosen = session.answers[i]
                Text(
                    if (chosen == null) "未作答" else "已选：${q.options[chosen]}",
                    Modifier.testTag("answer-$i"),
                    fontSize = 12.sp,
                    color = Color(0xFF555555),
                )
                if (selected) Text("这句在哪考过（回车）", Modifier.clickable { session.openProvenanceOfSelected() }, color = Accent, fontSize = 12.sp)
            }
        }
        session.message?.let { Text(it, Modifier.testTag("message").padding(top = 8.dp), color = Color(0xFFA33B2D), fontSize = 13.sp) }
    }
}

@Composable
private fun RemoteBody(session: ReaderSession, remote: ReaderRemote) {
    val state = session.state
    // 滚轮先在页内滚动，到边界才翻页（由外层 Initial 阶段判断）；换页/换书时滚动位置归零，往回翻则停在末尾。
    val scroll = remember(remote.current?.id, state.position.page) { androidx.compose.foundation.ScrollState(0) }
    SideEffect { session.bodyScroll = scroll }
    LaunchedEffect(scroll.maxValue, remote.page, session.scrollToEndOnArrive) {
        if (session.scrollToEndOnArrive && remote.page is PageState.Ready) { scroll.scrollTo(scroll.maxValue); session.scrollToEndOnArrive = false }
    }
    Column(Modifier.verticalScroll(scroll)) {
        when (val p = remote.page) {
            PageState.Idle -> Text(if (remote.current == null) "请先在书架里选一本" else "", Modifier.testTag("remote-idle"), fontSize = 14.sp)
            is PageState.Loading -> Text("正在加载 PDF 第${p.pdfPage}页", Modifier.testTag("remote-loading"), fontSize = 13.sp, color = Color(0xFF666666))
            is PageState.Failed -> Column {
                Text(p.failure.text, Modifier.testTag("remote-failure"), color = Color(0xFFA33B2D), fontSize = 14.sp)
                val scope = rememberCoroutineScope()
                Text("重试", Modifier.testTag("remote-retry").clickable { scope.launch { session.retryPage() } }.padding(top = 4.dp), color = Accent, fontSize = 14.sp)
            }
            is PageState.Ready -> RemotePage(session, remote, p.view)
        }
        when (val s = remote.save) {
            is SaveState.Conflict -> Column(Modifier.testTag("save-conflict").padding(top = 8.dp)) {
                Text(
                    if (s.versionChanged) "资料版本变了，本机位置未保存，已保留在本机" else "另一台设备已保存了新的位置，本机位置没有覆盖",
                    color = Color(0xFFA33B2D), fontSize = 13.sp,
                )
                if (s.refreshFailed) Text("连不上服务，稍后重试", Modifier.testTag("conflict-refresh-failed"), color = Color(0xFF8A6D1F), fontSize = 12.sp)
                if (!s.versionChanged) {
                    val scope = rememberCoroutineScope()
                    Text("保留本机位置", Modifier.testTag("keep-local").clickable { scope.launch { remote.keepLocalAfterConflict() } }.padding(top = 4.dp), color = Accent, fontSize = 13.sp)
                    if (s.remote != null) Text("用另一台的位置", Modifier.testTag("use-remote").clickable { session.useRemotePosition() }.padding(top = 4.dp), color = Accent, fontSize = 13.sp)
                }
            }
            is SaveState.Offline -> Text("连不上服务，位置稍后重试保存", Modifier.testTag("save-offline").padding(top = 8.dp), color = Color(0xFF8A6D1F), fontSize = 12.sp)
            is SaveState.Failed -> Text("位置没有保存：${s.failure.text}", Modifier.testTag("save-failed").padding(top = 8.dp), color = Color(0xFFA33B2D), fontSize = 12.sp)
            else -> {}
        }
    }
}

/** 页面：页图（可叠原句高亮）+ 按行的正文 + 本页批注 + 批注编辑。 */
@Composable
private fun RemotePage(session: ReaderSession, remote: ReaderRemote, view: ReaderPageView) {
    val state = session.state
    val bitmap = remember(view.image) { view.image?.let { b -> runCatching { org.jetbrains.skia.Image.makeFromEncoded(b).toComposeImageBitmap() }.getOrNull() } }
    // 高亮的引文：题目点出处时优先查题目的原句；若未命中题目 ID 则直接作为文本锚点；否则是用户点选的批注。
    val quote = remember(state.flashAnchor, session.highlightNoteId, remote.notes, remote.study) {
        state.flashAnchor?.takeIf { it.isNotBlank() }?.let { raw ->
            remote.studyQuestions.firstOrNull { it.id == raw }?.anchor ?: raw
        } ?: session.highlightNoteId?.let { id ->
            remote.notes.firstOrNull { it.id == id && remote.noteValid(it, view) }?.quote
        }
    }
    val located: Located? = when (val b = remote.boxes) {
        is BoxesState.Ready -> quote?.let { QuoteLocator.locate(b.boxes, it) }
        else -> null
    }
    if (bitmap != null) {
        val boxes = (remote.boxes as? BoxesState.Ready)?.boxes
        val aspectOk = boxes != null && kotlin.math.abs(bitmap.width.toDouble() / bitmap.height - boxes.width / boxes.height) < 0.02
        Box(Modifier.fillMaxWidth()) {
            Image(bitmap, "PDF 第${view.pdfPage}页", Modifier.testTag("remote-image").fillMaxWidth(), contentScale = ContentScale.FillWidth)
            if (located is Located.Found && aspectOk && boxes != null) {
                Canvas(Modifier.testTag("remote-highlight").matchParentSize()) {
                    val k = size.width / boxes.width.toFloat()
                    for (r in located.rects) drawRect(
                        Flash.copy(alpha = 0.55f),
                        topLeft = androidx.compose.ui.geometry.Offset(r.x0.toFloat() * k, r.y0.toFloat() * k),
                        size = androidx.compose.ui.geometry.Size((r.x1 - r.x0).toFloat() * k, (r.y1 - r.y0).toFloat() * k),
                    )
                }
            }
        }
    }
    val status = if (quote == null) null else {
        when {
            remote.boxes !is BoxesState.Ready -> "文字坐标不可用，不标注原句"
            located == Located.NoTextLayer -> "这一页没有文字层，无法标出原句"
            located == Located.NotFound -> "没找到这句原文，无法确认位置"
            located == Located.Ambiguous -> "这句原文在页上出现多处，无法确认是哪一处"
            bitmap == null -> "没有页图，无法在页上标出"
            located is Located.Found -> "已在页上标出原句"
            else -> "无法确认原句位置"
        }
    }
    SideEffect { session.uiHighlight = status }
    if (status != null) Text(status, Modifier.testTag("highlight-status").padding(vertical = 4.dp), fontSize = 12.sp, color = Color(0xFF555555))
    val size = (16 * state.zoomPercent / 100f).sp
    val lines = session.pageLines()
    var offset = 0
    view.text.split('\n').forEachIndexed { i, line ->
        val range = offset until offset + line.length
        val picked = session.noteLine >= 0 && lines.getOrNull(session.noteLine) == range && line.isNotBlank()
        val noted = remote.notes.any { remote.noteValid(it, view) && it.start >= range.first && it.end <= range.last + 1 }
        Text(
            line,
            Modifier.testTag("line-$i").fillMaxWidth().onGloballyPositioned { session.uiLineBounds[i] = it.boundsInRoot() }
                .then(if (picked) Modifier.background(Color(0xFFCFE3F6)) else if (noted) Modifier.background(Color(0xFFFFF4C2)) else Modifier)
                .padding(vertical = 3.dp),
            fontSize = size, color = Ink,
        )
        offset += line.length + 1
    }
    if (session.noteLine >= 0) Text("上下键选一行，回车确认，Esc 放弃", Modifier.testTag("note-pick-hint").padding(top = 4.dp), fontSize = 12.sp, color = Accent)
    session.message?.let { Text(it, Modifier.testTag("message").padding(top = 8.dp), color = Color(0xFFA33B2D), fontSize = 13.sp) }
    NotesSection(session, remote, view)
}

@Composable
private fun NotesSection(session: ReaderSession, remote: ReaderRemote, view: ReaderPageView) {
    val mine = remote.notes.filter { it.pdfPage == view.pdfPage || it.stale || it.version != remote.current?.version }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("批注（按 N 为一行写批注）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (mine.isEmpty()) Text("这一页还没有批注", Modifier.testTag("notes-empty"), fontSize = 12.sp, color = Color(0xFF666666))
        for (n in mine) {
            val valid = remote.noteValid(n, view)
            Column(Modifier.testTag("note-${n.id}").padding(top = 6.dp)) {
                if (!valid) Text("原文或资料版本已变化，位置待重新确认（原 PDF 第${n.pdfPage}页）", Modifier.testTag("note-stale-${n.id}"), fontSize = 12.sp, color = Color(0xFFA33B2D))
                Text("我的原句：${n.quote}", fontSize = 12.sp)
                if (n.note.isNotEmpty()) Text("我的批注：${n.note}", fontSize = 12.sp)
                if (valid) Text("在页上标出", Modifier.testTag("note-show-${n.id}").clickable { session.highlightNoteId = n.id }, fontSize = 12.sp, color = Accent)
            }
        }
        val d = session.noteDraft
        if (d == null) SideEffect { session.uiNoteStatus = null }
        if (d != null) {
            Column(Modifier.testTag("note-editor").padding(top = 8.dp).border(BorderStroke(1.dp, Accent)).padding(8.dp)) {
                Text("原句：${d.quote}", fontSize = 12.sp)
                val inputFocus = remember(d.id) { FocusRequester() }
                LaunchedEffect(d.id) { runCatching { inputFocus.requestFocus() } } // 选定一行后光标直接进输入框，可以马上键入
                // 光标起点在已有文字末尾（编辑已有批注时从末尾接着写）；外部改文字（采用服务端版本/找回草稿）时同步进输入框。
                var field by remember(d.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(session.noteText, androidx.compose.ui.text.TextRange(session.noteText.length))) }
                if (field.text != session.noteText) field = androidx.compose.ui.text.input.TextFieldValue(session.noteText, androidx.compose.ui.text.TextRange(session.noteText.length))
                androidx.compose.foundation.text.BasicTextField(
                    value = field, onValueChange = { field = it; session.noteText = it.text },
                    modifier = Modifier.testTag("note-input").focusRequester(inputFocus).fillMaxWidth().padding(vertical = 6.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Ink),
                )
                Text("Ctrl+回车保存，Esc 放弃", fontSize = 11.sp, color = Color(0xFF666666))
                val status = session.noteStatus()
                SideEffect { session.uiNoteStatus = status?.text }
                if (status != null) Text(
                    status.text, Modifier.testTag("note-status"), fontSize = 12.sp,
                    color = when (status) {
                        NoteStatus.Saved -> Color(0xFF2F6B3A)
                        NoteStatus.Dirty, NoteStatus.Offline, NoteStatus.Saving, NoteStatus.NotSaved -> Color(0xFF8A6D1F)
                        else -> Color(0xFFA33B2D)
                    },
                )
                (remote.noteSave as? NoteSaveState.Failed)?.let { if (status == NoteStatus.Failed) Text(NoteStatusRules.failureText(it.failure), Modifier.testTag("note-failure-reason"), fontSize = 12.sp, color = Color(0xFFA33B2D)) }
                val conflict = remote.noteSave as? NoteSaveState.Conflict
                if (status == NoteStatus.Conflict && conflict != null) ConflictPanel(session, conflict)
                if (session.noteStash != null) Text("找回我的草稿", Modifier.testTag("note-restore-stash").clickable { session.restoreStashedDraft() }.padding(top = 4.dp), fontSize = 12.sp, color = Accent)
            }
        }
    }
}

@Composable
private fun RemoteStudy(session: ReaderSession, remote: ReaderRemote) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("题目", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        when (val s = remote.study) {
            StudyState.Idle -> {}
            StudyState.Loading -> Text("加载中", fontSize = 12.sp, color = Color(0xFF666666))
            is StudyState.Failed -> Text(s.failure.text, Modifier.testTag("study-failure"), fontSize = 12.sp, color = Color(0xFFA33B2D))
            is StudyState.Ready -> {
                if (!s.view.available) Text("这本书还没有登记题目", Modifier.testTag("study-unavailable"), fontSize = 12.sp)
                s.view.questions.forEachIndexed { i, q ->
                    val selected = session.selectedQuestion == i
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp).then(if (selected) Modifier.background(Color(0xFFE6EEF6)) else Modifier).testTag("study-$i")) {
                        Text(q.stem, fontSize = 13.sp)
                        if (selected) Text(
                            if (q.anchor.isBlank()) "这题没有登记原句" else "这句在哪考过（回车）",
                            Modifier.testTag("study-source-$i").clickable { session.openRemoteProvenance() }, color = Accent, fontSize = 12.sp,
                        )
                    }
                }
                if (s.view.unlocated > 0) Text("另有${s.view.unlocated}题没能定位到页", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Color(0xFF666666))
            }
        }
        session.message?.let { Text(it, Modifier.testTag("message").padding(top = 8.dp), color = Color(0xFFA33B2D), fontSize = 13.sp) }
    }
}

@Composable
private fun ConflictPanel(session: ReaderSession, c: NoteSaveState.Conflict) {
    val scope = rememberCoroutineScope()
    Column(Modifier.testTag("note-conflict-panel").padding(top = 6.dp)) {
        when {
            c.refreshFailed -> Text("连不上服务，看不到服务端版本，不能覆盖", Modifier.testTag("note-conflict-refresh-failed"), fontSize = 12.sp, color = Color(0xFF8A6D1F))
            c.versionChanged -> Text("资料版本变了，不能覆盖；可另存为新批注", Modifier.testTag("note-conflict-version"), fontSize = 12.sp, color = Color(0xFFA33B2D))
            c.server == null -> Text("服务端看不到这条批注（可能已删除），不能覆盖；可另存为新批注", Modifier.testTag("note-conflict-missing"), fontSize = 12.sp, color = Color(0xFFA33B2D))
            else -> Text("服务端版本（修订 ${c.server.revision}）：${c.server.note}", Modifier.testTag("note-conflict-server"), fontSize = 12.sp)
        }
        val trusted = !c.refreshFailed && !c.versionChanged && c.server != null
        if (trusted) {
            Text("采用服务端版本", Modifier.testTag("note-adopt-server").clickable { session.adoptServerVersion() }.padding(top = 4.dp), fontSize = 12.sp, color = Accent)
            Text("用我的版本覆盖", Modifier.testTag("note-overwrite-mine").clickable { scope.launch { session.overwriteWithMine() } }.padding(top = 4.dp), fontSize = 12.sp, color = Accent)
        }
        if (c.refreshFailed) Text("重新获取服务端版本", Modifier.testTag("note-conflict-refresh").clickable { scope.launch { session.retryConflictRefresh() } }.padding(top = 4.dp), fontSize = 12.sp, color = Accent)
        Text("另存为新批注", Modifier.testTag("note-save-as-new").clickable { session.saveAsNewNote() }.padding(top = 4.dp), fontSize = 12.sp, color = Accent)
    }
}
