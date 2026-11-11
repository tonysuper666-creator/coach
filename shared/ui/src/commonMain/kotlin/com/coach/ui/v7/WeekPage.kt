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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.coach.client.*
import com.coach.ui.*
import com.coach.ui.theme.CoachTheme

@Composable
internal fun WeekDirectory(view: CourseView, active: Int?, onWeek: ((Int) -> Unit)?) {
    val d = LocalV7PageDetails.current ?: V7PageDetails()
    val c = CoachTheme.colors
    Text("各周", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelMedium, color = c.fgSecondary)
    Spacer(Modifier.height(12.dp))
    val entries = d.directory.ifEmpty { view.weeks.map { n ->
        val rows = view.weekKps(n)
        V7DirectoryEntry(n, if (n in view.midtermExemptionWeeks) "期中周" else weekName(rows), if (n in view.midtermExemptionWeeks) "无新课" else "${rows.count { it.status.isLearned }}/${rows.size}")
    } }
    entries.forEach { e ->
        Row(Modifier.fillMaxWidth().then(if (e.week == active) Modifier.paperShadow("card", c.dark).clip(RoundedCornerShape(8.dp)).background(c.surface1) else Modifier)
            .then(if (onWeek != null) Modifier.clickable { onWeek(e.week) } else Modifier).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("第 ${e.week} 周" + e.title.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                fontWeight = if (e.week == active) FontWeight.SemiBold else FontWeight.Normal, color = if (e.state.startsWith("0/")) c.fgTertiary else c.fgPrimary, maxLines = 1)
            Text(e.state, style = MaterialTheme.typography.labelMedium, color = if (e.state.startsWith("0/")) c.fgTertiary else c.fgSecondary)
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
            Text("要补的节", style = MaterialTheme.typography.labelMedium.copy(lineHeight = 16.8.sp), color = CoachTheme.colors.fgSecondary)
            Spacer(Modifier.height(12.dp))
            d.missing.forEach { item ->
                Row { Text(KpStatus.NEEDS_REPAIR.symbol, Modifier.width(18.dp), style = MaterialTheme.typography.bodyMedium); Text(annotatedMarks(item), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(8.dp))
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperButton(onClick = onSource) { Text("看要点") }
            PrimaryButton(onClick = onQuiz) { Text("开始做题") }
        }
        if (d.revisit.isNotBlank()) NoteCard(d.revisitLabel, d.revisit)
    }
    if (inner) AlignedRail(listOf("week-target", null, null, "week-next"), gap = 24.dp, finalGap = 16.dp, content = cards)
    else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) { cards() }

}

@Composable
fun WeekPage(c: CoachController, view: CourseView, ui: TutorUiState, actions: WeekActions, onOpen: (String, Boolean) -> Unit, week: Int) {
    val points = view.weekKps(week)
    val passed = points.count { it.status.isLearned }
    val decision = c.learningTarget(view, week)
    val target = decision?.kp
    val targetLabel = "$week.${(points.indexOfFirst { it.id == target?.id } + 1).coerceAtLeast(1)}"
    val d = pageDetails(c, view, week)
    val colors = CoachTheme.colors
    val title = weekName(points).ifBlank { "第 $week 周" }
    val weeks = d.directory.map { it.week }.ifEmpty { view.weeks }
    val select = LocalDeskSelect.current
    val railAnchors = remember(week) { MarginAnchors() }
    var choosingWeek by remember { mutableStateOf(false) }
    val canSelect = true
    val examCountdown = view.examCountdownText(rememberCourseNow(c))
    fun selectWeek(number: Int) { actions.select(number) }
    fun start() { decision?.let { actions.start(view, it) } }
    CompositionLocalProvider(LocalMarginAnchors provides railAnchors, LocalV7PageDetails provides d) {
    if (choosingWeek) AlertDialog(onDismissRequest = { choosingWeek = false }, title = { Text("换周") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { view.weeks.forEach { n ->
            TextButton(onClick = { choosingWeek = false; selectWeek(n) }, enabled = canSelect) { Text(view.weekLabel(n)) }
        } } }, confirmButton = { TextButton(onClick = { choosingWeek = false }) { Text("关闭") } })
    Desk(crumb = "${d.course.ifBlank { c.courseId }} · 第 $week 周", status = examCountdown,
        compactFooter = true, onCrumbClick = if (canSelect) ({ choosingWeek = true }) else null,
        backLabel = "← 路线", onBack = if (LocalAppManagedNavigation.current) null else ({ select(AppTab.ROUTE) }), directory = { WeekDirectory(view, week, if (canSelect) ::selectWeek else null) },
        margin = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (d.mustKnow.isNotEmpty()) WeekNotes(d, targetLabel, { target?.let { onOpen(it.id, false) } }, { start() })
            else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                PaperCard { Text("第 $week 周 · ${KpStatus.PASSED.label} $passed / ${points.size}", style = MaterialTheme.typography.bodyMedium); WeekBrowseSummary(view, week, actions) }
                target?.takeIf { it.reference.isNotBlank() }?.let { NoteCard("出处", it.reference) }
            }
            if (d.shelf.isNotEmpty()) PaperCard {
                Text("资料", style = MaterialTheme.typography.labelMedium)
                d.shelf.forEach { title -> TextButton(onClick = { select(AppTab.RESOURCES) }) { Text(title) } }
            }
            }
        }, footer = {
            val previous = weeks.lastOrNull { it < week }
            Text(previous?.let { "‹  第 $it 周" }.orEmpty(), Modifier.testTag("v7-previous-week").clickable(enabled = previous != null && canSelect) { previous?.let(::selectWeek) }, style = MaterialTheme.typography.labelLarge)
            Text("第 $week 周", style = MaterialTheme.typography.labelMedium, fontFamily = LocalV7Fonts.current.numbers)
            val next = weeks.firstOrNull { it > week }
            Text(next?.let { "第 $it 周  ›" }.orEmpty(), Modifier.testTag("v7-next-week").clickable(enabled = next != null && canSelect) { next?.let(::selectWeek) }, style = MaterialTheme.typography.labelLarge)
        }) {
        val m = LocalDeskMetrics.current
        Column(Modifier.widthIn(max = coach.design.tokens.CoachV7.Layout.colw + m.horizontalPadding * 2).fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = m.horizontalPadding).padding(top = m.topPadding, bottom = 32.dp)) {
            // Use Desk's actual status placement, including windows inside app navigation.
            if (!m.statusInBar) {
                Text(examCountdown, style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
                Spacer(Modifier.height(12.dp))
            }
            PageHeader("第 $week 周", title, deck = "这一周 ${points.size} 节", week = week,
                meta = d.weekStats.ifBlank { statusSummary(points) })
            Spacer(Modifier.height(if (m.inner) 0.dp else 0.dp))
            PaperCard(contentSpacing = 0.dp, contentPadding = PaddingValues(horizontal = 16.dp)) {
                points.forEachIndexed { index, kp ->
                    if (index > 0) HorizontalDivider(color = if (colors.dark) coach.design.tokens.CoachV7.Dark.line else coach.design.tokens.CoachV7.Light.line)
                    Row(Modifier.fillMaxWidth().testTag("week-section-${kp.id}").then(if (kp.id == target?.id) Modifier.railAnchor("week-target") else Modifier).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("$week.${index + 1}", Modifier.width(if (m.window == DeskWindow.Outer) 46.8.dp else 57.6.dp), style = MaterialTheme.typography.labelLarge,
                            color = colors.fgSecondary, fontFamily = LocalV7Fonts.current.numbers)
                        Column(Modifier.weight(1f)) {
                            Text(splitTitle(kp.title).second, style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (kp.status == KpStatus.IN_PROGRESS) FontWeight.SemiBold else FontWeight.Normal))
                            if (m.window == DeskWindow.Outer) d.types.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(2.dp)); Text(it, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary) }
                            Row {
                                TextButton(onClick = { actions.start(view, decision?.takeIf { it.kp.id == kp.id } ?: LearningTarget(kp, week, false)) }) { Text("开始") }
                                TextButton(onClick = { actions.read(LearningTarget(kp, week, false, reading = true)) }) { Text("先看要点") }
                            }
                        }
                        if (m.window != DeskWindow.Outer) d.types.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary) }
                        val state = kp.status.symbol + " " + kp.status.label
                        if (kp.status in setOf(KpStatus.PASSED, KpStatus.SOLID)) PassedLabel(
                            Modifier.width(if (m.window == DeskWindow.Outer) 68.dp else 100.8.dp),
                            MaterialTheme.typography.labelLarge.copy(textAlign = androidx.compose.ui.text.style.TextAlign.End), colors.successFg, kp.status.label, if (kp.status == KpStatus.SOLID) 2 else 1)
                        else Text(androidx.compose.ui.text.buildAnnotatedString {
                            append(state)
                            if (kp.status == KpStatus.IN_PROGRESS) addStyle(androidx.compose.ui.text.SpanStyle(color = colors.dangerFg), 0, 1)
                        }, Modifier.width(if (m.window == DeskWindow.Outer) 68.dp else 100.8.dp), style = MaterialTheme.typography.labelLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End, color = when (kp.status) { KpStatus.PASSED, KpStatus.SOLID -> colors.successFg; KpStatus.NEW -> colors.fgTertiary; else -> colors.fgPrimary })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            target?.let { kp -> PaperCard(Modifier.railAnchor("week-next").clickable { start() }, contentPadding = PaddingValues(16.dp), contentSpacing = 8.dp) {
                Text("接着学", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                Text(d.nextStep.ifBlank { "${if (view.isResume(kp)) "继续" else "开始"} · ${splitTitle(kp.title).second}" }, style = MaterialTheme.typography.bodyLarge)
            } }
            if (!m.inner) {
                Spacer(Modifier.height(24.dp))
                if (d.mustKnow.isNotEmpty()) WeekNotes(d, targetLabel, { target?.let { onOpen(it.id, false) } }, { start() })
                else WeekBrowseSummary(view, week, actions)
            }
        }
    }
}
}
