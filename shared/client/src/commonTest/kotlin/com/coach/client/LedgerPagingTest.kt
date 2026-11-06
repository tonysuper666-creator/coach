package com.coach.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 验收回放要在大账本里找到刚出的题：服务端 `/events?after=&limit=` 默认一页 500 条，
 * 09:53 那次副本已有 705 条、新的三题在 seq 702–704，只读第一页就只能看到空组。
 * 这里用假账本复现该形状：一页 500 条，只有继续按 `throughSeq` 翻页才到得了末端。
 */
class LedgerPagingTest {
    /** 与服务器 `page()` 同形：有下一页时 throughSeq 是本页最后一条，末页时是账本末端。 */
    private class FakeLedger(private val seqs: IntRange, private val pageSize: Int) {
        var reads = 0
        private set

        fun fetch(after: Long): LedgerPage<Int> {
            reads++
            val remaining = seqs.filter { it > after }
            val page = remaining.take(pageSize)
            return LedgerPage(page, if (page.isEmpty()) after else page.last().toLong(), remaining.size > page.size)
        }
    }

    @Test fun readsPastTheFirstPageToTheNewQuestions() {
        val ledger = FakeLedger(1..705, pageSize = 500)
        val all = drainLedger(0L, ledger::fetch)
        assertEquals(705, all.size)
        assertEquals(listOf(702, 703, 704), all.filter { it in 702..704 })
        assertEquals(2, ledger.reads, "705 条要翻两页才到末端")
    }

    @Test fun singlePageLedgerNeedsOneRead() {
        val ledger = FakeLedger(1..3, pageSize = 500)
        assertEquals(listOf(1, 2, 3), drainLedger(0L, ledger::fetch))
        assertEquals(1, ledger.reads)
    }

    /** 服务端说还有下一页却给空页：停在这里就是漏题，必须报错退出而不是返回半份账本。 */
    @Test fun emptyPageThatClaimsMoreIsAnError() {
        var reads = 0
        assertFailsWith<LedgerPagingException> {
            drainLedger(500L) { reads++; LedgerPage(emptyList<Int>(), 500, true) }
        }
        assertEquals(1, reads)
    }

    /** 游标不再前进（重复页）：继续读只会空转，必须报错。 */
    @Test fun pageThatDoesNotAdvanceTheCursorIsAnError() {
        var reads = 0
        assertFailsWith<LedgerPagingException> {
            drainLedger(0L) { reads++; LedgerPage(listOf(1), 1, true) }
        }
        assertEquals(2, reads)
    }

    @Test fun negativeCursorIsRejected() {
        assertFailsWith<IllegalArgumentException> { drainLedger(-1L) { LedgerPage(emptyList<Int>(), 0, false) } }
    }
}
