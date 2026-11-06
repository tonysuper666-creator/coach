package com.coach.ui

import androidx.compose.runtime.*
import com.coach.client.CourseView
import kotlinx.coroutines.delay

/** Re-evaluate at the course's day boundary even while an idle screen receives no ledger events. */
@Composable
internal fun rememberCourseNow(c: CoachController): Long {
    var now by remember(c) { mutableStateOf(c.nowMillis()) }
    LaunchedEffect(c) { while (true) { now = c.nowMillis(); delay(1_000) } }
    return now
}

internal fun examReviewNotice(view: CourseView, now: Long): String {
    if (view.examDay == null) return "期末日期未公布 · 按普通间隔复习"
    val days = view.daysToExam(now) ?: return "考试已结束 · 按普通间隔复习"
    return when {
        days == 0 -> "今天考试 · 复习间隔已缩短"
        days <= 7 -> "距考试还有 $days 天 · 复习间隔已缩短"
        else -> "距考试还有 $days 天 · 按普通间隔复习"
    }
}
