package com.coach.server

import com.coach.domain.json

/** All offsets are from the same command receipt and monotonic clock; absent events stay null. */
class FirstWordTrace(
    private val message: String,
    private val parent: String?,
    private val received: Long,
    private val nano: () -> Long = System::nanoTime,
) {
    private var prepared: Long? = null
    private var bridge: Long? = null
    private var raw: Long? = null
    private var visible: Long? = null
    private fun now() = (nano() - received) / 1_000_000
    @Synchronized fun prepared() { if (prepared == null) prepared = now() }
    @Synchronized fun bridge() { if (bridge == null) bridge = now() }
    @Synchronized fun raw(text: String) { if (raw == null && text.isNotEmpty()) raw = now() }
    @Synchronized fun visible(text: String) { if (visible == null && text.isNotEmpty()) visible = now() }
    @Synchronized fun finish(status: String, queueMs: Long?, bridgeTtftMs: Long?): String =
        "COACH first_word " + json(
            "msg" to message, "parent_msg" to parent, "attempt" to if (parent == null) "primary" else "fallback",
            "prepared_ms" to prepared, "bridge_start_ms" to bridge, "raw_first_ms" to raw,
            "visible_first_ms" to visible, "end_ms" to now(),
            "status" to status.takeIf { it in setOf("complete", "failed", "cancelled", "truncated") }.orEmpty(),
            "bridge_queue_ms" to queueMs?.takeIf { it >= 0 }, "bridge_ttft_ms" to bridgeTtftMs?.takeIf { it >= 0 },
        ).toString()
}
