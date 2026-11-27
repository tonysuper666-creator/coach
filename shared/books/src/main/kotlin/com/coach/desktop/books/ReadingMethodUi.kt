package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单书伴读上面的学法条。课程书不走这里。
 * 学法写进当前章的练习草稿，但要等草稿从库里读回来再写，避免空草稿盖掉已有作答。
 */
@Composable
internal fun ReadingMethodPanel(
    book: Textbook,
    chapter: ChapterSectionGroup?,
    draft: ChapterPracticeDraft,
    draftReady: Boolean,
    library: TextbookLibrary,
    onDraftChange: (ChapterPracticeDraft) -> Unit,
    onJumpPage: (Int) -> Unit,
    onCommitChoice: (BookLesson, Int, Practice, PracticeAnswer) -> Unit,
) {
    val colors = CoachTheme.colors
    var bookState by remember(book.id) { mutableStateOf<ReadingMethodBookState?>(null) }
    var graded by remember(book.id, chapter?.chapterId) { mutableStateOf<List<ReadingCheck>>(emptyList()) }
    val latestDraft = rememberUpdatedState(draft)
    val latestState = rememberUpdatedState(bookState)
    val plan = ReadingMethodFlow.planOf(chapter)

    LaunchedEffect(book.id, chapter?.chapterId, draftReady, book.pages.count { it.ocr }, book.pages.size) {
        if (!draftReady) return@LaunchedEffect
        val signals = withContext(Dispatchers.IO) {
            val sourceName = runCatching { library.sourceFilename(book.id) }.getOrNull()
            runCatching { ReadingMethodSignalsReader.fromBook(book, sourceName = sourceName) }.getOrDefault(ReadingMethodSignals())
        }
        var raw = latestDraft.value.readingMethodJson
        if (raw.isBlank() && latestState.value == null) {
            raw = withContext(Dispatchers.IO) {
                runCatching {
                    library.withCourseDatabase { db -> ChapterProgressStore.latestReadingMethodJson(db, book.id) }
                }.getOrNull()
            }.orEmpty()
        }
        val disk = ReadingMethodBookState.decode(raw)
        val resolved = ReadingMethodBookState.resolve(latestState.value ?: disk, signals).absorb(disk)
        bookState = resolved
        val encoded = resolved.encode()
        val current = latestDraft.value
        if (current.readingMethodJson != encoded) {
            onDraftChange(current.copy(readingMethodJson = encoded))
        }
    }

    LaunchedEffect(book.id, chapter?.chapterId) {
        val current = chapter ?: return@LaunchedEffect
        val pack = withContext(Dispatchers.IO) {
            runCatching { library.cachedChapterCompanion(book.id, current.chapterId) }.getOrNull()
        } ?: return@LaunchedEffect
        val incoming = pack.pretest.map { ReadingMethodFlow.fromQuiz(it, current.firstPage) }
        val answered = latestState.value?.chapters?.get(current.chapterId)
            ?.values
            ?.flatMap { it.answers.keys }
            ?.toSet()
            .orEmpty()
        if (answered.isNotEmpty() && answered.any { it !in incoming.map { check -> check.id }.toSet() }) return@LaunchedEffect
        graded = incoming
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val state = bookState
        if (!draftReady || state == null || plan == null && chapter != null) {
            Text("正在按这本书选学法…", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            return@Column
        }
        val method = state.active
        fun commit(next: ReadingMethodBookState) {
            bookState = next
            if (draftReady) onDraftChange(latestDraft.value.copy(readingMethodJson = next.encode()))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("学法：${method.title}", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary, modifier = Modifier.weight(1f))
            V7TextButton(
                onClick = { commit(state.switched(method.next())) },
                modifier = Modifier.testTag("reading-method-switch"),
            ) { Text("换一种") }
            if (!state.alreadyRead) {
                V7TextButton(
                    onClick = { commit(state.markedAlreadyRead()) },
                    modifier = Modifier.testTag("reading-method-mark-read"),
                ) { Text("标成读过了") }
            }
        }
        Text(state.reason, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.testTag("reading-method-reason"))
        if (plan == null) {
            Text("翻到正文的一章，再按这个学法走。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            return@Column
        }
        val progress = state.progress(plan.id, method)
        Text(
            stepTitle(method, progress.step),
            style = MaterialTheme.typography.labelLarge,
            color = colors.actionPrimary,
            modifier = Modifier.testTag("reading-method-step"),
        )
        when (method) {
            ReadingMethod.BEFORE -> BeforeFlow(plan, graded, progress, onProgress = { commit(state.withProgress(plan.id, method, it)) })
            ReadingMethod.DURING -> DuringFlow(
                plan, graded, progress,
                onProgress = { commit(state.withProgress(plan.id, method, it)) },
                onCommitChoice = onCommitChoice,
                chapter = chapter,
            )
            ReadingMethod.AFTER -> AfterFlow(
                plan, graded, progress,
                onProgress = { commit(state.withProgress(plan.id, method, it)) },
                onJumpPage = onJumpPage,
                onCommitChoice = onCommitChoice,
                chapter = chapter,
            )
        }
    }
}

@Composable
private fun BeforeFlow(
    plan: ReadingChapterPlan,
    graded: List<ReadingCheck>,
    progress: ChapterMethodProgress,
    onProgress: (ChapterMethodProgress) -> Unit,
) {
    val checks = remember(plan, graded) { ReadingMethodFlow.beforeChecks(plan, graded) }
    when (progress.step) {
        "PRETEST" -> {
            CheckList(checks, progress, reveal = true, onAnswer = { id, value -> onProgress(ReadingMethodFlow.recordAnswer(progress, id, value)) })
            PaperButton(
                onClick = { onProgress(ReadingMethodFlow.finishBeforePretest(progress, checks)) },
                enabled = ReadingMethodFlow.allAnswered(progress, checks),
                modifier = Modifier.testTag("reading-method-next"),
            ) { Text("做完了，去读") }
        }
        "READ" -> {
            Text("读这一章。读完回到这里，对照刚才写下的预期。", style = MaterialTheme.typography.bodyMedium, color = CoachTheme.colors.fgPrimary)
            PaperButton(
                onClick = { onProgress(ReadingMethodFlow.finishBeforeRead(progress)) },
                modifier = Modifier.testTag("reading-method-next"),
            ) { Text("读完了，对照") }
        }
        "COMPARE" -> {
            CheckList(checks, progress, reveal = true, onAnswer = { _, _ -> })
            PaperButton(
                onClick = { onProgress(ReadingMethodFlow.finishBeforeCompare(progress)) },
                modifier = Modifier.testTag("reading-method-next"),
            ) { Text("这一章对照完了") }
        }
        else -> Text("这一章的读前走完了。", style = MaterialTheme.typography.bodyMedium, color = CoachTheme.colors.fgPrimary)
    }
}

@Composable
private fun DuringFlow(
    plan: ReadingChapterPlan,
    graded: List<ReadingCheck>,
    progress: ChapterMethodProgress,
    onProgress: (ChapterMethodProgress) -> Unit,
    onCommitChoice: (BookLesson, Int, Practice, PracticeAnswer) -> Unit,
    chapter: ChapterSectionGroup?,
) {
    val index = progress.sectionIndex.coerceIn(0, plan.sections.lastIndex)
    val section = plan.sections[index]
    val check = remember(section, graded) { ReadingMethodFlow.duringCheck(section, graded) }
    when (progress.step) {
        "READ" -> {
            Text("读「${section.title}」。读完这一节再停下来。", style = MaterialTheme.typography.bodyMedium, color = CoachTheme.colors.fgPrimary)
            PaperButton(
                onClick = { onProgress(ReadingMethodFlow.finishDuringRead(progress)) },
                modifier = Modifier.testTag("reading-method-next"),
            ) { Text("这一节读完了") }
        }
        "PAUSE" -> {
            CheckList(
                listOf(check),
                progress,
                reveal = true,
                ownWordsTag = true,
                onAnswer = { id, value ->
                    val next = ReadingMethodFlow.recordAnswer(progress, id, value)
                    onProgress(next)
                    commitGraded(chapter, check, value, 0, onCommitChoice)
                },
            )
            PaperButton(
                onClick = { onProgress(ReadingMethodFlow.finishDuringPause(progress, plan.sections.size)) },
                enabled = progress.answers[check.id].orEmpty().isNotBlank(),
                modifier = Modifier.testTag("reading-method-next"),
            ) { Text(if (index + 1 >= plan.sections.size) "这一章走完了" else "下一节") }
        }
        else -> Text("这一章的读中走完了。", style = MaterialTheme.typography.bodyMedium, color = CoachTheme.colors.fgPrimary)
    }
}

@Composable
private fun AfterFlow(
    plan: ReadingChapterPlan,
    graded: List<ReadingCheck>,
    progress: ChapterMethodProgress,
    onProgress: (ChapterMethodProgress) -> Unit,
    onJumpPage: (Int) -> Unit,
    onCommitChoice: (BookLesson, Int, Practice, PracticeAnswer) -> Unit,
    chapter: ChapterSectionGroup?,
) {
    val checks = remember(plan, graded) { ReadingMethodFlow.afterChecks(plan, graded) }
    val colors = CoachTheme.colors
    when (progress.step) {
        "RECALL" -> {
            Text("先不看书，写下这一章你还记得的要点。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            OwnWordsField(
                value = progress.recall,
                onValue = { onProgress(progress.copy(recall = it)) },
                tag = "reading-method-own-words",
            )
            PaperButton(
                onClick = { onProgress(ReadingMethodFlow.finishRecall(progress)) },
                enabled = progress.recall.isNotBlank(),
                modifier = Modifier.testTag("reading-method-next"),
            ) { Text("去做题") }
        }
        "QUIZ" -> {
            CheckList(
                checks,
                progress,
                reveal = false,
                ownWordsTag = true,
                onAnswer = { id, value ->
                    onProgress(ReadingMethodFlow.recordAnswer(progress, id, value))
                    val check = checks.firstOrNull { it.id == id } ?: return@CheckList
                    commitGraded(chapter, check, value, checks.indexOfFirst { it.id == id }.coerceAtLeast(0), onCommitChoice)
                },
            )
            if (checks.any { it.ownWords }) {
                V7TextButton(
                    onClick = {
                        var next = progress
                        checks.filter { it.ownWords && next.answers[it.id].isNullOrBlank() }.forEach { check ->
                            next = ReadingMethodFlow.recordAnswer(next, check.id, ReadingMethodFlow.NEED_BOOK)
                        }
                        onProgress(next)
                    },
                ) { Text("写不出来") }
            }
            PaperButton(
                onClick = { onProgress(ReadingMethodFlow.finishQuiz(progress, checks)) },
                enabled = ReadingMethodFlow.allAnswered(progress, checks),
                modifier = Modifier.testTag("reading-method-next"),
            ) { Text("对一下") }
        }
        "RETURN" -> {
            val wrong = checks.filter { it.id in progress.wrongIds }
            wrong.forEachIndexed { index, check ->
                val back = progress.returnedIds.contains(check.id)
                Text(check.prompt, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                Text(
                    if (check.ownWords) "这题写的是「写不出来」。回书看这一节。" else "这题和原书对不上。回书看这一处。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    V7TextButton(
                        onClick = { onJumpPage(check.page) },
                        modifier = Modifier.testTag(if (index == 0) "reading-method-back-to-book" else "reading-method-back-${check.id}"),
                    ) { Text("回书看") }
                    if (!back) {
                        V7TextButton(onClick = { onProgress(ReadingMethodFlow.markReturned(progress, check.id)) }) { Text("看过了") }
                    } else {
                        Text("已回书看过。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                    }
                }
            }
        }
        else -> Text("这一章的读后走完了。", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
    }
}

@Composable
private fun CheckList(
    checks: List<ReadingCheck>,
    progress: ChapterMethodProgress,
    reveal: Boolean,
    ownWordsTag: Boolean = false,
    onAnswer: (String, String) -> Unit,
) {
    val colors = CoachTheme.colors
    var taggedOwnWords = false
    checks.forEach { check ->
        val given = progress.answers[check.id].orEmpty()
        Column(Modifier.fillMaxWidth().testTag("reading-method-answer-${check.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(check.prompt, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
        if (check.ownWords) {
            val primary = ownWordsTag && !taggedOwnWords
            if (primary) taggedOwnWords = true
            OwnWordsField(
                value = given,
                onValue = { onAnswer(check.id, it) },
                tag = if (primary) "reading-method-own-words" else "reading-method-answer-${check.id}",
            )
        } else {
            check.options.forEach { option ->
                val chosen = given == option
                val show = reveal && given.isNotBlank()
                val border = when {
                    show && option == check.correct -> colors.actionPrimary
                    show && chosen -> colors.fgSecondary
                    chosen -> colors.actionPrimary
                    else -> colors.borderSubtle
                }
                val shape = RoundedCornerShape(8.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (chosen) colors.actionPrimary.copy(alpha = 0.08f) else colors.surface1)
                        .border(1.dp, border, shape)
                        .clickable { onAnswer(check.id, option) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(option, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                }
            }
            if (reveal && given.isNotBlank() && check.correct != null) {
                Text(
                    if (given == check.correct) "对照：这题对了。" else "对照：这题先记下，读的时候看原文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgSecondary,
                )
            }
        }
        }
    }
}

@Composable
private fun OwnWordsField(value: String, onValue: (String) -> Unit, tag: String) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = value,
        onValueChange = onValue,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fgPrimary),
        cursorBrush = SolidColor(colors.fgPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(colors.surface1)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(8.dp)
            .testTag(tag),
    )
}

private fun commitGraded(
    chapter: ChapterSectionGroup?,
    check: ReadingCheck,
    value: String,
    index: Int,
    onCommitChoice: (BookLesson, Int, Practice, PracticeAnswer) -> Unit,
) {
    if (check.ownWords || check.correct == null || chapter == null) return
    val lesson = chapter.sections.firstOrNull { it.id == check.sectionId } ?: chapter.sections.firstOrNull() ?: return
    onCommitChoice(
        lesson,
        index,
        Practice(check.prompt, "", check.correct, check.options, check.page),
        PracticeAnswer(text = value, revealed = true, understood = value == check.correct),
    )
}

private fun stepTitle(method: ReadingMethod, step: String): String = when (step) {
    "PRETEST" -> "读前 · 先做几道"
    "READ" -> if (method == ReadingMethod.BEFORE) "读前 · 再读这一章" else "读中 · 读这一节"
    "COMPARE" -> "读前 · 读完对照"
    "PAUSE" -> "读中 · 停一下"
    "RECALL" -> "读后 · 先回忆"
    "QUIZ" -> "读后 · 再做题"
    "RETURN" -> "读后 · 错的回书看"
    "DONE" -> "${method.title} · 这一章走完了"
    else -> method.title
}
