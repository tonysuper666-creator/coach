package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coach.client.*
import com.coach.domain.json
import com.coach.protocol.CommandBody
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/**
 * 期末面板：共用接着学决策、课程日期、各周进度，以及课程内容/分值的真实缺项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExamPanelCard(
    c: CoachController, view: CourseView, focus: ExamFocus?,
    onOpen: (kpId: String, startNow: Boolean) -> Unit, onPractice: (week: Int) -> Unit,
) {
    // 跨午夜也要刷新：时钟每秒读一次，按课程时区的日历日变了才重算。
    val now = rememberCourseNow(c)
    val today = remember(now, view) { com.coach.domain.calendarDay(now, view.zone) }
    val panel = remember(view, focus, today) { view.examPanel(now, focus) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun setExam(day: Long) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cmd = c.sync.enqueue(CommandBody("set_exam", c.courseId, c.nowMillis(), json("examDay" to day)))
                    c.sync.syncOnce()
                    val receipt = c.sync.receipt(cmd.commandId) ?: error("No receipt")
                    check(receipt.status in setOf("OK", "DUPLICATE")) { receipt.error.orEmpty() }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "考试日期没存上，请连接 Mac 后重试。" }
            finally { busy = false }
        }
    }

    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Countdown(panel.countdown, busy) { picking = true }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            val target = c.learningTarget(view)
            Text("接着学", style = MaterialTheme.typography.titleMedium)
            if (target == null) Text("今天的都做完了。")
            else TextButton(onClick = { onOpen(target.kp.id, true) }, modifier = Modifier.testTag("exam-continue")) { Text(splitTitle(target.kp.title).second) }
            if (panel.offFocus.isNotEmpty() || panel.unplanned.isNotEmpty()) {
                Text(emptyFocusText(panel), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("各周进度", style = MaterialTheme.typography.titleMedium)
            panel.weeks.forEach { WeekLine(it) }
            Text("进度：会了算一半，稳了算满。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (picking) {
        val initial = when (val cd = panel.countdown) { is ExamCountdown.Days -> cd.examDay; is ExamCountdown.Past -> cd.examDay; ExamCountdown.Unset -> null }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial?.let { it * DAY_MILLIS })
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(enabled = state.selectedDateMillis != null, onClick = {
                    picking = false
                    state.selectedDateMillis?.let { setExam(it.floorDiv(DAY_MILLIS)) }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("取消") } },
        ) { DatePicker(state, title = { Text("期末考试日期", Modifier.padding(start = 24.dp, top = 16.dp)) }) }
    }
}

private const val DAY_MILLIS = 86_400_000L

/** 课程内容和分值的缺项独立显示，不因合并推荐入口而被隐藏。 */
internal fun emptyFocusText(panel: ExamPanel): String {
    if (panel.allSolid) return "各周都稳了，没有要补的。"
    return buildList {
        if (panel.offFocus.isNotEmpty()) add("${weekSpans(panel.offFocus)} 还没会，期末分值为 0。")
        if (panel.unplanned.isNotEmpty()) add("有分值的${weekSpans(panel.unplanned)}在课程里还没有对应的节，暂时没法学。")
    }.joinToString("")
}

@Composable
private fun Countdown(countdown: ExamCountdown, busy: Boolean, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("期末", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (countdown) {
                ExamCountdown.Unset -> Text("还没设期末日期", style = MaterialTheme.typography.headlineSmall)
                is ExamCountdown.Days -> {
                    Text("距期末 ${countdown.days} 天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(examDateLabel(countdown.examDay) + if (countdown.days == 0) " · 今天考试" else "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ExamCountdown.Past -> {
                    Text("期末已结束", style = MaterialTheme.typography.headlineSmall)
                    Text("记的是 ${examDateLabel(countdown.examDay)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val label = if (busy) "正在保存…" else if (countdown == ExamCountdown.Unset) "填考试日期" else "改日期"
        if (countdown == ExamCountdown.Unset) Button(onClick = onPick, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
        else TextButton(onClick = onPick, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
    }
}

@Composable
private fun WeekLine(row: WeekMastery) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("第 ${row.week} 周", Modifier.widthIn(min = 48.dp), style = MaterialTheme.typography.labelLarge)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (row.topic.isNotBlank()) Text(row.topic, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (row.exempt) Text("期中周，无新课", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (row.total == 0) Text("课程里还没有这周的节", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LinearProgressIndicator(progress = { row.score.toFloat() }, Modifier.fillMaxWidth().height(6.dp), color = successColor(), trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
        }
        if (row.total > 0) Text("${KpStatus.PASSED.label} ${row.passed}/${row.total}" + if (row.solid > 0) " · ${KpStatus.SOLID.label} ${row.solid}" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
