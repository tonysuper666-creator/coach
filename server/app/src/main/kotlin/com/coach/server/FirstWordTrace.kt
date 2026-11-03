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
    @Synchronized fun finish(status: String, queueMs: Long?, bridgeTtftMs: Long?, rid: String? = null): String =
        "COACH first_word " + json(
            "msg" to message, "rid" to safeRequestId(rid), "parent_msg" to parent, "attempt" to if (parent == null) "primary" else "fallback",
            "prepared_ms" to prepared, "bridge_start_ms" to bridge, "raw_first_ms" to raw,
            "visible_first_ms" to visible, "end_ms" to now(),
            "status" to status.takeIf { it in setOf("complete", "failed", "cancelled", "truncated") }.orEmpty(),
            "bridge_queue_ms" to queueMs?.takeIf { it >= 0 }, "bridge_ttft_ms" to bridgeTtftMs?.takeIf { it >= 0 },
        ).toString()
}

/** Keep request IDs joinable without writing arbitrary bridge response text to logs. */
internal fun safeRequestId(value: String?): String? =
    value?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) }

/** Bridge reasons can contain HTTP response bodies, transport exceptions and URLs. */
internal fun safeDiagnosticReason(value: String?): String? = when {
    value == null -> null
    value in setOf("empty_visible_body", "rate_limited", "first_token_timeout", "cancelled",
        "timeout", "cli_exit", "max_output_tokens", "client_gone", "exam_protection",
        "test_traffic_forbidden", "untrusted_source", "total_timeout", "stall",
        "source_lookup_timeout", "no_x_coach", "unparsable_response") -> value
    value.matches(Regex("http_[1-5][0-9]{2}(:.*)?", RegexOption.DOT_MATCHES_ALL)) -> value.substringBefore(':')
    value.startsWith("transport:") -> "transport"
    value.startsWith("internal:") -> "internal"
    else -> "other"
}
