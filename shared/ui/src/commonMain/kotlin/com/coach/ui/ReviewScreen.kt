package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coach.client.CourseView

/**
 * 复习：先看队列，点「开始」才进入该知识点的题。队列来自账本里的复习日程（到期或逾期）。
 * 「开始」打开该知识点的学习线程（出题仍由导师状态机发）；服务端还没有专门的复习出题流程，所以这里不假装有。
 */
@Composable
fun ReviewScreen(c: CoachController, view: CourseView, onOpenKp: (kpId: String, startNow: Boolean) -> Unit, onBack: (() -> Unit)?) {
    val due = view.dueReviews(c.nowMillis())
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 今天") }
            Text("复习", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()
        OfflinePractice(c)
        if (due.isEmpty()) {
            CenterMessage("今天没有到期的复习。")
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${due.size} 个知识点到期", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(due, key = { it.kp.id }) { item ->
                val (tag, main) = splitTitle(item.kp.title)
                Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            if (tag.isNotBlank()) Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(main, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(if (item.overdueDays == 0) "今天到期" else "逾期 ${item.overdueDays} 天", style = MaterialTheme.typography.labelMedium, color = if (item.overdueDays == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        }
                        Button(onClick = { onOpenKp(item.kp.id, false) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("开始") }
                    }
                }
            }
        }
    }
}

/** Independent from the learning thread: a downloaded review never changes Continue. */
@Composable
private fun OfflinePractice(c: CoachController) {
    val revision by c.sync.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(c.nowMillis()) }
    LaunchedEffect(c) { while (true) { tick = c.nowMillis(); delay(1000) } }
    val groups = remember(revision,tick) { c.sync.offlineGroups(c.courseId,tick) }
    var active by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    val group = groups.firstOrNull { it.groupId == active }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("离线练习", style = MaterialTheme.typography.titleMedium)
        if (group == null) {
            Text("${groups.filter { !it.submitted }.sumOf { it.cards.size }} 题可做 · 下载后无需连接 Mac")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { active = groups.firstOrNull { !it.submitted }?.groupId; chosen = emptyMap() },
                    enabled = groups.any { !it.submitted }, modifier = Modifier.heightIn(min = 56.dp)) { Text("复习 ${groups.firstOrNull { !it.submitted }?.cards?.size ?: 3} 题") }
                OutlinedButton(onClick = {
                    c.tutor.telemetry?.record("tap", "offline_pack")
                    downloading = true; error = null
                    scope.launch {
                        try { withContext(Dispatchers.IO) { c.sync.downloadOfflinePack(c.courseId) } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { c.tutor.telemetry?.record("error", "offline_pack"); error = "Mac 未连接，下载未完成；已有题仍可练习。" }
                        finally { downloading = false }
                    }
                },enabled = !downloading,modifier = Modifier.heightIn(min = 56.dp)) { Text(if (downloading) "正在下载…" else "下载练习包") }
            }
            groups.lastOrNull { it.submitted }?.let { last ->
                TextButton(onClick = { active = last.groupId }) { Text("查看上组结果") }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(group.cards,key = { it.attemptId }) { card ->
                    QuizCardView(group.cards.indexOf(card)+1,group.cards.size,card,group.submitted,chosen[card.attemptId],c.math) {
                        c.tutor.telemetry?.record("tap", "select_option")
                        chosen = chosen + (card.attemptId to it)
                    }
                }
                item {
                    if (!group.submitted) Button(onClick = {
                        c.tutor.telemetry?.record("tap", "answer")
                        try { c.sync.submitOfflineGroup(c.courseId,group.groupId,chosen,c.nowMillis()); error = null }
                        catch (_: IllegalArgumentException) { c.tutor.telemetry?.record("error", "offline_pack"); error = "题组已提交或已过期，请重新下载。" }
                    },enabled = group.cards.all { chosen[it.attemptId] != null },modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("提交本组答案") }
                    else TextButton(onClick = { active = null; chosen = emptyMap() }) { Text("返回练习队列") }
                }
            }
        }
        error?.let { Text(it,color = MaterialTheme.colorScheme.error) }
    }
}
