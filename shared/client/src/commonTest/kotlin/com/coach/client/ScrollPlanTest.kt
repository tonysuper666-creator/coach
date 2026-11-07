package com.coach.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 13:36 OPPO 的失败形状：第 2 题出现后，找第 3 题时 `drag #0..#39` 全是 `forward=true cards=[2]`，
 * 可见题号一直停在 2，最后撞上 420 秒超时。`review-trace.txt` 显示每轮布局都在变（旧代码因此
 * 一直以为“动了”），但题号从没前进——进展被量错了对象，而且只用手指拖这一个手段。
 *
 * 这里用合成题页复现同一形状：三张长卡、容器带吸附，手指拖 40% 屏高只换来十几像素回弹。
 * 旧的拖动策略在上面用满 40 次也到不了第 3 题；新驱动让容器自己翻页，几步就到；
 * 两个手段都推不动时有界退出，并把当时可见的题号带进证据。
 *
 * 15:35 QA-C 复核追加的两类用例：
 * - 锚点本身（[anchorsOf] / [movedBy]）必须单测，不能只测驱动决策：长卡中段题号行滚出视口、
 *   固定页眉不随内容移动，旧口径「整棵树前 6 个文本」会把真实翻页看成停滞。
 * - 回退不能一次探空就永久失效，手指拖也不能只剩两次机会。
 */
class ScrollPlanTest {
    /** 合成题页：内容坐标从 0 起，视口 1600，页眉从 400 开始。 */
    private class FakePage(
        private val card: Int,
        private val heights: List<Int> = listOf(2100, 2600, 1700),
        private val viewport: Int = 1600,
        private val pageTop: Int = 400,
        private val targetOffsetInCard: Int = 900,
        private val pageScrollWorks: Boolean = true,
        /** 前几次翻页动作故意不生效：模拟「列表到头」或探测误差，之后恢复。 */
        private val pageWorksAfter: Int = 0,
        private val dragWorks: Boolean = false,
        private val dragStep: Int = viewport * 4 / 10,
        startOffset: Int = 0,
        private val footerTarget: Boolean = false,
    ) {
        var offset = startOffset
            private set
        /** 旧实现看到的“布局变了”帧数：吸附回弹也算，和真机 trace 里每轮都不一样。 */
        var layoutChanges = 0
            private set
        var pageCalls = 0
            private set
        var dragCalls = 0
            private set
        private var jitterUp = false
        private val cardTops = heights.runningFold(0) { sum, h -> sum + h }
        private val footerY = heights.sum() + 40
        private val maxOffset = (heights.sum() + FOOTER_HEIGHT - viewport).coerceAtLeast(0)
        /** 目标卡不在本页时给一个永远到不了的位置：驱动必须靠预算退出。 */
        private val targetY = if (footerTarget) footerY
            else if (card in 1..heights.size) cardTops[card - 1] + targetOffsetInCard else heights.sum() + 9_000

        fun view(): ScrollView {
            val top = offset
            val bottom = offset + viewport
            return ScrollView(
                targetFound = targetY in top until bottom,
                targetCenterY = pageTop + (targetY - offset),
                pageTop = pageTop,
                pageBottom = pageTop + viewport,
                visibleCards = heights.indices.filter { cardTops[it] + 40 in top until bottom }.map { it + 1 },
                atFooter = footerY in top until bottom,
                pageScrollable = true,
            )
        }

        /** 屏幕上能看到的锚点位置：卡头、目标行、底部提交栏。 */
        fun signature(): List<Int> =
            heights.indices.map { cardTops[it] + 40 - offset } + listOf(targetY - offset, footerY - offset)

        /** 容器自身的翻页动作：整屏推进，和 ACTION_SCROLL_FORWARD 一样诚实。 */
        fun scrollPage(forward: Boolean) {
            pageCalls++
            if (!pageScrollWorks || pageCalls <= pageWorksAfter) return
            offset = (if (forward) offset + viewport else offset - viewport).coerceIn(0, maxOffset)
        }

        /** 手指拖 40% 屏高。吸附让它原地回弹：布局变了，题号没动。 */
        fun drag(forward: Boolean) {
            dragCalls++
            layoutChanges++
            if (!dragWorks) {
                offset = (offset + if (jitterUp) -16 else 16).coerceIn(0, maxOffset)
                jitterUp = !jitterUp
                return
            }
            offset = (offset + dragStep * (if (forward) 1 else -1)).coerceIn(0, maxOffset)
        }

        fun nudge(down: Boolean) {
            offset = (offset + if (down) 120 else -120).coerceIn(0, maxOffset)
        }

        fun drive(driver: ScrollDriver, budget: Int = 60): Pair<Boolean, List<ScrollMove>> {
            val moves = mutableListOf<ScrollMove>()
            var moved: Boolean? = null
            repeat(budget) {
                val before = signature()
                val move = driver.next(view(), moved)
                moves += move
                when (move) {
                    ScrollMove.Tap -> return true to moves
                    ScrollMove.NudgeDown -> nudge(down = true)
                    ScrollMove.NudgeUp -> nudge(down = false)
                    is ScrollMove.ScrollPage -> scrollPage(move.forward)
                    is ScrollMove.Drag -> drag(move.forward)
                    is ScrollMove.Unreachable -> return false to moves
                }
                moved = movedBy(before, signature())
            }
            error("滚动驱动没有在 $budget 步内结束：$moves")
        }

        companion object {
            const val FOOTER_HEIGHT = 220
        }
    }

