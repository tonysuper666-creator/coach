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
import com.coach.ui.v7.Desk
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun LocalBookReviews(ui: LibraryUi, onBack: () -> Unit, onOpen: (LocalBookReview) -> Unit) {
    Desk(crumb = "复习", onBack = onBack, backLabel = "返回书架", showNavigation = false) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("local-reviews"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("复习 ${ui.reviews.size} 题", style = MaterialTheme.typography.headlineMedium)
            Text("答错、猜的和到期题都在这里；同一道题只列一次，原答和每次复习都保留。")
            Text("独立答对后，按 1 天、3 天、7 天再回顾；答错或猜的继续留在本次复习。",
                style = MaterialTheme.typography.bodySmall)
            if (ui.reviews.isEmpty()) Text("暂时没有需要复习的题。")
            ui.reviews.forEachIndexed { index, review ->
                OutlinedCard(onClick = { onOpen(review) }, enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth().testTag("local-review-item-$index")) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(review.bookTitle, style = MaterialTheme.typography.titleMedium)
                        Text("${review.lessonTitle} · ${review.mode.label}")
                        Text(review.sources.joinToString(" · ") { it.label },
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("local-review-reasons-$index"))
                        Text(review.practice.prompt)
                        Text("上次作答：${review.originalAnswer}")
                    }
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
            Text("上次作答：${review.originalAnswer}")
            SourcePractice(review.lesson, review.mode, review.practice, review.answer, onAnswer, onSource, enabled, sourceFocus,
                showReference = false)
            if (review.completed) {
                Text("这题已复习。", modifier = Modifier.testTag("local-review-completed"))
                Text("下次回顾：${LocalDate.ofEpochDay(review.dueDay).format(DateTimeFormatter.ofPattern("M月d日"))}",
                    modifier = Modifier.testTag("local-review-next-due"))
            }
            else Text("这题保留在复习里；再答一次，或打开原文对照。")
            if (review.history.isNotEmpty()) {
                HorizontalDivider()
                Text("作答记录 · ${review.history.size} 次", style = MaterialTheme.typography.titleMedium)
                review.history.forEach { attempt ->
                    Text("第 ${attempt.number} 次${if (attempt.answer.guessed) " · 猜的" else ""}：${attempt.answer.text}",
                        modifier = Modifier.testTag("local-review-history-${attempt.number}"))
                }
            }
        }
    }
}
