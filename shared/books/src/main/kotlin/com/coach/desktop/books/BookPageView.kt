@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.FlowPreview::class)

package com.coach.desktop.books

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.coach.ui.pageturn.PageTurnState
import com.coach.ui.pageturn.ScrollReadPosition
import com.coach.ui.pageturn.ScrollReadState
import com.coach.ui.pageturn.ScrollReadView
import com.coach.ui.pageturn.PageTurnStyle
import com.coach.ui.pageturn.PageTurnView
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import coach.design.tokens.CoachV7
import com.coach.ui.LocalV7TextScale
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.LocalV7ReducedMotion
import com.coach.ui.v7.v7FocusRing
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val CROP_GAP = "coach.book.crop-gap"
private const val PAGE_BODY = "coach.book.page-body"
private const val SOURCE_OFFSET = "coach.book.source-offset"
internal val BookPageSources = SemanticsPropertyKey<List<BookPageSlice>>("BookPageSources")

/** Compose inserts a separator between selectable nodes; that separator is not source text. */
internal fun originalPagedBookSelection(selected: AnnotatedString): String {
    val original = originalBookSelection(selected)
    val ranges = selected.getStringAnnotations(PAGE_BODY, 0, selected.length)
    return if (ranges.isEmpty()) original else buildString {
        val gaps = selected.getStringAnnotations(CROP_GAP, 0, selected.length)
        for ((index, range) in ranges.sortedBy { it.start }.withIndex()) {
            if (index > 0) {
                gaps.firstOrNull { it.start == range.start }?.let { append(it.item) }
            }
            append(original, range.start, range.end)
        }
    }
}

private class PageClipboard(private val delegate: Clipboard) : Clipboard by delegate {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val selected = bookClipboardSelection(clipEntry)
        val clean = selected?.let(::originalPagedBookSelection)
        if (clean != null) delegate.setClipEntry(bookClipboardText(clean))
        else delegate.setClipEntry(clipEntry)
    }
}

/** SelectionContainer clips annotations along with the highlight, even for reversed drags. */
private fun Textbook.highlightedPassage(parts: List<AnnotatedString>): SourceBlock? {
    val selected = buildAnnotatedString { parts.forEach { append(it) } }
    val offsets = selected.getStringAnnotations(SOURCE_OFFSET, 0, selected.length)
    val first = offsets.firstOrNull()?.item ?: return null
    val last = offsets.last().item
    val pageNumber = first.substringBefore(':').toInt()
    // The note format has one source page. Never silently quote a prefix of a selection.
    if (last.substringBefore(':').toInt() != pageNumber) return null
    val start = first.substringAfter(':').toInt()
    val end = last.substringAfter(':').toInt() + 1
    val source = page(pageNumber)?.text ?: return null
    val quote = originalPagedBookSelection(selected)
    if (end - start != quote.length || !source.startsWith(quote, start)) return null
    return SourceBlock("${id}:p$pageNumber:$start", pageNumber, start, end, false, quote)
}

/** 阅读方式：翻页读 / 滚动读。 */
internal enum class BookReadMode { Paged, Scroll }

internal data class BookPaginationInputs(
    val pages: List<BookPage>, val width: Int, val height: Int, val typography: BookPageTypography,
    val tableRows: Map<BookIllustration, BookTableRows>,
    val density: Density, val direction: LayoutDirection, val fonts: FontFamily.Resolver, val pdf: Boolean,
    val immersive: Boolean = false,
    val tocNodes: List<BookTocNode> = emptyList(),
    val openerExtraHeightPx: Int = 0,
)

@Stable
internal class BookPagingState(initial: BookPageAnchor) {
    var anchor by mutableStateOf(initial)
    var pages by mutableStateOf(emptyList<BookLaidPage>())
    // Keep the last completed layout while the reader is showing the original PDF.
    var completedPagination: Pair<BookPaginationInputs, List<BookLaidPage>>? = null
    var columns by mutableIntStateOf(1)
    var spread: Boolean
        get() = columns > 1
        set(value) {
            if (value && columns < 2) columns = 2
            else if (!value) columns = 1
        }
    /** 当前这组多栏的第一页下标，和排好的页表一起记着；读者点右侧栏时锚点落在对应页，但这一组不跟着移位。 */
    var groupFirst = -1
    var groupPages: List<BookLaidPage>? = null
    /** 不含高亮覆盖层的基础排版列表，供多栏成组与 state.index 在高亮变化时保持同一布局身份。 */
    internal var layoutPages: List<BookLaidPage>? = null
    var publishedImportPage = initial.importPage
        private set
    private val pendingImportPages = mutableListOf<Int>()
    fun publishImportPage(number: Int): Boolean {
        if (publishedImportPage == number) return false
        publishedImportPage = number
        pendingImportPages += number
        return true
    }
    fun followImportPage(number: Int) {
        val acknowledged = pendingImportPages.indexOf(number)
        if (acknowledged >= 0) {
            // Saves are serialized, but their UI acknowledgements can arrive
            // after another local turn. They must not rewind its UTF-16 anchor.
            pendingImportPages.subList(0, acknowledged + 1).clear()
        } else if (number != publishedImportPage) {
            anchor = BookPageAnchor(number)
            publishedImportPage = number
            pendingImportPages.clear()
        }
    }
    /** 当前显示的第一页。多栏时锚点在这组任一可见栏里都算这一组。 */
    fun firstFor(
        laid: List<BookLaidPage>,
        visibleColumns: Int,
        layoutIdentity: List<BookLaidPage> = layoutPages ?: laid,
    ): Int {
        val p = laid.pageContaining(anchor)
        val g = groupFirst
        return if (visibleColumns > 1 && groupPages === layoutIdentity && p in g until (g + visibleColumns)) g else p
    }
    fun firstFor(laid: List<BookLaidPage>, twoPages: Boolean): Int = firstFor(laid, if (twoPages) 2 else 1)
    val index get() = firstFor(pages, columns)
    /** 滚动读时登记：能不能还往上/往下滚。底栏「上一页/下一页」据此启用，不再按整页下标判断。 */
    var scrollEdges by mutableStateOf<Pair<() -> Boolean, () -> Boolean>?>(null)
    val canPrevious get() = scrollEdges?.first?.invoke() ?: (index > 0)
    val canNext get() = scrollEdges?.second?.invoke() ?: (index + columns < pages.size)
    /** 滚动读时由视图登记：上一页/下一页键改成往前/往后滚一屏（留 10% 重叠）；翻页读时为 null。 */
    var screenScroller: ((forward: Boolean) -> Unit)? = null
    fun move(turn: ReaderTurn): BookPageAnchor? {
        if (pages.isEmpty()) return null
        val scroller = screenScroller
        if (scroller != null && (turn == ReaderTurn.Next || turn == ReaderTurn.Previous)) {
            scroller(turn == ReaderTurn.Next)
            return anchor
        }
        val current = index
        val target = when (turn) {
            ReaderTurn.Previous -> (current - columns).coerceAtLeast(0)
            ReaderTurn.Next -> if (canNext) current + columns else current
            ReaderTurn.First -> pages.indexOfFirst { it.chapter == pages[current].chapter }
            ReaderTurn.Last -> pages.indexOfLast { it.chapter == pages[current].chapter }
        }
        // 没有实际翻页（到了边界，或首/末页键落在当前页）：保留读者明确定位过的那一句，不要换成页首。
        if (target == current) return anchor
        anchor = pages[target].anchor
        return anchor
    }
}

