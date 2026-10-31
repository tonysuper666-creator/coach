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
import androidx.compose.ui.platform.LocalFocusManager
import com.coach.ui.input.EnterGate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.coach.client.*

/** 阅读栏最大宽度：内屏/桌面宽窗口下正文不拉成一整行。 */
private val READING_WIDTH = 760.dp

@Composable
fun StudyScreen(c: CoachController, kp: KpRow, view: CourseView, ui: TutorUiState, autoStart: Boolean, onBack: (() -> Unit)?) {
    val thread = threadIdFor(kp.id)
    val tv = ui.threads[thread] ?: ThreadView()
    val groups = view.quizGroups(thread)
    val pending = groups.lastOrNull { !it.submitted }
    var draft by remember(kp.id) { mutableStateOf(TextFieldValue("")) }
    val enterGate = remember(kp.id) { EnterGate() }
    val focus = LocalFocusManager.current
    var opened by remember(kp.id) { mutableStateOf(false) }
    var started by remember(kp.id) { mutableStateOf(false) }
    var pinned by remember(kp.id) { mutableStateOf(true) }
    var followingOutput by remember(kp.id) { mutableStateOf(false) }

    fun open() = c.tutor.open(thread, c.courseId, kp.id, kp.conceptIds, kp.title)
    fun fail() = c.tutor.model.onFailure(thread, "Mac 未连接，这一步没有发出去。")
    fun next(test: Boolean) {
        pinned = true
        focus.clearFocus()
        if (!open()) return fail()
        c.tutor.model.onSent(thread, if (test) "test" else "next", null)
        if (!c.tutor.next(thread, c.courseId, c.packFor(kp.id), test)) fail()
    }
    fun send(fromIme: Boolean = false) {
        if (draft.composition != null || (fromIme && !enterGate.shouldSend(true, true, false, false))) return
        val text = draft.text.trim()
        if (text.isEmpty() || tv.busy || !ui.connected) return
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
        // 钉底：停在底部时，流式内容增长会跟着走；用户往上翻就松开，翻回底部再钉住。
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canForward) -> if (scrolling && !followingOutput) pinned = !canForward }
        }
        val lastLength = (tv.items.lastOrNull() as? ChatItem.Tutor)?.message?.text?.length ?: 0
        LaunchedEffect(tv.items.size, lastLength) {
            if (pinned && tv.items.isNotEmpty()) {
                // A follow-up can arrive after a graded group. Keep its text visible
                // instead of scrolling past it into the older question cards.
                followingOutput = true
                try {
                    androidx.compose.runtime.withFrameNanos { }
                    val index = tv.items.lastIndex
                    val layout = listState.layoutInfo
                    val height = layout.viewportEndOffset - layout.viewportStartOffset
                    val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                    listState.scrollToItem(index, maxOf(0, (item?.size ?: 0) - height))
                } finally { followingOutput = false }
            }
        }
        LaunchedEffect(tv.poolText, tv.error, tv.notice) {
            if (pinned && listOf(tv.poolText, tv.error, tv.notice).any { it != null } && listState.layoutInfo.totalItemsCount > 0) {
                listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
            }
        }
        // 新题组出现时把它的开头放在最上面，方便从第 1 题读起。
        LaunchedEffect(groups.size) {
            if (groups.isNotEmpty()) listState.animateScrollToItem(tv.items.size + groups.size - 1)
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
            items(tv.items, key = { it.key }) { item ->
                when (item) {
                    is ChatItem.Learner -> LearnerBubble(item.text)
                    is ChatItem.Tutor -> TutorBubble(item.message, c.math) { c.tutor.cancel(item.message.id) }
                }
            }
            items(groups, key = { "g:${it.groupId}" }) { group ->
                QuizGroupView(group, kp, c, view, thread) { c.tutor.model.onSent(thread, "answer", null) }
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
            if (sample != null) Text(
                "上一条首字 ${sample.ms / 100 / 10.0} 秒",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val nextText = tv.nextLabel ?: if (kp.explained || tv.items.isNotEmpty()) "下一步" else "开始讲解"
                Button(onClick = { next(false) }, enabled = ui.connected && !tv.busy && pending == null,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(nextText) }
                OutlinedButton(onClick = { next(true) }, enabled = ui.connected && !tv.busy && pending == null,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("出题") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // 输入框始终可用（讲解进行中也能打字，键盘不会被收起）；只有「发送」会按状态禁用。
                OutlinedTextField(
                    draft, { value ->
                        enterGate.onEdit(draft.composition != null, value.composition != null)
                        draft = value
                    },
                    Modifier.weight(1f).onPreviewKeyEvent { event ->
                        // 中文输入法组字时按回车是「上屏」，不是发送：此时 composition 非空，放行给输入法。
                        if (event.key == Key.Enter && enterGate.shouldSend(true, event.type == KeyEventType.KeyDown, event.isShiftPressed, draft.composition != null)) { send(); true } else false
                    },
                    placeholder = { Text("追问…") }, maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(fromIme = true) }),
                )
                Button(onClick = { send() }, enabled = ui.connected && !tv.busy && draft.text.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) { Text("发送") }
            }
        }
    }
}

@Composable
private fun ProgressLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
