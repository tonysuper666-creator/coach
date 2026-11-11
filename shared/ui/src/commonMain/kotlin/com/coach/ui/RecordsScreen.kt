package com.coach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.coach.client.*
import com.coach.domain.flag
import com.coach.domain.logicalDay
import com.coach.domain.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 记录页选中的一项：一个掌握变化，或一次没有计入的作答。 */
private sealed interface RecordSelection {
    data class Change(val change: MasteryChange) : RecordSelection
    data class Orphan(val orphan: OrphanedAnswer) : RecordSelection
    data class Essay(val attemptId: String) : RecordSelection
}

/**
 * 记录：掌握变化的时间线。每个变化都能点开，看到支撑它的每一次作答：原题、你的原答、评分标准、逐字证据高亮、批改与复核用的模型。
 * 锁存撤销和没有计入的作答（孤儿作答）也在这里，并写明原因。
 */
@Composable
fun RecordsScreen(c: CoachController, view: CourseView?, wide: Boolean, onOpenMock: ((mockId: String) -> Unit)? = null, onOpenKp: (kpId: String) -> Unit) {
    if (view == null) return CenterMessage("正在加载课程…")
    val records by produceState<RecordsView?>(null, view) {
        value = withContext(Dispatchers.Default) { RecordsView(view.events).also { it.changes; it.orphans; it.timedEssays } }
    }
    val data = records ?: return CenterMessage("正在整理记录…")
    var picked by remember { mutableStateOf<RecordSelection?>(null) }
    // 宽屏右栏默认就是最新一条变化，不留一块空白；窄屏停在列表。
    val selected = picked ?: if (wide) data.changes.maxByOrNull { it.seq }?.let { RecordSelection.Change(it) } else null
    val list: @Composable (Modifier) -> Unit = { modifier ->
        RecordsList(c, view, data, selected, modifier, onOpenMock) { picked = it }
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(380.dp).fillMaxHeight()) { list(Modifier.fillMaxSize()) }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                RecordDetail(c, view, data, selected, onBack = null, onOpenKp = onOpenKp)
            }
        }
    } else if (selected != null) {
        RecordDetail(c, view, data, selected, onBack = { picked = null }, onOpenKp = onOpenKp)
    } else list(Modifier.fillMaxSize())
}

