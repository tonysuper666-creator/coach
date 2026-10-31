package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
