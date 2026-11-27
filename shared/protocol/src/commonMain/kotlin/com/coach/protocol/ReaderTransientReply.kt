package com.coach.protocol

/**
 * TON-1963: sentences the reader service returns, with HTTP 200 and no sources, when one model attempt broke off
 * (no first token, the stream stalled, the bridge failed, or the JSON could not be checked). The same request
 * usually succeeds on a fresh attempt, so the Windows client retries these once by itself before showing them.
 * The server builds its replies from these constants; changing a sentence here changes both sides together.
 */
object ReaderTransientReply {
    const val FIRST_TIMEOUT = "讲解正文迟迟未开始，已停止等待。可继续阅读或手动重试。"
    const val GAP_TIMEOUT = "讲解正文中途停止更新，已结束本次追问。可继续阅读或手动重试。"
    const val UNFINISHED = "追问未完成，暂时无法确认。可返回阅读或手动重试。"
    const val UNCHECKABLE = "回答缺少可核对的页码依据，暂时无法确认。"
    const val UNAVAILABLE = "依据读取或追问暂不可用，无法确认。可继续阅读。"
    /** Too many readers waiting at once; a short pause before the second try. */
    const val BUSY = "追问正在处理，请稍后手动重试。"
    const val TOTAL_TIMEOUT = "讲解已达到本次总时长上限，已停止。可继续阅读或手动重试。"

    /** The total-time limit is not listed: a second full attempt would only double the wait. */
    val retryable: Set<String> = setOf(FIRST_TIMEOUT, GAP_TIMEOUT, UNFINISHED, UNCHECKABLE, UNAVAILABLE, BUSY)

    fun isRetryable(text: String): Boolean = text.trim() in retryable
}
