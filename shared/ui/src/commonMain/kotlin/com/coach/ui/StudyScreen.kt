package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.coach.client.*
import com.coach.ui.chat.AnchoredChatList
import com.coach.ui.chat.ChatListState
import com.coach.ui.chat.jumpToTail
import com.coach.ui.chat.scrollItemToTop
import com.coach.ui.input.ComposerField
import kotlinx.coroutines.launch

/** 阅读栏最大宽度：内屏/桌面宽窗口下正文不拉成一整行。 */
private val READING_WIDTH = 600.dp

@Composable
fun StudyScreen(c: CoachController, kp: KpRow, view: CourseView, ui: TutorUiState, autoStart: Boolean, onBack: (() -> Unit)?) {
    val thread = threadIdFor(kp.id)
    val tv = ui.threads[thread] ?: ThreadView()
    val groups = view.quizGroups(thread)
    val chosen = remember(kp.id) { mutableStateMapOf<String, String>() }
    val pending = groups.lastOrNull { !it.submitted }
    val additionalScope = remember(kp.title) { additionalTitleScope(kp.title) }
    var showScope by remember(kp.id) { mutableStateOf(false) }
    var draft by remember(kp.id) { mutableStateOf(TextFieldValue("")) }
    val chat = remember(kp.id) { ChatListState() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var opened by remember(kp.id) { mutableStateOf(false) }
    var started by remember(kp.id) { mutableStateOf(false) }
    val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

    fun open() = c.tutor.open(thread, c.courseId, kp.id, kp.conceptIds, kp.title)
    fun fail() = c.tutor.model.onFailure(thread, "Mac 未连接，这一步没有发出去。")
    fun next(test: Boolean) {
        focus.clearFocus()
        if (!open()) return fail()
        scope.launch { chat.jumpToTail() }
        c.tutor.model.onSent(thread, if (test) "test" else "next", null)
        if (!c.tutor.next(thread, c.courseId, c.packFor(kp.id), test)) fail()
    }
    /** 返回 true 才清空草稿：没连上或 Mac 在回答时，字留在输入框里。 */
    fun send(text: String): Boolean {
        if (tv.busy || !ui.connected) return false
        // 发出后收起键盘：回答和「下一步 / 出题」按钮马上可见。
        focus.clearFocus()
        if (!open()) { fail(); return false }
        scope.launch { chat.jumpToTail() }
        c.tutor.model.onSent(thread, "ask", text)
        if (c.tutor.ask(thread, c.courseId, text, c.packFor(kp.id))) return true
        fail()
        return false
    }

    // 每次（重新）连上都声明一次线程；服务端按 kpId 幂等。
    LaunchedEffect(kp.id, ui.connected) {
        if (!ui.connected) { opened = false; return@LaunchedEffect }
        if (!opened) opened = open()
        if (autoStart && !started && opened && tv.items.isEmpty() && pending == null) { started = true; next(false) }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = READING_WIDTH).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 今天") }
                Column(Modifier.weight(1f)) {
                    val (tag, main) = splitTitle(kp.title)
                    Text(main, style = MaterialTheme.typography.titleMedium)
                    val sub = listOf(tag, kp.reference.takeIf { it.isNotBlank() }?.let { "本次参考：$it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showScope = !showScope }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) {
                            Text(if (showScope) "收起学习范围" else sub.ifBlank { "学习范围" } + if (additionalScope.isNotEmpty()) " · ${additionalScope.size + 1} 项 ▾" else " · 范围 ▾",
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        }
                        StatusChip(kp.status.label, MaterialTheme.colorScheme.primary)
                    }
                    if (showScope) {
                        Text(main, style = MaterialTheme.typography.bodyMedium)
                        additionalScope.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
        HorizontalDivider()

        // 讲解/追问消息和题组按「发生的先后」交错：消息出现时记下当时已有几组题，排在那几组之后。
        val anchors = remember(kp.id) { c.quizMessageAnchors.getOrPut(thread) { HashMap() } }
        val rows = remember(tv.items, groups) {
            val placed = tv.items.map { item -> item to anchors.getOrPut(item.key) { groups.size }.coerceAtMost(groups.size) }
            buildList<StudyRow> {
                for (g in 0..groups.size) {
                    placed.filter { it.second == g }.forEach { add(StudyRow.Message(it.first)) }
                    groups.getOrNull(g)?.let { group ->
                        group.cards.forEachIndexed { index, card -> add(StudyRow.Question(group, index, card)) }
                        add(StudyRow.GroupFooter(group))
                    }
                }
            }
        }
        val empty = tv.items.isEmpty() && groups.isEmpty()
        val entries = remember(rows, empty, kp.explained, tv.notice, tv.poolText, tv.error) {
            rows + listOfNotNull(
                if (empty) StudyRow.Text("hint", if (kp.explained) "这个知识点讲过了。点「下一步」继续出题，或直接提问。" else "还没开始。点下面的「开始讲解」。", StudyTone.Quiet) else null,
                tv.notice?.let { StudyRow.Text("notice", it, StudyTone.Accent) },
                tv.poolText?.let { StudyRow.Text("pool", it, StudyTone.Progress) },
                tv.error?.let { StudyRow.Text("error", it, StudyTone.Error) },
            )
        }
        // 新题组出现时把第 1 题放在最上面，方便从头读起；其余时候停在底部时跟着流式内容走，上翻后不被拉动。
        LaunchedEffect(groups.size) {
            val index = entries.indexOfLast { it is StudyRow.Question && it.index == 0 }
            if (index >= 0) chat.scrollItemToTop(index, entries.size)
        }
        BoxWithConstraints(Modifier.weight(1f).widthIn(max = READING_WIDTH).fillMaxWidth()) {
            AnchoredChatList(
                items = entries, key = { it.key }, state = chat, windowKey = constraints.maxWidth,
                modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), spacing = 14.dp,
            ) { row ->
                when (row) {
                    is StudyRow.Message -> when (val item = row.item) {
                        is ChatItem.Learner -> LearnerBubble(item.text)
                        is ChatItem.Tutor -> TutorBubble(item.message, c.math) { c.tutor.cancel(item.message.id) }
                    }
                    is StudyRow.Question -> QuizCardView(row.index + 1, row.group.cards.size, row.card, row.group.submitted, chosen[row.card.attemptId], c.math) { letter ->
                        if (!row.group.submitted) chosen[row.card.attemptId] = letter
                    }
                    is StudyRow.GroupFooter -> QuizGroupFooter(row.group, kp, c, view, thread, chosen) { c.tutor.model.onSent(thread, "answer", null) }
                    is StudyRow.Text -> when (row.tone) {
                        StudyTone.Quiet -> Text(row.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StudyTone.Accent -> Text(row.text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        StudyTone.Progress -> ProgressLine(row.text)
                        StudyTone.Error -> Text(row.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.widthIn(max = READING_WIDTH).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending != null) Text(
                "现在问不影响已通过，但这题不算稳固证据",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary,
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
            // 组字回车、Shift+回车、手机回车换行都在 ComposerField 里（DESIGN §7.6）；草稿归本屏持有，折叠/展开不丢。
            ComposerField(draft, { draft = it }, onSend = ::send, placeholder = "追问…", sendEnabled = ui.connected && !tv.busy, maxLines = 4)
        }
    }
}

private sealed interface StudyRow {
    val key: String
    data class Message(val item: ChatItem) : StudyRow { override val key get() = item.key }
    data class Question(val group: QuizGroup, val index: Int, val card: QuizCard) : StudyRow { override val key get() = "q:${card.attemptId}" }
    data class GroupFooter(val group: QuizGroup) : StudyRow { override val key get() = "g:${group.groupId}" }
    data class Text(override val key: String, val text: String, val tone: StudyTone) : StudyRow
}

private enum class StudyTone { Quiet, Accent, Progress, Error }

@Composable
private fun ProgressLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
