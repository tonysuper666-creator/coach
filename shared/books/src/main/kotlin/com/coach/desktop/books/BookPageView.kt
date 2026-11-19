@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CROP_GAP = "coach.book.crop-gap"
private const val PAGE_BODY = "coach.book.page-body"
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
        if (selected != null) delegate.setClipEntry(bookClipboardText(originalPagedBookSelection(selected)))
        else delegate.setClipEntry(clipEntry)
    }
}

/** 阅读方式：翻页读 / 滚动读。 */
internal enum class BookReadMode { Paged, Scroll }

@Stable
internal class BookPagingState(initial: BookPageAnchor) {
    var anchor by mutableStateOf(initial)
    var pages by mutableStateOf(emptyList<BookLaidPage>())
    var spread by mutableStateOf(false)
    /** 当前这组对开的左页下标，和排好的页表一起记着；读者点右页时锚点落在右页，但这一组对开不跟着移位。 */
    var groupFirst = -1
    var groupPages: List<BookLaidPage>? = null
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
    /** 当前显示的第一页。双页时锚点在这组的左页或右页都算这一组。 */
    fun firstFor(laid: List<BookLaidPage>, twoPages: Boolean): Int {
        val p = laid.pageContaining(anchor)
        val g = groupFirst
        return if (twoPages && groupPages === laid && (g == p || g == p - 1)) g else p
    }
    val index get() = firstFor(pages, spread)
    /** 滚动读时登记：能不能还往上/往下滚。底栏「上一页/下一页」据此启用，不再按整页下标判断。 */
    var scrollEdges by mutableStateOf<Pair<() -> Boolean, () -> Boolean>?>(null)
    val canPrevious get() = scrollEdges?.first?.invoke() ?: (index > 0)
    val canNext get() = scrollEdges?.second?.invoke() ?: (index + (if (spread) 2 else 1) < pages.size)
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
            ReaderTurn.Previous -> (current - if (spread) 2 else 1).coerceAtLeast(0)
            ReaderTurn.Next -> if (canNext) current + if (spread) 2 else 1 else current
            ReaderTurn.First -> pages.indexOfFirst { it.chapter == pages[current].chapter }
            ReaderTurn.Last -> pages.indexOfLast { it.chapter == pages[current].chapter }
        }
        // 没有实际翻页（到了边界，或首/末页键落在当前页）：保留读者明确定位过的那一句，不要换成页首，
        // 否则折叠屏换宽度重排时，那一句会落到下一个子页（TON-1724）。
        if (target == current) return anchor
        anchor = pages[target].anchor
        return anchor
    }
}

/** 超过这么多字的书，分页放后台线程做（TON-1724）。 */
private const val BACKGROUND_LAYOUT_CHARS = 60_000
/** 长书先排所在位置前后各这么多导入页，整本排完再换（TON-1724）。 */
private const val NEAR_LAYOUT_PAGES = 2

