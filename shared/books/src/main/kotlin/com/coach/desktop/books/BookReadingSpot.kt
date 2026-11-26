package com.coach.desktop.books

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints

/**
 * 阅读行的原文位置（TON-1671）：翻页读和滚动读切换时，不只记「哪一页」，而是记视口顶边那一行的原文偏移，
 * 在新版面里把同一行放回去。排版页由若干 run 组成，每个 run 的显示文字按 sources 顺序对应原文切片
 * （和 [BookLaidPage.selection] 同一约定），所以显示偏移和原文偏移可以互相换算。
 */
internal fun BookLaidPage.anchorAtY(yPx: Int, measurer: TextMeasurer, typography: BookPageTypography, widthPx: Int): BookPageAnchor {
    val run = runs.lastOrNull { it.topPx <= yPx } ?: runs.firstOrNull() ?: return anchor
    if (run.sources.isEmpty()) return anchor
    val layout = measurer.measure(run.text, typography.body, placeholders = run.placeholders, constraints = Constraints(maxWidth = widthPx))
    val local = (yPx - run.topPx).coerceIn(0, (layout.size.height - 1).coerceAtLeast(0))
    val line = layout.getLineForVerticalPosition(local.toFloat())
    val display = layout.getLineStart(line)
    var at = 0
    for (slice in run.sources) {
        val length = slice.endUtf16 - slice.startUtf16
        if (display < at + length) return BookPageAnchor(slice.importPage, slice.startUtf16 + (display - at))
        at += length
    }
    val last = run.sources.last()
    return BookPageAnchor(last.importPage, last.startUtf16)
}

/** 原文位置在本页里的纵向像素（页面坐标，从纸面顶算起）；锚点在图上或不在本页文字里时给 null。 */
internal fun BookLaidPage.yOfAnchor(anchor: BookPageAnchor, measurer: TextMeasurer, typography: BookPageTypography, widthPx: Int): Int? {
    if (anchor.visualOrder != null) return null
    for (run in runs) {
        var at = 0
        for (slice in run.sources) {
            val length = slice.endUtf16 - slice.startUtf16
            if (slice.importPage == anchor.importPage && anchor.offsetUtf16 in slice.startUtf16 until slice.endUtf16) {
                val display = at + (anchor.offsetUtf16 - slice.startUtf16)
                val layout = measurer.measure(run.text, typography.body, placeholders = run.placeholders, constraints = Constraints(maxWidth = widthPx))
                val line = layout.getLineForOffset(display.coerceAtMost((run.text.length - 1).coerceAtLeast(0)))
                return run.topPx + layout.getLineTop(line).toInt()
            }
            at += length
        }
    }
    return null
}

/** 排版页上正文区域内某一点 (xPx, yPx) 对应的原文偏移（xPx/yPx 均相对正文起点）。 */
internal fun BookLaidPage.textPositionAt(
    xPx: Float,
    yPx: Float,
    rendered: Map<Int, TextLayoutResult>,
): BookPageAnchor? {
    val run = runs.lastOrNull { it.topPx <= yPx } ?: runs.firstOrNull() ?: return null
    if (run.sources.isEmpty() || run.text.isEmpty()) return null
    val layout = rendered[run.topPx] ?: return null
    val localX = xPx.coerceIn(0f, layout.size.width.toFloat())
    val localY = (yPx - run.topPx).coerceIn(0f, (layout.size.height - 1).coerceAtLeast(0).toFloat())
    val display = layout.getOffsetForPosition(Offset(localX, localY)).coerceIn(0, run.text.length)
    var at = 0
    for (slice in run.sources) {
        val length = slice.endUtf16 - slice.startUtf16
        if (display <= at + length) return BookPageAnchor(slice.importPage, slice.startUtf16 + (display - at))
        at += length
    }
    val last = run.sources.last()
    return BookPageAnchor(last.importPage, last.endUtf16)
}

