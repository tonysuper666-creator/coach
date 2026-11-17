package com.coach.ui.pageturn

import kotlin.math.abs
import kotlin.math.min

/** 窗口宽度达到此值（dp）时摊成双页。分界以 TON-1654「书页感」一节为准，那节没定之前用 1400。 */
const val PAGE_TURN_SPREAD_MIN_DP = 1400

/** 宽度 dp -> 一次可见的页数（1 或 2）。 */
fun pageTurnColumns(widthDp: Float, spreadMinDp: Int = PAGE_TURN_SPREAD_MIN_DP): Int = if (widthDp >= spreadMinDp) 2 else 1

/** 拖页角：起点到当前横向位移换算成翻页进度 0..1。forward 时向左拖为正。 */
fun dragProgress(startX: Float, x: Float, forward: Boolean, travelPx: Float): Float {
    if (travelPx <= 0f) return 0f
    val d = if (forward) startX - x else x - startX
    return (d / travelPx).coerceIn(0f, 1f)
}

/** 松手：过半翻过去；或速度够快且方向同向也翻；反向快甩则回弹。velocity 沿翻页方向为正，px/s。 */
fun shouldCompleteTurn(progress: Float, velocityAlongTurn: Float, flingPxPerSec: Float = 900f): Boolean =
    when {
        velocityAlongTurn >= flingPxPerSec -> progress > 0.05f
        velocityAlongTurn <= -flingPxPerSec -> false
        else -> progress >= 0.5f
    }

/** 位置规整：双页时第一页下标取偶数，总在 [0, last] 内。 */
fun normalizePosition(position: Int, pageCount: Int, columns: Int): Int {
    if (pageCount <= 0) return 0
    val p = position.coerceIn(0, pageCount - 1)
    return if (columns == 2) p - p % 2 else p
}

/** 前后翻一步后的位置；到头返回 null。 */
fun stepPosition(position: Int, pageCount: Int, columns: Int, forward: Boolean): Int? {
    if (forward) {
        val next = position + columns
        return if (next >= pageCount) null else normalizePosition(next, pageCount, columns)
    }
    return if (position <= 0) null else normalizePosition(position - columns, pageCount, columns)
}

/** 长距离跳转时画几张翻过的页：最多 maxLeaves，距离 0 返回 0。 */
fun riffleLeaves(from: Int, to: Int, columns: Int, maxLeaves: Int = 6): Int {
    val steps = abs(to - from) / columns
    return if (steps == 0) 0 else min(steps, maxLeaves)
}

/** 翻转角度（度）。forward = 右页绕书脊向左，0..-180；单页只翻到 90° 再淡出。 */
fun leafAngle(progress: Float, forward: Boolean, columns: Int): Float {
    val full = if (columns == 2) 180f else 90f
    val a = progress.coerceIn(0f, 1f) * full
    return if (forward) -a else a
}

/** 翻起页是否已转过 90°（此时看到的是背面）。 */
fun showingBack(progress: Float, columns: Int): Boolean = columns == 2 && progress > 0.5f

/** 翻起页投在下面那页上的影的强度 0..1，在 0.5 处最强。 */
fun castShadowAlpha(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return 1f - abs(p - 0.5f) * 2f
}
