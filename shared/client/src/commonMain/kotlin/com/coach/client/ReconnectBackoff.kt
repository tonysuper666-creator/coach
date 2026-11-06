package com.coach.client

import kotlin.random.Random

/**
 * 重连等待：指数增长、封顶、带抖动。只有上一条连接稳定用过 [stableMs] 才回到起点——
 * 「连上就清零」会让连上即被踢的连接（如服务端推来客户端吃不下的帧）每 250ms 重连一次。
 */
class ReconnectBackoff(
    private val baseMs: Long = 500,
    private val maxMs: Long = 10_000,
    private val stableMs: Long = 10_000,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    /** 一次连接结束后调用；[upMs] 是这次连接可用的时长（没连上为 0）。返回下次重连前等待的毫秒数。 */
    fun next(upMs: Long): Long {
        if (upMs >= stableMs) attempt = 0
        val ceiling = (baseMs shl attempt.coerceAtMost(MAX_SHIFT)).coerceAtMost(maxMs)
        if (ceiling < maxMs) attempt++
        // 一半固定、一半随机：多台设备不会同时撞上服务端，等待的下限仍逐次翻倍。
        return ceiling / 2 + random.nextLong(ceiling - ceiling / 2 + 1)
    }

    private companion object { const val MAX_SHIFT = 20 }
}
