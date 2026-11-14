package com.coach.desktop.books

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coach.ui.CoachTheme
import com.coach.ui.v7.Desk
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PrimaryButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

/**
 * Answer writes that outlive the bookshelf composition.
 *
 * The workspace is removed from composition whenever the learner switches to 今天 or 课程, so a save
 * launched from its `rememberCoroutineScope` was cancelled while it was still waiting on SQLite. This
 * queue belongs to the host window instead, and every entry carries the book, lesson and question mode
 * it was typed in: a write that waits long enough for the learner to move on still lands on the lesson
 * it came from instead of the one that happens to be open when it finally runs.
 */
internal class AnswerDrafts(private val controller: TextbookController, private val scope: CoroutineScope) {
    private data class Key(val book: String, val lesson: String, val mode: QuestionMode, val review: Boolean = false)

    private val pending = mutableStateMapOf<Key, PracticeAnswer>()
    private var writer: Job? = null

    fun save(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer) {
        pending[Key(book, lesson, mode)] = answer
        startWriter()
    }

    fun current(book: String, lesson: String, mode: QuestionMode, saved: PracticeAnswer): PracticeAnswer =
        pending[Key(book, lesson, mode)] ?: saved

    fun saveReview(entry: LocalBookReview, answer: PracticeAnswer) {
        pending[Key(entry.book, entry.lesson, entry.mode, review = true)] = answer
        startWriter()
    }

    fun currentReview(entry: LocalBookReview): PracticeAnswer =
        pending[Key(entry.book, entry.lesson, entry.mode, review = true)] ?: entry.answer

    /** A failed write remains queued for an explicit retry, including when closing the window. */
    suspend fun flush(): Boolean {
        if (writer?.isActive != true && pending.isNotEmpty()) startWriter()
        writer?.join()
        return pending.isEmpty()
    }

    private fun startWriter() {
        if (writer?.isActive != true) {
            // Start before the owning composition can disappear; finish accepted writes even if it does.
            writer = scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { drain() } }
        }
    }

    private suspend fun drain() {
        while (true) {
            val (key, answer) = pending.entries.firstOrNull()?.toPair() ?: break
            // The controller checks the identity and writes under the same lock, so a lesson switch that is
            // still waiting on SQLite cannot make this draft land on the lesson it finally opens.
            val saved = if (key.review) controller.saveReviewDraft(key.book, key.lesson, key.mode, answer)
                else controller.saveDraft(key.book, key.lesson, key.mode, answer)
            if (!saved) return
            if (pending[key] == answer) pending.remove(key)
        }
    }
}

/** A workspace-local queue, for callers that own the whole screen and never unmount it mid-answer. */
@Composable
internal fun rememberAnswerDrafts(controller: TextbookController): AnswerDrafts {
    val scope = rememberCoroutineScope()
    return remember(controller) { AnswerDrafts(controller, scope) }
}

