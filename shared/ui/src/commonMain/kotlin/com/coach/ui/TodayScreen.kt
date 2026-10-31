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
fun TodayScreen(
    c: CoachController, view: CourseView?, ui: TutorUiState, selectedKpId: String?,
    onOpenReview: () -> Unit, onOpen: (kpId: String, startNow: Boolean) -> Unit,
) {
    if (view == null) return CenterMessage("正在加载课程…")
    val kps = view.kps
    if (kps.isEmpty()) {
        return CenterMessage(
            if (ui.connected) "课程「${c.courseId}」里还没有知识点（课程内容尚未装载，或课程号不对）。" else "还没有同步到课程内容，且 Mac 未连接。",
        )
    }
    val target = view.continueTarget
    val passed = kps.count { it.status == KpStatus.PASSED || it.status == KpStatus.SOLID }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("今天", style = MaterialTheme.typography.headlineMedium)
                    Text("已通过 $passed / ${kps.size} 个知识点", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (target != null) {
                        ContinueCard(target) { onOpen(target.id, true) }
                    } else {
                        Text("所有知识点都已通过。", style = MaterialTheme.typography.titleMedium)
                    }
                    val now = c.nowMillis()
                    ReviewCard(view.dueReviews(now).size, onOpenReview)
                    // 考前 7 天内显示倒计时；没设考试日就不显示（不猜日期）。
                    view.daysToExam(now)?.takeIf { it <= 7 }?.let { days ->
                        Text(if (days == 0) "今天考试" else "距考试还有 $days 天", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
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
}

/** 知识点名常带「【W1·Ch1–2】」这样的课次标签：标签放小字，正文放主行。 */
private val SCOPE_SEPARATOR = Regex("\\s+[｜|]\\s+(?=【)")
internal fun additionalTitleScope(title: String): List<String> = title.split(SCOPE_SEPARATOR).drop(1).map { it.trim() }.filter { it.isNotBlank() }
internal fun splitTitle(title: String): Pair<String, String> {
    val first = title.split(SCOPE_SEPARATOR, limit = 2).first().trim()
    val match = Regex("^【([^】]{1,24})】\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).find(first) ?: return "" to first
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
            Text(main, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 今天页的第二张卡：到期复习。没有到期时如实写没有，点进去也只是空队列。 */
@Composable
private fun ReviewCard(due: Int, onClick: () -> Unit) {
    Surface(
        tonalElevation = 2.dp, shape = MaterialTheme.shapes.large, onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("到期复习", style = MaterialTheme.typography.titleMedium)
                Text(if (due == 0) "今天没有到期的复习" else "$due 个知识点到期", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (due > 0) StatusChip("$due", MaterialTheme.colorScheme.secondary)
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
                Text(main, style = MaterialTheme.typography.bodyLarge)
            }
            StatusChip(kp.status.label, statusColor(kp.status))
        }
    }
}

@Composable
internal fun statusColor(status: KpStatus): Color = kpStatusColor(status, MaterialTheme.colorScheme, successColor())

/** 状态标签的字色。「未开始」用 onSurfaceVariant（outline 在深色下压在同色底上读不清）；「学习中」用 secondary（primary 在淡底上只有 4.1:1）。 */
internal fun kpStatusColor(status: KpStatus, scheme: ColorScheme, success: Color): Color = when (status) {
    KpStatus.NEW -> scheme.onSurfaceVariant
    KpStatus.IN_PROGRESS -> scheme.secondary
    KpStatus.PASSED, KpStatus.SOLID -> success
    KpStatus.NEEDS_REPAIR -> scheme.error
}
