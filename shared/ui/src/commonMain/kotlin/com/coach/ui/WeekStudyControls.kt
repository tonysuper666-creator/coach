package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.dp
import com.coach.client.CourseView
import com.coach.domain.*
import com.coach.protocol.CommandBody
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.v7FocusRing
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

/** UI only owns transient submission state; scope and cursor are ledger facts. */
class WeekActions(private val c: CoachController, private val scope: CoroutineScope) {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var onOpen: (String?) -> Unit = {}
    var onReview: (Int) -> Unit = {}
    var onPractice: (Int) -> Unit = {}
    var onBrowse: (Int) -> Unit = {}
    internal var onStart: (LearningTarget) -> Unit = {}
    fun select(week: Int) { onBrowse(week) }
    internal fun read(target: LearningTarget) { onStart(target.copy(reading = true)) }
    internal fun start(view: CourseView, target: LearningTarget) {
        if (busy) return
        val week = target.week
        // Offline cached questions and reading remain reachable; do not queue a scope change for browsing.
        if (!c.tutor.model.state.value.connected || week == null || view.projection.studyScope?.week == week) {
            onStart(target)
        } else run("set_study_scope", json("week" to week)) { onStart(target) }
    }
    fun next(view: CourseView, kpId: String) {
        val active = view.projection.studyScope ?: return
        run("advance_study_scope", json("scopeId" to active.id, "kpId" to kpId))
    }
    private fun run(type: String, payload: JsonObject, after: ((String?) -> Unit)? = null) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    val cmd = c.sync.enqueue(CommandBody(type, c.courseId, c.nowMillis(), payload))
                    c.sync.syncOnce()
                    val receipt = c.sync.receipt(cmd.commandId) ?: error("No receipt")
                    check(receipt.status in setOf("OK", "DUPLICATE")) { receipt.error.orEmpty() }
                    receipt.result.str("target").ifBlank { null }
                }
                (after ?: onOpen)(target)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "未能更新学习范围，请连接 Coach 服务后重试；若已在别的设备推进，以最新进度为准。" }
            finally { busy = false }
        }
    }
}

@Composable
fun WeekPicker(view: CourseView, actions: WeekActions, connected: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = !actions.busy, modifier = Modifier.heightIn(min = 48.dp).v7FocusRing(ButtonDefaults.textShape)) {
            Text(if (actions.busy) "正在更新…" else view.projection.studyScope?.let { "第 ${it.week} 周 · 换周 ▾" } ?: "浏览各周 ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            view.weeks.forEach { week ->
                val remaining = view.weekKps(week).count { it.status !in setOf(com.coach.client.KpStatus.PASSED, com.coach.client.KpStatus.SOLID) }
                val label = view.weekLabel(week)
                DropdownMenuItem(text = { Text(if (week in view.midtermExemptionWeeks) label else "$label · 还有 $remaining 节没会") }, onClick = { expanded = false; actions.select(week) }, modifier = Modifier.v7FocusRingInside())
            }
            if (view.projection.studyScope != null) DropdownMenuItem(text = { Text("全部课程") }, onClick = { expanded = false; actions.select(0) }, modifier = Modifier.v7FocusRingInside())
        }
    }
    actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

/**
 * The shared V7 focus ring for a full-width menu row. The menu clips its rows (scroll viewport and surface),
 * so a ring drawn outside the row would lose its edges; here it is drawn [MenuRingInset] inside the row instead.
 * The row keeps its size, position and click area: only the ring's own frame is inset.
 */
private val MenuRingInset = 8.dp

private fun Modifier.v7FocusRingInside(): Modifier = this
    .layout { measurable, constraints ->
        val p = MenuRingInset.roundToPx()
        val inner = measurable.measure(constraints.offset(-2 * p, -2 * p))
        layout(inner.width + 2 * p, inner.height + 2 * p) { inner.place(p, p) }
    }
    .v7FocusRing()
    .layout { measurable, constraints ->
        val p = MenuRingInset.roundToPx()
        val row = measurable.measure(constraints.offset(2 * p, 2 * p))
        layout(row.width - 2 * p, row.height - 2 * p) { row.place(-p, -p) }
    }

@Composable
fun WeekSummary(view: CourseView, actions: WeekActions, connected: Boolean) {
    val active = view.projection.studyScope ?: return
    if (view.weekTarget != null) return
    val passed = view.weekKps(active.week).count { it.status in setOf(com.coach.client.KpStatus.PASSED, com.coach.client.KpStatus.SOLID) }
    Text("第 ${active.week} 周都学完了，${com.coach.client.KpStatus.PASSED.label} $passed / ${view.weekKps(active.week).size} 节。", style = MaterialTheme.typography.titleMedium)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.onReview(active.week) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("再练这一周错题") }
        view.weeks.firstOrNull { it > active.week }?.let { next ->
            Button(onClick = { actions.select(next) }, enabled = !actions.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("换下一周") }
        }
    }
}

@Composable
internal fun WeekBrowseSummary(view: CourseView, week: Int, actions: WeekActions) {
    val points = view.weekKps(week)
    Text(if (points.isEmpty()) "这一周没有新课。" else "${com.coach.client.KpStatus.PASSED.label} ${points.count { it.status in setOf(com.coach.client.KpStatus.PASSED, com.coach.client.KpStatus.SOLID) }} / ${points.size} 节", style = MaterialTheme.typography.bodyMedium)
    V7TextButton(onClick = { actions.onReview(week) }) { Text("再练这一周错题") }
    view.weeks.firstOrNull { it > week }?.let { next -> V7TextButton(onClick = { actions.select(next) }) { Text("浏览下一周") } }
    actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
