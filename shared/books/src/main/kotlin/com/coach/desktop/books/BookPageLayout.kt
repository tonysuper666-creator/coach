package com.coach.desktop.books

import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.*
import kotlin.math.min

/** Stored coordinates, never offsets into the concatenated display string. */
internal data class BookPageAnchor(val importPage: Int, val offsetUtf16: Int = 0, val visualOrder: Int? = null,
    val tableStartY: Int? = null)
internal data class BookPageSlice(val importPage: Int, val startUtf16: Int, val endUtf16: Int)
internal enum class BookPageMode { Auto, Single, Spread }

/** Pixel coordinates in this exact reviewed crop. Canonical row ends are required when it replaces text. */
internal data class BookTableRow(val bottomPx: Int, val sourceEndUtf16: Int? = null)
internal data class BookTableRows(val headerBottomPx: Int, val rows: List<BookTableRow>,
    val bodyFontPx: Float, val tableFontPx: Float)
internal data class BookTableBand(val headerBottom: Int, val startY: Int, val endY: Int, val widthPx: Int) {
    val sourceHeight get() = headerBottom + endY - startY
}

internal fun bookSpread(widthDp: Float, columnDp: Float, mode: BookPageMode, wasSpread: Boolean): Boolean = when (mode) {
    BookPageMode.Single -> false
    BookPageMode.Spread -> widthDp >= 2 * (columnDp * (28f / 33f) + 112) + 48
    BookPageMode.Auto -> widthDp >= 2 * (columnDp + 112) + 48 - if (wasSpread) 24 else 0
}

internal data class BookPageTypography(
    val body: TextStyle, val chapter: SpanStyle, val section: SpanStyle,
    val chapterLine: TextUnit, val sectionLine: TextUnit,
    val smallGap: TextUnit, val headingGap: TextUnit,
    val highlight: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    val number: TextStyle = body.copy(fontSize = body.fontSize * (48f / 18),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Light, letterSpacing = (-.03).em),
)

internal data class BookTextRun(
    val topPx: Int, val text: AnnotatedString, val placeholders: List<AnnotatedString.Range<Placeholder>>,
    val sources: List<BookPageSlice>, val precedingCropText: String,
)
internal data class BookPlacedVisual(
    val item: BookIllustration, val topPx: Int, val widthPx: Int, val heightPx: Int,
    val captionGapPx: Int, val captionHeightPx: Int, val captionStyle: TextStyle, val singleLineCaption: Boolean,
    val replacement: BookPageSlice?, val highlighted: Boolean, val band: BookTableBand? = null,
)

internal data class BookLaidPage(
    val sources: List<BookPageSlice>, val chapter: Int, val title: String?, val chapterNumber: String?,
    val opener: Boolean, val text: AnnotatedString,
    val placeholders: List<AnnotatedString.Range<Placeholder>>,
    val topPx: Int, val heightPx: Int,
    val runs: List<BookTextRun> = emptyList(), val visuals: List<BookPlacedVisual> = emptyList(),
) {
    val anchor: BookPageAnchor get() {
        val first = runs.firstOrNull()?.sources?.firstOrNull()
        if (first != null) return BookPageAnchor(first.importPage, first.startUtf16)
        val visual = visuals.firstOrNull() ?: return BookPageAnchor(sources.first().importPage, sources.first().startUtf16)
        return BookPageAnchor(visual.item.page, visual.item.anchorOffset, visual.item.order, visual.band?.startY)
    }
    val sourceLabel: String get() {
        val first = minOf(runs.firstOrNull()?.sources?.firstOrNull()?.importPage ?: Int.MAX_VALUE,
            visuals.minOfOrNull { it.item.page } ?: sources.first().importPage)
        val last = maxOf(runs.lastOrNull()?.sources?.lastOrNull()?.importPage ?: Int.MIN_VALUE,
            visuals.maxOfOrNull { it.item.page } ?: sources.last().importPage)
        return if (first == last) "第 $first 页" else "第 $first–${last} 页"
    }
    fun selection(start: Int, end: Int): List<BookPageSlice> {
        if (start == end) return emptyList()
        val leftBoundary = safeBookBoundary(text.text, minOf(start, end).coerceIn(0, text.length))
        val requestedEnd = maxOf(start, end).coerceIn(0, text.length)
        val rightBoundary = if (safeBookBoundary(text.text, requestedEnd) != requestedEnd) requestedEnd + 1 else requestedEnd
        var at = 0
        return sources.mapNotNull { slice ->
            val length = slice.endUtf16 - slice.startUtf16
            val left = maxOf(leftBoundary, at); val right = minOf(rightBoundary, at + length)
            val result = if (left < right) slice.copy(startUtf16 = slice.startUtf16 + left - at,
                endUtf16 = slice.startUtf16 + right - at) else null
            at += length
            result
        }
    }
}

