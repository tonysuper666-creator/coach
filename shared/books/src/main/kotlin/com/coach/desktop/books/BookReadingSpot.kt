package com.coach.desktop.books

import androidx.compose.ui.geometry.Offset
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