@Composable
internal fun TextbookWorkspace(controller: TextbookController, drafts: AnswerDrafts = rememberAnswerDrafts(controller), loadOnMount: Boolean = true, chooseBook: suspend () -> Path?) {
    val ui by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var practiceSource by remember { mutableStateOf<PracticeSource?>(null) }
    var returnFocus by remember { mutableStateOf<SourceFocus?>(null) }
    var readerHasFocus by remember { mutableStateOf(false) }
    val readerFocus = remember { FocusRequester() }
    val lessonSourceFocus = remember { FocusRequester() }
    val practiceSourceFocus = remember { FocusRequester() }
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
        if (ui.source != null) returnFocus = if (practiceSource == null) SourceFocus.LESSON else SourceFocus.PRACTICE
        run { controller.back() }
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
        run { controller.source(); anchor.page?.let { controller.page(it) } }
    }
    CoachTheme {
        Surface(Modifier.fillMaxSize().testTag("windows-textbooks").onPreviewKeyEvent { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && ui.screen == BookScreen.READER &&
                ui.source != null && ui.prompt == null && !ui.busy) {
                returnFromReader(); true
            } else false
        }.onFocusChanged { readerHasFocus = it.hasFocus }
            .focusRequester(readerFocus).focusable(enabled = ui.screen == BookScreen.READER && ui.source != null),
            color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
            Column {
                if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ui.error?.let { error -> Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("book-error")) }
                val book = ui.book
                when {
                    ui.screen == BookScreen.HOME || book == null -> LibraryHome(ui,
                        onImport = { run { chooseBook()?.let { controller.importBook(it) } } },
                        onSelect = { run { controller.select(it) } }, onRead = { run { controller.read() } },
                        onLearn = { run { drafts.flush(); controller.learn() } })
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
                    ui.screen == BookScreen.READER -> TextbookReader(ui, controller.library,
                        practiceSource = practiceSource,
                        onPage = { page ->
                            val restoreFocus = ui.source != null && readerHasFocus
                            run {
                                controller.page(page)
                                if (restoreFocus) {
                                    val lostFocus = withTimeoutOrNull(1_000) {
                                        snapshotFlow { !readerHasFocus }.first { it }
                                    } == true
                                    val current = controller.state.value
                                    if (lostFocus && !readerHasFocus && current.screen == BookScreen.READER &&
                                        current.source != null && current.prompt == null) readerFocus.requestFocus()
                                }
                            }
                        }, onBack = { returnFromReader() },
                        onLearn = { run { drafts.flush(); controller.learn() } })
                    else -> {
                        val lesson = book.lessons.getOrNull(book.lessonIndex)
                        val displayed = if (lesson == null) ui else ui.copy(
                            answer = drafts.current(book.id, lesson.id, book.questionMode, ui.answer))
                        TextbookLearning(displayed, learningScroll, lessonSourceFocus, practiceSourceFocus, returnFocus,
                        onFocusRestored = { returnFocus = null }, onBack = { run { controller.back() } },
                        onSource = { practiceSource = null; run { controller.source() } },
                        onPracticeSource = { openPracticeSource(it) }, onLesson = { run { drafts.flush(); controller.lesson(it) } },
                        onSettings = { run { controller.questionSettings() } }, onAnswer = { answer ->
                            ui.book?.let { current ->
                                current.lessons.getOrNull(current.lessonIndex)?.let { lesson ->
                                    drafts.save(current.id, lesson.id, current.questionMode, answer)
                                }
                            }
                        }, onReviews = { run { if (drafts.flush()) controller.showReviews() } })
                    }
                }
            }
            if (ui.prompt != null && ui.book != null) BookEntryPrompt(ui,
                onDismiss = { run { controller.dismissPrompt() } }, onRead = { run { controller.read() } },
                onEnable = { run { controller.enableLearning() } }, onMode = { run { controller.mode(it) } })
        }
    }
}

private data class PracticeSource(val page: Int?, val quote: String?, val warning: String?)
private enum class SourceFocus { LESSON, PRACTICE }

