package com.coach.desktop.books

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val windowsToday = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

@Composable
internal fun TodayCard(
    ui: LibraryUi, library: TextbookLibrary?, onRead: (String) -> Unit,
    onReviews: () -> Unit, onQuestion: (BookAskDraft) -> Unit,
) {
    val focus = ui.catalog.firstOrNull { it.id == ui.book?.id } ?: ui.catalog.firstOrNull()
    val question by produceState<BookAskDraft?>(null, library, ui.catalog) {
        value = withContext(Dispatchers.IO) { library?.askDraft() }
    }
    val scope = rememberCoroutineScope()
    var budgetRevision by remember { mutableIntStateOf(0) }
    val exam by produceState<ExamDayBoard?>(null, library, budgetRevision) {
        value = withContext(Dispatchers.IO) { library?.let { ExamDailyHome.loadDay(it) } }
    }
    val count = ui.todayQueue.lineup.size
    val colors = CoachTheme.colors
    PaperCard(Modifier.testTag("home-focus-card"), color = colors.surface1) {
        Text("今天", modifier = Modifier.testTag("home-focus-title"), style = MaterialTheme.typography.titleLarge, fontFamily = LocalV7Fonts.current.display)
        exam?.let { board ->
            ExamDayBlock(
                board = board,
                busy = ui.busy,
                onBudget = if (library == null) null else { minutes ->
                    scope.launch {
                        withContext(Dispatchers.IO) { ExamDailyHome.setSharedBudget(library, minutes) }
                        budgetRevision++
                    }
                },
            )
        }
        if (focus != null) {
            val chapter = ui.book?.takeIf { it.id == focus.id }?.lessons?.firstOrNull {
                focus.readingPage in it.firstPage..it.lastPage
            }?.title
            val location = MasteryEngine.formatBookLocation(focus.title, chapter, focus.readingPage)
            val hasDraft = ui.activePracticeDraft?.let { it.bookId == focus.id && it.hasMeaningfulDraft() } == true
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (hasDraft) "$location · 练习草稿已保存" else location, Modifier.weight(1f).clickable(enabled = !ui.busy) { onRead(focus.id) }
                    .testTag("home-focus-meta"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (library != null) {
                    var pref by remember(library, focus.id) {
                        mutableStateOf(StudyModeStore.loadOrResolve(library, focus.id, focus.title, focus.pageCount))
                    }
                    StudyModeSelectorButton(pref, onUpdatePreference = { next, _ ->
                        // Only the preference changes here; the reader opens from 「继续」.
                        pref = StudyModeStore.save(library, next)
                    }, compact = true, tag = "home-mode-trigger")
                }
                PrimaryButton({ onRead(focus.id) }, enabled = !ui.busy, modifier = Modifier.testTag("home-read")) {
                    Text("继续")
                }
            }
        }
        if (count > 0) {
            Row(Modifier.fillMaxWidth().testTag("home-review-card"), verticalAlignment = Alignment.CenterVertically) {
                Text(MasteryEngine.formatHomeReviewMeta(count),
                    Modifier.weight(1f).clickable(enabled = !ui.busy, onClick = onReviews).testTag("home-review-meta"))
                PaperButton(onReviews, enabled = !ui.busy, modifier = Modifier.testTag("home-review-start")) {
                    Text("开始复习")
                }
            }
        }
        question?.let { draft ->
            Text("接着问 · ${draft.text}", Modifier.fillMaxWidth().clickable(enabled = !ui.busy) { onQuestion(draft) }
                .padding(vertical = 8.dp).testTag("today-question"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (focus == null && count == 0 && question == null && exam == null) {
            CoachCardMeta(HOME_REVIEW_EMPTY_MESSAGE, Modifier.testTag("home-review-done"))
        }
    }
}

@Composable
private fun ExamDayBlock(board: ExamDayBoard, busy: Boolean, onBudget: ((Int) -> Unit)?) {
    val colors = CoachTheme.colors
    val today = board.today
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.testTag("home-exam-board"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { expanded = !expanded }
                .testTag("home-exam-summary"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "几门课一起排 · 今天 ${today.totalMinutes}/${board.budgetMinutes} 分钟",
                modifier = Modifier.weight(1f).testTag("home-exam-countdown"),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (expanded) "收起" else "展开",
                modifier = Modifier.testTag("home-exam-toggle"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
            )
        }
        if (!expanded) return@Column
        today.courses.forEach { course ->
            Text(
                "${course.title} · ${course.reason}",
                modifier = Modifier.testTag("home-exam-course"),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            course.lines.forEach { line ->
                Text(
                    line,
                    modifier = Modifier.testTag("home-exam-task"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val reads = today.courses.flatMap { it.readFirstTitles }
        if (reads.isNotEmpty()) {
            Text(
                "先读这一节：${reads.joinToString("、")}",
                modifier = Modifier.testTag("home-exam-gap"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
            )
        }
        if (onBudget != null) {
            Text(
                "每天最多 ${board.budgetMinutes} 分钟，几门课加起来不超过它",
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 90, 120).forEach { minutes ->
                    PaperButton(
                        onClick = { onBudget(minutes) },
                        enabled = !busy,
                        modifier = Modifier.testTag("home-exam-minutes-$minutes"),
                    ) { Text("$minutes 分钟") }
                }
            }
        }
        Text(
            "连续 5 天。漏做一天不会堆到下一天，按剩下的天数摊开。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.fgSecondary,
        )
        board.days.forEach { day ->
            val cells = day.courses.joinToString(" · ") { "${it.title} ${it.minutes} 分钟" }
            Text(
                "${day.date} · $cells · 合计 ${day.totalMinutes} 分钟",
                modifier = Modifier.testTag("home-exam-day"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