/** 长书先从当前章首排到所在位置之后这么多导入页，整本排完再无缝替换（TON-1724）；无章标题的超大单章上限 64 页。 */
private const val NEAR_LAYOUT_PAGES = 2
private const val MAX_NEAR_LAYOUT_PAGES = 64

/** The same source anchor survives font/width reflow and original/text view changes. */
@Composable
internal fun BookPageView(
    book: Textbook, state: BookPagingState, highlights: Map<Int, List<IntRange>>,
    onTurn: (ReaderTurn) -> Unit, modifier: Modifier = Modifier, mode: BookPageMode = BookPageMode.Auto,
    libraryDirectory: Path? = null, tableRows: Map<BookIllustration, BookTableRows> = emptyMap(), onPosition: (Int) -> Unit = {},
    onSpot: (BookPageAnchor) -> Unit = {},
    /** 翻页效果。None 沿用原来的淡入换页；Flip/Slide 走共用的 PageTurnView（TON-1671）。 */
    turnStyle: PageTurnStyle = PageTurnStyle.None,
    /** 翻页读 / 滚动读（TON-1671）。两种共用同一套分页和锚点，切换时停在同一页。 */
    readMode: BookReadMode = BookReadMode.Paged,
    onOpenTableRow: ((BookPassage) -> Unit)? = null,
    onSelectText: ((SourceBlock?) -> Unit)? = null,
    hardPoints: List<Pair<SectionHardPoint, SourceBlock>> = emptyList(),
    onHardPointClick: ((SectionHardPoint, SourceBlock) -> Unit)? = null,
    immersive: Boolean = false,
    /**
     * 滚动等布局按导读开关预留页边栏。Windows 翻页读按每张纸上的实际标记留边。
     */
    hardPointGutter: Boolean = false,
) {
    var preview by remember(book.id, libraryDirectory) { mutableStateOf<BookFigurePreview?>(null) }
    preview?.let { BookFigureDialog(it) { preview = null } }
    val density = LocalDensity.current
    val fontResolver = LocalFontFamilyResolver.current
    val layoutDirection = LocalLayoutDirection.current
    val colors = CoachTheme.colors
    val scale = LocalV7TextScale.current.value
    val fonts = LocalV7Fonts.current
    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = CoachV7.Size.body * scale,
        lineHeight = CoachV7.Size.body * (CoachV7.LineHeight.body * scale), fontFeatureSettings = "'chws'")
    val typography = BookPageTypography(body,
        MaterialTheme.typography.headlineLarge.toSpanStyle().copy(fontFamily = fonts.display),
        MaterialTheme.typography.titleLarge.toSpanStyle().copy(fontFamily = fonts.display),
        MaterialTheme.typography.headlineLarge.lineHeight, MaterialTheme.typography.titleLarge.lineHeight,
        with(density) { 12.dp.toSp() }, with(density) { 32.dp.toSp() },
        if (colors.dark) CoachV7.Dark.hl else CoachV7.Light.hl,
        body.copy(fontFamily = fonts.display, fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
            fontSize = 48.sp * scale, letterSpacing = (-.03).em),
        readingFont = LocalReadingFont.current)
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val reduced = LocalV7ReducedMotion.current
    val clipboard = LocalClipboard.current
    val pageClipboard = remember(clipboard) { PageClipboard(clipboard) }
    val latestOnSelectText by rememberUpdatedState(onSelectText)
    val windowsPaged = immersive && readMode == BookReadMode.Paged &&
        System.getProperty("os.name").orEmpty().startsWith("Windows")
    // Reflowed PDF already has chapter and page navigation in the reader HUD.
    val topMargin = when {
        windowsPaged && book.format == "pdf" -> 0.dp
        immersive -> 8.dp
        else -> 52.dp
    }
    val openerTopMargin = if (immersive) minOf(2.dp, topMargin) else topMargin
    val openerExtraHeightPx = if (immersive) with(density) { (topMargin - openerTopMargin).roundToPx() } else 0
    val topMarginPx = with(density) { topMargin.toPx() }
    val openerTopMarginPx = with(density) { openerTopMargin.toPx() }
    val linePitchPx = if (windowsPaged) remember(measurer, body) {
        val sample = measurer.measure("Mg\nMg", body)
        (sample.lastBaseline - sample.firstBaseline).roundToInt().coerceAtLeast(1)
    } else null
    val paperBg = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
    var pointerActive by remember(state) { mutableStateOf(false) }
    BookPageViewport(
        modifier
            .fillMaxSize()
            .then(if (immersive) Modifier.background(paperBg) else Modifier)
            .pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.any { it.pressed }
                        if (pointerActive != pressed) pointerActive = pressed
                    }
                }
            }
            .testTag("book-page-layout"),
        linePitchPx, topMargin,
    ) { maxWidth, maxHeight, viewportWidth ->
        val column = with(density) { (body.fontSize * (if (immersive) 38 else 33)).toDp() }
        val scrolling = readMode == BookReadMode.Scroll
        val visibleColumns = when {
            scrolling -> 1
            immersive -> bookImmersiveColumns(viewportWidth.value, column.value, mode, state.columns)
            bookSpread(maxWidth.value, column.value, mode, state.spread) -> 2
            else -> 1
        }
        val spread = visibleColumns > 1
        val sheetWidth = when {
            immersive -> maxWidth / visibleColumns
            !spread -> minOf(maxWidth, column + 128.dp)
            mode == BookPageMode.Spread -> minOf((maxWidth - 48.dp) / 2, column + 112.dp)
            else -> minOf(maxWidth / 2, column + 112.dp)
        }
        val leftInset = if (immersive) minOf(4.dp, sheetWidth * 0.008f) else 64.dp
        // Facing columns share a gutter. Keep the full outer margin for corner drags.
        val rightInset = when {
            immersive && !windowsPaged && hardPointGutter -> 48.dp
            windowsPaged && spread -> minOf(2.dp, leftInset)
            immersive -> leftInset
            spread -> 48.dp
            else -> 64.dp
        }
        val widthPx = with(density) { (sheetWidth - leftInset - rightInset).roundToPx().coerceAtLeast(1) }
        val bodyHeight = with(density) { (maxHeight - if (immersive) topMargin else 116.dp).roundToPx().coerceAtLeast(1) }
        val failed = remember(book.id, libraryDirectory) { mutableStateListOf<BookIllustration>() }
        val layoutPages = remember(book.pages, libraryDirectory, failed.toList()) {
            if (libraryDirectory != null && failed.isEmpty()) book.pages else book.pages.map { page ->
                page.copy(illustrations = page.illustrations.filter { libraryDirectory != null && it !in failed })
            }
        }
        val pdf = book.format == "pdf"
        // The ordinary short-book path fits in one frame and needs no loading screen.
        val backgroundLayout = remember(layoutPages) {
            layoutPages.size > 64 || layoutPages.sumOf { it.illustrations.size } > 64 ||
                layoutPages.sumOf { it.text.length.toLong() } > 32_768
        }
        val tocForLayout = if (pdf) emptyList() else book.tocNodes
        val inputs = BookPaginationInputs(layoutPages, widthPx, bodyHeight, typography, tableRows,
            density, layoutDirection, fontResolver, pdf, immersive, tocForLayout, openerExtraHeightPx)
        val basePages = if (backgroundLayout) {
            var result by remember(state, inputs) {
                mutableStateOf(state.completedPagination?.takeIf { it.first == inputs }?.second)
            }
            LaunchedEffect(state, inputs) {
                if (result != null) return@LaunchedEffect
                // 从当前章起始导入页排到所在位置之后几页，确保章内分页边界与整本排版一致，整本排完后零跳页替换。
                val window = withContext(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    nearChapterLayoutPages(
                        pages = layoutPages,
                        anchor = state.anchor,
                        tailPages = NEAR_LAYOUT_PAGES * (if (windowsPaged) visibleColumns else 1),
                        maxPages = MAX_NEAR_LAYOUT_PAGES,
                        pdf = pdf,
                        tocNodes = tocForLayout,
                    ) { context.ensureActive() }
                }
                if (window.pages.isNotEmpty() && window.pages.size < layoutPages.size) {
                    result = withContext(Dispatchers.Default) {
                        val context = currentCoroutineContext()
                        val workerMeasurer = TextMeasurer(fontResolver, density, layoutDirection, cacheSize = 128)
                        BookPageLayouter(workerMeasurer, density, typography, pdf, immersive)
                            .layout(
                                window.pages, widthPx, bodyHeight, emptyMap(), tableRows, tocForLayout,
                                forceAuthoritativeTocHeadings = window.authoritativeTocHeadings,
                                openerExtraHeightPx = openerExtraHeightPx,
                            ) { context.ensureActive() }
                    }
                }
                // Measure with a worker-owned cache. Closing or switching views cancels this pagination.
                val laidOut = withContext(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    val workerMeasurer = TextMeasurer(fontResolver, density, layoutDirection, cacheSize = 128)
                    BookPageLayouter(workerMeasurer, density, typography, pdf, immersive)
                        .layout(
                            layoutPages, widthPx, bodyHeight, emptyMap(), tableRows, tocForLayout,
                            openerExtraHeightPx = openerExtraHeightPx,
                        ) { context.ensureActive() }
                }
                state.completedPagination = inputs to laidOut
                snapshotFlow { !pointerActive }.first { it }
                result = laidOut
            }
            result
        } else remember(inputs) {
            BookPageLayouter(measurer, density, typography, pdf, immersive)
                .layout(layoutPages, widthPx, bodyHeight, emptyMap(), tableRows, tocForLayout, openerExtraHeightPx = openerExtraHeightPx)
        }
        val pages = remember(basePages, highlights, typography.highlight) {
            basePages?.applyBookHighlights(highlights, typography.highlight)
        }
        // 读位只在读者自己动了锚点（翻页、滚动、点选、目录、出处）时写；重排（字号、宽度、字体、开合）
        // 只按锚点找页、不改锚点也不写，且提在加载态提前返回之前，避免重排取消待写入的读位。
        val spotSaved = remember(state) { arrayOf(state.anchor) }
        val latestOnSpot by rememberUpdatedState(onSpot)
        LaunchedEffect(state) {
            snapshotFlow { state.anchor }.distinctUntilChanged().drop(1).debounce(300).collect { a ->
                if (a.visualOrder == null) {
                    spotSaved[0] = a
                    withContext(Dispatchers.IO) { latestOnSpot(a) }
                }
            }
        }
        DisposableEffect(state) {
            onDispose {
                state.anchor.takeIf { it != spotSaved[0] && it.visualOrder == null }?.let(latestOnSpot)
            }
        }
        if (basePages == null || pages == null) {
            Text("正在排版…", Modifier.align(Alignment.Center).testTag("book-page-loading"), color = colors.fgSecondary)
            return@BookPageViewport
        }
        SideEffect {
            if (state.layoutPages !== basePages) state.layoutPages = basePages
            if (state.pages !== pages) state.pages = pages
            if (state.spread != spread) state.spread = spread
            if (state.columns != visibleColumns) state.columns = visibleColumns
        }
        if (pages.isEmpty()) {
            Text("这一页没有可提取文字，请切换到原页阅读。", Modifier.align(Alignment.Center))
            return@BookPageViewport
        }
        val first = state.firstFor(basePages, visibleColumns, layoutIdentity = basePages)
        SideEffect { state.groupFirst = first; state.groupPages = basePages }
        LaunchedEffect(basePages, first, state.anchor) {
            val activeImportPage = state.anchor.importPage.takeIf { book.page(it) != null }
                ?: pages[first].anchor.importPage
            onPosition(activeImportPage)
        }
        if (scrolling) {
            val itemHeightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
            val gapPx = with(density) { 24.dp.roundToPx() }
            val lineAt = itemHeightPx / 3 // 阅读行：屏幕上方 1/3 处（desktop-polish 6.9）
            /** 阅读行的原文锚点 -> 视口顶边应在的位置，使该行落在屏幕上方 1/3 处。 */
            fun positionOf(anchor: BookPageAnchor): ScrollReadPosition {
                val idx = pages.pageContaining(anchor)
                val y = pages[idx].yOfAnchor(anchor, measurer, typography, widthPx) ?: 0
                val top = y - lineAt
                return when {
                    top >= 0 -> ScrollReadPosition(idx, (top.toFloat() / itemHeightPx).coerceIn(0f, 1f))
                    idx > 0 && top < -gapPx -> ScrollReadPosition(idx - 1, ((itemHeightPx + gapPx + top).toFloat() / itemHeightPx).coerceIn(0f, 1f))
                    else -> ScrollReadPosition(idx, 0f)
                }
            }
            /** 视口顶边位置 -> 屏幕上方 1/3 处那一行的原文锚点。 */
            fun anchorOf(pos: ScrollReadPosition): BookPageAnchor {
                val total = (pos.fraction * itemHeightPx).toInt() + lineAt
                val (idx, y) = when {
                    pos.page + 1 >= pages.size -> pos.page to total.coerceAtMost(itemHeightPx)
                    total >= itemHeightPx + gapPx -> pos.page + 1 to (total - itemHeightPx - gapPx)
                    total >= itemHeightPx -> pos.page + 1 to 0
                    else -> pos.page to total
                }
                return pages[idx.coerceIn(0, pages.lastIndex)].anchorAtY(y, measurer, typography, widthPx)
            }
            val scrollState = remember(basePages) {
                val at = positionOf(state.anchor)
                ScrollReadState(pages.size, at, (at.fraction * itemHeightPx).toInt())
            }
            val screenScope = rememberCoroutineScope()
            DisposableEffect(scrollState) {
                state.screenScroller = { forward -> screenScope.launch { scrollState.scrollScreen(forward) } }
                state.scrollEdges = { scrollState.canScrollBackward } to { scrollState.canScrollForward }
                onDispose { state.screenScroller = null; state.scrollEdges = null }
            }
            // 用户滚动：把视口顶边那一行的原文位置写回锚点（不只是页首），切回翻页时能还原到这一行。
            // 进入时的位置是程序放的，不回写锚点：没动过就切回去，锚点要和切之前完全相等
            val programmatic = remember(scrollState) { arrayOf<ScrollReadPosition?>(positionOf(state.anchor)) }
            val written = remember(scrollState) { arrayOfNulls<BookPageAnchor>(1) }
            LaunchedEffect(scrollState, basePages) {
                snapshotFlow { scrollState.laidOut }.first { it }
                snapshotFlow { scrollState.position }.collect { pos ->
                    val set = programmatic[0]
                    // 程序刚跳过去的位置不回写，其余（用户滚动、惯性、语义滚动）都回写
                    if (set != null && set.page == pos.page && kotlin.math.abs(set.fraction - pos.fraction) * itemHeightPx <= 80) return@collect
                    programmatic[0] = null
                    val next = anchorOf(pos)
                    written[0] = next
                    state.anchor = next
                }
            }
            // 键盘 / 目录 / 出处等从外面改了锚点：滚过去；和当前位置差不到两行就不动，避免来回抖。
            LaunchedEffect(scrollState, state.anchor) {
                if (state.anchor == written[0]) return@LaunchedEffect // 是滚动自己写的，不用再滚回去
                snapshotFlow { scrollState.laidOut }.first { it }
                val target = positionOf(state.anchor)
                val now = scrollState.position
                val moved = target.page != now.page || kotlin.math.abs(target.fraction - now.fraction) * itemHeightPx > 80
                if (moved) { withFrameNanos { }; programmatic[0] = target; scrollState.scrollTo(target) }
            }
            val viewHeight = maxHeight
            val paper = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
            val desk = if (immersive) paper else if (colors.dark) CoachV7.Dark.desk else CoachV7.Light.desk
            val scrollInsetPx = with(density) { leftInset.toPx() }
            CompositionLocalProvider(LocalClipboard provides pageClipboard) {
                SelectionContainer {
                    ScrollReadView(scrollState, desk = desk, paper = Color.Transparent, gap = if (immersive) 8.dp else 24.dp, handleKeys = false,
                        modifier = Modifier.fillMaxSize().testTag("book-scroll-read"),
                        scrollbar = { BookReadScrollbar(it) }) { index ->
                        val page = pages[index]
                        val rendered = remember(page) { mutableMapOf<Int, TextLayoutResult>() }
                        val downPos = remember(scrollState) { arrayOf<ScrollReadPosition?>(null) }
                        val scrollSpot = rememberUpdatedState { g: ReadingPointerGesture ->
                            val cb = latestOnSelectText ?: return@rememberUpdatedState
                            val moved = downPos[0] != null && downPos[0] != scrollState.position
                            when {
                                !moved && (g.longPressed || (!g.isTouch && g.dragged)) ->
                                    cb(page.selectionForGesture(book, g, scrollInsetPx, if (page.opener) openerTopMarginPx else topMarginPx, rendered))
                                !g.dragged && !g.longPressed -> cb(null)
                            }
                        }
                        val recordDown = rememberUpdatedState { downPos[0] = scrollState.position }
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.width(sheetWidth).height(viewHeight).background(paper).clipToBounds()
                                .readingSpot(scrollSpot, onDown = recordDown).testTag("book-sheet-0")) {
                                val remaining = if (immersive) null else pages.count { it.chapter == page.chapter } -
                                    pages.take(index + 1).count { it.chapter == page.chapter }
                                BookSheet(book, page, typography, measurer, widthPx, leftInset, remaining, false, 0,
                                    libraryDirectory, onOpenTableRow, { preview = it }, hardPoints, onHardPointClick,
                                    topMargin = if (page.opener) openerTopMargin else topMargin, rendered = rendered) { failed.add(it) }
                            }
                        }
                    }
                }
            }
            return@BookPageViewport
        }
        // Keep pagination independent of asynchronously arriving guide annotations. Only a
        // sheet containing an anchored marker is fitted with a margin, retaining its lines.
        val sheetWidthPx = with(density) { sheetWidth.roundToPx().coerceAtLeast(1) }
        val markerMarginPx = with(density) { 48.dp.roundToPx() }
        var lastIdlePoints by remember(book.id) { mutableStateOf(hardPoints) }
        val displayedPoints = if (pointerActive) lastIdlePoints else hardPoints
        SideEffect { if (!pointerActive) lastIdlePoints = hardPoints }
        fun markerMargin(page: BookLaidPage?): Int {
            if (!windowsPaged || page == null || onHardPointClick == null) return 0
            val marked = displayedPoints.any { (_, block) ->
                page.runs.any { it.offsetOfHardPoint(block) != null }
            }
            return if (marked) markerMarginPx else 0
        }
        fun sheetScale(index: Int) = sheetWidthPx.toFloat() / (sheetWidthPx + markerMargin(pages.getOrNull(index)))
        val selection = remember(book.id) { SelectionState() }
        val paper = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
        val drawSheet: @Composable (Int, Int, Modifier, State<(Long) -> Boolean>?) -> Unit = { index, side, sheetModifier, ignoreDown ->
            val page = pages.getOrNull(index)
            val extraPx = markerMargin(page)
            val isLast = spread && side == visibleColumns - 1
            val inset = if (isLast) rightInset + with(density) { extraPx.toDp() } else leftInset
            val rendered = remember(page) { mutableMapOf<Int, TextLayoutResult>() }
            val scale = sheetWidthPx.toFloat() / (sheetWidthPx + extraPx)
            val sheetSpot = rememberUpdatedState { g: ReadingPointerGesture ->
                state.anchor = spotAnchor(pages, index, spread, side, g.down, g.up, g.dragged,
                    sheetWidthPx.toFloat(), measurer, typography, widthPx, ::sheetScale) ?: state.anchor
                val cb = latestOnSelectText ?: return@rememberUpdatedState
                when {
                    windowsPaged && !g.isTouch && g.dragged -> cb(book.highlightedPassage(selection.selectedTexts))
                    g.longPressed || (!g.isTouch && g.dragged) -> {
                        val local = if (extraPx == 0) g else g.copy(down = g.down / scale, up = g.up / scale)
                        cb(page?.selectionForGesture(book, local, with(density) { inset.toPx() },
                            if (page.opener) openerTopMarginPx else topMarginPx, rendered))
                    }
                    !g.dragged -> cb(null)
                }
            }
            Box(sheetModifier.background(paper).clipToBounds().readingSpot(sheetSpot, ignoreDown)
                .testTag("book-sheet-$side")) {
                if (page != null) {
                    val remaining = if (immersive) null else pages.count { it.chapter == page.chapter } -
                        pages.take(index + 1).count { it.chapter == page.chapter }
                    Box(Modifier.fillMaxSize().fitMarkerMargin(extraPx)) {
                        BookSheet(book, page, typography, measurer, widthPx, inset, remaining, spread, side,
                            libraryDirectory, onOpenTableRow, { preview = it }, displayedPoints, onHardPointClick,
                            topMargin = if (page.opener) openerTopMargin else topMargin,
                            lastColumn = isLast, recordSourceOffsets = windowsPaged, rendered = rendered) { failed.add(it) }
                    }
                }
            }
        }
        val fade = remember { Animatable(1f) }
        LaunchedEffect(first, basePages) { fade.snapTo(if (reduced) 1f else 0f); fade.animateTo(1f, tween(if (reduced) 0 else 150)) }
        var wheelAt by remember { mutableLongStateOf(0L) }
        val turn by rememberUpdatedState(onTurn)
        // 滚轮翻页：和桌面 onPointerEvent(Scroll) 同一个事件阶段，安卓也能编译。
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }
                    val now = System.nanoTime()
                    if (delta != 0.0 && now - wheelAt > 220_000_000L) {
                        wheelAt = now; turn(if (delta > 0) ReaderTurn.Next else ReaderTurn.Previous)
                    }
                    event.changes.forEach { it.consume() }
                }
            }
        }, contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalClipboard provides pageClipboard) {
                SelectionContainer(selection) {
                    if (turnStyle == PageTurnStyle.None) {
                        Row(Modifier.fillMaxHeight().graphicsLayer { alpha = fade.value }) {
                            repeat(visibleColumns) { side ->
                                drawSheet(first + side, side, Modifier.width(sheetWidth).fillMaxHeight(), null)
                            }
                        }
                    } else {
                        val turnState = remember(basePages) { PageTurnState(pages.size, first, alignToSpread = false) }
                        // 锚点（目录跳转、键盘、重排）变了：相邻一步翻过去，远的快翻，不动画的直接换
                        LaunchedEffect(turnState, first, visibleColumns) {
                            if (turnState.position != first) {
                                val d = first - turnState.position
                                if (turnState.turning || reduced) turnState.snapTo(first)
                                else if (kotlin.math.abs(d) == visibleColumns) turnState.turn(d > 0)
                                else turnState.jumpTo(first)
                            }
                        }
                        // 拖页角翻过去、触控板横扫：位置先变，回头同步成锚点
                        LaunchedEffect(turnState.position) {
                            val at = turnState.position
                            if (at != first && !turnState.turning) onTurn(if (at > first) ReaderTurn.Next else ReaderTurn.Previous)
                        }
                        val ignoreTurnGesture = rememberUpdatedState { downUptimeMs: Long ->
                            turnState.lastHandledPointerDownUptimeMs == downUptimeMs
                        }
                        val turnMod = if (immersive) Modifier.fillMaxSize() else Modifier.width(sheetWidth * visibleColumns).fillMaxHeight()
                        PageTurnView(turnState, paper = paper, style = turnStyle, columns = visibleColumns, handleKeys = false,
                            handleMouseDrag = false,
                            canMouseTurnAt = { pos ->
                                isNonTextTurnTarget(
                                    pos, pages, turnState.shownPosition, visibleColumns, spread,
                                    with(density) { sheetWidth.toPx() },
                                    with(density) { leftInset.toPx() },
                                    with(density) { rightInset.toPx() },
                                    widthPx, topMarginPx, openerTopMarginPx, with(density) { 4.dp.toPx() },
                                    measurer, typography, ::markerMargin,
                                )
                            },
                            modifier = turnMod.testTag("book-page-turn"),
                            shadow = if (colors.dark) Color.Black else CoachV7.Light.ink) { index ->
                            val side = if (spread) Math.floorMod(index - turnState.shownPosition, visibleColumns) else 0
                            drawSheet(index, side, Modifier.fillMaxSize(), ignoreTurnGesture)
                        }
                    }
                }
            }
            if (spread && turnStyle == PageTurnStyle.None) Box(Modifier.width(48.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(
                Color.Transparent, (if (colors.dark) Color.Black.copy(alpha = .24f) else CoachV7.Light.ink.copy(alpha = .06f)), Color.Transparent))))
        }
    }
}

