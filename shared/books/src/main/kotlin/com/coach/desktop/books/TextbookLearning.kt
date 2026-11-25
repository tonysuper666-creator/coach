package com.coach.desktop.books

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coach.ui.adaptive.Hinge
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

/** 学习页的「题型」按钮用完整说法；设置弹窗里的短名（「选择」「简答」）留给小标签。 */
private val QuestionMode.pageLabel: String
    get() = when (this) {
        QuestionMode.NONE -> "关闭"
        QuestionMode.CHOICE -> "选择题"
        QuestionMode.SHORT_ANSWER -> "简答题"
    }

@Composable
internal fun TextbookLearning(ui: LibraryUi, support: BookLearningSupport, scroll: ScrollState, lessonSourceFocus: FocusRequester, practiceSourceFocus: FocusRequester,
    returnFocus: SourceFocus?, explanationReply: MutableState<BookLearningReply?>, answerExplain: AnswerExplain?, answerExplainReply: MutableState<BookLearningReply?>,
    sourceResolver: BookSourceResolver,
    citationFocus: MutableMap<Int, FocusRequester>, citationIndex: Int,
    onFocusRestored: () -> Unit, onBack: () -> Unit, onSource: () -> Unit, onCitation: (Int, BookSourceTarget) -> Unit, onCitationRefused: () -> Unit, onPracticeSource: (Practice) -> Unit, onLesson: (Int) -> Unit,
    onQuestionMode: (QuestionMode) -> Unit, onAnswer: (PracticeAnswer) -> Unit, onReviews: () -> Unit, onQuestions: () -> Unit,
    onRecapContinue: () -> Unit, onPair: () -> Unit = onSource, hinge: Hinge? = LocalBookHinge.current, library: TextbookLibrary? = null) {
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
        showFocusToggle = false, scrollDirectory = false, paperMaxWidth = if (hinge != null) Dp.Infinity else Dp.Unspecified,
        status = if (lesson == null) "本书学习结束" else "第 ${book.lessonIndex + 1} / ${book.lessons.size} 节", directory = {
            Column(Modifier.fillMaxSize()) {
                Text("学习路线", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(book.lessons, key = { idx, item -> "${idx}:${item.id}" }) { index, item ->
                        V7TextButton({ onLesson(index) }, enabled = !ui.busy) { Text("${index + 1}. ${item.title}") }
                    }
                }
            }
        }) {
        val density = LocalDensity.current
        var learningOriginDp by remember { mutableStateOf<Pair<Float, Float>?>(null) }
        val rightScroll = rememberScrollState()
        val lessonContent: @Composable (Boolean) -> Unit = { includeNav ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (lesson == null) "这一遍学完了" else ui.learningPath.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = com.coach.ui.theme.CoachTheme.colors.fgSecondary)
                QuestionTypeButton(book.questionMode, enabled = !ui.busy, onSelect = onQuestionMode)
            }
            if (lesson == null) {
                Text("已走完「${book.title}」的 ${book.lessons.size} 节内容。", style = MaterialTheme.typography.titleLarge)
                Text("可以从目录回看。读完和掌握是两回事，复习时再试着用自己的话说明。")
                PaperButton({ onLesson(0) }, enabled = !ui.busy) { Text("从头回看") }
            } else {
                Text(lesson.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("lesson-title"))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ui.learningPath == LearningPath.READ_AND_LEARN) "先理解讲解，再试着回答。" else "先试着回答，答后再看讲解；原文可以主动打开。",
                        style = MaterialTheme.typography.bodySmall, color = com.coach.ui.theme.CoachTheme.colors.fgSecondary, modifier = Modifier.weight(1f, fill = false))
                    V7TextButton(onSource, enabled = !ui.busy, modifier = Modifier.focusRequester(lessonSourceFocus).testTag("open-lesson-source")) { Text("本节原文 · ${book.pageLabel(lesson.firstPage)}") }
                }
                val practice = remember(book.id, lesson.id, book.questionMode, ui.questionsOpen) {
                    if (ui.questionsOpen && book.questionMode == QuestionMode.SHORT_ANSWER && lesson.text.isNotBlank()) {
                        Practice("合上原文，用自己的话说明「${lesson.title}」的主要内容，并举一个例子。", lesson.text, "", emptyList())
                    } else null
                }
                val sourceRow: @Composable (List<BookPassage>) -> Unit = { sources ->
                    BookSourceRow(sources, book, sourceResolver,
                        focus = { i -> citationFocus.getOrPut(i) { FocusRequester() } }, onOpen = onCitation,
                        onRefused = { onCitationRefused() })
                }
                val sourceSection: @Composable () -> Unit = {
                    if (ui.learningPath == LearningPath.READ_AND_LEARN) {
                        BookLessonExplanation(book, lesson, support, reply = explanationReply, sources = sourceRow, onRepair = onPair)
                        if (!ui.questionsOpen && book.questionMode != QuestionMode.NONE) {
                            PaperButton(onQuestions, enabled = !ui.busy, modifier = Modifier.testTag("guided-start-questions")) { Text("继续自测") }
                        }
                    }
                    if (book.questionMode == QuestionMode.NONE) Text("当前已关闭出题，可在「题型」里重新选择。", Modifier.testTag("book-questions-off"))
                    else if (ui.questionsOpen) {
                        if (practice == null) Text("本节选择题需登录 Coach 服务生成，可先点击「本节原文」在阅读器中伴读学习。")
                        else {
                            SourcePractice(lesson.id, book.questionMode, practice, ui.answer, onAnswer, { onPracticeSource(practice) }, !ui.busy, practiceSourceFocus, showReference = false)
                            if (ui.reviews.any { it.book == book.id && it.lesson == lesson.id && it.mode == book.questionMode }) {
                                Text("已加入复习，可在今天查看原题。", modifier = Modifier.testTag("local-review-saved"),
                                    style = MaterialTheme.typography.bodySmall, color = com.coach.ui.theme.CoachTheme.colors.fgSecondary)
                                V7TextButton(onReviews, enabled = !ui.busy, modifier = Modifier.testTag("open-local-reviews")) { Text("查看复习") }
                            }
                        }
                    }
                    val question = practice
                    if (ui.learningPath == LearningPath.QUESTION_FIRST && ui.answer.revealed && question != null) {
                        if (answerExplain != null) LessonAnswerExplanation(
                            book, lesson, question, answerExplain, answerExplainReply, sources = sourceRow,
                            onOpenSourcePage = onPair, library = library,
                        ) else BookLessonExplanation(book, lesson, support, reply = explanationReply, sources = sourceRow, onRepair = onPair)
                        val lessonNav: @Composable () -> Unit = { if (includeNav) LessonNav(book, ui.busy, onLesson) }
                        LessonSummaryCard(book, lesson, question, ui.answer, nav = if (!includeNav) null else lessonNav)
                    } else {
                        if (includeNav) LessonNav(book, ui.busy, onLesson)
                    }
                }
                val gate = answerExplain
                if (ui.learningPath == LearningPath.QUESTION_FIRST && book.questionMode == QuestionMode.CHOICE && gate?.ask != null) {
                    GeneratedLessonPractice(book, lesson, gate, practiceSourceFocus, sourceRow, onPracticeSource, onPair,
                        fallback = sourceSection) { if (includeNav) LessonNav(book, ui.busy, onLesson) }
                } else {
                    sourceSection()
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { coords ->
            val pos = coords.positionInRoot()
            learningOriginDp = (pos.x / density.density) to (pos.y / density.density)
        }) {
            val verticalSplit = bookVerticalSplit(maxWidth.value, maxHeight.value, learningOriginDp?.first ?: 0f, hinge)
            val tabletopSplit = if (verticalSplit == null) bookTabletopSplit(maxWidth.value, maxHeight.value, learningOriginDp?.second ?: 0f, hinge) else null
            if (verticalSplit != null) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(verticalSplit.leftDp.dp).fillMaxHeight().testTag("learning-half-fold-left")) {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).testTag("textbook-learning"),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) { lessonContent(true) }
                        if (scroll.maxValue > 0) BookScrollbar(scroll,
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp).testTag("learning-scrollbar"))
                    }
                    Spacer(Modifier.width(verticalSplit.gapDp.dp).fillMaxHeight().testTag("learning-half-fold-hinge"))
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rightScroll).padding(16.dp).testTag("learning-half-fold-right"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(lesson?.let { "本节原文 · ${book.pageLabel(it.firstPage)}" } ?: "学习路线",
                            style = MaterialTheme.typography.titleMedium)
                        lesson?.text?.takeIf { it.isNotBlank() }?.let { body ->
                            Text(body.take(4_000), style = MaterialTheme.typography.bodyMedium, color = com.coach.ui.theme.CoachTheme.colors.fgSecondary)
                        }
                        if (lesson != null) {
                            V7TextButton(onSource, enabled = !ui.busy) { Text("打开完整原文") }
                        }
                        Text("章节目录", style = MaterialTheme.typography.titleSmall)
                        book.lessons.forEachIndexed { index, item ->
                            V7TextButton({ onLesson(index) }, enabled = !ui.busy) { Text("${index + 1}. ${item.title}") }
                        }
                    }
                }
            } else if (tabletopSplit != null) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.height(tabletopSplit.topDp.dp).fillMaxWidth().testTag("learning-tabletop-content")) {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).testTag("textbook-learning"),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) { lessonContent(false) }
                        if (scroll.maxValue > 0) BookScrollbar(scroll,
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp).testTag("learning-scrollbar"))
                    }
                    Spacer(Modifier.height(tabletopSplit.gapDp.dp).fillMaxWidth().testTag("learning-tabletop-hinge"))
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rightScroll).padding(16.dp).testTag("learning-tabletop-controls"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (lesson != null) LessonNav(book, ui.busy, onLesson)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (lesson != null) V7TextButton(onSource, enabled = !ui.busy) { Text("本节原文") }
                            QuestionTypeButton(book.questionMode, enabled = !ui.busy, onSelect = onQuestionMode, tag = null)
                        }
                    }
                }
            } else {
                // 学习页一屏放不下时，右侧有一道 V7 细滚动条：做题、小结都在同一个滚动区里，
                // 拖得动滑块就知道下面还有卡，滚到底能看到最后的操作（desktop-polish W5、B9）。
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp).testTag("textbook-learning"),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) { lessonContent(true) }
                if (scroll.maxValue > 0) BookScrollbar(scroll,
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp).testTag("learning-scrollbar"))
            }
        }
    }
}

/** 就地题型菜单：点开即选，不挂 BookPrompt，学习页继续留在原地。 */
@Composable
private fun QuestionTypeButton(
    mode: QuestionMode,
    enabled: Boolean,
    onSelect: (QuestionMode) -> Unit,
    tag: String? = "question-settings",
) {
    var open by remember { mutableStateOf(false) }
    val colors = com.coach.ui.theme.CoachTheme.colors
    Box {
        V7TextButton(
            { open = true },
            enabled = enabled,
            modifier = if (tag == null) Modifier else Modifier.testTag(tag),
        ) { Text("题型：${mode.pageLabel}") }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.surface1,
            modifier = if (tag == null) Modifier else Modifier.testTag("question-settings-menu"),
        ) {
            for (option in listOf(QuestionMode.CHOICE, QuestionMode.SHORT_ANSWER, QuestionMode.NONE)) {
                val current = option == mode
                DropdownMenuItem(
                    text = {
                        Text(
                            option.pageLabel + if (current) " · 当前" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.fgPrimary,
                        )
                    },
                    onClick = {
                        open = false
                        if (!current) onSelect(option)
                    },
                    modifier = if (tag == null) Modifier else Modifier.testTag("question-mode-${option.name}"),
                )
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
