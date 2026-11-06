package com.coach.client

import kotlin.math.abs

/**
 * 验收驱动在长题列表里滚动的决策。
 *
 * 13:36 的 OPPO 证据（`~/Coach/state/ton1146/oppo-20261106-b/review-trace.txt`）：
 * 第 2 题拖了 36 次才点中；找第 3 题时 `drag #0..#39` 全是 `forward=true cards=[2]`，
 * 可见题号一直停在 2，最后撞上 420 秒超时。旧实现把「任意一帧布局变了」当作前进，
 * 而手指拖 40% 屏高在带吸附/回弹的题页里会原地往返：布局每帧都在变，题号却不动，
 * 于是一次都不认输；每轮又要整树两遍比对（实测约 2.7 秒），三次重试就超过 420 秒。
 *
 * 这里把方向、手段与终止条件从设备代码里抽出来：一次观察 = 一次决策，
 * 进展只认题号与目标位置，页面推不动就有界退出，可以让假页面确定性地复现该缺陷。
 */
data class ScrollView(
    /** 目标是否真的可见（`isVisibleToUser`）；只是被组合出来不算。 */
    val targetFound: Boolean = false,
    val targetCenterY: Int = 0,
    val pageTop: Int = 0,
    val pageBottom: Int = 0,
    /** 屏幕上真正可见的题号（1 起）。 */
    val visibleCards: List<Int> = emptyList(),
    /** 题页底部那行「提交本组答案 / 选完 3 题后提交」是否可见。 */
    val atFooter: Boolean = false,
    /** 页面上是否有可滚动容器。 */
    val pageScrollable: Boolean = true,
)

sealed class ScrollMove {
    data object Tap : ScrollMove()
    data object NudgeDown : ScrollMove()
    data object NudgeUp : ScrollMove()

    /** 让容器自己翻一屏（`ACTION_SCROLL_FORWARD/BACKWARD`），不依赖手指拖多远。 */
    data class ScrollPage(val forward: Boolean) : ScrollMove()

    /** 手指拖动，只在容器不认翻页动作时使用。 */
    data class Drag(val forward: Boolean) : ScrollMove()

    /** 有界退出：页面再也推不动，带证据失败，绝不再空转。 */
    data class Unreachable(val reason: String) : ScrollMove()
}

/**
 * 一次滚动会话：调用方每轮给一次 [ScrollView] 与上一次动作是否真的移动了内容，
 * 换回下一个动作。[maxAttempts] 与 [stallLimit] 保证任何页面都不会空转。
 */
class ScrollDriver(
    private val card: Int? = null,
    private val maxAttempts: Int = 12,
    private val stallLimit: Int = 3,
) {
    private var attempts = 0
    private var forward = true
    private var stalls = 0
    private var nudges = 0
    private var reversed = false
    private var pageScrollBroken = false
    private var previous: ScrollMove? = null

    fun next(view: ScrollView, moved: Boolean? = null): ScrollMove {
        if (moved != null) {
            if (moved) {
                stalls = 0
            } else {
                stalls++
                // 容器自己的翻页动作都不动，就别再指望它，改用手指拖。
                if (previous is ScrollMove.ScrollPage) pageScrollBroken = true
            }
        }
        attempts++
        if (attempts > maxAttempts) return unreachable(view, "尝试 $maxAttempts 次仍没到达目标")
        if (view.targetFound) {
            val inside = view.targetCenterY >= view.pageTop + INSET && view.targetCenterY <= view.pageBottom - INSET
            if (inside) {
                nudges = 0
                return take(ScrollMove.Tap)
            }
            // 目标被组合出来但压在页眉或输入条下：先请它自己滚进来，推不动再按贴近的边缘点。
            val loose = view.targetCenterY >= view.pageTop + LOOSE_TOP && view.targetCenterY <= view.pageBottom - LOOSE_BOTTOM
            nudges++
            if (nudges > NUDGE_LIMIT) {
                if (loose) return take(ScrollMove.Tap)
                return unreachable(view, "目标始终停在题页之外（y=${view.targetCenterY}，页 ${view.pageTop}-${view.pageBottom}）")
            }
            return take(if (view.targetCenterY > view.pageBottom - INSET) ScrollMove.NudgeDown else ScrollMove.NudgeUp)
        }
        nudges = 0
        forward = direction(view)
        // 推不动就换一次方向（内容可能被甩过了头），但方向本身不重置计数：两个方向都不动就退出。
        if (stalls >= stallLimit) return unreachable(view, "题页连续 $stallLimit 次推不动")
        val move = if (!pageScrollBroken && view.pageScrollable) ScrollMove.ScrollPage(forward) else ScrollMove.Drag(forward)
        return take(move)
    }

    /** 题号说方向：目标卡还没出现就往下；已经是目标卡且到了底部才回头找别的东西。
     *  没有题号可依时（列表里的说明文字）先往下，推不动就掉头试一次，只试一次。 */
    private fun direction(view: ScrollView): Boolean {
        val cards = view.visibleCards
        if (card == null) {
            if (stalls > 0 && !reversed) {
                reversed = true
                return !forward
            }
            return forward
        }
        if (cards.isEmpty()) return forward
        val max = cards.max()
        return max <= card && !(max == card && view.atFooter)
    }

    private fun take(move: ScrollMove): ScrollMove {
        previous = move
        return move
    }

    private fun unreachable(view: ScrollView, why: String): ScrollMove {
        previous = null
        val where = if (card == null) "目标不在本题页" else "第 $card 题"
        return ScrollMove.Unreachable("$why；$where；可见题号 ${view.visibleCards}；页 ${view.pageTop}-${view.pageBottom}")
    }

    private companion object {
        const val INSET = 60
        const val LOOSE_TOP = 20
        const val LOOSE_BOTTOM = 30
        const val NUDGE_LIMIT = 3
    }
}

/**
 * 两次探测之间题页真的前进了吗。只认 ≥[tolerance] 像素的位移：
 * 13:36 的旧实现用「任意一帧布局不同」当进展，吸附回弹的十几像素抖动因此被当成前进，
 * 40 次拖动里题号一次没动也不判卡住。采样点在窗口内稳定，滚动一屏必然远超这个阈值。
 */
fun movedBy(before: List<Int>, after: List<Int>, tolerance: Int = 50): Boolean {
    if (before.size != after.size) return true
    return before.indices.any { abs(after[it] - before[it]) >= tolerance }
}
