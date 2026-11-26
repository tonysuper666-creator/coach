package com.coach.desktop.books

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TON-1905：翻到下一组时节末检查卡出现、读区变矮，整章按新高度重排；翻回时检查卡收起又排回原样。
 * 先翻下一组再翻回，必须落在同一组对开（插图那张纸还在屏上），不能按重排后的页表退到前一组。
 */
class BookPagingTurnBackTest {
    private fun sheet(vararg slices: Triple<Int, Int, Int>) = BookLaidPage(
        sources = slices.map { (page, start, end) -> BookPageSlice(page, start, end) },
        chapter = 3, title = null, chapterNumber = null, opener = false, text = AnnotatedString(""),
        placeholders = emptyList(), topPx = 0, heightPx = 1000,
    )

    /** 读区原高度下的页表：figure 那张纸（第 [page] 页 39 起，带插图）和上一张同在落点那组对开里。 */
    private fun tall(page: Int) = listOf(
        sheet(Triple(page - 1, 0, 3222)),
        sheet(Triple(page - 1, 3222, 4000), Triple(page, 0, 39)),
        sheet(Triple(page, 39, 400), Triple(page + 1, 0, 43)), // 插图纸
        sheet(Triple(page + 1, 43, 900)),
        sheet(Triple(page + 1, 900, 1800)),
        sheet(Triple(page + 1, 1800, 2600)),
    )

    /** 读区矮了约 182dp 后的页表：同一章断页全变，插图挤到后一张。 */
    private fun short(page: Int) = listOf(
        sheet(Triple(page - 1, 0, 2500)),
        sheet(Triple(page - 1, 2500, 4000), Triple(page, 0, 20)),
        sheet(Triple(page, 20, 397)),
        sheet(Triple(page, 397, 400), Triple(page + 1, 0, 120)), // 插图纸
        sheet(Triple(page + 1, 120, 700)),
        sheet(Triple(page + 1, 700, 1300)),
        sheet(Triple(page + 1, 1300, 1900)),
    )

    /** 视图每次组合后做的事：登记这一组的首页下标和页表身份。 */
    private fun BookPagingState.show(laid: List<BookLaidPage>): List<BookLaidPage> {
        pages = laid; layoutPages = laid
        val first = firstFor(laid, columns)
        groupFirst = first; groupPages = laid
        return laid.subList(first, minOf(laid.size, first + columns))
    }

    private fun assertNextThenBackLandsOnSameSpread(page: Int) {
        val state = BookPagingState(BookPageAnchor(page)).apply { columns = 2 }
        val landed = state.show(tall(page))
        assertEquals(tall(page).subList(1, 3), landed, "第 $page 页落点那组对开含插图纸")

        state.move(ReaderTurn.Next)
        state.show(tall(page))
        // 下一组翻到后节末检查卡出现，读区变矮，整章重排。
        val next = state.show(short(page))
        assert(next != landed)

        state.move(ReaderTurn.Previous)
        state.show(short(page))
        // 翻回本节，检查卡收起，读区恢复原高度。
        val back = state.show(tall(page))
        assertEquals(landed, back, "第 $page 页先翻下一组再翻回，应停在同一组对开")
    }

    @Test fun page77NextThenBackKeepsTheFigureSpreadAcrossARelayout() = assertNextThenBackLandsOnSameSpread(77)

    @Test fun page298NextThenBackKeepsTheFigureSpreadAcrossARelayout() = assertNextThenBackLandsOnSameSpread(298)

    @Test fun twoTurnsForwardAndTwoBackRetraceTheSameSpreads() {
        val state = BookPagingState(BookPageAnchor(77)).apply { columns = 2 }
        val a = state.show(tall(77))
        state.move(ReaderTurn.Next); val b = state.show(tall(77))
        state.move(ReaderTurn.Next); state.show(short(77))
        state.move(ReaderTurn.Previous); assertEquals(b, state.show(tall(77)))
        state.move(ReaderTurn.Previous); assertEquals(a, state.show(tall(77)))
    }

    @Test fun aJumpBetweenTurnsDropsTheTrailAndPreviousTurnsFromTheNewSpot() {
        val state = BookPagingState(BookPageAnchor(77)).apply { columns = 2 }
        state.show(tall(77))
        state.move(ReaderTurn.Next); state.show(tall(77))
        state.anchor = BookPageAnchor(78, 1900) // 跳到别处
        state.show(tall(77))
        state.move(ReaderTurn.Previous)
        assertEquals(tall(77).subList(3, 5), state.show(tall(77)), "跳转后上一页按当前位置减一组")
    }
}
