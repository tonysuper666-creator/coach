package com.coach.desktop.books

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coach.design.tokens.CoachV7
import com.coach.ui.LocalV7TextScale
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.Desk
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.V7Scrollbar
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.V7Tooltip
import com.coach.ui.v7.v7FocusRing
import com.coach.desktop.reader.ReaderSession
import com.coach.desktop.reader.ReaderDraftScope
import com.coach.desktop.reader.NotesSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Cursor

/**
 * 出处打开后这一页要标什么。练习依据只有页 + 原句；讲解出处还带着解析器核对过的块范围，
 * 所以同一页出现两句相同原文时，标的是被点的那一块，而不是第一次字符串命中。
 */
internal data class PracticeSource(val page: Int?, val quote: String?, val warning: String?, val target: BookSourceTarget? = null)

private sealed interface PageImage {
    data object Loading : PageImage
    data object Failed : PageImage
    data class Ready(val image: ImageBitmap) : PageImage
}

@Composable
internal fun TextbookReader(ui: LibraryUi, library: TextbookLibrary, support: BookLearningSupport, practiceSource: PracticeSource?, onNoteSession: (ReaderSession?) -> Unit, onPage: (Int) -> Unit, onBack: () -> Unit, onLearn: () -> Unit) {
    val book = checkNotNull(ui.book)
    val page = book.page(ui.page)
    if (page == null) {
        LaunchedEffect(book.id, ui.page) { onPage(book.pages.first().number) }
        return
    }
    val hasOriginal = book.format == "pdf" || book.format in BookFormats.images
    val textOnlySource = !hasOriginal || page.ocr
    val lastPage = book.pages.maxOf { it.number }
    val previousPage = book.pages.filter { it.number < ui.page }.maxByOrNull { it.number }?.number
    val nextPage = book.pages.filter { it.number > ui.page }.minByOrNull { it.number }?.number
    var textView by remember(book.id) { mutableStateOf(!hasOriginal || page.ocr) }
    var jump by remember(book.id, ui.page) { mutableStateOf(ui.page.toString()) }
    var retry by remember { mutableIntStateOf(0) }
    var notesOpen by remember(book.id) { mutableStateOf(false) }
    var explainOpen by remember(book.id) { mutableStateOf(false) }
    val explainer = remember(library) { BookExplainer(library = library) }
    val explainConsent = remember(library) { ExplainConsent(library.directory) }
    val source = ui.source
    LaunchedEffect(book.id, ui.page, source) {
        if (page.ocr && source != null) textView = true
    }
    var asking by remember(book.id, ui.page) { mutableStateOf(false) }

    val readerSource = remember(library) { com.coach.desktop.reader.LocalBooksReaderSource(library) }
    val remote = remember(readerSource, book.id) { com.coach.desktop.reader.ReaderRemote(readerSource) }
    val session = remember(remote) {
        com.coach.desktop.reader.ReaderSession(
            com.coach.desktop.reader.ReaderFixture(book.title, emptyList(), emptyList()),
            1200,
            remote,
            ReaderDraftScope.forSource(readerSource, library.directory),
        )
    }
    DisposableEffect(session) {
        onNoteSession(session)
        onDispose { session.cancelNote(); onNoteSession(null) }
    }
    // Only explicit saves trigger this effect. Panel hide/show and draft restore never send a write.
    LaunchedEffect(session, session.noteSaveTick) {
        if (session.noteSaveTick > 0) session.commitNote()
    }

    // 翻页键只到得了拿着键盘焦点的那棵子树，外壳的 Surface 只处理 Esc，所以阅读区自己是一个焦点目标：
    // 换页、合上批注编辑区、离开页码框之后把焦点收回来，方向键马上又能用。
    // 正在输入文字时不接管：提问弹窗、页码框、批注编辑区自己要用方向键。
    val pageFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var jumpFocused by remember { mutableStateOf(false) }
    var pageFocused by remember { mutableStateOf(false) }
    val typing = readerTyping(asking, jumpFocused, notesOpen, session.noting)
    LaunchedEffect(book.id, ui.page, typing) {
        if (typing) return@LaunchedEffect
        // 进阅读屏那一帧焦点节点可能才刚挂上，第一次请求会被丢掉；等几帧再确认一次，最多试几回。
        repeat(5) {
            if (pageFocused) return@LaunchedEffect
            withFrameNanos { }
            runCatching { pageFocus.requestFocus() }
        }
    }
    fun turnTo(pageNumber: Int?) {
        pageNumber ?: return
        onPage(pageNumber)
        session.goToPage(pageNumber)
    }

    val quoteAnchor = remember(ui.page, source, practiceSource) {
        if (source == null) null
        else if (practiceSource != null) practiceSource.quote?.takeIf { practiceSource.page == ui.page }
        else StudyCoachCourse.sourceQuote(source, ui.page)
    }
    // 讲解出处带着解析器核对过的块范围进来。只有这一页的同一偏移仍是同一句原文时才用它；
    // 对不上就什么都不标，绝不回落到「第一次出现」或别处的句子。
    val citedRange = remember(practiceSource?.target, ui.page, page.text) {
        val target = practiceSource?.target?.takeIf { it.pdfPage == ui.page } ?: return@remember null
        if (target.start >= 0 && target.end > target.start && target.end <= page.text.length &&
            page.text.substring(target.start, target.end) == target.quote) target.start until target.end else null
    }
    val image by produceState<PageImage>(PageImage.Loading, book.id, ui.page, retry) {
        value = PageImage.Loading
        if (hasOriginal) {
            value = try { withContext(Dispatchers.IO) { PageImage.Ready(library.renderOriginal(book, page.number).toComposeImageBitmap()) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { PageImage.Failed }
        }
    }
    // 页图还没回来时先铺纸：这一页自己有尺寸就按它铺，量不到才借上一张成功页图的比例。
    // 图到了原地换掉，后面的按钮不往下跳；横竖混排的书也不会先铺上一页那张的纸再塌下来。
    var paperAspect by remember(book.id) { mutableStateOf<Float?>(null) }
    LaunchedEffect(image) {
        (image as? PageImage.Ready)?.let { paperAspect = it.image.width.toFloat() / it.image.height }
    }
    val imageScroll = remember(book.id, ui.page) { ScrollState(0) }
    val view = (remote.page as? com.coach.desktop.reader.PageState.Ready)?.view
    val samePage = remote.current?.id == book.id && remote.current?.version == "1" &&
        view?.document == book.id && view.pdfPage == ui.page
    val boxes = if (samePage) (remote.boxes as? com.coach.desktop.reader.BoxesState.Ready)?.boxes else null
    // 同一页有多处相同原文时，用块范围在这一页里的序号挑出被点的那一处；序号算不出来就不猜。
    val citedOccurrence = remember(citedRange, quoteAnchor, page.text) {
        val range = citedRange ?: return@remember null
        val quote = quoteAnchor ?: return@remember null
        var ordinal = 0
        var at = page.text.indexOf(quote)
        while (at in 0 until range.first) { ordinal++; at = page.text.indexOf(quote, at + 1) }
        if (at == range.first) ordinal else null
    }
    val located = if (boxes != null && quoteAnchor != null) com.coach.desktop.reader.QuoteLocator.locate(boxes, quoteAnchor, citedOccurrence) else null
    val loadedImage = (image as? PageImage.Ready)?.image
    val aspectOk = boxes != null && loadedImage != null &&
        kotlin.math.abs(loadedImage.width.toDouble() / loadedImage.height - boxes.width / boxes.height) < 0.05
    val sourceWarning = if (source == null) null else when {
        practiceSource?.warning != null -> practiceSource.warning
        practiceSource?.page != null && practiceSource.page != ui.page -> "无法确认原句：本题依据在第 ${practiceSource.page} 页。"
        practiceSource?.target != null && citedRange == null -> "无法确认原句：这一页的内容变了，这条出处无法定位。"
        textOnlySource -> null
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

    Desk(crumb = book.title, onBack = onBack, backLabel = if (source != null) { if (ui.review != null) "返回复习" else "返回学习" } else "返回书架", showNavigation = false, hasMargin = false,
        status = "${book.pageLabel(ui.page)} · 共 $lastPage ${if (book.format == "pptx") "张" else "页"}", directory = {
            Text("教材目录", style = MaterialTheme.typography.titleMedium)
            for (lesson in book.lessons) TextButton(onClick = { onPage(lesson.firstPage); session.goToPage(lesson.firstPage) }, enabled = !ui.busy) { Text(lesson.title) }
        }) {
        val colors = CoachTheme.colors
        val highlight = if (colors.dark) CoachV7.Dark.hl else CoachV7.Light.hl
        Column(Modifier.fillMaxSize().padding(20.dp).testTag("textbook-reader"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (source != null) "学习原文 · ${source.title}" else "教材阅读", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (hasOriginal) V7TextButton(onClick = { textView = !textView }, modifier = Modifier.testTag("book-original-toggle")) { Text(if (textView) "看原页" else "看文字") }
                V7TextButton(onClick = { asking = true }, modifier = Modifier.testTag("ask-book-passage")) { Text("选段提问") }
                V7TextButton(onClick = { explainOpen = !explainOpen }, enabled = !ui.busy, modifier = Modifier.testTag("book-explain-toggle")) {
                    Text(if (explainOpen) "回到原文" else "讲解")
                }
                V7TextButton(onClick = {
                    if (notesOpen) session.cancelNote()
                    notesOpen = !notesOpen
                }, enabled = !ui.busy && samePage, modifier = Modifier.testTag("books-notes-toggle")) {
                    Text(if (notesOpen) "收起批注" else "批注")
                }
                if (source == null) PaperButton(onLearn, enabled = !ui.busy) { Text(if (book.learning) "继续学" else "开始学") }
            }
            if (page.ocr) Text(OCR_NOTICE, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("book-ocr-notice"))
            if (source != null) Text("对应教材第 ${source.firstPage}–${source.lastPage} 页 · 返回后保留学习进度", style = MaterialTheme.typography.bodySmall)
            sourceWarning?.let { Text(it, color = v7Alert(), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("source-anchor-status")) }
            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface).testTag("reader-page")
                .focusRequester(pageFocus).focusable().onFocusChanged { pageFocused = it.hasFocus }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Ctrl/Alt/Win 组合留给系统；纯方向键才翻页。
                    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return@onPreviewKeyEvent false
                    when (readerPagingTurn(event.key, typing)) {
                        ReaderTurn.Previous -> previousPage?.let(::turnTo)
                        ReaderTurn.Next -> nextPage?.let(::turnTo)
                        ReaderTurn.First -> turnTo(book.pages.first().number)
                        ReaderTurn.Last -> turnTo(lastPage)
                        null -> return@onPreviewKeyEvent false
                    }
                    true
                }) {
                if (explainOpen) BookExplainPanel(book, ui.page, explainConsent, explainer)
                else if (textView) {
                    // 标题字号取 V7 typography（随 Aa 缩放）：一级用 headlineLarge，其余用 titleLarge，字体换成宋体。
                    val headingFont = LocalV7Fonts.current.display
                    val chapterStyle = MaterialTheme.typography.headlineLarge.toSpanStyle().copy(fontFamily = headingFont)
                    val sectionStyle = MaterialTheme.typography.titleLarge.toSpanStyle().copy(fontFamily = headingFont)
                    val marked = buildAnnotatedString {
                        append(page.text.ifBlank { "这一页没有可提取文字，请切换到原页阅读。" })
                        // 原文里的 Markdown 标题：井号隐去、标题用宋体排大一号。字符不删，块范围和出处偏移照旧对得上原文。
                        for (heading in markdownHeadings(page.text)) {
                            addStyle(SpanStyle(color = Color.Transparent, fontSize = 0.1.sp), heading.marks.first, heading.marks.last + 1)
                            addStyle(if (heading.level == 1) chapterStyle else sectionStyle, heading.title.first, heading.title.last + 1)
                        }
                        val blocks = source?.blocks.orEmpty().filter { it.page == ui.page }
                        val confirmedBlocks = if (practiceSource != null) emptyList() else if (textOnlySource) blocks else if (located is com.coach.desktop.reader.Located.Found && aspectOk) {
                            blocks.filter { !it.heading && it.text == quoteAnchor }.take(1)
                        } else emptyList()
                        for (block in confirmedBlocks) {
                            if (block.start >= 0 && block.end <= length) addStyle(SpanStyle(background = highlight), block.start, block.end)
                        }
                        if (practiceSource != null) {
                            val markedRange = if (practiceSource.target != null) citedRange else if (quoteAnchor != null &&
                                (textOnlySource || located is com.coach.desktop.reader.Located.Found && aspectOk)) {
                                val start = page.text.indexOf(quoteAnchor)
                                if (start >= 0 && page.text.indexOf(quoteAnchor, start + 1) < 0) start until start + quoteAnchor.length else null
                            } else null
                            if (markedRange != null && markedRange.last + 1 <= length)
                                addStyle(SpanStyle(background = highlight), markedRange.first, markedRange.last + 1)
                        }
                    }
                    // 正文字号、行距取 V7 内屏 token（18 × 1.85，对照 inner-read 参考图量过），随 Aa 小/中/大缩放。
                    val textScale = LocalV7TextScale.current.value
                    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = CoachV7.Size.body * textScale,
                        lineHeight = CoachV7.Size.body * (CoachV7.LineHeight.body * textScale))
                    // 正文栏按 V7 measure 33em 限宽（中号 18sp → 594dp），在纸面内居中；窄窗口照旧占满可用宽度。
                    // 只改显示：分页在导入时按文字块固定，页码、原文字符和出处偏移都不受栏宽影响。
                    // 滚动留在整张纸上，鼠标停在栏外两侧也能滚。
                    val measure = with(LocalDensity.current) { (CoachV7.Size.body * (33f * textScale)).toDp() }
                    // 换页从顶部读起；打开出处时滚到标记那一段（栏收窄后正文更长，标记常落在首屏以下）。
                    val scroll = remember(book.id, ui.page) { ScrollState(0) }
                    var layout by remember(book.id, ui.page) { mutableStateOf<TextLayoutResult?>(null) }
                    val markStart = marked.spanStyles.filter { it.item.background == highlight }.minOfOrNull { it.start }
                    LaunchedEffect(book.id, ui.page, markStart, layout != null) {
                        val laid = layout ?: return@LaunchedEffect
                        if (markStart != null && markStart <= laid.layoutInput.text.length)
                            scroll.scrollTo(laid.getLineTop(laid.getLineForOffset(markStart)).toInt())
                    }
                    Box(Modifier.fillMaxSize()) {
                        SelectionContainer {
                            Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(22.dp).testTag("reader-scroll"),
                                contentAlignment = Alignment.TopCenter) {
                                Text(marked, style = bodyStyle, onTextLayout = { layout = it },
                                    modifier = Modifier.widthIn(max = measure).fillMaxWidth().testTag("reader-text")
                                        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR))))
                            }
                        }
                        ReaderScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                } else {
                    // 页图就位前先铺一张同尺寸的纸（纸面底色、一道细边），图到了原地换掉：
                    // 不出现转圈，下面的按钮和批注也不被推下去。打不开时同一张纸上给重试。
                    val paper = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
                    val paperLine = if (colors.dark) CoachV7.Dark.line else CoachV7.Light.line
                    val aspect = readerPaperAspect(boxes?.width, boxes?.height, paperAspect)
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().verticalScroll(imageScroll).padding(end = 12.dp).testTag("remote-pages")) {
                            Box(Modifier.fillMaxWidth()) {
                                when (val loaded = image) {
                                    PageImage.Loading -> Box(Modifier.fillMaxWidth().aspectRatio(aspect)
                                        .background(paper).border(1.dp, paperLine).testTag("reader-page-placeholder"))
                                    PageImage.Failed -> Column(Modifier.fillMaxWidth().aspectRatio(aspect)
                                        .background(paper).border(1.dp, paperLine).testTag("reader-page-placeholder"),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text("这一页暂时打不开。", color = colors.fgSecondary)
                                        TextButton(onClick = { retry++ }) { Text("重试") }
                                    }
                                    is PageImage.Ready -> {
                                        Image(loaded.image, "${book.title} 第 ${ui.page} 页", contentScale = ContentScale.FillWidth,
                                            modifier = Modifier.fillMaxWidth().aspectRatio(loaded.image.width.toFloat() / loaded.image.height).testTag("remote-image"))
                                        if (located is com.coach.desktop.reader.Located.Found && aspectOk && boxes != null) {
                                            androidx.compose.foundation.Canvas(Modifier.testTag("remote-highlight").semantics { contentDescription = "原句：$quoteAnchor" }.matchParentSize()) {
                                                val k = size.width / boxes.width.toFloat()
                                                for (r in located.rects) drawRect(
                                                    highlight,
                                                    topLeft = androidx.compose.ui.geometry.Offset(r.x0.toFloat() * k, r.y0.toFloat() * k),
                                                    size = androidx.compose.ui.geometry.Size((r.x1 - r.x0).toFloat() * k, (r.y1 - r.y0).toFloat() * k),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        ReaderScrollbar(imageScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
            if (notesOpen && samePage && view != null) {
                Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                    .testTag("books-notes"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!session.noting) TextButton(onClick = { session.beginNote() }, modifier = Modifier.testTag("books-note-new")) { Text("写批注") }
                    if (session.noteLine >= 0) {
                        Text("选一句原文写批注", style = MaterialTheme.typography.bodySmall)
                        session.pageLines().forEachIndexed { index, range ->
                            TextButton(onClick = { session.chooseNoteLine(index) }, modifier = Modifier.fillMaxWidth().testTag("books-note-line-$index"),
                                colors = ButtonDefaults.textButtonColors(containerColor = if (session.noteLine == index) highlight else Color.Transparent)) {
                                Text(view.text.substring(range), modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    NotesSection(session, remote, view, textColor = MaterialTheme.colorScheme.onSurface, keyboardHint = false)
                    if (session.noteDraft != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { session.requestNoteSave() }, enabled = !session.noteSaving, modifier = Modifier.testTag("books-note-save")) { Text("保存批注") }
                        TextButton(onClick = { session.cancelNote() }, modifier = Modifier.testTag("books-note-close")) { Text("关闭编辑区") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 快捷键写在悬停提示里：不用记，也不用在页面上多一行说明。提示用共享的 V7Tooltip，
                // 快捷键那半截走数字字体，跟面板上的其他提示一致。
                V7Tooltip("上一页", shortcut = "←  PageUp") {
                    PaperButton({ turnTo(previousPage) }, enabled = !ui.busy && previousPage != null, modifier = Modifier.testTag("previous-page").testTag("bar-prev-page")) { Text("上一页") }
                }
                // 页面下方不再常驻说明行；同一句挪到页码的悬停提示里，图片书/文字版各自说清。
                V7Tooltip(when {
                    book.format == "pdf" -> "页码按 PDF 原文件计算。"
                    book.format in BookFormats.images -> "一张图片为一页。"
                    else -> "页码按阅读器中的正文分页计算。"
                }) {
                    Text("${ui.page} / $lastPage", color = colors.fgSecondary, fontFamily = LocalV7Fonts.current.numbers,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("reader-page-number"))
                }
                V7Tooltip("下一页", shortcut = "→  PageDown") {
                    PaperButton({ turnTo(nextPage) }, enabled = !ui.busy && nextPage != null, modifier = Modifier.testTag("next-page").testTag("bar-next-page")) { Text("下一页") }
                }
                Spacer(Modifier.weight(1f))
                Text("页码", color = colors.fgSecondary, style = MaterialTheme.typography.bodySmall)
                val field = RoundedCornerShape(8.dp)
                BasicTextField(jump, { jump = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fgPrimary, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(colors.fgPrimary),
                    modifier = Modifier.width(64.dp).testTag("page-jump").v7FocusRing(field).clip(field).background(colors.surface2)
                        .border(1.dp, colors.borderSubtle, field).onFocusChanged { jumpFocused = it.isFocused }
                        .padding(horizontal = 10.dp, vertical = 9.dp))
                V7TextButton({ jump.toIntOrNull()?.let { turnTo(it); focusManager.clearFocus() } }, enabled = !ui.busy && jump.toIntOrNull()?.let { book.page(it) != null } == true) { Text("前往") }
            }
        }
    }
    if (asking) BookPassageAsk(book, page, support) { asking = false }
}

/** 键盘翻页的去向。 */
internal enum class ReaderTurn { Previous, Next, First, Last }

/**
 * 阅读区认的翻页键：←/PageUp 上一页，→/PageDown 下一页，Home 首页，End 末页。
 * 正在输入文字时一个都不接——方向键和 Home/End 归光标用。
 */
internal fun readerPagingTurn(key: Key, typing: Boolean): ReaderTurn? {
    if (typing) return null
    return when (key) {
        Key.DirectionLeft, Key.PageUp -> ReaderTurn.Previous
        Key.DirectionRight, Key.PageDown -> ReaderTurn.Next
        Key.MoveHome -> ReaderTurn.First
        Key.MoveEnd -> ReaderTurn.Last
        else -> null
    }
}

/** 焦点是否落在会吃掉方向键的文字输入里：选段提问、页码框、打开着的批注编辑区。 */
internal fun readerTyping(asking: Boolean, jumpFocused: Boolean, notesOpen: Boolean, noting: Boolean): Boolean =
    asking || jumpFocused || (notesOpen && noting)

/**
 * 页图还没到时的纸面宽高比：先用这一页自己的几何（文字的框），这一页还没量到才借同一本书
 * 上一张成功页图的比例，都没有就按 A4（1:√2）铺。三种都是纸，图到了只换内容不换框。
 * 顺序不能反：上一页的比例是另一张纸，横竖混排的书照它铺，图一到就会塌掉一截。
 */
internal fun readerPaperAspect(boxesWidth: Double?, boxesHeight: Double?, remembered: Float?): Float =
    boxesWidth?.let { w -> boxesHeight?.takeIf { it > 0 }?.let { h -> (w / h).toFloat() } }
        ?: remembered
        ?: (1f / 1.4142f)

/** 一行 Markdown 标题：[marks] 是井号连同后面的空白，[title] 是标题文字；都是原文里的字符下标。 */
internal data class MarkdownHeading(val level: Int, val marks: IntRange, val title: IntRange)

private val markdownHeadingLine = Regex("(?m)^(#{1,6})[ \\t]+(\\S.*?)[ \\t\\r]*$")

internal fun markdownHeadings(text: String): List<MarkdownHeading> = markdownHeadingLine.findAll(text).map { m ->
    val hashes = m.groups[1]!!.range
    val title = m.groups[2]!!.range
    MarkdownHeading(hashes.last - hashes.first + 1, hashes.first until title.first, title)
}.toList()

/**
 * 阅读区的滚动条，直接用共享的 `v7/V7Scrollbar`：固定 12dp 的槽，槽里 4dp 细条悬停加粗到 8dp，
 * 加粗不挪正文。内容不满一屏时整条不占位，跟清单 W5 说的一致。
 */
@Composable
private fun ReaderScrollbar(scroll: ScrollState, modifier: Modifier = Modifier) {
    if (scroll.maxValue <= 0) return
    V7Scrollbar(rememberScrollbarAdapter(scroll), modifier)
}

/** 待办的阅读器焦点请求：发起时的书与出处。 */
internal class ReaderFocusTicket(val bookId: String?, val source: BookLesson?)

/** 焦点只能还给发起请求时的那本书、那个出处的阅读屏；退出、切书、换出处、弹窗出现后旧请求一律作废。 */
internal fun readerFocusStillOwned(startBookId: String?, startSource: BookLesson?, current: LibraryUi): Boolean =
    current.screen == BookScreen.READER && current.source != null && current.prompt == null &&
        current.book?.id == startBookId && current.source == startSource

/**
 * 翻页之后等阅读器丢掉焦点（最多 [timeoutMs]），再核对发起时的书和出处仍是当前的，才发焦点票。
 * 抽成独立函数是为了能用受控的延迟和状态变化单独验证"旧请求晚到"的各种情形。
 */
internal suspend fun awaitReaderFocusRestore(
    startBookId: String?, startSource: BookLesson?,
    hasFocus: () -> Boolean, current: () -> LibraryUi,
    timeoutMs: Long = 1_000, issue: (ReaderFocusTicket) -> Unit,
) {
    val lostFocus = withTimeoutOrNull(timeoutMs) { snapshotFlow { !hasFocus() }.first { it } } == true
    if (lostFocus && !hasFocus() && readerFocusStillOwned(startBookId, startSource, current())) issue(ReaderFocusTicket(startBookId, startSource))
}

/**
 * 消费焦点票：票和界面状态可能在同一个 UI 回合里一起变，所以真正请求焦点前读的是"最新"的界面状态，
 * 而不是效果启动时捕获的那一份；票上的书和出处不再是当前阅读屏时不请求。
 */
@Composable
internal fun ReaderFocusTicketEffect(ticket: ReaderFocusTicket?, ui: LibraryUi, requestFocus: () -> Unit) {
    val latestUi by rememberUpdatedState(ui)
    LaunchedEffect(ticket) {
        val t = ticket ?: return@LaunchedEffect
        if (readerFocusStillOwned(t.bookId, t.source, latestUi)) requestFocus()
    }
}