    /** 修前的策略：只用手指拖，方向看题号，拖不动就掉头，最多 40 次；进展 = 「布局变了」。 */
    private class LegacyPolicy(private val page: FakePage) {
        var tries = 0
            private set

        fun dragTo(card: Int): Boolean {
            var forward = true
            repeat(40) {
                tries++
                val view = page.view()
                if (view.targetFound) return true
                val cards = view.visibleCards
                forward = if (cards.isNotEmpty()) cards.max() <= card && !(cards.max() == card && view.atFooter) else forward
                val before = page.signature()
                page.drag(forward)
                if (!movedBy(before, page.signature())) {
                    forward = !forward
                    page.drag(forward)
                }
            }
            return false
        }
    }

    // ---- 旧策略与容器翻页 ----------------------------------------------------

    @Test fun legacyDragPolicyNeverReachesTheThirdCard() {
        // 13:36 那一刻：第 2 题在屏幕上，目标在第 3 题，页面被吸附在卡 2 附近。
        val page = FakePage(card = 3, startOffset = 2040)
        val found = LegacyPolicy(page).dragTo(card = 3)
        assertFalse(found, "旧策略在合成页面上本来就没找到第 3 题")
        assertEquals(listOf(2), page.view().visibleCards, "可见题号一直停在 2，与 review-trace.txt 一致")
        assertTrue(page.layoutChanges >= 40, "每轮布局都在变，旧实现因此从不判定“卡住”")
        assertFalse(movedBy(listOf(100, 200), listOf(116, 184)), "十六像素的回弹不是进展")
    }

    @Test fun pagingReachesTheThirdCardWithoutFingerDrags() {
        val page = FakePage(card = 3)
        val (found, moves) = page.drive(ScrollDriver(card = 3))
        assertTrue(found, "让容器自己翻页就能到第 3 题：$moves")
        assertTrue(page.view().targetFound)
        assertTrue(moves.size <= 4, "只该翻 2–3 屏：$moves")
        assertEquals(0, moves.count { it is ScrollMove.Drag }, "容器能翻页时不该退回手指拖：$moves")
    }

    @Test fun transientFirstPageFailureStillReachesThirdCard() {
        // 首次翻页被吸附吞掉，下一次即可恢复；手指拖始终无效。
        val page = FakePage(card = 3, pageWorksAfter = 1, dragWorks = false)
        val (found, moves) = page.drive(ScrollDriver(card = 3))
        assertTrue(found, "一次翻页探空后应再次翻页并到达第 3 题：$moves")
        assertEquals(0, page.dragCalls, "不能因一次探空就转入无效的手指拖：$moves")
    }

