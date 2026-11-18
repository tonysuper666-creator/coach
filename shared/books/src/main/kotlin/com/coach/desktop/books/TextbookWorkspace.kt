package com.coach.desktop.books

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import com.coach.desktop.reader.ReaderSession
import com.coach.desktop.reader.toReaderInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.file.Path

@Composable
internal fun TextbookWorkspace(controller: TextbookController, drafts: AnswerDrafts = rememberAnswerDrafts(controller), loadOnMount: Boolean = true,
    support: BookLearningSupport? = null,
    answerExplain: AnswerExplain? = null,
    sourceResolver: BookSourceResolver = remember(controller.library) { BookSourceResolver(controller.library) },
    releasePickedBook: (Path) -> Unit = {}, chooseBook: suspend () -> Path?) {
    val learningSupport = support ?: remember(controller.library) { PairedBookLearningSupport(controller.library) }
    val ui by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var practiceSource by remember { mutableStateOf<PracticeSource?>(null) }
    var returnFocus by remember { mutableStateOf<SourceFocus?>(null) }
    var sourceOrigin by remember { mutableStateOf(SourceFocus.LESSON) }
    var citationIndex by remember { mutableIntStateOf(0) }
    var readerHasFocus by remember { mutableStateOf(false) }
    var noteReader by remember { mutableStateOf<ReaderSession?>(null) }
    // Both immediate and late restore requests target the page that handles
    // paging keys. Focusing this outer Surface would take those keys away.
    val readerFocus = remember { FocusRequester() }
    // 翻页后恢复阅读器焦点：续体可能在后台线程恢复（控制器动作在 IO 上完成），不能在那里直接动 Compose 焦点；
    // 只发一张"焦点票"，由下面的 LaunchedEffect 在组合所在的 UI 上下文里核对票上的书和出处仍是当前的，才真正请求焦点。
    var readerFocusTicket by remember { mutableStateOf<ReaderFocusTicket?>(null) }
    ReaderFocusTicketEffect(readerFocusTicket, ui) { readerFocus.requestFocus() }
    val lessonSourceFocus = remember { FocusRequester() }
    val practiceSourceFocus = remember { FocusRequester() }
    val lessonId = ui.book?.lessons?.getOrNull(ui.book?.lessonIndex ?: -1)?.id
    val explanationReply = remember(ui.book?.id, lessonId, learningSupport) { mutableStateOf<BookLearningReply?>(null) }
    // 答完就讲的话由另一条通道取，重开窗口会重新问一次；它不跟原文页的讲解共用缓存。
    val answerExplainReply = remember(ui.book?.id, lessonId, answerExplain) { mutableStateOf<BookLearningReply?>(null) }
    val citationFocus = remember(ui.book?.id, lessonId) { mutableMapOf<Int, FocusRequester>() }
    val learningScroll = remember(ui.book?.id, ui.book?.lessonIndex) { ScrollState(0) }
    LaunchedEffect(controller) {
        val current = controller.state.value
        if (loadOnMount && current.catalog.isEmpty() && current.book == null && current.prompt == null) controller.load()
    }
    LaunchedEffect(ui.screen, ui.source) {
        if (ui.screen == BookScreen.READER && ui.source != null) readerFocus.requestFocus()
    }
    fun run(action: suspend () -> Unit) { scope.launch { action() } }
    fun returnFromReader() {
        if (ui.source != null) {
            // Only newly composed, ready rows may supply focus targets on return.
            if (sourceOrigin == SourceFocus.CITATION) citationFocus.clear()
            returnFocus = sourceOrigin
        }
        run { controller.back() }
    }
    BookWorkspaceBackHandler(ui.prompt != null || ui.screen != BookScreen.HOME) {
        when {
            ui.prompt != null -> run { controller.dismissPrompt() }
            ui.screen == BookScreen.READER -> returnFromReader()
            else -> run { controller.back() }
        }
    }
    fun openPracticeSource(practice: Practice) {
        val book = ui.book ?: return
        val lesson = ui.review?.let { review -> book.lessons.find { it.id == review.lesson } }
            ?: book.lessons.getOrNull(book.lessonIndex) ?: return
        // 本题依据取自整节正文（lesson.text 以 "\n\n" 拼接各正文块），
        // 所以整段查找后再回落到命中的那个块，才能定位到块所在页。
        val body = lesson.blocks.filterNot { it.heading }
        val spans = ArrayList<Triple<SourceBlock, Int, Int>>(body.size)
        var cursor = 0
        for (block in body) {
            if (spans.isNotEmpty()) cursor += 2 // lesson.text 用 "\n\n" 拼接正文块
            spans += Triple(block, cursor, cursor + block.text.length)
            cursor += block.text.length
        }
        val hits = Regex(Regex.escape(practice.reference)).findAll(lesson.text).toList()
        val anchor = when (hits.size) {
            1 -> {
                // 依据可能跨多个正文块；只有命中覆盖的块同属一页时，页锚点才是确定的。
                val hit = hits.single()
                val covered = spans.filter { it.third > hit.range.first && it.second <= hit.range.last }
                val pages = covered.map { it.first.page }.distinct()
                when {
                    covered.isEmpty() ->
                        PracticeSource(null, null, "无法确认原句：未找到本题依据所在的位置。")
                    pages.size > 1 ->
                        PracticeSource(null, null, "无法确认原句：本题依据跨越多页，无法定位到单一页面。")
                    else -> PracticeSource(pages.single(), practice.reference, null)
                }
            }
            0 -> PracticeSource(null, null, "无法确认原句：未找到本题依据所在的位置。")
            else -> PracticeSource(null, null, "无法确认原句：本题依据出现多次。")
        }
        practiceSource = anchor
        sourceOrigin = SourceFocus.PRACTICE
        run { controller.source(); anchor.page?.let { controller.page(it) } }
    }
    // 外壳（WindowsTextbookHost）已经按系统或 coach.windows.theme 选好明暗；正文沿用同一个选择，
    // 不再让内层按系统重选，否则外壳浅、正文深。
    CoachTheme(com.coach.ui.theme.CoachTheme.colors.dark) {
        Surface(Modifier.fillMaxSize().testTag("windows-textbooks").onPreviewKeyEvent { event ->
            val editing = noteReader?.takeIf { ui.screen == BookScreen.READER && it.noting }
            val noteKey = event.toReaderInput()
            if (editing != null && noteKey != null && editing.onKey(noteKey.first, noteKey.second)) true
            else if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && ui.screen == BookScreen.READER &&
                ui.source != null && ui.prompt == null && !ui.busy) {
                returnFromReader(); true
            } else false
        }.onFocusChanged { readerHasFocus = it.hasFocus }
            .focusable(enabled = ui.screen == BookScreen.READER && ui.source != null),
            color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
            Box(Modifier.fillMaxSize()) {
                Column {
                    ui.importing?.let { progress ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("book-import-progress"),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(if (progress.total == 0) "正在准备教材…" else
                                "${if (progress.recognizing) "正在识别文字" else "正在读取教材"} · 第 ${progress.page} / ${progress.total} 页",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(controller::cancelImport, Modifier.testTag("book-import-cancel")) { Text("取消导入") }
                        }
                    }
                    ui.error?.let { error -> Text(error, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("book-error")) }
                    val book = ui.book
                    when {
                        ui.screen == BookScreen.HOME || book == null -> LibraryHome(ui,
                            onImport = { run { chooseBook()?.let { picked ->
                                try { controller.importBook(picked) } finally { releasePickedBook(picked) }
                            } } },
                            onSelect = { run { controller.select(it) } }, onRead = { run { if (drafts.flush()) controller.learn(LearningPath.READ) } },
                            onLearn = { run { if (drafts.flush()) controller.learn() } },
                            onPath = { path -> run { if (drafts.flush()) controller.learn(path) } })
                        ui.screen == BookScreen.REVIEWS -> LocalBookReviews(ui,
                            onBack = { run { controller.back() } },
                            onOpen = { entry -> run { if (drafts.flush()) controller.openReview(entry) } })
                        ui.screen == BookScreen.REVIEWING && ui.review != null -> {
                            val storedReview = checkNotNull(ui.review)
                            val review = storedReview.copy(answer = drafts.currentReview(storedReview))
                            LocalBookReviewQuestion(review, !ui.busy, practiceSourceFocus, returnFocus,
                                onFocusRestored = { returnFocus = null }, onBack = { run { controller.back() } },
                                onSource = { openPracticeSource(review.practice) },
                                onAnswer = { drafts.saveReview(review, it) })
                        }
                        ui.screen == BookScreen.READER -> TextbookReader(ui, controller.library, learningSupport,
                            practiceSource = practiceSource, pageFocus = readerFocus,
                            onNoteSession = { noteReader = it },
                            onPage = { page ->
                                val restoreFocus = ui.source != null && readerHasFocus
                                val startBookId = ui.book?.id; val startSource = ui.source
                                run {
                                    controller.page(page)
                                    if (restoreFocus) awaitReaderFocusRestore(startBookId, startSource, { readerHasFocus }, { controller.state.value }) {
                                        readerFocusTicket = it
                                    }
                                }
                            }, onBack = { returnFromReader() },
                            onLearn = { run { if (drafts.flush()) controller.learn(LearningPath.QUESTION_FIRST) } })
                        else -> {
                            val lesson = book.lessons.getOrNull(book.lessonIndex)
                            val displayed = if (lesson == null) ui else ui.copy(
                                answer = drafts.current(book.id, lesson.id, book.questionMode, ui.answer))
                            TextbookLearning(displayed, learningSupport, learningScroll, lessonSourceFocus, practiceSourceFocus, returnFocus,
                            explanationReply, answerExplain, answerExplainReply, sourceResolver, citationFocus, citationIndex,
                            onFocusRestored = { returnFocus = null }, onBack = { run { controller.back() } },
                            onSource = { practiceSource = null; sourceOrigin = SourceFocus.LESSON; run { controller.source() } },
                            onCitation = { index, target ->
                                val current = controller.state.value
                                if (current.screen == BookScreen.LEARNING && current.book?.id == target.book &&
                                    current.book.lessons.getOrNull(current.book.lessonIndex)?.id == lesson?.id && lesson != null) {
                                    citationIndex = index
                                    sourceOrigin = SourceFocus.CITATION
                                    practiceSource = PracticeSource(target.pdfPage, target.quote, null, target)
                                    run { controller.citationSource(target.book, lesson.id, target.pdfPage) }
                                }
                            },
                            onCitationRefused = {
                                val current = controller.state.value
                                if (current.screen == BookScreen.LEARNING && current.book?.id == book.id &&
                                    current.book.lessons.getOrNull(current.book.lessonIndex)?.id == lesson?.id) {
                                    runCatching { lessonSourceFocus.requestFocus() }
                                }
                            },
                            onPracticeSource = { openPracticeSource(it) }, onLesson = { run { drafts.flush(); controller.lesson(it) } },
                            onSettings = { run { controller.questionSettings() } }, onAnswer = { answer ->
                                ui.book?.let { current ->
                                    current.lessons.getOrNull(current.lessonIndex)?.let { lesson ->
                                        drafts.save(current.id, lesson.id, current.questionMode, answer)
                                    }
                                }
                            }, onReviews = { run { if (drafts.flush()) controller.showReviews() } },
                            onQuestions = { run { controller.beginQuestions() } },
                            onRecapContinue = { run { controller.recapContinue() } })
                        }
                    }
                }
                // Saving a reading bookmark must not resize the page being paginated.
                // Overlay activity so the desk's bounds stay intact.
                if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (ui.prompt != null && ui.book != null) BookEntryPrompt(ui,
                onDismiss = { run { controller.dismissPrompt() } }, onRead = { run { controller.read() } },
                onEnable = { run { controller.enableLearning() } }, onMode = { run { controller.mode(it) } },
                onPath = { run { controller.chooseLearningPath(it) } })
        }
    }
}

internal enum class SourceFocus { LESSON, PRACTICE, CITATION }