/** 将纸面坐标上的拖选起止点换算为对应书页的 [SourceBlock]（支持 EPUB/Word/TXT/OCR/PDF 文字版）。 */
internal fun BookLaidPage.selectionBetween(
    book: Textbook,
    down: Offset,
    up: Offset,
    insetPx: Float,
    topMarginPx: Float,
    rendered: Map<Int, TextLayoutResult>,
): SourceBlock? {
    val a = textPositionAt(down.x - insetPx, down.y - topMarginPx, rendered) ?: return null
    val b = textPositionAt(up.x - insetPx, up.y - topMarginPx, rendered) ?: return null
    val (pageNumber, rawStart, rawEnd) = if (a.importPage == b.importPage) {
        Triple(a.importPage, minOf(a.offsetUtf16, b.offsetUtf16), maxOf(a.offsetUtf16, b.offsetUtf16))
    } else {
        val first = if (a.importPage < b.importPage) a else b
        val storedLen = book.page(first.importPage)?.text?.length ?: return null
        Triple(first.importPage, first.offsetUtf16, storedLen)
    }
    val stored = book.page(pageNumber)?.text ?: return null
    var start = rawStart.coerceIn(0, stored.length)
    var end = rawEnd.coerceIn(0, stored.length)
    while (start < end && stored[start].isWhitespace()) start++
    while (end > start && stored[end - 1].isWhitespace()) end--
    if (end <= start) return null
    val text = stored.substring(start, end)
    return SourceBlock("${book.id}:p$pageNumber:$start", pageNumber, start, end, false, text)
}

/** 将纸面坐标上的单点长按换算为对应书页词/短语的 [SourceBlock]（支持中文分词与英文整词）。 */
internal fun BookLaidPage.selectionAt(
    book: Textbook,
    point: Offset,
    insetPx: Float,
    topMarginPx: Float,
    rendered: Map<Int, TextLayoutResult>,
): SourceBlock? {
    if (point.x < insetPx - 8f || point.y < topMarginPx - 8f) return null
    val localY = point.y - topMarginPx
    val lastRun = runs.lastOrNull() ?: return null
    val lastLayout = rendered[lastRun.topPx] ?: return null
    if (point.x > insetPx + lastLayout.size.width + 8f) return null
    if (localY > lastRun.topPx + lastLayout.size.height + 48f) return null
    val anchor = textPositionAt(point.x - insetPx, localY, rendered) ?: return null
    val stored = book.page(anchor.importPage)?.text ?: return null
    if (stored.isEmpty()) return null
    var offset = anchor.offsetUtf16.coerceIn(0, stored.lastIndex)
    if (stored[offset].isWhitespace()) {
        var right = offset
        while (right < stored.length && stored[right].isWhitespace() && stored[right] != '\n') right++
        var left = offset
        while (left > 0 && stored[left].isWhitespace() && stored[left] != '\n') left--
        offset = when {
            right < stored.length && !stored[right].isWhitespace() -> right
            !stored[left].isWhitespace() -> left
            else -> return null
        }
    }
    if (!stored[offset].isLetterOrDigit()) {
        when {
            offset > 0 && stored[offset - 1].isLetterOrDigit() -> offset -= 1
            offset + 1 < stored.length && stored[offset + 1].isLetterOrDigit() -> offset += 1
        }
    }
    val words = java.text.BreakIterator.getWordInstance(java.util.Locale.CHINA)
    words.setText(stored)
    var start = if (words.isBoundary(offset)) offset else words.preceding(offset).let { if (it == java.text.BreakIterator.DONE) offset else it }
    var end = words.following(offset).let { if (it == java.text.BreakIterator.DONE) (offset + 1).coerceAtMost(stored.length) else it }
    while (start < end && stored[start].isWhitespace()) start++
    while (end > start && stored[end - 1].isWhitespace()) end--
    if (end <= start) {
        start = offset
        end = (offset + 1).coerceAtMost(stored.length)
        if (stored[start].isWhitespace()) return null
    }
    val text = stored.substring(start, end)
    return SourceBlock("${book.id}:p${anchor.importPage}:$start", anchor.importPage, start, end, false, text)
}
