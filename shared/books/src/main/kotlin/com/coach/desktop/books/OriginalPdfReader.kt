package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.desktop.reader.Located
import com.coach.domain.reader.ReaderTextRange
import com.coach.domain.reader.ReaderTextUnit
import com.coach.ui.pageturn.PageTurnState
import com.coach.ui.pageturn.PageTurnStyle
import com.coach.ui.pageturn.PageTurnView
import com.coach.ui.pageturn.ScrollReadPosition
import com.coach.ui.pageturn.ScrollReadState
import com.coach.ui.pageturn.ScrollReadView
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.paperShadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val FallbackPdfAspect = 0.7071f
private const val TouchPdfRasterLimit = 3072

internal data class PdfSelection(val page: Int, val range: ReaderTextRange, val top: Float)

internal data class PdfDestination(val page: Int, val fraction: Float = 0f, val reveal: Boolean = false, val revision: Int = 0)

internal data class PdfRasterStatus(val aspect: Float?, val failed: Boolean)

@Stable
internal class OriginalPdfReading(initialPage: Int, initiallyScrolling: Boolean) {
    var scrolling by mutableStateOf(initiallyScrolling)
    var page by mutableIntStateOf(initialPage)
        private set
    var destination by mutableStateOf(PdfDestination(initialPage))
        private set
    var selection by mutableStateOf<PdfSelection?>(null)
    var sheets by mutableStateOf<List<List<Int>>>(emptyList())
    var activeScroll: ScrollReadState? = null
    var viewportPosition by mutableStateOf(PdfDestination(initialPage))
    val rasters = mutableStateMapOf<Int, PdfRasterStatus>()

    fun focus(number: Int) { page = number }

    fun jump(number: Int, fraction: Float = 0f, reveal: Boolean = false) {
        focus(number)
        destination = PdfDestination(number, fraction, reveal, destination.revision + 1)
    }

    fun switchMode() {
        val selected = selection
        val anchor = if (selected != null) PdfDestination(selected.page, selected.top, reveal = true)
            else viewportPosition.takeIf { it.page == page } ?: PdfDestination(page)
        scrolling = !scrolling
        jump(anchor.page, anchor.fraction, anchor.reveal)
    }

    fun adjacent(forward: Boolean): Int? {
        val at = sheets.indexOfFirst { page in it }
        return if (at < 0) null else sheets.getOrNull(at + if (forward) 1 else -1)?.first()
    }
}

/** Share glyph loading between the source panel and its mounted pages. */
@Stable
internal class OriginalPdfTextLayers(private val library: TextbookLibrary, private val book: Textbook) {
    private val gate = Mutex()
    private val layers = mutableStateMapOf<Int, Result<ReaderTextUnit?>>()
    private val users = mutableMapOf<Int, Int>()

    operator fun get(number: Int): Result<ReaderTextUnit?>? = layers[number]