    @Test fun gradingFooterStillReachableAfterOldLayoutRejectsFirstScroll() {
        // 真实失败：提交时已在旧题页底部，首个向下动作不动；判分反馈随后撑高三张卡。
        // 摘要在新页末尾，通用文字搜索掉头后会永远停在页首。
        fun page() = FakePage(card = 3, footerTarget = true, startOffset = 3900, pageWorksAfter = 1)
        assertFalse(page().drive(ScrollDriver()).first, "旧的无题号文字搜索会在首次探空后掉头而漏掉摘要")
        val page = page()
        val (found, moves) = page.drive(ScrollDriver(towardEnd = true))
        assertTrue(found, "判分摘要应在异步重排后仍可找到：$moves")
        assertTrue(moves.filterIsInstance<ScrollMove.ScrollPage>().all { it.forward },
            "已知页尾目标不能因旧布局的一次探空而反向搜索：$moves")
    }

    @Test fun dragsRemainTheFallbackWhenTheContainerCannotPage() {
        val page = FakePage(card = 3, pageScrollWorks = false, dragWorks = true)
        val (found, moves) = page.drive(ScrollDriver(card = 3))
        assertTrue(found, "翻页动作不动时要退回手指拖：$moves")
        assertTrue(moves.any { it is ScrollMove.Drag })
    }

    @Test fun bothMechanismsStalledGivesUpBoundedWithEvidence() {
        val page = FakePage(card = 3, pageScrollWorks = false, dragWorks = false)
        val (found, moves) = page.drive(ScrollDriver(card = 3))
        assertFalse(found, "页面推不动就不能假装在进展")
        assertTrue(moves.size <= 8, "必须有界退出：$moves")
        val stop = moves.last()
        assertTrue(stop is ScrollMove.Unreachable, "要以 Unreachable 结束：$stop")
        val reason = (moves.last() as ScrollMove.Unreachable).reason
        assertTrue(reason.contains("推不动") || reason.contains("没有进展"), reason)
        assertTrue(reason.contains("[1]"), "证据里要写清当时可见的题号：$reason")
    }

    // ---- 15:35 复核：回退不能只剩两次机会 ------------------------------------

    /** 手指拖这条路径在真机上用过 36 次，回退后只给两三次机会等于不可用。 */
    @Test fun fingerDragFallbackGetsFarMoreThanTwoChances() {
        val page = FakePage(card = 3, pageScrollWorks = false, dragWorks = true, dragStep = 200)
        val (found, moves) = page.drive(ScrollDriver(card = 3))
        assertTrue(found, "手指拖要能一直拖到目标：$moves")
        val drags = moves.count { it is ScrollMove.Drag }
        assertTrue(drags >= 10, "这条路径要给出足够机会，而不是拖两次就判死：$drags")
        assertTrue(drags <= ScrollDriver.DRAG_LIMIT, "但仍然有界：$drags")
    }

    /** 一次探空可能只是列表到头/探测误差，不能把翻页整个会话判死。 */
    @Test fun oneDeadPageProbeDoesNotDisablePagingForTheSession() {
        val page = FakePage(card = 3, pageWorksAfter = 2, dragWorks = true, dragStep = 200)
        val (found, moves) = page.drive(ScrollDriver(card = 3))
        assertTrue(found, "回退几步后要能再试翻页并成功：$moves")
        assertTrue(moves.first() is ScrollMove.ScrollPage, "一开始就该先试翻页：$moves")
        assertTrue(moves.drop(3).any { it is ScrollMove.ScrollPage }, "判死之后要回试翻页：$moves")
        assertTrue(moves.any { it is ScrollMove.Drag }, "两次翻页之间要有回退动作：$moves")
    }

    @Test fun pagingBudgetStopsARunawayPage() {
        val page = FakePage(card = 99, dragWorks = true, dragStep = 200)
        val (found, moves) = page.drive(ScrollDriver(card = 99, pageLimit = 3, dragLimit = 2))
        assertFalse(found)
        assertTrue(moves.size <= 6, "预算用完就退出：$moves")
        assertTrue(moves.last() is ScrollMove.Unreachable)
    }