@Composable
private fun LibraryHome(ui: LibraryUi, onImport: () -> Unit, onSelect: (String) -> Unit, onRead: () -> Unit, onLearn: () -> Unit) {
    val book = ui.book
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Text("书架", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("library-title"))
            Text("带来一本教材，从想读的地方开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton(onImport, enabled = !ui.busy, modifier = Modifier.testTag("upload-textbook")) { Text("上传教材") }
            Text("PDF、EPUB、Markdown 或文本 · 教材和进度保存在这台电脑", style = MaterialTheme.typography.bodySmall)
            if (book == null) {
                Text("书架还是空的", style = MaterialTheme.typography.titleLarge)
                Text("上传后，可以直接开始学，也可以先当一本书读。")
            } else {
                Text(book.title, style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    EntryCard("直接开始读", "打开阅读器，接着第 ${book.readingPage} 页读。", "home-read", !ui.busy, Modifier.weight(1f), onRead)
                    EntryCard("按进度继续学", when {
                        book.finished -> "已读完这门课，可以回看与练习。"
                        book.learning -> "继续第 ${book.lessonIndex + 1} / ${book.lessons.size} 节。"
                        else -> "按教材章节开启 learning，出题由你选。"
                    }, "home-learn", !ui.busy, Modifier.weight(1f), onLearn)
                }
                HorizontalDivider()
                Text("全部教材", style = MaterialTheme.typography.titleMedium)
                for (item in ui.catalog) {
                    OutlinedButton(onClick = { onSelect(item.id) }, enabled = !ui.busy,
                        modifier = Modifier.fillMaxWidth().testTag("book-${item.id}")) {
                        Column(Modifier.fillMaxWidth().padding(6.dp)) {
                            Text(item.title)
                            Text("${item.pageCount} 页 · 读到第 ${item.readingPage} 页${if (item.learning) " · 已开启 learning" else ""}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(title: String, description: String, tag: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 148.dp).testTag(tag)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun QuestionModePicker(selected: QuestionMode, enabled: Boolean, onMode: (QuestionMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("出题", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in QuestionMode.entries) FilterChip(selected = selected == mode, onClick = { onMode(mode) },
                enabled = enabled, label = { Text(mode.label) }, modifier = Modifier.testTag("question-mode-${mode.name}"))
        }
        Text(when (selected) {
            QuestionMode.NONE -> "只学教材内容，随时可以再开启练习。"
            QuestionMode.CHOICE -> "用原文填空选择题回忆教材内容。"
            QuestionMode.SHORT_ANSWER -> "用自己的话作答，再对照原文自检。"
        }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BookEntryPrompt(ui: LibraryUi, onDismiss: () -> Unit, onRead: () -> Unit, onEnable: () -> Unit, onMode: (QuestionMode) -> Unit) {
    val book = checkNotNull(ui.book)
    val settings = ui.prompt == BookPrompt.QUESTIONS
    AlertDialog(onDismissRequest = { if (!ui.busy) onDismiss() },
        title = { Text(when (ui.prompt) {
            BookPrompt.IMPORTED -> "教材已加入书架"
            BookPrompt.AFTER_READING -> "要开启 learning 吗？"
            BookPrompt.QUESTIONS -> "出题方式"
            else -> "开始学这本教材"
        }) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(book.title)
                Text(when (ui.prompt) {
                    BookPrompt.IMPORTED -> "可以先在阅读器里读，也可以直接按章节开始学。"
                    BookPrompt.AFTER_READING -> "阅读器已经打开。开启后会按教材章节继续学；暂不开启，就只读这本书。"
                    BookPrompt.QUESTIONS -> "三种方式共用这一个设置，切换后保留学习位置。"
                    else -> "从教材原文生成学习路线，按顺序理解、回忆和继续。"
                })
                QuestionModePicker(book.questionMode, !ui.busy, onMode)
                if (book.pages.any { it.text.isBlank() }) Text("部分页面没有可提取文字，仍可在阅读器查看原页。learning 只使用可提取的内容。", style = MaterialTheme.typography.bodySmall)
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(onClick = if (settings) onDismiss else onEnable, enabled = !ui.busy,
                modifier = Modifier.testTag(if (settings) "close-question-settings" else "enable-learning")) {
                Text(if (settings) "确定" else if (ui.prompt == BookPrompt.IMPORTED) "直接开始学" else "开启 learning")
            }
        }, dismissButton = {
            if (!settings) TextButton(onClick = if (ui.prompt == BookPrompt.IMPORTED) onRead else onDismiss, enabled = !ui.busy,
                modifier = Modifier.testTag("read-only")) { Text(if (ui.prompt == BookPrompt.IMPORTED) "先读" else "暂不开启") }
        })
}

private sealed interface PageImage {
    data object Loading : PageImage
    data object Failed : PageImage
    data class Ready(val image: ImageBitmap) : PageImage
}

@Composable
private fun TextbookReader(ui: LibraryUi, library: TextbookLibrary, practiceSource: PracticeSource?, onPage: (Int) -> Unit, onBack: () -> Unit, onLearn: () -> Unit) {
    val book = checkNotNull(ui.book)
    val page = book.pages[ui.page - 1]
    var textView by remember(book.id) { mutableStateOf(book.format != "pdf") }
    var jump by remember(book.id, ui.page) { mutableStateOf(ui.page.toString()) }
    var retry by remember { mutableIntStateOf(0) }
    val source = ui.source

    val readerSource = remember(library) { com.coach.desktop.reader.LocalBooksReaderSource(library) }
    val remote = remember(readerSource) { com.coach.desktop.reader.ReaderRemote(readerSource) }
    val session = remember(remote) {
        com.coach.desktop.reader.ReaderSession(
            com.coach.desktop.reader.ReaderFixture(book.title, emptyList(), emptyList()),
            1200,
            remote,
        )
    }

    val quoteAnchor = remember(ui.page, source, practiceSource) {
        if (source == null) null
        else if (practiceSource != null) practiceSource.quote?.takeIf { practiceSource.page == ui.page }
        else StudyCoachCourse.sourceQuote(source, ui.page)
    }
    val image by produceState<PageImage>(PageImage.Loading, book.id, ui.page, retry) {
        value = PageImage.Loading
        if (book.format == "pdf") {
            value = try { withContext(Dispatchers.IO) { PageImage.Ready(library.renderPdf(book, page.number).toComposeImageBitmap()) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { PageImage.Failed }
        }
    }
    val view = (remote.page as? com.coach.desktop.reader.PageState.Ready)?.view
    val samePage = remote.current?.id == book.id && remote.current?.version == "1" &&
        view?.document == book.id && view.pdfPage == ui.page
    val boxes = if (samePage) (remote.boxes as? com.coach.desktop.reader.BoxesState.Ready)?.boxes else null
    val located = if (boxes != null && quoteAnchor != null) com.coach.desktop.reader.QuoteLocator.locate(boxes, quoteAnchor) else null
    val loadedImage = (image as? PageImage.Ready)?.image
    val aspectOk = boxes != null && loadedImage != null &&
        kotlin.math.abs(loadedImage.width.toDouble() / loadedImage.height - boxes.width / boxes.height) < 0.05
    val sourceWarning = if (source == null) null else when {
        practiceSource?.warning != null -> practiceSource.warning
        practiceSource?.page != null && practiceSource.page != ui.page -> "无法确认原句：本题依据在第 ${practiceSource.page} 页。"
        book.format != "pdf" -> null
        image is PageImage.Failed -> "无法确认原句：这一页的图像打不开。"
        remote.failure != null || remote.page is com.coach.desktop.reader.PageState.Failed -> "无法确认原句：无法读取这一页的定位信息。"
        remote.current != null && (remote.current?.id != book.id || remote.current?.version != "1") -> "无法确认原句：教材版本不一致。"
        samePage && remote.boxes is com.coach.desktop.reader.BoxesState.Failed -> "无法确认原句：无法读取这一页的文字位置。"
        samePage && quoteAnchor.isNullOrBlank() -> "无法确认原句：没有可定位的原文。"
        located == com.coach.desktop.reader.Located.NoTextLayer -> "无法确认原句：这一页没有可定位的文字。"
        located == com.coach.desktop.reader.Located.NotFound -> "无法确认原句：这一页没有找到对应内容。"
        located == com.coach.desktop.reader.Located.Ambiguous -> "无法确认原句：这一页有多处相同内容。"
        located is com.coach.desktop.reader.Located.Found && loadedImage != null && !aspectOk -> "无法确认原句：页面图像与文字位置不一致。"
        else -> null
    }

    LaunchedEffect(book.id, ui.page, quoteAnchor) {
        remote.loadCatalog()
        if (quoteAnchor != null) {
            session.openRemoteAt(book.id, ui.page, quoteAnchor)
        } else {
            session.openRemote(book.id, ui.page)
        }
        // 这里只拥有原文视图；作答仍归原 controller，出处浏览不保存独立读书书签。
        if (remote.current?.id == book.id && remote.current?.version == "1" &&
            com.coach.desktop.reader.PdfPages.toPdf(session.state.position.page) == ui.page) {
            remote.show(ui.page)
        }
    }

    Desk(crumb = book.title, onBack = onBack, backLabel = if (source != null) { if (ui.review != null) "返回复习" else "返回学习" } else "返回书架", showNavigation = false,
        status = "第 ${ui.page} / ${book.pages.size} 页", directory = {
            Text("教材目录", style = MaterialTheme.typography.titleMedium)
            for (lesson in book.lessons) TextButton(onClick = { onPage(lesson.firstPage); session.goToPage(lesson.firstPage) }, enabled = !ui.busy) { Text(lesson.title) }
        }) {
        Column(Modifier.fillMaxSize().padding(20.dp).testTag("textbook-reader"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (source != null) "学习原文 · ${source.title}" else "教材阅读", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (book.format == "pdf") TextButton(onClick = { textView = !textView }) { Text(if (textView) "看原页" else "看文字") }
                if (source == null) PaperButton(onLearn, enabled = !ui.busy) { Text(if (book.learning) "继续学" else "开启 learning") }
            }
            if (source != null) Text("对应教材第 ${source.firstPage}–${source.lastPage} 页 · 返回后保留学习进度", style = MaterialTheme.typography.bodySmall)
            sourceWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("source-anchor-status")) }
            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface).testTag("reader-page")) {
                if (textView) {
                    val highlight = MaterialTheme.colorScheme.secondaryContainer
                    val marked = buildAnnotatedString {
                        append(page.text.ifBlank { "这一页没有可提取文字，请切换到原页阅读。" })
                        val blocks = source?.blocks.orEmpty().filter { it.page == ui.page }
                        val confirmedBlocks = if (practiceSource != null) emptyList() else if (book.format != "pdf") blocks else if (located is com.coach.desktop.reader.Located.Found && aspectOk) {
                            blocks.filter { !it.heading && it.text == quoteAnchor }.take(1)
                        } else emptyList()
                        for (block in confirmedBlocks) {
                            if (block.start >= 0 && block.end <= length) addStyle(SpanStyle(background = highlight), block.start, block.end)
                        }
                        if (practiceSource != null && quoteAnchor != null &&
                            (book.format != "pdf" || located is com.coach.desktop.reader.Located.Found && aspectOk)) {
                            val start = page.text.indexOf(quoteAnchor)
                            if (start >= 0 && page.text.indexOf(quoteAnchor, start + 1) < 0)
                                addStyle(SpanStyle(background = highlight), start, start + quoteAnchor.length)
                        }
                    }
                    SelectionContainer { Text(marked, fontSize = 19.sp, lineHeight = 31.sp,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp).testTag("reader-text")) }
                } else {
                    when (val loaded = image) {
                        PageImage.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        PageImage.Failed -> Column(Modifier.align(Alignment.Center)) {
                            Text("这一页暂时打不开。")
                            TextButton(onClick = { retry++ }) { Text("重试") }
                        }
                        is PageImage.Ready -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Box(Modifier.fillMaxWidth()) {
                                Image(loaded.image, "${book.title} 第 ${ui.page} 页", contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(loaded.image.width.toFloat() / loaded.image.height).testTag("remote-image"))
                                if (located is com.coach.desktop.reader.Located.Found && aspectOk && boxes != null) {
                                    androidx.compose.foundation.Canvas(Modifier.testTag("remote-highlight").semantics { contentDescription = "原句：$quoteAnchor" }.matchParentSize()) {
                                        val k = size.width / boxes.width.toFloat()
                                        for (r in located.rects) drawRect(
                                            androidx.compose.ui.graphics.Color(0xFFFFE9A8).copy(alpha = 0.55f),
                                            topLeft = androidx.compose.ui.geometry.Offset(r.x0.toFloat() * k, r.y0.toFloat() * k),
                                            size = androidx.compose.ui.geometry.Size((r.x1 - r.x0).toFloat() * k, (r.y1 - r.y0).toFloat() * k),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ onPage(ui.page - 1); session.prevPage() }, enabled = !ui.busy && ui.page > 1, modifier = Modifier.testTag("previous-page").testTag("bar-prev-page")) { Text("上一页") }
                Text("${ui.page} / ${book.pages.size}", modifier = Modifier.testTag("reader-page-number"))
                OutlinedButton({ onPage(ui.page + 1); session.nextPage() }, enabled = !ui.busy && ui.page < book.pages.size, modifier = Modifier.testTag("next-page").testTag("bar-next-page")) { Text("下一页") }
                Spacer(Modifier.weight(1f))
                OutlinedTextField(jump, { jump = it.filter(Char::isDigit).take(5) }, label = { Text("页码") }, singleLine = true,
                    modifier = Modifier.width(80.dp).testTag("page-jump"))
                TextButton({ jump.toIntOrNull()?.let { onPage(it); session.goToPage(it) } }, enabled = !ui.busy && jump.toIntOrNull()?.let { it in 1..book.pages.size } == true) { Text("前往") }
            }
            Text(if (book.format == "pdf") "页码按 PDF 原文件计算。" else "页码按阅读器中的正文分页计算。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TextbookLearning(ui: LibraryUi, scroll: ScrollState, lessonSourceFocus: FocusRequester, practiceSourceFocus: FocusRequester,
    returnFocus: SourceFocus?, onFocusRestored: () -> Unit, onBack: () -> Unit, onSource: () -> Unit, onPracticeSource: (Practice) -> Unit, onLesson: (Int) -> Unit,
    onSettings: () -> Unit, onAnswer: (PracticeAnswer) -> Unit, onReviews: () -> Unit) {
    val book = checkNotNull(ui.book)
    val lesson = book.lessons.getOrNull(book.lessonIndex)
    LaunchedEffect(returnFocus, ui.busy) {
        if (ui.busy) return@LaunchedEffect
        // The restored button needs a layout frame before it can accept focus.
        if (returnFocus != null) withFrameNanos { }
        when (returnFocus) {
            SourceFocus.LESSON -> lessonSourceFocus.requestFocus()
            SourceFocus.PRACTICE -> practiceSourceFocus.requestFocus()
            null -> return@LaunchedEffect
        }
        onFocusRestored()
    }
    Desk(crumb = book.title, onBack = onBack, backLabel = "返回书架", showNavigation = false,
        status = if (lesson == null) "本书学习结束" else "第 ${book.lessonIndex + 1} / ${book.lessons.size} 节", directory = {
            Text("学习路线", style = MaterialTheme.typography.titleMedium)
            book.lessons.forEachIndexed { index, item -> TextButton({ onLesson(index) }, enabled = !ui.busy) { Text("${index + 1}. ${item.title}") } }
        }) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp).testTag("textbook-learning"),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (lesson == null) "这一遍学完了" else "理解 → 回忆 → 继续", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onSettings, enabled = !ui.busy, modifier = Modifier.testTag("question-settings")) { Text("出题：${book.questionMode.label}") }
            }
            if (lesson == null) {
                Text("已走完「${book.title}」的 ${book.lessons.size} 节内容。", style = MaterialTheme.typography.titleLarge)
                Text("可以从目录回看。读完和掌握是两回事，复习时再试着用自己的话说明。")
                PaperButton({ onLesson(0) }, enabled = !ui.busy) { Text("从头回看") }
            } else {
                Text(lesson.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("lesson-title"))
                Text(if (book.questionMode == QuestionMode.NONE) "先理解这一节在说什么，再尝试用自己的话说明。" else "先试着回答，再对照原文理解这一节。")
                PaperButton(onSource, enabled = !ui.busy, modifier = Modifier.focusRequester(lessonSourceFocus).testTag("open-lesson-source")) { Text("打开对应原文 · 第 ${lesson.firstPage} 页") }
                HorizontalDivider()
                val practice = remember(book.id, lesson.id, book.questionMode) { StudyCoachCourse.practice(book, lesson, book.questionMode) }
                if (book.questionMode != QuestionMode.NONE) {
                    if (practice == null) Text("这一节的原文太少，暂时无法生成选择题。可以继续学，或改为简答。")
                    else {
                        SourcePractice(lesson.id, book.questionMode, practice, ui.answer, onAnswer, { onPracticeSource(practice) }, !ui.busy, practiceSourceFocus)
                        if (ui.reviews.any { it.book == book.id && it.lesson == lesson.id && it.mode == book.questionMode }) {
                            Text("已加入复习，可在今天查看原题。", modifier = Modifier.testTag("local-review-saved"))
                            TextButton(onReviews, enabled = !ui.busy, modifier = Modifier.testTag("open-local-reviews")) { Text("查看复习") }
                        }
                    }
                }
                if (practice == null || (book.questionMode == QuestionMode.CHOICE && ui.answer.revealed)) {
                    SelectionContainer { Text(lesson.text, fontSize = 19.sp, lineHeight = 31.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ onLesson(book.lessonIndex - 1) }, enabled = !ui.busy && book.lessonIndex > 0,
                        modifier = Modifier.testTag("previous-lesson")) { Text("上一节") }
                    PrimaryButton({ onLesson(book.lessonIndex + 1) }, enabled = !ui.busy, modifier = Modifier.testTag("next-lesson")) {
                        Text(if (book.lessonIndex + 1 == book.lessons.size) "读完这一节" else "继续下一节")
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePractice(lesson: String, mode: QuestionMode, practice: Practice, answer: PracticeAnswer, onAnswer: (PracticeAnswer) -> Unit, onSource: () -> Unit, sourceEnabled: Boolean, sourceFocus: FocusRequester) {
    var draft by remember(lesson, mode) { mutableStateOf(answer.text) }
    var typing by remember(lesson, mode) { mutableStateOf(false) }
    // Coming back from 今天/课程 rebuilds this screen with the answer the queue has already saved.
    // While the learner is typing the field owns the text, so a slower echo cannot overwrite keystrokes.
    LaunchedEffect(answer.text, typing) { if (!typing) draft = answer.text }
    Text(if (mode == QuestionMode.CHOICE) "原文填空" else "简答自检", style = MaterialTheme.typography.titleMedium)
    Text(practice.prompt)
    TextButton(onSource, enabled = sourceEnabled, modifier = Modifier.focusRequester(sourceFocus).testTag("open-practice-source")) { Text("查看对应原文") }
    if (mode == QuestionMode.CHOICE) {
        GuessMark(guessed = answer.guessed, enabled = true, onGuessed = { onAnswer(answer.copy(guessed = it)) })
        for ((index, option) in practice.options.withIndex()) OutlinedButton(onClick = {
            draft = option; onAnswer(answer.copy(text = option, revealed = true))
        }, enabled = !answer.revealed, modifier = Modifier.fillMaxWidth().testTag("choice-$index")) { Text(option) }
        if (answer.revealed) {
            Text("你选择：${answer.text}")
            Text(if (answer.text == practice.answer) "与原文一致。" else "再对照一下原文：${practice.answer}")
            Text("原文：${practice.reference}")
            TextButton({ draft = ""; onAnswer(PracticeAnswer()) }) { Text("重新作答") }
        }
    } else {
        OutlinedTextField(draft, { draft = it; onAnswer(PracticeAnswer(it)) }, label = { Text("写下你的理解") },
            minLines = 3, modifier = Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused }.testTag("short-answer"))
        TextButton({ onAnswer(PracticeAnswer(draft, revealed = true)) }, enabled = draft.isNotBlank(),
            modifier = Modifier.testTag("reveal-reference")) { Text("对照原文自检") }
        if (answer.revealed) {
            Text(practice.reference)
            Text("这是对照原文的自检，不会自动判定掌握。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(answer.understood == false, { onAnswer(PracticeAnswer(draft, true, false)) }, label = { Text("还需复习") })
                FilterChip(answer.understood == true, { onAnswer(PracticeAnswer(draft, true, true)) }, label = { Text("我已理解") })
            }
        }
    }
}


@Composable
private fun LocalBookReviews(ui: LibraryUi, onBack: () -> Unit, onOpen: (LocalBookReview) -> Unit) {
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
private fun LocalBookReviewQuestion(review: LocalBookReview, enabled: Boolean, sourceFocus: FocusRequester,
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
