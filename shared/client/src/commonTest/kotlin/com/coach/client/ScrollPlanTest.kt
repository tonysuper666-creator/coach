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
        private val dragWorks: Boolean = false,
        startOffset: Int = 0,
    ) {
        var offset = startOffset
            private set
        /** 旧实现看到的“布局变了”帧数：吸附回弹也算，和真机 trace 里每轮都不一样。 */
        var layoutChanges = 0
            private set
        private var jitterUp = false
        private val cardTops = heights.runningFold(0) { sum, h -> sum + h }
        private val footerY = heights.sum() + 40
        private val maxOffset = (heights.sum() + FOOTER_HEIGHT - viewport).coerceAtLeast(0)
        /** 目标卡不在本页时给一个永远到不了的位置：驱动必须靠预算退出。 */
        private val targetY = if (card in 1..heights.size) cardTops[card - 1] + targetOffsetInCard else heights.sum() + 9_000

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
            if (!pageScrollWorks) return
            offset = (if (forward) offset + viewport else offset - viewport).coerceIn(0, maxOffset)
        }

        /** 手指拖 40% 屏高。吸附让它原地回弹：布局变了，题号没动。 */
        fun drag(forward: Boolean) {
            layoutChanges++
            if (!dragWorks) {
                offset = (offset + if (jitterUp) -16 else 16).coerceIn(0, maxOffset)
                jitterUp = !jitterUp
                return
            }
            offset = (offset + viewport * 4 / 10 * (if (forward) 1 else -1)).coerceIn(0, maxOffset)
        }

        fun nudge(down: Boolean) {
            offset = (offset + if (down) 120 else -120).coerceIn(0, maxOffset)
        }

        fun drive(driver: ScrollDriver, budget: Int = 40): Pair<Boolean, List<ScrollMove>> {
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
        assertTrue(moves.size <= 5, "必须有界退出：$moves")
        val stop = moves.last()
        assertTrue(stop is ScrollMove.Unreachable, "要以 Unreachable 结束：$stop")
        val reason = (moves.last() as ScrollMove.Unreachable).reason
        assertTrue(reason.contains("推不动"), reason)
        assertTrue(reason.contains("[1]"), "证据里要写清当时可见的题号：$reason")
    }

    /** 目标已经可见却压在输入条下：先请它让开，让不开就按贴近的边缘点，而不是继续翻页。 */
    @Test fun targetUnderTheComposerIsNudgedThenTapped() {
        val driver = ScrollDriver(card = 3)
        val low = ScrollView(targetFound = true, targetCenterY = 1_990, pageTop = 400, pageBottom = 2_000, visibleCards = listOf(3))
        assertEquals(ScrollMove.NudgeDown, driver.next(low))
        val settled = low.copy(targetCenterY = 1_800)
        assertEquals(ScrollMove.Tap, driver.next(settled, moved = true))
    }

    @Test fun attemptBudgetStopsARunawayPage() {
        val page = FakePage(card = 99)
        val (found, moves) = page.drive(ScrollDriver(card = 99, maxAttempts = 6))
        assertFalse(found)
        assertTrue(moves.size <= 7, "预算用完就退出：$moves")
        assertTrue(moves.last() is ScrollMove.Unreachable)
    }
}
