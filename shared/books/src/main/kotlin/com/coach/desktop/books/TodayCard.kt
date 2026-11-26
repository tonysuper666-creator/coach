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
    val exam by produceState<ExamDailyHomeView?>(null, library, budgetRevision) {
        value = withContext(Dispatchers.IO) { library?.let { ExamDailyHome.load(it) } }
    }
    val count = ui.todayQueue.cards.size
    val colors = CoachTheme.colors
    PaperCard(Modifier.testTag("home-focus-card"), color = colors.surface1) {
        Text("今天", modifier = Modifier.testTag("home-focus-title"), style = MaterialTheme.typography.titleLarge, fontFamily = LocalV7Fonts.current.display)
        exam?.let { view ->
            Text(
                "${view.courseTitle} · ${view.tasks.header}",
                modifier = Modifier.testTag("home-exam-countdown"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(view.tasks.taskLine(), modifier = Modifier.testTag("home-exam-tasks"), style = MaterialTheme.typography.bodyMedium)
            if (view.tasks.pointsWithoutQuestions.isNotEmpty()) {
                val names = view.tasks.pointsWithoutQuestions.joinToString("、") { view.titles[it] ?: it }
                Text("这些考点还没有题：$names", modifier = Modifier.testTag("home-exam-gap"), style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            }
            if (library != null) {
                Text("每天最多 ${view.tasks.budgetMinutes} 分钟", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 90, 120).forEach { minutes ->
                        PaperButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { ExamDailyHome.setBudget(library, view.courseId, minutes) }
                                    budgetRevision++
                                }
                            },
                            enabled = !ui.busy,
                            modifier = Modifier.testTag("home-exam-minutes-$minutes"),
                        ) { Text("$minutes 分钟") }
                    }
                }
            }
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
                    StudyModeSelectorButton(pref, onUpdatePreference = { next, changed ->
                        pref = StudyModeStore.save(library, next)
                        if (changed && !ui.busy) onRead(focus.id)
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
