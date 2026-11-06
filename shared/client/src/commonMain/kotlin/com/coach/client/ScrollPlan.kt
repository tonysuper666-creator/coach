package com.coach.client

import kotlin.math.abs

/**
 * 验收驱动在长题列表里滚动的决策与「真的前进了吗」的判据。
 *
 * 13:36 的 OPPO 证据（`~/Coach/state/ton1146/oppo-20261106-b/review-trace.txt`）：
 * 第 2 题拖了 36 次才点中；找第 3 题时 `drag #0..#39` 全是 `forward=true cards=[2]`，
 * 可见题号一直停在 2，最后撞上 420 秒超时。旧实现把「任意一帧布局变了」当作前进，
 * 而手指拖 40% 屏高在带吸附/回弹的题页里会原地往返：布局每帧都在变，题号却不动，
 * 于是一次都不认输；每轮又要整树两遍比对（实测约 2.7 秒），三次重试就超过 420 秒。
 *
 * 这里把方向、手段与终止条件从设备代码里抽出来：一次观察 = 一次决策，
 * 进展只认题页内锚点的位移，页面推不动就有界退出，可以让假页面确定性地复现该缺陷。
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
 * 换回下一个动作。
 *
 * 两种手段各有自己的预算，不再共用一个 `maxAttempts`：容器翻页从第 1 题到第 3 题
 * 只需 3–5 次（1600px 视口、单卡 ≤2600px），[pageLimit] 给两倍余量；13:36 的手指拖
 * 真实用过 36 次，回退路径若只剩两三次机会等于这条路径不可用，所以在时间预算内
 * 给到 [dragLimit]=30 次。两条路径都推不动、或预算用完，才 [ScrollMove.Unreachable]。
 */
class ScrollDriver(
    private val card: Int? = null,
    private val pageLimit: Int = PAGE_LIMIT,
    private val dragLimit: Int = DRAG_LIMIT,
    private val stallLimit: Int = STALL_LIMIT,
) {
    private var pageTries = 0
    private var dragTries = 0
    private var pageStalls = 0
    private var dragStalls = 0
    /** 翻页连续不动才判「容器不认翻页」，一次探空可能只是列表到头或探测误差。 */
    private var pageBroken = false
    /** 判死之后每隔几步回试一次翻页：列表换页、换题后翻页动作可能又好用了。 */
    private var movesSinceProbe = 0
    private var forward = true
    private var nudges = 0
    private var reversed = false
    private var previous: ScrollMove? = null

    fun next(view: ScrollView, moved: Boolean? = null): ScrollMove {
        if (moved != null) {
            if (moved) {
                pageStalls = 0
                dragStalls = 0
                if (previous is ScrollMove.ScrollPage) pageBroken = false
            } else when (previous) {
                is ScrollMove.ScrollPage -> {
                    pageStalls++
                    if (pageStalls >= PAGE_STALLS) pageBroken = true
                }
                is ScrollMove.Drag -> dragStalls++
                else -> {}
            }
        }
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
        if (pageBroken && dragStalls >= stallLimit)
            return unreachable(view, "翻页不动，手指拖连续 $stallLimit 次也没有进展")
        val probe = pageBroken && movesSinceProbe >= REPROBE_EVERY
        if (pageTries < pageLimit && (!pageBroken || probe)) {
            pageTries++
            movesSinceProbe = 0
            return take(ScrollMove.ScrollPage(forward))
        }
        if (dragTries < dragLimit) {
            dragTries++
            movesSinceProbe++
            return take(ScrollMove.Drag(forward))
        }
        return unreachable(view, "翻页 $pageTries 次、手指拖 $dragTries 次仍没到达目标")
    }

    /** 题号说方向：目标卡还没出现就往下；已经是目标卡且到了底部才回头找别的东西。
     *  没有题号可依时（列表里的说明文字）先往下，推不动就掉头试一次，只试一次。 */
    private fun direction(view: ScrollView): Boolean {
        val cards = view.visibleCards
        if (card == null) {
            if (stallsSeen() > 0 && !reversed) {
                reversed = true
                return !forward
            }
            return forward
        }
        if (cards.isEmpty()) return forward
        val max = cards.max()
        return max <= card && !(max == card && view.atFooter)
    }

    private fun stallsSeen() = maxOf(pageStalls, dragStalls)

    private fun take(move: ScrollMove): ScrollMove {
        previous = move
        return move
    }

    private fun unreachable(view: ScrollView, why: String): ScrollMove {
        previous = null
        val where = if (card == null) "目标不在本题页" else "第 $card 题"
        return ScrollMove.Unreachable("$why；$where；可见题号 ${view.visibleCards}；页 ${view.pageTop}-${view.pageBottom}")
    }

    companion object {
        const val PAGE_LIMIT = 10
        const val DRAG_LIMIT = 30
        const val STALL_LIMIT = 3
        private const val PAGE_STALLS = 2
        private const val REPROBE_EVERY = 3
        private const val INSET = 60
        private const val LOOSE_TOP = 20
        private const val LOOSE_BOTTOM = 30
        private const val NUDGE_LIMIT = 3
    }
}

