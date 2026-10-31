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
import com.coach.client.KpStatus
import com.coach.client.TutorUiState

@Composable
fun TodayScreen(c: CoachController, view: CourseView?, ui: TutorUiState, onOpen: (kpId: String, startNow: Boolean) -> Unit) {
    if (view == null) return CenterMessage("正在读取本地账本…")
    val kps = view.kps
    if (kps.isEmpty()) {
        return CenterMessage(
            if (ui.connected) "这门课还没有知识点（课程内容尚未装载）。" else "还没有同步到课程内容，且 Mac 未连接。",
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
                    Button(
                        onClick = { onOpen(target.id, true) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) { Text("继续：${target.title}", style = MaterialTheme.typography.titleMedium) }
                } else {
                    Text("所有知识点都已通过。", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(4.dp))
                Text("全部知识点", style = MaterialTheme.typography.titleSmall)
            }
        }
        items(kps, key = { it.id }) { kp ->
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onOpen(kp.id, false) },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(kp.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    StatusChip(kp.status.label, statusColor(kp.status))
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: KpStatus): Color = when (status) {
    KpStatus.NEW -> MaterialTheme.colorScheme.outline
    KpStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
    KpStatus.PASSED -> Color(0xFF2E7D32)
    KpStatus.SOLID -> Color(0xFF1B5E20)
    KpStatus.NEEDS_REPAIR -> MaterialTheme.colorScheme.error
}
