package com.coach.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.coach.client.CourseView
import com.coach.client.KpRow
import com.coach.client.KpStatus
import com.coach.client.TutorUiState

@Composable
fun TodayScreen(c: CoachController, view: CourseView?, ui: TutorUiState, selectedKpId: String?, onOpen: (kpId: String, startNow: Boolean) -> Unit) {
    if (view == null) return CenterMessage("正在读取本地账本…")
    val kps = view.kps
    if (kps.isEmpty()) {
        return CenterMessage(
            if (ui.connected) "课程「${c.courseId}」里还没有知识点（课程内容尚未装载，或课程号不对）。" else "还没有同步到课程内容，且 Mac 未连接。",
        )
    }
    val target = view.continueTarget
    val passed = kps.count { it.status == KpStatus.PASSED || it.status == KpStatus.SOLID }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("今天", style = MaterialTheme.typography.headlineMedium)
                Text("已通过 $passed / ${kps.size} 个知识点", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (target != null) {
                    ContinueCard(target) { onOpen(target.id, true) }
                } else {
                    Text("所有知识点都已通过。", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(4.dp))
                Text("全部知识点", style = MaterialTheme.typography.titleSmall)
            }
        }
        kps.forEachIndexed { index, kp ->
            if (kp.module.isNotBlank() && kp.module != kps.getOrNull(index - 1)?.module) item(key = "m:${kp.module}") {
                Text(kp.module, Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            item(key = kp.id) { KpRowView(kp, kp.id == selectedKpId) { onOpen(kp.id, false) } }
        }
    }
}

/** 知识点名常带「【W1·Ch1–2】」这样的课次标签：标签放小字，正文放主行。 */
internal fun splitTitle(title: String): Pair<String, String> {
    val match = Regex("^【([^】]{1,24})】\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).find(title.trim()) ?: return "" to title
    return match.groupValues[1] to match.groupValues[2]
}

@Composable
private fun ContinueCard(kp: KpRow, onClick: () -> Unit) {
    val (tag, main) = splitTitle(kp.title)
    Surface(
        color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.extraLarge, onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (tag.isBlank()) "继续" else "继续 · $tag", style = MaterialTheme.typography.labelLarge)
            Text(main, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun KpRowView(kp: KpRow, selected: Boolean, onClick: () -> Unit) {
    Surface(
        tonalElevation = if (selected) 6.dp else 1.dp,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val (tag, main) = splitTitle(kp.title)
            Column(Modifier.weight(1f)) {
                if (tag.isNotBlank()) Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(main, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            StatusChip(kp.status.label, statusColor(kp.status))
        }
    }
}

@Composable
private fun statusColor(status: KpStatus): Color = when (status) {
    KpStatus.NEW -> MaterialTheme.colorScheme.outline
    KpStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
    KpStatus.PASSED -> successColor()
    KpStatus.SOLID -> successColor()
    KpStatus.NEEDS_REPAIR -> MaterialTheme.colorScheme.error
}
