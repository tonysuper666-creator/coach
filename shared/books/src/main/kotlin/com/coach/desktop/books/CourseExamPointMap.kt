package com.coach.desktop.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.V7TextButton
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CourseExamPointMap(detail: CourseDetailSnapshot, onPractice: (CourseTopicEntry) -> Unit) {
    val mastery = detail.countdownPlan?.rankedTopics.orEmpty().filter { it.hasPracticeEvidence }
        .associate { it.topicId to it.pMastery * it.projectedRetrievability }
    val points = detail.examinableTopics.filter { it.weekLabel == CourseExamPoints.LABEL }
        .sortedWith(compareBy({ mastery[it.topicId] ?: 1.1 }, { it.ord }))
    if (points.isEmpty()) return
    val colors = CoachTheme.colors
    var expanded by remember(detail.courseId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("考点地图", style = MaterialTheme.typography.titleMedium)
        Text("按预计掌握度从低到高排列，点选考点开始练习。", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (point in if (expanded) points else points.take(5)) {
                val value = mastery[point.topicId]
                val background = when {
                    value == null -> colors.surface2
                    value < 0.45 -> colors.warningBg
                    value >= 0.75 -> colors.successBg
                    else -> colors.accentSoft
                }
                Surface(onClick = { onPractice(point) }, shape = RoundedCornerShape(8.dp), color = background,
                    modifier = Modifier.testTag("exam-map-${point.topicId}")) {
                    Column(Modifier.padding(10.dp)) {
                        Text(point.title, style = MaterialTheme.typography.labelLarge)
                        Text(value?.let { "预计掌握度 ${(it * 100).roundToInt()}%" } ?: "待练习")
                        val techniques = detail.pastPaperTechniques[point.topicId].orEmpty()
                        if (techniques.isNotEmpty()) Text(techniques.joinToString("、") { it.label })
                    }
                }
            }
        }
        if (points.size > 5) V7TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起" else "查看全部 ${points.size} 个考点")
        }
    }
}