private fun Modifier.fitMarkerMargin(extraPx: Int): Modifier = if (extraPx == 0) this else layout { measurable, bounds ->
    val width = bounds.maxWidth + extraPx
    val scale = bounds.maxWidth.coerceAtLeast(1).toFloat() / width
    val sheet = measurable.measure(Constraints.fixed(width, bounds.maxHeight))
    layout(bounds.maxWidth, bounds.maxHeight) {
        sheet.placeWithLayer(0, 0) {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = scale
            scaleY = scale
        }
    }
}

/** Fit whole baselines under the header and above the progress line. Keep the physical
 * viewport width for column breakpoints and scale the glyphs and pointer targets together. */
@Composable
private fun BookPageViewport(
    modifier: Modifier,
    linePitchPx: Int?,
    topMargin: Dp,
    content: @Composable BoxScope.(width: Dp, height: Dp, viewportWidth: Dp) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val reservedPx = with(density) { topMargin.roundToPx() + 2.dp.roundToPx() }
        val rows = if (linePitchPx != null) (constraints.maxHeight - reservedPx) / linePitchPx else 0
        if (linePitchPx == null || rows < 8 || !constraints.hasBoundedHeight || !constraints.hasBoundedWidth) {
            content(maxWidth, maxHeight, maxWidth)
            return@BoxWithConstraints
        }
        val gridHeight = rows * linePitchPx + reservedPx
        val zoom = constraints.maxHeight.toFloat() / gridHeight
        val gridWidth = (constraints.maxWidth / zoom).roundToInt().coerceAtLeast(1)
        val viewportWidth = maxWidth
        Box(Modifier.layout { measurable, bounds ->
            val page = measurable.measure(Constraints.fixed(gridWidth, gridHeight))
            layout(bounds.maxWidth, bounds.maxHeight) {
                page.placeWithLayer(0, 0) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = zoom
                    scaleY = zoom
                }
            }
        }) {
            with(density) { content(gridWidth.toDp(), gridHeight.toDp(), viewportWidth) }
        }
    }
}

