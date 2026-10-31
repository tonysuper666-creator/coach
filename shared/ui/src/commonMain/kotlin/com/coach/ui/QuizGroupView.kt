package com.coach.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
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
internal fun QuizCardView(number: Int, total: Int, card: QuizCard, locked: Boolean, selected: String?, math: MathAssets, onFirstDraw: () -> Unit = {}, onSelect: (String) -> Unit) {
    var headerVisible by remember(card.attemptId) { mutableStateOf(false) }
    val trace=if(PoolTrace.enabled && number==1) Modifier.onGloballyPositioned {
        val bounds=it.boundsInWindow();headerVisible=bounds.width>0 && bounds.height>0
    }.drawWithContent { drawContent();if(headerVisible) onFirstDraw() } else Modifier
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("第 $number / $total 题", modifier=trace, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (card.untaught) StatusChip("这点还没讲过", MaterialTheme.colorScheme.tertiary)
            }
            QuizText(card.stem, "q:${card.attemptId}", math)
            val shown = card.answer ?: selected
            if (card.options.isEmpty()) {
                OutlinedTextField(value = shown.orEmpty(), onValueChange = onSelect, readOnly = locked,
                    label = { Text("你的回答") }, modifier = Modifier.fillMaxWidth())
                if (locked && card.grade == null) Text("答案已保存，联网后批改",style = MaterialTheme.typography.bodyMedium)
            }
            card.options.forEach { (letter, text) ->
                val isMine = shown == letter
                val cardGrade = card.grade
                // After a wrong answer the right option is marked the same way a right answer is (green frame and ✓), so nobody has to hunt for it.
                val isRight = cardGrade != null && !cardGrade.correct && correctLetterOf(cardGrade.feedback) == letter
                val wrongPick = cardGrade != null && !cardGrade.correct && isMine
                val border = when {
                    cardGrade != null && isMine -> if (cardGrade.correct) successColor() else MaterialTheme.colorScheme.error
                    isRight -> successColor()
                    isMine -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(if (isMine || isRight) 2.dp else 1.dp, border),
                    color = when {
                        wrongPick -> MaterialTheme.colorScheme.errorContainer
                        isMine -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = OPTION_MIN_HEIGHT).selectable(selected = isMine, enabled = !locked, role = Role.RadioButton) { onSelect(letter) },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if ((isMine && card.grade == null) || isRight || (isMine && card.grade?.correct == true)) "✓ $letter" else if (wrongPick) "✗ $letter" else letter, style = MaterialTheme.typography.titleMedium)
                        Box(Modifier.weight(1f)) { QuizText(text, "o:${card.attemptId}:$letter", math) }
                    }
                }
            }
            card.grade?.let { grade ->
                Text(
                    verdictText(grade) + if (grade.note.isBlank()) "" else " · ${grade.note}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (grade.correct) successColor() else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private val QUIZ_ORDERED_PREFIX = Regex("^\\d+[.)]\\s")

/** Plain question paragraphs need no Markdown parser or formula effects on their first frame. */
@Composable
internal fun QuizText(text: String, key: String, math: MathAssets) {
    // Deliberately conservative: any potential Markdown, math, URL, entity or indentation
    // goes through the existing renderer. Keep its exact body style and full-width layout.
    val plain = remember(text) {
        text.isNotEmpty() && !text.first().isWhitespace() && text.any { it.isLetterOrDigit() } &&
            !text.startsWith("- ") && !QUIZ_ORDERED_PREFIX.containsMatchIn(text) &&
            !text.contains("www.", ignoreCase = true) &&
            text.all { it.isLetterOrDigit() || it in " ，。；：？！、（）“”‘’％%,;=/.'?!-()" }
    }
    if (plain) Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    else Markdown(text, true, key, math)
}

/** The server's stock wrong-answer sentence ("回答错误，正确答案是 B。", from `choiceFeedback`) names the right letter; the card uses it to mark that option. */
internal fun correctLetterOf(feedback: String): String? = Regex("正确答案(?:是|为)\\s*([A-Za-z])").find(feedback)?.groupValues?.get(1)?.uppercase()

/** One line, no repeats: "✓ 答对了", "✗ 答错了，正确答案是 B", plus the server's feedback only when it says something more. */
internal fun verdictText(grade: CardGrade): String {
    if (grade.correct) return "✓ 答对了" + extraFeedback(grade.feedback)?.let { "：$it" }.orEmpty()
    val letter = correctLetterOf(grade.feedback)
    return "✗ 答错了" + letter?.let { "，正确答案是 $it" }.orEmpty() + extraFeedback(grade.feedback)?.let { "：$it" }.orEmpty()
}

/** The verdict already says right or wrong; keep the server's feedback only when it adds something (not "回答正确（B）" or "回答错误，正确答案是 B。" again). */
internal fun extraFeedback(feedback: String): String? =
    feedback.trim().replace(Regex("^回答(?:正确（[A-Za-z]）|正确|错误)[，,]?\\s*(?:正确答案(?:是|为)\\s*[A-Za-z])?[。.！!]?\\s*"), "").trim().ifEmpty { null }