    private suspend fun load(page: BookPage): Unit = gate.withLock {
        if (page.number !in layers) {
            val layer = try { Result.success(withContext(Dispatchers.IO) { library.pdfTextLayer(book, page) }) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { Result.failure(error) }
            layers[page.number] = layer
        }
    }

    suspend fun keep(page: BookPage) {
        val number = page.number
        users[number] = users.getOrDefault(number, 0) + 1
        try {
            load(page)
            awaitCancellation()
        } finally {
            val count = users.getValue(number) - 1
            if (count == 0) {
                users.remove(number)
                layers.remove(number)
            } else users[number] = count
        }
    }

    fun invalidate(number: Int) { layers.remove(number) }
}

/** Landscape pages occupy a sheet alone. Portrait pairs retain their physical order. */
internal fun originalPdfSheets(
    pages: List<Int>,
    aspects: Map<Int, Float>,
    width: Float,
    imageHeight: Float,
    immersive: Boolean = false,
): List<List<Int>> {
    val result = mutableListOf<List<Int>>()
    val perPageChrome = if (immersive) 3f else 88f
    val gutter = if (immersive) 2f else 48f
    var index = 0
    while (index < pages.size) {
        val left = pages[index]
        val right = pages.getOrNull(index + 1)
        val a = aspects[left] ?: FallbackPdfAspect
        val b = right?.let(aspects::get) ?: FallbackPdfAspect
        val spreadWidth = 2 * (maxOf(a, b) * imageHeight + perPageChrome) + gutter
        // 沉浸式常见笔记本窗口（含 1128x828）与并排伴读态：只要双页总书页面积明显大于单页且总宽 >= 700dp，就保持双页对开。
        val canPair = if (immersive) width >= 700f && width >= spreadWidth * 0.72f else width >= spreadWidth
        if (right != null && a <= 1f && b <= 1f && canPair) {
            result.add(listOf(left, right))
            index += 2
        } else {
            result.add(listOf(left))
            index++
        }
    }
    return result
}

/** 宽窗适宽连续阅读：双页竖版并排铺满宽度，横版页或窄窗单页铺满宽度。 */
internal fun originalPdfScrollSheets(
    pages: List<Int>,
    aspects: Map<Int, Float>,
    twoUp: Boolean,
): List<List<Int>> {
    if (!twoUp) return pages.map { listOf(it) }
    val result = mutableListOf<List<Int>>()
    var index = 0
    while (index < pages.size) {
        val left = pages[index]
        val right = pages.getOrNull(index + 1)
        val a = aspects[left] ?: FallbackPdfAspect
        val b = right?.let(aspects::get) ?: FallbackPdfAspect
        if (right != null && a <= 1f && b <= 1f) {
            result.add(listOf(left, right))
            index += 2
        } else {
            result.add(listOf(left))
            index++
        }
    }
    return result
}

@Composable
internal fun OriginalPdfReader(
    book: Textbook, library: TextbookLibrary, reading: OriginalPdfReading, scroll: ScrollReadState,
    layers: OriginalPdfTextLayers, citation: CitedPdfLocation?, quote: String?, highlightRevision: Int,
    turnStyle: PageTurnStyle, onPage: (Int) -> Unit, onTurn: (Int) -> Unit,
    immersive: Boolean = false,
    bitmapCache: OriginalPdfBitmapCache = remember(library, book.id) { OriginalPdfBitmapCache() },
) {
    val pageNumbers = remember(book.id, book.pages.size) { book.pages.map(BookPage::number) }
    val aspects by produceState(emptyMap<Int, Float>(), library, book.id) {
        value = try { withContext(Dispatchers.IO) { library.pdfPageAspects(book) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { emptyMap() }
    }
    val currentPage by rememberUpdatedState(onPage)
    val colors = CoachTheme.colors
    val paper = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
    val lastRenderWidth = remember(book.id) { mutableIntStateOf(0) }
    val turnByTouch: (Boolean) -> Unit = { forward ->
        val target = if (reading.scrolling) {
            val position = pageNumbers.indexOf(reading.page)
            if (position < 0) null else pageNumbers.getOrNull(position + if (forward) 1 else -1)
        } else reading.adjacent(forward)
        target?.let(onTurn)
    }

    // 吸收 TON-1463：后台预取当前页/当前对开的前后相邻页到有界 LRU 位图缓存（优先下一页再上一页）。
    val prefetchTargets = remember(pageNumbers, reading.sheets, reading.page, reading.scrolling) {
        adjacentPdfPrefetchPages(pageNumbers, reading.sheets, reading.page, reading.scrolling)
    }
    LaunchedEffect(library, book.id, prefetchTargets, lastRenderWidth.intValue) {
        val width = lastRenderWidth.intValue
        if (width <= 0 || prefetchTargets.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(40)
        for (adj in prefetchTargets) {
            val key = PdfBitmapCacheKey(book.id, adj, width)
            if (bitmapCache.get(key) != null) continue
            try {
                val rendered = withContext(Dispatchers.IO) {
                    library.renderOriginal(book, adj, width).asBookImageBitmap()
                }
                bitmapCache.put(key, rendered)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // 预取失败不影响当前页正常展示。
            }
        }
    }

    val leaf: @Composable (Int, Modifier) -> Unit = { number, modifier ->
        val page = checkNotNull(book.page(number))
        OriginalPdfLeaf(book, page, library, reading, layers, aspects[number],
            citation.takeIf { reading.page == number }, quote, highlightRevision, modifier, currentPage,
            bitmapCache = bitmapCache, onRenderWidth = { if (it > 0) lastRenderWidth.intValue = it })
    }

    if (reading.scrolling) {
        val density = LocalDensity.current
        var viewportWidthDp by remember { mutableStateOf(0.dp) }
        val twoUp = immersive && viewportWidthDp >= 1080.dp && pageNumbers.size > 1
        val scrollSheets = remember(pageNumbers, aspects, twoUp) {
            originalPdfScrollSheets(pageNumbers, aspects, twoUp)
        }
        val activeScroll = if (!twoUp) scroll else remember(book.id, scrollSheets.size) {
            val initRow = scrollSheets.indexOfFirst { reading.page in it }.coerceAtLeast(0)
            ScrollReadState(scrollSheets.size, ScrollReadPosition(initRow))
        }
        SideEffect {
            reading.activeScroll = activeScroll
            if (reading.sheets != scrollSheets) reading.sheets = scrollSheets
        }
        val target = reading.destination
        val footer = with(density) { (if (immersive) 4.dp else 48.dp).roundToPx() }
        LaunchedEffect(activeScroll, target, scrollSheets, footer) {
            val index = scrollSheets.indexOfFirst { target.page in it }.coerceAtLeast(0)
            val position = ScrollReadPosition(index, target.fraction)
            if (target.reveal || activeScroll.position.page != index || target.fraction > 0f) {
                withFrameNanos { }
                if (target.reveal) activeScroll.reveal(position, footer) else activeScroll.scrollTo(position)
            }
            var first = true
            snapshotFlow { activeScroll.position.takeUnless { activeScroll.visiblePages.isEmpty() } }.collect { position ->
                if (position == null) return@collect
                val row = scrollSheets.getOrNull(position.page) ?: return@collect
                val number = if (reading.page in row) reading.page else row.first()
                reading.viewportPosition = PdfDestination(number, position.fraction)
                // Near the end, the target can be visible below the first item.
                val selectedPage = reading.selection?.page
                val selectedRow = selectedPage?.let { sp -> scrollSheets.indexOfFirst { sp in it } } ?: -1
                val keepSelection = selectedRow >= 0 && selectedRow in activeScroll.visiblePages
                if ((!first || index !in activeScroll.visiblePages) && !keepSelection && number != reading.page) {
                    reading.selection = null
                    reading.focus(number)
                    currentPage(number)
                }
                first = false
            }
        }
        val swipe = if (originalPdfTouchEnabled) Modifier.originalPdfSwipe(onTurn = turnByTouch) else Modifier
        Box(
            Modifier.fillMaxSize()
                .background(if (immersive) paper else colors.bgCanvas)
                .onSizeChanged { viewportWidthDp = with(density) { it.width.toDp() } },
        ) {
            ScrollReadView(activeScroll, desk = if (immersive) paper else colors.bgCanvas, paper = Color.Transparent,
                modifier = Modifier.fillMaxSize().testTag("original-pdf-scroll").then(swipe),
                gap = if (immersive) 2.dp else 24.dp, handleKeys = false,
                scrollbar = { BookReadScrollbar(it) }) { index ->
                val row = scrollSheets[index]
                if (immersive) {
                    if (row.size == 2) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Box(Modifier.weight(1f).background(paper)) { leaf(row[0], Modifier.fillMaxWidth()) }
                            Box(Modifier.weight(1f).background(paper)) { leaf(row[1], Modifier.fillMaxWidth()) }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp).background(paper)) {
                            leaf(row[0], Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 1198.dp).fillMaxWidth()
                            .paperShadow("sheet", colors.dark).clip(RoundedCornerShape(CoachV7.Radius.sheet)).background(paper)) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                leaf(row[0], Modifier.padding(horizontal = minOf(64.dp, maxWidth * .10f)))
                            }
                            PdfPageFooter(book, row[0])
                        }
                    }
                }
            }
        }
    } else BoxWithConstraints(Modifier.fillMaxSize().background(if (immersive) paper else colors.bgCanvas), contentAlignment = Alignment.Center) {
        val top = if (immersive) 2.dp else minOf(52.dp, maxHeight * .12f)
        val bottom = if (immersive) 2.dp else minOf(64.dp, maxHeight * .14f)
        val imageHeight = (maxHeight - top - bottom).coerceAtLeast(1.dp)
        val sheets = remember(pageNumbers, aspects, maxWidth, imageHeight, immersive) {
            originalPdfSheets(pageNumbers, aspects, maxWidth.value, imageHeight.value, immersive = immersive)
        }
        SideEffect {
            reading.activeScroll = null
            if (reading.sheets != sheets) reading.sheets = sheets
        }
        val sheet = sheets.indexOfFirst { reading.page in it }.coerceAtLeast(0)
        val columns = sheets[sheet].size
        val maxAspect = sheets[sheet].maxOf { aspects[it] ?: FallbackPdfAspect }
        val sheetWidth = if (immersive) {
            minOf(maxWidth, (imageHeight * maxAspect + if (columns == 2) 3.dp else 4.dp) * columns)
        } else {
            minOf(maxWidth, (imageHeight * maxAspect + if (columns == 2) 88.dp else 128.dp) * columns)
        }
        val state = remember(sheets, columns) { PageTurnState(sheets.size * columns, sheet * columns) }
        LaunchedEffect(state, sheet) {
            val distance = sheet - state.position / columns
            if (kotlin.math.abs(distance) == 1 && !state.turning) state.turn(distance > 0)
            else if (distance != 0) state.snapTo(sheet * columns)
            // 等待程序触发的翻页/跳转彻底落位后再监听用户手势翻页，避免动画中途把旧页位置回写造成回弹。
            snapshotFlow { state.position / columns to state.turning }.collect { (position, turning) ->
                if (!turning && position != sheet && position in sheets.indices) {
                    val number = sheets[position].first()
                    reading.selection = null
                    reading.jump(number)
                    currentPage(number)
                }
            }
        }
        val swipe = if (originalPdfTouchEnabled) Modifier.originalPdfSwipe(
            edgeFraction = if (columns == 2) .08f else .14f,
            travelFraction = if (columns == 2) .9f else .6f,
            onTurn = turnByTouch,
        ) else Modifier
        PageTurnView(state, paper = paper,
            modifier = Modifier.width(sheetWidth).fillMaxHeight().testTag("original-pdf-paged").then(swipe),
            style = turnStyle, columns = columns, handleKeys = false, showThrough = false,
            handleTouch = !originalPdfTouchEnabled) { slot ->
            val number = sheets[slot / columns].getOrNull(slot % columns)
            if (number != null) key(number) {
                val right = columns == 2 && slot % columns == 1
                BoxWithConstraints(Modifier.fillMaxSize().testTag(if (right) "original-pdf-right" else "original-pdf-left"),
                    contentAlignment = Alignment.Center) {
                    val outside = if (immersive) 2.dp else minOf(64.dp, maxWidth * .16f)
                    val inside = if (immersive) (if (columns == 2) 1.dp else 2.dp) else (if (columns == 2) minOf(24.dp, maxWidth * .08f) else outside)
                    val start = if (right) inside else outside
                    val end = if (right) outside else inside
                    val width = minOf(maxWidth - start - end, imageHeight * (aspects[number] ?: FallbackPdfAspect))
                    Box(Modifier.fillMaxSize().padding(start = start, end = end, top = top, bottom = bottom),
                        contentAlignment = Alignment.Center) { leaf(number, Modifier.width(width.coerceAtLeast(1.dp))) }
                    if (!immersive) {
                        Box(Modifier.align(Alignment.BottomCenter)) { PdfPageFooter(book, number, bottom) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageFooter(book: Textbook, number: Int, height: Dp = 48.dp) {
    Box(Modifier.height(height).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(book.pageLabel(number), fontFamily = LocalV7Fonts.current.numbers,
            style = MaterialTheme.typography.labelSmall, color = CoachTheme.colors.fgSecondary)
    }
}

@Composable
private fun OriginalPdfLeaf(
    book: Textbook, page: BookPage, library: TextbookLibrary, reading: OriginalPdfReading,
    layers: OriginalPdfTextLayers, aspect: Float?, citation: CitedPdfLocation?, quote: String?,
    highlightRevision: Int, modifier: Modifier, onPage: (Int) -> Unit,
    bitmapCache: OriginalPdfBitmapCache? = null,
    onRenderWidth: (Int) -> Unit = {},
) {
    var pixelWidth by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    val reveal = reading.destination.takeIf { it.page == page.number && it.reveal }
    val zoom = if (originalPdfTouchEnabled) remember(page.number, reveal) { PdfTouchZoom() } else null
    val renderWidth = if (zoom == null || zoom.scale == 1f || pixelWidth == 0) pixelWidth else {
        // Keep the current raster during the pinch. Bucket the next request for cache reuse.
        val target = (pixelWidth * zoom.scale / 128f).roundToInt() * 128
        target.coerceIn(pixelWidth.coerceAtMost(TouchPdfRasterLimit), TouchPdfRasterLimit)
    }
    LaunchedEffect(renderWidth) { if (renderWidth > 0) onRenderWidth(renderWidth) }
    LaunchedEffect(layers, page.number, retry) { layers.keep(page) }
    val layer = layers[page.number]?.getOrNull()
    val image = rememberOriginalPageImage(library, book, page.number, true, renderWidth, retry, bitmapCache = bitmapCache)
    val displayed = (image as? PageImage.Ready)?.image?.let { it.width.toFloat() / it.height }
    val rasterStatus = PdfRasterStatus(displayed, image == PageImage.Failed)
    SideEffect { if (reading.rasters[page.number] != rasterStatus) reading.rasters[page.number] = rasterStatus }
    val active = reading.page == page.number
    val matchesCitation = citation?.takeIf { displayed != null && kotlin.math.abs(displayed - it.boxes.width / it.boxes.height) < .05 }
    val marks = remember(library, book.id, page, highlightRevision) { library.highlights(book.id, page) }
    OriginalBookPage("${book.title} ${book.pageLabel(page.number)}", image, aspect ?: FallbackPdfAspect, layer,
        page.text, reading.selection?.takeIf { it.page == page.number }?.range, marks, matchesCitation, quote,
        onResize = { pixelWidth = it.width }, onRetry = { layers.invalidate(page.number); retry++ },
        onSelection = { range ->
            reading.selection = range?.let {
                val top = layer?.let { unit -> PdfTextSelection.boxesForVerifiedRange(unit, page.text, range).firstOrNull()?.y }
                PdfSelection(page.number, range, top?.toFloat() ?: 0f)
            }
            if (range != null && !active) {
                reading.focus(page.number)
                onPage(page.number)
            }
        }, modifier = modifier.testTag("original-pdf-page-${page.number}"), roundPdfCorners = true,
        active = active, inactivePage = page.number, knownAspect = aspect,
        touchZoom = zoom,
    )
}