private data class ReadingPointerGesture(
    val down: Offset,
    val up: Offset,
    val dragged: Boolean,
    val longPressed: Boolean,
    val isTouch: Boolean,
)

private fun BookLaidPage.selectionForGesture(
    book: Textbook,
    gesture: ReadingPointerGesture,
    insetPx: Float,
    topMarginPx: Float,
    rendered: Map<Int, TextLayoutResult>,
): SourceBlock? = when {
    gesture.isTouch -> if (gesture.longPressed) {
        if (gesture.dragged) {
            selectionBetween(book, gesture.down, gesture.up, insetPx, topMarginPx, rendered)
                ?: selectionAt(book, gesture.down, insetPx, topMarginPx, rendered)
        } else {
            selectionAt(book, gesture.down, insetPx, topMarginPx, rendered)
        }
    } else null
    gesture.dragged -> selectionBetween(book, gesture.down, gesture.up, insetPx, topMarginPx, rendered)
    else -> null
}

/**
 * 版心里的阅读信号：点一下、鼠标拖选文字，或触屏长按选字。图、按钮、翻页手势消费掉的不算点。
 */
private fun Modifier.readingSpot(
    onSpot: State<(ReadingPointerGesture) -> Unit>,
    ignoreDown: State<(Long) -> Boolean>? = null,
    onDown: State<() -> Unit>? = null,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Final)
        onDown?.value?.invoke()
        var last = down.position
        var consumedUp = false
        val isTouch = down.type == PointerType.Touch
        val longPressMs = viewConfiguration.longPressTimeoutMillis
        val slop = viewConfiguration.touchSlop
        var movedBeforeLongPress = false
        var longPressed = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            last = change.position
            val elapsed = change.uptimeMillis - down.uptimeMillis
            val dist = (last - down.position).getDistance()
            if (!longPressed && !movedBeforeLongPress) {
                if (elapsed >= longPressMs) longPressed = true
                else if (dist > slop) movedBeforeLongPress = true
            }
            if (!change.pressed) {
                consumedUp = change.isConsumed
                break
            }
        }
        if (ignoreDown?.value?.invoke(down.uptimeMillis) == true) return@awaitEachGesture
        val dragged = (last - down.position).getDistance() > slop
        if (longPressed || dragged || !consumedUp) {
            onSpot.value(ReadingPointerGesture(down.position, last, dragged, longPressed, isTouch))
        }
    }
}