/** 一次树里的可见文本行：屏幕坐标 + 是否可见。 */
data class RowText(val text: String, val top: Int, val centerY: Int, val visible: Boolean = true)

/** 一次观察里能用来判断位移的锚点：题号行或容器内文本行的屏幕 top。 */
data class Anchors(val cards: List<Int> = emptyList(), val tops: List<Int> = emptyList())

val CardHeader = Regex("第 (\\d+) / \\d+ 题")

fun cardNumberIn(text: String): Int? = CardHeader.find(text)?.groupValues?.get(1)?.toIntOrNull()

/**
 * 从整棵树里挑锚点：**只取落在题页（容器）内的行**。
 *
 * 固定页眉、返回栏、计数条不随内容移动，把它们当锚点会把真实前进误判成停滞
 * （2600px 长卡滚到中段时题号行已经滚出视口，旧实现退回「整棵树前 6 个文本」，
 * 那 6 个恰好全是页眉）。容器里有题号行时用题号行，题号行全部出视口时改用
 * 容器内文本行——它们随内容一起走，位移才是真位移。
 */
fun anchorsOf(rows: List<RowText>, pageTop: Int, pageBottom: Int, limit: Int = 8): Anchors {
    val inside = rows.filter { it.visible && it.centerY in pageTop..pageBottom }
    val cardTops = ArrayList<Int>()
    val cards = ArrayList<Int>()
    val textTops = ArrayList<Int>()
    for (row in inside) {
        cardNumberIn(row.text)?.let { cards.add(it); cardTops.add(row.top) }
        if (textTops.size < limit) textTops.add(row.top)
    }
    return if (cardTops.isNotEmpty()) Anchors(cards, cardTops) else Anchors(emptyList(), textTops)
}

/**
 * 两次探测之间题页真的前进了吗。只认两个锚点集合里 ≥[tolerance] 像素的位移。
 *
 * 集合本身不可比时按「动了」处理，绝不因此判停滞：换页/换题会换掉整组锚点，
 * 题号行滚出视口时锚点为空，这些都不该被算成「推不动」。
 */
fun movedBy(before: Anchors, after: Anchors, tolerance: Int = 50): Boolean {
    if (before.tops.isEmpty() || after.tops.isEmpty()) return true
    if (before.cards != after.cards) return true
    return movedBy(before.tops, after.tops, tolerance)
}

/**
 * 位置列表版本：尺寸不同（换页/换题）或任一锚点移动 ≥[tolerance] 即算前进。
 * 13:36 的旧实现用「任意一帧布局不同」当进展，吸附回弹的十几像素抖动因此被当成前进。
 */
fun movedBy(before: List<Int>, after: List<Int>, tolerance: Int = 50): Boolean {
    if (before.isEmpty() || after.isEmpty()) return true
    if (before.size != after.size) return true
    return before.indices.any { abs(after[it] - before[it]) >= tolerance }
}
