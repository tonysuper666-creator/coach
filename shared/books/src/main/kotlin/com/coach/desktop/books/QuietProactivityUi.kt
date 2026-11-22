package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.PaperButton

/**
 * Quiet proactivity slot 2: collapsible single-line margin whisper strip (`12sp` fgSecondary).
 *
 * Placed in the paper margin OUTSIDE `reader-page` bounds so `pageOverlapPx == 0`.
 * No heavy card frame, no red/yellow warning fill, no modal dialog, and `canFocus = false`
 * so keyboard paging focus (`→` / `PageDown`) is never stolen.
 */
@Composable
internal fun MarginWhisperStrip(
    whisper: MarginWhisperCandidate,
    onExpandCompanion: () -> Unit,
    onJumpAnchorPage: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val paperCanvas = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
    var expanded by remember(whisper.whisperId) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(paperCanvas)
            .focusProperties { canFocus = false }
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .testTag("margin-whisper-strip"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (expanded) whisper.collapsedText.removeSuffix(" ▸") + " ▾" else whisper.collapsedText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colors.fgSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .focusProperties { canFocus = false }
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .testTag("margin-whisper-toggle"),
            )
            Text(
                text = "×",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colors.fgTertiary,
                modifier = Modifier
                    .focusProperties { canFocus = false }
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("margin-whisper-dismiss"),
            )
        }
        if (expanded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp)
                    .testTag("margin-whisper-expanded"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = whisper.expandedText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colors.fgSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "第 ${whisper.pdfPageAnchor} 页",
                    fontSize = 12.sp,
                    color = colors.actionPrimary,
                    modifier = Modifier
                        .focusProperties { canFocus = false }
                        .clickable(role = Role.Button) { onJumpAnchorPage(whisper.pdfPageAnchor) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .testTag("margin-whisper-anchor"),
                )
                Text(
                    text = "看伴读",
                    fontSize = 12.sp,
                    color = colors.fgSecondary,
                    modifier = Modifier
                        .focusProperties { canFocus = false }
                        .clickable(role = Role.Button, onClick = onExpandCompanion)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .testTag("margin-whisper-open-companion"),
                )
            }
        }
    }
}

/**
 * Quiet proactivity slot 3: non-blocking 1-2 question end-of-section checkpoint.
 *
 * Appears only after genuine reading of a section and advancing past its last page.
 * Uses `canFocus = false` so pressing `→` / `PageDown` immediately slides past without blocking.
 * Contains zero `PrimaryButton` instances (UX-4).
 */
@Composable
internal fun EndOfSectionCheckpointCard(
    data: EndOfSectionCheckpointData,
    onAnswer: (BookCompanionQuiz, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val paperCanvas = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
    val questions = remember(data.sectionId, data.questions) { data.questions.take(2) }
    var selections by remember(data.sectionId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var submitted by remember(data.sectionId) { mutableStateOf<Set<String>>(emptySet()) }
    var guessed by remember(data.sectionId) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(paperCanvas)
            .focusProperties { canFocus = false }
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .testTag("end-of-section-checkpoint"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.6f))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CoachCardMeta(
                text = "本节收尾 · ${data.sectionTitle} · 可直接按下一页跳过。",
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "略过",
                fontSize = 12.sp,
                color = colors.fgTertiary,
                modifier = Modifier
                    .focusProperties { canFocus = false }
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("checkpoint-dismiss"),
            )
        }
        questions.forEachIndexed { idx, quiz ->
            val chosen = selections[quiz.id]
            val isSubmitted = quiz.id in submitted
            val isGuessed = guessed[quiz.id] == true
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CoachV7.Radius.s))
                    .background(colors.surface2)
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(CoachV7.Radius.s))
                    .padding(10.dp)
                    .testTag("checkpoint-question-$idx"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "${idx + 1}. ${quiz.stem}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgPrimary,
                )
                if (quiz.options.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        quiz.options.take(4).forEachIndexed { optIdx, opt ->
                            val selected = chosen == opt
                            val shape = RoundedCornerShape(CoachV7.Radius.s)
                            val borderColor = when {
                                isSubmitted && opt == quiz.correctOption -> v7Pine()
                                isSubmitted && selected && opt != quiz.correctOption -> v7Alert()
                                selected -> colors.actionPrimary
                                else -> colors.borderSubtle
                            }
                            Text(
                                text = opt,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.fgPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(shape)
                                    .background(if (selected) colors.actionPrimary.copy(alpha = 0.08f) else colors.surface1)
                                    .border(1.dp, borderColor, shape)
                                    .focusProperties { canFocus = false }
                                    .clickable(enabled = !isSubmitted, role = Role.Button) {
                                        selections = selections + (quiz.id to opt)
                                    }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .testTag("checkpoint-opt-$idx-$optIdx"),
                            )
                        }
                    }
                }
                if (!isSubmitted) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (isGuessed) "✓ 这题是猜的" else "这题是猜的",
                            fontSize = 12.sp,
                            color = if (isGuessed) colors.actionPrimary else colors.fgSecondary,
                            modifier = Modifier
                                .focusProperties { canFocus = false }
                                .clickable(role = Role.Button) {
                                    guessed = guessed + (quiz.id to !isGuessed)
                                }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .testTag("checkpoint-guess-$idx"),
                        )
                        Spacer(Modifier.weight(1f))
                        PaperButton(
                            onClick = {
                                val ans = chosen.orEmpty()
                                if (ans.isNotBlank()) {
                                    submitted = submitted + quiz.id
                                    onAnswer(quiz, ans, isGuessed)
                                }
                            },
                            enabled = !chosen.isNullOrBlank(),
                            modifier = Modifier
                                .focusProperties { canFocus = false }
                                .testTag("checkpoint-submit-$idx"),
                        ) { Text("核对") }
                    }
                } else {
                    val ok = chosen == quiz.correctOption && !isGuessed
                    Text(
                        text = when {
                            ok -> "回答准确。${quiz.explanation}"
                            isGuessed && chosen == quiz.correctOption -> "猜对了，已记入复习巩固。${quiz.explanation}"
                            else -> "参考结论：${quiz.correctOption}。${quiz.explanation}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) v7Pine() else colors.fgSecondary,
                        modifier = Modifier.testTag("checkpoint-feedback-$idx"),
                    )
                }
            }
        }
    }
}