/**
 * 阅读信号 -> 原文锚点。点：点中那一行。拖选：选区第一个字所在那一行；左页拖到右页取左页按下点，
 * 右页往回拖到左页取左页抬起点；同页内取更靠上的那一端。
 */
internal fun spotAnchor(pages: List<BookLaidPage>, index: Int, multiColumn: Boolean, side: Int, down: Offset, up: Offset,
    dragged: Boolean, sheetWidthPx: Float, measurer: TextMeasurer, typography: BookPageTypography, widthPx: Int,
    sheetScale: (Int) -> Float = { 1f }): BookPageAnchor? {
    fun at(i: Int, y: Float) = pages.getOrNull(i)?.anchorAtY((y / sheetScale(i)).toInt().coerceAtLeast(0), measurer, typography, widthPx)
    if (!dragged) return at(index, down.y)
    if (!multiColumn) return at(index, minOf(down.y, up.y))
    val deltaCols = kotlin.math.floor(up.x / sheetWidthPx).toInt()
    return when {
        deltaCols > 0 -> at(index, down.y)
        deltaCols < 0 && side + deltaCols >= 0 -> at(index + deltaCols, up.y)
        else -> at(index, minOf(down.y, up.y))
    }
}

@Composable
private fun BoxScope.BookSheet(book: Textbook, page: BookLaidPage, typography: BookPageTypography,
    measurer: TextMeasurer, widthPx: Int, inset: Dp, remaining: Int?, spread: Boolean, side: Int,
    libraryDirectory: Path?, onOpenTableRow: ((BookPassage) -> Unit)?,
    onExpand: (BookFigurePreview) -> Unit,
    hardPoints: List<Pair<SectionHardPoint, SourceBlock>> = emptyList(),
    onHardPointClick: ((SectionHardPoint, SourceBlock) -> Unit)? = null,
    topMargin: Dp = 52.dp,
    lastColumn: Boolean = spread && side == 1,
    recordSourceOffsets: Boolean = false,
    rendered: MutableMap<Int, TextLayoutResult>,
    onMissing: (BookIllustration) -> Unit) {
    val density = LocalDensity.current
    val colors = CoachTheme.colors
    val column = with(density) { widthPx.toDp() }
    val runningTitle = remember(book, page) { BookBoundaries.runningHeaderTitle(book, page) }
    val compactHeader = topMargin < 44.dp
    DisableSelection { if (topMargin > 0.dp && !page.opener && (spread && side == 0 || runningTitle.isNotEmpty())) Text(if (spread && side == 0) book.title else runningTitle,
        Modifier.align(Alignment.TopStart).padding(start = inset, top = if (compactHeader) 0.dp else 20.dp).width(column),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = colors.fgTertiary,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = if (compactHeader) 9.5.sp else CoachV7.Size.cap,
            lineHeight = if (compactHeader) 10.sp else MaterialTheme.typography.labelSmall.lineHeight,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, letterSpacing = CoachV7.Tracking.cap), maxLines = 1) }
    val bodyTag = when (side) {
        0 -> "reader-text"
        1 -> "reader-text-right"
        else -> "reader-text-col-$side"
    }
    for ((index, run) in page.runs.withIndex()) {
        val top = with(density) { run.topPx.toDp() }
        val layout = remember(run, widthPx, typography) { measurer.measure(run.text, typography.body,
            placeholders = run.placeholders, constraints = Constraints(maxWidth = widthPx)) }
        val inline = remember(run.placeholders) { run.placeholders.associate { it.tag to InlineTextContent(it.item) {} } }
        val selectable = remember(run, recordSourceOffsets) { buildAnnotatedString {
            append(run.text)
            addStringAnnotation(PAGE_BODY, "", 0, length)
            if (recordSourceOffsets) {
                var display = 0
                for (slice in run.sources) {
                    for (offset in slice.startUtf16 until slice.endUtf16) {
                        addStringAnnotation(SOURCE_OFFSET, "${slice.importPage}:$offset", display, display + 1)
                        display++
                    }
                }
            }
            if (run.precedingCropText.isNotEmpty()) addStringAnnotation(CROP_GAP, run.precedingCropText, 0, 1)
        } }
        if (index == 0 && page.chapterNumber != null) DisableSelection {
            val number = bookChapterDisplayNumber(page.chapterNumber)
            val baseline = measurer.measure(number, typography.number).firstBaseline
            val numberTop = (topMargin + top + with(density) { (layout.firstBaseline - baseline).toDp() }).coerceAtLeast(0.dp)
            Text(number, Modifier.padding(start = inset, top = numberTop),
                style = typography.number, color = colors.fgSecondary)
        }
        Text(selectable, style = typography.body, inlineContent = inline,
            onTextLayout = { rendered[run.topPx] = it },
            modifier = Modifier.padding(start = inset, top = topMargin + top).width(column)
                .semantics {
                    this[BookPageSources] = run.sources
                    // Expose original heading/citation ranges, as the reader did before
                    // pagination; the rendering-only base typography is not a source mark.
                    text = AnnotatedString(originalBookSelection(run.text),
                        run.text.spanStyles.filterNot { it.item == typography.body.toSpanStyle() }, run.text.paragraphStyles)
                }.testTag(if (index == 0) bodyTag else "$bodyTag-$index"))
        var offset = 0
        for ((i, source) in run.sources.withIndex()) {
            if (i > 0 || (index > 0 && page.runs[index - 1].sources.last().importPage != source.importPage)) {
                val y = with(density) { layout.getLineTop(layout.getLineForOffset(offset.coerceAtMost(run.text.lastIndex))).toDp() }
                DisableSelection { Text(source.importPage.toString(), Modifier.padding(start = if (spread && side == 1) inset + column + 10.dp else 12.dp,
                    top = topMargin + top + y).width(36.dp), color = colors.fgSecondary,
                    style = MaterialTheme.typography.labelSmall, fontFamily = LocalV7Fonts.current.numbers) }
            }
            offset += source.endUtf16 - source.startUtf16
        }
    }
    if (hardPoints.isNotEmpty() && onHardPointClick != null) DisableSelection {
        var lastMarkerTopPx = -1000f
        val minGapPx = with(density) { 28.dp.toPx() }
        // 多栏时最后一栏的宽边距在左（起始边距用 rightInset），其余栏的宽边距在右。
        val markerX = if (lastColumn) 10.dp else inset + column + 8.dp
        val pillShape = RoundedCornerShape(CoachV7.Radius.s)
        val pillBg = if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash
        for (run in page.runs) {
            val runLayout = remember(run, widthPx, typography) {
                measurer.measure(run.text, typography.body, placeholders = run.placeholders, constraints = Constraints(maxWidth = widthPx))
            }
            for ((hp, block) in hardPoints) {
                val matchedDisplayOffset = run.offsetOfHardPoint(block) ?: continue
                val safeOffset = matchedDisplayOffset.coerceIn(0, (run.text.length - 1).coerceAtLeast(0))
                val line = runLayout.getLineForOffset(safeOffset)
                var lineTopPx = run.topPx + runLayout.getLineTop(line)
                if (lineTopPx < lastMarkerTopPx + minGapPx) {
                    lineTopPx = lastMarkerTopPx + minGapPx
                }
                lastMarkerTopPx = lineTopPx
                val topDp = topMargin + with(density) { lineTopPx.toDp() }
                Box(
                    Modifier
                        .padding(start = markerX, top = topDp)
                        .clip(pillShape)
                        .background(pillBg)
                        .border(1.dp, v7Pine().copy(alpha = 0.35f), pillShape)
                        .v7FocusRing(pillShape)
                        .clickable(role = Role.Button, onClickLabel = "查看难点讲解：${hp.label}") {
                            onHardPointClick(hp, block)
                        }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("hard-point-marker-${hp.blockId}"),
                ) {
                    Text("难点", style = MaterialTheme.typography.labelSmall, color = v7Pine(), maxLines = 1)
                }
            }
        }
    }
    for (block in page.visuals) key(book.id, block.item, block.band?.startY) {
        val width = with(density) { block.widthPx.toDp() }
        val left = inset + (column - width) / 2
        val y = topMargin + with(density) { block.topPx.toDp() }
        val item = block.item
        var bitmap by remember(libraryDirectory) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(libraryDirectory, item) {
            if (libraryDirectory != null) {
                bitmap = withContext(Dispatchers.IO) {
                    try {
                        decodeBookBitmap(BookIllustrationAssets.imageFile(libraryDirectory, book.id, item).toFile())
                            ?.takeIf { block.band == null || (it.width == item.width && it.height == item.height) }?.asBookImageBitmap()
                    } catch (_: java.io.IOException) { null }
                }
                if (bitmap == null) onMissing(item)
            }
        }
        DisableSelection {
            Column(Modifier.offset(x = left, y = y).width(width).testTag("book-block-${item.page}-${item.order}")) {
                val height = with(density) { block.heightPx.toDp() }
                val shape = RoundedCornerShape(CoachV7.Radius.s)
                val backing = when {
                    !colors.dark -> Modifier
                    item.kind == PageVisualKind.ILLUSTRATION -> Modifier.clip(shape).background(CoachV7.Light.pgPaper).padding(8.dp)
                    else -> Modifier.clip(shape).background(CoachV7.Light.pgPaper)
                }
                val mark = if (block.highlighted) Modifier.border(2.dp, typography.highlight, shape) else Modifier
                val loaded = bitmap
                val expand = if (item.kind == PageVisualKind.ILLUSTRATION && loaded != null)
                    Modifier.testTag("book-figure-${item.page}-${item.order}").clickable(role = Role.Button, onClickLabel = "放大插图") {
                        onExpand(BookFigurePreview(loaded, item))
                    } else Modifier
                Box(Modifier.fillMaxWidth().height(height).then(expand).then(mark).then(backing), contentAlignment = Alignment.Center) {
                    bitmap?.let { image ->
                        val band = block.band
                        if (band == null) Image(image, item.caption ?: "第 ${item.page} 页插图",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else Canvas(Modifier.fillMaxSize().semantics { contentDescription = "第 ${item.page} 页表格" }) {
                            val w = size.width.toInt()
                            val h = size.height.toInt()
                            val headerHeight = band.drawnHeaderHeight(h)
                            if (w > 0 && h > headerHeight) {
                                if (headerHeight > 0) drawImage(image, srcSize = IntSize(image.width, band.headerBottom),
                                    dstSize = IntSize(w, headerHeight))
                                drawImage(image, srcOffset = IntOffset(0, band.startY), srcSize = IntSize(image.width, band.endY - band.startY),
                                    dstOffset = IntOffset(0, headerHeight), dstSize = IntSize(w, h - headerHeight))
                            }
                        }
                        if (band != null && onOpenTableRow != null) TableRowLinks(book, item, band, onOpenTableRow)
                    }
                }
                item.caption?.takeIf { it.isNotEmpty() && item.kind == PageVisualKind.ILLUSTRATION }?.let { caption ->
                    Spacer(Modifier.height(with(density) { block.captionGapPx.toDp() }))
                    Text(caption, Modifier.fillMaxWidth(), style = block.captionStyle, color = colors.fgSecondary,
                        textAlign = if (block.singleLineCaption) TextAlign.Center else TextAlign.Start)
                }
            }
        }
    }
    if (remaining != null) DisableSelection { Row(Modifier.align(Alignment.BottomCenter)
        .padding(horizontal = 20.dp, vertical = if (topMargin < 44.dp) 10.dp else 20.dp)
        .fillMaxWidth()
        .testTag("book-sheet-footer"),
        horizontalArrangement = Arrangement.SpaceBetween) {
        val rest = if (remaining == 0) "本章完" else "本章还剩 $remaining 页"
        if (spread && side == 1) {
            Text(rest, color = colors.fgTertiary, style = MaterialTheme.typography.labelSmall)
            Text(page.sourceLabel, color = colors.fgSecondary, style = MaterialTheme.typography.labelSmall, fontFamily = LocalV7Fonts.current.numbers)
        } else {
            Text(page.sourceLabel, color = colors.fgSecondary, style = MaterialTheme.typography.labelSmall, fontFamily = LocalV7Fonts.current.numbers)
            Text(rest, color = colors.fgTertiary, style = MaterialTheme.typography.labelSmall)
        }
    } }
}