    /** 目标已经可见却压在输入条下：先请它让开，让不开就按贴近的边缘点，而不是继续翻页。 */
    @Test fun targetUnderTheComposerIsNudgedThenTapped() {
        val driver = ScrollDriver(card = 3)
        val low = ScrollView(targetFound = true, targetCenterY = 1_990, pageTop = 400, pageBottom = 2_000, visibleCards = listOf(3))
        assertEquals(ScrollMove.NudgeDown, driver.next(low))
        val settled = low.copy(targetCenterY = 1_800)
        assertEquals(ScrollMove.Tap, driver.next(settled, moved = true))
    }

    // ---- 15:35 复核：锚点必须只取容器内的行 ----------------------------------

    /** 2600px 长卡滚到中段：题号行已出视口，页眉固定不动。 */
    private val midCardRows = listOf(
        RowText("← 今天", top = 8, centerY = 30),                            // 固定页眉
        RowText("错题 3 道", top = 40, centerY = 60),                        // 固定页眉
        RowText("共 3 题", top = 90, centerY = 110),                         // 固定页眉
        RowText("第 2 / 3 题", top = -760, centerY = -720),                  // 题号行滚出视口
        RowText("A transaction or reallocation of resources", top = 520, centerY = 560),
        RowText("B …", top = 760, centerY = 800),
        RowText("正确答案：A", top = 1_200, centerY = 1_240),
        RowText("提交本组答案", top = 1_880, centerY = 1_920),
    )

    @Test fun anchorsIgnoreTheFixedHeaderAndUseRowsInsideThePage() {
        val anchors = anchorsOf(midCardRows, pageTop = 400, pageBottom = 2_000)
        assertEquals(emptyList(), anchors.cards, "题号行不在视口里，就不该冒出一个题号锚点")
        assertEquals(listOf(520, 760, 1_200, 1_880), anchors.tops, "锚点只能是容器内的行：${anchors.tops}")
        assertFalse(anchors.tops.contains(40), "固定页眉不能进锚点集合：${anchors.tops}")
    }

    @Test fun aFixedHeaderAsAnchorWouldHideRealPaging() {
        // 内容整体上移 120px（一次真翻页），页眉那几行一动不动。
        val afterRows = midCardRows.map {
            if (it.centerY in 400..2_000) it.copy(top = it.top - 120, centerY = it.centerY - 120) else it
        }
        val before = anchorsOf(midCardRows, 400, 2_000)
        val after = anchorsOf(afterRows, 400, 2_000)
        assertTrue(movedBy(before, after), "容器内的行上移 120px 就是前进：$before -> $after")

        // 旧的回退口径：整棵树可见文本的前几个。它们全是固定页眉，永远“没动”。
        val chromeOnly = Anchors(emptyList(), listOf(8, 40, 90))
        assertFalse(movedBy(chromeOnly, chromeOnly), "固定页眉当锚点永远看不出翻页：$chromeOnly")
    }

    @Test fun anchorsFollowTheCardHeaderWhileItIsVisible() {
        val rows = listOf(
            RowText("第 2 / 3 题", top = 420, centerY = 460),
            RowText("A …", top = 700, centerY = 740),
            RowText("第 3 / 3 题", top = 1_500, centerY = 1_540),
        )
        val anchors = anchorsOf(rows, pageTop = 400, pageBottom = 2_000)
        assertEquals(listOf(2, 3), anchors.cards)
        assertEquals(listOf(420, 1_500), anchors.tops, "有题号行时就用题号行做锚点：${anchors.tops}")
    }

    @Test fun turningThePageCountsAsMovement() {
        val before = Anchors(cards = listOf(2), tops = listOf(420))
        val after = Anchors(cards = listOf(3), tops = listOf(420))
        assertTrue(movedBy(before, after), "换了题号就是换了内容，不能因为坐标相同算停滞")
    }

    @Test fun unknownAnchorsNeverCountAsAStall() {
        assertTrue(movedBy(Anchors(), Anchors()), "没有锚点就无从判断，不能因此判停滞")
        assertTrue(movedBy(Anchors(tops = listOf(500)), Anchors()), "一侧没有锚点同样不算停滞")
        assertTrue(movedBy(emptyList(), listOf(500)), "位置列表为空也不算停滞")
    }
}
