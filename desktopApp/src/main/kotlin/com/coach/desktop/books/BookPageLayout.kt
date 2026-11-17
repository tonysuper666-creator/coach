package com.coach.desktop.books

import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.*
import kotlin.math.min

/** Stored coordinates, never offsets into the concatenated display string. */
internal data class BookPageAnchor(val importPage: Int, val offsetUtf16: Int = 0)
internal data class BookPageSlice(val importPage: Int, val startUtf16: Int, val endUtf16: Int)
internal enum class BookPageMode { Auto, Single, Spread }

internal fun bookSpread(widthDp: Float, columnDp: Float, mode: BookPageMode, wasSpread: Boolean): Boolean = when (mode) {
    BookPageMode.Single -> false
    BookPageMode.Spread -> true
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

internal data class BookLaidPage(
    val sources: List<BookPageSlice>, val chapter: Int, val title: String?, val chapterNumber: String?,
    val opener: Boolean, val text: AnnotatedString,
    val placeholders: List<AnnotatedString.Range<Placeholder>>,
    val topPx: Int, val heightPx: Int,
) {
    val anchor get() = sources.first().let { BookPageAnchor(it.importPage, it.startUtf16) }
    val sourceLabel get() = if (sources.first().importPage == sources.last().importPage)
        "第 ${sources.first().importPage} 页" else "第 ${sources.first().importPage}–${sources.last().importPage} 页"
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

internal fun List<BookLaidPage>.pageContaining(anchor: BookPageAnchor): Int = indexOfFirst { page ->
    page.sources.any { it.importPage == anchor.importPage && anchor.offsetUtf16 >= it.startUtf16 && anchor.offsetUtf16 < it.endUtf16 }
}.takeIf { it >= 0 } ?: indexOfLast { it.sources.any { source -> source.importPage == anchor.importPage } }.coerceAtLeast(0)

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

private data class BookChapter(val start: Int, val end: Int, val title: String?)
private data class BookPageText(val text: AnnotatedString, val placeholders: List<AnnotatedString.Range<Placeholder>>)

/** TextMeasurer and Text receive identical styles, placeholders and integer column width. */
internal class BookPageLayouter(
    private val measurer: TextMeasurer, private val density: Density, private val typography: BookPageTypography,
    private val pdf: Boolean = false,
) {
    fun layout(pages: List<BookPage>, widthPx: Int, bodyHeightPx: Int,
        highlights: Map<Int, List<IntRange>> = emptyMap()): List<BookLaidPage> {
        require(widthPx > 0 && bodyHeightPx > 0)
        val source = pages.joinToString("") { it.text }
        if (source.isEmpty()) return emptyList()
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
        val boundaries = (listOf(0) + headings.map { it.first } + source.length).distinct().sorted()
        val chapters = boundaries.zipWithNext { start, end -> BookChapter(start, end, headings.firstOrNull { it.first == start }?.second) }
        val result = mutableListOf<BookLaidPage>()
        for ((chapterIndex, chapter) in chapters.withIndex()) {
            val raw = source.substring(chapter.start, chapter.end)
            // A recognized chapter title is a heading even in plain text/PDF. Do not
            // let PDF hard-line joining merge it into the first body paragraph.
            val headingEnd = if (chapter.title != null) raw.indexOf('\n').let { if (it < 0) raw.length else it + 1 } else 0
            val head = bookParagraphs(raw.take(headingEnd), pdf = false)
            val tail = bookParagraphs(raw.substring(headingEnd), pdf = pdf)
            val model = BookParagraphs(head.display + tail.display,
                head.paragraphs.map { it.copy(kind = BookParagraph.Kind.HEADING) } +
                    tail.paragraphs.mapIndexed { i, p -> p.copy(start = p.start + headingEnd, end = p.end + headingEnd,
                        afterHeading = p.afterHeading || (i == 0 && headingEnd > 0)) })
            val original = buildAnnotatedString {
                append(raw)
                if (chapter.title != null) {
                    val end = raw.indexOf('\n').let { if (it < 0) raw.length else it }
                    addStyle(typography.chapter, 0, end)
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
            var start = 0
            while (start < raw.length) {
                val opener = start == 0 && chapter.title != null
                val number = if (opener) bookChapterNumber(chapter.title!!) else null
                val top = if (opener) bodyHeightPx / 4 else 0
                val titleInset = if (number != null) measurer.measure(bookChapterDisplayNumber(number), typography.number).size.width +
                    with(density) { 20.dp.roundToPx() } else 0
                val available = (bodyHeightPx - top).coerceAtLeast(1)
                // Measure a bounded look-ahead, extending it for unusually narrow glyphs
                // or tall windows. Repeatedly measuring an entire remaining textbook is
                // quadratic and blocks resize on long, single-paragraph imports.
                var lookAhead = minOf(raw.length, safeBookBoundary(raw, start + minOf(4096, raw.length - start)))
                var rest = pageText(original, model, start, lookAhead, titleInset)
                var measured = measure(rest, widthPx)
                val reserve = with(density) { typography.body.lineHeight.toPx() * 2 }
                while (lookAhead < raw.length && measured.size.height <= available + reserve) {
                    lookAhead = safeBookBoundary(raw, start + minOf((lookAhead - start) * 2, raw.length - start))
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
                var end = if (lines >= measured.lineCount) raw.length else start + measured.getLineEnd(lines - 1)
                end = safeBookBoundary(raw, end).coerceAtLeast(start + Character.charCount(raw.codePointAt(start)))
                var laid = pageText(original, model, start, end, titleInset)
                var height = measure(laid, widthPx).size.height
                while (height > available && lines > 1) {
                    lines--
                    end = safeBookBoundary(raw, start + measured.getLineEnd(lines - 1))
                    laid = pageText(original, model, start, end, titleInset)
                    height = measure(laid, widthPx).size.height
                }
                result += BookLaidPage(slices(chapter.start + start, chapter.start + end), chapterIndex,
                    chapter.title, number, opener, laid.text, laid.placeholders, top, height)
                start = end
            }
        }
        return result
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
