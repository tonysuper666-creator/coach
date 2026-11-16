package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import com.coach.ui.v7.AnswerOption

@Composable
internal fun SourcePractice(lesson: String, mode: QuestionMode, practice: Practice, answer: PracticeAnswer, onAnswer: (PracticeAnswer) -> Unit, onSource: () -> Unit, sourceEnabled: Boolean, sourceFocus: FocusRequester,
    showReference: Boolean = true) {
    var draft by remember(lesson, mode) { mutableStateOf(answer.text) }
    var typing by remember(lesson, mode) { mutableStateOf(false) }
    // Coming back from 今天/课程 rebuilds this screen with the answer the queue has already saved.
    // While the learner is typing the field owns the text, so a slower echo cannot overwrite keystrokes.
    LaunchedEffect(answer.text, typing) { if (!typing) draft = answer.text }
    Text(if (mode == QuestionMode.CHOICE) "原文填空" else "简答自检", style = MaterialTheme.typography.titleMedium)
    Text(practice.prompt)
    TextButton(onSource, enabled = sourceEnabled, modifier = Modifier.focusRequester(sourceFocus).testTag("open-practice-source")) { Text("查看对应原文") }
    FilterChip(selected = answer.guessed, onClick = { onAnswer(answer.copy(guessed = !answer.guessed)) },
        enabled = !answer.revealed && sourceEnabled, label = { Text("这题我是猜的") },
        modifier = Modifier.testTag("practice-guessed"))
    if (mode == QuestionMode.CHOICE) {
        val colors = com.coach.ui.theme.CoachTheme.colors
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
            Text("你选择：${answer.text}")
            Text(if (answer.text == practice.answer && answer.guessed) "这次选对了，但标记为猜的；仍会在复习里再答。"
                else if (answer.text == practice.answer) "与原文一致。" else "再对照一下原文：${practice.answer}")
            if (showReference) Text("原文：${practice.reference}")
            TextButton({ draft = ""; onAnswer(PracticeAnswer()) }) { Text("重新作答") }
        }
    } else {
        OutlinedTextField(draft, { draft = it; onAnswer(answer.copy(text = it, revealed = false, understood = null)) }, label = { Text("写下你的理解") },
            minLines = 3, modifier = Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused }.testTag("short-answer"))
        TextButton({ onAnswer(answer.copy(text = draft, revealed = true)) }, enabled = draft.isNotBlank(),
            modifier = Modifier.testTag("reveal-reference")) { Text("对照原文自检") }
        if (answer.revealed) {
            if (showReference) Text(practice.reference)
            Text("这是对照原文的自检，不会自动判定掌握。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(answer.understood == false, { onAnswer(answer.copy(text = draft, revealed = true, understood = false)) }, label = { Text("还需复习") })
                FilterChip(answer.understood == true, { onAnswer(answer.copy(text = draft, revealed = true, understood = true)) }, label = { Text("我已理解") })
            }
        }
    }
}