/** The same source anchor survives font/width reflow and original/text view changes. */
@Composable
internal fun BookPageView(
    book: Textbook, state: BookPagingState, highlights: Map<Int, List<IntRange>>,
    onTurn: (ReaderTurn) -> Unit, modifier: Modifier = Modifier, mode: BookPageMode = BookPageMode.Auto,
    libraryDirectory: Path? = null, tableRows: Map<BookIllustration, BookTableRows> = emptyMap(), onPosition: (Int) -> Unit = {}, onSpot: (BookPageAnchor) -> Unit = {},
    /** 翻页效果。None 沿用原来的淡入换页；Flip/Slide 走共用的 PageTurnView（TON-1671）。 */
    turnStyle: PageTurnStyle = PageTurnStyle.None,
    /** 翻页读 / 滚动读（TON-1671）。两种共用同一套分页和锚点，切换时停在同一页。 */
    readMode: BookReadMode = BookReadMode.Paged,
    onOpenTableRow: ((BookPassage) -> Unit)? = null,
) {
    var preview by remember(book.id, libraryDirectory) { mutableStateOf<BookFigurePreview?>(null) }
    preview?.let { BookFigureDialog(it) { preview = null } }
    val density = LocalDensity.current
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
    BoxWithConstraints(modifier.fillMaxSize().testTag("book-page-layout")) {
        val column = with(density) { (body.fontSize * 33).toDp() }
        val scrolling = readMode == BookReadMode.Scroll
        val spread = !scrolling && bookSpread(maxWidth.value, column.value, mode, state.spread)
        val sheetWidth = when {
            !spread -> minOf(maxWidth, column + 128.dp)
            mode == BookPageMode.Spread -> minOf((maxWidth - 48.dp) / 2, column + 112.dp)
            else -> minOf(maxWidth / 2, column + 112.dp)
        }
        val leftInset = 64.dp
        val rightInset = if (spread) 48.dp else 64.dp
        val widthPx = with(density) { (sheetWidth - leftInset - rightInset).roundToPx().coerceAtLeast(1) }
        val bodyHeight = with(density) { (maxHeight - 116.dp).roundToPx().coerceAtLeast(1) }
        val failed = remember(book.id, libraryDirectory) { mutableStateListOf<BookIllustration>() }
        val layoutPages = remember(book.pages, libraryDirectory, failed.toList()) {
            if (libraryDirectory != null && failed.isEmpty()) book.pages else book.pages.map { page ->
                page.copy(illustrations = page.illustrations.filter { libraryDirectory != null && it !in failed })
            }
        }
        // 分页要测量整本书的文字，长书首开在主线程做会超过 5 秒被判 ANR（TON-1724）。
        // 长书放到后台线程，用独立的 TextMeasurer（Compose 的测量缓存不保证线程安全）；
        // 重新分页期间保留上一份结果，首次还没算完时只显示「正在排版」。短书仍同步排，行为不变。
        val isPdf = book.format == "pdf"
        val longBook = remember(layoutPages) { layoutPages.sumOf { it.text.length } > BACKGROUND_LAYOUT_CHARS }
        val fontResolver = LocalFontFamilyResolver.current
        val layoutDirection = LocalLayoutDirection.current
        val shortPages = if (longBook) null else remember(layoutPages, widthPx, bodyHeight, typography, highlights, density, tableRows) {
            BookPageLayouter(measurer, density, typography, isPdf).layout(layoutPages, widthPx, bodyHeight, highlights, tableRows)
        }
        val longPages by produceState<List<BookLaidPage>?>(null, longBook, layoutPages, widthPx, bodyHeight, typography, highlights, density, tableRows) {
            if (longBook) value = withContext(Dispatchers.Default) {
                val layouter = BookPageLayouter(TextMeasurer(fontResolver, density, layoutDirection, 128), density, typography, isPdf)
                // 先只排读者所在位置前后几章页，马上有字可读；整本排完后再换上（锚点按原文位置对齐，读位不动）。
                val at = state.anchor.importPage
                val near = layoutPages.filter { it.number in (at - NEAR_LAYOUT_PAGES)..(at + NEAR_LAYOUT_PAGES) }
                if (near.isNotEmpty() && near.size < layoutPages.size)
                    value = layouter.layout(near, widthPx, bodyHeight, highlights, tableRows)
                layouter.layout(layoutPages, widthPx, bodyHeight, highlights, tableRows)
            }
        }
        val pages = if (longBook) longPages else shortPages
        if (pages == null) {
            Text("正在排版…", Modifier.align(Alignment.Center).testTag("book-page-laying-out"))
            return@BoxWithConstraints
        }
        SideEffect { if (state.pages !== pages) state.pages = pages; if (state.spread != spread) state.spread = spread }
        if (pages.isEmpty()) {
            Text("这一页没有可提取文字，请切换到原页阅读。", Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        val first = state.firstFor(pages, spread)
        SideEffect { state.groupFirst = first; state.groupPages = pages }
        LaunchedEffect(pages, first, state.anchor) {
            onPosition(pages[first].anchor.importPage)
            // 读者定位过的那一句就在当前页上就存它，冷启回到同一句；否则存页首。
            val exact = state.anchor.takeIf { it.visualOrder == null && pages.pageContaining(it) == first }
            (exact ?: pages[first].anchor.takeIf { it.visualOrder == null })?.let(onSpot)
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
            val scrollState = remember(pages) {
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
            LaunchedEffect(scrollState, pages) {
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
                if (moved) { programmatic[0] = target; scrollState.scrollTo(target) }
            }
            val viewHeight = maxHeight
            val paper = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
            val desk = if (colors.dark) CoachV7.Dark.desk else CoachV7.Light.desk
            CompositionLocalProvider(LocalClipboard provides pageClipboard) {
                SelectionContainer {
                    ScrollReadView(scrollState, desk = desk, paper = Color.Transparent, gap = 24.dp, handleKeys = false,
                        modifier = Modifier.fillMaxSize().testTag("book-scroll-read"),
                        scrollbar = { BookReadScrollbar(it) }) { index ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.width(sheetWidth).height(viewHeight).background(paper).clipToBounds().testTag("book-sheet-0")) {
                                val page = pages[index]
                                val remaining = pages.count { it.chapter == page.chapter } - pages.take(index + 1).count { it.chapter == page.chapter }
                                BookSheet(book, page, typography, measurer, widthPx, leftInset, remaining, false, 0,
                                    libraryDirectory, onOpenTableRow, { preview = it }) { failed.add(it) }
                            }
                        }
                    }
                }
            }
            return@BoxWithConstraints
        }
        val fade = remember { Animatable(1f) }
        LaunchedEffect(first, pages) { fade.snapTo(if (reduced) 1f else 0f); fade.animateTo(1f, tween(if (reduced) 0 else 150)) }
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
                SelectionContainer {
                    if (turnStyle == PageTurnStyle.None) {
                    Row(Modifier.fillMaxHeight().graphicsLayer { alpha = fade.value }) {
                            val visible = if (spread) 2 else 1
                            repeat(visible) { side ->
                                val page = pages.getOrNull(first + side)
                                Box(Modifier.width(sheetWidth).fillMaxHeight()
                                    .background(if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper)
                                    .clipToBounds().readingSpot { d, u, dragged ->
                                        state.anchor = spotAnchor(pages, first + side, spread, side, d, u, dragged, with(density) { sheetWidth.toPx() }, measurer, typography, widthPx) ?: state.anchor
                                    }
                                    .testTag("book-sheet-$side")) {
                                    if (page != null) {
                                        val remaining = pages.count { it.chapter == page.chapter } - pages.take(first + side + 1).count { it.chapter == page.chapter }
                                        BookSheet(book, page, typography, measurer, widthPx,
                                            if (spread && side == 1) rightInset else leftInset,
                                            remaining, spread, side, libraryDirectory, onOpenTableRow, { preview = it }) { failed.add(it) }
                                    }
                                }
                            }
                        }
    
                    } else {
                        val visible = if (spread) 2 else 1
                        val turnState = remember(pages) { PageTurnState(pages.size, first, alignToSpread = false) }
                        // 锚点（目录跳转、键盘、重排）变了：相邻一步翻过去，远的快翻，不动画的直接换
                        LaunchedEffect(turnState, first, spread) {
                            if (turnState.position != first) {
                                val d = first - turnState.position
                                if (turnState.turning || reduced) turnState.snapTo(first)
                                else if (kotlin.math.abs(d) == visible) turnState.turn(d > 0)
                                else turnState.jumpTo(first)
                            }
                        }
                        // 拖页角翻过去、触控板横扫：位置先变，回头同步成锚点
                        LaunchedEffect(turnState.position) {
                            val at = turnState.position
                            if (at != first && !turnState.turning) onTurn(if (at > first) ReaderTurn.Next else ReaderTurn.Previous)
                        }
                        val paper = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
                        PageTurnView(turnState, paper = paper, style = turnStyle, columns = visible, handleKeys = false,
                            modifier = Modifier.width(sheetWidth * visible).fillMaxHeight().testTag("book-page-turn"),
                            shadow = if (colors.dark) Color.Black else CoachV7.Light.ink) { index ->
                            val side = if (spread) Math.floorMod(index - turnState.shownPosition, 2) else 0
                            val page = pages[index]
                            Box(Modifier.width(sheetWidth).fillMaxHeight().background(paper).clipToBounds()
                                .readingSpot { d, u, dragged ->
                                    state.anchor = spotAnchor(pages, index, spread, side, d, u, dragged, with(density) { sheetWidth.toPx() }, measurer, typography, widthPx) ?: state.anchor
                                }
                                .testTag("book-sheet-$side")) {
                                val remaining = pages.count { it.chapter == page.chapter } - pages.take(index + 1).count { it.chapter == page.chapter }
                                BookSheet(book, page, typography, measurer, widthPx,
                                    if (spread && side == 1) rightInset else leftInset,
                                    remaining, spread, side, libraryDirectory, onOpenTableRow, { preview = it }) { failed.add(it) }
                            }
                        }
                    }
                }
            }
            if (spread && turnStyle == PageTurnStyle.None) Box(Modifier.width(48.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(
                Color.Transparent, (if (colors.dark) Color.Black.copy(alpha = .24f) else CoachV7.Light.ink.copy(alpha = .06f)), Color.Transparent))))
        }
    }
}

