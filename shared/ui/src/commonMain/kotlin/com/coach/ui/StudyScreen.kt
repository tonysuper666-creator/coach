package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
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
private val READING_WIDTH = 680.dp

@Composable
fun StudyScreen(c: CoachController, kp: KpRow, view: CourseView, ui: TutorUiState, autoStart: Boolean, onBack: (() -> Unit)?, initialDraft: String? = null) {
    val thread = threadIdFor(kp.id)
    val tv = ui.threads[thread] ?: ThreadView()
    val groups = view.quizGroups(thread)
    val pending = groups.lastOrNull { !it.submitted }
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
        c.tutor.model.onSent(thread, "ask", text)
        return if (c.tutor.ask(thread, c.courseId, text, c.packFor(kp.id))) true else { fail(); false }
    }

    // 每次（重新）连上都声明一次线程；服务端按 kpId 幂等。
    LaunchedEffect(kp.id, ui.connected) {
        if (!ui.connected) { opened = false; return@LaunchedEffect }
        if (!opened) opened = open()
        if (autoStart && !started && opened && tv.items.isEmpty() && pending == null) { started = true; next(false) }
    }

    // Reading position lives with the screen state (and the screen is `movableContentOf` in CoachApp), so a fold/unfold keeps it.
    val chat = remember(kp.id) { ChatListState() }
    val scope = rememberCoroutineScope()
    // 讲解/追问消息和题组按「发生的先后」交错：消息出现时记下当时已有几组题，排在那几组之后。
    val anchors = remember(kp.id) { HashMap<String, Int>() }
    val rows = remember(tv.items, groups, tv.notice, tv.poolText, tv.error) {
        val placed = tv.items.map { item -> item to anchors.getOrPut(item.key) { groups.size } }
        buildList<StudyRow> {
            for (g in 0..groups.size) {
                placed.filter { it.second == g }.forEach { add(StudyRow.Message(it.first)) }
                groups.getOrNull(g)?.let { add(StudyRow.Group(it)) }
            }
            tv.notice?.let { add(StudyRow.Note("notice", it, NoteKind.Notice)) }
            tv.poolText?.let { add(StudyRow.Note("pool", it, NoteKind.Progress)) }
            tv.error?.let { add(StudyRow.Note("error", it, NoteKind.Error)) }
        }
    }
    // 发送、点「下一步/出题」后回到底部继续跟随。
    LaunchedEffect(pinned) { if (pinned) { chat.scrollToTail(); pinned = false } }
    // 新题组出现时把它的开头放在最上面，方便从第 1 题读起（组比屏幕矮时它就停在底部）。
    var seenGroups by remember(kp.id) { mutableStateOf(0) }
    LaunchedEffect(groups.size) {
        // 新出现的组，或刚打开线程时还没答完的那组：从第 1 题读起。
        if (groups.size > seenGroups && (pending != null || seenGroups > 0)) {
            repeat(3) { withFrameNanos { } }
            chat.scrollItemTopIntoView("g:${groups.last().groupId}")
        }
        seenGroups = groups.size
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().widthIn(max = READING_WIDTH)) {
            val (tag, main) = splitTitle(kp.title)
            val more = extraTopics(kp.title)
            Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 4.dp, end = 16.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 今天") }
                Column(Modifier.weight(1f).padding(top = if (onBack != null) 10.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 标题独占整行、完整显示；状态标签和参考页放在它下面一行，不压标题。
                    Text(main, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(kp.status.label, statusColor(kp.status))
                        val sub = listOfNotNull(tag.takeIf { it.isNotBlank() }, kp.reference.takeIf { it.isNotBlank() }?.let { "本次参考：$it" }, more.takeIf { it > 0 }?.let { "同组另有 $it 个要点" }).joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth().widthIn(max = READING_WIDTH)) {
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
                        is StudyRow.Group -> QuizGroupView(row.group, kp, c, view, thread) { c.tutor.model.onSent(thread, "answer", null); pinned = true }
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
        Column(Modifier.fillMaxWidth().widthIn(max = READING_WIDTH).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending != null) Text(
                "现在问不影响已通过，但这题不算稳固证据",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary,
            )
            val sample = ui.ttft.lastOrNull { it.threadId == thread }
            if (sample != null && !imeOpen) Text(
                "上一条首字 ${sample.ms / 100 / 10.0} 秒",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 键盘弹出时收起两个大按钮，把空间让给对话。
            if (!imeOpen) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // 服务端只在需要时给特殊文字（「换个例子再讲」）；还没讲过、线程里什么都没有时按钮就叫「开始讲解」。
                val nextText = when {
                    tv.nextLabel == "换个例子再讲" -> "换个例子再讲"
                    !kp.explained && tv.items.isEmpty() && groups.isEmpty() -> "开始讲解"
                    else -> "下一步"
                }
                Button(onClick = { next(false) }, enabled = ui.connected && !tv.busy && pending == null,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(nextText) }
                OutlinedButton(onClick = { next(true) }, enabled = ui.connected && !tv.busy && pending == null,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("出题") }
            }
            // 输入框始终可用（讲解进行中也能打字，键盘不会被收起）；只有「发送」会按状态禁用。
            // 组字回车只上屏、不发送；硬件键盘/桌面回车发送，Shift+回车换行；手机软键盘用「发送」键（§7.6）。
            ComposerField(
                value = draft, onValueChange = { draft = it }, onSend = { send(it) }, gate = enterGate,
                placeholder = "追问…", maxLines = 4, canSend = ui.connected && !tv.busy, softSend = true, enterSends = true,
            )
        }
    }
}

private sealed interface StudyRow {
    val key: String
    data class Message(val item: ChatItem) : StudyRow { override val key get() = item.key }
    data class Group(val group: QuizGroup) : StudyRow { override val key get() = "g:${group.groupId}" }
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