internal fun List<BookLaidPage>.pageContaining(anchor: BookPageAnchor): Int {
    val visual = indexOfFirst { page -> page.visuals.any { block ->
        val item = block.item
        item.page == anchor.importPage && (if (anchor.visualOrder != null)
            item.anchorOffset == anchor.offsetUtf16 && item.order == anchor.visualOrder &&
                (anchor.tableStartY == null || block.band == null || anchor.tableStartY in block.band.startY until block.band.endY)
        else block.replacement?.let { anchor.offsetUtf16 in it.startUtf16 until it.endUtf16 } == true)
    } }
    if (visual >= 0) return visual
    return indexOfFirst { page -> page.sources.any {
        it.importPage == anchor.importPage && anchor.offsetUtf16 in it.startUtf16 until it.endUtf16
    } }.takeIf { it >= 0 } ?: indexOfLast { it.sources.any { source -> source.importPage == anchor.importPage } }.coerceAtLeast(0)
}

/** Figure/callout contract for the following illustration lane. Formula/table blocks stay in flow. */
internal data class BookFigureBlock(
    val anchor: BookPageAnchor, val widthPx: Int, val heightPx: Int, val captionHeightPx: Int,
    val kind: Kind = Kind.Figure,
) { enum class Kind { Figure, Callout, Formula, Table } }

internal data class BookFigurePlacement(val pageDelta: Int, val widthPx: Int, val heightPx: Int, val captionHeightPx: Int)
internal fun placeBookFigure(block: BookFigureBlock, columnPx: Int, bodyPx: Int, remainingPx: Int): BookFigurePlacement {
    require(columnPx > 0 && bodyPx > 0 && block.widthPx > 0 && block.heightPx > 0)
    val scale = min(1f, min(columnPx.toFloat() / block.widthPx,
        (bodyPx * .85f - block.captionHeightPx).coerceAtLeast(1f) / block.heightPx))
    val height = (block.heightPx * scale).toInt().coerceAtLeast(1)
    val floats = block.kind == BookFigureBlock.Kind.Figure || block.kind == BookFigureBlock.Kind.Callout
    return BookFigurePlacement(if (floats && height + block.captionHeightPx > remainingPx) 1 else 0,
        (block.widthPx * scale).toInt().coerceAtLeast(1), height, block.captionHeightPx)
}

private data class BookChapter(val start: Int, val end: Int, val title: String?, val headingStart: Int)
private data class BookPageText(val text: AnnotatedString, val placeholders: List<AnnotatedString.Range<Placeholder>>)