/**
 * 版心里的阅读信号：点一下，或拖选文字。点（没有拖动）：点中的那一行。拖选：选区第一个字所在的那一行。
 * 回调拿到按下点、抬起点（本页坐标，抬起点可能越过本页宽度到了另一页）。图、按钮这类自己消费了点击的不算点。
 */
private fun Modifier.readingSpot(onSpot: (down: Offset, up: Offset, dragged: Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Final)
        var last = down.position
        var consumedUp = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            last = change.position
            if (!change.pressed) { consumedUp = change.isConsumed; break }
        }
        val dragged = (last - down.position).getDistance() > viewConfiguration.touchSlop
        if (dragged || !consumedUp) onSpot(down.position, last, dragged)
    }
}

/**
 * 阅读信号 -> 原文锚点。点：点中那一行。拖选：选区第一个字所在那一行；左页拖到右页取左页按下点，
 * 右页往回拖到左页取左页抬起点；同页内取更靠上的那一端。
 */
private fun spotAnchor(pages: List<BookLaidPage>, index: Int, twoPages: Boolean, side: Int, down: Offset, up: Offset,
    dragged: Boolean, sheetWidthPx: Float, measurer: TextMeasurer, typography: BookPageTypography, widthPx: Int): BookPageAnchor? {
    fun at(i: Int, y: Float) = pages.getOrNull(i)?.anchorAtY(y.toInt().coerceAtLeast(0), measurer, typography, widthPx)
    if (!dragged) return at(index, down.y)
    val rightOfOwn = twoPages && side == 0 && up.x > sheetWidthPx
    val leftOfOwn = twoPages && side == 1 && up.x < 0f
    return when {
        rightOfOwn -> at(index, down.y)
        leftOfOwn -> at(index - 1, up.y)
        else -> at(index, minOf(down.y, up.y))
    }
}

