package com.coach.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.coach.client.*
import com.coach.ui.input.EnterGate

/** 阅读栏最大宽度：内屏/桌面宽窗口下正文不拉成一整行。 */
private val READING_WIDTH = 760.dp

@Composable
fun StudyScreen(c: CoachController, kp: KpRow, view: CourseView, ui: TutorUiState, autoStart: Boolean, onBack: (() -> Unit)?) {
    val thread = threadIdFor(kp.id)
    val tv = ui.threads[thread] ?: ThreadView()
    val groups = view.quizGroups(thread)
    val pending = groups.lastOrNull { !it.submitted }
    var draft by remember(kp.id) { mutableStateOf(TextFieldValue("")) }
    // 与 Composer 共用同一个回车门闩：组字中、以及组字刚上屏后的一小段时间内，回车都不发送。
    val enterGate = remember(kp.id) { EnterGate() }
    var opened by remember(kp.id) { mutableStateOf(false) }
    var started by remember(kp.id) { mutableStateOf(false) }
    var pinned by remember(kp.id) { mutableStateOf(true) }
    val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

    fun open() = c.tutor.open(thread, c.courseId, kp.id, kp.conceptIds, kp.title)
    fun fail() = c.tutor.model.onFailure(thread, "Mac 未连接，这一步没有发出去。")
    fun next(test: Boolean) {
        if (!open()) return fail()
        pinned = true
        c.tutor.model.onSent(thread, if (test) "test" else "next", null)
        if (!c.tutor.next(thread, c.courseId, c.packFor(kp.id), test)) fail()
    }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun send() {
        val text = draft.text.trim()
        if (text.isEmpty() || tv.busy || !ui.connected) return
        // 发出后收起键盘：回答和「下一步 / 出题」按钮马上可见。
        focus.clearFocus()
        if (!open()) return fail()
        pinned = true
        c.tutor.model.onSent(thread, "ask", text)
        if (c.tutor.ask(thread, c.courseId, text, c.packFor(kp.id))) draft = TextFieldValue("") else fail()
    }

    // 每次（重新）连上都声明一次线程；服务端按 kpId 幂等。
    LaunchedEffect(kp.id, ui.connected) {
        if (!ui.connected) { opened = false; return@LaunchedEffect }
        if (!opened) opened = open()
        if (autoStart && !started && opened && tv.items.isEmpty() && pending == null) { started = true; next(false) }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().widthIn(max = READING_WIDTH)) {
            Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 今天") }
                Column(Modifier.weight(1f)) {
                    val (tag, main) = splitTitle(kp.title)
                    Text(main, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    val sub = listOf(tag, kp.reference.takeIf { it.isNotBlank() }?.let { "本次参考：$it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(kp.status.label, MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider()

        val listState = rememberLazyListState()
        // 讲解/追问消息和题组按「发生的先后」交错：消息出现时记下当时已有几组题，排在那几组之后。
        val anchors = remember(kp.id) { HashMap<String, Int>() }
        val rows = remember(tv.items, groups) {
            val placed = tv.items.map { item -> item to anchors.getOrPut(item.key) { groups.size } }
            buildList<StudyRow> {
                for (g in 0..groups.size) {
                    placed.filter { it.second == g }.forEach { add(StudyRow.Message(it.first)) }
                    groups.getOrNull(g)?.let { add(StudyRow.Group(it)) }
                }
            }
        }
        // 钉底：停在底部时，流式内容增长会跟着走；用户往上翻就松开，翻回底部再钉住。
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canForward) -> if (scrolling) pinned = !canForward }
        }
        val lastLength = (tv.items.lastOrNull() as? ChatItem.Tutor)?.message?.text?.length ?: 0
        LaunchedEffect(tv.items.size, lastLength, tv.poolText, tv.error, tv.notice, c.math.assets.size) {
            // 刚打开且有待答题组时不要跳到底：让「新题组出现」那条把第 1 题放在最上面。
            if (pinned && (tv.streaming || pending == null) && listState.layoutInfo.totalItemsCount > 0) {
                androidx.compose.runtime.withFrameNanos { }
                listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
                listState.scrollBy(1_000_000f)
            }
        }
        // 新题组出现时把它的开头放在最上面，方便从第 1 题读起。
        LaunchedEffect(groups.size) {
            val index = rows.indexOfLast { it is StudyRow.Group }
            if (index >= 0) listState.animateScrollToItem(index)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().widthIn(max = READING_WIDTH),
            state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (tv.items.isEmpty() && groups.isEmpty()) item {
                Text(
                    if (kp.explained) "这个知识点讲过了。点「下一步」继续出题，或直接提问。" else "还没开始。点下面的「开始讲解」。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is StudyRow.Message -> when (val item = row.item) {
                        is ChatItem.Learner -> LearnerBubble(item.text)
                        is ChatItem.Tutor -> TutorBubble(item.message, c.math) { c.tutor.cancel(item.message.id) }
                    }
                    is StudyRow.Group -> QuizGroupView(row.group, kp, c, view, thread) { c.tutor.model.onSent(thread, "answer", null) }
                }
            }
            tv.notice?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) } }
            tv.poolText?.let { text -> item { ProgressLine(text) } }
            tv.error?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // 输入框始终可用（讲解进行中也能打字，键盘不会被收起）；只有「发送」会按状态禁用。
                OutlinedTextField(
                    draft, { new -> enterGate.onEdit(draft.composition != null, new.composition != null); draft = new },
                    Modifier.weight(1f).onPreviewKeyEvent { event ->
                        // 中文输入法组字时按回车是「上屏」，不是发送：此时 composition 非空，放行给输入法。
                        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                        if (enterGate.shouldSend(true, event.type == KeyEventType.KeyDown, event.isShiftPressed, draft.composition != null)) { send(); true } else false
                    },
                    placeholder = { Text("追问…") }, maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                Button(onClick = { send() }, enabled = ui.connected && !tv.busy && draft.text.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) { Text("发送") }
            }
        }
    }
}

private sealed interface StudyRow {
    val key: String
    data class Message(val item: ChatItem) : StudyRow { override val key get() = item.key }
    data class Group(val group: QuizGroup) : StudyRow { override val key get() = "g:${group.groupId}" }
}

@Composable
private fun ProgressLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
