package com.coach.ai

import java.io.InputStream
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次进行中的流式调用。`cancel()` 会断开 HTTP 请求——桥看到客户端断开就会杀掉 CLI 整个进程组
 * （ADR-0002 第 2 条 / §5.5），所以这里不需要单独一条「杀进程」的通道。
 */
class BridgeStreamHandle internal constructor() {
    @Volatile internal var future: Future<*>? = null
    @Volatile internal var body: InputStream? = null
    private val cancelled = AtomicBoolean(false)

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        cancelled.set(true)
        runCatching { body?.close() }
        runCatching { future?.cancel(true) }
    }
}

/**
 * 流式回调。`atMs` 是从请求发出起算的单调钟毫秒数（§4.7：进程内超时一律单调钟），
 * 首字口径用**第一次 [onDelta]** 的时刻，不含 [onMeta]（served_by 在出正文前就到了）。
 */
interface BridgeStreamSink {
    fun onMeta(servedBy: String) {}
    fun onDelta(text: String, atMs: Long) {}
    fun onTerminal(result: BridgeResult) {}
}

/** 一次流式回合的终态判定，供上层写 `messages.status`（§4.2：与桥 `x_coach` 对应）。 */
object StreamStatus {
    const val COMPLETE = "complete"
    const val TRUNCATED = "truncated"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    /** 桥的 `x_coach.status` 一一映射；没有终态帧 = 截断（ADR-0002）。 */
    fun fromBridge(status: String?, cancelled: Boolean, hasText: Boolean): String = when {
        cancelled -> CANCELLED
        status == null -> TRUNCATED
        status == COMPLETE -> COMPLETE
        status == TRUNCATED -> TRUNCATED
        status == "failed" -> if (hasText) TRUNCATED else FAILED
        else -> FAILED
    }
}
