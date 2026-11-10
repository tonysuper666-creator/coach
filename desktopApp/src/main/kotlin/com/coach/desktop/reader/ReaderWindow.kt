package com.coach.desktop.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.*
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
class ReaderSession(val fixture: ReaderFixture, initialWidthDp: Int) {
    var state by mutableStateOf(ReaderState(DesktopReaderLayouts.forWidth(initialWidthDp), pageCount = fixture.pages.size))
        private set
    var selectedQuestion by mutableIntStateOf(0)
        private set
    val answers: SnapshotStateMap<Int, Int> = mutableStateMapOf()
    var message by mutableStateOf<String?>(null)
        private set
    private var openedFromQuestions = false

    /** 本次组合实际渲染的栏，供测试读取。 */
    var renderedPanes: List<ReaderPane> = emptyList()

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

    /** 鼠标滚轮：正文上滚轮翻页，Ctrl+滚轮缩放；缩放受 50%–300% 限制。 */
    fun onWheel(deltaY: Float, ctrlOrCmd: Boolean) {
        if (deltaY == 0f) return
        state = if (ctrlOrCmd) state.apply(if (deltaY < 0) ReaderAction.ZoomIn else ReaderAction.ZoomOut)
        else state.apply(if (deltaY > 0) ReaderAction.NextPage else ReaderAction.PrevPage)
    }

    /** 返回 true 表示键已被消费；false 交还系统，焦点不会被困住。 */
    fun onKey(input: KeyInput, digit: Int? = null): Boolean {
        message = null
        val s = state
        if (s.focus == ReaderPane.Related && !input.ctrlOrCmd && !input.alt) {
            when {
                input.key == ReaderKey.Down && selectedQuestion < fixture.questions.lastIndex -> { selectedQuestion++; return true }
                input.key == ReaderKey.Up && selectedQuestion > 0 -> { selectedQuestion--; return true }
                digit != null && digit in 1..9 -> { answer(digit - 1); return true }
                input.key == ReaderKey.Enter -> { openProvenanceOfSelected(); return true }
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
            ReaderAction.OpenProvenance -> {
                val a = s.selectedAnchor ?: return true
                val page = fixture.pageOfAnchor(a)
                if (page < 0) message = "这一页没找到出处" else state = s.openProvenance(a, page)
            }
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
        val state = session.state
        val shown = session.shownPanes()
        SideEffect { session.renderedPanes = shown }
        val requesters = remember { ReaderPane.entries.associateWith { FocusRequester() } }
        LaunchedEffect(state.focus, shown) { runCatching { requesters.getValue(state.focus).requestFocus() } }

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
                                if (pane == ReaderPane.Body) Modifier.onPointerEvent(PointerEventType.Scroll) { ev ->
                                    val c = ev.changes.first()
                                    session.onWheel(c.scrollDelta.y, ev.keyboardModifiers.ptrCtrl || ev.keyboardModifiers.ptrMeta)
                                    c.consume()
                                } else Modifier,
                            )
                            .padding(12.dp),
                    ) {
                        when (pane) {
                            ReaderPane.Shelf -> ShelfPane(session)
                            ReaderPane.Body -> BodyPane(session)
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
        val pos = if (state.pageCount > 0) "第${state.position.page + 1}页 / 共${state.pageCount}页（样张页）" else "页数未知"
        Text(pos, Modifier.testTag("bar-position"), fontSize = 12.sp, color = Color(0xFF666666))
        if (state.provenanceStack.isNotEmpty()) {
            Text("  返回题目", Modifier.testTag("bar-return").clickable { session.returnFromProvenance() }, color = Accent, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ShelfPane(session: ReaderSession) {
    Column {
        Text("书架", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(session.fixture.title, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun BodyPane(session: ReaderSession) {
    val state = session.state
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
