package com.coach.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import com.coach.client.CourseView
import com.coach.client.KpRow
import com.coach.client.KpStatus
import kotlin.math.roundToInt

/** 一个章节在路线上的小计。 */
internal data class ModuleProgress(val module: String, val kps: List<KpRow>) {
    val passed get() = kps.count { it.status == KpStatus.PASSED || it.status == KpStatus.SOLID }
    val solid get() = kps.count { it.status == KpStatus.SOLID }
}

internal fun routeModules(kps: List<KpRow>): List<ModuleProgress> {
    val out = ArrayList<ModuleProgress>()
    for (kp in kps) {
        val last = out.lastOrNull()
        if (last != null && last.module == kp.module) out[out.lastIndex] = last.copy(kps = last.kps + kp) else out += ModuleProgress(kp.module, listOf(kp))
    }
    return out
}

/** 前置里还没通过的知识点（软锁：只提示，不阻止打开）。 */
internal fun unmetPrereqs(kp: KpRow, view: CourseView): List<KpRow> =
    kp.prereqs.mapNotNull { view.kp(it) }.filter { it.status != KpStatus.PASSED && it.status != KpStatus.SOLID }

/** 路线：全部知识点按章节排开，两层状态各自按知识点个数的比例显示（分值口径的「学了多少」在今天页顶部）。 */
@Composable
fun RouteScreen(c: CoachController, view: CourseView?, selectedKpId: String?, onOpen: (kpId: String, startNow: Boolean) -> Unit) {
    if (view == null) return CenterMessage("正在加载课程…")
    val kps = view.kps
    if (kps.isEmpty()) return CenterMessage("课程里还没有知识点。")
    // 每个知识点有几道已复核的构答题：只有 Mac 服务返回了才显示；拿不到就什么都不说，不猜。
    val supply by produceState<Map<String, Int>?>(null, c) { value = c.course?.constructedSupply() }
    val passed = kps.count { it.status == KpStatus.PASSED || it.status == KpStatus.SOLID }
    val solid = kps.count { it.status == KpStatus.SOLID }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("路线", style = MaterialTheme.typography.headlineMedium)
                ProportionBar("已通过", passed, kps.size, MaterialTheme.colorScheme.secondary)
                ProportionBar("稳固", solid, kps.size, successColor())
                Text("按知识点个数计；按期中分值的覆盖在「今天」页顶部。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (module in routeModules(kps)) {
            item(key = "m:${module.module}:${module.kps.first().id}") {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
                    Text(module.module.ifBlank { "知识点" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("${module.passed}/${module.kps.size} 已通过" + if (module.solid > 0) " · ${module.solid} 稳固" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(module.kps, key = { it.id }) { kp ->
                RouteRow(kp, kp.id == selectedKpId, unmetPrereqs(kp, view), noConstructed = supply?.let { (it[kp.id] ?: 0) == 0 } == true) { onOpen(kp.id, false) }
            }
        }
    }
}

@Composable
private fun ProportionBar(label: String, count: Int, total: Int, color: androidx.compose.ui.graphics.Color) {
    val fraction = if (total == 0) 0f else count.toFloat() / total
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("$count / $total（${(fraction * 100).roundToInt()}%）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().height(8.dp), color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
    }
}

@Composable
private fun RouteRow(kp: KpRow, selected: Boolean, unmet: List<KpRow>, noConstructed: Boolean, onClick: () -> Unit) {
    val (tag, main) = splitTitle(kp.title)
    Surface(
        tonalElevation = if (selected) 6.dp else 1.dp,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    if (tag.isNotBlank()) Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(main, style = MaterialTheme.typography.bodyLarge)
                }
                // 状态用文字（和图标位），不只靠颜色。
                StatusChip((if (kp.status == KpStatus.SOLID) "✓✓ " else if (kp.status == KpStatus.PASSED) "✓ " else "") + kp.status.label, statusColor(kp.status))
            }
            if (kp.formerlySolid) Text("曾稳固：最近一次合格情境没通过，需要新的合格通过。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            if (kp.solidProgress.isNotBlank()) Text(kp.solidProgress, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (unmet.isNotEmpty()) Text("建议先学：" + unmet.joinToString("、") { splitTitle(it.title).second.take(24) }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            if (noConstructed) Text("此 KP 暂无构答题：稳固层到不了，如实显示。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
