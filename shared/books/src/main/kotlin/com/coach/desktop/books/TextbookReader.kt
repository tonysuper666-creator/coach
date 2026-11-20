package com.coach.desktop.books

import com.coach.ui.pageturn.PageTurnStyle
import com.coach.ui.pageturn.ScrollReadPosition
import com.coach.ui.pageturn.ScrollReadState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.Desk
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.V7Tooltip
import com.coach.ui.v7.v7FocusRing
import com.coach.desktop.reader.ReaderSession
import com.coach.desktop.reader.ReaderDraftScope
import com.coach.desktop.reader.NotesSection
import com.coach.domain.reader.ReaderTextRange
import com.coach.domain.reader.ReaderTextUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.sql.SQLException

/**
 * 出处打开后这一页要标什么。练习依据只有页 + 原句；讲解出处还带着解析器核对过的块范围，
 * 所以同一页出现两句相同原文时，标的是被点的那一块，而不是第一次字符串命中。
 */
internal data class PracticeSource(val page: Int?, val quote: String?, val warning: String?, val target: BookSourceTarget? = null)

internal data class CitedPdfLocation(val boxes: com.coach.desktop.reader.PageBoxes, val located: com.coach.desktop.reader.Located)

/** Only called after the citation's exact UTF-16 range has been checked against the imported page. */
internal fun locateCitedPdfRange(page: PdfPageText, text: String, range: IntRange): CitedPdfLocation {
    val width = page.frame.cropBox.right - page.frame.cropBox.left
    val height = page.frame.cropBox.top - page.frame.cropBox.bottom
    val sideways = page.frame.rotationClockwise in listOf(90, 270)
    val displayWidth = if (sideways) height else width
    val displayHeight = if (sideways) width else height
    val valid = page.unit.text == text && range.first >= 0 && !range.isEmpty() && range.last < text.length
    val rects = if (valid) PdfTextSelection.boxesForVerifiedRange(page.unit, text,
        com.coach.domain.reader.ReaderTextRange(range.first, range.last + 1)).map { box ->
        com.coach.desktop.reader.WordBox(box.x * displayWidth, box.y * displayHeight,
            (box.x + box.width) * displayWidth, (box.y + box.height) * displayHeight, "")
    } else emptyList()
    val located = when {
        !valid -> com.coach.desktop.reader.Located.NotFound
        page.unit.spans.isEmpty() -> com.coach.desktop.reader.Located.NoTextLayer
        rects.isEmpty() -> com.coach.desktop.reader.Located.NotFound
        else -> com.coach.desktop.reader.Located.Found(rects)
    }
    return CitedPdfLocation(com.coach.desktop.reader.PageBoxes(displayWidth, displayHeight, rects), located)
}

