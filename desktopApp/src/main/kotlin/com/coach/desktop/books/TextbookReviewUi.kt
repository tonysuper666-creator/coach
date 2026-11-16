package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.Desk
import com.coach.ui.v7.PaperCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun LocalBookReviews(ui: LibraryUi, onBack: () -> Unit, onOpen: (LocalBookReview) -> Unit) {
    Desk(crumb = "复习", onBack = onBack, backLabel = "返回书架", showNavigation = false) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("local-reviews"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("复习 ${ui.reviews.size} 题", style = MaterialTheme.typography.headlineMedium)
            Text("答错、猜的和到期题都在这里；同一道题只列一次，原答和每次复习都保留。", color = CoachTheme.colors.fgSecondary)
            Text("独立答对后，按 1 天、3 天、7 天再回顾；答错或猜的继续留在本次复习。",
                style = MaterialTheme.typography.bodySmall, color = CoachTheme.colors.fgSecondary)
            if (ui.reviews.isEmpty()) Text("暂时没有需要复习的题。")
            // V7：每道题是一张无描边的纸卡（card 色、纸影、焦点环），整张可点；原因用朱色小字标在书名旁。
            ui.reviews.forEachIndexed { index, review ->
                PaperTile(!ui.busy, Modifier.fillMaxWidth().testTag("local-review-item-$index"), { onOpen(review) },
                    PaddingValues(20.dp), 8.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(review.bookTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                        Text(review.sources.joinToString(" · ") { it.label }, style = MaterialTheme.typography.labelLarge,
                            color = v7Zhu(), modifier = Modifier.testTag("local-review-reasons-$index"))
                    }
                    Text("${review.lessonTitle} · ${review.mode.label}", style = MaterialTheme.typography.bodySmall,
                        color = CoachTheme.colors.fgSecondary)
                    Text(review.practice.prompt)
                    Text("上次作答：${review.originalAnswer}", style = MaterialTheme.typography.bodySmall,
                        color = CoachTheme.colors.fgSecondary)
                }
            }
        }
    }
}

@Composable
internal fun LocalBookReviewQuestion(review: LocalBookReview, enabled: Boolean, sourceFocus: FocusRequester,
    returnFocus: SourceFocus?, onFocusRestored: () -> Unit, onBack: () -> Unit, onSource: () -> Unit,
    onAnswer: (PracticeAnswer) -> Unit) {
    LaunchedEffect(returnFocus, enabled) {
        if (enabled && returnFocus != null) {
            withFrameNanos { }
            sourceFocus.requestFocus()
            onFocusRestored()
        }
    }
    Desk(crumb = review.bookTitle, onBack = onBack, backLabel = "返回复习列表", showNavigation = false) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("local-review-question"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("复习 · ${review.lessonTitle}", style = MaterialTheme.typography.headlineMedium)
            Text("上次作答：${review.originalAnswer}", style = MaterialTheme.typography.bodySmall, color = CoachTheme.colors.fgSecondary)
            SourcePractice(review.lesson, review.mode, review.practice, review.answer, onAnswer, onSource, enabled, sourceFocus,
                showReference = false)
            if (review.completed) {
                Text("这题已复习。", color = CoachTheme.colors.successFg, modifier = Modifier.testTag("local-review-completed"))
                Text("下次回顾：${LocalDate.ofEpochDay(review.dueDay).format(DateTimeFormatter.ofPattern("M月d日"))}",
                    style = MaterialTheme.typography.bodySmall, color = CoachTheme.colors.fgSecondary,
                    modifier = Modifier.testTag("local-review-next-due"))
            }
            else Text("这题保留在复习里；再答一次，或打开原文对照。", style = MaterialTheme.typography.bodySmall,
                color = CoachTheme.colors.fgSecondary)
            // 作答记录放进一张纸卡，不再用分隔线隔开。
            if (review.history.isNotEmpty()) PaperCard(contentPadding = PaddingValues(16.dp)) {
                Text("作答记录 · ${review.history.size} 次", style = MaterialTheme.typography.titleMedium)
                review.history.forEach { attempt ->
                    Text("第 ${attempt.number} 次${if (attempt.answer.guessed) " · 猜的" else ""}：${attempt.answer.text}",
                        style = MaterialTheme.typography.bodySmall, color = CoachTheme.colors.fgSecondary,
                        modifier = Modifier.testTag("local-review-history-${attempt.number}"))
                }
            }
        }
    }
}

/** V7 朱色：标出复习原因（答错、猜的、到期），随明暗取 token。 */
@Composable
private fun v7Zhu(): androidx.compose.ui.graphics.Color = if (CoachTheme.colors.dark) CoachV7.Dark.zhu else CoachV7.Light.zhu