private fun BookTableBand.drawnHeaderHeight(height: Int): Int =
    (height.toLong() * headerBottom / sourceHeight).toInt()

/** The canvas draws the header and the cropped body into two separately scaled rectangles. */
@Composable
private fun TableRowLinks(book: Textbook, item: BookIllustration, band: BookTableBand, onOpen: (BookPassage) -> Unit) {
    val table = item.table ?: return
    val page = remember(book, item.page) { book.page(item.page) } ?: return
    if (!remember(table, item.height, page.text) { table.valid(item.height, page.text) }) return
    val rows = table.rows
    val precedingRow = rows.indexOfFirst { it.bottomPx == band.startY }
    val lastRow = rows.indexOfFirst { it.bottomPx == band.endY }
    if (rows.first().bottomPx != band.headerBottom || precedingRow < 0 || lastRow <= precedingRow) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = constraints.maxHeight
        val headerHeight = band.drawnHeaderHeight(height)
        Column {
            var top = 0
            for (index in 0..lastRow) {
                if (index in 1..precedingRow) continue
                val row = rows[index]
                val bottom = if (index == 0) headerHeight else headerHeight +
                    ((row.bottomPx - band.startY).toLong() * (height - headerHeight) / (band.endY - band.startY)).toInt()
                if (bottom > top) {
                    val label = if (index == 0) "查看原页表头" else "查看原页第 ${index} 行数据"
                    Spacer(Modifier.fillMaxWidth().height(with(LocalDensity.current) { (bottom - top).toDp() })
                        .clickable(role = Role.Button) {
                            val (start, end) = row.source
                            onOpen(BookPassage(book.id, page.number, start, end, page.text.substring(start, end)))
                        }.semantics { contentDescription = label })
                }
                top = bottom
            }
        }
    }
}

