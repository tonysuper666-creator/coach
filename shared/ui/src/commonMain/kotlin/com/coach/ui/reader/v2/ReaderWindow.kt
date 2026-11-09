package com.coach.ui.reader.v2

import kotlin.math.min

/** Coordinates in content-local dp; never device model pixels. */
data class ReaderPane(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

enum class ReaderArrangement { COMPACT, EXPANDED, BOOK, TABLETOP }
data class ReaderFold(val bounds: ReaderPane, val vertical: Boolean, val separating: Boolean)
data class ReaderWindow(val width: Float, val height: Float, val fold: ReaderFold? = null) {
    init { require(width.isFinite() && height.isFinite() && width > 0 && height > 0) }
}
data class ReaderPanes(val arrangement: ReaderArrangement, val page: ReaderPane, val questions: ReaderPane?)

fun readerPanes(window: ReaderWindow, railVisible: Boolean): ReaderPanes {
    val w = window.width
    val h = window.height
    val fold = window.fold?.takeIf { it.separating }
    val b = fold?.bounds
    if (fold != null && b != null && listOf(b.left, b.top, b.right, b.bottom).all { it.isFinite() }) {
        if (fold.vertical && b.top <= 0 && b.bottom >= h && b.left > 0 && b.right < w && b.right >= b.left) {
            // Equal readable panes on either side of the actual runtime reserved region.
            val half = min(b.left, w - b.right)
            return ReaderPanes(ReaderArrangement.BOOK, ReaderPane(b.right, 0f, b.right + half, h),
                ReaderPane(b.left - half, 0f, b.left, h))
        }
        if (!fold.vertical && b.left <= 0 && b.right >= w && b.top > 0 && b.bottom < h && b.bottom >= b.top) {
            return ReaderPanes(ReaderArrangement.TABLETOP, ReaderPane(0f, 0f, w, b.top),
                ReaderPane(0f, b.bottom, w, h))
        }
    }
    if (w < 600f) return ReaderPanes(ReaderArrangement.COMPACT, ReaderPane(0f, 0f, w, h), null)
    val rail = if (railVisible) min(200f, w * .28f) else 0f
    return ReaderPanes(ReaderArrangement.EXPANDED, ReaderPane(0f, 0f, w - rail, h),
        if (rail > 0) ReaderPane(w - rail, 0f, w, h) else null)
}
