package com.coach.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.coach.client.*

/** 选项点按区最小高度（dp）。验收按 ≥56dp 量。 */
val OPTION_MIN_HEIGHT = 56.dp

/** 一组题的提交与结果；选择状态由学习页持有，题卡可独立保留阅读锚点。 */
@Composable
internal fun QuizGroupFooter(group: QuizGroup, kp: KpRow, c: CoachController, view: CourseView, thread: String, chosen: Map<String, String>, onSubmitted: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!group.submitted) {
            val ready = group.cards.all { chosen[it.attemptId] != null }
            Button(
                onClick = {
                    onSubmitted()
                    val sent = c.tutor.answer(thread, c.courseId, group.groupId, group.cards.associate { it.attemptId to chosen.getValue(it.attemptId) }, "ui-${group.groupId}")
                    if (!sent) c.tutor.model.onFailure(thread, "Mac 未连接，答案没有提交；选择还在，连上后再点提交。")
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().heightIn(min = OPTION_MIN_HEIGHT),
            ) { Text(if (ready) "提交本组答案" else "选完 ${group.cards.size} 题后提交") }
        } else if (group.cards.all { it.grade != null }) {
            val right = group.cards.count { it.grade?.correct == true }
            Text(
                "本组 $right / ${group.cards.size} 题正确 · 当前知识点：${view.kp(kp.id)?.status?.label ?: kp.status.label}",
                style = MaterialTheme.typography.titleSmall,
            )
        } else {
            Text("已提交，正在判分…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun QuizCardView(number: Int, total: Int, card: QuizCard, locked: Boolean, selected: String?, math: MathAssets, onSelect: (String) -> Unit) {
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("第 $number / $total 题", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (card.untaught) StatusChip("这点还没讲过", MaterialTheme.colorScheme.tertiary)
            }
            Markdown(card.stem, true, "q:${card.attemptId}", math)
            val shown = card.answer ?: selected
            card.options.forEach { (letter, text) ->
                val isMine = shown == letter
                val cardGrade = card.grade
                val border = when {
                    cardGrade != null && isMine -> if (cardGrade.correct) successColor() else MaterialTheme.colorScheme.error
                    isMine -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(if (isMine) 2.dp else 1.dp, border),
                    color = if (isMine) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f) else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().heightIn(min = OPTION_MIN_HEIGHT).selectable(selected = isMine, enabled = !locked, role = Role.RadioButton) { onSelect(letter) },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(letter, style = MaterialTheme.typography.titleMedium)
                        Box(Modifier.weight(1f)) { Markdown(text, true, "o:${card.attemptId}:$letter", math) }
                    }
                }
            }
            card.grade?.let { grade ->
                Text(
                    (if (grade.correct) "✓ 答对了" else "✗ 答错了") + if (grade.feedback.isNotBlank()) "：${grade.feedback}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (grade.correct) successColor() else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
