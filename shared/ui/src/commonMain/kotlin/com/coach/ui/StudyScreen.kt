package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coach.client.*

@Composable
fun StudyScreen(c: CoachController, kp: KpRow, view: CourseView, ui: TutorUiState, autoStart: Boolean, onBack: () -> Unit) {
    val thread = threadIdFor(kp.id)
    val tv = ui.threads[thread] ?: ThreadView()
    val groups = view.quizGroups(thread)
    val pending = groups.lastOrNull { !it.submitted }
    var draft by remember(kp.id) { mutableStateOf("") }
    var opened by remember(kp.id) { mutableStateOf(false) }
    var started by remember(kp.id) { mutableStateOf(false) }

    fun open() = c.tutor.open(thread, c.courseId, kp.id, kp.conceptIds, kp.title)
    fun fail() = c.tutor.model.onFailure(thread, "Mac 未连接，这一步没有发出去。")
    fun next(test: Boolean) {
        if (!open()) return fail()
        c.tutor.model.onSent(thread, if (test) "test" else "next", null)
        if (!c.tutor.next(thread, c.courseId, c.packFor(kp.id), test)) fail()
    }

    // 每次（重新）连上都声明一次线程；服务端按 kpId 幂等。
    LaunchedEffect(kp.id, ui.connected) {
        if (!ui.connected) { opened = false; return@LaunchedEffect }
        if (!opened) opened = open()
        if (autoStart && !started && opened && tv.items.isEmpty() && pending == null) { started = true; next(false) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 今天") }
            Text(kp.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
            StatusChip(kp.status.label, MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider()
        val listState = rememberLazyListState()
        val lastLength = tv.items.lastOrNull().let { (it as? ChatItem.Tutor)?.message?.text?.length ?: 0 }
        LaunchedEffect(tv.items.size, groups.size, lastLength) {
            val count = tv.items.size + groups.size
            if (count > 0) listState.animateScrollToItem(count)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                QuizGroupView(group, kp, c, view, thread) {
                    c.tutor.model.onSent(thread, "answer", null)
                }
            }
            tv.poolText?.let { text -> item { ProgressLine(text) } }
            tv.error?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                OutlinedTextField(
                    draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("追问…") },
                    maxLines = 3, enabled = !tv.busy,
                )
                Button(onClick = {
                    val text = draft.trim()
                    if (text.isEmpty()) return@Button
                    if (!open()) return@Button fail()
                    c.tutor.model.onSent(thread, "ask", text)
                    if (c.tutor.ask(thread, c.courseId, text, c.packFor(kp.id))) draft = "" else fail()
                }, enabled = ui.connected && !tv.busy && draft.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) { Text("发送") }
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
