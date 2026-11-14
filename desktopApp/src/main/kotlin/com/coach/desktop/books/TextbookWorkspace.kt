package com.coach.desktop.books

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

@Composable
internal fun TextbookWorkspace(controller: TextbookController, chooseBook: suspend () -> Path?) {
    val ui by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.load() }
    fun run(action: suspend () -> Unit) { scope.launch { action() } }
    CoachTheme {
        Surface(Modifier.fillMaxSize().testTag("windows-textbooks"), color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
            Column {
                if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ui.error?.let { error -> Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("book-error")) }
                val book = ui.book
                when {
                    ui.screen == BookScreen.HOME || book == null -> LibraryHome(ui,
                        onImport = { run { chooseBook()?.let { controller.importBook(it) } } },
                        onSelect = { run { controller.select(it) } }, onRead = { run { controller.read() } },
                        onLearn = { run { controller.learn() } })
                    ui.screen == BookScreen.READER -> TextbookReader(ui, controller.library,
                        onPage = { run { controller.page(it) } }, onBack = { run { controller.back() } },
                        onLearn = { run { controller.learn() } })
                    else -> TextbookLearning(ui, onBack = { run { controller.back() } },
                        onSource = { run { controller.source() } }, onLesson = { run { controller.lesson(it) } },
                        onSettings = { run { controller.questionSettings() } }, onAnswer = { run { controller.answer(it) } })
                }
            }
            if (ui.prompt != null && ui.book != null) BookEntryPrompt(ui,
                onDismiss = { run { controller.dismissPrompt() } }, onRead = { run { controller.read() } },
                onEnable = { run { controller.enableLearning() } }, onMode = { run { controller.mode(it) } })
        }
    }
}

@Composable
private fun LibraryHome(ui: LibraryUi, onImport: () -> Unit, onSelect: (String) -> Unit, onRead: () -> Unit, onLearn: () -> Unit) {
    val book = ui.book
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Text("我的书桌", style = MaterialTheme.typography.headlineLarge)
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
                Text("书架", style = MaterialTheme.typography.titleMedium)
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
private fun TextbookReader(ui: LibraryUi, library: TextbookLibrary, onPage: (Int) -> Unit, onBack: () -> Unit, onLearn: () -> Unit) {
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

    val quoteAnchor = remember(ui.page, source) {
        source?.let { StudyCoachCourse.sourceQuote(it, ui.page) }
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

    Desk(crumb = book.title, onBack = onBack, backLabel = if (source != null) "返回学习" else "返回书桌", showNavigation = false,
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
            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface).testTag("reader-page")) {
                if (textView) {
                    val highlight = MaterialTheme.colorScheme.secondaryContainer
                    val marked = buildAnnotatedString {
                        append(page.text.ifBlank { "这一页没有可提取文字，请切换到原页阅读。" })
                        for (block in source?.blocks.orEmpty().filter { it.page == ui.page }) {
                            if (block.start >= 0 && block.end <= length) addStyle(SpanStyle(background = highlight), block.start, block.end)
                        }
                    }
                    SelectionContainer { Text(marked, fontSize = 19.sp, lineHeight = 31.sp,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp).testTag("reader-text")) }
                } else {
                    val image by produceState<PageImage>(PageImage.Loading, book.id, ui.page, retry) {
                        value = PageImage.Loading
                        value = try { withContext(Dispatchers.IO) { PageImage.Ready(library.renderPdf(book, page.number).toComposeImageBitmap()) } }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { PageImage.Failed }
                    }
                    when (val loaded = image) {
                        PageImage.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        PageImage.Failed -> Column(Modifier.align(Alignment.Center)) {
                            Text("这一页暂时打不开。")
                            TextButton(onClick = { retry++ }) { Text("重试") }
                        }
                        is PageImage.Ready -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            val view = (remote.page as? com.coach.desktop.reader.PageState.Ready)?.view
                            val samePage = remote.current?.id == book.id && remote.current?.version == "1" &&
                                view?.document == book.id && view.pdfPage == ui.page
                            val boxes = if (samePage) (remote.boxes as? com.coach.desktop.reader.BoxesState.Ready)?.boxes else null
                            val located = if (boxes != null && quoteAnchor != null) {
                                com.coach.desktop.reader.QuoteLocator.locate(boxes, quoteAnchor)
                            } else null
                            val aspectOk = boxes != null && kotlin.math.abs(loaded.image.width.toDouble() / loaded.image.height - boxes.width / boxes.height) < 0.05
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
private fun TextbookLearning(ui: LibraryUi, onBack: () -> Unit, onSource: () -> Unit, onLesson: (Int) -> Unit,
    onSettings: () -> Unit, onAnswer: (PracticeAnswer) -> Unit) {
    val book = checkNotNull(ui.book)
    val lesson = book.lessons.getOrNull(book.lessonIndex)
    Desk(crumb = book.title, onBack = onBack, backLabel = "返回书桌", showNavigation = false,
        status = if (lesson == null) "本书学习结束" else "第 ${book.lessonIndex + 1} / ${book.lessons.size} 节", directory = {
            Text("学习路线", style = MaterialTheme.typography.titleMedium)
            book.lessons.forEachIndexed { index, item -> TextButton({ onLesson(index) }, enabled = !ui.busy) { Text("${index + 1}. ${item.title}") } }
        }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("textbook-learning"),
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
                Text("先理解这一节在说什么，再尝试用自己的话说明。")
                PaperButton(onSource, enabled = !ui.busy, modifier = Modifier.testTag("open-lesson-source")) { Text("打开对应原文 · 第 ${lesson.firstPage} 页") }
                SelectionContainer { Text(lesson.text, fontSize = 19.sp, lineHeight = 31.sp) }
                HorizontalDivider()
                if (book.questionMode != QuestionMode.NONE) {
                    val practice = remember(book.id, lesson.id, book.questionMode) { StudyCoachCourse.practice(book, lesson, book.questionMode) }
                    if (practice == null) Text("这一节的原文太少，暂时无法生成选择题。可以继续学，或改为简答。")
                    else SourcePractice(lesson.id, book.questionMode, practice, ui.answer, onAnswer)
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
private fun SourcePractice(lesson: String, mode: QuestionMode, practice: Practice, answer: PracticeAnswer, onAnswer: (PracticeAnswer) -> Unit) {
    var draft by remember(lesson, mode) { mutableStateOf(answer.text) }
    Text(if (mode == QuestionMode.CHOICE) "原文填空" else "简答自检", style = MaterialTheme.typography.titleMedium)
    Text(practice.prompt)
    if (mode == QuestionMode.CHOICE) {
        for ((index, option) in practice.options.withIndex()) OutlinedButton(onClick = {
            draft = option; onAnswer(PracticeAnswer(option, revealed = true))
        }, enabled = !answer.revealed, modifier = Modifier.fillMaxWidth().testTag("choice-$index")) { Text(option) }
        if (answer.revealed) {
            Text(if (answer.text == practice.answer) "与原文一致。" else "再对照一下原文：${practice.answer}")
            Text("原文：${practice.reference}")
            TextButton({ draft = ""; onAnswer(PracticeAnswer()) }) { Text("重新作答") }
        }
    } else {
        OutlinedTextField(draft, { draft = it; onAnswer(PracticeAnswer(it)) }, label = { Text("写下你的理解") },
            minLines = 3, modifier = Modifier.fillMaxWidth().testTag("short-answer"))
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
