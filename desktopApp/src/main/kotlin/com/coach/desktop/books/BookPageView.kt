@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.coach.desktop.books

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.asAwtTransferable
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

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
        val data = clipEntry?.asAwtTransferable
        val flavor = DataFlavor(AnnotatedString::class.java, "AnnotatedString")
        val selected = if (data?.isDataFlavorSupported(flavor) == true) data.getTransferData(flavor) as? AnnotatedString else null
        if (selected != null) delegate.setClipEntry(ClipEntry(StringSelection(originalPagedBookSelection(selected))))
        else delegate.setClipEntry(clipEntry)
    }
}

@Stable
internal class BookPagingState(initial: BookPageAnchor) {
    var anchor by mutableStateOf(initial)
    var pages by mutableStateOf(emptyList<BookLaidPage>())
    var spread by mutableStateOf(false)
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
    val index get() = pages.pageContaining(anchor)
    val canPrevious get() = index > 0
    val canNext get() = index + (if (spread) 2 else 1) < pages.size
    fun move(turn: ReaderTurn): BookPageAnchor? {
        if (pages.isEmpty()) return null
        val current = index
        val target = when (turn) {
            ReaderTurn.Previous -> (current - if (spread) 2 else 1).coerceAtLeast(0)
            ReaderTurn.Next -> if (canNext) current + if (spread) 2 else 1 else current
            ReaderTurn.First -> pages.indexOfFirst { it.chapter == pages[current].chapter }
            ReaderTurn.Last -> pages.indexOfLast { it.chapter == pages[current].chapter }
        }
        anchor = pages[target].anchor
        return anchor
    }
}

/** The same source anchor survives font/width reflow and original/text view changes. */
@Composable
internal fun BookPageView(
    book: Textbook, state: BookPagingState, highlights: Map<Int, List<IntRange>>,
    onTurn: (ReaderTurn) -> Unit, modifier: Modifier = Modifier, mode: BookPageMode = BookPageMode.Auto,
    libraryDirectory: Path? = null, tableRows: Map<BookIllustration, BookTableRows> = emptyMap(), onPosition: (Int) -> Unit = {},
) {
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
            fontSize = 48.sp * scale, letterSpacing = (-.03).em))
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val reduced = LocalV7ReducedMotion.current
    val clipboard = LocalClipboard.current
    val pageClipboard = remember(clipboard) { PageClipboard(clipboard) }
    BoxWithConstraints(modifier.fillMaxSize().testTag("book-page-layout")) {
        val column = with(density) { (body.fontSize * 33).toDp() }
        val spread = bookSpread(maxWidth.value, column.value, mode, state.spread)
        val sheetWidth = if (spread) minOf(maxWidth / 2, column + 112.dp) else minOf(maxWidth, column + 128.dp)
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
        val pages = remember(layoutPages, widthPx, bodyHeight, typography, highlights, density, tableRows) {
            BookPageLayouter(measurer, density, typography, book.format == "pdf").layout(layoutPages, widthPx, bodyHeight, highlights, tableRows)
        }
        SideEffect { if (state.pages !== pages) state.pages = pages; if (state.spread != spread) state.spread = spread }
        if (pages.isEmpty()) {
            Text("这一页没有可提取文字，请切换到原页阅读。", Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        val first = pages.pageContaining(state.anchor)
        LaunchedEffect(pages, first, state.anchor) { onPosition(pages[first].anchor.importPage) }
        val fade = remember { Animatable(1f) }
        LaunchedEffect(first, pages) { fade.snapTo(if (reduced) 1f else 0f); fade.animateTo(1f, tween(if (reduced) 0 else 150)) }
        var wheelAt by remember { mutableLongStateOf(0L) }
        Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Scroll) { event ->
            val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }
            val now = System.nanoTime()
            if (delta != 0.0 && now - wheelAt > 220_000_000L) {
                wheelAt = now; onTurn(if (delta > 0) ReaderTurn.Next else ReaderTurn.Previous)
            }
            event.changes.forEach { it.consume() }
        }, contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalClipboard provides pageClipboard) {
                SelectionContainer {
                    Row(Modifier.fillMaxHeight().graphicsLayer { alpha = fade.value }) {
                        val visible = if (spread) 2 else 1
                        repeat(visible) { side ->
                            val page = pages.getOrNull(first + side)
                            Box(Modifier.width(sheetWidth).fillMaxHeight()
                                .background(if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper)
                                .clipToBounds().testTag("book-sheet-$side")) {
                                if (page != null) {
                                    val remaining = pages.count { it.chapter == page.chapter } - pages.take(first + side + 1).count { it.chapter == page.chapter }
                                    BookSheet(book.title, page, typography, measurer, widthPx,
                                        if (spread && side == 1) rightInset else leftInset,
                                        remaining, spread, side, book.id, libraryDirectory) { failed.add(it) }
                                }
                            }
                        }
                    }
                }
            }
            if (spread) Box(Modifier.width(48.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(
                Color.Transparent, (if (colors.dark) Color.Black.copy(alpha = .24f) else CoachV7.Light.ink.copy(alpha = .06f)), Color.Transparent))))
        }
    }
}

@Composable
private fun BoxScope.BookSheet(bookTitle: String, page: BookLaidPage, typography: BookPageTypography,
    measurer: TextMeasurer, widthPx: Int, inset: Dp, remaining: Int, spread: Boolean, side: Int,
    bookId: String, libraryDirectory: Path?, onMissing: (BookIllustration) -> Unit) {
    val density = LocalDensity.current
    val colors = CoachTheme.colors
    val column = with(density) { widthPx.toDp() }
    DisableSelection { if (!page.opener) Text(if (spread && side == 0) bookTitle else page.title.orEmpty(),
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
    for (block in page.visuals) key(bookId, block.item, block.band?.startY) {
        val width = with(density) { block.widthPx.toDp() }
        val left = inset + (column - width) / 2
        val y = 52.dp + with(density) { block.topPx.toDp() }
        val item = block.item
        var bitmap by remember(libraryDirectory) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(libraryDirectory, item) {
            if (libraryDirectory != null) {
                bitmap = withContext(Dispatchers.IO) {
                    try {
                        ImageIO.read(BookIllustrationAssets.imageFile(libraryDirectory, bookId, item).toFile())
                            ?.takeIf { block.band == null || (it.width == item.width && it.height == item.height) }?.toComposeImageBitmap()
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
                Box(Modifier.fillMaxWidth().height(height).then(mark).then(backing), contentAlignment = Alignment.Center) {
                    bitmap?.let { image ->
                        val band = block.band
                        if (band == null) Image(image, item.caption ?: "第 ${item.page} 页插图",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else Canvas(Modifier.fillMaxSize().semantics { contentDescription = "第 ${item.page} 页表格" }) {
                            val w = size.width.toInt()
                            val h = size.height.toInt()
                            val headerHeight = (size.height * band.headerBottom / band.sourceHeight).toInt()
                            if (w > 0 && h > headerHeight) {
                                if (headerHeight > 0) drawImage(image, srcSize = IntSize(image.width, band.headerBottom),
                                    dstSize = IntSize(w, headerHeight))
                                drawImage(image, srcOffset = IntOffset(0, band.startY), srcSize = IntSize(image.width, band.endY - band.startY),
                                    dstOffset = IntOffset(0, headerHeight), dstSize = IntSize(w, h - headerHeight))
                            }
                        }
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
