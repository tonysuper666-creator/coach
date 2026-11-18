package com.coach.ui.pageturn

import kotlin.math.abs

/**
 * 滚动阅读的位置标记：第几页（和 [PageTurnState.position] 同一套下标）加页内进度 0..1。
 * 翻页和滚动互切时，调用方把它换成自己的句子锚点；组件只保证页和页内进度不丢。
 */
data class ScrollReadPosition(val page: Int, val fraction: Float = 0f)

/** 页内像素偏移 -> 进度 0..1。页高未知（0）时给 0。 */
fun scrollFraction(offsetPx: Int, pageHeightPx: Int): Float =
    if (pageHeightPx <= 0) 0f else (offsetPx.toFloat() / pageHeightPx).coerceIn(0f, 1f)

/** 页内进度 -> 页内像素偏移。 */
fun scrollOffsetFor(fraction: Float, pageHeightPx: Int): Int = (fraction.coerceIn(0f, 1f) * pageHeightPx).toInt()

/** 空格 / PageDown 滚一屏：留 10% 重叠，让眼睛接得上上一屏最后一行。 */
fun screenScrollPx(viewportPx: Int): Float = viewportPx * 0.9f

/** 翻页位置 -> 滚动位置：翻页时左页下标就是该滚到的页，进度 0。 */
fun PageTurnState.toScrollPosition(): ScrollReadPosition = ScrollReadPosition(position, 0f)

/** 滚动位置 -> 翻页位置：双页时取偶数左页。书页不要求偶数对齐的调用方（alignToSpread=false）传 align=false。 */
fun ScrollReadPosition.toPageTurnPosition(pageCount: Int, columns: Int, align: Boolean = true): Int =
    normalizePosition(page, pageCount, columns, align)

/** 两个滚动位置是否指向同一处（页相同、进度差不超过 tolerance）。 */
fun ScrollReadPosition.sameSpot(other: ScrollReadPosition, tolerance: Float = 0.02f): Boolean =
    page == other.page && abs(fraction - other.fraction) <= tolerance