@Composable
private fun RecordsList(c: CoachController, view: CourseView, data: RecordsView, selected: RecordSelection?, modifier: Modifier, onOpenMock: ((String) -> Unit)?, onSelect: (RecordSelection) -> Unit) {
    val changes = data.changes.sortedByDescending { it.seq }
    val orphans = data.orphans.sortedByDescending { it.seq }
    val mocks = remember(data) { mockRecords(view, data) }
    val essays = data.timedEssays
    var allEssays by remember { mutableStateOf(false) }
    if (changes.isEmpty() && orphans.isEmpty() && mocks.isEmpty() && essays.isEmpty()) {
        Box(modifier) { CenterMessage("还没有掌握变化。连续答对 3 道不同的题，知识点就会「已通过」，这里会记下为什么。") }
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("记录", style = MaterialTheme.typography.headlineMedium) }
        item { Text("每个掌握变化都能点开，看到原题、你的原答和判分依据。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (mocks.isNotEmpty()) {
            item { Text("模拟考", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(mocks, key = { "m:${it.mockId}" }) { m ->
                Surface(onClick = { onOpenMock?.invoke(m.mockId) }, enabled = onOpenMock != null, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("record-mock")) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(dayLabel(logicalDay(m.at, view.zone)) + " " + c.formatClock(m.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(m.title, style = MaterialTheme.typography.bodyLarge)
                            Text((if (m.purpose == "FULL_MOCK") "整卷模拟" else "限时单选") + (if (m.auto) " · 到点自动交卷" else "") + (if (m.pending > 0) " · ${m.pending} 题批改中" else ""),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${marks(m.score)} / ${marks(m.max)}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (essays.isNotEmpty()) {
            item { Text("限时论述", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(if (allEssays) essays else essays.take(3), key = { "e:${it.attemptId}" }) { d ->
                EssayRow(c, d, (selected as? RecordSelection.Essay)?.attemptId == d.attemptId) { onSelect(RecordSelection.Essay(d.attemptId)) }
            }
            if (essays.size > 3 && !allEssays) item { TextButton(onClick = { allEssays = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("显示全部 ${essays.size} 篇") } }
        }
        var lastDay = Long.MIN_VALUE
        for (change in changes) {
            val day = logicalDay(change.at, view.zone)
            if (day != lastDay) { lastDay = day; item(key = "d:$day") { Text(dayLabel(day), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) } }
            item(key = "c:${change.seq}:${change.kpId}:${change.kind}") {
                ChangeRow(c, view, change, (selected as? RecordSelection.Change)?.change == change) { onSelect(RecordSelection.Change(change)) }
            }
        }
        if (orphans.isNotEmpty()) {
            item { Text("没有计入的作答", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(orphans, key = { "o:${it.seq}" }) { o ->
                Surface(onClick = { onSelect(RecordSelection.Orphan(o)) }, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.formatClock(o.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(o.reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun EssayRow(c: CoachController, d: AttemptDetail, selected: Boolean, onClick: () -> Unit) {
    val grade = d.effectiveGrade
    Surface(
        onClick = onClick, tonalElevation = if (selected) 6.dp else 1.dp, shape = MaterialTheme.shapes.medium,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("essay-record"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.formatClock(d.submittedAt ?: 0L), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    grade == null -> StatusChip("批改中", MaterialTheme.colorScheme.tertiary)
                    d.review == null -> StatusChip("初评 ${percent(grade)} · 复核中", MaterialTheme.colorScheme.tertiary)
                    else -> StatusChip("得分 ${percent(grade)}", if (grade.score * 10 >= grade.max * 8) successColor() else MaterialTheme.colorScheme.error)
                }
                if (d.overtime) StatusChip("超时", MaterialTheme.colorScheme.error)
            }
            Text(d.stem, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("${com.coach.domain.Essay.wordCount(d.answer)} 词 · 看逐项批改和改写示范 ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ChangeRow(c: CoachController, view: CourseView, change: MasteryChange, selected: Boolean, onClick: () -> Unit) {
    val title = view.kp(change.kpId)?.title?.let { splitTitle(it).second } ?: "未命名的一节"
    val good = change.kind == ChangeKind.PASSED || change.kind == ChangeKind.SOLID
    Surface(
        onClick = onClick, tonalElevation = if (selected) 6.dp else 1.dp, shape = MaterialTheme.shapes.medium,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.formatClock(change.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusChip(change.kind.label, if (good) successColor() else MaterialTheme.colorScheme.error)
            }
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(change.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (change.attemptIds.isNotEmpty()) Text("依据 ${change.attemptIds.size} 次作答 ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RecordDetail(c: CoachController, view: CourseView, data: RecordsView, selected: RecordSelection?, onBack: (() -> Unit)?, onOpenKp: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 记录") }
        }
        when (selected) {
            null -> CenterMessage("选一条记录，看原题和判分依据。")
            is RecordSelection.Orphan -> OrphanDetail(c, selected.orphan)
            is RecordSelection.Change -> ChangeDetail(c, view, data, selected.change, onOpenKp)
            is RecordSelection.Essay -> {
                val detail = remember(selected.attemptId, data) { data.attempt(selected.attemptId) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("限时论述", style = MaterialTheme.typography.titleLarge)
                    if (detail == null) Text("本机账本里找不到这篇，请联网同步后再看。", color = MaterialTheme.colorScheme.error)
                    else AttemptCard(c.math, c.course != null, { id -> c.course?.attemptRecord(id)?.let(::rubricFrom) }, 1, 1, detail)
                }
            }
        }
    }
}

@Composable
private fun ChangeDetail(c: CoachController, view: CourseView, data: RecordsView, change: MasteryChange, onOpenKp: (String) -> Unit) {
    val kp = view.kp(change.kpId)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(kp?.title?.let { splitTitle(it).second } ?: "未命名的一节", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(change.kind.label, if (change.kind == ChangeKind.PASSED || change.kind == ChangeKind.SOLID) successColor() else MaterialTheme.colorScheme.error)
            Text(c.formatClock(change.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(change.reason, style = MaterialTheme.typography.bodyLarge)
        if (kp != null) TextButton(onClick = { onOpenKp(kp.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("去这个知识点 ›") }
        if (change.attemptIds.isEmpty()) Text("这个变化不依赖具体作答。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        change.attemptIds.forEachIndexed { index, id ->
            val detail = remember(id, data) { data.attempt(id) }
            if (detail == null) Text("第 ${index + 1} 次作答（$id）在本机账本里找不到原题，请联网同步后再看。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            else AttemptCard(c.math, c.course != null, { id -> c.course?.attemptRecord(id)?.let(::rubricFrom) }, index + 1, change.attemptIds.size, detail)
        }
    }
}

@Composable
private fun OrphanDetail(c: CoachController, o: OrphanedAnswer) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("没有计入的作答", style = MaterialTheme.typography.titleLarge)
        Text(c.formatClock(o.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(o.reason, style = MaterialTheme.typography.bodyLarge)
        if (o.stem.isNotBlank()) { Text("当时的题", style = MaterialTheme.typography.labelLarge); Markdown(o.stem, true, "orphan-stem:${o.attemptId}", c.math) }
        Text("你当时的作答", style = MaterialTheme.typography.labelLarge)
        Text(o.rawAnswer.ifBlank { "（空）" }, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 一次作答的全部事实。构答题的评分标准联网向 Mac 取；离线时只有账本里已有的部分，并明说。 */
@Composable
internal fun AttemptCard(math: MathAssets, canAskMac: Boolean, loadRubric: suspend (attemptId: String) -> RubricView?, number: Int, total: Int, d: AttemptDetail) {
    val rubric by produceState<RubricView?>(null, d.attemptId) {
        if (!d.isChoice) value = loadRubric(d.attemptId)
    }
    val grade = d.effectiveGrade
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("attempt")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("第 $number / $total 次作答 · ${formatLabel(d.format)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (grade != null) {
                    val full = grade.score >= grade.max
                    StatusChip(if (d.isChoice) (if (full) "答对" else "答错") else "得分 ${percent(grade)}", if (d.counted && (if (d.isChoice) full else grade.score * 10 >= grade.max * 8)) successColor() else MaterialTheme.colorScheme.error)
                }
            }
            if (!d.counted && d.grade != null) Text("这次批改还在复核，复核通过前不计入掌握。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            if (d.voided) Text("这次作答已作废。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            if (d.hinted) Text("答题前看过提示，这次不进已通过窗口。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            if (d.overtime) Text("超过时限才交卷：照常批改，不算限时成绩。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)

            Text("原题", style = MaterialTheme.typography.labelLarge)
            Markdown(d.stem, true, "rec-stem:${d.attemptId}", math)
            if (d.isChoice) ChoiceBlock(math, d) else ConstructedBlock(d, rubric, canAskMac)
            GradeBlock(d)
        }
    }
}

@Composable
private fun ChoiceBlock(math: MathAssets, d: AttemptDetail) {
    val picked = d.answer.trim().uppercase()
    val correct = d.effectiveGrade?.let { it.score >= it.max }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("选项与你的选择", style = MaterialTheme.typography.labelLarge)
        d.options.forEach { (letter, text) ->
            val mine = letter == picked
            val color = when { mine && correct == true -> successColor(); mine && correct == false -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface }
            Row(Modifier.fillMaxWidth().background(if (mine) color.copy(alpha = .10f) else Color.Transparent, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(letter, style = MaterialTheme.typography.titleMedium, color = color)
                Box(Modifier.weight(1f)) { Markdown(text, true, "rec-opt:${d.attemptId}:$letter", math) }
                if (mine) Text(if (correct == true) "你的选择 ✓" else if (correct == false) "你的选择 ✗" else "你的选择", style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}

@Composable
private fun ConstructedBlock(d: AttemptDetail, rubric: RubricView?, canAskMac: Boolean) {
    val grade = d.effectiveGrade
    val answerSpans = grade?.evidence.orEmpty().filter { it.field == "answer" }
    val valid = answerSpans.filter { it.start >= 0 && it.end <= d.answer.length && it.start < it.end && d.answer.substring(it.start, it.end) == it.text }
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("你的原答", style = MaterialTheme.typography.labelLarge)
        Text(buildAnnotatedString {
            var at = 0
            for (span in valid.sortedBy { it.start }) {
                if (span.start < at) continue
                append(d.answer.substring(at, span.start))
                withStyle(SpanStyle(background = highlight, fontWeight = FontWeight.SemiBold)) { append(d.answer.substring(span.start, span.end)) }
                at = span.end
            }
            append(d.answer.substring(at))
        }, style = MaterialTheme.typography.bodyLarge)
        if (valid.isNotEmpty()) Text("高亮部分是批改引用的逐字证据（原答里一字不差）。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (valid.size < answerSpans.size) Text("有 ${answerSpans.size - valid.size} 处证据区间和原答对不上，没有高亮。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        if (d.finalAnswer.isNotBlank()) { Text("最终数值", style = MaterialTheme.typography.labelLarge); Text(d.finalAnswer, style = MaterialTheme.typography.bodyLarge) }

        Text("评分标准与得分", style = MaterialTheme.typography.labelLarge)
        val items = grade?.items.orEmpty()
        if (rubric == null) {
            Text(if (canAskMac) "完整评分标准要从 Mac 取，现在没取到（离线，或 Mac 上的服务还没更新）。下面是账本里已有的逐项得分。" else "完整评分标准要联网从 Mac 取。下面是账本里已有的逐项得分。",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (rubric.referenceAnswer.isNotBlank()) {
            Text("参考答案：${rubric.referenceAnswer}", style = MaterialTheme.typography.bodyMedium)
        }
        val ids = (rubric?.rubric?.map { it.id } ?: emptyList()).ifEmpty { items.map { it.id } }
        ids.forEach { id ->
            val item = items.firstOrNull { it.id == id }
            val spec = rubric?.rubric?.firstOrNull { it.id == id }
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(buildString {
                    append(if (d.format == "ESSAY") com.coach.domain.Essay.LABELS[id] ?: id else id); if (spec != null && spec.text.isNotBlank()) append("  ").append(spec.text)
                    if (item != null) append("  ·  得 ${trimZero(item.points)}") ; if (spec != null) append(" / ${trimZero(spec.points)} 分")
                }, style = MaterialTheme.typography.bodyMedium)
                if (item == null) Text("批改记录里没有这一项的得分。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                if (item != null && item.reason.isNotBlank()) Text("批改理由：${item.reason}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item != null && item.evidence.isNotBlank() && item.evidence != d.finalAnswer) Text("引用：「${item.evidence}」", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (spec != null && spec.basis.isNotBlank()) Text("教材依据：${spec.basis}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (grade != null) RewriteBlock(grade)
    }
}

/** 论述题的改写示范：在你自己的论证上改，不是参考答案。 */
@Composable
internal fun RewriteBlock(grade: GradeView) {
    if (grade.rewrite.isBlank() && grade.rewriteNotice.isBlank()) return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f), RoundedCornerShape(10.dp)).padding(10.dp).testTag("essay-rewrite"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("改写示范", style = MaterialTheme.typography.labelLarge)
        if (grade.rewrite.isNotBlank()) {
            Text("在你的论证上改写，示范结构和证据怎么写；不是参考答案。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(grade.rewrite, style = MaterialTheme.typography.bodyMedium)
        } else Text(grade.rewriteNotice, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GradeBlock(d: AttemptDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("批改与复核", style = MaterialTheme.typography.labelLarge)
        val g = d.grade
        if (g == null) { Text("还没有批改。", style = MaterialTheme.typography.bodyMedium); return }
        if (d.isChoice) {
            Text("选择题按选项对答案。", style = MaterialTheme.typography.bodyMedium)
            if (g.feedback.isNotBlank()) Text(g.feedback, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        Text("初评：${g.graderModel.ifBlank { "（未记录）" }} · ${percent(g)} · ${statusLabel(g.status)}", style = MaterialTheme.typography.bodyMedium)
        val r = d.review
        if (r == null) Text("复核：还没有完成，完成前这次批改不计入掌握。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        else {
            Text("复核：${r.reviewerModel.ifBlank { "（未记录）" }} · ${if (r.decision == "CONFIRM") "同意初评" else "修改了初评"}" + (r.revised?.let { " · 复核后 ${percent(it)}" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatLabel(format: String) = when (format) { "SINGLE_CHOICE" -> "选择题"; "SHORT_ANSWER" -> "简答"; "ESSAY" -> "论述"; "CALCULATION" -> "计算"; else -> format }
private fun statusLabel(status: String) = when (status) { "AI_UNREVIEWED" -> "待复核"; "EXACT" -> "精确判分"; "VERIFIED" -> "已核验"; else -> status }
private fun percent(g: GradeView) = "${g.score * 100 / g.max}%"
private fun trimZero(x: Double) = if (x == x.toLong().toDouble()) x.toLong().toString() else x.toString()

private class MockRecord(val mockId: String, val title: String, val purpose: String, val auto: Boolean, val at: Long, val score: Double, val max: Double, val pending: Int)

/** 交卷事件在本机账本里，离线也能列出；每题的分来自同一账本里的批改（复核改过的以复核为准），批完之前算「批改中」。 */
private fun mockRecords(view: CourseView, data: RecordsView): List<MockRecord> =
    view.events.filter { it.type == "mock_submitted" }.sortedByDescending { it.seq }.map { e ->
        val p = e.payload
        var score = 0.0; var pending = 0
        for (unit in (p["items"] as? kotlinx.serialization.json.JsonArray).orEmpty()) {
            val u = unit as? kotlinx.serialization.json.JsonObject ?: continue
            val unitMarks = (u["marks"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            val attempt = (u["attemptId"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
            val grade = data.attempt(attempt)?.effectiveGrade
            if (grade == null || grade.max <= 0) pending++ else score += unitMarks * grade.score / grade.max
        }
        val max = ((p["max"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0) / 100
        MockRecord(p.str("mockId"), p.str("title"), p.str("purpose"), p.flag("auto"), e.occurredAt, score, max, pending)
    }

/** 逻辑日（自 1970-01-01 起的天数）→「10月31日」。 */
internal fun dayLabel(day: Long): String {
    val z = day + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return "${m}月${d}日"
}
