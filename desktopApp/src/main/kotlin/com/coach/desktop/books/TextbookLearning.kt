package com.coach.desktop.books

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.v7.Desk
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.flow.first

/** Focus changes must run inside the UI frame, including the timeout fallback. */
internal suspend fun restoreCitationFocus(
    tryCitation: () -> Boolean,
    fallback: () -> Unit,
    finished: () -> Unit,
    timeoutNanos: Long = 2_000_000_000L,
) {
    var start: Long? = null
    while (true) {
        val done = withFrameNanos { frame ->
            val firstFrame = start ?: frame.also { start = it }
            when {
                tryCitation() -> { finished(); true }
                frame - firstFrame >= timeoutNanos -> { fallback(); finished(); true }
                else -> false
            }
        }
        if (done) return
    }
}

@Composable
internal fun TextbookLearning(ui: LibraryUi, support: BookLearningSupport, scroll: ScrollState, lessonSourceFocus: FocusRequester, practiceSourceFocus: FocusRequester,
    returnFocus: SourceFocus?, explanationReply: MutableState<BookLearningReply?>, answerExplain: AnswerExplain?, answerExplainReply: MutableState<BookLearningReply?>,
    sourceResolver: BookSourceResolver,
    citationFocus: MutableMap<Int, FocusRequester>, citationIndex: Int,
    onFocusRestored: () -> Unit, onBack: () -> Unit, onSource: () -> Unit, onCitation: (Int, BookSourceTarget) -> Unit, onCitationRefused: () -> Unit, onPracticeSource: (Practice) -> Unit, onLesson: (Int) -> Unit,
    onSettings: () -> Unit, onAnswer: (PracticeAnswer) -> Unit, onReviews: () -> Unit, onQuestions: () -> Unit,
    onRecapContinue: () -> Unit) {
    val book = checkNotNull(ui.book)
    val lesson = book.lessons.getOrNull(book.lessonIndex)
    LaunchedEffect(returnFocus, ui.busy) {
        if (ui.busy) return@LaunchedEffect
        // The restored button needs a layout frame before it can accept focus.
        if (returnFocus != null) withFrameNanos { }
        when (returnFocus) {
            SourceFocus.LESSON -> lessonSourceFocus.requestFocus()
            SourceFocus.PRACTICE -> practiceSourceFocus.requestFocus()
            SourceFocus.CITATION -> {
                // A changed file may turn the old button into read-only text while away.
                // The row verifies asynchronously, so allow its button to reappear first.
                restoreCitationFocus(
                    tryCitation = { citationFocus[citationIndex]?.requestFocus() == true },
                    fallback = { lessonSourceFocus.requestFocus() },
                    finished = onFocusRestored,
                )
                return@LaunchedEffect
            }
            null -> return@LaunchedEffect
        }
        onFocusRestored()
    }
    Desk(crumb = book.title, onBack = onBack, backLabel = "返回书架", showNavigation = false, hasMargin = false,
        status = if (lesson == null) "本书学习结束" else "第 ${book.lessonIndex + 1} / ${book.lessons.size} 节", directory = {
            Text("学习路线", style = MaterialTheme.typography.titleMedium)
            book.lessons.forEachIndexed { index, item -> V7TextButton({ onLesson(index) }, enabled = !ui.busy) { Text("${index + 1}. ${item.title}") } }
        }) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp).testTag("textbook-learning"),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (lesson == null) "这一遍学完了" else ui.learningPath.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = com.coach.ui.theme.CoachTheme.colors.fgSecondary)
                V7TextButton(onSettings, enabled = !ui.busy, modifier = Modifier.testTag("question-settings")) { Text("出题：${book.questionMode.label}") }
            }
            if (lesson == null) {
                Text("已走完「${book.title}」的 ${book.lessons.size} 节内容。", style = MaterialTheme.typography.titleLarge)
                Text("可以从目录回看。读完和掌握是两回事，复习时再试着用自己的话说明。")
                PaperButton({ onLesson(0) }, enabled = !ui.busy) { Text("从头回看") }
            } else {
                Text(lesson.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("lesson-title"))
                // 导语和整节原文入口并成一行小字：这里只按页打开，讲解后面的出处才指到具体那一块，两处不再一样重。
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ui.learningPath == LearningPath.READ_AND_LEARN) "先理解讲解，再试着回答。" else "先试着回答，答后再看讲解；原文可以主动打开。",
                        style = MaterialTheme.typography.bodySmall, color = com.coach.ui.theme.CoachTheme.colors.fgSecondary, modifier = Modifier.weight(1f, fill = false))
                    V7TextButton(onSource, enabled = !ui.busy, modifier = Modifier.focusRequester(lessonSourceFocus).testTag("open-lesson-source")) { Text("本节原文 · ${book.pageLabel(lesson.firstPage)}") }
                }
                // 做题学：累计到六题出知识小结。两张小结同屏时这一节在上、六题小结在下，继续/完成只留最下面一组。
                val recapOpen = ui.learningPath == LearningPath.QUESTION_FIRST && ui.ladder.recapOpen
                val recap: @Composable () -> Unit = {
                    if (recapOpen) KnowledgeRecapCard(ui.ladder) { onRecapContinue() }
                }
                val practice = remember(book.id, lesson.id, book.questionMode, ui.questionsOpen) {
                    if (ui.questionsOpen) StudyCoachCourse.practice(book, lesson, book.questionMode) else null
                }
                val sourceRow: @Composable (List<BookPassage>) -> Unit = { sources ->
                    BookSourceRow(sources, book, sourceResolver,
                        focus = { i -> citationFocus.getOrPut(i) { FocusRequester() } }, onOpen = onCitation,
                        onRefused = { onCitationRefused() })
                }
                // 配对只决定「按本节原文现出题」；没连上 Mac 时这一节仍用原文出的题，读书和做题不依赖配对。
                val sourceSection: @Composable () -> Unit = {
                    // 边看边学：先看讲解，再动手做题。
                    if (ui.learningPath == LearningPath.READ_AND_LEARN) {
                        BookLessonExplanation(book, lesson, support, reply = explanationReply, sources = sourceRow)
                        if (!ui.questionsOpen && book.questionMode != QuestionMode.NONE) {
                            PaperButton(onQuestions, enabled = !ui.busy, modifier = Modifier.testTag("guided-start-questions")) { Text("继续做题") }
                        }
                    }
                    if (book.questionMode == QuestionMode.NONE) Text("当前已关闭出题，可在出题设置中重新选择。", Modifier.testTag("book-questions-off"))
                    else if (ui.questionsOpen) {
                        if (practice == null) Text("这一节的原文太少，暂时无法生成选择题。可以继续学，或改为简答。")
                        else {
                            SourcePractice(lesson.id, book.questionMode, practice, ui.answer, onAnswer, { onPracticeSource(practice) }, !ui.busy, practiceSourceFocus, showReference = false)
                            if (ui.reviews.any { it.book == book.id && it.lesson == lesson.id && it.mode == book.questionMode }) {
                                Text("已加入复习，可在今天查看原题。", modifier = Modifier.testTag("local-review-saved"),
                                    style = MaterialTheme.typography.bodySmall, color = com.coach.ui.theme.CoachTheme.colors.fgSecondary)
                                V7TextButton(onReviews, enabled = !ui.busy, modifier = Modifier.testTag("open-local-reviews")) { Text("查看复习") }
                            }
                        }
                    }
                    // 做题学：答完就讲，讲解在题下面；原文仍要点开才出现。讲完用小结给出下一步。
                    val question = practice
                    if (ui.learningPath == LearningPath.QUESTION_FIRST && ui.answer.revealed && question != null) {
                        if (answerExplain != null) LessonAnswerExplanation(
                            book, lesson, question, answerExplain, answerExplainReply, sources = sourceRow,
                            onOpenSourcePage = onSource,
                        ) else BookLessonExplanation(book, lesson, support, reply = explanationReply, sources = sourceRow)
                        val lessonNav: @Composable () -> Unit = { LessonNav(book, ui.busy, onLesson) }
                        // 六题小结开着时，这一节只报刚才那一题、不铺按钮；走法让给最下面那张卡的「接着做题」。
                        LessonSummaryCard(book, lesson, question, ui.answer, nav = if (recapOpen) null else lessonNav)
                        recap()
                    } else {
                        recap()
                        LessonNav(book, ui.busy, onLesson)
                    }
                }
                val gate = answerExplain
                if (ui.learningPath == LearningPath.QUESTION_FIRST && book.questionMode == QuestionMode.CHOICE && gate?.ask != null) {
                    GeneratedLessonPractice(book, lesson, gate, practiceSourceFocus, sourceRow, onPracticeSource, onSource,
                        fallback = sourceSection) { LessonNav(book, ui.busy, onLesson) }
                } else {
                    sourceSection()
                }
            }
        }
    }
}

@Composable
private fun LessonNav(book: Textbook, busy: Boolean, onLesson: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PaperButton({ onLesson(book.lessonIndex - 1) }, enabled = !busy && book.lessonIndex > 0,
            modifier = Modifier.testTag("previous-lesson")) { Text("上一节") }
        PrimaryButton({ onLesson(book.lessonIndex + 1) }, enabled = !busy, modifier = Modifier.testTag("next-lesson")) {
            Text(if (book.lessonIndex + 1 == book.lessons.size) "读完这一节" else "继续下一节")
        }
    }
}