private fun BookTextRun.offsetOfHardPoint(block: SourceBlock): Int? {
    var offset = 0
    for (slice in sources) {
        if (slice.importPage == block.page && block.start in slice.startUtf16 until slice.endUtf16) {
            return offset + block.start - slice.startUtf16
        }
        offset += slice.endUtf16 - slice.startUtf16
    }
    return null
}

private fun isNonTextTurnTarget(
    pos: Offset,
    pages: List<BookLaidPage>,
    shownPosition: Int,
    visibleColumns: Int,
    spread: Boolean,
    sheetWidthPx: Float,
    leftInsetPx: Float,
    rightInsetPx: Float,
    widthPx: Int,
    topMarginPx: Float,
    openerTopMarginPx: Float,
    hitSlopPx: Float,
    measurer: TextMeasurer,
    typography: BookPageTypography,
    markerMargin: (BookLaidPage) -> Int,
): Boolean {
    val col = if (visibleColumns <= 1 || sheetWidthPx <= 0f) 0
        else (pos.x / sheetWidthPx).toInt().coerceIn(0, visibleColumns - 1)
    val laidPage = pages.getOrNull(shownPosition + col) ?: return true
    if (laidPage.runs.isEmpty()) return true
    val extraPx = markerMargin(laidPage)
    val scale = sheetWidthPx / (sheetWidthPx + extraPx)
    val insetPx = if (spread && col == visibleColumns - 1) rightInsetPx + extraPx else leftInsetPx
    val localX = (pos.x - col * sheetWidthPx) / scale
    if (localX !in (insetPx - hitSlopPx)..(insetPx + widthPx + hitSlopPx)) return true
    val pageTopMarginPx = if (laidPage.opener) openerTopMarginPx else topMarginPx
    val firstTop = pageTopMarginPx + laidPage.runs.first().topPx
    val lastRun = laidPage.runs.last()
    val lastLayout = measurer.measure(
        lastRun.text, typography.body,
        placeholders = lastRun.placeholders,
        constraints = Constraints(maxWidth = widthPx),
    )
    val lastBottom = pageTopMarginPx + lastRun.topPx + lastLayout.size.height
    return (pos.y / scale) !in (firstTop - hitSlopPx)..(lastBottom + hitSlopPx)
}

