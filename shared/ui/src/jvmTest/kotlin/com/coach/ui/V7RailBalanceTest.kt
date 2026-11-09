package com.coach.ui
import com.coach.ui.v7.balancedMarginOffsets
import kotlin.test.*

class V7RailBalanceTest {
    @Test fun notesKeepTheirAnchorsWhenTheyFit() {
        assertEquals(listOf(20, 100, 220), balancedMarginOffsets(listOf(20, 100, 220), listOf(50, 70, 40), 12, 400))
    }
    @Test fun bottomRebalanceFitsAllNotesWithoutOverlap() {
        val heights = listOf(80, 120, 90)
        val ys = balancedMarginOffsets(listOf(150, 230, 300), heights, 12, 350)
        assertTrue(ys.first() >= 0)
        assertTrue(ys.last() + heights.last() <= 350)
        assertTrue(ys.zipWithNext().withIndex().all { (i, pair) -> pair.second >= pair.first + heights[i] + 12 })
    }
    @Test fun crowdedNotesKeepTheirOrderAndNeverOverlap() {
        val ys = balancedMarginOffsets(listOf(0, 10, 20), listOf(200, 200, 200), 12, 300)
        assertEquals(0, ys.first())
        assertTrue(ys.zipWithNext().all { (a, b) -> b >= a + 212 })
    }
}
