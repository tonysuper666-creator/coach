package com.coach.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.coach.client.CourseView
import com.coach.client.examCountdownText
import com.coach.domain.today
import com.coach.client.KpRow
import com.coach.client.KpStatus
import com.coach.client.TutorUiState

@Composable
fun TodayScreen(
    c: CoachController, view: CourseView?, ui: TutorUiState, selectedKpId: String?,
    onOpenReview: () -> Unit, onOpen: (kpId: String, startNow: Boolean) -> Unit, weekActions: WeekActions? = null,
) {
    if (view == null) return CenterMessage("正在加载课程…")
    val now = rememberCourseNow(c)
    val kps = view.kps
    if (kps.isEmpty()) {
        return CenterMessage(
            if (ui.connected) "课程「${c.courseId}」里还没有知识点（课程内容尚未装载，或课程号不对）。" else "还没有同步到课程内容，且 Mac 未连接。",
        )
    }
    val decision = c.learningTarget(view)
    val target = decision?.kp
    val passed = kps.count { it.status == KpStatus.PASSED || it.status == KpStatus.SOLID }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("今天", style = MaterialTheme.typography.headlineMedium)
                    if (target != null) {
                        ContinueCard(target, decision.resume) { if (weekActions != null) weekActions.start(view, decision) else onOpen(target.id, true) }
                    } else {
                        Text("今天的都做完了。可以到路线浏览其他节。", style = MaterialTheme.typography.titleMedium)
                    }
                    // 今天页也能直接「选一周来学」：没选范围时各周铺开，学完一周时给「换下一周」。
                    if (weekActions != null) {
                        WeekPicker(view, weekActions, ui.connected, directWhenUnscoped = true)
                        WeekSummary(view, weekActions, ui.connected)
                    }
                    // 选周 / 开始学习的失败都由 WeekPicker 里的同一行错误显示，这里不重复。
                    ReviewCard(view.dueReviews(now).size, onOpenReview)
                    Text(view.examCountdownText(now), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

/** 知识点名常带「【W1·Ch1–2】」这样的课次标签：显示时标签变成一行说明（[sourceLine]），正文放主行，界面上不出现【】。 */
private val SCOPE_SEPARATOR = Regex("\\s+[｜|]\\s+(?=【)")
internal fun additionalTitleScope(title: String): List<String> = title.split(SCOPE_SEPARATOR).drop(1).map { it.trim() }.filter { it.isNotBlank() }
/** [splitTitle] 的标签部分 → 一行人话说明（「W3·slides」→「第 3 周 · 课件」）。 */
internal fun sourceLine(tag: String): String = if (tag.isBlank()) "" else com.coach.client.titleSource("【$tag】")
internal fun splitTitle(title: String): Pair<String, String> {
    val first = title.split(SCOPE_SEPARATOR, limit = 2).first().trim()
    val match = Regex("^【([^】]{1,24})】\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).find(first)
    val tag = match?.groupValues?.get(1).orEmpty()
    // The tag part is a source note, never the title; every 【…】 left in the title is removed before display.
    return tag to com.coach.client.displayTitle(match?.groupValues?.get(2) ?: first)
}

@Composable
internal fun ContinueCard(kp: KpRow, resume: Boolean, onClick: () -> Unit) {
    val (tag, main) = splitTitle(kp.title)
    Surface(
        color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.extraLarge, onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).then(Modifier.testTag("today-continue")),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("接着学", style = MaterialTheme.typography.labelLarge)
            Text(if (resume) "继续 · $main" else "开始 · $main", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
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
                Text(if (due == 0) "今天没有到期的复习" else "$due 节到期", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        // Tag and status share the first line, so the full first-anchor title gets the whole card width below them (no ellipsis, fewer wrapped lines).
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val (tag, main) = splitTitle(kp.title)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sourceLine(tag), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusChip(kp.status.symbol + " " + kp.status.label, statusColor(kp.status))
            }
            Text(main, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun statusColor(status: KpStatus): Color = kpStatusColor(status, MaterialTheme.colorScheme, successColor())

/** 状态标签的字色。「未学」用 onSurfaceVariant（outline 在深色下压在同色底上读不清）；「在学」用 onSurface。 */
internal fun kpStatusColor(status: KpStatus, scheme: ColorScheme, success: Color): Color = when (status) {
    KpStatus.NEW -> scheme.onSurfaceVariant
    KpStatus.IN_PROGRESS -> scheme.onSurface
    KpStatus.PASSED, KpStatus.SOLID -> success
    KpStatus.NEEDS_REPAIR -> scheme.error
}
