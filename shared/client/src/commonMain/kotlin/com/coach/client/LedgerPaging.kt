package com.coach.client

/** One page of a ledger read: [throughSeq] is where the next read starts, [hasMore] says another page exists. */
data class LedgerPage<E>(val events: List<E>, val throughSeq: Long, val hasMore: Boolean)

/** The ledger could not be read to its end; the caller must fail rather than accept a partial answer. */
class LedgerPagingException(message: String) : IllegalStateException(message)

/**
 * 从 [after] 读到账本末端，按到达顺序返回全部事件。
 *
 * 服务端 `/events?after=&limit=` 一页最多 500 条（`LedgerStore.page` 把 limit 收在 1..500），
 * 所以只看第一页会漏掉刚写入的题：09:53 那次副本已有 705 条、新的三题在 seq 702–704，
 * 只读 after=0 的第一页就只能找到 0 道。验收回放必须跟着 [LedgerPage.throughSeq] 翻到 `hasMore=false`。
 *
 * 两种情况停下来就是漏题，所以抛 [LedgerPagingException] 而不是返回半份账本：
 * `hasMore=true` 却给了空页；或 `throughSeq` 没有越过当前游标（重复页，继续读只会空转）。
 */
fun <E> drainLedger(after: Long, fetch: (Long) -> LedgerPage<E>): List<E> {
    require(after >= 0) { "ledger cursor must not be negative: $after" }
    val all = ArrayList<E>()
    var cursor = after
    while (true) {
        val page = fetch(cursor)
        if (page.events.isEmpty()) {
            if (page.hasMore) throw LedgerPagingException("empty ledger page after seq $cursor while hasMore=true")
            return all
        }
        all += page.events
        if (!page.hasMore) return all
        if (page.throughSeq <= cursor) throw LedgerPagingException(
            "ledger page after seq $cursor did not advance the cursor (throughSeq=${page.throughSeq})")
        cursor = page.throughSeq
    }
}