private fun List<BookLaidPage>.applyBookHighlights(
    highlights: Map<Int, List<IntRange>>,
    highlightColor: Color,
): List<BookLaidPage> {
    if (highlights.isEmpty() || highlights.values.all { it.isEmpty() }) return this
    var changed = false
    val updated = map { laid ->
        val hasRunMatch = laid.runs.any { run ->
            run.sources.any { !highlights[it.importPage].isNullOrEmpty() }
        }
        val hasVisualMatch = laid.visuals.any { !highlights[it.item.page].isNullOrEmpty() }
        if (!hasRunMatch && !hasVisualMatch) return@map laid

        var pageChanged = false
        val newRuns = if (!hasRunMatch) laid.runs else laid.runs.map { run ->
            val annotated = highlightRunText(run.text, run.sources, highlights, highlightColor)
            if (annotated === run.text) run else {
                pageChanged = true
                run.copy(text = annotated)
            }
        }
        val newVisuals = if (!hasVisualMatch) laid.visuals else laid.visuals.map { visual ->
            val pageHighlights = highlights[visual.item.page].orEmpty()
            val marked = if (visual.replacement != null) {
                val r = visual.replacement
                pageHighlights.any { !it.isEmpty() && it.first < r.endUtf16 && it.last >= r.startUtf16 }
            } else {
                val isFirstOrNotSplit = visual.band == null || visual.band.startY == visual.band.headerBottom
                isFirstOrNotSplit && pageHighlights.any { !it.isEmpty() && visual.item.anchorOffset in it }
            }
            if (marked == visual.highlighted) visual else {
                pageChanged = true
                visual.copy(highlighted = marked)
            }
        }
        if (!pageChanged) laid else {
            changed = true
            val newText = if (laid.runs.size == 1 && laid.text === laid.runs[0].text) {
                newRuns[0].text
            } else if (hasRunMatch && laid.sources.sumOf { it.endUtf16 - it.startUtf16 } == laid.text.length) {
                highlightRunText(laid.text, laid.sources, highlights, highlightColor)
            } else laid.text
            laid.copy(text = newText, runs = newRuns, visuals = newVisuals)
        }
    }
    return if (changed) updated else this
}

private fun highlightRunText(
    text: AnnotatedString,
    sources: List<BookPageSlice>,
    highlights: Map<Int, List<IntRange>>,
    highlightColor: Color,
): AnnotatedString {
    val rangesToAdd = mutableListOf<Pair<Int, Int>>()
    var displayOffset = 0
    for (slice in sources) {
        val sliceLen = slice.endUtf16 - slice.startUtf16
        val marks = highlights[slice.importPage]
        if (!marks.isNullOrEmpty()) {
            for (range in marks) {
                if (range.isEmpty()) continue
                val overlapStart = maxOf(slice.startUtf16, range.first)
                val overlapEnd = minOf(slice.endUtf16, range.last + 1)
                if (overlapStart < overlapEnd) {
                    val startInRun = (displayOffset + overlapStart - slice.startUtf16).coerceIn(0, text.length)
                    val endInRun = (displayOffset + overlapEnd - slice.startUtf16).coerceIn(0, text.length)
                    if (startInRun < endInRun) rangesToAdd += startInRun to endInRun
                }
            }
        }
        displayOffset += sliceLen
    }
    if (rangesToAdd.isEmpty()) return text
    return buildAnnotatedString {
        append(text)
        val style = SpanStyle(background = highlightColor)
        for ((start, end) in rangesToAdd) addStyle(style, start, end)
    }
}
