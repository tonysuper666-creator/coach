package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.v7.AnswerOption
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.V7TextButton

/**
 * Shown only under a finished one-pager. Tags stay off the one-pager's guide-source / guide-tab names.
 */
@Composable
internal fun LiteratureQuizSection(
    quiz: LiteratureQuizSet?,
    busy: Boolean,
    notice: String,
    gaps: List<String>,
    library: TextbookLibrary,
    onAllowExplain: (() -> Unit)? = null,
    onSource: (GuideExcerpt) -> Unit,
) {
    var picks by remember(quiz?.digest) { mutableStateOf<Map<Int, QuizPick>>(emptyMap()) }
    var errorText by remember(quiz?.digest) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().testTag("quiz-section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("选择题", style = MaterialTheme.typography.titleMedium)
        val status = when {
            errorText.isNotEmpty() -> errorText
            notice.isNotEmpty() -> notice
            busy -> "正在核对选择题…"
            else -> ""
        }
        if (status.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(status, modifier = Modifier.weight(1f, fill = false).testTag("quiz-notice"))
                if (onAllowExplain != null && !busy && errorText.isEmpty()) {
                    PaperButton(
                        onClick = onAllowExplain,
                        modifier = Modifier.testTag("quiz-allow-explain"),
                    ) { Text("开启伴读讲解") }
                }
            }
        }
        if (gaps.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().testTag("quiz-gaps"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                gaps.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
            }
        }
        val current = quiz
        if (current != null) current.items.forEachIndexed { index, item ->
            val pick = picks[index]
            Column(Modifier.fillMaxWidth().testTag("quiz-item-$index"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("思考题 ${item.questionIndex + 1}", style = MaterialTheme.typography.labelMedium)
                Text(item.stem, style = MaterialTheme.typography.titleSmall)
                item.options.forEachIndexed { optionIndex, option ->
                    val revealed = pick != null
                    AnswerOption(
                        letter = ('A' + optionIndex).toString(),
                        selected = revealed && pick?.text == option.text,
                        correct = if (revealed) option.correct else null,
                        enabled = !revealed,
                        modifier = Modifier.testTag("quiz-choice-$index-$optionIndex"),
                        onClick = {
                            commit(library, current, index, option.text, guessed = false, picks, { picks = it }, { errorText = it })
                        },
                    ) { Text(option.text) }
                }
                if (pick == null) {
                    V7TextButton(
                        onClick = { commit(library, current, index, null, guessed = true, picks, { picks = it }, { errorText = it }) },
                        modifier = Modifier.testTag("quiz-unsure-$index"),
                    ) { Text("不会，看讲解") }
                } else {
                    Text(LiteratureQuizBank.explanation(item), modifier = Modifier.testTag("quiz-explain-$index"))
                    val sources = item.options.flatMap { it.sources }
                        .distinctBy { it.book.id to it.anchor.pdfPage to it.anchor.startUtf16 }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sources.forEachIndexed { sourceIndex, source ->
                            V7TextButton(
                                onClick = { onSource(source) },
                                modifier = Modifier.testTag(if (sourceIndex == 0) "quiz-source-$index" else "quiz-source-$index-$sourceIndex"),
                            ) { Text("PDF 第 ${source.anchor.pdfPage} 页", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }
}

private data class QuizPick(val text: String, val guessed: Boolean)

private fun commit(
    library: TextbookLibrary,
    quiz: LiteratureQuizSet,
    index: Int,
    chosen: String?,
    guessed: Boolean,
    picks: Map<Int, QuizPick>,
    setPicks: (Map<Int, QuizPick>) -> Unit,
    setError: (String) -> Unit,
) {
    try {
        LiteratureQuizBank.commit(library, quiz, index, chosen, guessed)
        setPicks(picks + (index to QuizPick(chosen ?: "不确定", guessed)))
        setError("")
    } catch (e: Exception) {
        setError(e.message ?: "这道题没有写入复习。")
    }
}
