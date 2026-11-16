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

@Composable
internal fun LocalBookReviews(ui: LibraryUi, onBack: () -> Unit, onOpen: (LocalBookReview) -> Unit) {
    Desk(crumb = "复习", onBack = onBack, backLabel = "返回书架", showNavigation = false) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("local-reviews"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("复习 ${ui.reviews.size} 题", style = MaterialTheme.typography.headlineMedium)
            Text("答错和自检时选择还需复习的题都在这里，保留原题和原文。")
            if (ui.reviews.isEmpty()) Text("暂时没有需要复习的题。")
            ui.reviews.forEachIndexed { index, review ->
                OutlinedCard(onClick = { onOpen(review) }, enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth().testTag("local-review-item-$index")) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(review.bookTitle, style = MaterialTheme.typography.titleMedium)
                        Text("${review.lessonTitle} · ${review.mode.label}")
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
            SourcePractice(review.lesson, review.mode, review.practice, review.answer, onAnswer, onSource, enabled, sourceFocus)
            if (review.completed) Text("这题已复习。", modifier = Modifier.testTag("local-review-completed"))
            else Text("这题保留在复习里；再答一次，或打开原文对照。")
        }
    }
}
