package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.coach.client.*
import com.coach.ui.chat.AnchoredChatList
import com.coach.ui.chat.ChatListState
import com.coach.ui.input.ComposerField
import com.coach.ui.input.EnterGate
import kotlinx.coroutines.launch

/** 阅读栏最大宽度：内屏/桌面宽窗口下正文不拉成一整行。 */
private val READING_WIDTH = 600.dp

@Composable
fun StudyScreen(c: CoachController, kp: KpRow, view: CourseView, ui: TutorUiState, autoStart: Boolean, onBack: (() -> Unit)?, initialDraft: String? = null, onOpenReference: ((KpRow) -> Unit)? = null, weekActions: WeekActions? = null) {
    val thread = view.tutorThreadId(kp.id)
    val tv = ui.threads[thread] ?: ThreadView()
    val groups = view.quizGroups(thread)
    SideEffect { if(groups.isNotEmpty()) PoolTrace.mark(thread,"composed") }
    val chosen = remember(kp.id) { mutableStateMapOf<String, String>() }
    val pending = groups.lastOrNull { !it.submitted }
    val additionalScope = remember(kp.title) { additionalTitleScope(kp.title) }
    var showScope by remember(kp.id) { mutableStateOf(false) }
    var draft by remember(kp.id) { mutableStateOf(TextFieldValue(initialDraft.orEmpty())) }
    val enterGate = remember(kp.id) { EnterGate() }
    val focus = LocalFocusManager.current
    var opened by remember(kp.id) { mutableStateOf(false) }
    var started by remember(kp.id) { mutableStateOf(false) }
    var pinned by remember(kp.id) { mutableStateOf(false) }
    val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

    fun open() = c.tutor.open(thread, c.courseId, kp.id, kp.conceptIds, kp.title)
    fun fail() = c.tutor.model.onFailure(thread, "Mac 未连接，这一步没有发出去。")
    fun next(test: Boolean) {
        pinned = true
        focus.clearFocus()
        if (!open()) return fail()
        pinned = true
        c.tutor.model.onSent(thread, if (test) "test" else "next", null)
        if (!c.tutor.next(thread, c.courseId, c.packFor(kp.id), test)) fail()
    }
    fun send(text: String): Boolean {
        if (tv.busy || !ui.connected) return false
        // 发出后收起键盘：回答和「下一步 / 出题」按钮马上可见。
        focus.clearFocus()
        if (!open()) { fail(); return false }
        pinned = true
        return if (c.tutor.ask(thread, c.courseId, text, c.packFor(kp.id))) true else { fail(); false }
    }

    // 每次（重新）连上都声明一次线程；服务端按 kpId 幂等。
    LaunchedEffect(kp.id, ui.connected, tv.historyLoaded) {
        if (!ui.connected) { opened = false; return@LaunchedEffect }
        if (!opened) opened = open()
        if (autoStart && !started && opened && tv.historyLoaded && tv.items.isEmpty() && pending == null) { started = true; next(false) }
    }

    // A newly passed queue item opens its successor after grading/streaming settles.
    LaunchedEffect(kp.status, view.weekTarget?.id, tv.busy, pending) {
        if (weekActions != null && view.weekProgress(kp.id) != null && kp.status in setOf(KpStatus.PASSED, KpStatus.SOLID) && !tv.busy && pending == null) {
            weekActions.onOpen(view.weekTarget?.id)
        }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = READING_WIDTH).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
                // Back button and the title's first line sit on one row; the scope toggle lines up with the title's left edge.
                if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 今天") }
                Column(Modifier.weight(1f)) {
                    val (tag, main) = splitTitle(kp.title)
                    Text(main, Modifier.padding(top = if (onBack != null) 11.dp else 8.dp), style = MaterialTheme.typography.titleMedium)
                    // 范围、看原文（点开就是本知识点锚点所在的教材页）、状态放在同一组里，放不下就换行，不压标题。
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                        TextButton(onClick = { c.tutor.telemetry?.record("tap", "scope"); showScope = !showScope }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(if (showScope) "收起学习范围" else tag.ifBlank { "学习范围" } + if (additionalScope.isNotEmpty()) " · 共 ${additionalScope.size + 1} 项 ▾" else " · 范围 ▾", style = MaterialTheme.typography.labelMedium)
                        }
                        if (onOpenReference != null && kp.anchorDoc.isNotBlank()) OutlinedButton(onClick = { onOpenReference(kp) }, modifier = Modifier.heightIn(min = 48.dp).testTag("open-source"), contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Text("看原文 · ${kp.reference.ifBlank { "教材" }}", style = MaterialTheme.typography.labelLarge)
                        } else if (kp.reference.isNotBlank()) Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { Text("本次参考：${kp.reference}", style = MaterialTheme.typography.labelMedium) }
                        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { StatusChip(kp.status.label, statusColor(kp.status)) }
                    }
                    view.weekProgress(kp.id)?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    if (showScope) {
                        Text(main, style = MaterialTheme.typography.bodyMedium)
                        additionalScope.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        if (weekActions != null) WeekPicker(view, weekActions, ui.connected)
                    }
                }
            }
        }
        HorizontalDivider()

        // Reading position lives with the screen state (and this screen is `movableContentOf` in CoachApp): a fold/unfold keeps it.
        val chat = remember(kp.id) { ChatListState() }
        val scope = rememberCoroutineScope()
        // 讲解/追问消息和题组按「发生的先后」交错：消息出现时记下当时已有几组题，排在那几组之后。
        val anchors = remember(kp.id) { c.quizMessageAnchors.getOrPut(thread) { HashMap() } }
        val rows = remember(tv.items, groups, tv.notice, tv.poolText, tv.error) {
            val placed = tv.items.map { item -> item to anchors.getOrPut(item.key) { groups.size }.coerceAtMost(groups.size) }
            buildList<StudyRow> {
                for (g in 0..groups.size) {
                    placed.filter { it.second == g }.forEach { add(StudyRow.Message(it.first)) }
                    groups.getOrNull(g)?.let { group ->
                        group.cards.forEachIndexed { index, card -> add(StudyRow.Question(group, index, card)) }
                        add(StudyRow.GroupFooter(group))
                    }
                }
                tv.notice?.let { add(StudyRow.Note("notice", it, NoteKind.Notice)) }
                tv.poolText?.let { add(StudyRow.Note("pool", it, NoteKind.Progress)) }
                tv.error?.let { add(StudyRow.Note("error", it, NoteKind.Error)) }
            }
        }
        // 发送、点「下一步/出题」后回到底部继续跟随。
        LaunchedEffect(pinned) { if (pinned) { chat.scrollToTail(); pinned = false } }
        // 新题组出现，或刚打开线程时还没答完的那组：把第 1 题放在最上面，从头读起。
        var seenGroups by remember(kp.id) { mutableStateOf(0) }
        val focusKey = if (groups.size > seenGroups && (pending != null || seenGroups > 0))
            groups.last().cards.firstOrNull()?.let { "q:${it.attemptId}" } else null
        SideEffect {
            if (focusKey != null) {
                val index = rows.indexOfFirst { it.key == focusKey }
                if (index >= 0) chat.requestItemTop(focusKey, rows.lastIndex - index)
            }
        }
        LaunchedEffect(groups.size) {
            if (groups.size > seenGroups && (pending != null || seenGroups > 0)) {
                PoolTrace.mark(thread,"focus_requested")
                groups.last().cards.firstOrNull()?.let { chat.scrollItemTopIntoView("q:${it.attemptId}") }
                PoolTrace.mark(thread,"focus_settled")
            }
            seenGroups = groups.size
        }
        Box(Modifier.weight(1f).widthIn(max = READING_WIDTH).fillMaxWidth()) {
            if (rows.isEmpty()) Text(
                if (kp.explained) "这个知识点讲过了。点「下一步」继续出题，或直接提问。" else "还没开始。点下面的「开始讲解」。",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BoxWithConstraints(Modifier.fillMaxSize()) {
                AnchoredChatList(
                    items = rows, key = { it.key }, state = chat, windowKey = constraints.maxWidth,
                    modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), spacing = 14.dp,
                ) { row ->
                    when (row) {
                        is StudyRow.Message -> when (val item = row.item) {
                            is ChatItem.Learner -> LearnerBubble(item.text)
                            is ChatItem.Tutor -> TutorBubble(item.message, c.math) { c.tutor.cancel(item.message.id) }
                        }
                        is StudyRow.Question -> QuizCardView(row.index + 1, row.group.cards.size, row.card, row.group.submitted, chosen[row.card.attemptId], c.math, onFirstDraw={ PoolTrace.mark(thread,"first_draw") }) { letter ->
                            if (!row.group.submitted) { c.tutor.telemetry?.record("tap", "select_option"); chosen[row.card.attemptId] = letter }
                        }
                        is StudyRow.GroupFooter -> QuizGroupFooter(row.group, kp, c, view, thread, chosen) { c.tutor.model.onSent(thread, "answer", null); pinned = true }
                        is StudyRow.Note -> when (row.kind) {
                            NoteKind.Notice -> Text(row.text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            NoteKind.Progress -> ProgressLine(row.text)
                            NoteKind.Error -> Text(row.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (!chat.anchor.pinned && tv.streaming) {
                FilledTonalButton(onClick = { scope.launch { chat.scrollToTail() } }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).heightIn(min = 48.dp)) { Text("回到最新") }
            }
        }
        HorizontalDivider()
        Column(Modifier.widthIn(max = READING_WIDTH).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 答题时「下一步 / 出题」本来就点不了，不占高度，把空间让给题目；键盘弹出时同样收起。
            if (!imeOpen && pending == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // 服务端只在需要时给特殊文字（「换个例子再讲」）；还没讲过、线程里什么都没有时按钮就叫「开始讲解」。
                val nextText = when {
                    tv.nextLabel == "换个例子再讲" -> "换个例子再讲"
                    !kp.explained && tv.items.isEmpty() && groups.isEmpty() -> "开始讲解"
                    else -> "下一步"
                }
                Button(onClick = { next(false) }, enabled = ui.connected && !tv.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(nextText) }
                OutlinedButton(onClick = { next(true) }, enabled = ui.connected && !tv.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("出题") }
            }
            if (!imeOpen && weekActions != null && view.weekTarget?.id == kp.id) {
                OutlinedButton(onClick = { weekActions.next(view, kp.id) }, enabled = ui.connected && !tv.busy && !weekActions.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("下一个知识点 →") }
                weekActions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            // 输入框始终可用（讲解进行中也能打字，键盘不会被收起）；只有「发送」会按状态禁用。
            // 组字回车只上屏、不发送；硬件键盘/桌面回车发送，Shift+回车换行；手机回车换行，点击按钮发送（§7.6）。
            ComposerField(
                value = draft, onValueChange = { draft = it }, onSend = { send(it) }, gate = enterGate,
                placeholder = if (pending != null) "追问（不影响通过状态）…" else "追问…", maxLines = 4, canSend = ui.connected && !tv.busy,
            )
        }
    }
}

private sealed interface StudyRow {
    val key: String
    data class Message(val item: ChatItem) : StudyRow { override val key get() = item.key }
    data class Question(val group: QuizGroup, val index: Int, val card: QuizCard) : StudyRow { override val key get() = "q:${card.attemptId}" }
    data class GroupFooter(val group: QuizGroup) : StudyRow { override val key get() = "g:${group.groupId}" }
    data class Note(override val key: String, val text: String, val kind: NoteKind) : StudyRow
}

private enum class NoteKind { Notice, Progress, Error }

@Composable
private fun ProgressLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