/** TextMeasurer and Text receive identical styles, placeholders and integer column width. */
internal class BookPageLayouter(
    private val measurer: TextMeasurer, private val density: Density, private val typography: BookPageTypography,
    private val pdf: Boolean = false,
) {
    fun layout(pages: List<BookPage>, widthPx: Int, bodyHeightPx: Int,
        highlights: Map<Int, List<IntRange>> = emptyMap(), tableRows: Map<BookIllustration, BookTableRows> = emptyMap()): List<BookLaidPage> {
        require(widthPx > 0 && bodyHeightPx > 0)
        val source = pages.joinToString("") { it.text }
        if (source.isEmpty() && pages.all { it.illustrations.isEmpty() }) return emptyList()
        val starts = mutableListOf<Pair<Int, BookPage>>()
        var at = 0
        pages.forEach { starts += at to it; at += it.text.length }
        fun slices(start: Int, end: Int) = starts.mapNotNull { (base, page) ->
            val left = maxOf(start, base); val right = minOf(end, base + page.text.length)
            if (left < right) BookPageSlice(page.number, left - base, right - base) else null
        }
        val lineHeadings = Regex("(?m)^[^\\r\\n]+$").findAll(source).mapNotNull { line ->
            BookText.chapterTitle(line.value)?.let { line.range.first to it }
        }.toList()
        // Text import trims chapter chunks. A real heading at an import-page start
        // still starts a chapter when the preceding chunk has no trailing newline.
        val headings = (lineHeadings + starts.mapNotNull { (base, page) ->
            BookText.chapterTitle(page.text.lineSequence().firstOrNull().orEmpty())?.let { base to it }
        }).distinctBy { it.first }.sortedBy { it.first }
        // Physical-page metadata belongs to the following heading. Keep its source
        // coordinates, without manufacturing a separate preamble sheet for it.
        val physicalMarker = Regex("<!--\\s*PDF 第 \\d+ 页 · 书第 .*? 页\\s*-->")
        val chapterStarts = headings.map { (heading, title) ->
            val base = starts.last { it.first <= heading }.first
            val prefix = source.substring(base, heading)
            val start = if (prefix.lineSequence().all { it.isBlank() || physicalMarker.matches(it.trim()) }) base else heading
            Triple(start, heading, title)
        }.distinctBy { it.first }
        val boundaries = (listOf(0) + chapterStarts.map { it.first } + source.length).distinct().sorted()
        val textChapters = if (source.isEmpty()) listOf(BookChapter(0, 0, null, 0)) else boundaries.zipWithNext { start, end ->
            val heading = chapterStarts.firstOrNull { it.first == start }
            BookChapter(start, end, heading?.third, heading?.second ?: start)
        }
        val leadingImages = textChapters.first().title != null && pages.takeWhile { it.text.isEmpty() }.any { it.illustrations.isNotEmpty() }
        val chapters = if (leadingImages) listOf(BookChapter(0, 0, null, 0)) + textChapters else textChapters
        val chapterVisuals = starts.flatMap { (base, page) ->
            if (page.illustrations.isEmpty()) return@flatMap emptyList()
            val segments = BookIllustrationLayout.segment(page.text, page.illustrations, hideReplacedSourceText = true)
            val visible = segments.filterIsInstance<BookPageSegment.Text>()
            segments.filterIsInstance<BookPageSegment.Illustration>().flatMap { segment ->
                val item = segment.illustration
                val offset = BookIllustrationAnchors.snapToValidUtf16Boundary(page.text, item.anchorOffset)
                val at = base + offset
                // An image at EOF (including an empty physical page) still belongs to the preceding chapter.
                val owner = if (leadingImages && base == 0 && page.text.isEmpty()) 0 else
                    chapters.indexOfLast { it.start <= if (offset == page.text.length && at > 0) at - 1 else at }.coerceAtLeast(0)
                val cropEnd = item.sourceEnd?.takeIf { end ->
                    item.kind != PageVisualKind.ILLUSTRATION && item.sourceRegion != null && item.sourceStart == offset &&
                        end > offset && end <= page.text.length && visible.none { it.startOffset < end && it.endOffset > offset }
                }
                // PDF captions remain addressable source text. Move a verbatim, isolated caption with its figure.
                val captionEnd = if (pdf && item.kind == PageVisualKind.ILLUSTRATION && item.sourceRegion != null)
                    item.caption?.takeIf { it.isNotBlank() && page.text.startsWith(it, offset) }?.let { offset + it.length }
                        ?.takeIf { end -> page.text.substring(page.text.lastIndexOfAny(charArrayOf('\r', '\n'), offset - 1) + 1, offset).isBlank() &&
                            page.text.substring(end, page.text.indexOfAny(charArrayOf('\r', '\n'), end).let { if (it < 0) page.text.length else it }).isBlank() &&
                            page.illustrations.none { it !== item && it.anchorOffset in offset until end } }
                    else null
                val replacement = (cropEnd ?: captionEnd)?.takeIf { base + it <= chapters[owner].end }
                    ?.let { BookPageSlice(page.number, offset, it) }
                val marked = highlights[page.number].orEmpty().any { r ->
                    if (replacement == null) offset in r else r.first < replacement.endUtf16 && r.last >= offset
                }
                val event = VisualEvent(owner, at, item, replacement, marked)
                val rows = if (item.kind == PageVisualKind.TABLE) {
                    tableRows[item] ?: item.table?.takeIf { it.valid(item.height, page.text) }?.layout()
                } else null
                if (rows == null) listOf(event)
                else tableEvents(event, rows, page.text, highlights[page.number].orEmpty(), widthPx, bodyHeightPx)
            }
        }.groupBy { it.chapter }
        val result = mutableListOf<BookLaidPage>()
        var previousTextEnd = 0
        for ((chapterIndex, chapter) in chapters.withIndex()) {
            val raw = source.substring(chapter.start, chapter.end)
            // A recognized chapter title is a heading even in plain text/PDF. Do not
            // let PDF hard-line joining merge it into the first body paragraph.
            val headingStart = chapter.headingStart - chapter.start
            val headingEnd = if (chapter.title != null) raw.indexOf('\n', headingStart).let { if (it < 0) raw.length else it + 1 } else 0
            val head = bookParagraphs(raw.take(headingEnd), pdf = false)
            val tail = bookParagraphs(raw.substring(headingEnd), pdf = pdf)
            val model = BookParagraphs(head.display + tail.display,
                head.paragraphs.map { if (it.start >= headingStart) it.copy(kind = BookParagraph.Kind.HEADING) else it } +
                    tail.paragraphs.mapIndexed { i, p -> p.copy(start = p.start + headingEnd, end = p.end + headingEnd,
                        afterHeading = p.afterHeading || (i == 0 && headingEnd > 0)) })
            val original = buildAnnotatedString {
                append(raw)
                if (chapter.title != null && markdownHeadings(raw).none { it.marks.first == headingStart }) {
                    val end = raw.indexOf('\n', headingStart).let { if (it < 0) raw.length else it }
                    addStyle(typography.chapter, headingStart, end)
                }
                markdownHeadings(raw).forEach {
                    addStyle(if (it.level == 1) typography.chapter else typography.section, it.title.first, it.title.last + 1)
                    addStyle(SpanStyle(color = androidx.compose.ui.graphics.Color.Transparent, fontSize = 1.sp), it.marks.first, it.marks.last + 1)
                }
                for ((base, page) in starts) for (range in highlights[page.number].orEmpty()) {
                    val left = maxOf(chapter.start, base + range.first)
                    val right = minOf(chapter.end, base + range.last + 1)
                    if (left < right) addStyle(SpanStyle(background = typography.highlight), left - chapter.start, right - chapter.start)
                }
            }
            val events = chapterVisuals[chapterIndex].orEmpty()
            val boxes = mutableMapOf<Pair<VisualEvent, Int>, VisualBox>()
            fun boxFor(event: VisualEvent, height: Int) = boxes.getOrPut(event to height) { sizeVisual(event, widthPx, height) }
            var next = 0
            var start = 0
            var firstSheet = true
            val pending = ArrayDeque<VisualEvent>()
            while (start < raw.length || next < events.size || pending.isNotEmpty()) {
                val opener = firstSheet && chapter.title != null
                val number = if (opener) bookChapterNumber(chapter.title!!) else null
                val top = if (opener) bodyHeightPx / 4 else 0
                val titleInset = if (number != null) measurer.measure(bookChapterDisplayNumber(number), typography.number).size.width +
                    with(density) { 20.dp.roundToPx() } else 0
                val sheetStart = start
                var y = top
                val runs = mutableListOf<BookTextRun>()
                val visuals = mutableListOf<BookPlacedVisual>()
                fun place(event: VisualEvent): Boolean {
                    val box = boxFor(event, if (runs.isEmpty() && visuals.isEmpty()) bodyHeightPx - y else bodyHeightPx)
                    if (box.totalHeight > bodyHeightPx - y && (runs.isNotEmpty() || visuals.isNotEmpty() || y > 0)) return false
                    visuals += box.block.copy(topPx = y + box.margin)
                    y += box.totalHeight
                    return true
                }
                while (pending.isNotEmpty() && place(pending.first())) pending.removeFirst()
                while (true) {
                    val event = events.getOrNull(next)
                    if (event != null && event.at <= chapter.start + start) {
                        if (event.band?.let { it.startY > it.headerBottom } == true && (runs.isNotEmpty() || visuals.isNotEmpty())) break
                        if (pending.isNotEmpty() || !place(event)) {
                            if (event.inline || (pending.isNotEmpty() &&
                                    (pending.sumOf { boxFor(it, bodyHeightPx).totalHeight } +
                                        boxFor(event, bodyHeightPx).totalHeight > bodyHeightPx))) break
                            pending.addLast(event)
                        }
                        next++
                        event.replacement?.let { start = maxOf(start, event.at - chapter.start + it.endUtf16 - it.startUtf16) }
                        continue
                    }
                    val limit = event?.let { it.at - chapter.start } ?: raw.length
                    if (start >= limit || y >= bodyHeightPx) break
                    val fit = fitText(raw, original, model, start, limit, widthPx, bodyHeightPx - y,
                        if (start == 0 && opener) titleInset else 0)
                    if (fit.height > bodyHeightPx - y && (runs.isNotEmpty() || visuals.isNotEmpty())) break
                    val sources = slices(chapter.start + start, chapter.start + fit.end)
                    runs += BookTextRun(y, fit.laid.text, fit.laid.placeholders, sources, source.substring(previousTextEnd, chapter.start + start))
                    previousTextEnd = chapter.start + fit.end
                    y += fit.height
                    start = fit.end
                    if (start < limit) break
                }
                val sources = (runs.singleOrNull()?.takeIf { it.text.length == start - sheetStart }?.sources ?:
                    slices(chapter.start + sheetStart, chapter.start + start)).ifEmpty {
                    val item = (visuals.firstOrNull()?.item ?: events.getOrNull(next)?.item ?: pending.first().item)
                    listOf(BookPageSlice(item.page, item.anchorOffset, item.anchorOffset))
                }
                val fullRun = runs.singleOrNull()?.takeIf { it.text.length == start - sheetStart }
                val text = fullRun?.text ?: original.subSequence(sheetStart, start)
                result += BookLaidPage(sources, chapterIndex, chapter.title, number, opener, text,
                    fullRun?.placeholders.orEmpty(), top, y - top, runs, visuals)
                firstSheet = false
            }
        }
        return result
    }

    private data class VisualEvent(val chapter: Int, val at: Int, val item: BookIllustration,
        val replacement: BookPageSlice?, val highlighted: Boolean, val band: BookTableBand? = null) {
        val inline get() = item.kind == PageVisualKind.TABLE || item.kind == PageVisualKind.FORMULA
    }
    private data class VisualBox(val block: BookPlacedVisual, val margin: Int, val totalHeight: Int)

    private fun tableEvents(event: VisualEvent, table: BookTableRows, text: String, marks: List<IntRange>,
        width: Int, height: Int): List<VisualEvent> = with(density) {
        val item = event.item
        val header = table.headerBottomPx
        val rows = table.rows
        val covered = event.replacement
        if (header !in 1 until item.height || rows.lastOrNull()?.bottomPx != item.height ||
            !table.bodyFontPx.isFinite() || table.bodyFontPx <= 0 || !table.tableFontPx.isFinite() || table.tableFontPx <= 0) return listOf(event)
        var previousY = header
        var previousOffset = covered?.startUtf16 ?: 0
        for (row in rows) {
            if (row.bottomPx <= previousY || row.bottomPx > item.height) return listOf(event)
            previousY = row.bottomPx
            if (covered != null) {
                val end = row.sourceEndUtf16 ?: return listOf(event)
                if (end <= previousOffset || end > covered.endUtf16 || safeBookBoundary(text, end) != end) return listOf(event)
                previousOffset = end
            }
        }
        if (covered != null && previousOffset != covered.endUtf16) return listOf(event)
        val scale = minOf(typography.body.fontSize.toPx() / table.bodyFontPx, width.toFloat() / item.width)
        val cap = (height * .85f).toInt().coerceAtLeast(1)
        val split = item.height * scale > cap && table.tableFontPx * cap / item.height < 12.sp.toPx()
        val targetWidth = (item.width * scale).toInt().coerceIn(1, width)
        buildList {
            var first = 0
            while (first < rows.size) {
                val startY = if (first == 0) header else rows[first - 1].bottomPx
                var last = if (split) first else rows.lastIndex
                while (last < rows.lastIndex && (header + rows[last + 1].bottomPx - startY) * scale <= cap) last++
                val slice = covered?.copy(startUtf16 = if (first == 0) covered.startUtf16 else rows[first - 1].sourceEndUtf16!!,
                    endUtf16 = rows[last].sourceEndUtf16!!)
                add(event.copy(at = event.at + (slice?.startUtf16 ?: 0) - (covered?.startUtf16 ?: 0),
                    replacement = slice, highlighted = if (slice == null) event.highlighted && first == 0 else
                        marks.any { it.first < slice.endUtf16 && it.last >= slice.startUtf16 },
                    band = BookTableBand(header, startY, rows[last].bottomPx, targetWidth)))
                first = last + 1
            }
        }
    }

    private fun sizeVisual(event: VisualEvent, width: Int, height: Int): VisualBox = with(density) {
        val item = event.item
        val margin = (if (event.inline) 12.dp else 16.dp).roundToPx().coerceAtMost(height / 14)
        val cap = (height * .85f).toInt().coerceAtLeast(1)
        val naturalWidth = event.band?.widthPx ?: item.width.dp.roundToPx().coerceAtLeast(1)
        val naturalHeight = event.band?.let { (it.sourceHeight.toDouble() * naturalWidth / item.width).toInt().coerceAtLeast(1) }
            ?: item.height.dp.roundToPx().coerceAtLeast(1)
        // Shrink the complete measured group (including a multi-line caption) together.
        val caption = item.caption.takeIf { item.kind == PageVisualKind.ILLUSTRATION }.orEmpty()
        val initialWidth = minOf(width, naturalWidth)
        val baseStyle = typography.body.copy(fontSize = 15.sp, lineHeight = 22.sp)
        val initialHeight = naturalHeight.toDouble() * initialWidth / naturalWidth
        val gap = if (caption.isEmpty()) 0 else 8.dp.roundToPx()
        fun atScale(scale: Float): VisualBox {
            val w = (initialWidth * scale).toInt().coerceAtLeast(1)
            val h = (initialHeight * scale).toInt().coerceAtLeast(1)
            val style = baseStyle.copy(fontSize = baseStyle.fontSize * scale, lineHeight = baseStyle.lineHeight * scale)
            val layout = if (caption.isEmpty()) null else measurer.measure(caption, style, constraints = Constraints(maxWidth = w))
            val space = (gap * scale).toInt()
            val captionHeight = layout?.size?.height ?: 0
            val block = BookPlacedVisual(item, 0, w, h, space, captionHeight, style, layout?.lineCount == 1, event.replacement, event.highlighted, event.band)
            return VisualBox(block, margin, margin * 2 + h + space + captionHeight)
        }
        val full = atScale(1f)
        if (full.totalHeight - margin * 2 <= cap) return@with full
        var low = .0001f
        var high = 1f
        repeat(16) {
            val mid = (low + high) / 2
            if (atScale(mid).totalHeight - margin * 2 <= cap) low = mid else high = mid
        }
        atScale(low)
    }

    private data class TextFit(val end: Int, val laid: BookPageText, val height: Int)

    private fun fitText(raw: String, original: AnnotatedString, model: BookParagraphs, start: Int, limit: Int,
        widthPx: Int, available: Int, titleInset: Int): TextFit {
        // Measure a bounded look-ahead, extending it for unusually narrow glyphs
        // or tall windows. Repeatedly measuring an entire remaining textbook is
        // quadratic and blocks resize on long, single-paragraph imports.
        var lookAhead = minOf(limit, safeBookBoundary(raw, start + minOf(4096, limit - start)))
        var rest = pageText(original, model, start, lookAhead, titleInset)
        var measured = measure(rest, widthPx)
        val reserve = with(density) { typography.body.lineHeight.toPx() * 2 }
        while (lookAhead < limit && measured.size.height <= available + reserve) {
            lookAhead = safeBookBoundary(raw, start + minOf((lookAhead - start) * 2, limit - start))
            rest = pageText(original, model, start, lookAhead, titleInset)
            measured = measure(rest, widthPx)
        }
        var lines = (0 until measured.lineCount).takeWhile { measured.getLineBottom(it) <= available }.size.coerceAtLeast(1)
        if (lines < measured.lineCount) {
            var end = measured.getLineEnd(lines - 1)
            // At least two lines at either end of a paragraph with four or more lines.
            for (paragraph in model.paragraphs) {
                if (paragraph.start < start + end && paragraph.end > start + end) {
                    val first = measured.getLineForOffset((paragraph.start - start).coerceAtLeast(0))
                    val last = if (paragraph.end > lookAhead) measured.lineCount + 1 else
                        measured.getLineForOffset((paragraph.end - start - 1).coerceIn(0, rest.text.lastIndex))
                    if (last - first + 1 >= 4) {
                        if (lines - first == 1 && first > 0) lines = first
                        else if (last - lines + 1 == 1 && lines - first > 2) lines--
                    }
                }
            }
            end = measured.getLineEnd(lines - 1)
            // A heading travels with the following paragraph, never alone at the foot.
            val lastParagraph = model.paragraphs.lastOrNull { it.start < start + end }
            val previous = lastParagraph?.let { model.paragraphs.getOrNull(model.paragraphs.indexOf(it) - 1) }
            val stranded = when {
                lastParagraph?.kind == BookParagraph.Kind.HEADING -> lastParagraph
                previous?.kind == BookParagraph.Kind.HEADING && lastParagraph != null &&
                    measured.getLineForOffset((lastParagraph.start - start).coerceAtLeast(0)) >= lines - 1 -> previous
                else -> null
            }
            if (stranded != null && stranded.start > start) {
                lines = measured.getLineForOffset(stranded.start - start).coerceAtLeast(1)
            }
        }
        var end = if (lines >= measured.lineCount) lookAhead else start + measured.getLineEnd(lines - 1)
        end = safeBookBoundary(raw, end).coerceAtLeast(start + Character.charCount(raw.codePointAt(start)))
        var laid = pageText(original, model, start, end, titleInset)
        var height = measure(laid, widthPx).size.height
        while (height > available && lines > 1) {
            lines--
            end = safeBookBoundary(raw, start + measured.getLineEnd(lines - 1))
            laid = pageText(original, model, start, end, titleInset)
            height = measure(laid, widthPx).size.height
        }
        // A clipped paragraph is laid out again on the following page. Its
        // final fragment can fit on one line even when the uncut layout's
        // line indices predicted two. Check that actual continuation after
        // fitting this page, using the same no-indent continuation style.
        val continuing = model.paragraphs.firstOrNull {
            it.kind == BookParagraph.Kind.BODY && it.start < end && it.end > end
        }
        if (continuing != null && continuing.end <= lookAhead && lines > 2 &&
            measure(pageText(original, model, end, continuing.end, 0), widthPx).lineCount == 1) {
            lines--
            end = safeBookBoundary(raw, start + measured.getLineEnd(lines - 1))
            laid = pageText(original, model, start, end, titleInset)
            height = measure(laid, widthPx).size.height
        }
        return TextFit(end, laid, height)
    }

    private fun measure(text: BookPageText, width: Int) = measurer.measure(text.text, typography.body,
        placeholders = text.placeholders, constraints = Constraints(maxWidth = width))

    private fun pageText(original: AnnotatedString, model: BookParagraphs, start: Int, end: Int, titleInset: Int): BookPageText {
        val paragraphs = model.paragraphs.filter { it.end > start && it.start < end }.map {
            it.copy(start = (it.start - start).coerceAtLeast(0), end = minOf(it.end, end) - start)
        }
        val clipped = BookParagraphs(model.display.substring(start, end), paragraphs)
        val marked = bookParagraphText(original.subSequence(start, end), clipped, typography.body,
            typography.smallGap, typography.headingGap, typography.chapterLine, typography.sectionLine)
        val first = model.paragraphs.firstOrNull { it.end > start }
        val text = buildAnnotatedString {
            append(marked.text)
            marked.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
            marked.paragraphStyles.forEachIndexed { i, range ->
                val indent = when {
                    i == 0 && titleInset > 0 -> with(density) { TextIndent(titleInset.toSp(), titleInset.toSp()) }
                    i == 0 && first != null && first.start < start -> TextIndent.None
                    else -> range.item.textIndent
                }
                addStyle(range.item.copy(textIndent = indent), range.start, range.end)
            }
            marked.getStringAnnotations(0, marked.length).forEach { addStringAnnotation(it.tag, it.item, it.start, it.end) }
        }
        val placeholders = paragraphs.mapIndexedNotNull { i, paragraph ->
            val previous = paragraphs.getOrNull(i - 1) ?: return@mapIndexedNotNull null
            val gap = when {
                paragraph.kind == BookParagraph.Kind.HEADING -> typography.headingGap
                previous.kind != BookParagraph.Kind.BODY || paragraph.kind != BookParagraph.Kind.BODY -> typography.smallGap
                else -> 0.sp
            }
            if (gap.value == 0f) return@mapIndexedNotNull null
            val normal = measurer.measure(text.subSequence(paragraph.start, paragraph.end), typography.body)
            val height = with(density) { (normal.firstBaseline + gap.toPx()).toSp() }
            AnnotatedString.Range(Placeholder(0.sp, height, PlaceholderVerticalAlign.AboveBaseline), previous.end, previous.end + 1,
                "coach.book.gap.${paragraph.start}")
        }
        return BookPageText(text, placeholders)
    }
}

internal fun safeBookBoundary(text: String, end: Int): Int = if (end in 1 until text.length &&
    text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end - 1 else end

internal fun bookChapterNumber(title: String): String? = Regex("^第\\s*([零〇一二三四五六七八九十百千万两0-9]+)\\s*[章节篇]")
    .find(title)?.groupValues?.get(1) ?: Regex("^(?:Chapter|Section)\\s+([0-9]+|[IVXLC]+)\\b", RegexOption.IGNORE_CASE)
    .find(title)?.groupValues?.get(1)

internal fun bookChapterDisplayNumber(raw: String): String {
    raw.toIntOrNull()?.let { return it.toString().padStart(2, '0') }
    val digits = "零一二三四五六七八九"
    var total = 0; var digit = 0
    for (c in raw) when {
        c in digits -> digit = digits.indexOf(c)
        c == '〇' -> digit = 0
        c == '两' -> digit = 2
        c == '十' || c == '百' || c == '千' -> {
            total += digit.coerceAtLeast(1) * when(c) { '十' -> 10; '百' -> 100; else -> 1000 }; digit = 0
        }
        else -> return raw // Roman chapter numbers retain their printed form.
    }
    return (total + digit).toString().padStart(2, '0')
}