@Composable
private fun BoxScope.BookSheet(book: Textbook, page: BookLaidPage, typography: BookPageTypography,
    measurer: TextMeasurer, widthPx: Int, inset: Dp, remaining: Int, spread: Boolean, side: Int,
    libraryDirectory: Path?, onOpenTableRow: ((BookPassage) -> Unit)?,
    onExpand: (BookFigurePreview) -> Unit, onMissing: (BookIllustration) -> Unit) {
    val density = LocalDensity.current
    val colors = CoachTheme.colors
    val column = with(density) { widthPx.toDp() }
    DisableSelection { if (!page.opener) Text(if (spread && side == 0) book.title else page.title.orEmpty(),
        Modifier.align(Alignment.TopStart).padding(start = inset, top = 20.dp).width(column),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = colors.fgTertiary,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = CoachV7.Size.cap,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, letterSpacing = CoachV7.Tracking.cap), maxLines = 1) }
    val bodyTag = if (side == 0) "reader-text" else "reader-text-right"
    for ((index, run) in page.runs.withIndex()) {
        val top = with(density) { run.topPx.toDp() }
        val layout = remember(run, widthPx, typography) { measurer.measure(run.text, typography.body,
            placeholders = run.placeholders, constraints = Constraints(maxWidth = widthPx)) }
        val inline = remember(run.placeholders) { run.placeholders.associate { it.tag to InlineTextContent(it.item) {} } }
        val selectable = remember(run) { buildAnnotatedString {
            append(run.text)
            addStringAnnotation(PAGE_BODY, "", 0, length)
            if (run.precedingCropText.isNotEmpty()) addStringAnnotation(CROP_GAP, run.precedingCropText, 0, 1)
        } }
        if (index == 0 && page.chapterNumber != null) DisableSelection {
            val number = bookChapterDisplayNumber(page.chapterNumber)
            val baseline = measurer.measure(number, typography.number).firstBaseline
            Text(number, Modifier.padding(start = inset, top = 52.dp + top +
                with(density) { (layout.firstBaseline - baseline).toDp() }),
                style = typography.number, color = colors.fgSecondary)
        }
        Text(selectable, style = typography.body, inlineContent = inline,
            modifier = Modifier.padding(start = inset, top = 52.dp + top).width(column)
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
                    top = 52.dp + top + y).width(36.dp), color = colors.fgSecondary,
                    style = MaterialTheme.typography.labelSmall, fontFamily = LocalV7Fonts.current.numbers) }
            }
            offset += source.endUtf16 - source.startUtf16
        }
    }
    for (block in page.visuals) key(book.id, block.item, block.band?.startY) {
        val width = with(density) { block.widthPx.toDp() }
        val left = inset + (column - width) / 2
        val y = 52.dp + with(density) { block.topPx.toDp() }
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
    DisableSelection { Row(Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 20.dp).fillMaxWidth(),
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
