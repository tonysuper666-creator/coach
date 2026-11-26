package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.V7TextButton
import kotlin.math.roundToInt

@Composable
internal fun CourseExamPointMap(
    detail: CourseDetailSnapshot,
    onRealign: () -> Unit,
    onPractice: (CourseTopicEntry) -> Unit,
    onGenerateExamDrills: (() -> Unit)? = null,
) {
    val mastery = detail.countdownPlan?.rankedTopics.orEmpty().filter { it.hasPracticeEvidence }
        .associate { it.topicId to (it.pMastery * it.projectedRetrievability).coerceIn(0.0, 1.0) }
    val points = detail.examinableTopics.filter { it.weekLabel == CourseExamPoints.LABEL && it.sources.isNotEmpty() }
        .sortedWith(compareBy({ mastery[it.topicId] ?: Double.POSITIVE_INFINITY }, { it.ord }))
    val colors = CoachTheme.colors
    var expanded by remember(detail.courseId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("course-exam-point-map"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("考点地图", style = MaterialTheme.typography.titleMedium)
        if (points.isEmpty()) {
            Text("教材、课件和讲义中的共同考点会显示在这里。", style = MaterialTheme.typography.bodySmall)
            if (detail.materials.isNotEmpty()) V7TextButton(onClick = onRealign) { Text("重新整理考点") }
            return@Column
        }
        val hint = if (points.first().topicId in mastery) {
            "预计掌握度从低到高，点考点开练。"
        } else {
            "这些考点还没有练习记录，点一个开始。"
        }
        Text(hint, style = MaterialTheme.typography.bodySmall)
        for (point in if (expanded) points else points.take(5)) {
            val value = mastery[point.topicId]
            val status = when {
                value == null -> "待练习"
                value < 0.45 -> "薄弱"
                value < 0.75 -> "巩固中"
                else -> "较熟练"
            }
            val weak = value != null && value < 0.45
            Surface(onClick = { onPractice(point) }, shape = RoundedCornerShape(8.dp),
                color = if (weak) colors.dangerBg else colors.surface2,
                modifier = Modifier.fillMaxWidth().testTag("exam-map-${point.topicId}")) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(status, color = if (weak) colors.dangerFg else colors.fgSecondary,
                        style = MaterialTheme.typography.labelMedium)
                    Column(Modifier.weight(1f)) {
                        Text(point.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val techniques = detail.pastPaperTechniques[point.topicId].orEmpty()
                        if (techniques.isNotEmpty()) Text(techniques.joinToString("、") { it.label }, style = MaterialTheme.typography.bodySmall)
                        val drillLine = ExamPointDrillLocal.statusLine(point.pack?.drill)
                        if (drillLine != null) Text(drillLine, style = MaterialTheme.typography.bodySmall)
                    }
                    if (value != null) {
                        Box(Modifier.width(80.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.borderSubtle)) {
                            Box(Modifier.fillMaxWidth(value.toFloat()).height(6.dp)
                                .background(if (weak) colors.dangerFg else colors.successFg))
                        }
                        Text("${(value * 100).roundToInt()}%", Modifier.width(38.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if (points.size > 5) V7TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起" else "查看全部 ${points.size} 个考点")
        }
        if (onGenerateExamDrills != null) V7TextButton(
            onClick = onGenerateExamDrills,
            modifier = Modifier.testTag("exam-point-drill-generate"),
        ) { Text("为考点出题") }
    }
}
