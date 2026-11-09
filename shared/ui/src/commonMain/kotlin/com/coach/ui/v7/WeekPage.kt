package com.coach.ui.v7

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coach.client.*
import com.coach.ui.*
import com.coach.ui.theme.CoachTheme

@Composable
internal fun WeekDirectory(view: CourseView, active: Int?, onWeek: ((Int) -> Unit)?) {
    Text("周目录", style = MaterialTheme.typography.labelMedium, color = CoachTheme.colors.fgSecondary)
    Spacer(Modifier.height(12.dp))
    view.weeks.forEach { week ->
        val points = view.weekKps(week)
        val passed = points.count { it.status in setOf(KpStatus.PASSED, KpStatus.SOLID) }
        Surface(color = if (active == week) CoachTheme.colors.surface1 else androidx.compose.ui.graphics.Color.Transparent,
            shadowElevation = if (active == week) 2.dp else 0.dp, shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().then(if (onWeek != null) Modifier.clickable { onWeek(week) } else Modifier)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(view.weekLabel(week), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(if (active == week) "在读" else if (points.isNotEmpty() && passed == points.size) "已读" else "$passed/${points.size}",
                    style = MaterialTheme.typography.labelMedium, color = CoachTheme.colors.fgSecondary)
            }
        }
    }
}

/** The selected week uses the same ledger, callbacks and labels as Today; only its presentation changes. */
@Composable
internal fun WeekPage(c: CoachController, view: CourseView, ui: TutorUiState, actions: WeekActions, onOpen: (String, Boolean) -> Unit) {
    val week = view.projection.studyScope?.week ?: return
    val points = view.weekKps(week)
    val passed = points.count { it.status in setOf(KpStatus.PASSED, KpStatus.SOLID) }
    val target = view.weekTarget
    val title = points.firstOrNull()?.module?.ifBlank { null } ?: "第 $week 周"
    Desk(crumb = "${c.courseId} · 第 $week 周", directory = { WeekDirectory(view, week, if (ui.connected && !actions.busy) actions::select else null) }, margin = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PaperCard {
                Text("第 $week 周", style = MaterialTheme.typography.labelMedium)
                Text("已通过 $passed / ${points.size} 个知识点", style = MaterialTheme.typography.bodyMedium)
                WeekSummary(view, actions, ui.connected)
            }
            target?.takeIf { it.reference.isNotBlank() }?.let { kp -> PaperCard {
                Text("本次参考", style = MaterialTheme.typography.labelMedium)
                Text(kp.reference, style = MaterialTheme.typography.bodyMedium)
            } }
        }
    }, footer = {
        val previous = view.weeks.lastOrNull { it < week }
        TextButton(onClick = { previous?.let(actions::select) }, enabled = previous != null && ui.connected && !actions.busy) { Text(previous?.let { "‹ 第 $it 周" }.orEmpty()) }
        Text("第 $week 周", style = MaterialTheme.typography.labelMedium, fontFamily = LocalV7Fonts.current.numbers)
        val next = view.weeks.firstOrNull { it > week }
        TextButton(onClick = { next?.let(actions::select) }, enabled = next != null && ui.connected && !actions.busy) { Text(next?.let { "第 $it 周 ›" }.orEmpty()) }
    }) {
        val metrics = LocalDeskMetrics.current
        Column(Modifier.widthIn(max = coach.design.tokens.CoachV7.Layout.colw + metrics.horizontalPadding * 2).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = metrics.horizontalPadding).padding(top = metrics.topPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            PageHeader("第 $week 周 · 本周", title, meta = "已通过 $passed / ${points.size} 个知识点", week = week)
            PaperCard(Modifier.fillMaxWidth(), contentSpacing = 0.dp, contentPadding = PaddingValues(horizontal = 16.dp)) {
                points.forEachIndexed { index, kp ->
                    if (index > 0) HorizontalDivider(color = CoachTheme.colors.borderSubtle)
                    Row(Modifier.fillMaxWidth().clickable { onOpen(kp.id, false) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("$week.${index + 1}", style = MaterialTheme.typography.labelMedium, fontFamily = LocalV7Fonts.current.numbers)
                        Text(splitTitle(kp.title).second, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(fontSize = coach.design.tokens.CoachV7.Size.row))
                        Text(kp.status.label, style = MaterialTheme.typography.labelMedium, color = statusColor(kp.status))
                    }
                }
            }
            target?.let { kp -> PaperCard(Modifier.fillMaxWidth().clickable { onOpen(kp.id, true) }) {
                Text(if (view.isResume(kp)) "回到（没学完）" else "开始下一个", style = MaterialTheme.typography.labelMedium)
                Text(splitTitle(kp.title).second, style = MaterialTheme.typography.titleMedium)
            } }
            WeekPicker(view, actions, ui.connected)
            if (!metrics.inner) WeekSummary(view, actions, ui.connected)
        }
    }
}
