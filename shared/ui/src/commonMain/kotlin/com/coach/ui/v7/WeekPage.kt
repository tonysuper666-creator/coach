package com.coach.ui.v7

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.coach.client.*
import com.coach.ui.*
import com.coach.ui.theme.CoachTheme

@Composable
internal fun WeekDirectory(view: CourseView, active: Int?, onWeek: ((Int) -> Unit)?) {
    val d = LocalV7PageDetails.current
    val c = CoachTheme.colors
    Text("周目录", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelMedium, color = c.fgSecondary)
    Spacer(Modifier.height(12.dp))
    val entries = d.directory.ifEmpty { view.weeks.map { n ->
        val rows = view.weekKps(n)
        V7DirectoryEntry(n, rows.firstOrNull()?.module.orEmpty(), if (n == active) "在读" else if (rows.isNotEmpty() && rows.all { it.status in setOf(KpStatus.PASSED, KpStatus.SOLID) }) "已读" else "未读")
    } }
    entries.forEach { e ->
        Row(Modifier.fillMaxWidth().then(if (e.week == active) Modifier.paperShadow("card", c.dark).clip(RoundedCornerShape(8.dp)).background(c.surface1) else Modifier)
            .then(if (onWeek != null) Modifier.clickable { onWeek(e.week) } else Modifier).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("第 ${e.week} 周 · ${e.title}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                fontWeight = if (e.week == active) FontWeight.SemiBold else FontWeight.Normal, color = if (e.state == "未读") c.fgTertiary else c.fgPrimary, maxLines = 1)
            Text(e.state, style = MaterialTheme.typography.labelMedium, color = if (e.state == "未读") c.fgTertiary else c.fgSecondary)
        }
        Spacer(Modifier.height(2.dp))
    }
    if (d.shelf.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        Text("书架", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelMedium, color = c.fgSecondary)
        Spacer(Modifier.height(12.dp))
        d.shelf.forEach { Text(it, Modifier.padding(start = 12.dp).padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun WeekNotes(d: V7PageDetails, target: String, onSource: () -> Unit, onQuiz: () -> Unit) {
    val inner = LocalDeskMetrics.current.inner
    val cards: @Composable () -> Unit = {
        if (d.mustKnow.isNotEmpty()) PaperCard(contentSpacing = 0.dp) {
            Text("$target · 只要会这些", style = MaterialTheme.typography.labelMedium.copy(lineHeight = 16.8.sp), color = CoachTheme.colors.fgSecondary)
            Spacer(Modifier.height(12.dp))
            d.mustKnow.forEach { item ->
                Row { Text("•", Modifier.width(18.dp), style = MaterialTheme.typography.bodyMedium); Text(annotatedMarks(item), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (d.missing.isNotEmpty()) PaperCard(contentSpacing = 0.dp) {
            Text("还缺这些", style = MaterialTheme.typography.labelMedium.copy(lineHeight = 16.8.sp), color = CoachTheme.colors.fgSecondary)
            Spacer(Modifier.height(12.dp))
            d.missing.forEach { item ->
                Row { Text("○", Modifier.width(18.dp), style = MaterialTheme.typography.bodyMedium); Text(annotatedMarks(item), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(8.dp))
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperButton(onClick = onSource) { Text("回到原页") }
            PrimaryButton(onClick = onQuiz) { Text("做这一节 ${d.question?.count ?: ""} 题") }
        }
        if (d.revisit.isNotBlank()) NoteCard(d.revisitLabel, d.revisit)
    }
    if (inner) AlignedRail(listOf("week-target", null, null, "week-next"), gap = 24.dp, finalGap = 16.dp, content = cards)
    else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) { cards() }

}

@Composable
internal fun WeekPage(c: CoachController, view: CourseView, ui: TutorUiState, actions: WeekActions, onOpen: (String, Boolean) -> Unit) {
    val week = view.projection.studyScope?.week ?: return
    val points = view.weekKps(week)
    val passed = points.count { it.status in setOf(KpStatus.PASSED, KpStatus.SOLID) }
    val inProgress = points.count { it.status == KpStatus.IN_PROGRESS }
    val missing = points.count { it.status == KpStatus.NEEDS_REPAIR }
    val target = view.weekTarget
    val targetLabel = "$week.${(points.indexOfFirst { it.id == target?.id } + 1).coerceAtLeast(1)}"
    val d = LocalV7PageDetails.current
    val colors = CoachTheme.colors
    val title = points.firstOrNull()?.module?.ifBlank { null } ?: "第 $week 周"
    val weeks = d.directory.map { it.week }.ifEmpty { view.weeks }
    val select = LocalDeskSelect.current
    val railAnchors = remember(week) { MarginAnchors() }
    CompositionLocalProvider(LocalMarginAnchors provides railAnchors) {
    Desk(crumb = "${d.course.ifBlank { c.courseId }} · 第 $week 周", status = "距考 · ${if (view.projection.examDay == null) "日期未公布" else "见学习计划"}",
        backLabel = "← 书架", onBack = { select(AppTab.RESOURCES) }, directory = { WeekDirectory(view, week, if (ui.connected && !actions.busy) actions::select else null) },
        margin = {
            if (d.mustKnow.isNotEmpty()) WeekNotes(d, targetLabel, { target?.let { onOpen(it.id, false) } }, { target?.let { onOpen(it.id, true) } })
            else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                PaperCard { Text("第 $week 周 · 已通过 $passed / ${points.size}", style = MaterialTheme.typography.bodyMedium); WeekSummary(view, actions, ui.connected) }
                target?.takeIf { it.reference.isNotBlank() }?.let { NoteCard("出处", it.reference) }
            }
        }, footer = {
            val previous = weeks.lastOrNull { it < week }
            Text(previous?.let { "‹  第 $it 周" }.orEmpty(), Modifier.clickable(enabled = previous != null && ui.connected) { previous?.let(actions::select) }, style = MaterialTheme.typography.labelLarge)
            Text("第 $week 周", style = MaterialTheme.typography.labelMedium, fontFamily = LocalV7Fonts.current.numbers)
            val next = weeks.firstOrNull { it > week }
            Text(next?.let { "第 $it 周  ›" }.orEmpty(), Modifier.clickable(enabled = next != null && ui.connected) { next?.let(actions::select) }, style = MaterialTheme.typography.labelLarge)
        }) {
        val m = LocalDeskMetrics.current
        Column(Modifier.widthIn(max = coach.design.tokens.CoachV7.Layout.colw + m.horizontalPadding * 2).fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = m.horizontalPadding).padding(top = m.topPadding, bottom = 32.dp)) {
            PageHeader("第 $week 周 · 本周", title, deck = "这一周要会的 ${points.size} 件事", week = week,
                meta = d.weekStats.ifBlank { "通过 $passed · 在读 $inProgress · 还缺 $missing" })
            Spacer(Modifier.height(if (m.inner) 0.dp else 0.dp))
            PaperCard(contentSpacing = 0.dp, contentPadding = PaddingValues(horizontal = 16.dp)) {
                points.forEachIndexed { index, kp ->
                    if (index > 0) HorizontalDivider(color = if (colors.dark) Color(0xFF2A2722) else Color(0xFFEFE9DE))
                    Row(Modifier.fillMaxWidth().then(if (kp.id == target?.id) Modifier.railAnchor("week-target") else Modifier).clickable { onOpen(kp.id, false) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("$week.${index + 1}", Modifier.width(if (m.window == DeskWindow.Outer) 46.8.dp else 57.6.dp), style = MaterialTheme.typography.labelLarge,
                            color = colors.fgSecondary, fontFamily = LocalV7Fonts.current.numbers)
                        Column(Modifier.weight(1f)) {
                            Text(splitTitle(kp.title).second, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = if (m.inner) 31.45.sp else 24.65.sp,
                                fontWeight = if (kp.status == KpStatus.IN_PROGRESS) FontWeight.SemiBold else FontWeight.Normal))
                            if (m.window == DeskWindow.Outer) d.types.getOrNull(index)?.let { Spacer(Modifier.height(2.dp)); Text(it, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary) }
                        }
                        if (m.window != DeskWindow.Outer) d.types.getOrNull(index)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary) }
                        val state = when (kp.status) { KpStatus.PASSED, KpStatus.SOLID -> "✓ 已通过"; KpStatus.IN_PROGRESS -> "● 在读"; KpStatus.NEEDS_REPAIR -> "○ 还缺"; else -> "未读" }
                        Text(androidx.compose.ui.text.buildAnnotatedString {
                            append(state)
                            if (kp.status == KpStatus.IN_PROGRESS) addStyle(androidx.compose.ui.text.SpanStyle(color = colors.dangerFg), 0, 1)
                        }, Modifier.width(if (m.window == DeskWindow.Outer) 68.dp else 100.8.dp), style = MaterialTheme.typography.labelLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End, color = when (kp.status) { KpStatus.PASSED, KpStatus.SOLID -> colors.successFg; KpStatus.NEW -> colors.fgTertiary; else -> colors.fgPrimary })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            target?.let { kp -> PaperCard(Modifier.railAnchor("week-next").clickable { onOpen(kp.id, true) }, contentPadding = PaddingValues(16.dp), contentSpacing = 8.dp) {
                Text("下一步", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                Text(d.nextStep.ifBlank { "先把 ${splitTitle(kp.title).second} ${if (view.isResume(kp)) "读完" else "开始学习"}。" }, style = MaterialTheme.typography.bodyLarge)
            } }
            if (!m.inner) {
                Spacer(Modifier.height(24.dp))
                if (d.mustKnow.isNotEmpty()) WeekNotes(d, targetLabel, { target?.let { onOpen(it.id, false) } }, { target?.let { onOpen(it.id, true) } })
                else WeekSummary(view, actions, ui.connected)
            }
        }
    }
}
}
