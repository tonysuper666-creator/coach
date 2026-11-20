package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.v7.AnswerOption
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.v7FocusRing

@Composable
internal fun SourcePractice(lesson: String, mode: QuestionMode, practice: Practice, answer: PracticeAnswer, onAnswer: (PracticeAnswer) -> Unit, onSource: () -> Unit, sourceEnabled: Boolean, sourceFocus: FocusRequester,
    showReference: Boolean = true, temporary: Boolean = false) {
    var draft by remember(lesson, mode) { mutableStateOf(answer.text) }
    var typing by remember(lesson, mode) { mutableStateOf(false) }
    // Coming back from 今天/课程 rebuilds this screen with the answer the queue has already saved.
    // While the learner is typing the field owns the text, so a slower echo cannot overwrite keystrokes.
    LaunchedEffect(answer.text, typing) { if (!typing) draft = answer.text }
    val colors = com.coach.ui.theme.CoachTheme.colors
    // V7 题面直接是题干，没有「原文填空」这层小标题；简答保留一行小字说明怎么答。
    if (mode != QuestionMode.CHOICE) Text("简答自检", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
    Text(practice.prompt, style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        V7TextButton(onSource, enabled = sourceEnabled, modifier = Modifier.focusRequester(sourceFocus).testTag("open-practice-source")) { Text("查看对应原文") }
        FilterChip(selected = answer.guessed, onClick = { onAnswer(answer.copy(guessed = !answer.guessed)) },
            enabled = !answer.revealed && sourceEnabled, label = { Text("这题我是猜的") },
            colors = paperChipColors(), border = null,
            modifier = Modifier.testTag("practice-guessed"))
    }
    if (mode == QuestionMode.CHOICE) {
        for ((index, option) in practice.options.withIndex()) {
            val selected = answer.revealed && answer.text == option
            val correct = if (selected && !answer.guessed) option == practice.answer else null
            AnswerOption(
                letter = ('A' + index).toString(), selected = selected, correct = correct,
                enabled = !answer.revealed, modifier = Modifier.testTag("choice-$index"),
                onClick = { draft = option; onAnswer(answer.copy(text = option, revealed = true, understood = null)) },
            ) {
                Text(option, color = when (correct) { true -> colors.successFg; false -> colors.dangerFg; null -> colors.fgPrimary })
            }
        }
        if (answer.revealed) {
            // 选项上已经有对勾或「你选的」，这里只留一行小字：选了什么、和原文的关系，旁边是重新作答。
            val right = answer.text == practice.answer
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("你选择：${answer.text}", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                Text(if (right && answer.guessed) if (temporary) "这次选对了，但标记为猜的；不计作独立答对。" else "这次选对了，但标记为猜的；仍会在复习里再答。"
                    else if (right) if (temporary) "与本题答案一致。" else "与原文一致。"
                    else if (temporary) "本题答案是：${practice.answer}" else "再对照一下原文：${practice.answer}",
                    style = MaterialTheme.typography.bodySmall, color = if (right && !answer.guessed) colors.successFg else colors.fgSecondary,
                    modifier = Modifier.weight(1f))
                V7TextButton({ draft = ""; onAnswer(PracticeAnswer()) }) { Text("重新作答") }
            }
            if (showReference) Text("原文：${practice.reference}")
        }
    } else {
        // V7 弱线纸面输入：字段名在框上方，框是卡片色块加一道细线，聚焦时外圈一道焦点环；不再用 Material 描边浮动标签。
        val shape = RoundedCornerShape(CoachV7.Radius.btn)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("写下你的理解", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
            BasicTextField(draft, { draft = it; onAnswer(answer.copy(text = it, revealed = false, understood = null)) },
                minLines = 3, textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.fgPrimary),
                cursorBrush = SolidColor(colors.fgPrimary),
                modifier = Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused }
                    .semantics { contentDescription = "写下你的理解" }
                    .v7FocusRing(shape).clip(shape).background(colors.surface2)
                    .border(1.dp, colors.borderSubtle, shape).padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("short-answer"))
        }
        V7TextButton({ onAnswer(answer.copy(text = draft, revealed = true)) }, enabled = draft.isNotBlank(),
            modifier = Modifier.testTag("reveal-reference")) { Text("对照原文自检") }
        if (answer.revealed) {
            if (showReference) Text(practice.reference)
            Text("这是对照原文的自检，不会自动判定掌握。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(answer.understood == false, { onAnswer(answer.copy(text = draft, revealed = true, understood = false)) }, label = { Text("还需复习") },
                    colors = paperChipColors(), border = null)
                FilterChip(answer.understood == true, { onAnswer(answer.copy(text = draft, revealed = true, understood = true)) }, label = { Text("我已理解") },
                    colors = paperChipColors(), border = null)
            }
        }
    }
}

/** V7 没有描边：未选是卡片色块，选中是松绿浅底配松绿字，和答对的选项同一套颜色。 */
@Composable
private fun paperChipColors(): SelectableChipColors {
    val c = com.coach.ui.theme.CoachTheme.colors
    val dark = c.dark
    return FilterChipDefaults.filterChipColors(
        containerColor = c.surface2, labelColor = c.fgPrimary,
        selectedContainerColor = if (dark) coach.design.tokens.CoachV7.Dark.pineWash else coach.design.tokens.CoachV7.Light.pineWash,
        selectedLabelColor = c.successFg, selectedLeadingIconColor = c.successFg,
    )
}