@Composable
internal fun TextbookReader(
    ui: LibraryUi, library: TextbookLibrary, support: BookLearningSupport, practiceSource: PracticeSource?,
    pageFocus: FocusRequester, onNoteSession: (ReaderSession?) -> Unit, onPage: (Int) -> Unit, onBack: () -> Unit,
    onLearn: () -> Unit, explainProvider: BookExplainer? = null, openPairing: Boolean = false,
) {
    val book = checkNotNull(ui.book)
    val page = book.page(ui.page)
    if (page == null) {
        LaunchedEffect(book.id, ui.page) { onPage(book.pages.first().number) }
        return
    }
    val hasOriginal = book.format == "pdf" || book.format in BookFormats.images
    val originalPdf = book.format == "pdf"
    val textOnlySource = !hasOriginal || page.ocr
    val lastPage = book.pages.maxOf { it.number }
    var textView by remember(library, book.id) {
        mutableStateOf(!hasOriginal || (library.preferredTextView(book.id) ?: (page.ocr && !(originalPdf && originalPdfTouchEnabled))))
    }
    var viewSaveRevision by remember(library, book.id) { mutableIntStateOf(0) }
    var viewSaveError by remember(library, book.id) { mutableStateOf(false) }
    val paging = remember(book.id) { BookPagingState(BookPageAnchor(ui.page)) }
    val pdf = remember(library, book.id) { OriginalPdfReading(ui.page, !originalPdf || library.pdfScrollMode(book.id)) }
    val pdfLayers = remember(library, book.id) { OriginalPdfTextLayers(library, book) }
    val scroll = remember(book.id) { ScrollReadState(book.pages.size, ScrollReadPosition(book.pages.indexOf(page))) }
    val pdfVisible = originalPdf && !textView
    val pdfScrolling = pdfVisible && pdf.scrolling
    val previousPage = if (pdfVisible && !pdf.scrolling) pdf.adjacent(false)
        else book.pages.lastOrNull { it.number < ui.page }?.number
    val nextPage = if (pdfVisible && !pdf.scrolling) pdf.adjacent(true)
        else book.pages.firstOrNull { it.number > ui.page }?.number
    var turnStyle by remember { mutableStateOf(PageTurnStyle.Flip) }
    var readMode by remember(book.id) { mutableStateOf(BookReadMode.Paged) }
    LaunchedEffect(ui.page) {
        val previouslyPublished = paging.publishedImportPage
        paging.followImportPage(ui.page)
        if (pdfVisible && previouslyPublished != paging.publishedImportPage && pdf.page != ui.page) {
            pdf.selection = null
            pdf.jump(ui.page)
        }
    }
    var editingJump by remember(book.id) { mutableStateOf(false) }
    var jump by remember(book.id, ui.page) { mutableStateOf(ui.page.toString()) }
    var retry by remember { mutableIntStateOf(0) }
    var notesOpen by remember(book.id) { mutableStateOf(false) }
    var explainOpen by remember(book.id, openPairing) { mutableStateOf(openPairing) }
    var explainingSelection by remember(book.id) { mutableStateOf<SourceBlock?>(null) }
    var passageJump by remember(book.id) { mutableStateOf<BookPassage?>(null) }
    var passageJumpTick by remember(book.id) { mutableIntStateOf(0) }
    var highlightRevision by remember(book.id) { mutableIntStateOf(0) }
    val marks = remember(library, book.id, page, highlightRevision) { library.highlights(book.id, page) }
    var savingMark by remember(book.id) { mutableStateOf(false) }
    var markError by remember(book.id, ui.page) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val chosen = pdf.selection?.let { selected ->
        val range = selected.range
        val stored = book.page(selected.page)?.text ?: return@let null
        SourceBlock("${book.id}:p${selected.page}:${range.startUtf16}", selected.page, range.startUtf16, range.endUtf16,
            false, stored.substring(range.startUtf16, range.endUtf16))
    }?.takeIf { it.text.isNotBlank() }
    val learnableSelection = chosen?.takeIf { it.text.trim().length <= 4_000 }
    val explainer = remember(library, explainProvider) { explainProvider ?: BookExplainer(library = library) }
    val explainConsent = remember(library) { ExplainConsent(library.directory) }
    val source = ui.source
    LaunchedEffect(book.id, source, practiceSource) {
        if (source == null && practiceSource == null) return@LaunchedEffect
        val target = practiceSource?.target
        val number = target?.pdfPage ?: practiceSource?.page ?: ui.page
        val stored = book.page(number)?.text.orEmpty()
        val blocks = source?.blocks.orEmpty().filter {
            it.page == number && it.start >= 0 && it.end > it.start && it.end <= stored.length
        }
        // A lesson opens at its body evidence. Imported physical-page markers and
        // chapter titles can precede that evidence on a different laid-out page.
        val physicalMarker = Regex("<!--\\s*PDF 第 (\\d+) 页 · 书第 (.*?) 页\\s*-->")
        val block = blocks.firstOrNull { !it.heading && !physicalMarker.matches(it.text.trim()) }
            ?: blocks.firstOrNull { !physicalMarker.matches(it.text.trim()) }
        // Older imported lessons can consist only of the physical-page marker.
        // Keep their source page and highlight, but open the page's body context
        // rather than a metadata-only preamble sheet. Exact citations still win.
        val bodyContext = if (block == null && blocks.isNotEmpty()) bookParagraphs(stored, pdf = false)
            .paragraphs.firstOrNull { it.kind == BookParagraph.Kind.BODY &&
                !physicalMarker.matches(stored.substring(it.start, it.end).trim()) }?.start else null
        val offset = when {
            target != null && target.start >= 0 && target.end > target.start && target.end <= stored.length &&
                stored.substring(target.start, target.end) == target.quote -> target.start
            block != null -> block.start
            bodyContext != null -> bodyContext
            else -> practiceSource?.quote?.let { quote -> stored.indexOf(quote).takeIf {
                it >= 0 && stored.indexOf(quote, it + 1) < 0
            } } ?: 0
        }
        paging.anchor = BookPageAnchor(number, offset)
        if (originalPdf && number == ui.page) pdf.jump(number)
    }
    // 从答后讲解的「去原文页配对」过来、这台电脑的连接已停用：原文页直接摊开配对，不用再去找「讲解」。
    LaunchedEffect(explainer, source != null) {
        if (source == null) return@LaunchedEffect
        if (!explainer.loaded) explainer.load()
        if (explainer.revoked && explainer.identity == null) explainOpen = true
    }
    LaunchedEffect(book.id, ui.page, source) {
        if (page.ocr && source != null) textView = true
    }
    var asking by remember(book.id, ui.page) { mutableStateOf(false) }
    // A laid-out sheet can start on the previous imported page. Its bookmark
    // follows that first source, but paragraph actions must keep the source
    // explicitly selected by a page jump or citation.
    val paragraphPage = if (textView) book.page(paging.anchor.importPage) ?: page else page

    // The page surface fetches its own raster at the current viewport size.
    val readerSource = remember(library) { com.coach.desktop.reader.LocalBooksReaderSource(library, loadPageImages = false) }
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
    val focusManager = LocalFocusManager.current
    var jumpFocused by remember { mutableStateOf(false) }
    var pageFocused by remember { mutableStateOf(false) }
    val typing = readerTyping(asking, jumpFocused, notesOpen, session.noting)
    LaunchedEffect(book.id, ui.page, typing, pdfScrolling) {
        if (typing) return@LaunchedEffect
        // A successful request already owns focus. Do not leave another frame's
        // request pending: it could steal focus from a newly clicked page field.
        repeat(5) {
            withFrameNanos { }
            // Mouse-up can finish moving focus to the paging button after its
            // click changed ui.page. Inspect focus once that frame has settled.
            if (pageFocused) return@LaunchedEffect
            val requested = runCatching { pageFocus.requestFocus() }.getOrDefault(false)
            if (requested) return@LaunchedEffect
        }
    }
    val publishPdfPage: (Int) -> Unit = { number ->
        val selection = pdf.selection?.takeIf { it.page == number }
        paging.anchor = BookPageAnchor(number, selection?.range?.startUtf16 ?: 0)
        if (paging.publishImportPage(number)) onPage(number)
        session.goToPage(number)
    }
    val turnTo: (Int?) -> Unit = { pageNumber ->
        if (pageNumber != null && book.page(pageNumber) != null) {
            passageJump = null
            pdf.selection = null
            if (pdfVisible) pdf.jump(pageNumber)
            paging.anchor = BookPageAnchor(pageNumber)
            val visiblePage = if (textView && paging.pages.isNotEmpty())
                paging.pages[paging.index].anchor.importPage else pageNumber
            if (paging.publishImportPage(visiblePage)) onPage(visiblePage)
            session.goToPage(visiblePage)
        }
    }

    // Keep the event handler connected to the current neighbours after recomposition.
    // A remembered key callback must not retain a local function's old page captures.
    val turnLayout by rememberUpdatedState<(ReaderTurn) -> Unit> { turn ->
        if (!textView) {
            when (turn) {
                ReaderTurn.Previous -> turnTo(previousPage)
                ReaderTurn.Next -> turnTo(nextPage)
                ReaderTurn.First -> turnTo(book.pages.first().number)
                ReaderTurn.Last -> turnTo(lastPage)
            }
        } else paging.move(turn)?.let { anchor ->
            if (paging.publishImportPage(anchor.importPage)) {
                onPage(anchor.importPage)
                session.goToPage(anchor.importPage)
            }
        }
    }

    val openPassage: (BookPassage) -> Unit = open@{ passage ->
        val target = book.page(passage.page) ?: return@open
        if (passage.book != book.id || passage.start !in 0 until passage.end || passage.end > target.text.length ||
            target.text.substring(passage.start, passage.end) != passage.text) return@open
        pdf.selection = null
        asking = false
        explainOpen = false
        textView = false
        passageJump = passage
        passageJumpTick++
        pdf.jump(passage.page)
        paging.anchor = BookPageAnchor(passage.page, passage.start)
        if (paging.publishImportPage(passage.page)) onPage(passage.page)
    }
    val quoteAnchor = remember(ui.page, source, practiceSource, passageJump) {
        if (passageJump?.page == ui.page) passageJump?.text
        else if (source == null) null
        else if (practiceSource != null) practiceSource.quote?.takeIf { practiceSource.page == ui.page }
        else StudyCoachCourse.sourceQuote(source, ui.page)
    }
    // 讲解出处带着解析器核对过的块范围进来。只有这一页的同一偏移仍是同一句原文时才用它；
    // 对不上就什么都不标，绝不回落到「第一次出现」或别处的句子。
    val citedRange = remember(practiceSource?.target, passageJump, ui.page, page.text) {
        passageJump?.takeIf { it.page == ui.page }?.let { return@remember it.start until it.end }
        val target = practiceSource?.target?.takeIf { it.pdfPage == ui.page } ?: return@remember null
        if (target.start >= 0 && target.end > target.start && target.end <= page.text.length &&
            page.text.substring(target.start, target.end) == target.quote) target.start until target.end else null
    }
    LaunchedEffect(pdfLayers, page.number, retry) { if (originalPdf) pdfLayers.keep(page) }
    val originalText = pdfLayers[page.number]
    val layer = originalText?.getOrNull()
    val rangeLocation = remember(layer, citedRange, page.text) {
        val range = citedRange ?: return@remember null
        val unit = layer ?: return@remember null
        val frame = unit.spans.firstOrNull()?.geometry?.frame ?: return@remember null
        locateCitedPdfRange(PdfPageText(frame, unit, emptyList()), page.text, range)
    }
    var originalSize by remember(book.id) { mutableStateOf(IntSize.Zero) }
    val image = rememberOriginalPageImage(library, book, page.number,
        hasOriginal && !pdfVisible && (!textView || source != null), null, retry)
    // 页图还没回来时先铺纸：这一页自己有尺寸就按它铺，量不到才借上一张成功页图的比例。
    // 图到了原地换掉，后面的按钮不往下跳；横竖混排的书也不会先铺上一页那张的纸再塌下来。
    var paperAspect by remember(book.id) { mutableStateOf<Float?>(null) }
    LaunchedEffect(image) {
        (image as? PageImage.Ready)?.let { paperAspect = it.image.width.toFloat() / it.image.height }
    }
    val imageScroll = remember(book.id, ui.page) { ScrollState(0) }
    val imageHeight = originalSize.height
    val view = (remote.page as? com.coach.desktop.reader.PageState.Ready)?.view
    val samePage = remote.current?.id == book.id && remote.current?.version == "1" &&
        view?.document == book.id && view.pdfPage == ui.page
    val boxes = if (citedRange != null) rangeLocation?.boxes
        else if (samePage) (remote.boxes as? com.coach.desktop.reader.BoxesState.Ready)?.boxes else null
    val located = if (citedRange != null) rangeLocation?.located
        else if (boxes != null && quoteAnchor != null) com.coach.desktop.reader.QuoteLocator.locate(boxes, quoteAnchor) else null
    val loadedAspect = if (pdfVisible) pdf.rasters[ui.page]?.aspect else
        (image as? PageImage.Ready)?.image?.let { it.width.toFloat() / it.height }
    val aspectOk = boxes != null && loadedAspect != null && kotlin.math.abs(loadedAspect - boxes.width / boxes.height) < 0.05
    val textTarget = practiceSource?.target
    val textTargetValid = textTarget?.let { target -> book.page(target.pdfPage)?.text?.let { stored ->
        target.start >= 0 && target.end > target.start && target.end <= stored.length &&
            stored.substring(target.start, target.end) == target.quote
    } } == true
    val sourceWarning = if (passageJump?.page == page.number && originalText != null && !textView &&
        rangeLocation?.located !is com.coach.desktop.reader.Located.Found) "无法确认原句：无法读取这一页的文字位置。"
    else if (source == null) null else when {
        textView && practiceSource?.warning != null -> practiceSource.warning
        textView && textTarget != null && !textTargetValid -> "无法确认原句：这一页的内容变了，这条出处无法定位。"
        textView && citedRange == null && !quoteAnchor.isNullOrEmpty() &&
            page.text.indexOf(quoteAnchor) != page.text.lastIndexOf(quoteAnchor) -> "无法确认原句：这一页有多处相同内容。"
        textView -> null
        practiceSource?.warning != null -> practiceSource.warning
        practiceSource?.page != null && practiceSource.page != ui.page -> "无法确认原句：本题依据在第 ${practiceSource.page} 页。"
        practiceSource?.target != null && citedRange == null -> "无法确认原句：这一页的内容变了，这条出处无法定位。"
        textOnlySource -> null
        image is PageImage.Failed || (pdfVisible && pdf.rasters[ui.page]?.failed == true) -> "无法确认原句：这一页的图像打不开。"
        citedRange != null && originalText != null && rangeLocation == null -> "无法确认原句：无法读取这一页的文字位置。"
        remote.failure != null || remote.page is com.coach.desktop.reader.PageState.Failed -> "无法确认原句：无法读取这一页的定位信息。"
        remote.current != null && (remote.current?.id != book.id || remote.current?.version != "1") -> "无法确认原句：教材版本不一致。"
        citedRange == null && samePage && remote.boxes is com.coach.desktop.reader.BoxesState.Failed -> "无法确认原句：无法读取这一页的文字位置。"
        samePage && quoteAnchor.isNullOrBlank() -> "无法确认原句：没有可定位的原文。"
        located == com.coach.desktop.reader.Located.NoTextLayer -> "无法确认原句：这一页没有可定位的文字。"
        located == com.coach.desktop.reader.Located.NotFound -> "无法确认原句：这一页没有找到对应内容。"
        located == com.coach.desktop.reader.Located.Ambiguous -> "无法确认原句：这一页有多处相同内容。"
        located is com.coach.desktop.reader.Located.Found && loadedAspect != null && !aspectOk -> "无法确认原句：页面图像与文字位置不一致。"
        else -> null
    }
    LaunchedEffect(pdfScrolling, ui.page, citedRange, passageJumpTick, located, aspectOk) {
        if (pdfScrolling && aspectOk && boxes != null && located is com.coach.desktop.reader.Located.Found) {
            val top = located.rects.minOf { it.y0 } / boxes.height
            pdf.jump(ui.page, top.toFloat(), reveal = true)
        }
    }

    LaunchedEffect(citedRange, passageJumpTick, located, aspectOk, imageHeight, imageScroll.maxValue, imageScroll.viewportSize,
        textView, explainOpen) {
        if (located is com.coach.desktop.reader.Located.Found && boxes != null &&
            aspectOk && imageHeight > 0 && imageScroll.maxValue != Int.MAX_VALUE && !textView && !explainOpen) {
            val top = located.rects.minOf { it.y0 } / boxes.height * imageHeight
            imageScroll.scrollTo((top - imageScroll.viewportSize / 3).toInt().coerceIn(0, imageScroll.maxValue))
        }
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
        paperMaxWidth = if (textView || pdfVisible) Dp.Infinity else Dp.Unspecified,
        typeMenuExtra = { close ->
            if (pdfVisible) for (scrolling in listOf(false, true)) {
                val label = if (scrolling) "滚动" else "翻页"
                DropdownMenuItem(text = { Text(label + if (pdf.scrolling == scrolling) " ✓" else "") },
                    modifier = Modifier.testTag(if (scrolling) "original-mode-scroll" else "original-mode-paged"),
                    onClick = {
                        if (pdf.scrolling != scrolling) {
                            pdf.switchMode()
                            library.savePdfScrollMode(book.id, scrolling)
                            publishPdfPage(pdf.page)
                        }
                        close()
                    })
            }
        },
        status = "${book.pageLabel(ui.page)} · 共 $lastPage ${if (book.format == "pptx") "张" else "页"}", directory = {
            Text("教材目录", style = MaterialTheme.typography.titleMedium)
            for (lesson in book.lessons) TextButton(onClick = { turnTo(lesson.firstPage) }, enabled = !ui.busy) { Text(lesson.title) }
        }) {
        val colors = CoachTheme.colors
        val highlight = if (colors.dark) CoachV7.Dark.hl else CoachV7.Light.hl
        val touchPdf = originalPdf && originalPdfTouchEnabled
        val toolPadding = if (touchPdf) PaddingValues(horizontal = 8.dp) else ButtonDefaults.TextButtonContentPadding
        Column(Modifier.fillMaxSize().padding(20.dp).testTag("textbook-reader"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val actions: @Composable () -> Unit = {
                if (hasOriginal) V7TextButton(onClick = {
                    val nextTextView = !textView
                    // A reflowed sheet can start on an earlier page than its reading anchor.
                    if (!nextTextView) {
                        val passage = passageJump?.takeIf {
                            touchPdf && it.page == paging.anchor.importPage && it.start == paging.anchor.offsetUtf16
                        }
                        if (passage != null) openPassage(passage)
                        else {
                            if (touchPdf) passageJump = null
                            if (originalPdf) pdf.jump(paragraphPage.number)
                            if (paging.publishImportPage(paragraphPage.number)) onPage(paragraphPage.number)
                        }
                    } else if (chosen != null) {
                        paging.anchor = BookPageAnchor(chosen.page, chosen.start)
                        if (touchPdf) passageJump = BookPassage.from(book, chosen)
                    }
                    textView = nextTextView
                    pdf.selection = null
                    viewSaveError = false
                    val revision = ++viewSaveRevision
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            library.persistTextView(book.id, nextTextView)
                        } catch (_: SQLException) {
                            if (revision == viewSaveRevision) viewSaveError = true
                        }
                    }
                }, modifier = Modifier.testTag("book-original-toggle"), contentPadding = toolPadding) {
                    Text(if (textView) "看原页" else "看文字")
                }
                V7TextButton(onClick = { asking = true }, enabled = chosen == null || learnableSelection != null,
                    modifier = Modifier.testTag("ask-book-passage"), contentPadding = toolPadding) { Text("选段提问") }
                V7TextButton(onClick = {
                    explainingSelection = if (explainOpen) null else learnableSelection
                    explainOpen = !explainOpen
                }, enabled = !ui.busy && (explainOpen || chosen == null || learnableSelection != null),
                    modifier = Modifier.testTag("book-explain-toggle"), contentPadding = toolPadding) {
                    Text(if (explainOpen) "回到原文" else if (chosen != null) "讲解选段" else "讲解")
                }
                if (!textView && !explainOpen && chosen != null) {
                    val selected = checkNotNull(pdf.selection)
                    val highlighted = remember(library, book.id, selected, highlightRevision) {
                        val selectedPage = checkNotNull(book.page(selected.page))
                        selected.range in library.highlights(book.id, selectedPage)
                    }
                    V7TextButton(onClick = {
                        val passage = BookPassage.from(book, chosen)
                        savingMark = true
                        markError = false
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { library.setHighlight(passage, !highlighted) }
                                highlightRevision++
                                // 高亮后选区会清掉；先把阅读位置记在选区首字，之后切到文字版还回到这里
                                paging.anchor = BookPageAnchor(chosen.page, chosen.start)
                                if (pdf.selection == selected) pdf.selection = null
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { markError = true }
                            finally { savingMark = false }
                        }
                    }, enabled = !savingMark, modifier = Modifier.testTag("original-pdf-highlight"), contentPadding = toolPadding) {
                        Text(if (highlighted) "取消高亮" else "高亮")
                    }
                } else if (touchPdf && !textView && !explainOpen) {
                    V7TextButton(onClick = {}, enabled = false, contentPadding = toolPadding) { Text("高亮") }
                }
                V7TextButton(onClick = {
                    if (notesOpen) session.cancelNote()
                    notesOpen = !notesOpen
                }, enabled = !ui.busy && samePage, modifier = Modifier.testTag("books-notes-toggle"), contentPadding = toolPadding) {
                    Text(if (notesOpen) "收起批注" else "批注")
                }
                if (source == null) PaperButton(onLearn, enabled = !ui.busy) { Text(if (book.learning) "继续学" else "开始学") }
            }
            if (touchPdf) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) { actions() }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (source != null) "学习原文 · ${source.title}" else "教材阅读",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    actions()
                }
            }
            if (page.ocr) Text(OCR_NOTICE, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("book-ocr-notice"))
            if (viewSaveError) Text("阅读视图暂未保存。切换视图后会再试一次。", style = MaterialTheme.typography.bodySmall, color = v7Alert())
            if (markError) Text("高亮暂未保存，请再试一次。", style = MaterialTheme.typography.bodySmall, color = v7Alert())
            if (chosen != null && learnableSelection == null) Text("选段超过 4000 字。请选短一些再讲解或提问。",
                style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            if (source != null) Text("对应教材第 ${source.firstPage}–${source.lastPage} 页 · 返回后保留学习进度", style = MaterialTheme.typography.bodySmall)
            sourceWarning?.let { Text(it, color = v7Alert(), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("source-anchor-status")) }
            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface).testTag("reader-page")
                .focusRequester(pageFocus).onFocusChanged { pageFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Ctrl/Alt/Win 组合留给系统；纯方向键才翻页。
                    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return@onPreviewKeyEvent false
                    if (pdfScrolling && !typing) when (event.key) {
                        Key.PageDown, Key.PageUp, Key.Spacebar, Key.MoveHome, Key.MoveEnd, Key.DirectionDown, Key.DirectionUp -> {
                            pdf.selection = null
                            passageJump = null
                            scope.launch {
                                when (event.key) {
                                    Key.MoveEnd -> scroll.scrollToEnd()
                                    Key.MoveHome -> scroll.scrollTo(ScrollReadPosition(0))
                                    Key.DirectionDown, Key.DirectionUp -> scroll.scrollLine(event.key == Key.DirectionDown)
                                    else -> scroll.scrollScreen(event.key == Key.PageDown || (event.key == Key.Spacebar && !event.isShiftPressed))
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                    }
                    val turn = if (textView && !typing && event.key == Key.Spacebar) (if (event.isShiftPressed) ReaderTurn.Previous else ReaderTurn.Next)
                        else readerPagingTurn(event.key, typing)
                    turn?.let(turnLayout) ?: return@onPreviewKeyEvent false
                    true
                }.focusable()) {
                if (explainOpen) BookExplainPanel(book, paragraphPage.number, explainConsent, explainer,
                    selection = explainingSelection, onOpenOriginal = openPassage.takeIf { explainingSelection != null })
                else if (textView) {
                    val ranges = remember(book.pages, source, practiceSource, ui.page, quoteAnchor, located, aspectOk, marks, passageJump) {
                        val result = mutableMapOf<Int, List<IntRange>>()
                        if (practiceSource == null) {
                            source?.blocks.orEmpty().groupBy { it.page }.forEach { (number, blocks) ->
                                val stored = book.page(number)?.text.orEmpty()
                                val confirmed = if (book.format != "pdf" || book.page(number)?.ocr == true) blocks
                                    else if (number == ui.page && located is com.coach.desktop.reader.Located.Found && aspectOk)
                                        blocks.filter { !it.heading && it.text == quoteAnchor }.take(1)
                                    else emptyList()
                                result[number] = confirmed.filter { it.start >= 0 && it.end > it.start &&
                                    it.end <= stored.length && stored.substring(it.start, it.end) == it.text }
                                    .map { it.start until it.end }
                            }
                        } else {
                            val target = practiceSource.target
                            val number = target?.pdfPage ?: practiceSource.page
                            val stored = number?.let { book.page(it)?.text }.orEmpty()
                            val range = if (target != null) {
                                if (target.start >= 0 && target.end > target.start && target.end <= stored.length &&
                                    stored.substring(target.start, target.end) == target.quote) target.start until target.end else null
                            } else practiceSource.quote?.takeIf { it.isNotEmpty() }?.let { quote ->
                                val at = stored.indexOf(quote)
                                if (at >= 0 && stored.indexOf(quote, at + 1) < 0) at until at + quote.length else null
                            }
                            if (number != null && range != null) result[number] = listOf(range)
                        }
                        passageJump?.let { result[it.page] = result[it.page].orEmpty() + listOf(it.start until it.end) }
                        if (marks.isNotEmpty()) result[ui.page] = result[ui.page].orEmpty() +
                            marks.map { it.startUtf16 until it.endUtf16 }
                        result
                    }
                    BookPageView(book, paging, ranges, turnLayout, libraryDirectory = library.directory,
                        turnStyle = turnStyle, readMode = readMode, onOpenTableRow = openPassage, onPosition = { number ->
                        if (paging.publishImportPage(number)) {
                            onPage(number)
                            session.goToPage(number)
                        }
                    })

                } else if (originalPdf) {
                    val citation = if (boxes != null && located is com.coach.desktop.reader.Located.Found)
                        CitedPdfLocation(boxes, located) else null
                    OriginalPdfReader(book, library, pdf, scroll, pdfLayers, citation, quoteAnchor, highlightRevision,
                        turnStyle, publishPdfPage, onTurn = turnTo)
                } else {
                    // 页图就位前先铺一张同尺寸的纸（纸面底色、一道细边），图到了原地换掉：
                    // 不出现转圈，下面的按钮和批注也不被推下去。打不开时同一张纸上给重试。
                    val paper = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
                    // 只有 pdf 的非 OCR 页，boxes 才是这一页真实的 cropBox；图片书和 OCR 页那份是
                    // 1200x1600 的占位常数，按它铺纸反而会和真图差一截，所以只认真量过的几何。
                    val pageBoxes = if (readerBoxesAreGeometry(book.format, page.ocr)) boxes else null
                    val aspect = readerPaperAspect(pageBoxes?.width, pageBoxes?.height, paperAspect)
                    val citation = if (aspectOk && boxes != null && located is com.coach.desktop.reader.Located.Found)
                        CitedPdfLocation(boxes, located) else null
                    Box(Modifier.fillMaxSize().background(paper)) {
                        Column(Modifier.fillMaxSize().verticalScroll(imageScroll).padding(end = 12.dp).testTag("remote-pages")) {
                            OriginalBookPage(
                                "${book.title} ${book.pageLabel(ui.page)}", image, aspect, layer, page.text, null, marks,
                                citation, quoteAnchor, onResize = { originalSize = it }, onRetry = { retry++ },
                                onSelection = {},
                            )
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
            if (textView) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                V7Tooltip("上一页", shortcut = "←  PageUp") {
                    PaperButton({ turnLayout(ReaderTurn.Previous) }, enabled = paging.canPrevious,
                        modifier = Modifier.testTag("previous-page").testTag("bar-prev-page")) { Text("上一页") }
                }
                Spacer(Modifier.width(20.dp))
                if (!editingJump) Text(paging.pages.getOrNull(paging.index)?.sourceLabel ?: "第 ${ui.page} 页",
                    style = MaterialTheme.typography.bodyMedium, fontFamily = LocalV7Fonts.current.numbers,
                    modifier = Modifier.testTag("reader-page-number").clickable { editingJump = true })
                else {
                    val jumpFocus = remember { FocusRequester() }
                    fun submitJump() {
                        jump.toIntOrNull()?.takeIf { book.page(it) != null }?.let {
                            turnTo(it); editingJump = false; focusManager.clearFocus()
                        }
                    }
                    BasicTextField(jump, { jump = it.filter(Char::isDigit).take(5) }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fgPrimary, textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submitJump() }),
                        modifier = Modifier.width(80.dp).testTag("page-jump").focusRequester(jumpFocus)
                            .onFocusChanged { jumpFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) { submitJump(); true }
                                else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                    editingJump = false; focusManager.clearFocus(); true
                                } else false
                            }.background(colors.surface2).padding(9.dp))
                    LaunchedEffect(Unit) { jumpFocus.requestFocus() }
                }
                Spacer(Modifier.width(20.dp))
                V7Tooltip("下一页", shortcut = "→  PageDown") {
                    PaperButton({ turnLayout(ReaderTurn.Next) }, enabled = paging.canNext,
                        modifier = Modifier.testTag("next-page").testTag("bar-next-page")) { Text("下一页") }
                }
                // 阅读方式：翻页 / 滚动；本次打开内有效，按书记住并进阅读设置（TON-1654）。
                Spacer(Modifier.width(20.dp))
                V7Tooltip("阅读方式") {
                    PaperButton({ readMode = if (readMode == BookReadMode.Paged) BookReadMode.Scroll else BookReadMode.Paged
                        runCatching { pageFocus.requestFocus() } },
                        modifier = Modifier.testTag("read-mode")) { Text(if (readMode == BookReadMode.Paged) "翻页" else "滚动") }
                }
                // 翻页效果：本次打开内有效；记住与入口以后并进阅读设置（TON-1654）。
                Spacer(Modifier.width(20.dp))
                V7Tooltip("翻页效果") {
                    PaperButton({ turnStyle = when (turnStyle) {
                        PageTurnStyle.Flip -> PageTurnStyle.Slide
                        PageTurnStyle.Slide -> PageTurnStyle.None
                        PageTurnStyle.None -> PageTurnStyle.Flip
                    }
                        runCatching { pageFocus.requestFocus() } }, modifier = Modifier.testTag("turn-style")) {
                        Text(when (turnStyle) { PageTurnStyle.Flip -> "翻转"; PageTurnStyle.Slide -> "滑动"; PageTurnStyle.None -> "无" })
                    }
                }
            } else
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (touchPdf) 4.dp else 10.dp)) {
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
                if (!touchPdf) Text("页码", color = colors.fgSecondary, style = MaterialTheme.typography.bodySmall)
                val field = RoundedCornerShape(8.dp)
                BasicTextField(jump, { jump = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fgPrimary, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(colors.fgPrimary),
                    modifier = Modifier.width(if (touchPdf) 52.dp else 64.dp).testTag("page-jump")
                        .semantics { contentDescription = "页码" }.v7FocusRing(field).clip(field).background(colors.surface2)
                        .border(1.dp, colors.borderSubtle, field).onFocusChanged { jumpFocused = it.isFocused }
                        .padding(horizontal = 10.dp, vertical = 9.dp))
                V7TextButton({ jump.toIntOrNull()?.let { turnTo(it); focusManager.clearFocus() } },
                    enabled = !ui.busy && jump.toIntOrNull()?.let { book.page(it) != null } == true,
                    contentPadding = toolPadding) { Text("前往") }
            }
        }
    }
    if (asking) BookPassageAsk(book, paragraphPage, support,
        onRepair = { asking = false; explainingSelection = learnableSelection; explainOpen = true },
        initialSelection = learnableSelection,
        onOpenOriginal = openPassage.takeIf { learnableSelection != null },
    ) { asking = false }
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

/**
 * 这一页的「几何」是不是真量出来的：只有 pdf 的非 OCR 页给的是本页 cropBox，
 * 图片书和 OCR 页那份 1200x1600 是按经验补的常数，照它铺纸会和真图差一截。
 */
internal fun readerBoxesAreGeometry(format: String, ocr: Boolean): Boolean = format == "pdf" && !ocr

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
    BookScrollbar(scroll, modifier)
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
